#!/usr/bin/env bash
# `wyrd report` writes a bundle for the Wyrdsekai authors and sends nothing (2026-10-19, after a
# household's steward collected one by hand). The blanking of secrets is a pure function and is
# table-tested here; the whole verb runs when WYRD_REPORT_LIVE=1 (the build box in the release
# chain): the bundle holds the journal, units, versions, hardware and the doctor's lines, and
# nothing from the data directory.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
eval "$(sed -n '/^_report_redact() {/,/^}/p' "$W")"

out="$(printf '%s\n' 'WYRDSEKAI_PORT=7070' 'WYRDSEKAI_RELAY_TOKEN=abc123' 'Environment=WYRDSEKAI_OPERATOR_TOKEN=xyz' \
    'NATS_PASSWORD=hunter2' 'WYRDSEKAI_API_KEY=sk-live' 'RELAY_NKEY_SEED=SUAM...' 'WYRDSEKAI_INFERENCE_URL=http://198.51.100.161:11434' | _report_redact)"
check 'a port stays'                        '[[ "$out" == *"WYRDSEKAI_PORT=7070"* ]]'
check 'a token is blanked'                   '[[ "$out" == *"WYRDSEKAI_RELAY_TOKEN=<redacted>"* && "$out" != *"abc123"* ]]'
check 'a unit Environment= token is blanked' '[[ "$out" == *"Environment=WYRDSEKAI_OPERATOR_TOKEN=<redacted>"* && "$out" != *"xyz"* ]]'
check 'a password is blanked'                '[[ "$out" != *"hunter2"* ]]'
check 'an API key is blanked'                '[[ "$out" != *"sk-live"* ]]'
check 'an nkey seed is blanked'              '[[ "$out" != *"SUAM"* ]]'
check 'the inference URL stays'              '[[ "$out" == *"WYRDSEKAI_INFERENCE_URL=http://198.51.100.161:11434"* ]]'

if [[ "${WYRD_REPORT_LIVE:-}" == "1" ]]; then
    T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
    echo "== the whole verb, one day of journal"
    run="$(cd "$HERE" && WYRDSEKAI_REPORT_DIR="$T" bash "$W" report 1 2>&1 | sed 's/\x1b\[[0-9;]*m//g')"
    printf '%s\n' "$run" | tail -3 | sed 's/^/     /'
    tarball="$(ls "$T"/wyrdsekai-report-*.tar.gz 2>/dev/null | head -1)"
    check 'a tarball was written in the chosen directory' '[[ -f "$tarball" ]]'
    if [[ -f "$tarball" ]]; then
        listing="$(tar -tzf "$tarball")"
        for f in README.txt version.txt api-version.json doctor.txt brain-plan.txt hardware.txt units.txt journal-wyrdsekai.log.gz; do
            check "the bundle holds $f" '[[ "$listing" == *"/$f"* ]]'
        done
        check 'nothing from the data directory' '[[ "$listing" != *"world.db"* && "$listing" != *"souls"* && "$listing" != *"agent-activity"* && "$listing" != *"credentials.safe"* ]]'
        check 'no secret value in the units or settings' '! tar -xzOf "$tarball" --wildcards "*/units.txt" "*/wyrdsekai.conf" "*/env" 2>/dev/null | grep -qiE "(TOKEN|PASSWORD|SECRET|API_KEY|NKEY|SEED)[A-Za-z_]*=[^<]"'
        check 'the README says what is and is not inside' '[[ "$(tar -xzOf "$tarball" --wildcards "*/README.txt")" == *"What is not: the data directory"* ]]'
    fi
else
    echo "skip: set WYRD_REPORT_LIVE=1 to run the whole verb"
fi
[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
