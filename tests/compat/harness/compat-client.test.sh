#!/usr/bin/env bash
# Compat-client JVM harness: drives the REAL parser and adapter of
# app/src/main/java/.../terminal/ai/compat/ through the controlled transport
# seam (FakeCompatTransport), because the old stub termux-am never exercised
# a Java LocalSocket client.
#
# It compiles the production compat classes plus the test against functional
# org.json / LocalSocket / JUnit stubs (jvm-stubs/) and runs every @Test.
# The platform org.json and a real socket boundary remain CI material.
#
# Negative counter-proof (mutation): flipping one expected stdout byte, one
# expected exit code or one expected stderr line in a scenario must turn this
# harness red — see the MUTATION section at the bottom.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
SRC="$REPO/app/src/main/java/com/termux/app/terminal/ai/compat"
TEST_SRC="$REPO/app/src/test/java/com/termux/app/terminal/ai/compat"
WORK="$(mktemp -d /tmp/termuxai-s1b1-jvm.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

note "--- compile production compat classes + tests (stubs) ---"
javac -d "$WORK" \
  "$HERE"/jvm-stubs/org/json/*.java \
  "$HERE"/jvm-stubs/org/junit/*.java \
  "$HERE"/jvm-stubs/org/junit/runner/*.java \
  "$HERE"/jvm-stubs/org/robolectric/*.java \
  "$HERE"/jvm-stubs/org/robolectric/annotation/*.java \
  "$HERE"/jvm-stubs/android/net/*.java \
  "$HERE"/jvm-stubs/RunTests.java \
  "$SRC"/*.java "$TEST_SRC"/*.java
[ $? -eq 0 ] || { note "FAIL: compilation"; exit 1; }
note "compile rc=0"

note "--- run the compat client JVM suite through the transport seam ---"
COMPAT_ROOT="$REPO/tests/compat"
java -Dtermuxai.compat.root="$COMPAT_ROOT" -cp "$WORK" RunTests \
  com.termux.app.terminal.ai.compat.TermuxApiCompatClientTest \
  com.termux.app.terminal.ai.compat.LocalSocketCompatTransportTest \
  com.termux.app.terminal.ai.compat.GoldenOracleCompatTest \
  com.termux.app.terminal.ai.compat.NativeFixtureContractTest
suite_rc=$?
note "suite rc=$suite_rc"
[ "$suite_rc" -eq 0 ] || fail=1

# Mutation counter-proofs: flipping ONE asserted property at a
# time — a payload byte, an expected exit code, an expected stderr line, one
# byte of a frozen golden — must each turn the suite red. Green under any of
# these mutations would mean the property is not actually asserted.
run_mutation() { # $1 label, $2 class to run, $3 extra java args...
  local label="$1"; shift
  local cls="$1"; shift
  java "$@" -cp "$WORK/mut:$WORK" RunTests "$cls" >/dev/null 2>&1
  local rc=$?
  if [ "$rc" -eq 0 ]; then
    note "FAIL: $label stayed green"
    fail=1
  else
    note "$label rc=$rc (red as required)"
  fi
}

compile_mutation() { # $1 label, $2 mutated source file
  local label="$1"
  local src="$2"
  rm -rf "$WORK/mut"; mkdir -p "$WORK/mut"
  if ! javac -d "$WORK/mut" -cp "$WORK" "$src" 2>/dev/null; then
    note "FAIL: $label did not compile"
    fail=1
    return 1
  fi
  return 0
}

note "--- mutation 1/5: one wrong payload byte in an expectation ---"
MUTDIR="$WORK/mut-src"
mkdir -p "$MUTDIR"
sed 's/\\"temperature\\": 29.0,/\\"temperature\\": 29.1,/' \
  "$TEST_SRC/TermuxApiCompatClientTest.java" > "$MUTDIR/TermuxApiCompatClientTest.java"
if compile_mutation "mutated payload expectation" "$MUTDIR/TermuxApiCompatClientTest.java"; then
  run_mutation "mutated payload expectation" com.termux.app.terminal.ai.compat.TermuxApiCompatClientTest
fi

note "--- mutation 2/5: one wrong expected exit code ---"
sed 's/assertEquals(1, r\.exit);/assertEquals(0, r.exit);/' \
  "$TEST_SRC/TermuxApiCompatClientTest.java" > "$MUTDIR/TermuxApiCompatClientTest.java"
if compile_mutation "mutated exit-code expectation" "$MUTDIR/TermuxApiCompatClientTest.java"; then
  run_mutation "mutated exit-code expectation" com.termux.app.terminal.ai.compat.TermuxApiCompatClientTest
fi

note "--- mutation 3/5: one wrong expected stderr line ---"
sed 's/assertEquals("E_BRIDGE_ERROR\\n", r.stderr);/assertEquals("E_CONNECT\\n", r.stderr);/' \
  "$TEST_SRC/TermuxApiCompatClientTest.java" > "$MUTDIR/TermuxApiCompatClientTest.java"
if compile_mutation "mutated stderr expectation" "$MUTDIR/TermuxApiCompatClientTest.java"; then
  run_mutation "mutated stderr expectation" com.termux.app.terminal.ai.compat.TermuxApiCompatClientTest
fi

note "--- mutation 4/5: one corrupted byte of a frozen golden ---"
# The golden file itself is the assertion: corrupting ONE value in a scratch
# copy of fixtures/golden (the real tree is untouched) must fail the oracle.
GROOT="$WORK/golden-root"
mkdir -p "$GROOT/fixtures/golden"
cp "$COMPAT_ROOT/MATRIX.md" "$GROOT/"
cp -r "$COMPAT_ROOT/fixtures/golden/compat" "$GROOT/fixtures/golden/"
cp -r "$COMPAT_ROOT/fixtures/golden/s1b1-native" "$GROOT/fixtures/golden/"
sed 's/"temperature": 29.0,/"temperature": 28.0,/' \
  "$GROOT/fixtures/golden/compat/BatteryStatus.json" > "$GROOT/battery.tmp" \
  && mv "$GROOT/battery.tmp" "$GROOT/fixtures/golden/compat/BatteryStatus.json"
rm -rf "$WORK/mut"; mkdir -p "$WORK/mut"
java -Dtermuxai.compat.root="$GROOT" -cp "$WORK" RunTests \
  com.termux.app.terminal.ai.compat.GoldenOracleCompatTest >/dev/null 2>&1
if [ $? -eq 0 ]; then
  note "FAIL: corrupted golden stayed green"
  fail=1
else
  note "corrupted golden rc=1 (red as required)"
fi

note "--- mutation 5/5: the budget re-read removed from the transport ---"
# Deleting the budget re-check before publishing a success
# must re-open the late-success hole and turn the transport suite red.
sed 's/if (deadline - clock\.nowMs() <= 0) {/if (false) {/' \
  "$SRC/LocalSocketCompatTransport.java" > "$MUTDIR/LocalSocketCompatTransport.java"
if compile_mutation "transport without the budget re-read" "$MUTDIR/LocalSocketCompatTransport.java"; then
  run_mutation "transport without the budget re-read" com.termux.app.terminal.ai.compat.LocalSocketCompatTransportTest
fi

note "compat-client harness rc=$fail"
exit "$fail"
