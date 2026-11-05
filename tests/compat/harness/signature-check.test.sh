#!/usr/bin/env bash
# Signature check: compile ALL the main classes the compat layer added or
# touched — the 10 compat classes AND the enriched backend
# (TermuxAiSocketServer.java with its package + litert closure) — against
# signature stubs of every Android / org.json / com.termux.shared API they
# use, org.json's checked JSONException included.
#
# This is a SIGNATURE CHECK, NOT A BUILD: it proves the main sources compile
# against the stub contracts above — a missing checked exception or a wrong
# constant NAME is caught HERE, against these stubs. It does NOT prove the
# stubs are complete or faithful to the real SDK, and it does not execute,
# package or verify any Android behaviour: the runtime SDK guard, DEX, the
# real API surface and their interaction stay CI material.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
MAIN="$REPO/app/src/main/java"
STUBS="$HERE/jvm-stubs"
WORK="$(mktemp -d /tmp/termuxai-sigcheck.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

# Every Java main source of the terminal/ai package (compat client + backend
# closure); the Kotlin entry points (AICoreBackend, LiteRtLmRuntime, ...)
# are represented by the Java stand-ins in the stub tree.
MAIN_SOURCES="$(find "$MAIN/com/termux/app/terminal/ai" -name '*.java' | sort)"
# The MCP token and shim classes (no Android service dependency): the token epoch, the
# dispatcher and the app_process stdio bridge that uses android.net.LocalSocket.
MAIN_SOURCES="$MAIN_SOURCES
$MAIN/com/termux/app/mcp/McpAuth.java
$MAIN/com/termux/app/mcp/McpDispatch.java
$MAIN/com/termux/app/mcp/McpFrameReader.java
$MAIN/com/termux/app/mcp/McpStdioShim.java"
MAIN_COUNT="$(printf '%s\n' "$MAIN_SOURCES" | grep -c . )"
STUB_SOURCES="$(find "$STUBS" -name '*.java' ! -path '*/org/junit/*' ! -name RunTests.java | sort)"
STUB_COUNT="$(printf '%s\n' "$STUB_SOURCES" | grep -c . )"
note "main classes: $MAIN_COUNT · signature stubs: $STUB_COUNT"

javac -d "$WORK" -encoding UTF-8 $STUB_SOURCES $MAIN_SOURCES 2>"$WORK/javac.err"
rc=$?
if [ "$rc" -ne 0 ]; then
  note "FAIL: signature compilation rc=$rc"
  cat "$WORK/javac.err"
  fail=1
else
  note "signature compilation rc=0 (checked JSONException + constants against the stubs)"
fi

note "signature-check rc=$fail"
exit "$fail"
