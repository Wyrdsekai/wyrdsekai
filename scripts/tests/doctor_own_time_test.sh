#!/usr/bin/env bash
# `wyrd doctor`'s own-time probe: a companion whose own-time turns produce no act in the window is named;
# one who acted is not; a companion with fewer than three turns is not judged. Lifts the pure function from
# bin/wyrd and feeds it a small trail. Run: bash scripts/tests/doctor_own_time_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
eval "$(sed -n '/^_own_time_summary() {/,/^}/p' "$HERE/bin/wyrd")"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
now() { date -u -d "-$1 min" +%Y-%m-%dT%H:%M:%S.000Z; }
{
  # mia: five own-time turns, five lines, no act — the 09-26 shape
  for m in 300 240 180 120 60; do
    echo "{\"ts\":\"$(now $m)\",\"kind\":\"autonomy\",\"agentId\":\"companion-mia\",\"agentName\":\"mia\",\"decision\":\"idle_curiosity\"}"
    echo "{\"ts\":\"$(now $((m-1)))\",\"kind\":\"speak\",\"agentId\":\"companion-mia\",\"agentName\":\"mia\",\"text\":\"evening\"}"
  done
  # rose: four turns and two acts
  for m in 250 190 130 70; do
    echo "{\"ts\":\"$(now $m)\",\"kind\":\"autonomy\",\"agentId\":\"companion-rose\",\"agentName\":\"rose\",\"decision\":\"idle_curiosity\"}"
  done
  echo "{\"ts\":\"$(now 189)\",\"kind\":\"move\",\"agentId\":\"companion-rose\",\"agentName\":\"rose\",\"to\":\"home\"}"
  echo "{\"ts\":\"$(now 69)\",\"kind\":\"action\",\"agentId\":\"companion-rose\",\"agentName\":\"rose\",\"action\":\"library_search\"}"
  # wyrd: two turns only, no act — too few to judge
  echo "{\"ts\":\"$(now 100)\",\"kind\":\"autonomy\",\"agentId\":\"companion-wyrd\",\"agentName\":\"wyrd\",\"decision\":\"idle_curiosity\"}"
  echo "{\"ts\":\"$(now 50)\",\"kind\":\"autonomy\",\"agentId\":\"companion-wyrd\",\"agentName\":\"wyrd\",\"decision\":\"idle_curiosity\"}"
  # ember: twelve turns and one act — words with a single act is still the 09-26 shape
  for m in 330 300 270 240 210 180 150 120 90 60 30 10; do
    echo "{\"ts\":\"$(now $m)\",\"kind\":\"autonomy\",\"agentId\":\"companion-ember\",\"agent\":\"ember\",\"decision\":\"idle_curiosity\"}"
  done
  echo "{\"ts\":\"$(now 200)\",\"kind\":\"enacted\",\"agentId\":\"did:key:z6MkEmber\",\"agent\":\"ember\",\"verb\":\"go_to_room\"}"
  # an old row outside the window
  echo "{\"ts\":\"$(now 900)\",\"kind\":\"autonomy\",\"agentId\":\"companion-wyrd\",\"agentName\":\"wyrd\",\"decision\":\"idle_curiosity\"}"
} > "$T/trail.jsonl"
out=$(_own_time_summary "$T/trail.jsonl" 6)
fail=0
check() { local label="$1"; shift; if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi; }
check "mia is named: five turns, no act"          grep -q "^WARN mia: 5 own-time turns" <<<"$out"
check "mia's line carries her counts"             grep -q "^mia: 5 own-time turn(s), 0 act(s), 5 line(s) said" <<<"$out"
check "rose is not named: she acted"              bash -c "! grep -q '^WARN rose' <<<\"$out\""
check "rose's line shows two acts"                grep -q "^rose: 4 own-time turn(s), 2 act(s)" <<<"$out"
check "wyrd is not judged on two turns"           bash -c "! grep -q '^WARN wyrd' <<<\"$out\""
check "the old row stays outside the window"      grep -q "^wyrd: 2 own-time turn(s)" <<<"$out"
empty=$(_own_time_summary /dev/null 6)
check "ember is named on one act in twelve turns"  grep -q "^WARN ember: 12 own-time turns in the last 6 h and only 1 action" <<<"$out"
check "ember's DID enactment is joined to her by name" grep -q "^ember: 12 own-time turn(s), 1 act(s)" <<<"$out"
check "an empty window says nothing"              test -z "$empty"
exit $fail
