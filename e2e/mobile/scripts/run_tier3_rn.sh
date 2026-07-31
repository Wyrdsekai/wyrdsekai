#!/usr/bin/env bash
# Run RN Tier 3 against a real wyrdsekai server. Seeds AsyncStorage with a
# pre-authenticated session before each flow so the test can skip the
# Welcome wizard entirely (which is now gated behind LoginScreen when
# inferenceUrl is set but the user has no auth token).
#
# Expects environment:
#   LLAMA_URL          (http://home-server:8200 or similar)
#   WYRD_SERVER_URL    (http://home-server:7070)
#   WYRD_USERNAME      (pre-created via `wyrd invite create` + redeem)
#   WYRD_PASSWORD      (password from the redeem step)
#
# Run from e2e/mobile. Exits non-zero on any flow failure.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SEED="$SCRIPT_DIR/seed_phone_session.sh"

: "${LLAMA_URL:?set LLAMA_URL}"
: "${WYRD_SERVER_URL:?set WYRD_SERVER_URL}"
: "${WYRD_USERNAME:?set WYRD_USERNAME}"
: "${WYRD_PASSWORD:?set WYRD_PASSWORD}"

cd "$ROOT_DIR"

# Maestro CLI from default install location
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
report_dir="$ROOT_DIR/reports/$(date +%Y%m%d-%H%M%S)-rn-tier3-server"
mkdir -p "$report_dir"

for flow in "${flows[@]}"; do
    echo "==== $flow ===="
    # Seed AsyncStorage with a fresh server session for each flow so state
    # accumulated by the previous flow doesn't pollute this one.
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
    # Give the app a moment to start, hit Birth, transition to Standalone
    # (the seed script kicks `am start` but doesn't await).
    sleep 8
    if maestro --device emulator-5554 test \
            -e SERVER_URL="$WYRD_SERVER_URL" \
            -e CONNECT_TIMEOUT=60000 \
            -e INFERENCE_TIMEOUT=180000 \
            "flows/rn/tier3/${flow}.yaml" 2>&1 \
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
echo "==== Tier 3 RN server-backed: $pass/${#flows[@]} passed ===="
echo "Reports: $report_dir"
if [ "$fail" -gt 0 ]; then
    echo "Failures: ${failed_names[*]}"
    exit 1
fi
