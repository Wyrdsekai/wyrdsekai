#!/usr/bin/env bash
# `wyrd update now` asks CodeZaiku and ResearchZosho to update themselves, by their update contract
# (2026-09-28): `update --json` for status (changes nothing), `update now --json` only when newer and
# canUpdate and not updating, exit 0/75/3/1 read as updated-or-current / busy / cannot / failed; a release
# from before the contract (no --json) is compared installed-vs-latest and gets a plain `update now`.
# Wyrdsekai never touches their files: the fake install folders are fingerprinted before and after.
# Lifts the functions from bin/wyrd with fake siblings. Run as a normal user.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
for fn in _is_release_version _version_newer _owner_of _sibling_version _rz_bin _rz_bin_for_update \
          _sibling_as _sibling_json _sibling_update _cz_bin_for_update _update_siblings do_coding; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
CALLS="$T/calls"; : > "$CALLS"
log() { echo "LOG $*"; }; warn() { echo "WARN $*"; }; err() { echo "ERR $*"; }; _t() { echo "$*"; }
LATEST_CZ=""; LATEST_RZ=""
_release_latest() { case "$1" in Wyrdsekai/codezaiku) echo "$LATEST_CZ" ;; Wyrdsekai/researchzosho) echo "$LATEST_RZ" ;; esac; }
DATA_DIR="$T/data"; CZDIR="$DATA_DIR/coding-cli-bundle/codezaiku"; mkdir -p "$CZDIR/codezaiku/bin" "$T/rz/bin"
# A fake sibling: behaviour from $T/<id>.* files; every update call recorded.
fake() {   # <id> <path> <version-word>
cat > "$2" <<SH
#!/bin/sh
id=$1; T="$T"
mode=\$(cat "\$T/\$id.mode")
case "\$1" in
  $3) echo "\$id \$(cat "\$T/\$id.version")"; exit 0 ;;
  update)
    if [ "\$2" = "--json" ]; then
      [ "\$mode" = contract ] || { echo "usage: \$id update [status | now]" >&2; exit 2; }
      cat "\$T/\$id.status"; exit 0
    fi
    if [ "\$2" = now ]; then
      echo "\$id update now \$3" >> "$CALLS"
      if [ "\$3" = "--json" ]; then cat "\$T/\$id.now"; fi
      exit \$(cat "\$T/\$id.now_rc")
    fi ;;
esac
exit 2
SH
chmod +x "$2"
}
fake cz "$CZDIR/codezaiku/bin/codezaiku" --version
fake rz "$T/rz/bin/researchzosho" version
export RESEARCHZOSHO_BIN="$T/rz/bin/researchzosho"
print_fp() { (cd "$CZDIR" && find . -type f -exec sha256sum {} +; cd "$T/rz" && find . -type f -exec sha256sum {} +) | sort; }
FP_BEFORE="$(print_fp)"
set_contract() {   # <id> <newer> <canUpdate> <updating> <now_rc> <now json>
    echo contract > "$T/$1.mode"
    printf '{"installed":"0.3.10","running":"0.3.10","latest":"0.3.11","newer":%s,"mode":"check","root":"/opt/x","canUpdate":%s,"updating":%s}\n' "$2" "$3" "$4" > "$T/$1.status"
    echo "$5" > "$T/$1.now_rc"; echo "$6" > "$T/$1.now"
}
set_old() { echo old > "$T/$1.mode"; echo "$2" > "$T/$1.version"; echo "${3:-0}" > "$T/$1.now_rc"; }
fail=0
check() { local label="$1"; shift; if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi; }
# Under bin/wyrd's own shell options: a sibling's non-zero answer (usage 2 from a release without
# --json, 75, 3, 1) aborted the whole script before its exit code was read (second-node, 2026-09-28).
run() {
    : > "$CALLS"
    ( set -euo pipefail; _update_siblings; echo "SIBLINGS DONE" ) > "$T/out" 2>&1
    OUT="$(cat "$T/out")"
    grep -q "SIBLINGS DONE" <<<"$OUT" || { echo "FAIL the script stopped inside the sibling update: $(tail -1 <<<"$OUT")"; fail=1; }
}

