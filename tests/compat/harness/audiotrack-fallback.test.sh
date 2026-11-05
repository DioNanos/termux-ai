#!/usr/bin/env bash
# AudioTrack fallback suite: the audio.info AudioTrack enrichment fallback. Runs the
# REAL dispatch (TermuxAiSocketServer.processRequest) against the harness
# AudioTrack double (audiotrack-stubs/, platform surface only, behaviour
# steered by system properties — no framework hook): a recoverable
# measurement failure keeps the legacy six-key success, a fatal error
# (AssertionError) propagates instead of becoming that success, and a built
# track is released exactly once. Android-faithful behaviour stays CI
# material.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
MAIN="$REPO/app/src/main/java"
STUBS="$HERE/jvm-stubs"
DOUBLE="$HERE/audiotrack-stubs"
TEST_SRC="$HERE/audiotrack-src"
WORK="$(mktemp -d /tmp/termuxai-s1b1-at.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

MAIN_SOURCES="$(find "$MAIN/com/termux/app/terminal/ai" -name '*.java' | sort)"
STUB_SOURCES="$(find "$STUBS" -name '*.java' ! -path '*/android/media/AudioTrack.java' | sort)"

note "--- compile main closure + double + fallback test ---"
javac -d "$WORK" -encoding UTF-8 $STUB_SOURCES "$DOUBLE/android/media/AudioTrack.java" \
  $MAIN_SOURCES "$TEST_SRC/com/termux/app/terminal/ai/AudioTrackFallbackTest.java"
[ $? -eq 0 ] || { note "FAIL: compilation"; exit 1; }
note "compile rc=0"

note "--- run the AudioTrack fallback suite ---"
java -cp "$WORK" RunTests com.termux.app.terminal.ai.AudioTrackFallbackTest
suite_rc=$?
note "suite rc=$suite_rc"
[ "$suite_rc" -eq 0 ] || fail=1

note "--- mutation counter-proof: back to catch(Throwable) must fail ---"
# Rebuilding the previous behaviour (absorbing every Throwable) in a scratch
# copy of the server must turn this suite red: it proves the fatal-error
# assertion actually bites.
MUTDIR="$WORK/mut-src"
mkdir -p "$MUTDIR/com/termux/app/terminal/ai"
sed 's/} catch (Exception e) {/} catch (Throwable e) {/' \
  "$MAIN/com/termux/app/terminal/ai/TermuxAiSocketServer.java" \
  > "$MUTDIR/com/termux/app/terminal/ai/TermuxAiSocketServer.java"
mkdir -p "$WORK/mut"
javac -d "$WORK/mut" -encoding UTF-8 $STUB_SOURCES "$DOUBLE/android/media/AudioTrack.java" \
  $(printf '%s\n' $MAIN_SOURCES | sed "s|$MAIN/com/termux/app/terminal/ai/TermuxAiSocketServer.java||") \
  "$MUTDIR/com/termux/app/terminal/ai/TermuxAiSocketServer.java" \
  "$TEST_SRC/com/termux/app/terminal/ai/AudioTrackFallbackTest.java" 2>/dev/null
if [ $? -ne 0 ]; then
  note "FAIL: mutated server did not compile"
  fail=1
else
  java -cp "$WORK/mut" RunTests com.termux.app.terminal.ai.AudioTrackFallbackTest >/dev/null 2>&1
  if [ $? -eq 0 ]; then
    note "FAIL: catch(Throwable) mutation stayed green"
    fail=1
  else
    note "catch(Throwable) mutation rc=1 (red as required)"
  fi
fi

note "audiotrack-fallback rc=$fail"
exit "$fail"
