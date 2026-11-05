#!/usr/bin/env bash
# Content-free MCP audit log (v2) JVM harness.
#
# Compiles the REAL production classes McpSocketServer / McpDispatch /
# McpAuditLog against signature stubs (jvm-stubs/ + mcp-stubs/, the latter
# replacing the Android service, the logger and the socket by recording
# stand-ins) and runs:
#   * McpAuditLeakTest   - behavioural leak check on McpSocketServer.runTool
#                          (must be RED on the base writer, GREEN after the fix)
#   * McpAuditWiringTest - wiring/observe-act/diagnostics (new code only)
#   * McpAuditLogTest    - schema, modes, symlinks, rotation, concurrency
# plus mutation counter-proofs (a mutated production class must turn it red).
#
# Not a build: the real Android runtime, DEX and the real service stay CI
# material. Filesystem behaviour is the host JVM's POSIX one.
#
# Env:
#   MCPTEST_MCP_SRC  directory holding McpSocketServer.java / McpDispatch.java
#                 (and McpAuditLog.java if present); default: this repo.
#   MCPTEST_MODE     full (default) | leak  (leak = base check, leak test only)
#   MCPTEST_SKIP_MUT 1 = skip the mutation counter-proofs (used to show the new tests red on older code)
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
MCP_SRC="${MCPTEST_MCP_SRC:-$REPO/app/src/main/java/com/termux/app/mcp}"
MODE="${MCPTEST_MODE:-full}"
UNIT_TEST="$REPO/app/src/test/java/com/termux/app/mcp/McpAuditLogTest.java"
STUBS="$HERE/jvm-stubs"
WORK="$(mktemp -d /tmp/termuxai-mcpaudit.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

APPS="$WORK/apps"
mkdir -p "$APPS" "$WORK/stubs/com/termux/shared/termux" "$WORK/classes" "$WORK/tmp"
cat > "$WORK/stubs/com/termux/shared/termux/TermuxConstants.java" <<EOT
package com.termux.shared.termux;
public final class TermuxConstants {
    public static final class TERMUX_APP {
        public static final String APPS_DIR_PATH = "$APPS";
    }
    private TermuxConstants() {}
}
EOT

OVERLAY="$(find "$HERE/mcp-stubs" -name '*.java' | sort)"
MAIN="$MCP_SRC/McpSocketServer.java $MCP_SRC/McpDispatch.java"
[ -f "$MCP_SRC/McpAuditLog.java" ] && MAIN="$MAIN $MCP_SRC/McpAuditLog.java"
[ -f "$MCP_SRC/McpAuth.java" ] && MAIN="$MAIN $MCP_SRC/McpAuth.java"
[ -f "$MCP_SRC/McpFrameReader.java" ] && MAIN="$MAIN $MCP_SRC/McpFrameReader.java"

TESTS="$HERE/mcp-src/com/termux/app/mcp/McpAuditLeakTest.java"
CLASSES="com.termux.app.mcp.McpAuditLeakTest"
if [ "$MODE" = "full" ]; then
  TESTS="$TESTS $HERE/mcp-src/com/termux/app/mcp/McpAuthFixtures.java $HERE/mcp-src/com/termux/app/mcp/McpAuditWiringTest.java $UNIT_TEST"
  CLASSES="$CLASSES com.termux.app.mcp.McpAuditWiringTest com.termux.app.mcp.McpAuditLogTest"
fi

note "--- compile production MCP classes + tests (stubs), mode=$MODE ---"
# -sourcepath pulls the shared signature stubs on demand; the explicitly listed
# overlay classes (Logger, TermuxConstants, the service, ...) take precedence.
javac -encoding UTF-8 -d "$WORK/classes" -sourcepath "$STUBS" \
  "$WORK/stubs/com/termux/shared/termux/TermuxConstants.java" $OVERLAY \
  "$STUBS/RunTests.java" $MAIN $TESTS
[ $? -eq 0 ] || { note "FAIL: compilation"; exit 1; }
note "compile rc=0"

