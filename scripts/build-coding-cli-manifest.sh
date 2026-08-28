#!/usr/bin/env bash
#
# build-coding-cli-manifest.sh — refresh data/coding-cli-bundle/manifest.json
# with the latest pinned version + per-platform sha256 of every downloadable
# coding-CLI backend.
#
# Run at release time, before tagging:
#   ./scripts/build-coding-cli-manifest.sh             # refresh all
#   ./scripts/build-coding-cli-manifest.sh goose codex # refresh subset
#
# Test mode (smoke-test against a local fixture upstream):
#   WYRD_BUILD_HELPER_TEST_MODE=1 ./scripts/build-coding-cli-manifest.sh ...
#       — skips real network; reads upstream stand-in JSON / archives from
#       $WYRD_BUILD_HELPER_TEST_FIXTURES. Used by
#       scripts/test-build-coding-cli-manifest.sh.
#
# Output: rewrites data/coding-cli-bundle/manifest.json in-place. A
# timestamped backup is written next to it. Exits non-zero if any
# backend fails (so CI can gate on a clean refresh).
#
# Per-backend asset shape notes (researched May 2026):
# - goose:      GitHub releases under aaif-goose/goose (NOT block/goose —
#               repo migrated to AAIF/Linux Foundation 2026), Rust target
#               triples (e.g. goose-x86_64-unknown-linux-gnu.tar.gz).
#               Tag = v1.33.1 (Apr 29 2026).
# - codex:      GitHub releases, Rust target triples
#               (codex-aarch64-apple-darwin.tar.gz). Tag prefixed `rust-v`.
# - gemini-cli: npm package @google/gemini-cli, ONE platform-independent
#               tarball. Pin >= 0.40.1 (CVSS-10 RCE; floor cherry-picked
#               2026-04-30 in v0.40.1).
# - claude-sdk: npm package @anthropic-ai/claude-code (manifest v2:
#               distribution=npm). The build helper bumps version + sha
#               via the npm registry.
# - continue:   npm package @continuedev/cli (binary `cn`); requires
#               Node.js 20+. GitHub releases ship .vsix only — IDE plugin.
# - cline:      npm package `cline` v2.18+ (manifest v2: distribution=npm).
#               GitHub releases ship .vsix only — IDE plugin.
# - openhands:  manifest entry only — V1 Agent Server REST+WS, port 8000.
#               Docker image (legacy runtime) is informational.
# - devin:      config-only, nothing to fetch.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$REPO_ROOT/data/coding-cli-bundle/manifest.json"

if [[ ! -f "$MANIFEST" ]]; then
    echo "manifest not found: $MANIFEST" >&2
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required (apt install jq / brew install jq)" >&2
    exit 1
fi
if ! command -v sha256sum >/dev/null 2>&1; then
    if command -v shasum >/dev/null 2>&1; then
        sha256sum() { shasum -a 256 "$@"; }
        export -f sha256sum
    else
        echo "sha256sum or shasum is required" >&2
        exit 1
    fi
fi

BACKUP="$MANIFEST.$(date -u +%Y%m%dT%H%M%SZ).bak"
cp "$MANIFEST" "$BACKUP"
echo "backup: $BACKUP"

WORKDIR="$(mktemp -d -t wyrd-cli-manifest.XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

# Platforms we publish hashes for. Must match keys in sha256_per_platform.
PLATFORMS=(linux-x64 linux-arm64 darwin-arm64 darwin-x64 windows-x64)

# Map our platform-arch keys → Rust target triples (used by goose, codex).
rust_triple() {
    case "$1" in
        linux-x64)    echo "x86_64-unknown-linux-gnu" ;;
        linux-arm64)  echo "aarch64-unknown-linux-gnu" ;;
        darwin-arm64) echo "aarch64-apple-darwin" ;;
        darwin-x64)   echo "x86_64-apple-darwin" ;;
        windows-x64)  echo "x86_64-pc-windows-msvc" ;;
        *) echo "" ;;
    esac
}

