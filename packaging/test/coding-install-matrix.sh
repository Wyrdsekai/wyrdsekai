#!/usr/bin/env bash
# coding-install-matrix.sh — prove `wyrd coding install <backend>` works on a
# CLEAN machine, for every backend the manifest offers.
#
# Why this exists: an install that exits 0 is not evidence. A manifest entry
# can parse, list, download and extract and STILL leave a backend that never
# registers -- because registration needs the executable to be FOUND, and the
# layout inside a release tarball is nobody's promise. Both failures shipped:
#   * a sha256 map keyed "any" -> `install` died with "no sha256 entry for
#     platform 'linux-x64'", after listing perfectly happily;
#   * a tarball carrying its own top-level dir -> extract succeeded and the
#     binary sat one level deeper than anything looked.
# So the gate here is: DOES THE BINARY RUN. Not "did the command return 0".
#
# Usage (inside a clean container, or on a mac):
#   packaging/test/coding-install-matrix.sh [backend ...]
#
# Env:
#   WYRD                 wyrd entrypoint (default: wyrd on PATH)
#   WYRDSEKAI_DATA_DIR   install root (default: a fresh temp dir)
#   SKIP_NPM=1           skip npm-distributed backends (no node in this image)
#   SKIP_DOCKER=1        skip docker-helper backends (no docker in this image)
set -uo pipefail

# Word-split deliberately: WYRD may carry arguments ("bash /path/to/wyrd"),
# which a single quoted expansion would treat as one long filename.
# shellcheck disable=SC2206 -- word splitting is the point here.
WYRD_CMD=(${WYRD:-wyrd})
: "${WYRDSEKAI_DATA_DIR:=$(mktemp -d)}"
export WYRDSEKAI_DATA_DIR
BUNDLE="$WYRDSEKAI_DATA_DIR/coding-cli-bundle"

pass=0; fail=0; skip=0
declare -a FAILED=()

note() { printf '  %-12s %-8s %s\n' "$1" "$2" "${3:-}"; }

# The one question that matters: is there something here we can execute?
# Mirrors BackendExecutableResolver's bundle shapes -- a bare binary, an
# unpacked app tree, or a tarball that brought its own top directory.
find_exe() {
    local n="$1" c
    for c in "$BUNDLE/$n/$n" "$BUNDLE/$n/bin/$n" "$BUNDLE/$n/$n/bin/$n"; do
        [[ -f "$c" && -x "$c" ]] && { printf '%s' "$c"; return 0; }
    done
    # Fourth shape: the archive names the binary after the build target
    # (codex -> codex-x86_64-unknown-linux-musl). Accept a SINGLE match only.
    # bash 3.2 (macOS) has no mapfile; count and capture with plain find.
    local hits count first
    hits="$(find "$BUNDLE/$n" -maxdepth 1 -type f -perm -u+x -name "$n-*" 2>/dev/null)"
    count="$(printf '%s' "$hits" | grep -c . 2>/dev/null || printf 0)"
    if [[ "$count" -eq 1 ]]; then
        first="$(printf '%s\n' "$hits" | head -1)"
        printf '%s' "$first"; return 0
    fi
    # npm installs land on PATH rather than in the bundle.
    command -v "$n" 2>/dev/null && return 0
    return 1
}

backends_of_kind() {
    "${WYRD_CMD[@]}" coding list 2>/dev/null | awk 'NR>1 {print $2}' | grep -vE '^$'
}

main() {
    local requested=("$@")
    if [[ ${#requested[@]} -eq 0 ]]; then
        # bash 3.2 has no mapfile — read the list the portable way.
        local line
        requested=()
        while IFS= read -r line; do
            [[ -n "$line" ]] && requested+=("$line")
        done < <(backends_of_kind)
    fi
    [[ ${#requested[@]} -gt 0 ]] || { echo "no backends found via '${WYRD_CMD[*]} coding list'" >&2; exit 2; }

    echo "data dir: $WYRDSEKAI_DATA_DIR"
    echo "backends: ${requested[*]}"
    echo

    for b in "${requested[@]}"; do
        case "$b" in
            devin)                                   note "$b" SKIP "config-only — nothing to install"; skip=$((skip+1)); continue ;;
            opencode)   # Ships inside the .deb; `install` correctly refuses it.
                        # Still worth checking it is THERE and runnable.
                        if exe="$(find_exe "$b")" && { "$exe" --version >/dev/null 2>&1 || "$exe" --help >/dev/null 2>&1; }; then
                            note "$b" PASS "bundled: $exe"; pass=$((pass+1))
                        else
                            note "$b" SKIP "bundled — not present in this data dir"; skip=$((skip+1))
                        fi
                        continue ;;
            openhands)  [[ "${SKIP_DOCKER:-0}" = 1 ]] && { note "$b" SKIP "docker helper"; skip=$((skip+1)); continue; } ;;
            claude-sdk|gemini-cli|cline|continue)
                        [[ "${SKIP_NPM:-0}" = 1 ]] && { note "$b" SKIP "npm — no node here"; skip=$((skip+1)); continue; } ;;
        esac

        local out rc
        out="$("${WYRD_CMD[@]}" coding install "$b" 2>&1)"; rc=$?
        if [[ $rc -ne 0 ]]; then
            note "$b" FAIL "install rc=$rc: $(printf '%s' "$out" | tail -1 | cut -c1-100)"
            FAILED+=("$b: install failed — $(printf '%s' "$out" | tail -1 | cut -c1-120)")
            fail=$((fail+1)); continue
        fi

        local exe
        if ! exe="$(find_exe "$b")"; then
            # THE case this harness exists for: install said fine, nothing runnable.
            note "$b" FAIL "installed but no executable found under $BUNDLE/$b"
            FAILED+=("$b: install returned 0 but produced no runnable binary")
            fail=$((fail+1)); continue
        fi

        if "$exe" --version >/dev/null 2>&1 || "$exe" --help >/dev/null 2>&1; then
            note "$b" PASS "$exe"
            pass=$((pass+1))
        else
            note "$b" FAIL "found $exe but it does not run"
            FAILED+=("$b: $exe is present but --version/--help both failed")
            fail=$((fail+1))
        fi
    done

    echo
    echo "pass=$pass fail=$fail skip=$skip"
    if (( fail )); then
        echo
        echo "failures:"
        printf '  - %s\n' "${FAILED[@]}"
        exit 1
    fi
}

main "$@"
