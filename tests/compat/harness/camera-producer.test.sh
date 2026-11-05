#!/usr/bin/env bash
# Camera producer-path suite: the camera backend path with controlled synthetic
# measurements. Runs the REAL producers (TermuxAiSocketServer cameraInfo /
# camera.torch dispatch) through the executable CameraCharacteristics double
# (camera-stubs/, platform surface only, identity-keyed synthetic get — no
# framework hook): facing and facing_id must derive from ONE LENS_FACING
# value, an unreadable lens is "unknown" without facing_id, and torch names
# the first camera that actually has a flash. Android-faithful behaviour
# stays CI material.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
MAIN="$REPO/app/src/main/java"
STUBS="$HERE/jvm-stubs"
DOUBLE="$HERE/camera-stubs"
TEST_SRC="$HERE/camera-src"
WORK="$(mktemp -d /tmp/termuxai-s1b1-camprod.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

MAIN_SOURCES="$(find "$MAIN/com/termux/app/terminal/ai" -name '*.java' | sort)"
STUB_SOURCES="$(find "$STUBS" -name '*.java' \
  ! -path '*/android/hardware/camera2/CameraCharacteristics.java' | sort)"

note "--- compile main closure + camera double + producer-path test ---"
javac -d "$WORK" -encoding UTF-8 $STUB_SOURCES \
  "$DOUBLE/android/hardware/camera2/CameraCharacteristics.java" \
  $MAIN_SOURCES "$TEST_SRC/com/termux/app/terminal/ai/CameraProducerPathTest.java"
[ $? -eq 0 ] || { note "FAIL: compilation"; exit 1; }
note "compile rc=0"

note "--- run the camera producer-path suite ---"
java -cp "$WORK" RunTests com.termux.app.terminal.ai.CameraProducerPathTest
suite_rc=$?
note "suite rc=$suite_rc"
[ "$suite_rc" -eq 0 ] || fail=1

note "--- mutation counter-proof: swapped facing constants must fail ---"
# Swapping LENS_FACING_FRONT/BACK values in a scratch copy of the double
# (the previous fixture contradiction, rebuilt on the producer side) must
# turn the suite red: it proves the coherence assertion actually bites.
MUTDIR="$WORK/mut-src"
mkdir -p "$MUTDIR/android/hardware/camera2"
sed -e 's/LENS_FACING_FRONT = 0/LENS_FACING_FRONT = 1/' \
    -e 's/LENS_FACING_BACK = 1/LENS_FACING_BACK = 0/' \
  "$DOUBLE/android/hardware/camera2/CameraCharacteristics.java" \
  > "$MUTDIR/android/hardware/camera2/CameraCharacteristics.java"
mkdir -p "$WORK/mut"
javac -d "$WORK/mut" -encoding UTF-8 $STUB_SOURCES \
  "$MUTDIR/android/hardware/camera2/CameraCharacteristics.java" \
  $MAIN_SOURCES "$TEST_SRC/com/termux/app/terminal/ai/CameraProducerPathTest.java" 2>/dev/null
if [ $? -ne 0 ]; then
  note "FAIL: mutated double did not compile"
  fail=1
else
  java -cp "$WORK/mut" RunTests com.termux.app.terminal.ai.CameraProducerPathTest >/dev/null 2>&1
  if [ $? -eq 0 ]; then
    note "FAIL: swapped-facing mutation stayed green"
    fail=1
  else
    note "swapped-facing mutation rc=1 (red as required)"
  fi
fi

note "camera-producer rc=$fail"
exit "$fail"
