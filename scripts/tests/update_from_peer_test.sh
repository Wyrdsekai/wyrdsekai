#!/usr/bin/env bash
# Nodes update each other through the mesh: `wyrd update now --from <peer url>` takes the installer's
# bytes from a household node first and the release's SHA256SUMS from the release itself; a node that
# does not have the file is passed over for the release; a wrong checksum is refused either way.
# The functions are lifted from bin/wyrd and run against a stub curl.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
for fn in _release_download_verify _keep_for_household; do
    prog="/^${fn}() {/,/^}/p"; body="$(sed -n "$prog" "$W")"; eval "$body"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
DATA_DIR="$T/data"; WYRDSEKAI_RELEASE_REPO="Wyrdsekai/wyrdsekai"
log() { echo "[log] $*"; }; err() { echo "[err] $*" >&2; }; _t() { echo "$*"; }
_release_artifact() { echo "wyrdsekai_$1_amd64.deb"; }
# A stub curl: records every url; serves what the test staged under $T/serve/<path>.
mkdir -p "$T/bin" "$T/serve"
cat > "$T/bin/curl" <<'STUB'
#!/usr/bin/env bash
out=""; url=""
all="$*"; while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift 2 ;; --cacert) ca="$2"; shift 2 ;; -*) shift ;; *) url="$1"; shift ;; esac; done
echo "$url" >> "$CURL_LOG"; echo "ARGS: $all" >> "$CURL_LOG"
key="$(printf '%s' "$url" | sed -E 's#^https?://##; s#/#_#g')"
[ -f "$SERVE/$key" ] || exit 22
[ -n "$out" ] && cp "$SERVE/$key" "$out"
exit 0
STUB
chmod +x "$T/bin/curl"; export PATH="$T/bin:$PATH" CURL_LOG="$T/curl.log" SERVE="$T/serve"
good="the installer"; sum="$(printf '%s' "$good" | sha256sum | awk '{print $1}')"
printf '%s  wyrdsekai_0.5.4_amd64.deb\n' "$sum" > "$T/serve/github.com_Wyrdsekai_wyrdsekai_releases_download_v0.5.4_SHA256SUMS"

# 1. The peer has it: bytes from the peer, sums from the release.
printf '%s' "$good" > "$T/serve/198.51.100.65:7070_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -rf "$DATA_DIR"
f="$(_release_download_verify 0.5.4 http://198.51.100.65:7070 2>/dev/null)"
check 'taken from the peer, verified against the release' '[[ "$f" == "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb" && "$(cat "$f")" == "$good" ]]'
check 'the release was asked only for SHA256SUMS' '! grep -q "releases/download/v0.5.4/wyrdsekai_0.5.4_amd64.deb" "$CURL_LOG" && grep -q "SHA256SUMS" "$CURL_LOG"'

# 2. The peer does not have it: the release serves the bytes.
rm -f "$T/serve/198.51.100.65:7070_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
printf '%s' "$good" > "$T/serve/github.com_Wyrdsekai_wyrdsekai_releases_download_v0.5.4_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -rf "$DATA_DIR"
f="$(_release_download_verify 0.5.4 http://198.51.100.65:7070 2>/dev/null)"
check 'the peer passed over, the release used' '[[ -n "$f" ]] && grep -q "api/update/artifact" "$CURL_LOG" && grep -q "releases/download/v0.5.4/wyrdsekai_0.5.4_amd64.deb" "$CURL_LOG"'

# 3. A peer serving the wrong bytes is refused by the release's checksum.
printf 'tampered' > "$T/serve/198.51.100.65:7070_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -rf "$DATA_DIR"
if _release_download_verify 0.5.4 http://198.51.100.65:7070 >/dev/null 2>&1; then rc=0; else rc=$?; fi
check 'wrong bytes from a peer are refused and removed' '[[ $rc -ne 0 && ! -f "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb" ]]'

# 4. Without a peer, nothing about peers happens.
rm -f "$T/serve/198.51.100.65:7070_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -rf "$DATA_DIR"
f="$(_release_download_verify 0.5.4 2>/dev/null)"
check 'no peer given: the release only' '[[ -n "$f" ]] && ! grep -q "api/update" "$CURL_LOG"'