set_contract cz true true false 0 '{"result":"updated","code":0,"from":"0.3.10","to":"0.3.11","finishesAfterExit":false,"note":""}'
set_contract rz false true false 0 '{}'
run
check "contract: newer + canUpdate → update now --json"                 grep -qx "cz update now --json" "$CALLS"
check "contract: the result is read (from → to)"                          grep -q "update.sibling.updated CodeZaiku 0.3.10 0.3.11" <<<"$OUT"
check "contract: not newer → not asked to update"                         bash -c "! grep -q '^rz ' '$CALLS'"
check "contract: not newer → reported current"                            grep -q "update.sibling.current ResearchZosho 0.3.10" <<<"$OUT"

set_contract cz true false false 0 '{}'; set_contract rz true true true 0 '{}'; run
check "contract: canUpdate false → not asked"                              bash -c "! test -s '$CALLS'"
check "contract: canUpdate false → says it cannot, with the mode"          grep -q "WARN update.sibling.cannot CodeZaiku check" <<<"$OUT"
check "contract: updating true → not asked, said busy"                     grep -q "update.sibling.busy ResearchZosho" <<<"$OUT"

set_contract cz true true false 75 '{"result":"busy","code":75,"note":"another update"}'
set_contract rz true true false 3 '{"result":"cannot","code":3,"note":"a package manager owns it"}'; run
check "contract: exit 75 → busy, asked once, not in a loop"                test "$(grep -c '^cz update now' "$CALLS")" = 1
check "contract: exit 75 → reported busy"                                   grep -q "update.sibling.busy CodeZaiku" <<<"$OUT"
check "contract: exit 3 → the person is told why"                          grep -q "update.sibling.cannot ResearchZosho check a package manager owns it" <<<"$OUT"

set_contract cz true true false 1 '{"result":"failed","code":1,"from":"0.3.10","to":"0.3.10","note":"checksum did not match"}'
set_contract rz false true false 0 '{}'; run
check "contract: exit 1 → failed, with its note, old version stays"         grep -q "WARN update.sibling.failed CodeZaiku 0.3.10 checksum did not match" <<<"$OUT"

set_contract cz true true false 0 '{"result":"updated","code":0,"from":"0.3.10","to":"0.3.11","finishesAfterExit":true,"note":""}'; run
check "contract: finishesAfterExit → says it finishes after the command"    grep -q "update.sibling.finishing CodeZaiku 0.3.10 0.3.11" <<<"$OUT"

set_old cz 0.3.6 0; set_old rz 0.4.6 1; LATEST_CZ=0.3.10; LATEST_RZ=0.4.7; run
check "before the contract: older → plain update now"                     grep -qx "cz update now " "$CALLS"
check "before the contract: exit 0 → updated"                              grep -q "update.sibling.updated CodeZaiku 0.3.6 0.3.10" <<<"$OUT"
check "before the contract: exit 1 → failed"                               grep -q "WARN update.sibling.failed ResearchZosho 0.4.6" <<<"$OUT"

set_old cz 0.3.10 0; set_old rz 0.4.7 0; run
check "before the contract: current → not asked"                           bash -c "! test -s '$CALLS'"

LATEST_CZ=""; LATEST_RZ=""; run
check "before the contract: unknown latest → nothing asked"                bash -c "! test -s '$CALLS'"
check "before the contract: unknown latest → said"                         grep -q "WARN update.sibling.unknown CodeZaiku" <<<"$OUT"

set_contract cz true true false 0 '{}'; WYRDSEKAI_UPDATE_SIBLINGS=0 run
check "WYRDSEKAI_UPDATE_SIBLINGS=0 (the node's own update) asks nobody"     bash -c "! test -s '$CALLS'"
check "the skip is said"                                                    grep -q "update.sibling.skipped" <<<"$OUT"

: > "$CALLS"; set_contract cz true true false 0 '{"result":"updated","code":0,"from":"0.3.10","to":"0.3.11"}'
( set -euo pipefail; do_coding update codezaiku; echo "CODING DONE" ) > "$T/out" 2>&1; OUT="$(cat "$T/out")"
check "wyrd coding update codezaiku runs to its end under the script's shell options" grep -q "CODING DONE" <<<"$OUT"
check "wyrd coding update codezaiku asks CodeZaiku's own updater"            grep -qx "cz update now --json" "$CALLS"

check "no file in either sibling's folder was touched"                       test "$(print_fp)" = "$FP_BEFORE"

rm -rf "$CZDIR"; RESEARCHZOSHO_BIN="$T/none"; HOME="$T/emptyhome"; PATH="/usr/bin:/bin"; run
check "absent siblings: nothing runs"                                        bash -c "! test -s '$CALLS'"
check "absent siblings: nothing said"                                        test "$OUT" = "SIBLINGS DONE"
exit $fail
