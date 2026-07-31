#!/usr/bin/env bash
# Generate household CA (one-time) + leaf cert with LAN SANs.
# Idempotent: if ca.crt and leaf.crt exist and leaf is still valid for at
# least 30 days, do nothing. Otherwise regenerate the leaf (CA is preserved).
#
# Inputs (env):
#   CERT_DIR         output directory (default /certs)
#   RELAY_HOST_NAMES comma-separated DNS SANs (e.g. "relay-node.local,relay.home.arpa")
#   RELAY_HOST_IPS   comma-separated IP SANs (e.g. "198.51.100.39,192.0.2.108")
#   CA_DAYS          CA lifetime (default 3650)
#   LEAF_DAYS        leaf lifetime (default 365)
#
# Outputs in CERT_DIR:
#   ca.key, ca.crt        household root CA
#   leaf.key, leaf.crt    server cert
#   chain.crt             leaf + CA — what Caddy actually serves. Sending
#                         the CA in the TLS chain lets phones pin the CA
#                         from a wyrdphone:// invite fingerprint without
#                         any cleartext /ca.crt bootstrap (§10.9).
#

set -euo pipefail

CERT_DIR="${CERT_DIR:-/certs}"
CA_DAYS="${CA_DAYS:-3650}"
LEAF_DAYS="${LEAF_DAYS:-365}"
RELAY_HOST_NAMES="${RELAY_HOST_NAMES:-localhost}"
RELAY_HOST_IPS="${RELAY_HOST_IPS:-127.0.0.1}"

mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

# 1. CA — generate once, preserve forever.
if [ ! -f ca.crt ] || [ ! -f ca.key ]; then
    echo "[certinit] generating household CA (lifetime=${CA_DAYS}d)"
    openssl genrsa -out ca.key 4096
    openssl req -x509 -new -nodes -key ca.key -sha256 -days "$CA_DAYS" \
        -subj "/CN=Wyrdsekai Household CA" \
        -out ca.crt
    chmod 600 ca.key
else
    echo "[certinit] CA already exists, preserving"
fi

# 2. Leaf — regenerate if missing or expiring within 30 days.
needs_leaf=1
if [ -f leaf.crt ] && [ -f leaf.key ]; then
    if openssl x509 -checkend 2592000 -noout -in leaf.crt >/dev/null 2>&1; then
        # Also check SANs match current env — if operator added a new IP we need new cert.
        existing_sans=$(openssl x509 -in leaf.crt -noout -ext subjectAltName 2>/dev/null | tr -d ' \n' || true)
        wanted_sig="${RELAY_HOST_NAMES}|${RELAY_HOST_IPS}"
        if [ -f leaf.sig ] && [ "$(cat leaf.sig)" = "$wanted_sig" ]; then
            echo "[certinit] leaf cert valid >=30d and SANs match, skipping"
            needs_leaf=0
        else
            echo "[certinit] leaf SANs changed, regenerating"
        fi
    else
        echo "[certinit] leaf cert expiring within 30 days, regenerating"
    fi
fi

if [ "$needs_leaf" = "1" ]; then
    # Build SAN list.
    san_lines=()
    IFS=',' read -ra names <<< "$RELAY_HOST_NAMES"
    for n in "${names[@]}"; do
        n=$(echo "$n" | xargs)
        [ -n "$n" ] && san_lines+=("DNS:$n")
    done
    IFS=',' read -ra ips <<< "$RELAY_HOST_IPS"
    for ip in "${ips[@]}"; do
        ip=$(echo "$ip" | xargs)
        [ -n "$ip" ] && san_lines+=("IP:$ip")
    done
    san_csv=$(IFS=','; echo "${san_lines[*]}")
    echo "[certinit] leaf SANs: $san_csv"

    cat > leaf.cnf <<EOF
[req]
distinguished_name = dn
req_extensions = v3_req
prompt = no
[dn]
CN = wyrdsekai-relay
[v3_req]
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $san_csv
EOF

    openssl genrsa -out leaf.key 2048
    openssl req -new -key leaf.key -out leaf.csr -config leaf.cnf
    openssl x509 -req -in leaf.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
        -out leaf.crt -days "$LEAF_DAYS" -sha256 -extensions v3_req -extfile leaf.cnf
    rm -f leaf.csr leaf.cnf
    chmod 600 leaf.key

    # Record the SAN signature so we know when env changes.
    echo "${RELAY_HOST_NAMES}|${RELAY_HOST_IPS}" > leaf.sig
fi

# Full chain for Caddy: clients receive the CA alongside the leaf, so a
# device holding only the invite's ca_fp fingerprint can verify + pin the
# CA straight from the handshake. Rebuilt unconditionally — cheap, and it
# self-heals volumes created before chain.crt existed.
cat leaf.crt ca.crt > chain.crt

# Caddy needs to read leaf.key — ensure it can.
chmod 644 ca.crt leaf.crt chain.crt
chmod 600 ca.key leaf.key
echo "[certinit] done. Files in $CERT_DIR:"
ls -la
