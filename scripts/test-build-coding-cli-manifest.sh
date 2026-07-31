#!/usr/bin/env bash
#
# Smoke test for scripts/build-coding-cli-manifest.sh. Mocks every
# upstream API + asset URL so the helper runs offline against a
# canned fixture set; verifies the manifest emerges with concrete
# version pins + sha256 hashes (no TODO_RUN_BUILD_HELPER placeholders
# for the backends we exercise).
#
# We can't realistically live-test against GitHub + npm in CI on every
# commit (rate limits, upstream churn), so this smoke covers the
# helper's logic itself: URL substitution, sha computation, jq
# manifest mutations, error paths.
#
# Usage:
#   ./scripts/test-build-coding-cli-manifest.sh
#
# Returns 0 on green, non-zero on any test-case failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HELPER="$SCRIPT_DIR/build-coding-cli-manifest.sh"

if [[ ! -x "$HELPER" ]]; then
    echo "FAIL: helper not found / not executable at $HELPER" >&2
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    echo "SKIP: jq not installed; smoke test requires jq"
    exit 0
fi

WORK="$(mktemp -d -t wyrd-build-helper-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

FIXTURES="$WORK/fixtures"
mkdir -p "$FIXTURES"

# ── Fixture builders ─────────────────────────────────────────────────

# Convert a URL to its fixture filename (mirrors helper's curl_get).
# Translates `https://` → '' and `[/?:]` → `_`.
fixture_path_for_url() {
    echo "$1" | sed 's|https\?://||; s|[/?:]|_|g'
}

# Create a fixture file holding `body` for the given URL.
write_fixture() {
    local url="$1" body="$2"
    local p; p="$FIXTURES/$(fixture_path_for_url "$url")"
    printf '%s' "$body" > "$p"
}

write_fixture_bin() {
    local url="$1" src="$2"
    local p; p="$FIXTURES/$(fixture_path_for_url "$url")"
    cp "$src" "$p"
}

# Create a fake archive (some bytes), return its sha256.
make_fake_archive() {
    local out="$1" tag="$2"
    printf 'wyrdsekai-test-archive %s\n' "$tag" > "$out"
    sha256sum "$out" | awk '{print $1}'
}

# ── Fixture wiring ───────────────────────────────────────────────────

GOOSE_TAG="v9.9.9"
GOOSE_VERSION="9.9.9"
declare -A GOOSE_TRIPLE=(
    [linux-x64]=x86_64-unknown-linux-gnu
    [linux-arm64]=aarch64-unknown-linux-gnu
    [darwin-arm64]=aarch64-apple-darwin
    [darwin-x64]=x86_64-apple-darwin
    [windows-x64]=x86_64-pc-windows-msvc
)
declare -A GOOSE_SHA

setup_goose_fixtures() {
    # 2026-05-04: repo moved block/goose -> aaif-goose/goose.
    write_fixture "https://api.github.com/repos/aaif-goose/goose/releases/latest" \
        "{\"tag_name\":\"$GOOSE_TAG\"}"
    for p in "${!GOOSE_TRIPLE[@]}"; do
        local triple="${GOOSE_TRIPLE[$p]}"
        local url="https://github.com/aaif-goose/goose/releases/download/$GOOSE_TAG/goose-${triple}.tar.gz"
        local archive="$WORK/goose-${p}.tar.gz"
        local sha; sha="$(make_fake_archive "$archive" "goose-$p")"
        GOOSE_SHA[$p]="$sha"
        write_fixture_bin "$url" "$archive"
    done
}

CODEX_TAG="rust-v8.8.8"
CODEX_VERSION="8.8.8"
declare -A CODEX_TRIPLE=(
    [linux-x64]=x86_64-unknown-linux-musl
    [linux-arm64]=aarch64-unknown-linux-musl
    [darwin-arm64]=aarch64-apple-darwin
    [darwin-x64]=x86_64-apple-darwin
    [windows-x64]=x86_64-pc-windows-msvc
)
declare -A CODEX_SHA

setup_codex_fixtures() {
    write_fixture "https://api.github.com/repos/openai/codex/releases/latest" \
        "{\"tag_name\":\"$CODEX_TAG\"}"
    for p in "${!CODEX_TRIPLE[@]}"; do
        local triple="${CODEX_TRIPLE[$p]}"
        local suffix=".tar.gz"
        if [[ "$p" == windows-* ]]; then suffix=".exe.tar.gz"; fi
        local url="https://github.com/openai/codex/releases/download/$CODEX_TAG/codex-${triple}${suffix}"
        local archive="$WORK/codex-${p}.tar.gz"
        local sha; sha="$(make_fake_archive "$archive" "codex-$p")"
        CODEX_SHA[$p]="$sha"
        write_fixture_bin "$url" "$archive"
    done
}

GEMINI_VERSION="0.42.0"

