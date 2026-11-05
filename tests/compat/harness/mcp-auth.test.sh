#!/usr/bin/env bash
# MCP token (verified per server epoch) + stdio shim JVM harness.
#
# Compiles the REAL production classes McpSocketServer / McpDispatch / McpAuth /
# McpAuditLog / McpStdioShim / TermuxAiCliInstaller against signature stubs
# (jvm-stubs/ + mcp-stubs/; the latter replaces the Android service, the logger and
# the socket manager by recording stand-ins that speak over REAL JDK unix-domain
# sockets) and runs:
#   * McpAuthBypassTest  - a connection without the token must get no session
#                          (RED on the base writer, GREEN after the fix)
#   * McpAuthTest        - token form, constant-time compare, publication, epochs
#   * McpDispatchAuthTest- per-connection auth, re-initialize, fixed diagnostics
#   * McpAuthWiringTest  - the real server: absent/bad/good, separate connections,
#                          restart with a changed token, publication error
#   * McpStdioShimTest   - the shim core: framing, limits, token insertion
#   * McpAuthE2ETest     - wrapper -> shim (JVM) -> real unix socket -> real server,
#                          direct socket negative, OLD socat shim fails
#   * InstallerShimTest  - the installed shim is the shipped asset, or gone
# plus mutation counter-proofs (a mutated production class must turn them red).
#
# NOT a build and NOT an Android proof: the real DEX/R8 keep, ART app_process, the
# platform LocalSocket and `pm path` are device-CI material. The wrapper is run with a fake
# pm/app_process pair; the shim main runs on a JDK, not on ART.
#
# Env:
#   MCPTEST_MCP_SRC  directory holding McpSocketServer.java / McpDispatch.java (and
#                 McpAuditLog.java / McpAuth.java / McpStdioShim.java when present);
#                 default: this repo.
#   MCPTEST_MODE     full (default) | bypass (base check: bypass test only)
#   MCPTEST_SKIP_MUT 1 = skip the mutation counter-proofs
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
MCP_SRC="${MCPTEST_MCP_SRC:-$REPO/app/src/main/java/com/termux/app/mcp}"
MODE="${MCPTEST_MODE:-full}"
UNIT_TESTS_DIR="$REPO/app/src/test/java/com/termux/app/mcp"
INSTALLER_SRC="$REPO/app/src/main/java/com/termux/app/terminal/ai/TermuxAiCliInstaller.java"
ASSETS="$REPO/app/src/main/assets"
STUBS="$HERE/jvm-stubs"
SRC="$HERE/mcp-src/com/termux/app/mcp"
WORK="$(mktemp -d /tmp/termuxai-mcpauth.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

command -v javac >/dev/null 2>&1 || { note "SKIP: javac not available"; exit 0; }

APPS="$WORK/apps"
mkdir -p "$APPS" "$WORK/stubs/com/termux/shared/termux" "$WORK/classes" "$WORK/tmp"
cat > "$WORK/stubs/com/termux/shared/termux/TermuxConstants.java" <<EOT
package com.termux.shared.termux;
public final class TermuxConstants {
    public static final java.io.File TERMUX_BIN_PREFIX_DIR = new java.io.File("/nonexistent/usr/bin");
    public static final java.io.File TERMUX_LIBEXEC_PREFIX_DIR = new java.io.File("/nonexistent/usr/libexec");
    public static final java.io.File TERMUX_VAR_PREFIX_DIR = new java.io.File("/nonexistent/usr/var");
    public static final class TERMUX_APP {
        public static final String APPS_DIR_PATH = "$APPS";
    }
    private TermuxConstants() {}
}
EOT

OVERLAY="$(find "$HERE/mcp-stubs" -name '*.java' | sort)"
MAIN=""
for f in McpSocketServer McpDispatch McpAuditLog McpAuth McpFrameReader McpStdioShim; do
  [ -f "$MCP_SRC/$f.java" ] && MAIN="$MAIN $MCP_SRC/$f.java"
