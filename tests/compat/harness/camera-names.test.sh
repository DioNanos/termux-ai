#!/usr/bin/env bash
# Camera name tables: runs CameraInfoNamesTest — every
# value the pinned upstream switch knows (CameraInfoAPI.java:66-139 at blob
# 760c1777) must map to its exact name, and an unknown value must return null
# so the backend emits the raw NUMBER like the pin writer, never an invented
# "capability_N"/"CONTROL_AE_MODE_N" token.
#
# Compiles the production table class plus its test against the signature
# stubs (android.hardware.camera2.CameraMetadata with the AOSP constant
# values, org.junit) and runs every @Test.
#
# Negative counter-proof (mutation): renaming ONE known mapping in the
# production table must turn the suite red — it proves the assertions are
# live against the pin names, not tautologies on the table itself.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
MAIN="$REPO/app/src/main/java/com/termux/app/terminal/ai"
TEST_SRC="$REPO/app/src/test/java/com/termux/app/terminal/ai"
WORK="$(mktemp -d /tmp/termuxai-s1b1-cameranames.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

note "--- compile camera name tables + test (stubs) ---"
javac -d "$WORK" \
  "$HERE"/jvm-stubs/android/hardware/camera2/CameraMetadata.java \
  "$HERE"/jvm-stubs/org/junit/*.java \
  "$HERE"/jvm-stubs/RunTests.java \
  "$MAIN/CameraInfoNames.java" "$TEST_SRC/CameraInfoNamesTest.java"
[ $? -eq 0 ] || { note "FAIL: compilation"; exit 1; }
note "compile rc=0"

note "--- run the camera pin-name suite ---"
java -cp "$WORK" RunTests com.termux.app.terminal.ai.CameraInfoNamesTest
suite_rc=$?
note "suite rc=$suite_rc"
[ "$suite_rc" -eq 0 ] || fail=1

note "--- mutation counter-proof: a renamed mapping must fail ---"
# Corrupt exactly one production name: the suite must go red.
MUTDIR="$WORK/mut-src"
mkdir -p "$MUTDIR"
sed 's/return "CONTROL_AE_MODE_ON_AUTO_FLASH";/return "CONTROL_AE_MODE_ON_AUTO_FLASH_X";/' \
  "$MAIN/CameraInfoNames.java" > "$MUTDIR/CameraInfoNames.java"
mkdir -p "$WORK/mut"
javac -d "$WORK/mut" -cp "$WORK" "$MUTDIR/CameraInfoNames.java" 2>/dev/null
mut_compile_rc=$?
if [ "$mut_compile_rc" -ne 0 ]; then
  note "FAIL: mutated table did not compile"
  fail=1
else
  java -cp "$WORK/mut:$WORK" RunTests com.termux.app.terminal.ai.CameraInfoNamesTest >/dev/null 2>&1
  mut_rc=$?
  if [ "$mut_rc" -eq 0 ]; then
    note "FAIL: mutated table stayed green"
    fail=1
  else
    note "mutation rc=$mut_rc (red as required)"
  fi
fi

note "camera-names harness rc=$fail"
exit "$fail"
