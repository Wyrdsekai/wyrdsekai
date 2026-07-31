#!/usr/bin/env bash
#
# publish-release.sh — attach the installers to a GitHub Release.
#
# WHY GitHub Releases: the installers are several gigabytes each, and a project
# distributing them from its own hosting is one popular week away from a
# download page nobody can reach. GitHub carries that traffic.
#
# WHAT DOES NOT MOVE: the model weights. GitHub caps a release asset at 2 GiB
# and both GGUFs are larger, so models-index.json keeps its own URLs and this
# script does not touch it.
#
# Usage:
#   scripts/publish-release.sh                 # dry run — shows what would happen
#   scripts/publish-release.sh --go            # create/update the release + upload
#   scripts/publish-release.sh --go --draft    # upload as a draft first (recommended)
#
# Auth, either one:
#   gh auth login                              # the gh CLI, if installed
#   export GITHUB_TOKEN=ghp_...                # a PAT with `contents: write`
#
set -euo pipefail

REPO="${WYRDSEKAI_GH_REPO:-Wyrdsekai/wyrdsekai}"
VERSION="${WYRDSEKAI_VERSION:-0.1.0}"
TAG="v${VERSION}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/installers"

# GitHub refuses an asset at or above 2 GiB. The .deb is the one to watch: at
# 1.79 GiB it has roughly 220MB of headroom, and the bundle only grows.
MAX_BYTES=$((2 * 1024 * 1024 * 1024))

ASSETS=(
    "wyrdsekai_${VERSION}_amd64.deb"
    "Wyrdsekai-${VERSION}.pkg"
    "Wyrdsekai-${VERSION}.msi"
    "wyrdsekai-relay-${VERSION}.tar.gz"
)

GO=false
DRAFT=""
for arg in "$@"; do
    case "$arg" in
        --go)    GO=true ;;
        --draft) DRAFT="--draft" ;;
    esac
done

info() { echo -e "\033[36m[release]\033[0m $*"; }
ok()   { echo -e "\033[32m[release]\033[0m $*"; }
err()  { echo -e "\033[31m[release]\033[0m $*" >&2; }

# ── Preflight: every asset present, readable, and under the cap ──────────────
missing=0
for a in "${ASSETS[@]}"; do
    f="$DIR/$a"
    if [[ ! -f "$f" ]]; then
        err "missing: $a"
        missing=1
        continue
    fi
    bytes=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")
    gib=$(awk -v b="$bytes" 'BEGIN{printf "%.2f", b/1073741824}')
    if (( bytes >= MAX_BYTES )); then
        err "$a is ${gib} GiB — at or over GitHub's 2 GiB asset cap. It cannot be a release asset."
        missing=1
    else
        info "$(printf '%-34s %s GiB' "$a" "$gib")"
    fi
done
(( missing )) && { err "preflight failed — nothing uploaded"; exit 1; }

# ── Checksums over exactly the set being published ───────────────────────────
# Regenerated here rather than reused: a stale SHA256SUMS that verifies the
# previous build is worse than none, because it fails closed on honest users
# and teaches them to skip the check.
info "Generating SHA256SUMS over the release set…"
# Portable: a release can be cut from macOS, where sha256sum may not exist.
( cd "$DIR" && if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "${ASSETS[@]}" > SHA256SUMS
  else
      shasum -a 256 "${ASSETS[@]}" > SHA256SUMS
  fi )
ASSETS+=("SHA256SUMS")
ok "SHA256SUMS written ($(wc -l < "$DIR/SHA256SUMS") entries)"

if ! $GO; then
    echo
    info "DRY RUN. Would publish to ${REPO} as ${TAG}:"
    for a in "${ASSETS[@]}"; do echo "    $a"; done
    echo
    info "Re-run with --go (add --draft to stage it privately first)."
    exit 0
fi

# ── Publish ─────────────────────────────────────────────────────────────────
if command -v gh &>/dev/null; then
    info "Using the gh CLI."
    if gh release view "$TAG" --repo "$REPO" &>/dev/null; then
        info "Release $TAG exists — uploading with --clobber."
        ( cd "$DIR" && gh release upload "$TAG" "${ASSETS[@]}" --repo "$REPO" --clobber )
    else
        info "Creating release $TAG."
        ( cd "$DIR" && gh release create "$TAG" "${ASSETS[@]}" \
            --repo "$REPO" $DRAFT \
            --title "Wyrdsekai $VERSION" \
            --notes "Installers for Linux, macOS and Windows, plus the relay bundle.

Model weights are fetched separately on first \`wyrd start\` and are not release
assets — they exceed GitHub's 2 GiB per-file limit.

Verify before you run:
\`\`\`
sha256sum -c SHA256SUMS --ignore-missing
\`\`\`" )
    fi
elif [[ -n "${GITHUB_TOKEN:-}" ]]; then
    err "gh is not installed. The REST path needs a multi-step upload; install gh:"
    err "  sudo apt install gh   (or: https://cli.github.com)"
    err "  gh auth login"
    exit 1
else
    err "No auth. Either install gh and run 'gh auth login', or export GITHUB_TOKEN."
    exit 1
fi

# ── Verify what actually landed ─────────────────────────────────────────────
# An upload that half-succeeded reports success and leaves a truncated asset,
# which is exactly the failure the checksums above are meant to catch — so check
# the sizes GitHub reports against the local files rather than trusting exit 0.
info "Verifying uploaded asset sizes against local files…"
bad=0
while read -r name size; do
    local_f="$DIR/$name"
    [[ -f "$local_f" ]] || continue
    local_size=$(stat -c%s "$local_f" 2>/dev/null || stat -f%z "$local_f")
    if [[ "$size" != "$local_size" ]]; then
        err "size mismatch for $name: local $local_size, remote $size"
        bad=1
    fi
done < <(gh release view "$TAG" --repo "$REPO" --json assets \
         --jq '.assets[] | "\(.name) \(.size)"' 2>/dev/null)
(( bad )) && { err "at least one asset is truncated — re-run to clobber it"; exit 1; }

ok "Published: https://github.com/${REPO}/releases/tag/${TAG}"
info "Download URLs take the form:"
info "  https://github.com/${REPO}/releases/download/${TAG}/<filename>"