done

if [ "$MODE" = "bypass" ]; then
  TESTS="$SRC/McpAuthFixtures.java $SRC/McpAuthBypassTest.java"
  CLASSES="com.termux.app.mcp.McpAuthBypassTest"
else
  TESTS="$SRC/McpAuthFixtures.java $SRC/McpAuthBypassTest.java $SRC/McpAuditLeakTest.java $SRC/McpAuditWiringTest.java"
  TESTS="$TESTS $UNIT_TESTS_DIR/McpAuditLogTest.java $UNIT_TESTS_DIR/McpAuthTest.java"
  TESTS="$TESTS $SRC/McpDispatchAuthTest.java $SRC/McpAuthWiringTest.java $SRC/McpStdioShimTest.java $SRC/McpFrameLimitTest.java $SRC/McpDrainAccountingTest.java $SRC/McpAuthE2ETest.java"
  TESTS="$TESTS $HERE/mcp-src/com/termux/app/terminal/ai/InstallerShimTest.java"
  MAIN="$MAIN $INSTALLER_SRC"
  CLASSES="com.termux.app.mcp.McpAuthBypassTest com.termux.app.mcp.McpAuditLeakTest com.termux.app.mcp.McpAuditWiringTest com.termux.app.mcp.McpAuditLogTest"
  CLASSES="$CLASSES com.termux.app.mcp.McpAuthTest com.termux.app.mcp.McpDispatchAuthTest com.termux.app.mcp.McpAuthWiringTest"
  CLASSES="$CLASSES com.termux.app.mcp.McpStdioShimTest com.termux.app.mcp.McpFrameLimitTest com.termux.app.mcp.McpDrainAccountingTest com.termux.app.mcp.McpAuthE2ETest com.termux.app.terminal.ai.InstallerShimTest"
fi

[ -n "${MCPTEST_CLASSES:-}" ] && CLASSES="$MCPTEST_CLASSES"

note "--- compile production MCP classes + tests (stubs), mode=$MODE ---"
javac -encoding UTF-8 -d "$WORK/classes" -sourcepath "$STUBS" \
  "$WORK/stubs/com/termux/shared/termux/TermuxConstants.java" $OVERLAY \
  "$STUBS/RunTests.java" $MAIN $TESTS
[ $? -eq 0 ] || { note "FAIL: compilation"; exit 1; }
note "compile rc=0"

# --- the real directory the server uses, evaluated from the real TermuxConstants source ---
REAL_DIR="$(python3 - "$REPO/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java" <<'PYEOF'
import re, sys
src = open(sys.argv[1]).read()
def const(name):
    m = re.search(r'String ' + name + r' = (.*?);', src)
    expr = m.group(1)
    expr = re.sub(r'//.*', '', expr)
    out = ''
    for part in expr.split('+'):
        part = part.strip()
        out += part[1:-1] if part.startswith('"') else const(part)
    return out
print(const('TERMUX_APPS_DIR_PATH') + '/' + const('TERMUX_PACKAGE_NAME') + '/termux-ai')
PYEOF
)"
if ! grep -q 'private static final String DIR_PATH = TermuxConstants.TERMUX_APP.APPS_DIR_PATH + "/termux-ai";' "$MCP_SRC/McpSocketServer.java"; then
  note "FAIL: the server directory is no longer APPS_DIR_PATH + /termux-ai (update the shim and this check together)"; fail=1
fi

# The shim main with the directory pointed at the sandbox: ONE literal differs from the shipped source.
build_shim_classes() { # $1 source file, $2 output dir
  local src="$1" out="$2" tmp="$2.src"
  rm -rf "$out" "$tmp"; mkdir -p "$out" "$tmp/com/termux/app/mcp"
  sed "s#static final String DEFAULT_DIR = \"[^\"]*\";#static final String DEFAULT_DIR = \"$APPS/termux-ai\";#" \
    "$src" > "$tmp/com/termux/app/mcp/McpStdioShim.java"
  if [ "$(diff "$src" "$tmp/com/termux/app/mcp/McpStdioShim.java" | grep -c '^>')" -ne 1 ]; then
    note "FAIL: the sandbox copy of the shim must differ from the shipped source in exactly one line"; return 1
  fi
  javac -encoding UTF-8 -d "$out" -cp "$WORK/classes" "$tmp/com/termux/app/mcp/McpStdioShim.java"
}