# Map our platform-arch keys → musl variant (codex uses linux-musl).
rust_triple_musl() {
    case "$1" in
        linux-x64)    echo "x86_64-unknown-linux-musl" ;;
        linux-arm64)  echo "aarch64-unknown-linux-musl" ;;
        darwin-arm64) echo "aarch64-apple-darwin" ;;
        darwin-x64)   echo "x86_64-apple-darwin" ;;
        windows-x64)  echo "x86_64-pc-windows-msvc" ;;
        *) echo "" ;;
    esac
}

# ── Generic helpers ──────────────────────────────────────────────────

# Fetch with retry. Returns 0 if the URL streamed cleanly to $out.
curl_get() {
    local url="$1" out="$2"
    if [[ "${WYRD_BUILD_HELPER_TEST_MODE:-0}" == "1" ]]; then
        local stub="${WYRD_BUILD_HELPER_TEST_FIXTURES:-}/$(echo "$url" \
            | sed 's|https\?://||; s|[/?:]|_|g')"
        if [[ -f "$stub" ]]; then cp "$stub" "$out"; return 0; fi
        return 22
    fi
    curl -fSL --retry 3 --retry-delay 5 -o "$out" "$url"
}

# Stream URL → file, sha256-sum it. Echoes the lowercase hex on success.
fetch_and_sha256() {
    local url="$1" out="$2"
    if curl_get "$url" "$out"; then
        sha256sum "$out" | awk '{print $1}'
    else
        echo "DOWNLOAD_FAILED"
    fi
}

# Probe a URL with HEAD; returns 0 if 2xx/3xx.
url_exists() {
    local url="$1"
    if [[ "${WYRD_BUILD_HELPER_TEST_MODE:-0}" == "1" ]]; then
        local stub="${WYRD_BUILD_HELPER_TEST_FIXTURES:-}/$(echo "$url" \
            | sed 's|https\?://||; s|[/?:]|_|g')"
        [[ -f "$stub" ]]; return $?
    fi
    curl -fsLI -o /dev/null --retry 2 --retry-delay 2 "$url" >/dev/null 2>&1
}

# Write a sha256 entry into the manifest for one (backend, platform).
update_sha() {
    local backend="$1" platform="$2" sha="$3"
    local tmp; tmp="$(mktemp)"
    jq --arg b "$backend" --arg p "$platform" --arg s "$sha" \
        '.backends[$b].sha256_per_platform[$p] = $s' "$MANIFEST" > "$tmp"
    mv "$tmp" "$MANIFEST"
}

# Write a version pin into the manifest.
update_version() {
    local backend="$1" version="$2"
    local tmp; tmp="$(mktemp)"
    jq --arg b "$backend" --arg v "$version" \
        '.backends[$b].version = $v' "$MANIFEST" > "$tmp"
    mv "$tmp" "$MANIFEST"
}

# Set (or replace) a backend's download_url_template.
update_url_template() {
    local backend="$1" url="$2"
    local tmp; tmp="$(mktemp)"
    jq --arg b "$backend" --arg u "$url" \
        '.backends[$b].download_url_template = $u' "$MANIFEST" > "$tmp"
    mv "$tmp" "$MANIFEST"
}

# Resolve the latest tag for a GitHub repo. Echoes the tag (e.g. "v1.33.1").
github_latest_tag() {
    local repo="$1"
    local stub_url="https://api.github.com/repos/${repo}/releases/latest"
    local resp; resp="$(curl_get_to_string "$stub_url")" || return 1
    echo "$resp" | jq -r '.tag_name // empty'
}

# Helper: like curl_get but echoes body to stdout (for JSON APIs).
curl_get_to_string() {
    local url="$1"
    if [[ "${WYRD_BUILD_HELPER_TEST_MODE:-0}" == "1" ]]; then
        local stub="${WYRD_BUILD_HELPER_TEST_FIXTURES:-}/$(echo "$url" \
            | sed 's|https\?://||; s|[/?:]|_|g')"
        if [[ -f "$stub" ]]; then cat "$stub"; return 0; fi
        return 22
    fi
    curl -fsSL --retry 3 --retry-delay 5 "$url"
}

# ── Per-backend fetchers ─────────────────────────────────────────────

