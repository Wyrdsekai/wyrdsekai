#!/usr/bin/env bash
#
# fetch-build-assets.sh — pull the build-time assets that cannot live in git.
#
# WHY: a package needs several large files that are gitignored — upstream
# release binaries and multi-megabyte model files. Every packager stages them
# behind a fail-soft `[[ -f ... ]]` guard, which is right for an optional
# extra and wrong for a required one: a clone of the public repository built a
# package that was missing ~580MB and reported success, with the omissions
# spread across scattered warning lines nobody reads in a 2000-line build log.
#
# Driven by packaging/build-assets.json so adding an asset means adding a JSON
# entry, not writing another near-identical fetch script. Each entry pins a
# sha256 and a commit revision — never a branch — so two builds a month apart
# contain the same bytes.
#
# Assets with their own upstream (llama.cpp, nats-server) keep their own
# fetchers and are NOT listed here: mirroring someone else's release binary
# when they publish one is a liability, not a convenience.
#
# Usage:
#   ./packaging/fetch-build-assets.sh          # fetch whatever is missing
#   ./packaging/fetch-build-assets.sh --check  # report only, change nothing
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
MANIFEST="$SCRIPT_DIR/build-assets.json"

CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

info() { echo -e "\033[36m[assets]\033[0m $*"; }
ok()   { echo -e "\033[32m[assets]\033[0m $*"; }
warn() { echo -e "\033[33m[assets]\033[0m $*" >&2; }
err()  { echo -e "\033[31m[assets]\033[0m $*" >&2; }

# sha256, portably. `sha256sum` is GNU coreutils and is NOT on a stock macOS —
# only `shasum` and `openssl` are guaranteed there. Hardcoding sha256sum made
# these scripts Linux-only, which defeats the point: the whole reason they exist
# is so that someone who is not us can build a package.
_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    else
        echo "no sha256 tool available (need sha256sum, shasum or openssl)" >&2
        return 1
    fi
}

[[ -f "$MANIFEST" ]] || { err "manifest not found: $MANIFEST"; exit 1; }

missing=0
fetched=0
present=0

while IFS=$'\t' read -r file sha size repo rev hfpath dest executable; do
    [[ -z "$file" ]] && continue
    target="$PROJECT_DIR/$dest/$file"

    if [[ -f "$target" ]]; then
        got=$(_sha256 "$target")
        if [[ "$got" == "$sha" ]]; then
            present=$((present + 1))
            continue
        fi
        warn "$file is present but does not match the pinned checksum — refetching"
    fi

    if $CHECK_ONLY; then
        err "MISSING: $dest/$file"
        missing=$((missing + 1))
        continue
    fi

    url="https://huggingface.co/${repo}/resolve/${rev}/${hfpath}"
    info "fetching $file"
    mkdir -p "$(dirname "$target")"
    if ! curl -fL --http1.1 --retry 5 --retry-delay 3 --retry-all-errors \
              --progress-bar -C - -o "$target.part" "$url"; then
        err "download failed: ${url#https://}"
        rm -f "$target.part"
        missing=$((missing + 1))
        continue
    fi

    got=$(_sha256 "$target.part")
    if [[ "$got" != "$sha" ]]; then
        err "$file: checksum mismatch (got $got, want $sha) — discarding"
        rm -f "$target.part"
        missing=$((missing + 1))
        continue
    fi

    mv "$target.part" "$target"
    [[ "$executable" == "True" ]] && chmod +x "$target"
    fetched=$((fetched + 1))
done < <(python3 - "$MANIFEST" <<'PY'
import json, sys
for a in json.load(open(sys.argv[1]))["assets"]:
    print("\t".join(str(a[k]) for k in
          ("file", "sha256", "size", "hf_repo", "hf_revision", "hf_path", "dest", "executable")))
PY
)

if (( missing )); then
    err "$missing asset(s) unavailable — the resulting package would be incomplete"
    exit 1
fi
ok "build assets ready ($present already present, $fetched fetched)"