run_suite() { # $1 classpath, $2... classes
  local cp="$1"; shift
  rm -rf "$APPS"; mkdir -p "$APPS" "$WORK/e2e/home"
  TMPDIR="$WORK/tmp" java -Djava.io.tmpdir="$WORK/tmp" \
    -Dmcptest.work="$WORK" -Dmcptest.assets="${MCPTEST_ASSETS_DIR:-$ASSETS}" -Dmcptest.apps="$APPS" \
    -Dmcptest.mcpsrc="${MCPTEST_FORM_SRC:-$MCP_SRC}" \
    -Dmcptest.oldshim="$REPO/tests/compat/fixtures/old-mcp-shim/termux-ai-mcp.socat" \
    -Dmcptest.realdir="$REAL_DIR" \
    -Dmcptest.shimclasses="${MCPTEST_SHIM_CLASSES:-$WORK/shim-classes}" \
    -cp "$cp" RunTests "$@"
}

if [ "$MODE" = "full" ]; then
  # the fixture is byte-identical to the shim of the base 278db1ac (git blob 0132bd80...)
  if [ "$(git -C "$REPO" hash-object "$REPO/tests/compat/fixtures/old-mcp-shim/termux-ai-mcp.socat" 2>/dev/null)" != "0132bd80c6850262dc775d4ffaff6a862b355a03" ]; then
    note "FAIL: the old-shim fixture is not the shim of the base"; fail=1
  fi
  build_shim_classes "$MCP_SRC/McpStdioShim.java" "$WORK/shim-classes" || { note "FAIL: shim classes"; exit 1; }
fi

note "--- run the suites ---"
run_suite "$WORK/classes" $CLASSES
suite_rc=$?
note "suite rc=$suite_rc"
[ "$suite_rc" -eq 0 ] || fail=1