fetch_goose() {
    # 2026-05-04: repo moved block/goose -> aaif-goose/goose under
    # AAIF/Linux Foundation. Latest stable v1.33.1 (Apr 29 2026).
    local repo="aaif-goose/goose"
    local tag; tag="$(github_latest_tag "$repo")" || {
        echo "[goose] FATAL: failed to query $repo latest release" >&2
        return 1
    }
    if [[ -z "$tag" ]]; then
        echo "[goose] FATAL: no tag_name in API response" >&2
        return 1
    fi
    local version="${tag#v}"
    echo "[goose] tag=$tag version=$version"

    # Asset naming: goose-<rust-triple>.tar.gz (Windows uses .zip upstream;
    # build helper substitutes per-platform suffix).
    update_url_template goose \
        "https://github.com/${repo}/releases/download/${tag}/goose-{rust_triple}.tar.gz"

    local missing=()
    for p in "${PLATFORMS[@]}"; do
        local triple; triple="$(rust_triple "$p")"
        if [[ -z "$triple" ]]; then
            echo "[goose]   skip $p — no Rust triple mapping"
            missing+=("$p")
            continue
        fi
        local url="https://github.com/${repo}/releases/download/${tag}/goose-${triple}.tar.gz"
        local out="$WORKDIR/goose-${p}.tar.gz"
        local sha; sha="$(fetch_and_sha256 "$url" "$out")"
        if [[ "$sha" == "DOWNLOAD_FAILED" ]]; then
            echo "[goose]   WARN: $p asset missing at $url — skipping"
            missing+=("$p")
            continue
        fi
        echo "[goose]   $p $sha"
        update_sha goose "$p" "$sha"
    done
    update_version goose "$version"
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "[goose] missing platforms: ${missing[*]}"
    fi
}

fetch_codex() {
    local repo="openai/codex"
    local tag; tag="$(github_latest_tag "$repo")" || {
        echo "[codex] FATAL: failed to query $repo latest release" >&2
        return 1
    }
    if [[ -z "$tag" ]]; then
        echo "[codex] FATAL: no tag_name in API response" >&2
        return 1
    fi
    # Codex tags look like "rust-v0.128.0"; strip both prefixes.
    local version="${tag#rust-v}"
    version="${version#v}"
    echo "[codex] tag=$tag version=$version"

    update_url_template codex \
        "https://github.com/${repo}/releases/download/${tag}/codex-{rust_triple_musl}.tar.gz"

    local missing=()
    for p in "${PLATFORMS[@]}"; do
        local triple; triple="$(rust_triple_musl "$p")"
        if [[ -z "$triple" ]]; then
            echo "[codex]   skip $p — no Rust triple mapping"
            missing+=("$p")
            continue
        fi
        # Windows asset uses .exe.tar.gz.
        local suffix=".tar.gz"
        if [[ "$p" == windows-* ]]; then suffix=".exe.tar.gz"; fi
        local url="https://github.com/${repo}/releases/download/${tag}/codex-${triple}${suffix}"
        local out="$WORKDIR/codex-${p}.tar.gz"
        local sha; sha="$(fetch_and_sha256 "$url" "$out")"
        if [[ "$sha" == "DOWNLOAD_FAILED" ]]; then
            echo "[codex]   WARN: $p asset missing at $url — skipping"
            missing+=("$p")
            continue
        fi
        echo "[codex]   $p $sha"
        update_sha codex "$p" "$sha"
    done
    update_version codex "$version"
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "[codex] missing platforms: ${missing[*]}"
    fi
}

# Common helper for npm-distributed CLIs (manifest v2:
# distribution=npm + npm_package). The build helper bumps version + sha
# via the npm registry; the installer (BundleInstaller.installFromNpm)
# shells out to `npm install -g <pkg>@<version>` at runtime — npm has
# its own integrity, so we don't need a manifest sha256 map. The version
# query also enforces a CVSS-floor for backends that need it (gemini-cli).
fetch_npm_backend() {
    local backend="$1" pkg="$2" min_version="${3:-}"
    local resp; resp="$(curl_get_to_string "https://registry.npmjs.org/${pkg}/latest")" || {
        echo "[$backend] FATAL: npm registry query failed for $pkg" >&2
        return 1
    }
    local version; version="$(echo "$resp" | jq -r '.version // empty')"
    if [[ -z "$version" ]]; then
        echo "[$backend] FATAL: npm response missing version" >&2
        return 1
    fi
    echo "[$backend] pkg=$pkg version=$version"

    if [[ -n "$min_version" ]]; then
        if ! version_ge "$version" "$min_version"; then
            echo "[$backend] FATAL: upstream version $version < pinned min $min_version." >&2
            echo "[$backend]   This is a CVSS-10 reminder — DO NOT relax this floor." >&2
            return 1
        fi
    fi

    # Manifest v2 npm shape: declare distribution + npm_package, drop the
    # github-release URL template + sha256 map (npm has its own integrity).
    update_distribution "$backend" "npm"
    update_npm_package "$backend" "$pkg"
    clear_url_and_sha "$backend"
    update_version "$backend" "$version"
}

