#!/usr/bin/env bash
# `wyrd seed`, `wyrd logs` and `wyrd inference restart|start|stop`.
# Lifts the functions from bin/wyrd and runs them against a fake curl that records what it was
# given: the passphrase must reach the server on stdin, never on a command line; the file is
# written 0600 and never over an existing one; refusals are said in plain words.
# Run as a normal user: bash scripts/tests/seed_cli_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
for fn in _seed_read_passphrase _seed_refused do_seed do_inference _doctor_seed_line; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
export HOME="$T/home" TMPDIR="$T"; mkdir -p "$HOME"
log() { echo "LOG $*"; }; warn() { echo "WARN $*"; }; err() { echo "ERR $*"; }; _t() { echo "$*"; }
TOKEN="steward-token"; wyrd_session_token() { printf '%s' "$TOKEN"; }
ARGV="$T/argv"; BODY="$T/body"; CODE=200; ANSWER='{}'
curl() {   # records argv and the body read from stdin; answers with $ANSWER / $CODE
    printf '%s\n' "$*" >> "$ARGV"
    local out="" a
    while [[ $# -gt 0 ]]; do a="$1"; shift; [[ "$a" == "-o" ]] && { out="$1"; shift; }; done
    cat > "$BODY"
    [[ -n "$out" ]] && printf '%s' "$ANSWER" > "$out"
    printf '%s' "$CODE"
}
fail=0
check() { local label="$1"; shift; if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi; }
PASS="correct horse battery staple"
SEALED="$(printf 'WSRS-sealed-bytes' | base64)"

: > "$ARGV"; CODE=200
ANSWER="{\"name\":\"Mia\",\"entityId\":\"mia\",\"did\":\"did:key:z6Mk\",\"createdAt\":\"2026-09-28T10:00:00Z\",\"bonds\":1,\"carriesKey\":true,\"hereAlready\":false,\"file\":\"$SEALED\",\"localCopy\":\"/var/lib/wyrdsekai/recovery-seed/mia.wsrs\"}"
OUT="$(printf '%s\n' "$PASS" | do_seed generate Mia 2>&1)"; rc=$?
check "generate: succeeds"                                   test $rc -eq 0
check "generate: the passphrase is not on curl's command line" bash -c "! grep -qF '$PASS' '$ARGV'"
check "generate: the passphrase reaches the server in the body" grep -qF "\"passphrase\": \"$PASS\"" "$BODY"
check "generate: the companion is named in the body"          grep -qF '"companion": "Mia"' "$BODY"
check "generate: the steward's token is sent"                 grep -qF "Authorization: Bearer $TOKEN" "$ARGV"
check "generate: asks the seed route"                         grep -qF "/api/seed/generate" "$ARGV"
SAVED="$(ls "$HOME"/mia-recovery-seed-*.wsrs 2>/dev/null | head -1)"
check "generate: the file lands in the home folder"          test -n "$SAVED"
check "generate: the file holds the sealed bytes"            test "$(cat "$SAVED" 2>/dev/null)" = "WSRS-sealed-bytes"
check "generate: the file is 0600"                           test "$(stat -c %a "$SAVED" 2>/dev/null)" = "600"
check "generate: says where it went and what to keep apart"  bash -c "grep -q 'seed.made Mia' <<<\"\$0\" && grep -q 'seed.keep_passphrase' <<<\"\$0\"" "$OUT"
check "generate: no temp answer is left behind"              bash -c "! ls '$T'/wyrd-seed.* >/dev/null 2>&1"

OUT="$(printf '%s\n' "$PASS" | do_seed generate Mia 2>&1)"; rc=$?
check "generate: never writes over an existing file"         bash -c "[[ $rc -ne 0 ]] && grep -q 'seed.out_exists' <<<\"\$0\"" "$OUT"
check "generate: the existing file is untouched"             test "$(cat "$SAVED")" = "WSRS-sealed-bytes"

OUT="$(printf '%s\n' "$PASS" | do_seed generate Mia --out "$T/mine.wsrs" 2>&1)"
check "generate --out: writes where it is told"              test -f "$T/mine.wsrs"

printf 'seedfile' > "$T/in.wsrs"; : > "$ARGV"
ANSWER='{"name":"Mia","entityId":"mia","did":"did:key:z6Mk","createdAt":"2026-09-28T10:00:00Z","bonds":1,"carriesKey":true,"hereAlready":false}'
OUT="$(printf '%s\n' "$PASS" | do_seed verify "$T/in.wsrs" 2>&1)"
check "verify: sends the file's bytes, base64"               grep -qF "\"file\": \"$(printf 'seedfile' | base64)\"" "$BODY"
check "verify: the passphrase is not on curl's command line"  bash -c "! grep -qF '$PASS' '$ARGV'"
check "verify: says whose seed it is and when"               grep -q "seed.verified Mia 2026-09-28 1 seed.key_included" <<<"$OUT"

OUT="$(printf '%s\n' "$PASS" | do_seed restore "$T/in.wsrs" 2>&1)"
check "restore: asks the restore route"                      grep -qF "/api/seed/restore" "$ARGV"
check "restore: says she wakes at the next start"            grep -q "seed.restored Mia" <<<"$OUT"

CODE=403; ANSWER='{"error":"Steward role required"}'
OUT="$(printf '%s\n' "$PASS" | do_seed restore "$T/in.wsrs" 2>&1)"; rc=$?
check "a member is told only the steward can"               bash -c "[[ $rc -ne 0 ]] && grep -q 'seed.err.forbidden' <<<\"\$0\"" "$OUT"
CODE=409; ANSWER='{"error":"name_taken","message":"A different companion already lives here"}'
OUT="$(printf '%s\n' "$PASS" | do_seed restore "$T/in.wsrs" 2>&1)"
check "a refusal is said in the launcher's own words"        grep -q "seed.err.name_taken" <<<"$OUT"
CODE=404; ANSWER='{"error":"no_such_companion","message":"x","names":["Mia","Rose"]}'
OUT="$(printf '%s\n' "$PASS" | do_seed generate Nobody 2>&1)"
check "an unknown name lists who lives here"                 grep -q "seed.err.no_such_companion Mia, Rose" <<<"$OUT"
CODE=000; ANSWER=''
OUT="$(printf '%s\n' "$PASS" | do_seed generate Mia 2>&1)"
check "no server: says so"                                   grep -q "seed.no_server" <<<"$OUT"

: > "$ARGV"; TOKEN=""
OUT="$(printf '%s\n' "$PASS" | do_seed generate Mia 2>&1)"
check "no token: nothing is sent"                            bash -c "! test -s '$ARGV'"
check "no token: says how"                                   grep -q "seed.no_token" <<<"$OUT"
OUT="$(do_seed restore "$T/none.wsrs" 2>&1)"
check "a missing file is named"                              grep -q "seed.file_missing" <<<"$OUT"

check "wyrd logs is the same verb as wyrd log"               grep -qE '^    log\|logs\)[[:space:]]+do_log ;;' "$HERE/bin/wyrd"
check "wyrd seed is dispatched"                              grep -qE '^    seed\)[[:space:]]+do_seed "\$\{@:2\}" ;;' "$HERE/bin/wyrd"

DATA_DIR="$T/data"; mkdir -p "$DATA_DIR"
OUT="$(_doctor_seed_line 2>&1)"; rc=$?
check "doctor: no companion yet, nothing to say"             bash -c "[[ $rc -eq 0 && -z \"\$0\" ]]" "$OUT"
mkdir -p "$DATA_DIR/souls"; echo "did:key:z6Mk" > "$DATA_DIR/souls/mia.did"
OUT="$(_doctor_seed_line 2>&1)"; rc=$?
check "doctor: a companion with no seed is a warning"        bash -c "[[ $rc -eq 1 ]] && grep -q 'doctor.seed_none' <<<\"\$0\"" "$OUT"
mkdir -p "$DATA_DIR/recovery-seed"; : > "$DATA_DIR/recovery-seed/mia.wsrs"
OUT="$(_doctor_seed_line 2>&1)"; rc=$?
check "doctor: a made seed is named"                         bash -c "[[ $rc -eq 0 ]] && grep -q 'doctor.seed_ok mia' <<<\"\$0\"" "$OUT"

CALLS="$T/calls"; : > "$CALLS"
_quiesce_notice() { echo "notice $*" >> "$CALLS"; }
stop_inference() { echo "stop" >> "$CALLS"; }
ensure_inference() { echo "ensure" >> "$CALLS"; }
sleep() { :; }
do_inference restart >/dev/null 2>&1
check "inference restart: tells the companions, stops, then starts" test "$(tr '\n' ' ' < "$CALLS")" = "notice an inference restart stop ensure "
: > "$CALLS"; do_inference start >/dev/null 2>&1
check "inference start: brings the servers up"               test "$(cat "$CALLS")" = "ensure"
: > "$CALLS"; do_inference stop >/dev/null 2>&1
check "inference stop: takes them down"                      test "$(cat "$CALLS")" = "stop"
exit $fail