if [ "$MODE" = "full" ] && [ "${MCPTEST_SKIP_MUT:-0}" != 1 ]; then
  # mutate LABEL EXPECTED-RED-REGEX FILE::SED-EXPR [FILE::SED-EXPR ...] -- TEST-CLASSES...
  # FILE is a main source (McpX.java / TermuxAiCliInstaller.java) or the wrapper asset (termux-ai-mcp).
  mutate() {
    local label="$1" want="$2"; shift 2
    local specs=()
    while [ "$1" != "--" ]; do specs+=("$1"); shift; done; shift
    local msrc="$WORK/mut-src" mdst="$WORK/mut-classes" massets="$WORK/mut-assets" mshim="$WORK/mut-shim-classes"
    rm -rf "$msrc" "$mdst" "$massets"; mkdir -p "$msrc" "$mdst"
    cp "$MCP_SRC"/*.java "$msrc/"; cp "$INSTALLER_SRC" "$msrc/"; cp -r "$ASSETS" "$massets"
    local spec file expr target compile=() shimmed=0
    for spec in "${specs[@]}"; do
      file="${spec%%::*}"; expr="${spec#*::}"
      case "$file" in termux-ai-mcp) target="$massets/$file" ;; *) target="$msrc/$file" ;; esac
      cp "$target" "$target.orig"
      sed "$expr" "$target.orig" > "$target"
      if cmp -s "$target" "$target.orig"; then note "FAIL: $label: the mutation did not change $file"; fail=1; return; fi
      rm -f "$target.orig"
      case "$file" in
        termux-ai-mcp) ;;
        McpStdioShim.java) shimmed=1; compile+=("$msrc/$file") ;;
        *) compile+=("$msrc/$file") ;;
      esac
    done
    if [ "${#compile[@]}" -gt 0 ] && ! javac -encoding UTF-8 -d "$mdst" -cp "$WORK/classes" "${compile[@]}" 2>"$WORK/mut.err"; then
      note "FAIL: $label: the mutation did not compile"; head -5 "$WORK/mut.err"; fail=1; return
    fi
    local shimcp="$WORK/shim-classes"
    if [ "$shimmed" -eq 1 ]; then
      build_shim_classes "$msrc/McpStdioShim.java" "$mshim" >/dev/null 2>&1 || { note "FAIL: $label: shim copy"; fail=1; return; }
      shimcp="$mshim"
    fi
    MCPTEST_SHIM_CLASSES="$shimcp" MCPTEST_ASSETS_DIR="$massets" MCPTEST_FORM_SRC="$msrc" \
      run_suite "$mdst:$WORK/classes" "$@" > "$WORK/mut.out" 2>&1
    local rc=$?
    if [ "$rc" -eq 0 ]; then note "FAIL: $label stayed green"; fail=1; return; fi
    if ! grep -Eq "^FAIL .*($want)" "$WORK/mut.out"; then
      note "FAIL: $label: red, but not in the expected test ($want)"; grep '^FAIL' "$WORK/mut.out" | sed 's/: .*//' | head -5; fail=1; return
    fi
    note "$label rc=$rc (red as required): $(grep '^FAIL' "$WORK/mut.out" | sed 's/^FAIL com.termux.app.[a-z.]*\.//; s/: .*//' | head -4 | paste -sd, -)"
  }

  C=com.termux.app.mcp
  note "--- mutation 1: equals instead of the constant-time compare (read-back and presented token) ---"
  mutate "equals in ok()" "everyTokenCompareIsConstantTimeAndNeverEquals" \
    'McpAuth.java::s/return MessageDigest.isEqual(got, want);/return new String(got).equals(new String(want));/' -- $C.McpAuthTest
  mutate "equals in verifyPublished()" "everyTokenCompareIsConstantTimeAndNeverEquals" \
    'McpAuth.java::s/if (!MessageDigest.isEqual(back, token)) throw/if (!new String(back).equals(new String(token))) throw/' -- $C.McpAuthTest
  note "--- mutation 2: t -> true instead of the epoch check ---"
  mutate "t -> true fallback" "McpAuthBypassTest|McpAuthWiringTest" \
    'McpSocketServer.java::s/new McpDispatch(McpSocketServer::runTool, auth)/new McpDispatch(McpSocketServer::runTool, new McpDispatch.AuthCheck() { public boolean ok(String t) { return true; } public boolean live() { return true; } })/' \
    -- $C.McpAuthBypassTest $C.McpAuthWiringTest
  note "--- mutation 3: a failed initialize leaves authed standing ---"
  mutate "authed not cleared" "aFailedReinitializeClosesAnEarlierSession|aWronglyTypedReinitializeAlsoClosesTheSession|aFailedReinitializeOnTheRealClientLoop" \
    'McpDispatch.java::s/^        authed = false;$//' -- $C.McpDispatchAuthTest $C.McpAuthWiringTest
  note "--- mutation 4: the epoch end is not checked on the connection ---"
  mutate "epoch end ignored" "whenTheEpochEndsTheConnectionStopsBeingAuthenticated" \
    'McpDispatch.java::s/return authed \&\& auth.live();/return authed;/' -- $C.McpDispatchAuthTest
  mutate "epoch end ignored and connections not closed" "restartWithAChangedTokenInvalidatesTheOldEpochAndItsConnections|stopRemovesTheTokenClosesConnectionsAndRefusesNewOnes" \
    'McpDispatch.java::s/return authed \&\& auth.live();/return authed;/' \
    'McpAuth.java::s/for (Runnable r : toClose) runQuietly(r);//' -- $C.McpAuthWiringTest
  note "--- mutation 5: auth read with optString ---"
  mutate "optString auth" "authIsReadAsAStringNeverCoerced" \
    'McpDispatch.java::s/Object presented = params.opt("auth");/Object presented = params.optString("auth", "");/' -- $C.McpDispatchAuthTest
  note "--- mutation 6: diagnostics echo exception text ---"
  mutate "parse error echo" "diagnosticsAreFixedTextsAndEchoNothing|noDiagnosticInTheServerSources" \
    'McpDispatch.java::s/return err(id, -32700, "parse error");/return err(id, -32700, "parse error: " + e.getMessage());/' -- $C.McpDispatchAuthTest
  mutate "method echo" "diagnosticsAreFixedTextsAndEchoNothing" \
    'McpDispatch.java::s/"method not found"/"method not found: " + method/' -- $C.McpDispatchAuthTest
  mutate "client loop echo" "noDiagnosticInTheServerSources" \
    'McpSocketServer.java::s/"client loop failed"/"client loop: " + e.getMessage()/' -- $C.McpDispatchAuthTest
  note "--- mutation 7: the server starts although the token was not published ---"
  mutate "serve without token" "aPublicationErrorMeansNoServerAndAFixedCode" \
    'McpSocketServer.java::s/if (!fresh.available()) {/if (false) {/' -- $C.McpAuthWiringTest
  note "--- mutation 8: publication checks removed ---"
  mutate "mode not verified" "aPublishedFileThatIsNotOwnerOnlyIsRefused" \
    'McpAuth.java::s/if (!hasMode(target, FILE_MODE)) throw new Fail(AUTH_VERIFY_FAILED);//' -- $C.McpAuthTest
  mutate "read-back skipped" "aWriteThatStoresDifferentBytesIsCaughtByTheReadBack|aTruncatedWriteIsCaughtByTheReadBack" \
    'McpAuth.java::s/^            verifyPublished(target, token);$//' -- $C.McpAuthTest
  mutate "stale token kept" "aStaleTokenOfAnEarlierEpochDoesNotSurviveAFailedPublication|aFailedWrite|aFailedMove" \
    'McpAuth.java::s/try { Files.deleteIfExists(target); } catch (IOException | RuntimeException ignored) { }//' -- $C.McpAuthTest
  mutate "token form not checked" "onlyTheExactTokenIsAccepted" \
    'McpAuth.java::s/if (!hex) return false;//' -- $C.McpAuthTest
  note "--- mutation 9: pre-auth tools allowed ---"
  mutate "tools/list before auth" "nothingIsServedBeforeAGoodInitialize" \
    'McpDispatch.java::/case "tools\/list":/{n;d;}' -- $C.McpDispatchAuthTest
  note "--- mutation 10: the shim ---"
  mutate "no token inserted" "initializeGetsTheTokenAndEveryOtherFieldIsKept|theLegitimateShimThroughTheWrapper" \
    'McpStdioShim.java::s/params.put("auth", token);//' -- $C.McpStdioShimTest $C.McpAuthE2ETest
  mutate "client auth kept" "aClientSuppliedAuthIsReplacedNeverForwarded" \
    'McpStdioShim.java::s/params.put("auth", token);/if (!params.has("auth")) params.put("auth", token);/' -- $C.McpStdioShimTest
  mutate "no frame limit" "anOversizedFrameIsRefusedNeverTruncated|anOversizedLineBeforeAuth|theLimitIsExactly" \
    'McpFrameReader.java::s/if (len == MAX_FRAME_BYTES) throw new Rejected();//' -- $C.McpStdioShimTest $C.McpFrameLimitTest
  mutate "CR accepted" "aCarriageReturnInsideAFrameIsRefused|lineTerminatorsAre|carriageReturnsAndUtf8" \
    'McpFrameReader.java::s/if (acc\[i\] == .\\r.) throw new Rejected();//' -- $C.McpStdioShimTest $C.McpFrameLimitTest
  mutate "limit changed" "theLimitIsExactlyOneMebibyteOfWireBytes|theShimAndTheServerShareOneFrameLimit" \
    'McpFrameReader.java::s/MAX_FRAME_BYTES = 1 << 20;/MAX_FRAME_BYTES = 1 << 21;/' -- $C.McpStdioShimTest $C.McpFrameLimitTest
  mutate "talks without a token" "aMissingOrMalformedTokenStopsBeforeAnyConnection" \
    'McpStdioShim.java::s/if (token == null) return s.fail(EXIT_NO_TOKEN, E_NO_TOKEN);/if (token == null) token = "";/' -- $C.McpStdioShimTest
  mutate "token form not checked in shim" "aMissingOrMalformedTokenStopsBeforeAnyConnection" \
    'McpStdioShim.java::s/if (!hex) return null;//' -- $C.McpStdioShimTest
  mutate "error text echoes the cause" "anAbsentServerIsAFixedLocalErrorAndNeverEchoesTheCause" \
    'McpStdioShim.java::s/return s.fail(EXIT_NO_SERVER, E_NO_SERVER);/return s.fail(EXIT_NO_SERVER, E_NO_SERVER + " " + e.getMessage());/' -- $C.McpStdioShimTest
  mutate "initialize fabricated" "noInitializeIsFabricatedInFrontOfTheClientsFirstFrame" \
    'McpStdioShim.java::s/McpFrameReader frames = new McpFrameReader(stdin);/McpFrameReader frames = new McpFrameReader(stdin); try { toServer.write(("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":0,\\"method\\":\\"initialize\\",\\"params\\":{\\"auth\\":\\"" + token + "\\"}}\\n").getBytes()); } catch (IOException ignored) { }/' -- $C.McpStdioShimTest
  note "--- mutation 11: the wrapper and the installer ---"
  mutate "wrapper takes the split apk" "theLegitimateShimThroughTheWrapper" \
    'termux-ai-mcp::s#package:/\*/base.apk)#package:/*.apk)#' -- $C.McpAuthE2ETest
  mutate "wrapper keeps the Termux libraries" "theLegitimateShimThroughTheWrapper" \
    'termux-ai-mcp::s/^unset LD_LIBRARY_PATH LD_PRELOAD$//' -- $C.McpAuthE2ETest
  mutate "wrapper accepts a classpath separator" "theWrapperFailsWithFixedLinesAndNeverPrintsOnStdout" \
    'termux-ai-mcp::s/\*:\* | //' -- $C.McpAuthE2ETest
  mutate "install not verified" "bytesThatChangeBetweenWriteAndVerifyAreCaught|aTruncatedCopyIsCaught" \
    'TermuxAiCliInstaller.java::s/if (!qualifiedCopy(context, assetName, aside)) throw new java.io.IOException();//' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "final copy not qualified" "aSwapThatPutsSomethingElseInPlace" \
    'TermuxAiCliInstaller.java::s/ok = qualifiedCopy(context, assetName, targetPath);/ok = true;/' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "stale shim kept on failure" "aFailedInstallRemovesTheStaleShimAndLogsAFixedLine|aFailedSwap|aFailedCopy|bytesThatChange" \
    'TermuxAiCliInstaller.java::s/^            removeStaleShim(targetPath, assetName);$//' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "stale shim removal failure hidden" "aStaleShimThatCannotBeRemoved" \
    'TermuxAiCliInstaller.java::s/Logger.logError(LOG_TAG, "stale shim not removed: " + assetName);//' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "fixed temporary name" "aSymlinkAtTheOldFixedTemporaryName|aForeignFileOrDirectory" \
    'TermuxAiCliInstaller.java::s/".new-" + randomSuffix()/".new"/' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "temporary written through an existing link" "aSymlinkAtTheOldFixedTemporaryName" \
    'TermuxAiCliInstaller.java::s/java.nio.file.Files.createFile(aside, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(SHIM_MODE));/try { java.nio.file.Files.createFile(aside); } catch (java.nio.file.FileAlreadyExistsException e) { }/;s/".new-" + randomSuffix()/".new"/' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "link target accepted" "aTargetThatIsALinkOrADirectoryIsRefused" \
    'TermuxAiCliInstaller.java::s/\&\& !java.nio.file.Files.readAttributes(targetPath, java.nio.file.attribute.BasicFileAttributes.class, noFollow).isRegularFile()/\&\& false/' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "shim not in the verified set" "theOtherNamedAssetKeepsItsPlainInstall" \
    'TermuxAiCliInstaller.java::s/VERIFIED_ASSETS = {"termux-ai-mcp"}/VERIFIED_ASSETS = {"none"}/' -- com.termux.app.terminal.ai.InstallerShimTest
  note "--- mutation 13: temporary-name ownership and reply accounting ---"
  mutate "cleanup of a name this call did not create" "aCollidingTemporaryNameIsRetried|whenEveryTryCollides" \
    'TermuxAiCliInstaller.java::s/\(java.nio.file.Path candidate = .*\)$/\1 aside = candidate;/' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "no retry on a taken name" "aCollidingTemporaryNameIsRetried" \
    'TermuxAiCliInstaller.java::s/NAME_ATTEMPTS = 4;/NAME_ATTEMPTS = 1;/' -- com.termux.app.terminal.ai.InstallerShimTest
  mutate "malformed input not counted as owed" "theShimOwesExactlyTheAnswers|anAnswerToAnEarlierOddInput|severalOddInputs" \
    'McpStdioShim.java::s/return new Prepared(frame, true);\n//;0,/} catch (JSONException | RuntimeException e) {/{n;s/return new Prepared(frame, true);/return new Prepared(frame, false);/}' -- $C.McpDrainAccountingTest
  mutate "every notifications/ prefix exempt" "theShimOwesExactlyTheAnswers|anAnswerToAnEarlierOddInput|bothDelivered" \
    'McpStdioShim.java::s/boolean request = !(method instanceof String \&\& McpDispatch.NO_REPLY_METHOD.equals(method));/boolean request = method instanceof String \&\& !((String) method).startsWith("notifications\/");/' -- $C.McpDrainAccountingTest
  mutate "non-string method not counted" "theShimOwesExactlyTheAnswers|anAnswerToAnEarlierOddInput" \
    'McpStdioShim.java::s/boolean request = !(method instanceof String \&\& McpDispatch.NO_REPLY_METHOD.equals(method));/boolean request = method instanceof String;/' -- $C.McpDrainAccountingTest
  mutate "blank defined narrower than the server" "blankLinesAreIgnoredByBothSides" \
    'McpStdioShim.java::s/if ((frame\[i\] \& 0xff) > . .) blank = false;/if (frame[i] != 32 \&\& frame[i] != 9) blank = false;/' -- $C.McpDrainAccountingTest
  note "--- mutation 12: drain and late accept ---"
  mutate "drain timeout reported as success" "aServerThatStaysSilent|aServerThatClosesWithoutAnswering|anAnswerThatNeverCompletes|anInterruptedWait" \
    'McpStdioShim.java::s/if (answeredEverything()) decide(EXIT_OK, null);/if (true) decide(EXIT_OK, null);/' -- $C.McpStdioShimTest
  mutate "partial answer counted as complete" "anAnswerThatNeverCompletesItsLineIsNotACompletion" \
    'McpStdioShim.java::s/ \&\& !partialAnswer.get();/;/' -- $C.McpStdioShimTest
  mutate "requests not counted" "aServerThatStaysSilent|aServerThatClosesWithoutAnswering" \
    'McpStdioShim.java::s/boolean request = !(method instanceof String \&\& McpDispatch.NO_REPLY_METHOD.equals(method));/boolean request = false;/' -- $C.McpStdioShimTest
  mutate "late accept not closed" "aConnectionAcceptedAfterTheEpochEnded" \
    'McpAuth.java::s/if (runNow) runQuietly(closer);//' -- $C.McpAuthWiringTest
fi

note "mcp-auth harness rc=$fail"
exit "$fail"