# Set .backends.<backend>.distribution
update_distribution() {
    local backend="$1" distribution="$2"
    local tmp; tmp="$(mktemp)"
    jq --arg b "$backend" --arg d "$distribution" \
        '.backends[$b].distribution = $d' "$MANIFEST" > "$tmp"
    mv "$tmp" "$MANIFEST"
}

# Set .backends.<backend>.npm_package
update_npm_package() {
    local backend="$1" pkg="$2"
    local tmp; tmp="$(mktemp)"
    jq --arg b "$backend" --arg p "$pkg" \
        '.backends[$b].npm_package = $p' "$MANIFEST" > "$tmp"
    mv "$tmp" "$MANIFEST"
}

# Drop legacy fields (download_url_template, sha256_per_platform) when
# switching a backend to npm distribution. Keeps the manifest tidy and
# guarantees v1 fields don't shadow v2 ones for tooling that checks both.
clear_url_and_sha() {
    local backend="$1"
    local tmp; tmp="$(mktemp)"
    jq --arg b "$backend" \
        'del(.backends[$b].download_url_template) | del(.backends[$b].sha256_per_platform)' \
        "$MANIFEST" > "$tmp"
    mv "$tmp" "$MANIFEST"
}

# semver compare: returns 0 if $1 >= $2.
version_ge() {
    [[ "$1" == "$2" ]] && return 0
    local lo; lo="$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n1)"
    [[ "$lo" == "$2" ]]
}

fetch_claude_sdk() {
    fetch_npm_backend claude-sdk "@anthropic-ai/claude-code"
}

fetch_gemini_cli() {
    # 2026-05-04: floor bumped 0.40.0 -> 0.40.1. CVSS-10 RCE patches landed
    # in 0.39.1 / 0.40.0-preview.3, with v0.40.1 the cherry-pick floor
    # (2026-04-30). DO NOT relax this floor.
    fetch_npm_backend gemini-cli "@google/gemini-cli" "0.40.1"
}

fetch_continue() {
    # 2026-05-04: distribution = npm @continuedev/cli (binary `cn`);
    # requires Node.js 20+. GitHub releases ship .vsix only.
    fetch_npm_backend continue "@continuedev/cli"
}

fetch_cline() {
    # 2026-05-04: distribution = npm `cline` (cline.bot/blog/announcing-
    # cline-cli-2-0). GitHub releases ship the IDE .vsix only.
    fetch_npm_backend cline "cline"
}

fetch_openhands() {
    # Manifest entry only — Docker image pulled via `wyrd setup openhands`.
    # We don't pin a sha256 (Docker images are immutable by digest, but
    # the install path is `docker pull`, not download+verify). Probe the
    # ghcr manifest URL to confirm the tag is resolvable; that's all.
    local image
    image="$(jq -r '.backends.openhands.docker_image // empty' "$MANIFEST")"
    if [[ -z "$image" ]]; then
        echo "[openhands] no docker_image set in manifest — leaving as-is"
        return 0
    fi
    # ghcr.io/all-hands-ai/openhands:vX.Y.Z → namespace + tag.
    local namespace; namespace="$(echo "$image" | sed -E 's|^ghcr\.io/([^:]+):.*|\1|')"
    local tag;       tag="$(echo "$image" | sed -E 's|^[^:]+:(.*)$|\1|')"
    if [[ -z "$namespace" || -z "$tag" || "$namespace" == "$image" ]]; then
        echo "[openhands] WARN: docker_image '$image' not in ghcr.io/<ns>:<tag> form"
        return 0
    fi
    local probe="https://ghcr.io/v2/${namespace}/manifests/${tag}"
    if url_exists "$probe"; then
        echo "[openhands] OK: docker manifest reachable for $image"
    else
        echo "[openhands] WARN: docker manifest unreachable: $probe"
        echo "[openhands]   (Anonymous ghcr.io HEAD may be auth-gated; not fatal.)"
    fi
}

