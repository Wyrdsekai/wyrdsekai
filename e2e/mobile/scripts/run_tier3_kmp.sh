#!/usr/bin/env bash
# KMP parallel of run_tier3_rn.sh. Seeds wyrdsekai_prefs SharedPreferences
# before each Tier 3 flow so the app boots into LocalRoomScreen with a
# live ServerClient session.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SEED="$SCRIPT_DIR/seed_phone_session_kmp.sh"

: "${LLAMA_URL:?set LLAMA_URL}"
: "${WYRD_SERVER_URL:?set WYRD_SERVER_URL}"
: "${WYRD_USERNAME:?set WYRD_USERNAME}"
: "${WYRD_PASSWORD:?set WYRD_PASSWORD}"

cd "$ROOT_DIR"
export PATH="$HOME/.maestro/bin:$PATH"

flows=(
    companion-reply
    study-journal
    cross-zone-tell
    ember-library
)

pass=0
fail=0
failed_names=()
report_dir="$ROOT_DIR/reports/$(date +%Y%m%d-%H%M%S)-kmp-tier3-server"
mkdir -p "$report_dir"

for flow in "${flows[@]}"; do
    echo "==== $flow ===="
    if ! LLAMA_URL="$LLAMA_URL" \
         WYRD_SERVER_URL="$WYRD_SERVER_URL" \
         WYRD_USERNAME="$WYRD_USERNAME" \
         WYRD_PASSWORD="$WYRD_PASSWORD" \
         bash "$SEED" 2>&1 | tee "$report_dir/${flow}-seed.log"; then
        echo "  seed failed; skipping"
        failed_names+=("$flow (seed)")
        fail=$((fail+1))
        continue
    fi
    sleep 12  # KMP boots slower than RN; give Compose + Pekko time
    if maestro --device emulator-5554 test \
            -e SERVER_URL="$WYRD_SERVER_URL" \
            -e CONNECT_TIMEOUT=120000 \
            -e INFERENCE_TIMEOUT=180000 \
            "flows/kmp/tier3/${flow}.yaml" 2>&1 \
            | tee "$report_dir/${flow}.log"; then
        echo "  PASS"
        pass=$((pass+1))
    else
        echo "  FAIL"
        failed_names+=("$flow")
        fail=$((fail+1))
        adb shell screencap -p "/sdcard/${flow}-fail.png" 2>/dev/null
        adb pull "/sdcard/${flow}-fail.png" "$report_dir/${flow}-fail.png" 2>/dev/null
    fi
done

echo
echo "==== Tier 3 KMP server-backed: $pass/${#flows[@]} passed ===="
echo "Reports: $report_dir"
if [ "$fail" -gt 0 ]; then
    echo "Failures: ${failed_names[*]}"
    exit 1
fi