setup_gemini_fixtures() {
    # 2026-05-04: manifest v2 records distribution=npm + npm_package.
    # No tarball download — npm has its own integrity at install time.
    # CVSS-10 floor is enforced by the helper (min_version="0.40.1").
    write_fixture "https://registry.npmjs.org/@google/gemini-cli/latest" \
        "{\"version\":\"$GEMINI_VERSION\"}"
}

CLAUDE_VERSION="2.1.128"
setup_claude_fixtures() {
    # 2026-05-04: manifest v2 records distribution=npm + npm_package.
    write_fixture "https://registry.npmjs.org/@anthropic-ai/claude-code/latest" \
        "{\"version\":\"$CLAUDE_VERSION\"}"
}

CONTINUE_VERSION="3.3.3"

setup_continue_fixtures() {
    # 2026-05-04: package is @continuedev/cli (binary `cn`); manifest v2
    # records `distribution=npm` + `npm_package`; no sha256 map.
    write_fixture "https://registry.npmjs.org/@continuedev/cli/latest" \
        "{\"version\":\"$CONTINUE_VERSION\"}"
}

CLINE_VERSION="2.18.5"
setup_cline_fixtures() {
    # 2026-05-04: package is `cline` on npm.
    write_fixture "https://registry.npmjs.org/cline/latest" \
        "{\"version\":\"$CLINE_VERSION\"}"
}

# ── Test runner ──────────────────────────────────────────────────────

PASS=0
FAIL=0
FAILURES=()

check() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "  ok: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "    expected: $expected"
        echo "    actual:   $actual"
        FAIL=$((FAIL + 1))
        FAILURES+=("$desc")
    fi
}

check_contains() {
    local desc="$1" haystack="$2" needle="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        echo "  ok: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc (no match for '$needle')"
        FAIL=$((FAIL + 1))
        FAILURES+=("$desc")
    fi
}

# Run helper against an isolated copy of the manifest. Returns the path
# to the modified manifest. Exit code propagates.
run_helper() {
    local backends=("$@")
    local manifest_dir="$WORK/data/coding-cli-bundle"
    mkdir -p "$manifest_dir"
    cp "$REPO_ROOT/data/coding-cli-bundle/manifest.json" "$manifest_dir/manifest.json"

    # Helper resolves $REPO_ROOT/data/... — point it at our isolated
    # workspace by copying scripts/ and the helper itself into $WORK
    # and running it from there.
    local fake_root="$WORK/fake-repo"
    mkdir -p "$fake_root/data/coding-cli-bundle" "$fake_root/scripts"
    cp "$manifest_dir/manifest.json" "$fake_root/data/coding-cli-bundle/manifest.json"
    cp "$HELPER" "$fake_root/scripts/build-coding-cli-manifest.sh"
    chmod +x "$fake_root/scripts/build-coding-cli-manifest.sh"

    local helper_rc=0
    (
        cd "$fake_root"
        WYRD_BUILD_HELPER_TEST_MODE=1 \
        WYRD_BUILD_HELPER_TEST_FIXTURES="$FIXTURES" \
            ./scripts/build-coding-cli-manifest.sh "${backends[@]}"
    ) || helper_rc=$?
    echo "$fake_root/data/coding-cli-bundle/manifest.json"
    return $helper_rc
}

# ── Scenarios ────────────────────────────────────────────────────────

setup_goose_fixtures
setup_codex_fixtures
setup_gemini_fixtures
setup_claude_fixtures
setup_continue_fixtures
setup_cline_fixtures

echo
echo "── Scenario 1: goose happy path"
out_manifest="$(run_helper goose 2>&1 | tail -1)"
manifest_after_goose="$(jq . "$out_manifest")"
check "goose version pinned" "$GOOSE_VERSION" "$(echo "$manifest_after_goose" | jq -r '.backends.goose.version')"
check "goose linux-x64 sha"   "${GOOSE_SHA[linux-x64]}"   "$(echo "$manifest_after_goose" | jq -r '.backends.goose.sha256_per_platform["linux-x64"]')"
check "goose darwin-arm64 sha" "${GOOSE_SHA[darwin-arm64]}" "$(echo "$manifest_after_goose" | jq -r '.backends.goose.sha256_per_platform["darwin-arm64"]')"

echo
echo "── Scenario 2: codex happy path (Rust target triples + rust-v tag prefix)"
out_manifest="$(run_helper codex 2>&1 | tail -1)"
manifest_after_codex="$(jq . "$out_manifest")"
check "codex version stripped of rust-v prefix" "$CODEX_VERSION" "$(echo "$manifest_after_codex" | jq -r '.backends.codex.version')"
check "codex linux-x64 sha"  "${CODEX_SHA[linux-x64]}"  "$(echo "$manifest_after_codex" | jq -r '.backends.codex.sha256_per_platform["linux-x64"]')"
check "codex windows-x64 sha (uses .exe.tar.gz suffix)" "${CODEX_SHA[windows-x64]}" "$(echo "$manifest_after_codex" | jq -r '.backends.codex.sha256_per_platform["windows-x64"]')"