fetch_codezaiku() {
    # CodeZaiku publishes ONE platform-independent tarball -- it is a JVM app,
    # so there is no per-platform asset and no triple to substitute. The sha
    # therefore lands under the single key "any" rather than per platform.
    local repo="Wyrdsekai/codezaiku"
    local tag; tag="$(github_latest_tag "$repo")" || {
        echo "[codezaiku] FATAL: failed to query $repo latest release" >&2
        return 1
    }
    if [[ -z "$tag" ]]; then
        echo "[codezaiku] FATAL: no tag_name in API response" >&2
        return 1
    fi
    local version="${tag#v}"
    echo "[codezaiku] tag=$tag version=$version"

    local tarball="codezaiku-${version}.tar.gz"
    update_url_template codezaiku \
        "https://github.com/${repo}/releases/download/${tag}/${tarball}"

    # Take the checksum from the release's OWN SHA256SUMS rather than hashing a
    # download here: it is the same file the upstream installer verifies
    # against, so the manifest and the installer cannot disagree about what a
    # good artifact is.
    local sums; sums="$(curl -fsSL \
        "https://github.com/${repo}/releases/download/${tag}/SHA256SUMS" 2>/dev/null)" || {
        echo "[codezaiku] FATAL: no SHA256SUMS in $tag — refusing to record an unverified sha" >&2
        return 1
    }
    local sha; sha="$(printf '%s\n' "$sums" | awk -v f="$tarball" '$2 == f {print $1}' | head -1)"
    if [[ -z "$sha" ]]; then
        echo "[codezaiku] FATAL: $tarball not listed in SHA256SUMS" >&2
        return 1
    fi
    # One artifact for every platform, but the installer keys on
    # "<platform>-<arch>" and has NO platform-independent key -- so record the
    # same sha under each. Writing a single "any" key parses fine and then
    # fails at install time with "no sha256 entry for platform 'linux-x64'".
    for pl in "${PLATFORMS[@]}"; do
        update_sha codezaiku "$pl" "$sha"
    done
    update_version codezaiku "$version"
    echo "[codezaiku]   all platforms -> $sha"
}

fetch_devin() {
    echo "[devin] config-only backend — nothing to fetch"
}

# ── Main ─────────────────────────────────────────────────────────────

requested=("${@+$@}")
if [[ ${#requested[@]} -eq 0 ]]; then
    requested=(goose codezaiku codex claude-sdk gemini-cli cline continue openhands devin)
fi

failures=0
for backend in "${requested[@]}"; do
    case "$backend" in
        goose)        fetch_goose      || failures=$((failures + 1)) ;;
        codezaiku)    fetch_codezaiku  || failures=$((failures + 1)) ;;
        codex)        fetch_codex      || failures=$((failures + 1)) ;;
        claude-sdk)   fetch_claude_sdk || failures=$((failures + 1)) ;;
        gemini-cli)   fetch_gemini_cli || failures=$((failures + 1)) ;;
        cline)        fetch_cline      || failures=$((failures + 1)) ;;
        continue)     fetch_continue   || failures=$((failures + 1)) ;;
        openhands)    fetch_openhands  || failures=$((failures + 1)) ;;
        devin)        fetch_devin      || failures=$((failures + 1)) ;;
        *) echo "unknown backend: $backend" >&2; exit 1 ;;
    esac
done

echo
echo "Manifest refreshed. Diff:"
diff -u "$BACKUP" "$MANIFEST" || true

if [[ $failures -gt 0 ]]; then
    echo
    echo "FAILED: $failures backend(s) reported errors. Manifest left at current state;" >&2
    echo "        backup preserved at $BACKUP for rollback." >&2
    exit 1
fi