# 5. The household door is HTTPS: the peer is trusted by the household CA this node holds...
mkdir -p "$DATA_DIR/tls"; printf 'ca' > "$DATA_DIR/tls/household-ca.pem"
printf '%s' "$good" > "$T/serve/198.51.100.65:7443_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -rf "$DATA_DIR/updates"
f="$(_release_download_verify 0.5.4 https://198.51.100.65:7443 2>/dev/null)"
check 'an https peer is fetched with the household CA' '[[ -n "$f" ]] && grep -q "ARGS: .*--cacert $DATA_DIR/tls/household-ca.pem .*api/update/artifact" "$CURL_LOG"'
# ...and passed over when this node has no household CA (never -k).
rm -f "$DATA_DIR/tls/household-ca.pem"; : > "$CURL_LOG"; rm -rf "$DATA_DIR/updates"
f="$(_release_download_verify 0.5.4 https://198.51.100.65:7443 2>/dev/null)"
check 'an https peer is passed over without the household CA' '[[ -n "$f" ]] && ! grep -q "api/update/artifact" "$CURL_LOG" && ! grep -q -- " -k " "$CURL_LOG"'

# 6. The release cannot be reached: the checksums another household machine passed on over the Between...
rm -f "$T/serve/github.com_Wyrdsekai_wyrdsekai_releases_download_v0.5.4_SHA256SUMS"
mkdir -p "$DATA_DIR/tls" "$DATA_DIR/updates"; printf 'ca' > "$DATA_DIR/tls/household-ca.pem"
printf '%s  wyrdsekai_0.5.4_amd64.deb\n' "$sum" > "$DATA_DIR/updates/SHA256SUMS.0.5.4.household"
printf '%s' "$good" > "$T/serve/198.51.100.65:7443_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -f "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb" "$DATA_DIR/updates/SHA256SUMS.0.5.4"
f="$(_release_download_verify 0.5.4 https://198.51.100.65:7443 2>/dev/null)"
check 'offline: verified against the checksums the household carried' '[[ -n "$f" && "$(cat "$f")" == "$good" ]]'
# ...a wrong installer is still refused by them...
printf 'tampered' > "$T/serve/198.51.100.65:7443_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
: > "$CURL_LOG"; rm -f "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb"
if _release_download_verify 0.5.4 https://198.51.100.65:7443 >/dev/null 2>&1; then rc=0; else rc=$?; fi
check 'offline: wrong bytes are refused by the carried checksums' '[[ $rc -ne 0 && ! -f "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb" ]]'
# ...and with nothing carried, an unverifiable install is refused as before.
rm -f "$DATA_DIR/updates/SHA256SUMS.0.5.4.household"; printf '%s' "$good" > "$T/serve/198.51.100.65:7443_api_update_artifact_wyrdsekai_0.5.4_amd64.deb"
if _release_download_verify 0.5.4 https://198.51.100.65:7443 >/dev/null 2>&1; then rc=0; else rc=$?; fi
check 'offline with nothing carried: refused' '[[ $rc -ne 0 ]]'
# When the release IS reachable, its checksums win over a stale carried copy.
printf '%s  wyrdsekai_0.5.4_amd64.deb\n' "$sum" > "$T/serve/github.com_Wyrdsekai_wyrdsekai_releases_download_v0.5.4_SHA256SUMS"
printf '%s  wyrdsekai_0.5.4_amd64.deb\n' "$(printf 'x' | sha256sum | awk '{print $1}')" > "$DATA_DIR/updates/SHA256SUMS.0.5.4.household"
rm -f "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb"
f="$(_release_download_verify 0.5.4 https://198.51.100.65:7443 2>/dev/null)"
check 'online: the release checksums are used, the carried copy ignored' '[[ -n "$f" && -f "$DATA_DIR/updates/SHA256SUMS.0.5.4" ]]'
rm -f "$DATA_DIR/updates/SHA256SUMS.0.5.4.household" "$DATA_DIR/tls/household-ca.pem"

# 7. An installer a person hands the node is kept for the household; a dev build is not.
rm -rf "$DATA_DIR"; mkdir -p "$T/in"; printf 'x' > "$T/in/wyrdsekai_0.5.4_amd64.deb"; printf 'y' > "$T/in/wyrdsekai_0.5.4~dev10_amd64.deb"; printf 'z' > "$T/in/notes.deb"
_keep_for_household "$T/in/wyrdsekai_0.5.4_amd64.deb"; _keep_for_household "$T/in/wyrdsekai_0.5.4~dev10_amd64.deb"; _keep_for_household "$T/in/notes.deb"
check 'a release installer is kept, a dev build and a stray file are not' '[[ -f "$DATA_DIR/updates/wyrdsekai_0.5.4_amd64.deb" && ! -f "$DATA_DIR/updates/wyrdsekai_0.5.4~dev10_amd64.deb" && ! -f "$DATA_DIR/updates/notes.deb" ]]'

[[ $fail -eq 0 ]] && echo "all passed" || { echo "some failed"; exit 1; }
