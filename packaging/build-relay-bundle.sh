#!/bin/sh
# Build the self-contained relay installer bundle (SPEC_RELAY_SIMPLE P2).
#
#   sh packaging/build-relay-bundle.sh            # → dist/wyrdsekai-relay.tar.gz
#
# The bundle is the pre-hosting distribution channel: one file you scp to
# any VPS, then
#
#   tar xzf wyrdsekai-relay.tar.gz
#   sh wyrdsekai-relay/relay.sh <domain-or-ip>[:port]
#
# Post-OSS the same relay.sh is served from the website as a curl|sh
# one-liner; this tarball is the equivalent for private/air-gapped use.

set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
OUT_DIR="$ROOT/dist"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/wyrdsekai-relay/deploy"
cp "$ROOT/packaging/relay.sh" "$STAGE/wyrdsekai-relay/relay.sh"
cp -r "$ROOT/deploy/relay" "$STAGE/wyrdsekai-relay/deploy/relay"
# Strip dev droppings the relay host doesn't need.
rm -rf "$STAGE/wyrdsekai-relay/deploy/relay/__pycache__" \
       "$STAGE/wyrdsekai-relay/deploy/relay/.pytest_cache" \
       "$STAGE/wyrdsekai-relay/deploy/relay/test_registration.py"

# The relay bundle assembles its own payload straight from deploy/relay and
# never passes through build-dist.sh, so it inherited none of that script's
# redaction — the source-built tarball shipped `masumi`, `raven` and `lain` in
# comments. That matters more here than almost anywhere else: this is the
# artifact strangers install on internet-facing machines.
#
# Third packager to need its own pass (after the .msi and the .deb). Any script
# that stages a payload independently needs this; there is no inheriting it.
# POSIX guard on purpose: this script runs under `sh` (dash). The previous
# bash-isms ([[ / &>) made dash mis-parse the condition as always-true, which
# hard-failed the build on trees without the private redaction tooling (the
# OSS export) instead of skipping redaction there.
if [ -f "$ROOT/scripts/lib/oss_redact.py" ] \
   && [ -f "$ROOT/scripts/lib/dist_redact.py" ] \
   && command -v python3 >/dev/null 2>&1; then
    echo "redacting relay bundle payload..."
    if ! python3 "$ROOT/scripts/lib/dist_redact.py" "$STAGE/wyrdsekai-relay"; then
        echo "ERROR: relay bundle redaction failed — refusing to build" >&2
        exit 1
    fi
fi

mkdir -p "$OUT_DIR"
tar -C "$STAGE" -czf "$OUT_DIR/wyrdsekai-relay.tar.gz" wyrdsekai-relay
echo "built: $OUT_DIR/wyrdsekai-relay.tar.gz ($(du -h "$OUT_DIR/wyrdsekai-relay.tar.gz" | cut -f1))"
echo ""
echo "Deploy with:"
echo "  scp $OUT_DIR/wyrdsekai-relay.tar.gz user@vps:"
echo "  ssh user@vps 'tar xzf wyrdsekai-relay.tar.gz && sh wyrdsekai-relay/relay.sh <domain-or-ip>'"