echo
echo "── Scenario 3: gemini-cli happy path (npm v2 — distribution + npm_package)"
out_manifest="$(run_helper gemini-cli 2>&1 | tail -1)"
manifest_after_gemini="$(jq . "$out_manifest")"
check "gemini-cli version pinned" "$GEMINI_VERSION" "$(echo "$manifest_after_gemini" | jq -r '.backends["gemini-cli"].version')"
check "gemini-cli distribution=npm" "npm" \
    "$(echo "$manifest_after_gemini" | jq -r '.backends["gemini-cli"].distribution')"
check "gemini-cli npm_package set" "@google/gemini-cli" \
    "$(echo "$manifest_after_gemini" | jq -r '.backends["gemini-cli"].npm_package')"

echo
echo "── Scenario 4: gemini-cli REFUSES if upstream is below the CVSS-10 floor (v0.40.1)"
# Point fixtures at a 0.40.0 stand-in (below the 0.40.1 cherry-pick floor).
write_fixture "https://registry.npmjs.org/@google/gemini-cli/latest" \
    "{\"version\":\"0.40.0\"}"
set +e
out=$(run_helper gemini-cli 2>&1)
rc=$?
set -e
check "gemini-cli below floor → non-zero exit" "0" "$([ "$rc" -ne 0 ] && echo 0 || echo -1)"
check_contains "gemini-cli below floor → CVSS-10 reminder" "$out" "CVSS-10"
# Restore good fixture for downstream tests.
setup_gemini_fixtures

echo
echo "── Scenario 5: claude-sdk happy path (npm v2)"
out_manifest="$(run_helper claude-sdk 2>&1 | tail -1)"
manifest_after_claude="$(jq . "$out_manifest")"
check "claude-sdk version pinned" "$CLAUDE_VERSION" "$(echo "$manifest_after_claude" | jq -r '.backends["claude-sdk"].version')"
check "claude-sdk distribution=npm" "npm" \
    "$(echo "$manifest_after_claude" | jq -r '.backends["claude-sdk"].distribution')"
check "claude-sdk npm_package set" "@anthropic-ai/claude-code" \
    "$(echo "$manifest_after_claude" | jq -r '.backends["claude-sdk"].npm_package')"

echo
echo "── Scenario 6: continue happy path (npm @continuedev/cli, manifest v2)"
out_manifest="$(run_helper continue 2>&1 | tail -1)"
manifest_after_continue="$(jq . "$out_manifest")"
check "continue version pinned" "$CONTINUE_VERSION" "$(echo "$manifest_after_continue" | jq -r '.backends.continue.version')"
check "continue distribution=npm" "npm" \
    "$(echo "$manifest_after_continue" | jq -r '.backends.continue.distribution')"
check "continue npm_package set (binary cn)" "@continuedev/cli" \
    "$(echo "$manifest_after_continue" | jq -r '.backends.continue.npm_package')"

echo
echo "── Scenario 7: cline happy path (npm \`cline\` v2.18+, manifest v2)"
out_manifest="$(run_helper cline 2>&1 | tail -1)"
manifest_after_cline="$(jq . "$out_manifest")"
check "cline version pinned" "$CLINE_VERSION" "$(echo "$manifest_after_cline" | jq -r '.backends.cline.version')"
check "cline distribution=npm" "npm" \
    "$(echo "$manifest_after_cline" | jq -r '.backends.cline.distribution')"
check "cline npm_package set" "cline" \
    "$(echo "$manifest_after_cline" | jq -r '.backends.cline.npm_package')"

echo
echo "── Scenario 8: openhands probes manifest.json image tag and is non-fatal on miss"
# url_exists for ghcr.io will fail in test mode (no fixture) → WARN, not fail.
out=$(run_helper openhands 2>&1)
# Should still exit 0 (helper return value 0; the WARN is informational).
check_contains "openhands non-fatal probe" "$out" "openhands"

echo
echo "── Scenario 9: devin is config-only no-op"
out=$(run_helper devin 2>&1)
check_contains "devin config-only message" "$out" "config-only"

echo
echo "── Scenario 10: unknown backend exits non-zero with actionable error"
set +e
out=$(run_helper bogus-backend 2>&1)
rc=$?
set -e
check "unknown backend → non-zero exit" "0" "$([ "$rc" -ne 0 ] && echo 0 || echo -1)"
check_contains "unknown backend error message" "$out" "unknown backend"

# ── Summary ──────────────────────────────────────────────────────────

echo
echo "==========================================================="
echo "build-coding-cli-manifest.sh smoke results: $PASS passed, $FAIL failed"
if [[ $FAIL -gt 0 ]]; then
    echo "Failures:"
    for f in "${FAILURES[@]}"; do echo "  - $f"; done
    exit 1
fi
exit 0
