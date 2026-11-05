#!/usr/bin/env bash
# Checks that an external entry point survives R8 in an APK (or in a .dex file).
#
# usage: check_dex.sh <apk-or-dex> [dexdump] [class-descriptor]
#   dexdump          path of the build-tools dexdump (default: $ANDROID_HOME/build-tools/36.0.0/dexdump)
#   class-descriptor default Lcom/termux/app/terminal/ai/compat/TermuxApiCompatClient;
#
# The contract checked here is the external one: the class is defined in a DEX of the archive and has a
# public static void main(String[]). Names of the classes R8 keeps behind it (merged, inlined or renamed)
# are not part of the contract. For the compat client it also checks that nothing under its package refers to
# an Activity, androidx or ML Kit class: the client must run without a UI process.
# This is a static check; it does not prove that the client works. The run on a device does.
#
# Exit: 0 all checks passed, 1 a check failed, 2 usage or tool problem.
set -u

APK="${1:-}"
DEXDUMP="${2:-${ANDROID_HOME:-}/build-tools/36.0.0/dexdump}"
CLASS="${3:-Lcom/termux/app/terminal/ai/compat/TermuxApiCompatClient;}"
PACKAGE_PREFIX="${CLASS%/*}/"

fail() { printf 'FAIL: %s\n' "$*"; exit 1; }
usage() { printf 'check_dex: %s\n' "$*" >&2; exit 2; }

[ -n "$APK" ] || usage "usage: check_dex.sh <apk-or-dex> [dexdump] [class-descriptor]"
[ -f "$APK" ] || usage "not a file: $APK"
[ -x "$DEXDUMP" ] || usage "dexdump not executable: $DEXDUMP"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/check-dex.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

"$DEXDUMP" "$APK" > "$WORK/dump.txt" 2> "$WORK/dump.err" \
  || usage "dexdump failed on $APK: $(head -n 1 "$WORK/dump.err")"
classes_total="$(grep -c "^  Class descriptor  :" "$WORK/dump.txt" || true)"
[ "$classes_total" -gt 0 ] || fail "no classes found in $APK (not an APK with a DEX?)"

# 1. the class is defined in the archive
if ! grep -F -q "Class descriptor  : '$CLASS'" "$WORK/dump.txt"; then
  fail "$CLASS is not defined in $APK ($classes_total classes in the archive): R8 removed it or the keep rule is missing"
fi

# 2. public static void main(String[]) of that class
awk -v cls="$CLASS" '
  /^    #[0-9]+ +: \(in / { inside = (index($0, "(in " cls ")") > 0); name = ""; type = ""; next }
  inside && /^      name +:/    { name = $0; sub(/^[^\047]*\047/, "", name); sub(/\047.*$/, "", name); next }
  inside && /^      type +:/    { type = $0; sub(/^[^\047]*\047/, "", type); sub(/\047.*$/, "", type); next }
  inside && /^      access +:/  { if (name == "main" && type == "([Ljava/lang/String;)V" && $0 ~ /PUBLIC/ && $0 ~ /STATIC/) found = 1; next }
  END { exit(found ? 0 : 1) }
' "$WORK/dump.txt" || fail "$CLASS is defined but has no public static void main(String[])"

# 3. the compat client must not drag UI or ML Kit classes in (only meaningful for its own package)
case "$CLASS" in
  Lcom/termux/app/terminal/ai/compat/*)
    "$DEXDUMP" -d "$APK" > "$WORK/disasm.txt" 2>/dev/null || usage "dexdump -d failed on $APK"
    awk -v prefix="$PACKAGE_PREFIX" '
      /^  Class descriptor  :/ { d = $0; sub(/^[^\047]*\047/, "", d); sub(/\047.*$/, "", d); in_pkg = (index(d, "L" substr(prefix, 2)) == 1 || index(d, prefix) == 1); cls = d }
      in_pkg && (/Landroid\/app\/Activity;/ || /Landroidx\// || /Lcom\/google\/mlkit\// || /Lcom\/termux\/app\/TermuxActivity;/) {
        print "  " cls ": " $0; bad = 1
      }
      END { exit(bad ? 1 : 0) }
    ' "$WORK/disasm.txt" > "$WORK/ui.txt" || { cat "$WORK/ui.txt"; fail "classes under $PACKAGE_PREFIX refer to UI or ML Kit classes"; }
    ;;
esac

printf 'OK: %s defines public static main(String[]) (%s classes in the archive)\n' "$CLASS" "$classes_total"