run_suite() { # $1 classes dir prefix, $2... classes
  local cp="$1"; shift
  rm -rf "$APPS"; mkdir -p "$APPS"
  TMPDIR="$WORK/tmp" java -Djava.io.tmpdir="$WORK/tmp" -cp "$cp" RunTests "$@"
}

note "--- run the audit suite ---"
run_suite "$WORK/classes" $CLASSES
suite_rc=$?
note "suite rc=$suite_rc"
[ "$suite_rc" -eq 0 ] || fail=1

if [ "$MODE" = "full" ] && [ "${MCPTEST_SKIP_MUT:-0}" != 1 ]; then
  mutate() { # $1 label, $2 sed expression, $3 test classes
    local label="$1" expr="$2"; shift 2
    local msrc="$WORK/mut-src" mdst="$WORK/mut-classes"
    rm -rf "$msrc" "$mdst"; mkdir -p "$msrc" "$mdst"
    sed "$expr" "$MCP_SRC/McpAuditLog.java" > "$msrc/McpAuditLog.java"
    if cmp -s "$msrc/McpAuditLog.java" "$MCP_SRC/McpAuditLog.java"; then
      note "FAIL: $label mutation did not change the source"; fail=1; return
    fi
    if ! javac -encoding UTF-8 -d "$mdst" -cp "$WORK/classes" "$msrc/McpAuditLog.java" 2>/dev/null; then
      note "FAIL: $label mutation did not compile"; fail=1; return
    fi
    run_suite "$mdst:$WORK/classes" "$@" >/dev/null 2>&1
    local rc=$?
    if [ "$rc" -eq 0 ]; then note "FAIL: $label stayed green"; fail=1
    else note "$label rc=$rc (red as required)"; fi
  }
  note "--- mutation 1/9: rotation check removed (cap would be exceeded) ---"
  mutate "no rotation" 's/if (size + line.length > MAX_FILE_BYTES) {/if (false) {/' com.termux.app.mcp.McpAuditLogTest
  note "--- mutation 2/9: raw tool name in the log ---"
  mutate "raw tool name" 's/        return TOOL_UNKNOWN;/        return name == null ? TOOL_UNKNOWN : name;/' com.termux.app.mcp.McpAuditLogTest com.termux.app.mcp.McpAuditLeakTest
  note "--- mutation 3/9: result copied into the outcome ---"
  mutate "raw result" 's/        if (result == null) return OUTCOME_ERROR;/        if (result != null) return result.length() > 200 ? result.substring(0, 200) : result.replace("\\n", " ");\n        if (result == null) return OUTCOME_ERROR;/' com.termux.app.mcp.McpAuditLogTest com.termux.app.mcp.McpAuditLeakTest
  note "--- mutation 4/9: symlinks followed (all three independent guards removed) ---"
  mutate "symlink followed" 's/if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new Fail(AUDIT_FILE_UNSAFE);//;s/opts.add(LinkOption.NOFOLLOW_LINKS);//;s/if (!hasMode(active, FILE_MODE)) throw new Fail(AUDIT_FILE_UNSAFE);//' com.termux.app.mcp.McpAuditLogTest
  note "--- mutation 5/9: previous generation not qualified ---"
  mutate "rotated unqualified" 's/checkRotated();//' com.termux.app.mcp.McpAuditLogTest
  note "--- mutation 6/9: previous generation size not checked ---"
  mutate "rotated size unchecked" 's/ || attrs.size() > MAX_FILE_BYTES//' com.termux.app.mcp.McpAuditLogTest
  note "--- mutation 7/9: broken tail not qualified ---"
  mutate "tail unqualified" 's/qualifyTail(ch);//' com.termux.app.mcp.McpAuditLogTest
  note "--- mutation 8/9: broken tail never cut back ---"
  mutate "tail not repaired" 's/hooks.truncate(append, keep);/if (keep < 0) hooks.truncate(append, keep);/' com.termux.app.mcp.McpAuditLogTest
  note "--- mutation 9/9: last whole line not checked against the schema ---"
  mutate "tail schema unchecked" 's/if (isSchemaLine(w, prev + 1, n - 1)) return;/if (true) return;/' com.termux.app.mcp.McpAuditLogTest
fi

note "mcp-audit harness rc=$fail"
exit "$fail"
