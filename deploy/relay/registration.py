#!/usr/bin/env python3
"""
Wyrdsekai Relay Registration Sidecar

Runs alongside NATS on a relay server. Provides:
- POST /register — generate a household token, add to NATS config, return token
- GET /status — relay capacity and registration count
- GET /relays — known peer relays (gossip cache)
- POST /announce — relay announces itself to peers

Rate limited: 1 registration per IP per hour.
Liveness: registrations hard-deleted if no NATS connect within
LIVENESS_TIMEOUT_HOURS (default 168h / 7 days). A live zone holds a
persistent connection so its last_seen stays fresh; only a zone absent the
full window is reaped, and a reconnect within the window restarts the clock.
Voluntary teardown: POST /deregister (NKey-signed) or `wyrd relay leave`.

Configuration via environment:
  RELAY_CAPACITY=500         Max households (default 500)
  RELAY_REGION=us-east       Region label
  RELAY_PUBLIC=true          Public (discoverable) or private
  RELAY_API_KEY=             Optional API key for /register
  NATS_CONF=/etc/nats/relay.conf   NATS config file path
  NATS_SIGNAL_CMD=nats-server --signal reload  Command to reload NATS
  DATA_DIR=/var/lib/wyrd-relay     Persistent storage

modes + trust tiers (all env-tunable; the
relay-policy.json file is the runtime source of truth, env only SEEDS):
  RELAY_MODE=invite-only           Registration mode: invite-only (DEFAULT) |
                                   open (LAN/firewalled) | commons (public).
  FLOOR_LIVENESS_TIMEOUT_HOURS=24  Reaper window for FLOOR-tier records (short).
                                   VOUCHED/HOUSEHOLD use LIVENESS_TIMEOUT_HOURS.
  WOT_PROMOTE_THRESHOLD=1.0        Tier-weighted WoT vouch score that, with a
                                   verified IdentityOutbox, auto-promotes FLOOR→VOUCHED
                                   (HOUSEHOLD/owner voucher=1.0, VOUCHED=0.6, FLOOR=0).
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import time
from datetime import datetime, timedelta
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from threading import Lock

# --- Config ---
CAPACITY = int(os.environ.get("RELAY_CAPACITY", "500"))
REGION = os.environ.get("RELAY_REGION", "unknown")
PUBLIC = os.environ.get("RELAY_PUBLIC", "true").lower() == "true"
API_KEY = os.environ.get("RELAY_API_KEY", "")
NATS_CONF = os.environ.get("NATS_CONF", "/etc/nats/relay.conf")
NATS_SIGNAL = os.environ.get("NATS_SIGNAL_CMD", "nats-server --signal reload")
DATA_DIR = Path(os.environ.get("DATA_DIR", "/var/lib/wyrd-relay"))
PORT = int(os.environ.get("REGISTRATION_PORT", "9280"))
BIND = os.environ.get("REGISTRATION_BIND", "0.0.0.0")  # native mode binds loopback; caddy fronts it
# The relay's PUBLIC NATS zone-leg port — what a joining NODE dials for its raw-NATS
# leg (phones use wss:4443 instead). Defaults to 4222, but a co-hosted relay runs
# with WYRD_RELAY_PORT_OFFSET so the port shifts (e.g. 4322); the registration
# response must advertise the REAL port or the joining node dials 4222 and silently
# connects to its own NATS instead of the relay (found 2026-07-16).
NATS_PORT = int(os.environ.get("RELAY_NATS_PORT", "4222"))
# Liveness reaper window (Part 3). A live zone holds a persistent NATS
# connection so its last_seen stays fresh; only a zone absent for the FULL
# window is reaped, and a reconnect within the window restarts the clock.
# Default 168h (7 days). Env-overridable.
LIVENESS_TIMEOUT_HOURS = int(os.environ.get("LIVENESS_TIMEOUT_HOURS", "168"))
# How often the reaper sweeps connz + prunes stale records (seconds).
LIVENESS_REAP_INTERVAL_SECONDS = int(os.environ.get("LIVENESS_REAP_INTERVAL_SECONDS", "600"))
# Localhost NATS monitoring endpoint the reaper reads liveness from.
NATS_MONITOR_URL = os.environ.get("NATS_MONITOR_URL", "http://127.0.0.1:8222")
RATE_LIMIT_SECONDS = int(os.environ.get("RATE_LIMIT_SECONDS", "3600"))  # 1 per IP per hour; set to 0 to disable (dev/LAN)

# ---: registration modes + trust tiers ---
#
# A relay runs in exactly ONE mode (§4), chosen at deploy (relay.sh --mode) or
# flipped at runtime via the signed `set-mode` admin op. The mode is the source
# of truth for the register-nkey gate; it is persisted in relay-policy.json so a
# restart preserves it. RELAY_MODE only SEEDS the initial value on first read —
# once set-mode has written the file, the file wins (a redeploy that omits
# --mode must not silently flip a runtime-changed mode back).
#
#   invite-only (DEFAULT) — register-nkey requires a valid invite; HOUSEHOLD tier.
#   open                  — invite-less register-nkey ok (LAN/firewalled); HOUSEHOLD-equiv.
#   commons               — invite-less ok but entrant tier = FLOOR; DID mandatory;
#                           hard per-IP rate-limit; verified IdentityOutbox required
#                           to be eligible for promotion above FLOOR.
RELAY_MODE_DEFAULT = (os.environ.get("RELAY_MODE", "") or "invite-only").strip().lower()
_VALID_MODES = ("invite-only", "open", "commons")

# Trust tiers (§3). FLOOR < VOUCHED < HOUSEHOLD. Tier gates the reaper window
# (and is the hook for per-tier quota, recorded-only in P5 — see §5 / TODO-P6).
TIER_FLOOR = "FLOOR"
TIER_VOUCHED = "VOUCHED"
TIER_HOUSEHOLD = "HOUSEHOLD"
_TIER_RANK = {TIER_FLOOR: 1, TIER_VOUCHED: 2, TIER_HOUSEHOLD: 3}

# Reaper window per tier (§3, env-tunable). FLOOR newcomers are reaped fast
# (default 24h) so an unattended commons doesn't accrete dead FLOOR records;
# VOUCHED/HOUSEHOLD keep the standing LIVENESS_TIMEOUT_HOURS window (168h).
# CONSERVATIVE defaults; operator can tune via env.
FLOOR_LIVENESS_TIMEOUT_HOURS = int(os.environ.get("FLOOR_LIVENESS_TIMEOUT_HOURS", "24"))

# Promotion threshold (§3, §13 open-question — start CONSERVATIVE). A FLOOR
# registration with a verified IdentityOutbox AND a WoT promotion score at or
# above WOT_PROMOTE_THRESHOLD auto-promotes to VOUCHED — see WOT_* below.
# COMMONS_VOUCH_THRESHOLD is the SUPERSEDED flat-count knob, kept only so an old
# env/setup that still sets it doesn't error; nothing reads it for promotion now.
COMMONS_VOUCH_THRESHOLD = int(os.environ.get("COMMONS_VOUCH_THRESHOLD", "2"))

# --- — Web-of-Trust promotion scoring ---
#
# The relay's promotion decision consumes the WoT (#150-151) instead of a flat
# vouch count: each voucher is WEIGHTED by the trust tier it has itself earned in
# the web. The tier IS the WoT's output (a node reaches HOUSEHOLD/VOUCHED only
# by operator grant or community vouches), so weighting by tier consumes that
# graph directly — and, unlike a raw transitive-edge BFS, it correctly confers
# vouching power on INVITED household members (whose tier is granted at the door
# without writing a vouch edge). A HOUSEHOLD voucher counts more than a VOUCHED
# one; a FLOOR node cannot confer trust at all.
#
# The transitive hop-decay graph (RelayTrustGraph.java, HOP_TRUST {1,1,.6,.3}) is
# the ZONE-side consumer — it scores which relay a zone should CONNECT to over
# bond chains. The relay-side promotion below is its dual: how much standing a
# voucher lends, by tier.
WOT_TIER_WEIGHT = {
    TIER_HOUSEHOLD: 1.0,   # operator-trusted (invited / promoted to full standing)
    TIER_VOUCHED:   0.6,   # community-promoted, partial standing
    TIER_FLOOR:     0.0,   # newcomers cannot confer trust (the §3 edge rule)
}
# Promote at or above this summed weighted trust. Default 1.0 ≈ "one HOUSEHOLD
# voucher (or the owner), or several VOUCHED ones". Env-tunable.
WOT_PROMOTE_THRESHOLD = float(os.environ.get("WOT_PROMOTE_THRESHOLD", "1.0"))

# --- — per-tier quota ENFORCEMENT ---
#
# Two enforcement levers the relay sidecar can apply WITHOUT restructuring the
# NATS account model (which is a single flat `authorization` block precisely so
# federation.>/_INBOX.> cross-talk works between every household):
#
#   1. max_registrations — a HARD cap on how many ACTIVE records may hold each
#      tier. Enforced at the register-nkey gate (the Sybil/flood defense that
#      actually matters for a public commons): when a tier is full, a NEW entrant
#      at that tier is refused. A re-register of an existing record is never
#      refused by the cap. -1 = unlimited.
#   2. max_connections — the per-DID concurrent-connection ceiling. The reaper
#      already polls /connz (nkey-per-connection); it stamps `over_connection_limit`
#      on any record whose live connection count exceeds its tier budget, and
#      surfaces it in list/audit for the operator. This is DETECTION-grade: the
#      sidecar cannot sever one NATS connection without dropping the record from
#      auth, so a hard cut stays an operator decision (relay.sh remove).
#
# NOTE (honest scope): NATS-native bandwidth throttling (max_data / max_subs /
# connection-rate) is ACCOUNT-scoped, not user-scoped. Applying it per tier means
# splitting into per-tier accounts with exports/imports for the shared federation
# subjects — a restructure that needs a live two-zone federation soak before it
# can ship. That piece is tracked separately; the registration cap below is the
# real flood defense and is enforced now.
#
# Conservative ship defaults; every value is policy-overridable via set-policy
# and env-seedable. A public commons tightens FLOOR; a private/LAN relay can
# leave them effectively unlimited (the defaults already are, except FLOOR).
DEFAULT_TIER_QUOTAS = {
    "floor":     {"max_registrations": int(os.environ.get("FLOOR_MAX_REGISTRATIONS", "500")),
                  "max_connections":   int(os.environ.get("FLOOR_MAX_CONNECTIONS", "2"))},
    "vouched":   {"max_registrations": int(os.environ.get("VOUCHED_MAX_REGISTRATIONS", "-1")),
                  "max_connections":   int(os.environ.get("VOUCHED_MAX_CONNECTIONS", "5"))},
    "household": {"max_registrations": int(os.environ.get("HOUSEHOLD_MAX_REGISTRATIONS", "-1")),
                  "max_connections":   int(os.environ.get("HOUSEHOLD_MAX_CONNECTIONS", "20"))},
}


def tier_quota(tier: str, policy: dict | None = None) -> dict:
    """The effective quota for a tier: DEFAULT_TIER_QUOTAS overlaid by any
    per-tier policy recorded via set-policy (relay-policy.json `tiers`). Policy
    keys win field-by-field; an absent field falls back to the default."""
    key = (tier or TIER_HOUSEHOLD).lower()
    base = dict(DEFAULT_TIER_QUOTAS.get(key, DEFAULT_TIER_QUOTAS["household"]))
    p = policy if policy is not None else load_policy()
    override = (p.get("tiers", {}) or {}).get(key, {}) or {}
    for k, v in override.items():
        if isinstance(v, (int, float)):
            base[k] = int(v)
    return base

# Invite flow.
CERT_DIR = Path(os.environ.get("CERT_DIR", "/certs"))
INVITE_DEFAULT_TTL = int(os.environ.get("INVITE_DEFAULT_TTL", "3600"))      # 1h default
INVITE_MAX_TTL = int(os.environ.get("INVITE_MAX_TTL", "86400"))             # 24h cap
# Commons self-serve (codeless /join): the payload only needs to survive the
# immediate register round-trip, so keep it short — it consumes no code and
# can always be re-fetched (rate-limited).
SELF_SERVE_INVITE_TTL_SECONDS = int(os.environ.get("SELF_SERVE_INVITE_TTL", "300"))
RELAY_PUBLIC_HOST = os.environ.get("RELAY_PUBLIC_HOST", "")                  # used in invite URL
# A relay can be reachable at several addresses (a multi-NIC box on more than
# one LAN, or LAN + public). When the operator names no single host, relay.sh
# passes EVERY address the relay sees as a comma list; invites then advertise
# all of them so a client (zone or phone) just tries each and uses the one that
# connects — the cert pins by fingerprint, so any reachable address validates.
# RELAY_PUBLIC_HOST stays the PRIMARY (first) one, used for single-host URLs.
def _public_hosts() -> list:
    raw = os.environ.get("RELAY_PUBLIC_HOSTS", "") or RELAY_PUBLIC_HOST
    hosts = [h.strip() for h in raw.split(",") if h.strip()]
    return hosts or ["localhost"]
# ONE public port (the Caddy household-CA front
# RELAY_PORT, default 4443). Invite material always carries host:port
# explicitly. The dedicated :8443 registration port is gone; registration
# rides the same listener as NATS WS.
RELAY_PUBLIC_PORT = (os.environ.get("RELAY_PUBLIC_PORT", "")
                     or os.environ.get("RELAY_PORT", "4443"))

# --- State ---
DATA_DIR.mkdir(parents=True, exist_ok=True)
REGISTRATIONS_FILE = DATA_DIR / "registrations.json"
PEERS_FILE = DATA_DIR / "peers.json"
INVITE_KEY_FILE = DATA_DIR / "invite-key"      # 32 bytes, generated on first run
SEEN_NONCES_FILE = DATA_DIR / "seen-nonces.json"
# b/§5 — owner bootstrap + local relay-admin grant store.
OWNER_FILE = DATA_DIR / "owner.json"                       # {owner_did, relay_did, set_at, via}
RELAY_ADMIN_GRANTS_FILE = DATA_DIR / "relay-admin-grants.json"  # {did -> grant}
OWNER_CLAIM_TOKENS_FILE = DATA_DIR / "owner-claim-tokens.json"  # {token -> {exp, fp}}
# relay mode + per-tier policy, and the
# relay-local Web-of-Trust vouch store.
RELAY_POLICY_FILE = DATA_DIR / "relay-policy.json"   # {mode, floor:{}, vouched:{}, household:{}}
RELAY_VOUCHES_FILE = DATA_DIR / "relay-vouches.json"  # {subject_did: [voucher_did, ...]}
# abuse reports queue. A list of report records
# see file_report / report_queue / resolve_report below.
RELAY_REPORTS_FILE = DATA_DIR / "relay-reports.json"  # [{id, subject_did, reporter_did, ...}]
# §4b — optional deploy-time owner (relay.sh --owner / RELAY_OWNER_DID env).
RELAY_OWNER_DID = os.environ.get("RELAY_OWNER_DID", "")

# ---: SSH-over-relay (raw-TCP reverse tunnel) ---
# All dormant unless the relay was deployed with --ssh-tunnel. The relay runs a
# dedicated, forwarding-ONLY sshd (deploy/relay/tunnel-sshd_config); a zone opts
# in with a DEDICATED ed25519 key (not its NKey/DID), and we regenerate that
# sshd's authorized_keys from registrations.json (mirroring update_nats_config).
# One `restrict,permitlisten="0.0.0.0:<port>"` line per opted-in zone gives
# per-zone public-port isolation welded to the key.
SSH_TUNNEL_ENABLED = os.environ.get("WYRD_SSH_TUNNEL_ENABLED", "").lower() in ("1", "true", "yes")
SSH_TUNNEL_PORT_BASE = int(os.environ.get("WYRD_SSH_TUNNEL_PORT_BASE", "7100"))
SSH_TUNNEL_PORT_COUNT = int(os.environ.get("WYRD_SSH_TUNNEL_PORT_COUNT", "50"))
SSH_TUNNEL_CTRL_PORT = int(os.environ.get("WYRD_SSH_TUNNEL_PORT", "2222"))
SSH_AUTHORIZED_KEYS = Path(os.environ.get(
    "WYRD_SSH_AUTHKEYS", str(DATA_DIR / "ssh" / "authorized_keys")))
# Per-relay opt-in policy: off (no door) | grant (owner/relay-admin grant only) |
# open (any registered zone may self-serve a port with its own NKey signature).
SSH_TUNNEL_MODE_DEFAULT = os.environ.get("WYRD_SSH_TUNNEL_MODE", "off").lower()
# Topology: port (per-zone PUBLIC port — household default) | jump (one ProxyJump
# port for all zones — commons; zones bind LOOPBACK + a forward-only jump key).
SSH_TUNNEL_TOPOLOGY_DEFAULT = os.environ.get("WYRD_SSH_TUNNEL_TOPOLOGY", "port").lower()
# Shared forward-only ProxyJump principal pubkey (jump topology only); the relay
# generates the keypair at deploy and ships the private half in the enable
# response. Absent → the jump line is simply omitted (per-zone lines still emit).
SSH_JUMP_KEY = DATA_DIR / "ssh" / "jump_ed25519_key"
SSH_JUMP_KEY_PUB = DATA_DIR / "ssh" / "jump_ed25519_key.pub"
SSH_HOST_KEY_PUB = DATA_DIR / "ssh" / "tunnel_host_ed25519_key.pub"
_VALID_SSH_MODES = ("off", "grant", "open")
_VALID_SSH_TOPOLOGIES = ("port", "jump")

lock = Lock()
rate_limits: dict[str, float] = {}  # IP -> last registration timestamp


def load_registrations() -> dict:
    if REGISTRATIONS_FILE.exists():
        return json.loads(REGISTRATIONS_FILE.read_text())
    return {}


def save_registrations(regs: dict):
    # Atomic write — the ssh-tunnel feature now depends on regs.json integrity
    # across a possible crash (a torn write could lose a port assignment).
    tmp = REGISTRATIONS_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(regs, indent=2, default=str))
    os.replace(tmp, REGISTRATIONS_FILE)


def load_peers() -> list:
    if PEERS_FILE.exists():
        return json.loads(PEERS_FILE.read_text())
    return []


def save_peers(peers: list):
    PEERS_FILE.write_text(json.dumps(peers, indent=2, default=str))


def generate_token() -> str:
    """Generate a 256-bit random token (base64url, no padding)."""
    return secrets.token_urlsafe(32)


# --- Invite-flow helpers (F2.1 / F3) ---

def _load_or_init_invite_key() -> bytes:
    if INVITE_KEY_FILE.exists():
        return INVITE_KEY_FILE.read_bytes()
    key = secrets.token_bytes(32)
    INVITE_KEY_FILE.write_bytes(key)
    INVITE_KEY_FILE.chmod(0o600)
    return key


def _leaf_fingerprint() -> str:
    """SHA-256 fingerprint of the relay's leaf cert in DER, hex with colons."""
    leaf = CERT_DIR / "leaf.crt"
    if not leaf.is_file():
        raise FileNotFoundError(f"Leaf cert not found at {leaf}")
    pem = leaf.read_text()
    # Strip the PEM headers and base64-decode to DER.
    body = "".join(line for line in pem.splitlines()
                   if line and not line.startswith("-----"))
    der = base64.b64decode(body)
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[i:i+2] for i in range(0, len(digest), 2))


def _ca_pem_and_fingerprint() -> tuple[str, str]:
    """Read the household CA cert PEM and return (pem, sha256-fingerprint).

    Phones use the CA as their root trust anchor (not the leaf), so cert
    rotation doesn't force re-pinning. Returned by `mint_invite()` to embed
    directly in the invite payload — this is what replaces the cleartext
/ca.crt fetch over :80.
    """
    ca = CERT_DIR / "ca.crt"
    if not ca.is_file():
        raise FileNotFoundError(f"CA cert not found at {ca}")
    pem = ca.read_text()
    body = "".join(line for line in pem.splitlines()
                   if line and not line.startswith("-----"))
    der = base64.b64decode(body)
    digest = hashlib.sha256(der).hexdigest().upper()
    fp = ":".join(digest[i:i+2] for i in range(0, len(digest), 2))
    return pem, fp


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)


# --- DID identity ( / R2.1) ---
# A relay NKey and the node's `did:key:` are two projections of the SAME
# `NodeIdentity` Ed25519 key. So `nkey ⇄ did:key:` is a
# deterministic, relay-side computation — surfacing the identity already
# under the pubkey, not adding a new (spoofable) one. The DID a node
# computes from its own NodeIdentity (Java `DidKey.fromRawPublicKey`) MUST
# equal this; the algorithm below is a byte-for-byte port of that util.

# Bitcoin base58 alphabet — identical to Java DidKey.BASE58_ALPHABET.
_BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
# Ed25519 multicodec varint prefix (0xed as unsigned varint = [0xed, 0x01]),
# identical to Java DidKey.MULTICODEC_ED25519.
_MULTICODEC_ED25519 = bytes([0xED, 0x01])


def _base58btc_encode(data: bytes) -> str:
    """Base58btc encode (Bitcoin alphabet), preserving leading-zero bytes as
    '1'. Mirrors Java DidKey.base58Encode byte-for-byte."""
    if not data:
        return ""
    leading_zeros = 0
    for b in data:
        if b == 0:
            leading_zeros += 1
        else:
            break
    num = int.from_bytes(data, "big")
    out = []
    while num > 0:
        num, rem = divmod(num, 58)
        out.append(_BASE58_ALPHABET[rem])
    out.reverse()
    return (_BASE58_ALPHABET[0] * leading_zeros) + "".join(out)


def _pubkey_to_raw_ed25519(pubkey: str) -> bytes:
    """Decode a NATS user NKey (56-char base32 'U…') to its raw 32-byte
    Ed25519 public key. Same decode path as `_verify_nkey_sig` /
    `re_register_existing_nkey`: base32-decode → strip the 1-byte prefix +
    2-byte CRC16 trailer → bytes[1:33]. Raises ValueError if malformed."""
    raw = base64.b32decode(pubkey.encode("ascii"))
    if len(raw) != 35:
        raise ValueError("pubkey is not a valid NATS user NKey (length)")
    # raw[0] = prefix byte (PREFIX_BYTE_USER), raw[1:33] = key, raw[33:35] = CRC16
    return bytes(raw[1:33])


def nkey_to_did(pubkey: str) -> str | None:
    """derive the canonical `did:key:z6Mk…`
    from a registering NATS NKey pubkey, relay-side.

    Byte-for-byte equivalent to Java `DidKey.fromRawPublicKey`:
        did:key:z + base58btc( [0xed,0x01] + raw_32_byte_ed25519_pubkey )

    Returns None if the pubkey can't be decoded (callers stamp only when
    non-None, so a malformed key never corrupts a record)."""
    try:
        raw = _pubkey_to_raw_ed25519(pubkey)
    except Exception:
        return None
    if len(raw) != 32:
        return None
    return "did:key:z" + _base58btc_encode(_MULTICODEC_ED25519 + raw)


def _base58btc_decode(s: str) -> bytes:
    """Base58btc decode (Bitcoin alphabet), restoring leading '1' chars as
    zero bytes. Inverse of `_base58btc_encode`; mirrors the Java DidKey decode
    path. Raises ValueError on an out-of-alphabet character."""
    if s == "":
        return b""
    leading_ones = 0
    for ch in s:
        if ch == _BASE58_ALPHABET[0]:
            leading_ones += 1
        else:
            break
    num = 0
    for ch in s:
        idx = _BASE58_ALPHABET.find(ch)
        if idx < 0:
            raise ValueError(f"invalid base58 character: {ch!r}")
        num = num * 58 + idx
    body = num.to_bytes((num.bit_length() + 7) // 8, "big") if num else b""
    return (b"\x00" * leading_ones) + body


def did_to_ed25519_pubkey(did: str) -> bytes:
    """the INVERSE of `nkey_to_did`: recover the
    raw 32-byte Ed25519 public key from a `did:key:z6Mk…` string so a signature
    made by that DID's key can be verified.

        did:key:z + base58btc( [0xed,0x01] + raw_32_byte_ed25519_pubkey )

    Strip the `did:key:z` prefix, base58btc-decode, strip the 2-byte
    `0xed01` multicodec prefix → 32-byte pubkey. Raises ValueError if the DID
    is malformed (wrong prefix, wrong multicodec, wrong length)."""
    if not did or not did.startswith("did:key:z"):
        raise ValueError("not a did:key: identifier")
    raw = _base58btc_decode(did[len("did:key:z"):])
    if len(raw) != len(_MULTICODEC_ED25519) + 32:
        raise ValueError(f"unexpected did:key payload length {len(raw)}")
    if raw[:len(_MULTICODEC_ED25519)] != _MULTICODEC_ED25519:
        raise ValueError("did:key is not an Ed25519 key (bad multicodec prefix)")
    return bytes(raw[len(_MULTICODEC_ED25519):])


def _verify_did_sig(did: str, message: bytes, signature_b64: str) -> str | None:
    """Verify an Ed25519 signature made by the key behind `did`. Returns None
    on success or a human-readable error string. The DID's pubkey is recovered
    via `did_to_ed25519_pubkey` and the signature checked with
    `nacl.signing.VerifyKey` (same nacl path as `_verify_nkey_sig`)."""
    try:
        import nacl.signing as _signing  # type: ignore
        import nacl.exceptions as _nacl_exc  # type: ignore
    except ImportError:
        return "server missing 'pynacl' Python package"
    try:
        pubkey = did_to_ed25519_pubkey(did)
    except ValueError as e:
        return f"malformed did:key: {e}"
    try:
        signature = base64.b64decode(signature_b64)
    except Exception as e:
        return f"signature is not valid base64: {e}"
    try:
        _signing.VerifyKey(pubkey).verify(message, signature)
    except _nacl_exc.BadSignatureError:
        return "signature verification failed (bad sig)"
    except Exception as e:
        return f"signature verification failed: {e}"
    return None


def _canonical_args(args) -> str:
    """Stable canonicalization of an admin op's `args` for the signed-admin
    challenge: compact JSON with sorted keys (separators stripped of spaces).
    A `None`/missing args canonicalizes to `null`. Both signer and verifier
    MUST produce the identical bytes; this is the documented form."""
    return json.dumps(args, separators=(",", ":"), sort_keys=True,
                      ensure_ascii=True, default=str)


# --- / R2.2: IdentityOutboxRecord verification ---
#
# A registrant MAY present a signed IdentityOutboxRecord (Java
# core/.../identity/IdentityOutboxRecord). We verify the Ed25519 signature in
# Python; for commons mode (§4) a verified record is the price of a non-FLOOR
# tier.
#
# CROSS-LANGUAGE PARITY (the load-bearing detail): the Java signer covers the
# CANONICAL bytes produced by IdentityOutboxRecord.signingData — a compact JSON
# object built from a LinkedHashMap with a FIXED key order (insertion order, NOT
# sorted) and serialized by a plain Jackson ObjectMapper:
#
#   {"did":…,"displayName":…,"primaryZone":…,"writeZones":[…],"readZones":[…],
#    "channels":[{"type":…,"address":…},…],"updatedAt":<long>}
#
# Jackson's default ObjectMapper emits compact JSON (no spaces), escapes ONLY
# the JSON-required characters (it does NOT \uXXXX-escape non-ASCII, and does
# NOT escape '/', '<', '>'). To reproduce those exact bytes in Python:
#   json.dumps(..., separators=(",",":"), ensure_ascii=False)
# with the keys inserted in the Java order and channels as {type, address} in
# that order. ensure_ascii=False is REQUIRED so non-ASCII rides as raw UTF-8
# (matching Jackson) rather than \uXXXX. The `sig` field is EXCLUDED.
#
# Verified against a REAL Java-minted vector (see test_registration.py
# TestIdentityOutbox::_JAVA_VECTOR — produced by signing with the live Java
# IdentityOutboxRecord.sign and asserting Python verifies the signature).

# Canonical signing-field order — MUST mirror Java signingData() insertion order.
_IDENTITY_OUTBOX_FIELDS = (
    "did", "displayName", "primaryZone", "writeZones", "readZones",
    "channels", "updatedAt",
)


def identity_outbox_signing_bytes(record: dict) -> bytes:
    """Reproduce Java IdentityOutboxRecord.signingData byte-for-byte for the
    given parsed record dict. Coerces null list fields to [] (Java does the
    same: `writeZones == null ? List.of() : writeZones`) and normalizes each
    channel to the {type, address} order Jackson emits for the ChannelRef
    record. Raises ValueError if a required field is structurally wrong."""
    m = {}
    m["did"] = record.get("did")
    m["displayName"] = record.get("displayName")
    m["primaryZone"] = record.get("primaryZone")
    m["writeZones"] = record.get("writeZones") or []
    m["readZones"] = record.get("readZones") or []
    channels = record.get("channels") or []
    norm_channels = []
    for ch in channels:
        if not isinstance(ch, dict):
            raise ValueError("channel entry is not an object")
        # Java ChannelRef record → Jackson emits {type, address} in that order.
        norm_channels.append({"type": ch.get("type"), "address": ch.get("address")})
    m["channels"] = norm_channels
    # updatedAt is a Java long — Jackson emits it as a bare integer. Coerce to
    # int so a float/str from a sloppy client still canonicalizes identically.
    try:
        m["updatedAt"] = int(record.get("updatedAt"))
    except (TypeError, ValueError):
        raise ValueError("updatedAt must be an integer (unix-ms)")
    return json.dumps(m, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def verify_identity_outbox(record: dict) -> str | None:
    """verify a presented IdentityOutboxRecord.
    Recovers the Ed25519 pubkey from the record's `did` (the same did_to_…
    path P3 uses) and checks `sig` over the canonical signing bytes.

    Returns None on success, or a human-readable error string. The record's
    self-asserted `did` IS the verification key — a record signed by another
    key fails, so the relay never trusts an unverified channel list."""
    if not isinstance(record, dict):
        return "identity_outbox must be a JSON object"
    did = record.get("did")
    if not did or not str(did).startswith("did:key:z"):
        return "identity_outbox.did (did:key:…) required"
    sig = record.get("sig")
    if not sig or not str(sig).strip():
        return "identity_outbox.sig (base64 Ed25519 signature) required"
    try:
        message = identity_outbox_signing_bytes(record)
    except ValueError as e:
        return f"malformed identity_outbox: {e}"
    # _verify_did_sig expects a base64 sig (Java emits standard Base64) and
    # recovers the pubkey from the DID — exactly our cross-language seam.
    return _verify_did_sig(did, message, sig)


def backfill_dids() -> int:
    """One-time, idempotent backfill: stamp the
    canonical `did` on any nkey record missing it. Cheap — runs once at boot,
    re-derives nothing already present, and rewrites the ledger only if it
    actually changed (no NATS reload — DID is metadata, not auth). Returns the
    number of records filled."""
    with lock:
        regs = load_registrations()
        filled = 0
        for pubkey, entry in regs.items():
            if entry.get("kind") != "nkey" or entry.get("did"):
                continue
            did = nkey_to_did(entry.get("pubkey") or pubkey)
            if did:
                entry["did"] = did
                filled += 1
        if filled:
            save_registrations(regs)
    return filled


# ---: mode + tier + policy + vouch store ---


def load_policy() -> dict:
    """The relay-policy.json document: {mode, floor:{}, vouched:{}, household:{},
    updated_at}. Returns {} if absent (callers default mode/quotas)."""
    if RELAY_POLICY_FILE.exists():
        try:
            return json.loads(RELAY_POLICY_FILE.read_text())
        except json.JSONDecodeError:
            return {}
    return {}


def save_policy(policy: dict) -> None:
    tmp = RELAY_POLICY_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(policy, indent=2, default=str))
    tmp.replace(RELAY_POLICY_FILE)


def relay_mode() -> str:
    """Current registration mode (§4). The persisted relay-policy.json wins; the
    RELAY_MODE env only SEEDS the file on first read so a redeploy that omits
    --mode never silently reverts a runtime set-mode. Falls back to the
    conservative default (invite-only) if an unknown value is ever stored."""
    p = load_policy()
    mode = (p.get("mode") or "").strip().lower()
    if mode in _VALID_MODES:
        return mode
    seed = RELAY_MODE_DEFAULT if RELAY_MODE_DEFAULT in _VALID_MODES else "invite-only"
    # Persist the seed so the first read is sticky (the env is only a seed).
    _set_mode(seed)
    return seed


def _set_mode(mode: str) -> str:
    """Persist the registration mode into relay-policy.json. Returns the stored
    value, or raises ValueError on an invalid mode."""
    mode = (mode or "").strip().lower()
    if mode not in _VALID_MODES:
        raise ValueError(f"invalid mode '{mode}' "
                         f"(one of {', '.join(_VALID_MODES)})")
    with lock:
        p = load_policy()
        p["mode"] = mode
        p["updated_at"] = datetime.utcnow().isoformat()
        save_policy(p)
    return mode


def load_vouches() -> dict:
    if RELAY_VOUCHES_FILE.exists():
        try:
            return json.loads(RELAY_VOUCHES_FILE.read_text())
        except json.JSONDecodeError:
            return {}
    return {}


def save_vouches(vouches: dict) -> None:
    tmp = RELAY_VOUCHES_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(vouches, indent=2, default=str))
    tmp.replace(RELAY_VOUCHES_FILE)


def _tier_rank(tier: str) -> int:
    return _TIER_RANK.get((tier or "").upper(), 0)


def did_tier(did: str) -> str:
    """The trust tier a DID currently holds, read from its registration record.
    Defaults to FLOOR for an unknown/untiered DID (the safe floor for WoT
    reasoning — a stranger is never assumed VOUCHED)."""
    if not did:
        return TIER_FLOOR
    regs = load_registrations()
    for entry in regs.values():
        if entry.get("did") == did:
            return (entry.get("tier") or TIER_FLOOR).upper()
    return TIER_FLOOR


def _record_for_did(regs: dict, did: str):
    """Return (pubkey, entry) for the registration whose canonical DID matches,
    or (None, None). The DID is derived relay-side so it's unique per key."""
    for pubkey, entry in regs.items():
        if entry.get("did") == did:
            return pubkey, entry
    return None, None


def reaper_window_hours_for(entry: dict) -> int:
    """the liveness reaper window for a record, gated
    by its trust tier: FLOOR gets the short window, VOUCHED/HOUSEHOLD the long
    one. A record with no tier (pre-P5 backfilled to HOUSEHOLD) gets the long
    window."""
    tier = (entry.get("tier") or TIER_HOUSEHOLD).upper()
    if tier == TIER_FLOOR:
        return FLOOR_LIVENESS_TIMEOUT_HOURS
    return LIVENESS_TIMEOUT_HOURS


def backfill_tiers() -> int:
    """One-time, idempotent (§3): stamp tier=HOUSEHOLD on any nkey record missing
    a tier. Every pre-P5 record was invite-bound (operator-vouched at the door),
    so HOUSEHOLD is the correct backfill. Returns the number filled."""
    with lock:
        regs = load_registrations()
        filled = 0
        for entry in regs.values():
            if entry.get("kind") != "nkey":
                continue
            if not entry.get("tier"):
                entry["tier"] = TIER_HOUSEHOLD
                filled += 1
        if filled:
            save_registrations(regs)
    return filled


def mint_invite(ttl_seconds: int, self_serve: bool = False) -> dict:
    """Generate a single-use invite token. v2 carries the household CA in
    the payload itself — joining nodes install the CA from the invite and
never fetch /ca.crt.

    self_serve=True marks a commons codeless-/join mint. The tag matters
    because legacy /register has no mode/tier logic: redeeming a self-serve
    mint there would land a pw record OUTSIDE the commons FLOOR gate
    (observed live 2026-07-30 — a stranger's bash join got HOUSEHOLD
    standing). /register refuses ss-tagged invites; /register-nkey is the
    self-serve enrollment path and applies the mode gate.
    """
    ttl = max(60, min(ttl_seconds, INVITE_MAX_TTL))
    fingerprint = _leaf_fingerprint()
    ca_pem, ca_fp = _ca_pem_and_fingerprint()
    payload = {
        "v": 2,
        "fp": fingerprint,
        "ca_fp": ca_fp,
        "ca": _b64url(ca_pem.encode()),
        "exp": int(time.time()) + ttl,
        "n": _b64url(secrets.token_bytes(16)),
    }
    if self_serve:
        payload["ss"] = 1
    payload_bytes = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    sig = hmac.new(_load_or_init_invite_key(), payload_bytes, hashlib.sha256).digest()
    token = _b64url(payload_bytes) + "." + _b64url(sig)

    hosts = _public_hosts()
    host = hosts[0]
    invite_url = f"wyrdrelay://{host}:{RELAY_PUBLIC_PORT}/{token}"

    return {
        "invite_url": invite_url,
        "token": token,
        "fingerprint": fingerprint,
        "ca_fingerprint": ca_fp,
        "expires_at": payload["exp"],
        "host": host,
        # All addresses this relay answers on (dial hints, NOT signed — the
        # token's fp/ca_fp is the trust decision). A joining zone may try each.
        "hosts": hosts,
        "port": RELAY_PUBLIC_PORT,
    }


# --- Join codes ---
# A join code is a SHORT, typeable alias for a full invite: `wyrd relay
# join <host> <code>` POSTs it to /join (which rides the single public
# Caddy listener) and gets back the same payload the wyrdrelay:// URL
# carries. Codes are single-use, expire with their invite, and use an
# ambiguity-free alphabet (no 0/O/1/l/I).

JOIN_CODES_FILE = DATA_DIR / "join-codes.json"
_JOIN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
_join_attempts: dict[str, list] = {}   # IP -> [timestamps] (brute-force guard)


def _mint_join_code(invite: dict) -> str:
    code = "".join(secrets.choice(_JOIN_ALPHABET) for _ in range(8))
    with lock:
        codes = {}
        if JOIN_CODES_FILE.exists():
            try:
                codes = json.loads(JOIN_CODES_FILE.read_text())
            except json.JSONDecodeError:
                codes = {}
        now = int(time.time())
        codes = {k: v for k, v in codes.items() if v.get("exp", 0) > now}
        codes[code] = {"invite": invite, "exp": invite.get("expires_at", now + 3600)}
        JOIN_CODES_FILE.write_text(json.dumps(codes))
    return code


def _consume_join_code(code: str) -> dict | None:
    """Pop a join code (single use). Returns the invite dict or None."""
    with lock:
        if not JOIN_CODES_FILE.exists():
            return None
        try:
            codes = json.loads(JOIN_CODES_FILE.read_text())
        except json.JSONDecodeError:
            return None
        entry = codes.pop(code, None)
        JOIN_CODES_FILE.write_text(json.dumps(codes))
    if entry is None or entry.get("exp", 0) < int(time.time()):
        return None
    return entry.get("invite")


def _join_rate_ok(ip: str, limit: int = 10, window: int = 60,
                  bucket: dict | None = None) -> bool:
    """Allow at most `limit` attempts per IP per `window` seconds.

    Default bucket is the /join brute-force guard; other public endpoints
    pass their own dict so a noisy client on one endpoint can't exhaust
    another endpoint's budget.
    """
    if bucket is None:
        bucket = _join_attempts
    now = time.time()
    attempts = [t for t in bucket.get(ip, []) if now - t < window]
    if len(attempts) >= limit:
        bucket[ip] = attempts
        return False
    attempts.append(now)
    bucket[ip] = attempts
    return True


# --- Phone invites ---
# `wyrd phone invite` runs on the ZONE: it signs a challenge with the
# household NKey and POSTs to /phone-invite (public, rides the Caddy
# listener). Only a registered, active household can mint — that's what
# moves the relay_phone credential out of "hardcoded in the APK" and into
# invite material a steward hands to their own device. The payload is
# self-contained connection material (ws URL + phone NATS credential +
# CA pin when self-signed), encoded as a wyrdphone:// URL the app can
# scan from a QR code.

_phone_invite_attempts: dict[str, list] = {}   # IP -> [timestamps]
# separate brute-force bucket for /claim-owner +
# /admin so a noisy admin caller can't exhaust the join/phone budgets.
_admin_attempts: dict[str, list] = {}          # IP -> [timestamps]


RELAY_SECRETS_FILE = DATA_DIR / "relay-secrets.json"   # 0600, generated on first run


def _relay_secret(name: str) -> str:
    """A per-INSTALL infrastructure credential, generated on first use and
    persisted alongside the other relay state.

    Pre-OSS these had literal fallbacks in the source ("…or 'M3bWgIOV…'"), which
    was survivable while the only relay was operator's. Published, a constant
    default means every wyrdsekai relay on the internet ships with the SAME
    known password for its infrastructure accounts — `relay_join` alone would
    let any stranger drive `relay.register` on anyone's relay. Generated +
    persisted keeps the property the old constants were there for (a conf
    regen must not invalidate the running sidecar's credential) without
    publishing the secret. Env vars still win, so compose/relay.sh deployments
    that inject their own are unchanged. (OSS hardening, 2026-07-25.)
    """
    try:
        if RELAY_SECRETS_FILE.exists():
            secrets_map = json.loads(RELAY_SECRETS_FILE.read_text())
        else:
            secrets_map = {}
    except (OSError, ValueError):
        secrets_map = {}
    value = secrets_map.get(name)
    if not value:
        value = secrets.token_urlsafe(32)
        secrets_map[name] = value
        try:
            RELAY_SECRETS_FILE.write_text(json.dumps(secrets_map, indent=2))
            os.chmod(RELAY_SECRETS_FILE, 0o600)
        except OSError:
            # Read-only state dir: still return a usable value for this process
            # rather than falling back to a published constant.
            pass
    return value


def _phone_password() -> str:
    """The relay_phone NATS credential. Env seam mirrors the sidecar's
    NATS_PASSWORD pattern (P2): a deploy that randomizes it survives conf
    regen because _DEFAULTS reads the same env. With neither env nor a prior
    value, one is generated for THIS relay (see _relay_secret) — phones learn
    it from the invite payload, never from a compiled-in default."""
    return os.environ.get("NATS_PHONE_PASSWORD", "") or _relay_secret("phone")


def _join_password() -> str:
    """The relay_join bootstrap credential used by `wyrd relay register-nkey`.
    Was a hard-coded constant — the single worst pre-OSS default, since it is
    the account that can register new nodes on a relay."""
    return os.environ.get("RELAY_JOIN_PASSWORD", "") or _relay_secret("join")


def _phone_user_for(household_tag: str) -> str:
    """Per-household phone NATS user name ( hardening).
    One shared relay_phone across all households let any phone read every
    phone's tunnel frames/session tokens and study deltas; per-household
    accounts scope the blast radius to the holder's own household."""
    return f"phone-{household_tag}"


def _phone_password_for(household_tag: str) -> str:
    """Deterministic per-household phone credential, derived from the master
    phone secret — survives conf regens with no extra state; rotating the
    master (NATS_PHONE_PASSWORD) rotates every household's phone credential."""
    import hmac as _hmac
    import hashlib as _hashlib
    return _hmac.new(_phone_password().encode(), household_tag.encode(),
                     _hashlib.sha256).hexdigest()[:43]


def _phone_perms_for(zone_id) -> dict:
    """Subject permissions for a per-household phone account. Scoped to the
    household's own zone when the registration knows its label; falls back to
    the legacy-broad set when zone_id is unset/'unspecified' (those zones keep
    the old exposure until they re-register with a label)."""
    if zone_id and zone_id != "unspecified":
        return {
            # Audit F1 (partial): a phone tunnels its session as
            # `wyrd.tunnel.{zone}.{session}.{open,up,close}` and reads `.down`.
            # PUBLISH is scoped to the three C2S verbs (NOT `>`), so a household
            # phone can't spoof server-side `.down` frames into a sibling's
            # session. SUBSCRIBE is scoped to `.down` ONLY (NOT `>`): the login
            # session token rides the `.open` payload, so a broad `wyrd.tunnel.>`
            # subscribe let one household phone HARVEST siblings' session tokens
            # (→ full account impersonation). Reading `.down` only removes that.
            # Residual: cross-session `.up` injection / `.down` reads WITHIN a
            # household still need per-session (dynamic) credentials — the session
            # id is client-chosen and static ACLs can't bind "sessions you own".
            # Mode 4 (own local node, home GPU behind it) borrows inference the
            # same way any GPU-less node does — over federation, not the tunnel.
            # docs/public/MODELS.md already promises this ("the phone borrows the
            # 9B ... the NatsRemote backend"); without the grant it worked on the
            # LAN and silently failed everywhere else.
            #
            # PUBLISH is the request to THIS zone only, never `federation.>` —
            # a phone may ask its own household for capacity, not any zone it can
            # name. SUBSCRIBE is stream chunks: streamId is client-generated and
            # unguessable, and binding it per-session would need dynamic creds
            # (same residual as the tunnel `.up` note above).
            "publish": [f"wyrd.zone.{zone_id}.>", "wyrd.discover.>",
                        f"wyrd.tunnel.{zone_id}.*.open",
                        f"wyrd.tunnel.{zone_id}.*.up",
                        f"wyrd.tunnel.{zone_id}.*.close",
                        f"between.{zone_id}.*.*.study.state",
                        f"between.{zone_id}.*.*.study.sync",
                        f"federation.inference.{zone_id}.complete", "_INBOX.>"],
            "subscribe": [f"wyrd.tunnel.{zone_id}.*.down",
                          f"between.{zone_id}.*.*.study.state",
                          f"between.{zone_id}.*.*.study.sync",
                          "federation.inference.stream.*", "_INBOX.>"],
        }
    return {
        "publish": ["wyrd.zone.>", "wyrd.discover.>", "wyrd.tunnel.>",
                    "between.*.*.*.study.state", "between.*.*.*.study.sync",
                    "federation.inference.*.complete", "_INBOX.>"],
        "subscribe": ["wyrd.tunnel.>", "between.*.*.*.study.state",
                      "between.*.*.*.study.sync",
                      "federation.inference.stream.*", "_INBOX.>"],
    }


def _verify_nkey_sig(pubkey: str, challenge: bytes, signature_b64: str) -> str | None:
    """Verify an Ed25519 signature under a NATS user NKey pubkey.

    Returns None on success, or a human-readable error string. Same
    decode path as re_register_existing_nkey (base32 NKey → strip prefix
    byte + CRC16 trailer → raw Ed25519 verify key).
    """
    try:
        import nkeys  # type: ignore
    except ImportError:
        return "server missing 'nkeys' Python package"
    try:
        import nacl.signing as _signing  # type: ignore
        import nacl.exceptions as _nacl_exc  # type: ignore
    except ImportError:
        return "server missing 'pynacl' Python package"
    try:
        signature = base64.b64decode(signature_b64)
    except Exception as e:
        return f"signature is not valid base64: {e}"
    try:
        raw = base64.b32decode(pubkey.encode("ascii"))
        if len(raw) != 35 or raw[0] != nkeys.PREFIX_BYTE_USER:
            return "pubkey is not a valid NATS user NKey (length/prefix)"
        verify_key = _signing.VerifyKey(bytes(raw[1:33]))
        try:
            verify_key.verify(challenge, signature)
        except _nacl_exc.BadSignatureError:
            return "signature verification failed (bad sig)"
    except Exception as e:
        return f"signature verification failed: {e}"
    return None


def mint_phone_invite(pubkey: str = None, ts=None, signature_b64: str = None,
                      household_id: str = None, token: str = None,
                      max_skew_seconds: int = 300) -> dict:
    """mint a phone connection invite for a
    registered household.

    Two proofs of registration are accepted (a zone presents whichever
    matches how it enrolled — both registration modes coexist per
):
      1. NKey: Ed25519 signature over `phone-invite:{ts}:{pubkey}` under
         an already-registered, active pubkey (the domain prefix prevents
         cross-protocol replay of /re-register-nkey signatures). ts within
         ±max_skew_seconds (anti-replay).
      2. Token: the household's relay credential (`household_id` + the
         256-bit `token` only that zone holds) — covers password-mode
         registrations from `wyrd relay join` / `wyrd relay register`.

    The payload's `relays` is an ORDERED LIST (phone failover,
) even though one relay can only vouch for
    itself — the zone-side CLI may merge lists from several relays.
    """
    now = int(time.time())
    with lock:
        regs = load_registrations()

    existing = None
    if pubkey and pubkey.startswith("U") and len(pubkey) == 56 \
            and regs.get(pubkey, {}).get("kind") == "nkey":
        # NKey path.
        if signature_b64 is None or ts is None:
            return {"error": "ts and signature are required", "_status": 400}
        try:
            ts_int = int(ts)
        except (ValueError, TypeError):
            return {"error": "ts must be an integer (epoch seconds)", "_status": 400}
        if abs(now - ts_int) > max_skew_seconds:
            return {"error": f"timestamp skew too large ({abs(now - ts_int)}s) — clock drift?",
                    "_status": 401}
        challenge = f"phone-invite:{ts_int}:{pubkey}".encode("utf-8")
        sig_err = _verify_nkey_sig(pubkey, challenge, signature_b64)
        if sig_err:
            return {"error": sig_err, "_status": 401}
        existing = regs[pubkey]
    elif household_id and token:
        # Token path (password-mode registration).
        entry = regs.get(household_id)
        if not entry or "token" not in entry:
            return {"error": "unknown household — register this zone first "
                             "(wyrd relay join <host> <code>)", "_status": 404}
        if not hmac.compare_digest(str(entry.get("token", "")), str(token)):
            return {"error": "invalid household token", "_status": 401}
        existing = dict(entry)
        existing.setdefault("household_tag", household_id)
    else:
        return {"error": "registration proof required: either "
                         "{pubkey, ts, signature} or {household_id, token}",
                "_status": 404}

    if not existing.get("active", True):
        return {"error": "registration deactivated by operator", "_status": 403}

    hosts = _public_hosts()
    host = hosts[0]
    # The invite IS the trust decision (§10.9): it carries FINGERPRINT
    # pins (the app pins the household CA by fingerprint at paste/scan
    # time — no cleartext bootstrap, no prompt). The full CA PEM is
    # several KB and would overflow QR capacity, so it rides only in the
    # JSON response for paste/deep-link flows.
    try:
        ca_pem, ca_fp = _ca_pem_and_fingerprint()
        ca_b64 = _b64url(ca_pem.encode())
        leaf_fp = _leaf_fingerprint()
    except FileNotFoundError as e:
        return {"error": str(e), "_status": 503}
    # One relay entry per address the relay answers on — the `relays` list is
    # the phone's ordered FAILOVER list (§2.1): it tries each ws_url and uses
    # the first that connects. Same household CA + phone creds for all; only
    # the dial address differs. On a multi-NIC relay this means the phone works
    # regardless of which LAN it's on, with no host to pick.
    # Per-household phone credential (hardening): scoped to this household's
    # own tunnel/study subjects. The shared relay_phone remains only as a
    # deprecated fallback for registrations that predate household tags.
    _tag = existing.get("household_tag")
    if _tag and _tag != "unspecified":
        _inv_user, _inv_pass = _phone_user_for(_tag), _phone_password_for(_tag)
    else:
        _inv_user, _inv_pass = "relay_phone", _phone_password()
    relay_entries = [
        {
            "ws_url": f"wss://{h}:{RELAY_PUBLIC_PORT}",
            "nats_user": _inv_user,
            "nats_password": _inv_pass,
            "ca_fp": ca_fp,
            "fp": leaf_fp,
        }
        for h in hosts
    ]
    payload = {
        "v": 1,
        "kind": "phone",
        "relays": relay_entries,
        "household_id": existing.get("household_tag", "unspecified"),
        "zone_id": existing.get("zone_id", "unspecified"),
        "minted_at": now,
    }
    payload_b64 = _b64url(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
    return {
        "invite_url": f"wyrdphone://{host}:{RELAY_PUBLIC_PORT}/{payload_b64}",
        "payload": payload,
        "host": host,
        "port": RELAY_PUBLIC_PORT,
        "ca": ca_b64,
    }


def _consume_nonce(nonce: str, expiry: int) -> bool:
    """Mark a nonce as consumed. Returns False if already seen."""
    with lock:
        seen = {}
        if SEEN_NONCES_FILE.exists():
            try:
                seen = json.loads(SEEN_NONCES_FILE.read_text())
            except json.JSONDecodeError:
                seen = {}
        # Garbage collect expired nonces while we're here.
        now = int(time.time())
        seen = {k: v for k, v in seen.items() if v > now}
        if nonce in seen:
            return False
        seen[nonce] = expiry
        SEEN_NONCES_FILE.write_text(json.dumps(seen))
        return True


def verify_invite(token: str) -> dict:
    """Verify an invite token. Returns the payload dict or raises ValueError."""
    if not token or "." not in token:
        raise ValueError("Malformed token")
    payload_b64, sig_b64 = token.split(".", 1)
    try:
        payload_bytes = _b64url_decode(payload_b64)
        provided_sig = _b64url_decode(sig_b64)
    except Exception as e:
        raise ValueError(f"Decode failed: {e}")

    expected_sig = hmac.new(
        _load_or_init_invite_key(), payload_bytes, hashlib.sha256
    ).digest()
    if not hmac.compare_digest(provided_sig, expected_sig):
        raise ValueError("Bad signature")

    payload = json.loads(payload_bytes)
    if payload.get("exp", 0) < int(time.time()):
        raise ValueError("Token expired")
    if not _consume_nonce(payload.get("n", ""), payload["exp"]):
        raise ValueError("Token already used")
    return payload


def register_household(ip: str) -> dict:
    """Register a new household. Returns token or error."""
    with lock:
        # Rate limit — skipped when RATE_LIMIT_SECONDS=0
        now = time.time()
        if RATE_LIMIT_SECONDS > 0:
            last = rate_limits.get(ip, 0)
            if now - last < RATE_LIMIT_SECONDS:
                wait = int(RATE_LIMIT_SECONDS - (now - last))
                return {"error": f"Rate limited. Try again in {wait}s."}

        regs = load_registrations()

        # Capacity check
        active = {k: v for k, v in regs.items() if v.get("active", True)}
        if len(active) >= CAPACITY:
            return {
                "error": "Relay at capacity",
                "capacity": CAPACITY,
                "registered": len(active),
                "suggestion": "Deploy your own relay: https://github.com/wyrdsekai/wyrdsekai/tree/main/deploy/relay"
            }

        # Generate credentials
        household_id = f"hh-{secrets.token_hex(6)}"
        token = generate_token()

        regs[household_id] = {
            "token": token,
            "registered_at": datetime.utcnow().isoformat(),
            "registered_ip": ip,
            "active": True,
            "last_seen": None
        }

        save_registrations(regs)
        rate_limits[ip] = now

        # Update NATS config
        try:
            update_nats_config(regs)
        except Exception as e:
            return {"error": f"Failed to update NATS config: {e}"}

        return {
            "household_id": household_id,
            "token": token,
            "relay_url": f"nats://0.0.0.0:{NATS_PORT}",  # host substituted by caller; port is authoritative
            "nats_user": household_id,
            "nats_password": token
        }


def register_nkey(ip: str, pubkey: str, household_tag: str = None,
                  zone_id: str = None, node_name: str = None,
                  entrant_tier: str = None, identity_outbox: dict = None) -> dict:
    """/register-nkey.

    Idempotent: registering the same pubkey twice updates the metadata in place
    and returns the same identity. This is the drift-recovery path — a node that
    lost its env can re-register with the same NodeIdentity NKey and get back
    the same NATS authorization with no operator intervention.

    `entrant_tier` is the tier a NEW registration enters at, decided by the
    caller from the relay mode (HOUSEHOLD for invite-only/open, FLOOR for
    commons). On a re-register the existing tier is preserved (a vouched/
    promoted node never silently drops to FLOOR by re-registering).

    `identity_outbox`, when present, is a parsed IdentityOutboxRecord (§2.2);
    it is verified here and, on success, stored with identity_verified=true so
    a commons FLOOR record becomes eligible for promotion (§3).

    Returns {pubkey, household_id, subject_permissions, relay_url, tier,
    identity_verified} on success or {error: ...} on rejection. Caller is
    responsible for invite_token / mode-gate check (already done at the HTTP
    layer).
    """
    if not pubkey or not pubkey.startswith("U") or len(pubkey) != 56:
        return {"error": "Invalid NKey pubkey (must be 56-char NATS user-key, starting with 'U')"}

    # Verify a presented IdentityOutbox up front (§2.2). A PRESENT-but-INVALID
    # record is a hard reject — silently storing an unverified channel list
    # would let a registrant forge reachability. Absent is fine (FLOOR is
    # reachable without it; it's only the price of PROMOTION).
    identity_verified = False
    verified_outbox = None
    if identity_outbox is not None:
        err = verify_identity_outbox(identity_outbox)
        if err:
            return {"error": f"identity_outbox rejected: {err}", "_status": 400}
        # The record's DID MUST match the registering key's DID — otherwise a
        # registrant could attach someone else's (validly-signed) identity.
        reg_did = nkey_to_did(pubkey)
        if reg_did and identity_outbox.get("did") != reg_did:
            return {"error": "identity_outbox.did does not match the registering "
                             "key's DID", "_status": 400}
        identity_verified = True
        verified_outbox = identity_outbox

    with lock:
        regs = load_registrations()

        # Capacity check (counts both password and nkey households against the same cap;
        # intentional — abuse vector is connection load, not entry count).
        active = {k: v for k, v in regs.items() if v.get("active", True)}
        if pubkey not in regs and len(active) >= CAPACITY:
            return {
                "error": "Relay at capacity",
                "capacity": CAPACITY,
                "registered": len(active),
                "suggestion": "Deploy your own relay: https://github.com/wyrdsekai/wyrdsekai/tree/main/deploy/relay"
            }

        # per-tier registration cap (the commons
        # Sybil/flood defense, ENFORCED). Only a NEW entrant is gated; a
        # re-register of an existing record always passes (its tier is preserved
        # below, so it cannot push a tier over its own cap). The entrant tier is
        # the tier this NEW record would enter at (HOUSEHOLD default). A negative
        # max_registrations means unlimited (the default for VOUCHED/HOUSEHOLD).
        if pubkey not in regs:
            new_tier = (entrant_tier or TIER_HOUSEHOLD).upper()
            tier_cap = tier_quota(new_tier).get("max_registrations", -1)
            if tier_cap is not None and tier_cap >= 0:
                tier_active = sum(
                    1 for v in active.values()
                    if (v.get("tier") or TIER_HOUSEHOLD).upper() == new_tier
                )
                if tier_active >= tier_cap:
                    return {
                        "error": f"{new_tier} tier at capacity",
                        "tier": new_tier,
                        "tier_cap": tier_cap,
                        "tier_registered": tier_active,
                        "suggestion": "Deploy your own relay: https://github.com/wyrdsekai/wyrdsekai/tree/main/deploy/relay"
                    }

        # Idempotent: if the pubkey is already registered, refresh metadata + last_seen.
        # If household_tag is provided and different from existing, update it (operator
        # may be re-tagging). If absent, keep existing.
        existing = regs.get(pubkey, {})
        # Tier (§3): a NEW record enters at the caller-decided entrant_tier
        # (HOUSEHOLD default if unspecified). A re-register PRESERVES whatever
        # tier the record already holds — a promoted/vouched node must not drop
        # to FLOOR just by re-registering.
        if existing.get("tier"):
            tier = existing["tier"]
        else:
            tier = (entrant_tier or TIER_HOUSEHOLD).upper()
        # identity_verified is sticky: once verified it stays verified across
        # re-registers; a fresh verified record on re-register can set it true.
        new_identity_verified = bool(existing.get("identity_verified")) or identity_verified
        regs[pubkey] = {
            "kind": "nkey",
            "pubkey": pubkey,
            # canonical DID derived relay-side
            # from the pubkey (un-spoofable; same Ed25519 key the node's own
            # NodeIdentity→DidKey computes). Refreshed on every (re-)register.
            "did": nkey_to_did(pubkey) or existing.get("did"),
            "household_tag": household_tag or existing.get("household_tag", "unspecified"),
            "zone_id": zone_id or existing.get("zone_id", "unspecified"),
            "node_name": node_name or existing.get("node_name", "unknown"),
            # trust tier (gates the reaper window + is
            # the hook for per-tier quota). §2.2 — verified IdentityOutbox flag.
            "tier": tier,
            "identity_verified": new_identity_verified,
            "registered_at": existing.get("registered_at", datetime.utcnow().isoformat()),
            "registered_ip": ip,
            "active": True,
            "last_seen": datetime.utcnow().isoformat()
        }
        if verified_outbox is not None:
            regs[pubkey]["identity_outbox"] = verified_outbox
        elif existing.get("identity_outbox") is not None:
            regs[pubkey]["identity_outbox"] = existing["identity_outbox"]
        # the ssh_tunnel sub-record (enabled flag + pubkey +
        # assigned_port) is STICKY across re-register, like tier/identity_verified:
        # a zone re-registering must not lose its SSH tunnel or its port.
        if existing.get("ssh_tunnel") is not None:
            regs[pubkey]["ssh_tunnel"] = existing["ssh_tunnel"]

        save_registrations(regs)

        # Update NATS config (atomic rewrite + signal reload).
        try:
            update_nats_config(regs)
        except Exception as e:
            return {"error": f"Failed to update NATS config: {e}"}

        return {
            "pubkey": pubkey,
            "household_id": regs[pubkey]["household_tag"],
            "zone_id": regs[pubkey]["zone_id"],
            "tier": regs[pubkey]["tier"],
            "identity_verified": regs[pubkey]["identity_verified"],
            "subject_permissions": _subject_permissions_for(
                regs[pubkey]["household_tag"], regs[pubkey]["zone_id"]),
            "relay_url": f"nats://0.0.0.0:{NATS_PORT}",  # host substituted by caller; port is authoritative
        }


def gate_register_nkey(body: dict, *, verify_invite_fn=None) -> dict:
    """apply the relay's MODE gate to a register-nkey
    request and resolve the entrant tier. Shared by the HTTP and NATS surfaces.

    Returns a dict:
      {"ok": True, "entrant_tier": TIER, "identity_outbox": <verified dict|None>}
    on success, or {"error": str, "_status": int} on rejection.

    Mode semantics (§4):
      invite-only — a valid invite_token is REQUIRED; entrant tier = HOUSEHOLD.
      open        — invite-less accepted (LAN/firewalled); entrant tier = HOUSEHOLD.
      commons     — invite-less accepted but entrant tier = FLOOR; the DID
                    (R2.1) is MANDATORY (always true for a valid NKey) and a
                    verified IdentityOutbox is required for promotion above FLOOR
                    (NOT to register). Hard per-IP rate-limit applies at the
                    HTTP layer.

    `verify_invite_fn` defaults to the module verify_invite (single-use). The
    function consumes the invite nonce on success, so call it exactly once.
    """
    verify_invite_fn = verify_invite_fn or verify_invite
    mode = relay_mode()
    invite_token = body.get("invite_token")

    if mode == "invite-only":
        if not invite_token:
            return {"error": "invite_token required for /register-nkey "
                             "(relay mode: invite-only)",
                    "hint": "Mint one on the relay host via wyrd relay invite",
                    "_status": 401}
        try:
            verify_invite_fn(invite_token)
        except ValueError as e:
            return {"error": f"Invite rejected: {e}", "_status": 401}
        entrant_tier = TIER_HOUSEHOLD

    elif mode == "open":
        # Invite OPTIONAL on an open relay (the perimeter is the trust boundary).
        # If one is presented we still consume it (so it can't be replayed); a
        # bad token is non-fatal in open mode — the registration proceeds.
        if invite_token:
            try:
                verify_invite_fn(invite_token)
            except ValueError:
                pass
        entrant_tier = TIER_HOUSEHOLD

    elif mode == "commons":
        # Invite-less self-serve FLOOR registration. No invite required; the
        # per-IP rate-limit (HTTP layer) is the only anti-abuse gate for v1
        # (§13: rate-limit-alone is the conservative default — no PoW).
        entrant_tier = TIER_FLOOR

    else:  # unreachable — relay_mode() normalizes, but be defensive.
        return {"error": f"relay misconfigured (unknown mode '{mode}')",
                "_status": 503}

    return {"ok": True, "entrant_tier": entrant_tier,
            "identity_outbox": body.get("identity_outbox")}


def re_register_existing_nkey(ip: str, pubkey: str, ts: int, signature_b64: str,
                              max_skew_seconds: int = 300) -> dict:
    """/re-register-nkey — drift recovery without an invite.

    Verifies that the caller holds the seed for an already-registered pubkey by
    requiring a signature over `ts:pubkey`. Steps:
      1. Pubkey must already be in regs.json (kind=nkey).
      2. ts within ±max_skew_seconds of now (anti-replay).
      3. signature is a valid Ed25519 signature of `ts:pubkey` under the pubkey.
    On success, refresh last_seen + registered_ip; do NOT change household_tag/
    zone_id (those came from the original invite-bound registration).

    Returns same shape as register_nkey on success, or {error: ...} on failure.
    """
    if not pubkey or not pubkey.startswith("U") or len(pubkey) != 56:
        return {"error": "Invalid NKey pubkey", "_status": 400}
    if signature_b64 is None or ts is None:
        return {"error": "ts and signature are required", "_status": 400}

    # Skew check.
    now = int(time.time())
    try:
        ts_int = int(ts)
    except (ValueError, TypeError):
        return {"error": "ts must be an integer (epoch seconds)", "_status": 400}
    if abs(now - ts_int) > max_skew_seconds:
        return {"error": f"timestamp skew too large ({abs(now - ts_int)}s) — clock drift?",
                "_status": 401}

    # Load regs and confirm pubkey is known.
    with lock:
        regs = load_registrations()
        existing = regs.get(pubkey)
        if not existing or existing.get("kind") != "nkey":
            # Specifically signal "unknown pubkey" so the client can suggest a fresh invite.
            return {"error": "unknown pubkey — relay does not have a record. "
                             "Get a fresh invite and run register-nkey.", "_status": 404}
        if not existing.get("active", True):
            return {"error": "pubkey deactivated by operator — cannot re-register",
                    "_status": 403}

        # Verify signature. We use jnats's Python equivalent: `nkeys` package.
        # Lazy-import so the rest of registration.py doesn't fail if nkeys isn't installed
        # (it's a small new dep introduced in F24 Phase 2).
        try:
            import nkeys  # type: ignore
        except ImportError:
            return {"error": "server missing 'nkeys' Python package — install with "
                             "'pip install nkeys' on the relay host", "_status": 500}
        # Python `nkeys` doesn't expose a from_public_key() helper, and the
        # KeyPair(public_key=...) constructor leaves the underlying nacl
        # VerifyKey unset. We do the verify ourselves: base32-decode the
        # NKey, strip the prefix byte + 2-byte CRC16 trailer, and pass the
        # remaining 32 bytes to nacl.signing.VerifyKey.
        try:
            import nacl.signing as _signing  # type: ignore
            import nacl.exceptions as _nacl_exc  # type: ignore
        except ImportError:
            return {"error": "server missing 'pynacl' Python package — install via "
                             "'pip install pynacl' (transitive dep of nkeys)",
                    "_status": 500}
        try:
            challenge = f"{ts_int}:{pubkey}".encode("utf-8")
            try:
                signature = base64.b64decode(signature_b64)
            except Exception as e:
                return {"error": f"signature is not valid base64: {e}", "_status": 400}

            raw = base64.b32decode(pubkey.encode("ascii"))
            if len(raw) != 35 or raw[0] != nkeys.PREFIX_BYTE_USER:
                return {"error": "pubkey is not a valid NATS user NKey (length/prefix)",
                        "_status": 400}
            ed25519_pub = bytes(raw[1:33])  # strip prefix byte + last 2 CRC bytes
            verify_key = _signing.VerifyKey(ed25519_pub)
            try:
                verify_key.verify(challenge, signature)
            except _nacl_exc.BadSignatureError:
                return {"error": "signature verification failed (bad sig)",
                        "_status": 401}
        except Exception as e:
            return {"error": f"signature verification failed: {e}", "_status": 401}

        # OK — refresh last_seen + IP, keep tags. Stamp/refresh the canonical
        # DID (R2.1) so records that pre-date the field get it on re-register.
        existing["last_seen"] = datetime.utcnow().isoformat()
        existing["registered_ip"] = ip
        existing["did"] = nkey_to_did(pubkey) or existing.get("did")
        regs[pubkey] = existing
        save_registrations(regs)

        # NATS config doesn't change (same user entry already there) — but
        # re-emit it defensively in case it drifted out of sync. Skip the
        # nats reload if regs.json is the only thing that changed; rewriting
        # the conf is idempotent and a clean defensive measure.
        try:
            update_nats_config(regs)
        except Exception as e:
            # Non-fatal — the regs.json side is what matters for verification.
            return {
                "pubkey": pubkey,
                "household_id": existing.get("household_tag", "unspecified"),
                "zone_id": existing.get("zone_id", "unspecified"),
                "warn": f"re-registered, but NATS config rewrite warned: {e}",
            }

        return {
            "pubkey": pubkey,
            "household_id": existing.get("household_tag", "unspecified"),
            "zone_id": existing.get("zone_id", "unspecified"),
            "subject_permissions": _subject_permissions_for(
                existing.get("household_tag"), existing.get("zone_id")),
            "relay_url": f"nats://0.0.0.0:{NATS_PORT}",
        }


def deregister_nkey(pubkey: str, ts: int, signature_b64: str,
                    max_skew_seconds: int = 300) -> dict:
    """Relay-side `/deregister` — voluntary teardown of a registration.

    The inverse of re_register_existing_nkey: the caller proves seed-ownership
    by signing a challenge, and on success the record is HARD-DELETED (not
    deactivated — re-register-existing 403s on inactive records, so a soft
    flag would be a dead-end). The pubkey is then pulled from the live NATS
    auth config so it can no longer authenticate.

    Challenge string is `deregister:{ts}:{pubkey}` — a DISTINCT namespace from
    re-register's bare `{ts}:{pubkey}`, so a captured re-register signature can
    never be replayed as a delete (and vice-versa).

    Idempotent: a retried leave whose pubkey is already gone returns 200
    {"status": "already_absent"} rather than 404, so a client that retries
    after a successful-but-unacknowledged delete still succeeds.

    Returns a dict with `_status` carrying the HTTP-equivalent code.
    """
    if not pubkey or not pubkey.startswith("U") or len(pubkey) != 56:
        return {"error": "Invalid NKey pubkey", "_status": 400}
    if signature_b64 is None or ts is None:
        return {"error": "ts and signature are required", "_status": 400}

    # Skew check (anti-replay), same window as re-register.
    now = int(time.time())
    try:
        ts_int = int(ts)
    except (ValueError, TypeError):
        return {"error": "ts must be an integer (epoch seconds)", "_status": 400}
    if abs(now - ts_int) > max_skew_seconds:
        return {"error": f"timestamp skew too large ({abs(now - ts_int)}s) — clock drift?",
                "_status": 401}

    # Verify the signature BEFORE touching regs — an unauthenticated caller must
    # never learn (via timing or response shape) whether a pubkey is registered.
    challenge = f"deregister:{ts_int}:{pubkey}".encode("utf-8")
    sig_err = _verify_nkey_sig(pubkey, challenge, signature_b64)
    if sig_err:
        return {"error": sig_err, "_status": 401}

    with lock:
        regs = load_registrations()
        if pubkey not in regs:
            # Idempotent: a retried leave succeeds even if the record is gone.
            return {"status": "already_absent", "pubkey": pubkey, "_status": 200}

        del regs[pubkey]
        save_registrations(regs)

        # Pull the NKey from the live auth config so it can't reconnect.
        try:
            update_nats_config(regs)
        except Exception as e:
            # The regs.json delete is the source of truth; a conf-rewrite
            # warning shouldn't fail the leave (boot rehydrate reprojects).
            return {"status": "deregistered", "pubkey": pubkey,
                    "warn": f"deregistered, but NATS config rewrite warned: {e}",
                    "_status": 200}

        print(f"[deregister] {pubkey[:12]}… removed from regs + NATS config")
        return {"status": "deregistered", "pubkey": pubkey, "_status": 200}


def mint_peer_invite(remote_host_hint: str = None, ttl_seconds: int = None) -> dict:
    """mint a single-use peer-invite token.

    Distinct from /invite (which mints a household-join token). A peer invite
    has scope='peer' in its payload and is meant to be `wyrd relay peer-accept`-ed
    by another relay operator. Once accepted, the two relays trust each other
    as NATS leafnode peers.

    The signing key is the same per-relay invite_key; verifier is _verify_peer_invite.
    """
    ttl = max(60, min(int(ttl_seconds) if ttl_seconds else INVITE_DEFAULT_TTL,
                     INVITE_MAX_TTL))
    fingerprint = _leaf_fingerprint()
    payload = {
        "scope": "peer",
        "fp": fingerprint,
        "exp": int(time.time()) + ttl,
        "n": _b64url(secrets.token_bytes(16)),
        "host_hint": remote_host_hint or "",
    }
    payload_bytes = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    sig = hmac.new(_load_or_init_invite_key(), payload_bytes, hashlib.sha256).digest()
    token = _b64url(payload_bytes) + "." + _b64url(sig)
    host = RELAY_PUBLIC_HOST or "localhost"
    invite_url = f"wyrdrelay-peer://{host}:{RELAY_PUBLIC_PORT}/{token}"
    return {
        "invite_url": invite_url,
        "token": token,
        "scope": "peer",
        "fingerprint": fingerprint,
        "expires_at": payload["exp"],
        "host": host,
        "port": RELAY_PUBLIC_PORT,
    }


def verify_peer_invite(token: str) -> dict:
    """Verify a peer-invite token. Returns payload or raises ValueError."""
    if not token or "." not in token:
        raise ValueError("Malformed token")
    payload_b64, sig_b64 = token.split(".", 1)
    try:
        payload_bytes = _b64url_decode(payload_b64)
        provided_sig = _b64url_decode(sig_b64)
    except Exception as e:
        raise ValueError(f"Decode failed: {e}")
    expected_sig = hmac.new(
        _load_or_init_invite_key(), payload_bytes, hashlib.sha256
    ).digest()
    if not hmac.compare_digest(provided_sig, expected_sig):
        raise ValueError("Bad signature")
    payload = json.loads(payload_bytes)
    if payload.get("scope") != "peer":
        raise ValueError(f"Wrong scope: {payload.get('scope')} (expected 'peer')")
    if payload.get("exp", 0) < int(time.time()):
        raise ValueError("Token expired")
    if not _consume_nonce(payload.get("n", ""), payload["exp"]):
        raise ValueError("Token already used")
    return payload


def accept_peer_invite(remote_token: str, remote_url: str, remote_pubkey: str = None,
                       remote_fingerprint: str = None) -> dict:
    """accept a peer invite from another relay.

    Records the remote relay in our peers.json under a 'peers' key. The actual
    NATS leafnodes.remotes config update is the operator's next step (template
    output is included in the response for them to splice into relay.conf).

    The peer-accept flow is mutual-consent: both relays must run their side of
    `peer-invite`/`peer-accept` for the link to come up. Without that symmetry,
    one side can advertise the relationship but traffic won't flow because the
    other side hasn't authorised the leafnode connection.

    `remote_url` is the upstream NATS URL (typically the OTHER relay's leaf
    port). `remote_pubkey` is the OTHER relay's nkeyPublicKey() — used for
    auth on the leafnodes link. `remote_fingerprint` is the SHA-256 of the
    other relay's leaf cert, for TLS pinning.
    """
    if not remote_url or not remote_pubkey:
        return {"error": "remote_url and remote_pubkey are required",
                "_status": 400}

    with lock:
        peers = load_peers()
        # Dedup on URL — same peer can't be added twice.
        peers = [p for p in peers if p.get("url") != remote_url]
        peer_record = {
            "kind": "peer-relay",
            "url": remote_url,
            "pubkey": remote_pubkey,
            "fingerprint": remote_fingerprint or "",
            "accepted_at": datetime.utcnow().isoformat(),
        }
        peers.append(peer_record)
        save_peers(peers)

    return {
        "status": "peer accepted",
        "peer": peer_record,
        "next_step": (
            "Add the following block to your NATS relay.conf leafnodes.remotes "
            "and reload nats-server (full restart, not SIGHUP):\n"
            f"  remotes = [\n"
            f"    {{\n"
            f"      url: \"{remote_url}\"\n"
            f"      account: \"$SYS\"  # or scoped account if you use them\n"
            f"    }}\n"
            f"  ]"
        ),
    }


def _subject_permissions_for(household_tag: str, zone_id: str) -> dict:
    """namespace isolation: scope each pubkey to its own
    zone's between subjects + its zone's federation gate + _INBOX.

    Actual subject schema (verified live 2026-04-28): wyrdsekai publishes on
    `between.{zone_id}.{nodeId}.>` — NOT `between.{household_tag}.>` as the
    Phase 2 first-pass assumed. Same household across zones (e.g. home-server in alpha
    + test-node in beta) get separate prefixes; cross-household isolation is
    achieved by zone-id naming convention.

    Each pubkey gets BOTH directions (publish + subscribe) on its own zone's
    between subjects + the federation.{zone_id}.> gate (where it sources/sinks
    cross-zone tells) + _INBOX.> (NATS request/reply requires this).

    `unspecified` falls back to permissive (between.> + federation.>) so nodes
    that didn't supply tags during registration still work.
    """
    if zone_id and zone_id != "unspecified":
        between_subj = f"between.{zone_id}.>"
        # Audit F6 (pre-OSS): SUBSCRIBE used to be a blanket `between.>`, letting
        # any household node read every other zone's between traffic — presence,
        # cluster heartbeats, room snapshots, study-sync deltas. The zone's
        # RelayBridge only ever needs its OWN zone (`between.{zone}.>`, forwarded
        # in both directions) plus REMOTE zones' capability announcements
        # (`between.*.*.*.capability.announce` — subscribeRemoteZone in
        # RelayBridge.java). Everything else cross-zone rides `federation.>`.
        between_sub = [between_subj, "between.*.*.*.capability.announce"]
    else:
        between_subj = "between.>"
        between_sub = ["between.>"]
    # Federation gate: cross-zone delivery uses `federation.{destZone}.gate.>`,
    # so a node needs publish on ANY zone's federation gate (it addresses the
    # peer's mailbox). Tightening to "only zones I have an active agreement
    # with" would need agreement-aware permissions — a future hardening.
    # For now, mirror the legacy permissive federation.> on both sides.
    #
    # Subscribe is broader: a node sees its own between traffic + remote
    # zones' capability announcements (which arrive on between.{remoteZone}.>),
    # and federation gate replies on federation.{ownZone}.>.
    # `wyrd.zone.>` carries the MCP NATS surface
    # (login/tell/library/journal) and follow-ons. Each zone's Java server
    # opens a relay-side NATS connection with these creds and subscribes
    # `wyrd.zone.{zoneId}.*` to receive phone-side request/reply messages.
    # Without it the relay-side McpNatsHandler silently can't subscribe and
    # phones get "no responders" on wyrd.zone.{zone}.mcp.login.
    # `wyrd.discover.>` is a global zone-discovery namespace — phones publish
    # `wyrd.discover.zone` to learn the zone label before scoping subsequent
    # wyrd.zone.{label}.* subjects.
    # `wyrd.tunnel.>` carries the dumb session pipe: the phone
    # opens `wyrd.tunnel.{zone}.{session}.{open,up,close}` and reads `.down`; the
    # zone tunnels those raw C2S/S2C frames into its own /ws. The relay only
    # shuffles bytes on these subjects — it never parses them.
    #
    # Audit F4 (2026-07-25): `wyrd.zone.>` and `wyrd.tunnel.>` used to be blanket
    # on BOTH directions for every household node. That let any registered
    # household impersonate another zone's MCP surface (publishing replies on
    # `wyrd.zone.{other}.mcp.*` — the phone request/reply channel carrying
    # login), and read/inject other households' tunnel sessions. A zone server
    # only ever needs its OWN label: WyrdConfig.zoneId() is what McpNatsHandler
    # subscribes and what TunnelSessionHandler prefixes. Zones registered
    # without a label keep the legacy broad grant until they re-register.
    if zone_id and zone_id != "unspecified":
        zone_subj = [f"wyrd.zone.{zone_id}.>", f"wyrd.tunnel.{zone_id}.>"]
    else:
        zone_subj = ["wyrd.zone.>", "wyrd.tunnel.>"]
    return {
        "publish":   [between_subj, "federation.>", *zone_subj, "wyrd.discover.>", "_INBOX.>"],
        "subscribe": between_sub + ["federation.>", *zone_subj, "wyrd.discover.>", "_INBOX.>"],
    }


def _sanitize_ssh_pubkey(raw: str):
    """Accept ONLY a bare `ssh-ed25519 <base64> [comment]` line; reject anything
    else (rsa/ecdsa, leading key-options, embedded newlines) so a registrant can
    never smuggle their own command=/permitlisten into authorized_keys. Returns
    the normalized line or None."""
    if not raw or "\n" in raw or "\r" in raw:
        return None
    parts = raw.strip().split()
    if len(parts) < 2 or parts[0] != "ssh-ed25519":
        return None
    import base64
    import binascii
    try:
        blob = base64.b64decode(parts[1], validate=True)
    except (binascii.Error, ValueError):
        return None
    # OpenSSH ed25519 blob: len(4)+"ssh-ed25519"(11) + len(4)+key(32) = 51 bytes.
    if len(blob) != 51 or blob[4:15] != b"ssh-ed25519":
        return None
    comment = parts[2] if len(parts) > 2 else ""
    comment = "".join(c for c in comment if c.isalnum() or c in "-_.@")
    return f"{parts[0]} {parts[1]}" + (f" {comment}" if comment else "")


def _assign_ssh_tunnel_port(regs: dict, pubkey: str):
    """Lowest free public port in [BASE, BASE+COUNT) for this zone's reverse
    tunnel; sticky (a record that already holds one keeps it); None if the range
    is exhausted. Derived from the ledger — no separate counter to drift. Call
    inside `lock`."""
    rec = regs.get(pubkey, {})
    existing = (rec.get("ssh_tunnel") or {}).get("assigned_port")
    if existing:
        return existing
    taken = {
        (r.get("ssh_tunnel") or {}).get("assigned_port")
        for r in regs.values() if (r.get("ssh_tunnel") or {}).get("assigned_port")
    }
    for p in range(SSH_TUNNEL_PORT_BASE, SSH_TUNNEL_PORT_BASE + SSH_TUNNEL_PORT_COUNT):
        if p not in taken:
            return p
    return None


def ssh_tunnel_mode() -> str:
    """Per-relay ssh-tunnel opt-in policy: off | grant | open. Read-only (no
    seed-on-read write) so it is safe to call while the regs `lock` is held;
    relay.sh seeds the policy file at deploy. Falls back to the env default."""
    m = (load_policy().get("ssh_tunnel_mode") or "").strip().lower()
    if m in _VALID_SSH_MODES:
        return m
    return SSH_TUNNEL_MODE_DEFAULT if SSH_TUNNEL_MODE_DEFAULT in _VALID_SSH_MODES else "off"


def ssh_tunnel_topology() -> str:
    """Per-relay ssh-tunnel exposure: port (per-zone public port) | jump (one
    ProxyJump port). Read-only — see ssh_tunnel_mode for the no-seed rationale."""
    t = (load_policy().get("ssh_tunnel_topology") or "").strip().lower()
    if t in _VALID_SSH_TOPOLOGIES:
        return t
    return SSH_TUNNEL_TOPOLOGY_DEFAULT if SSH_TUNNEL_TOPOLOGY_DEFAULT in _VALID_SSH_TOPOLOGIES else "port"


def _set_ssh_policy(mode: str = None, topology: str = None) -> dict:
    """Persist ssh_tunnel_mode / ssh_tunnel_topology into relay-policy.json
    (admin `set-policy` path). Validates; returns the stored sub-document.
    save_policy is atomic; no extra lock (admin ops are serial)."""
    p = load_policy()
    if mode is not None:
        mode = mode.strip().lower()
        if mode not in _VALID_SSH_MODES:
            raise ValueError(f"invalid ssh_tunnel_mode '{mode}'")
        p["ssh_tunnel_mode"] = mode
    if topology is not None:
        topology = topology.strip().lower()
        if topology not in _VALID_SSH_TOPOLOGIES:
            raise ValueError(f"invalid ssh_tunnel_topology '{topology}'")
        p["ssh_tunnel_topology"] = topology
    p["updated_at"] = datetime.utcnow().isoformat()
    save_policy(p)
    return {"ssh_tunnel_mode": ssh_tunnel_mode(), "ssh_tunnel_topology": ssh_tunnel_topology()}


def _ssh_jump_principal_key():
    """The shared forward-only ProxyJump pubkey (jump topology), or None."""
    try:
        if SSH_JUMP_KEY_PUB.exists():
            return _sanitize_ssh_pubkey(SSH_JUMP_KEY_PUB.read_text().strip())
    except OSError:
        pass
    return None


def _ssh_host_fingerprint():
    """SHA256 fingerprint of the tunnel sshd host key (for the zone to pin), or
    None if the host key or ssh-keygen isn't available yet."""
    try:
        if not SSH_HOST_KEY_PUB.exists():
            return None
        out = subprocess.run(["ssh-keygen", "-lf", str(SSH_HOST_KEY_PUB)],
                             capture_output=True, text=True, timeout=5)
        if out.returncode == 0:
            # "256 SHA256:abc... comment (ED25519)" → the SHA256:… token.
            for tok in out.stdout.split():
                if tok.startswith("SHA256:"):
                    return tok
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def update_ssh_authorized_keys(regs: dict) -> None:
    """Regenerate the tunnel sshd's authorized_keys from registrations.json,
    topology-aware. Parallel to update_nats_config; atomic write; NO sshd reload
    needed (read per-connection). No-op when disabled.

    - `port` topology: one `restrict,permitlisten="0.0.0.0:<port>"` per ACTIVE
      enabled zone (the per-zone PUBLIC bind).
    - `jump` topology: one `restrict,permitlisten="127.0.0.1:<port>"` per zone
      (LOOPBACK, not public) PLUS one forward-only ProxyJump principal line
      `restrict,permitopen="127.0.0.1:<all active ports>"` (direct-tcpip to zone
      ports only, no listen/shell) — the single-port commons door."""
    if not SSH_TUNNEL_ENABLED:
        return
    topo = ssh_tunnel_topology()
    bind = "0.0.0.0" if topo == "port" else "127.0.0.1"
    SSH_AUTHORIZED_KEYS.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    active_ports = []
    for info in regs.values():
        if not info.get("active", True):
            continue
        t = info.get("ssh_tunnel") or {}
        if not t.get("enabled") or not t.get("pubkey") or not t.get("assigned_port"):
            continue
        key = _sanitize_ssh_pubkey(t["pubkey"])
        if not key:
            continue
        port = int(t["assigned_port"])
        active_ports.append(port)
        zone = info.get("zone_id") or "unspecified"
        ztag = "".join(c for c in zone if c.isalnum() or c in "-_") or "zone"
        # `restrict` disables ALL forwarding; `permitlisten=` alone does NOT
        # re-enable it (sshd answers tcpip-forward with "Server has disabled port
        # forwarding" even when the listen matches). The `port-forwarding` option
        # is REQUIRED to restore the capability; `permitlisten` then welds it to
        # exactly one bind. In `jump` topology the global is `AllowTcpForwarding
        # yes` (ProxyJump needs direct-tcpip), so a zone key could otherwise -L
        # pivot anywhere — pin `permitopen` to the zone's OWN loopback port: valid
        # host:port syntax that denies -L to every OTHER host (a -L back to the
        # zone's own reverse-tunnel port just loops, harmless), so no pivot. DO NOT
        # use `permitopen="none"` here — it is NOT accepted as a per-key option
        # ("bad key options: invalid permission port" → the whole key is rejected
        # at auth); `none` is only valid for the global `PermitOpen` directive. In
        # `port` topology the global stays `remote`, which already blocks -L/-D
        # server-side, so no per-key permitopen is needed.
        if topo == "jump":
            lines.append(f'restrict,port-forwarding,permitopen="{bind}:{port}",'
                         f'permitlisten="{bind}:{port}" {key} wyrd-tunnel-{ztag}')
        else:
            lines.append(f'restrict,port-forwarding,permitlisten="{bind}:{port}" '
                         f'{key} wyrd-tunnel-{ztag}')
    if topo == "jump" and active_ports:
        jump_key = _ssh_jump_principal_key()
        if jump_key:
            # The ProxyJump principal forwards (direct-tcpip / -L) ONLY to the
            # active zone loopback ports and nowhere else; it never listens
            # (no permitlisten) and has no shell. `port-forwarding` re-enables the
            # capability `restrict` stripped; `permitopen=` constrains it.
            opens = ",".join(f"127.0.0.1:{p}" for p in sorted(active_ports))
            lines.append(f'restrict,port-forwarding,permitopen="{opens}" '
                         f'{jump_key} wyrd-jump')
    body = ("# GENERATED by registration.py from registrations.json — do not edit.\n"
            + "\n".join(lines) + ("\n" if lines else ""))
    tmp = SSH_AUTHORIZED_KEYS.with_suffix(".tmp")
    tmp.write_text(body)
    # 0644 (world-readable), NOT 0600: sshd reads authorized_keys after
    # temporarily_use_uid() drops to the unprivileged tunnel account, but this
    # file is written by root (the sidecar). A root-owned 0600 file is unreadable
    # by that uid, so sshd SILENTLY skips it (no "trying public key file" log) and
    # every key is rejected. Public keys are not secret; 0644 is the standard mode
    # for a system-location AuthorizedKeysFile. The containing dir is owned by the
    # tunnel account so it stays traversable.
    os.chmod(tmp, 0o644)
    os.replace(tmp, SSH_AUTHORIZED_KEYS)


def update_nats_config(regs: dict):
    """Rewrite the NATS authorization block and signal reload.

    Two correctness rules learned the hard way:

    1. Each household user MUST have a `permissions` block scoping it to its
       own `between.{hh}.>` namespace, plus the federation gate subjects and
       `_INBOX.>` for request/reply. Without permissions, the user can
       authenticate but every publish/subscribe is rejected, which manifests
       as silent federation failure (RelayBridge gets `Authorization
       Violation`). This was the bug behind the long-standing "cross-zone
       tells don't actually go through the relay" symptom.

    2. NON-household users (e.g. peer_trainer for cross-zone peer-training)
       must be PRESERVED across regenerations. We rewrite the authorization
       block from scratch, but read the existing one first to keep any user
       whose name doesn't match the `hh-*` prefix.
    """
    active = {k: v for k, v in regs.items() if v.get("active", True)}

    # keep the tunnel sshd's authorized_keys in lock-step
    # with the same ledger. Independent of the NATS rewrite below (no-op when the
    # feature is disabled); a failure here must never break federation auth.
    try:
        update_ssh_authorized_keys(regs)
    except Exception as _ssh_e:  # noqa: BLE001
        print(f"[ssh-tunnel] authorized_keys rewrite warned: {_ssh_e}")

    # Read existing config first (we need to preserve non-household users).
    conf_path = Path(NATS_CONF)
    if not conf_path.exists():
        raise FileNotFoundError(f"NATS config not found: {NATS_CONF}")
    conf = conf_path.read_text()

    # Extract any non-household user blocks already present (peer_trainer, etc.).
    # We look for `{ user: "<name>"` where <name> doesn't start with `hh-`.
    # Each user-entry block runs from its `{` to the matching `}`.
    # Preserve non-hh-* user blocks across regenerations.
    #
    # Bug fixed 2026-05-12: the previous break condition
    #   `if u_idx > conf.find("}\n", auth_idx) + 200: break`
    # stopped at the *first* `}\n` in the file — which is usually the end of
    # the FIRST user's `permissions` block, not the end of the `authorization`
    # block. So only the first non-hh-* user got preserved; subsequent ones
    # (relay_phone, relay_sidecar) were silently dropped on every regen. The
    # running NATS container masked this because its bind-mount captured the
    # pre-rename inode with the original accounts. As soon as the container
    # restarted, mobile probes broke because `relay_phone` was gone.
    #
    # Correct logic: scope the user scan to the *authorization block*'s
    # brace-matched range, not the first stray `}\n`.
    import re
    preserved_users = []
    # Anchor on the REAL `authorization {` directive (start of line), NOT the
    # first occurrence of the substring "authorization" — which appears inside
    # the header comment ("...only rewrites `authorization {`"). Matching the
    # comment made brace-matching splice a SECOND authorization block into the
    # file; NATS honours the LAST authorization block, so every household user
    # we wrote into the first block silently never authorized (both federation
    # AND phone mcp got "Authorization Violation" against a relay that had
    # supposedly registered them).
    _auth_m = re.search(r'(?m)^[ \t]*authorization[ \t]*\{', conf)
    auth_idx = _auth_m.start() if _auth_m else -1
    if auth_idx != -1:
        # Brace-match from the directive's opening brace.
        auth_open = _auth_m.end() - 1
        if auth_open != -1:
            depth, i = 1, auth_open + 1
            while i < len(conf) and depth > 0:
                if conf[i] == "{":
                    depth += 1
                elif conf[i] == "}":
                    depth -= 1
                i += 1
            auth_close = i  # one past the closing `}`

            # Scan for `user:` patterns within [auth_open, auth_close).
            scan = auth_open
            while scan < auth_close:
                u_idx = conf.find('user:', scan, auth_close)
                if u_idx == -1:
                    break
                # Find the start of this user entry (the `{` before `user:`)
                br = conf.rfind("{", auth_open, u_idx)
                if br == -1:
                    scan = u_idx + 5
                    continue
                # Match braces forward to find the end of this user block
                d, j = 1, br + 1
                while j < auth_close and d > 0:
                    if conf[j] == "{":
                        d += 1
                    elif conf[j] == "}":
                        d -= 1
                    j += 1
                entry = conf[br:j]
                import re
                m = re.search(r'user:\s*"([^"]+)"', entry)
                # hh-* (household) and phone-hh-* (per-household phone) users are
                # REGENERATED from registrations each pass — preserving them too
                # produced "Duplicate user" and a flapping relay NATS.
                if m and not m.group(1).startswith("hh-") \
                        and not m.group(1).startswith("phone-"):
                    preserved_users.append(entry.strip())
                scan = j

    # Always-present infrastructure accounts. If preserve_users somehow lost
    # them (e.g. fresh deploy, manual edit), inject canonical defaults so the
    # relay stays functional across container restarts.
    preserved_names = set()
    for u in preserved_users:
        import re as _re
        m = _re.search(r'user:\s*"([^"]+)"', u)
        if m:
            preserved_names.add(m.group(1))
    # the sidecar password comes from the env the
    # sidecar ITSELF authenticates with (NATS_PASSWORD, set by compose /
    # relay.sh, randomized per-deploy). Hardcoding the canonical default here
    # would silently revert a randomized password on the first conf regen.
    _sidecar_pw = os.environ.get("NATS_PASSWORD", "") or _relay_secret("sidecar")
    _DEFAULTS = {
        "relay_sidecar": '''{ user: "relay_sidecar", password: "%s",
          permissions: {
            publish:   { allow: ["relay.>", "_INBOX.>"] }
            subscribe: { allow: ["relay.>", "_INBOX.>"] }
          }
        }''' % _sidecar_pw,
        # phones connect with this account
        # over `wss://relay:4443`. Restricted: cannot subscribe to between.>
        # or wyrd.zone.> (those are server-side). Phones publish requests
        # under wyrd.zone.{zone}.* and wyrd.discover.zone, and receive
        # replies on _INBOX.> via NATS request/reply pairing.
        # P4 — password comes from the same env seam mint_phone_invite
        # reads (NATS_PHONE_PASSWORD), so a deploy-randomized credential
        # survives conf regen AND lands in phone invites consistently.
        # phones also open a dumb session pipe: publish
        # `wyrd.tunnel.{zone}.{session}.{open,up,close}` and subscribe the
        # matching `.down`. The relay only shuffles bytes on these subjects.
        #
        # phones ALSO run the CRDT Study-sync layer over
        # the relay: they pub/sub `between.{zone}.{src}.{dst}.study.{state,sync}`
        # so the phone's local Study and the home-zone Study converge. Scoped to
        # the `.study.state`/`.study.sync` leaves ONLY — NOT full `between.>`,
        # which would let a phone read every zone's cluster/presence traffic. Each
        # study message is userDid-scoped (peers ignore other users'), so this
        # narrow grant is safe; per-zone tightening is a future hardening.
        # DEPRECATED: kept only so invites minted before per-household phone
        # accounts keep connecting. NO study grants here — study sync requires
        # the per-household account (a shared credential would let any phone
        # read/write any user's Study). Old invites: re-mint to get study sync.
        "relay_phone": '''{ user: "relay_phone", password: "%s",
          permissions: {
            publish:   { allow: ["wyrd.zone.>", "wyrd.discover.>", "wyrd.tunnel.>", "_INBOX.>"] }
            subscribe: { allow: ["wyrd.tunnel.>", "_INBOX.>"] }
          }
        }''' % _phone_password(),
        # bootstrap account for join nodes.
        # Used by `wyrd relay register-nkey` CLI to migrate off HTTPS:443
        # (eliminates the Caddy/operator-website port collision). Locked
        # down to only the register subjects + reply inbox.
        "relay_join": '''{ user: "relay_join", password: "%s",
          permissions: {
            publish:   { allow: ["relay.register", "relay.re-register", "relay.peer.list", "relay.status", "_INBOX.>"] }
            subscribe: { allow: ["_INBOX.>"] }
          }
        }''' % _join_password(),
    }
    # The infrastructure accounts in _DEFAULTS are AUTHORITATIVE: their
    # permission set comes from here, never from whatever relay.conf is sitting
    # in the data volume. A relay.conf seeded before an internal design note has a
    # grant-less relay_phone (no `wyrd.tunnel.>`); merely back-filling on absence
    # (the old `if name not in preserved_names`) preserved that stale grant
    # forever — every redeploy kept the broken account, so CLI/TUI/phone tunnels
    # stayed dead even after the source fix. Re-emitting is safe because the
    # PASSWORD comes from the same env seam (_phone_password()/NATS_PASSWORD) the
    # phone invite reads, so it can't diverge from what clients hold. Non-default
    # accounts (peer_trainer, registered hh-* households) are untouched.
    for name, block in _DEFAULTS.items():
        if name in preserved_names:
            # Drop the volume's (possibly stale) copy so the authoritative
            # block below wins. Match the exact `user: "<name>"` token.
            preserved_users = [
                u for u in preserved_users
                if not re.search(r'user:\s*"' + re.escape(name) + r'"', u)
            ]
        preserved_users.append(block)
        preserved_names.add(name)

    # Build household user entries with scoped permissions.
    #
    # Two entry shapes coexist during the migration:
    # 1. Password-mode (legacy): `{ user: "hh-XXX", password: "<token>", permissions }`
    # 2. NKey-mode: `{ nkey: "U...", permissions }` — NATS extracts the user
    #    identity from the pubkey itself, no separate `user:` field needed.
    #
    # Phase 1 = both supported. Phase 4 = password-mode dropped.
    # Subject permissions for NKey-mode are tightened per-pubkey when
    # household_tag/zone_id are known (closes WYRDSEKAI_RELAY_ADD §5.4 gap);
    # password-mode keeps the legacy `between.>` blanket allow because we
    # don't have those tags retrofitted.
    household_blocks = []
    _phone_users_emitted: set = set()
    for hid, info in active.items():
        if info.get("kind") == "nkey":
            # NKey-mode entry. `hid` IS the pubkey here (we keyed regs.json by it).
            perms = _subject_permissions_for(info.get("household_tag"), info.get("zone_id"))
            pub_list = ", ".join(f'"{s}"' for s in perms["publish"])
            sub_list = ", ".join(f'"{s}"' for s in perms["subscribe"])
            block = f'''{{ nkey: "{hid}",
          permissions: {{
            publish:   {{ allow: [{pub_list}] }}
            subscribe: {{ allow: [{sub_list}] }}
          }}
        }}'''
        elif "token" not in info:
            # SSH-tunnel-only registrations (kind=="zone")
            # carry no NATS account: they hold a `did`/`ssh_tunnel` record used
            # to regenerate the tunnel sshd's authorized_keys, not a relay login.
            # They have no `token`, so they must NOT produce a NATS user block
            # (dereferencing info["token"] here raised KeyError('token') and broke
            # the whole NATS-config regen — and thus every fresh NKey registration).
            continue
        else:
            # Legacy password-mode entry.
            # Includes wyrd.zone.> for the MCP
            # NATS surface — the zone's Java server uses this account to
            # subscribe to phone-side `wyrd.zone.{zoneId}.{login,tell,...}`.
            # wyrd.discover.> for zone discovery ( — phones
            # use this to learn the zone label before scoping any wyrd.zone.>
            # subject. "home" is reserved.)
            # wyrd.tunnel.> for the universal session door: the
            # CLI/TUI (`wyrd connect --relay`) and phones open
            # `wyrd.tunnel.{zone}.{session}.{open,up,close}` and read `.down`; the
            # zone bridges those raw frames into its own /ws. A layman `wyrd relay
            # join` registers the zone as a password-mode household, so WITHOUT
            # this grant a pw-joined zone is reachable by SSH-over-relay (separate
            # sshd) but NOT by the CLI/TUI/phone NATS tunnel. Mirror the NKey path
            # (_subject_permissions_for) so both registration modes are equal.
            # Pre-OSS hardening (audit F5): password-mode households used to get a
            # blanket between.>/federation.> PUBLISH — cross-zone request forgery
            # through the relay. Scope them exactly like the NKey path; the helper
            # itself falls back to broad only when the zone label is unknown.
            _pw_perms = _subject_permissions_for(info.get("household_tag"), info.get("zone_id"))
            _pw_pub = ", ".join(f'"{sub}"' for sub in _pw_perms["publish"])
            _pw_sub = ", ".join(f'"{sub}"' for sub in _pw_perms["subscribe"])
            block = f'''{{ user: "{hid}", password: "{info["token"]}",
          permissions: {{
            publish:   {{ allow: [{_pw_pub}] }}
            subscribe: {{ allow: [{_pw_sub}] }}
          }}
        }}'''
        household_blocks.append(block)

        # Per-household PHONE account ( hardening): each
        # household's phones authenticate with their own NATS user, scoped to
        # their own zone's tunnel + study subjects. Replaces the shared
        # relay_phone for all NEW invites (see mint_phone_invite).
        # Password-mode registrations are KEYED by the household id (hh-…) and
        # may carry no household_tag field — the key IS the tag there.
        tag = info.get("household_tag") \
            or (hid if isinstance(hid, str) and hid.startswith("hh-") else None)
        if tag and tag != "unspecified":
            pu = _phone_user_for(tag)
            if pu not in _phone_users_emitted:
                _phone_users_emitted.add(pu)
                _ph = _phone_perms_for(info.get("zone_id"))
                _ph_pub = ", ".join(f'"{sub}"' for sub in _ph["publish"])
                _ph_sub = ", ".join(f'"{sub}"' for sub in _ph["subscribe"])
                household_blocks.append(f'''{{ user: "{pu}", password: "{_phone_password_for(tag)}",
          permissions: {{
            publish:   {{ allow: [{_ph_pub}] }}
            subscribe: {{ allow: [{_ph_sub}] }}
          }}
        }}''')

    all_users = preserved_users + household_blocks
    users_block = ",\n        ".join(all_users)

    new_auth = f"""authorization {{
    users = [
        {users_block}
    ]
}}"""

    # Replace the existing authorization block with brace-matching. Anchor on
    # the real `^authorization {` directive, not the word inside the header
    # comment (the duplicate-block / households-never-auth bug above).
    _start_m = re.search(r'(?m)^[ \t]*authorization[ \t]*\{', conf)
    start = _start_m.start() if _start_m else -1
    brace_open = (_start_m.end() - 1) if _start_m else -1
    if start != -1 and brace_open != -1:
        depth = 1
        i = brace_open + 1
        while i < len(conf) and depth > 0:
            c = conf[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            i += 1
        if depth == 0:
            conf = conf[:start] + new_auth + conf[i:]
        else:
            conf += "\n" + new_auth + "\n"
    else:
        conf += "\n" + new_auth + "\n"

    conf_path.write_text(conf)

    # Signal NATS to reload
    subprocess.run(NATS_SIGNAL.split(), check=True, timeout=5)


def _record_announce_signed(body: dict) -> dict:
    """Sign + persist an /announce payload.

    Used by both HTTP `POST /announce` and NATS `relay.announce`.
    Returns the response shape both paths emit.
    """
    announced_at = datetime.utcnow().isoformat()
    try:
        fingerprint = _leaf_fingerprint()
    except FileNotFoundError:
        fingerprint = ""
    canonical = "|".join([
        str(body.get("url") or ""),
        str(body.get("region", "unknown")),
        str(body.get("capacity", 500)),
        str(body.get("registered", 0)),
        str(bool(body.get("public", True))).lower(),
        fingerprint,
        announced_at,
    ]).encode("utf-8")
    key = _load_or_init_invite_key()
    signature = hmac.new(key, canonical, hashlib.sha256).hexdigest()
    peers = load_peers()
    peers = [p for p in peers if p.get("url") != body.get("url")]
    peers.append({
        "url": body.get("url"),
        "region": body.get("region", "unknown"),
        "capacity": body.get("capacity", 500),
        "registered": body.get("registered", 0),
        "public": body.get("public", True),
        "relay_fingerprint": fingerprint,
        "announced_at": announced_at,
        "signature": signature,
    })
    save_peers(peers)
    return {
        "status": "announced",
        "relay_fingerprint": fingerprint,
        "signature": signature,
    }


def get_status() -> dict:
    regs = load_registrations()
    active = {k: v for k, v in regs.items() if v.get("active", True)}
    return {
        "capacity": CAPACITY,
        "registered": len(active),
        "available": CAPACITY - len(active),
        "region": REGION,
        "public": PUBLIC,
        "utilization_percent": round(len(active) * 100 / CAPACITY, 1) if CAPACITY > 0 else 100,
        # The relay's own DID, when known — a signed /admin caller binds it into
        # the challenge (admin:{op}:{ts}:{relay_did}:…). Empty until the relay
        # records one (e.g. via claim-owner); for owner / open-self-serve ops it
        # is signature-bound but not validated, so "" is acceptable.
        "relay_did": load_owner().get("relay_did", "") or "",
        # Whether this relay runs the SSH reverse tunnel + its opt-in policy and
        # topology, so a zone can decide whether `ssh-enable` will be honoured.
        "ssh_tunnel_enabled": SSH_TUNNEL_ENABLED,
        "ssh_tunnel_mode": ssh_tunnel_mode() if SSH_TUNNEL_ENABLED else "off",
        "ssh_tunnel_topology": ssh_tunnel_topology() if SSH_TUNNEL_ENABLED else "port",
    }


# --- Owner bootstrap + relay-admin grants (b / §5 / §6) ---
#
# Administration ≠ registration. The relay learns an `owner_did` (the root of
# its grant chain) and keeps a LOCAL relay-admin grant store — a self-contained
# enforcement copy. The Java Grant (P2) is the zone-side ISSUING + visibility
# authority; in-world issuance (P4) pushes a signed `grant-admin` here. The
# relay never calls back into a zone.
#
# Canonical signing strings (the bytes a caller signs with its NodeIdentity
# Ed25519 key, recovered relay-side via did_to_ed25519_pubkey):
#   owner-claim:   "claim-owner:{ts}:{did}"
#   signed /admin: "admin:{op}:{ts}:{relay_did}:{sha256_hex(canonical_args)}"
# where canonical_args = compact JSON (sorted keys) of the op's `args`
# (see _canonical_args). Anti-replay: |now - ts| <= ADMIN_MAX_SKEW + nonce GC.

ADMIN_MAX_SKEW_SECONDS = int(os.environ.get("ADMIN_MAX_SKEW_SECONDS", "300"))

# op -> required RelayAdminScope (MUST mirror Java RelayAdminOp). Scopes form
# a containment hierarchy: full ⊇ moderation ⊇ invite-only.
_SCOPE_RANK = {"invite-only": 1, "moderation": 2, "full": 3}
_OP_REQUIRED_SCOPE = {
    "invite": "invite-only",
    "list": "moderation",
    "remove": "moderation",
    "promote": "moderation",
    "demote": "moderation",
    "vouch": "moderation",
    "report-queue": "moderation",
    "resolve-report": "moderation",
    # `report` (FILE a report) is INTENTIONALLY exempt from the moderation-scope
    # gate (see _OPEN_TO_ANY_SIGNER below). It is mapped to "moderation" here
    # ONLY so the op is recognized + so the value mirrors Java RelayAdminOp.REPORT
    # (whose enum scope stays MODERATION for a stable op→scope vocabulary). The
    # authorize path special-cases it: filing is open to ANY valid signer (a user
    # reporting a node), like `deregister` — a signature proves identity, and no
    # relay-admin grant is required. Viewing (`report-queue`) and acting on
    # (`resolve-report`) reports remain moderator-only.
    "report": "moderation",
    "set-mode": "full",
    "set-policy": "full",
    # enable/disable a zone's SSH reverse tunnel. In
    # `grant` ssh-tunnel mode this needs a moderation grant / owner; in `open`
    # mode a zone may self-serve its OWN tunnel (a valid signature over its own
    # registration is the whole bar — see the self-serve bypass in authorize).
    "ssh-enable": "moderation",
    "ssh-disable": "moderation",
    "grant-admin": "full",
    "revoke-admin": "full",
    "set-owner": "full",
    "audit": "full",
}


# ops that any VALID SIGNER may invoke, bypassing the
# relay-admin grant/scope gate. Filing an abuse report (`report`) is open to any
# registered DID — a user reporting a node — exactly like `deregister`: the
# Ed25519 signature proves the caller controls the DID, and that is the whole
# bar (no grant needed). Anti-abuse is the per-op rate-limit + a per-(reporter,
# subject) open-report cap (see file_report), NOT a scope check. Viewing
# (`report-queue`) and resolving (`resolve-report`) reports stay moderator-only.
_OPEN_TO_ANY_SIGNER = frozenset({"report"})


def _scope_covers(held: str, required: str) -> bool:
    """True iff a held scope is at least as broad as the required scope.
    Mirrors Java RelayAdminScope.covers (rank comparison)."""
    return _SCOPE_RANK.get(held, 0) >= _SCOPE_RANK.get(required, 99)


def load_owner() -> dict:
    if OWNER_FILE.exists():
        try:
            return json.loads(OWNER_FILE.read_text())
        except json.JSONDecodeError:
            return {}
    return {}


def owner_did() -> str | None:
    """The relay's current owner DID, or None if unclaimed. A deploy-time
    RELAY_OWNER_DID (relay.sh --owner) seeds the store on first read so the
    --owner shortcut works with no claim step."""
    o = load_owner()
    did = o.get("owner_did")
    if did:
        return did
    if RELAY_OWNER_DID:
        _set_owner(RELAY_OWNER_DID, via="env")
        return RELAY_OWNER_DID
    return None


def _set_owner(did: str, via: str = "claim") -> None:
    with lock:
        o = load_owner()
        o["owner_did"] = did
        o["set_at"] = datetime.utcnow().isoformat()
        o["via"] = via
        OWNER_FILE.write_text(json.dumps(o, indent=2))


def load_admin_grants() -> dict:
    if RELAY_ADMIN_GRANTS_FILE.exists():
        try:
            return json.loads(RELAY_ADMIN_GRANTS_FILE.read_text())
        except json.JSONDecodeError:
            return {}
    return {}


def save_admin_grants(grants: dict) -> None:
    # Atomic-ish write (write + replace) so a crash mid-write can't truncate
    # the store.
    tmp = RELAY_ADMIN_GRANTS_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(grants, indent=2, default=str))
    tmp.replace(RELAY_ADMIN_GRANTS_FILE)


def held_scope(did: str, relay_did: str | None, now: int | None = None) -> str | None:
    """Return the (unexpired) scope a DID holds on this relay, or None.

    Honors expiry (`expiresAt`, epoch seconds; 0/absent = no expiry) and an
    optional per-grant relay narrowing (`relay`): a grant scoped to a specific
    relay DID only applies when that DID matches `relay_did`."""
    now = int(time.time()) if now is None else now
    g = load_admin_grants().get(did)
    if not g:
        return None
    exp = g.get("expiresAt") or 0
    try:
        exp = int(exp)
    except (TypeError, ValueError):
        exp = 0
    if exp and now > exp:
        return None
    scoped_relay = g.get("relay")
    if scoped_relay and relay_did and scoped_relay != relay_did:
        return None
    return g.get("scope")


def mint_owner_claim_token(ttl_seconds: int) -> dict:
    """b — mint a one-time, TTL'd, fingerprint-pinned
    owner-claim token. Mirrors mint_invite's HMAC-signed envelope but binds
    OWNERSHIP, not membership: redeeming it (via /claim-owner) records the
    redeemer's DID as owner_did. Single-use (nonce-tracked at redeem)."""
    ttl = max(60, min(int(ttl_seconds), INVITE_MAX_TTL))
    fingerprint = _leaf_fingerprint()
    payload = {
        "scope": "owner-claim",
        "fp": fingerprint,
        "exp": int(time.time()) + ttl,
        "n": _b64url(secrets.token_bytes(16)),
    }
    payload_bytes = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    sig = hmac.new(_load_or_init_invite_key(), payload_bytes, hashlib.sha256).digest()
    token = _b64url(payload_bytes) + "." + _b64url(sig)
    host = RELAY_PUBLIC_HOST or "localhost"
    return {
        "claim_token": token,
        "scope": "owner-claim",
        "fingerprint": fingerprint,
        "expires_at": payload["exp"],
        "host": host,
        "port": RELAY_PUBLIC_PORT,
    }


def _verify_owner_claim_token(token: str) -> dict:
    """Verify an owner-claim token (HMAC + scope + expiry + single-use nonce).
    Returns the payload or raises ValueError. Shares the nonce ledger with
    invites, so a redeemed token can't be replayed."""
    if not token or "." not in token:
        raise ValueError("Malformed token")
    payload_b64, sig_b64 = token.split(".", 1)
    try:
        payload_bytes = _b64url_decode(payload_b64)
        provided_sig = _b64url_decode(sig_b64)
    except Exception as e:
        raise ValueError(f"Decode failed: {e}")
    expected = hmac.new(_load_or_init_invite_key(), payload_bytes, hashlib.sha256).digest()
    if not hmac.compare_digest(provided_sig, expected):
        raise ValueError("Bad signature")
    payload = json.loads(payload_bytes)
    if payload.get("scope") != "owner-claim":
        raise ValueError(f"Wrong scope: {payload.get('scope')} (expected 'owner-claim')")
    if payload.get("exp", 0) < int(time.time()):
        raise ValueError("Token expired")
    if not _consume_nonce(payload.get("n", ""), payload["exp"]):
        raise ValueError("Token already used")
    return payload


def claim_owner(token: str, did: str, ts, signature_b64: str,
                max_skew_seconds: int = None) -> dict:
    """b — redeem an owner-claim token.

    1. The claim token must be valid + unconsumed (HMAC, scope, TTL, nonce).
    2. The Ed25519 signature over `claim-owner:{ts}:{did}` must verify against
       the key behind `did` (proves the caller holds that DID's key).
    3. ts within ±skew (anti-replay).
    On success records owner_did=did and consumes the token (single-use).

    Returns a dict carrying `_status` (HTTP-equivalent)."""
    max_skew = ADMIN_MAX_SKEW_SECONDS if max_skew_seconds is None else max_skew_seconds
    if not did or not did.startswith("did:key:z"):
        return {"error": "did (did:key:…) required", "_status": 400}
    if signature_b64 is None or ts is None:
        return {"error": "ts and signature are required", "_status": 400}
    now = int(time.time())
    try:
        ts_int = int(ts)
    except (ValueError, TypeError):
        return {"error": "ts must be an integer (epoch seconds)", "_status": 400}
    if abs(now - ts_int) > max_skew:
        return {"error": f"timestamp skew too large ({abs(now - ts_int)}s)", "_status": 401}
    # Verify the DID signature BEFORE consuming the token, so a bad-sig attempt
    # doesn't burn a valid token.
    challenge = f"claim-owner:{ts_int}:{did}".encode("utf-8")
    sig_err = _verify_did_sig(did, challenge, signature_b64)
    if sig_err:
        return {"error": sig_err, "_status": 401}
    # Token check (consumes the nonce on success — single-use).
    try:
        _verify_owner_claim_token(token)
    except ValueError as e:
        return {"error": f"claim token rejected: {e}", "_status": 401}
    _set_owner(did, via="claim-token")
    print(f"[claim-owner] owner_did set to {did[:24]}… via claim token")
    return {"status": "owner_set", "owner_did": did, "_status": 200}


def admin_op(op: str, args, relay_did: str, ts, did: str, signature_b64: str,
             max_skew_seconds: int = None) -> dict:
    """the signed /admin/* surface.

    Verifies the Ed25519 signature over
        admin:{op}:{ts}:{relay_did}:{sha256_hex(canonical_args)}
    against the key behind `did`, enforces ±skew + single-use nonce, then
    authorizes: allow iff did == owner_did (any op) OR held_scope(did,
    relay_did) covers the op's required scope. Dispatches the op.

    Returns a dict carrying `_status` (HTTP-equivalent)."""
    max_skew = ADMIN_MAX_SKEW_SECONDS if max_skew_seconds is None else max_skew_seconds
    op = (op or "").strip().lower().replace("_", "-")
    if op not in _OP_REQUIRED_SCOPE:
        return {"error": f"unknown admin op: {op}", "_status": 400}
    if not did or not did.startswith("did:key:z"):
        return {"error": "did (did:key:…) required", "_status": 400}
    if signature_b64 is None or ts is None or relay_did is None:
        return {"error": "ts, relay_did and signature are required", "_status": 400}
    now = int(time.time())
    try:
        ts_int = int(ts)
    except (ValueError, TypeError):
        return {"error": "ts must be an integer (epoch seconds)", "_status": 400}
    if abs(now - ts_int) > max_skew:
        return {"error": f"timestamp skew too large ({abs(now - ts_int)}s)", "_status": 401}

    args_canon = _canonical_args(args)
    args_hash = hashlib.sha256(args_canon.encode("utf-8")).hexdigest()
    challenge = f"admin:{op}:{ts_int}:{relay_did}:{args_hash}".encode("utf-8")
    sig_err = _verify_did_sig(did, challenge, signature_b64)
    if sig_err:
        return {"error": sig_err, "_status": 401}
    # Anti-replay: a distinct nonce ledger entry keyed on the full signed
    # challenge. (No explicit nonce field needed — the challenge is unique per
    # ts/op/args; reusing it is a replay.)
    if not _consume_nonce("admin:" + args_hash + ":" + str(ts_int) + ":" + op,
                          ts_int + max_skew + 1):
        return {"error": "replayed admin request (nonce already seen)", "_status": 401}

    # Authorize. The signature is already verified above (the caller controls
    # `did`). For OPEN-to-any-signer ops (§8: filing a `report`), that is the
    # whole bar — skip the relay-admin grant/scope gate. Every other op requires
    # owner OR a grant covering the op's scope.
    required = _OP_REQUIRED_SCOPE[op]
    owner = owner_did()
    is_owner = (owner is not None and did == owner)
    # in `open` ssh-tunnel mode a zone may self-serve its
    # OWN tunnel: a valid signature over a registration whose DID is the caller's
    # is the whole bar (like `report`/`deregister`). `grant` mode falls through to
    # the owner/scope gate below; a zone can NEVER flip another zone's tunnel.
    ssh_self_serve = False
    if op in ("ssh-enable", "ssh-disable") and ssh_tunnel_mode() == "open":
        tgt = (args or {}).get("pubkey")
        tgt_rec = (load_registrations().get(tgt) or {}) if tgt else {}
        tgt_did = tgt_rec.get("did")
        # Self-serve when the existing tunnel/zone record is the caller's, OR no
        # record exists yet for this pubkey (first-time enable from a zone that
        # registered via password-mode `wyrd relay join` — it has an hh-* NATS
        # household but no registration keyed by its NKey pubkey; ssh-enable
        # creates a tunnel-only kind:"zone" record with did=caller below).
        # `open` mode is an explicit operator opt-in and the tunnel only reaches
        # the zone's own invite-gated MUD, so first-time self-claim is in-scope.
        ssh_self_serve = bool(tgt) and (tgt_did == did or not tgt_rec)
    if op not in _OPEN_TO_ANY_SIGNER and not is_owner and not ssh_self_serve:
        scope = held_scope(did, relay_did, now)
        if scope is None:
            return {"error": "no relay-admin grant for this DID", "_status": 403}
        if not _scope_covers(scope, required):
            return {"error": f"scope '{scope}' does not cover op '{op}' "
                             f"(needs '{required}')", "_status": 403}

    args = args or {}
    return _dispatch_admin(op, args, relay_did, did, is_owner, now)


def _dispatch_admin(op: str, args: dict, relay_did: str, caller_did: str,
                    is_owner: bool, now: int) -> dict:
    """Run an authorized admin op. Wires invite/list/remove to the EXISTING
    sidecar logic; grant-admin/revoke-admin/set-owner mutate the local stores;
    set-mode/set-policy are P5 stubs (persist a value, clear TODO)."""
    if op == "invite":
        ttl = int(args.get("ttl", INVITE_DEFAULT_TTL))
        try:
            result = mint_invite(ttl)
        except FileNotFoundError as e:
            return {"error": str(e), "_status": 503}
        result["join_code"] = _mint_join_code(result)
        result["_status"] = 200
        return result

    if op == "list":
        regs = load_registrations()
        out = []
        for pubkey, e in regs.items():
            out.append({
                "pubkey": pubkey,
                "did": e.get("did"),
                "household_tag": e.get("household_tag"),
                "zone_id": e.get("zone_id"),
                "kind": e.get("kind"),
                # surface tier + identity flag.
                "tier": e.get("tier", TIER_HOUSEHOLD),
                "identity_verified": bool(e.get("identity_verified")),
                "active": e.get("active", True),
                "last_seen": e.get("last_seen"),
                # surface a live connection-ceiling
                # overage if the reaper flagged one (operator's cue to investigate
                # / relay.sh remove). Absent when within budget.
                "over_connection_limit": e.get("over_connection_limit"),
            })
        return {"registrations": out, "mode": relay_mode(),
                "quotas": {t: tier_quota(t) for t in ("floor", "vouched", "household")},
                "_status": 200}

    if op == "remove":
        target = args.get("pubkey")
        if not target:
            return {"error": "remove needs args.pubkey", "_status": 400}
        with lock:
            regs = load_registrations()
            if target not in regs:
                return {"status": "already_absent", "pubkey": target, "_status": 200}
            del regs[target]
            save_registrations(regs)
            try:
                update_nats_config(regs)
            except Exception as e:
                return {"status": "removed", "pubkey": target,
                        "warn": f"NATS config rewrite warned: {e}", "_status": 200}
        print(f"[admin/remove] {target[:12]}… removed by {caller_did[:16]}…")
        return {"status": "removed", "pubkey": target, "_status": 200}

    if op == "grant-admin":
        subject = args.get("subject_did")
        scope = (args.get("scope") or "").strip().lower()
        if not subject or not subject.startswith("did:key:z"):
            return {"error": "grant-admin needs args.subject_did (did:key:…)", "_status": 400}
        if scope not in _SCOPE_RANK:
            return {"error": f"invalid scope '{scope}' "
                             "(invite-only|moderation|full)", "_status": 400}
        # Delegation rule: the OWNER may grant any scope. A non-owner caller
        # reaches this op only if it already passed the full-scope authorize
        # gate above (grant-admin requires 'full'); it may grant at scopes ≤
        # its own held scope — and a full holder covers every scope, so in
        # practice a delegate with 'full' may issue invite-only/moderation/full.
        # This is consistent with P2's cascade intent (a delegate never issues
        # broader than it holds).
        if not is_owner:
            caller_scope = held_scope(caller_did, relay_did, now)
            if caller_scope is None or not _scope_covers(caller_scope, scope):
                return {"error": "delegate may not grant a scope broader than its own",
                        "_status": 403}
        with lock:
            grants = load_admin_grants()
            grants[subject] = {
                "scope": scope,
                "relay": args.get("relay"),
                "expiresAt": args.get("expiresAt"),
                "granted_by": caller_did,
                "granted_at": datetime.utcnow().isoformat(),
            }
            save_admin_grants(grants)
        print(f"[admin/grant-admin] {subject[:16]}… granted '{scope}' by {caller_did[:16]}…")
        return {"status": "granted", "subject_did": subject, "scope": scope, "_status": 200}

    if op == "revoke-admin":
        subject = args.get("subject_did")
        if not subject:
            return {"error": "revoke-admin needs args.subject_did", "_status": 400}
        with lock:
            grants = load_admin_grants()
            existed = subject in grants
            grants.pop(subject, None)
            save_admin_grants(grants)
        print(f"[admin/revoke-admin] {subject[:16]}… revoked by {caller_did[:16]}…")
        return {"status": "revoked" if existed else "already_absent",
                "subject_did": subject, "_status": 200}

    if op == "set-owner":
        # Owner transfer — owner-only (the authorize gate let non-owner-full
        # delegates through on 'full', so re-check ownership here explicitly).
        if not is_owner:
            return {"error": "set-owner is owner-only", "_status": 403}
        new_owner = args.get("owner_did") or args.get("did")
        if not new_owner or not new_owner.startswith("did:key:z"):
            return {"error": "set-owner needs args.owner_did (did:key:…)", "_status": 400}
        _set_owner(new_owner, via="set-owner")
        print(f"[admin/set-owner] owner transferred to {new_owner[:24]}…")
        return {"status": "owner_set", "owner_did": new_owner, "_status": 200}

    if op == "audit":
        return {
            "owner_did": owner_did(),
            "grants": load_admin_grants(),
            "registrations": len(load_registrations()),
            # governance state.
            "mode": relay_mode(),
            "policy": load_policy(),
            "vouches": load_vouches(),
            "_status": 200,
        }

    # flip the live registration mode. The gate
    # (full scope, already authorized above) is enforced; the new mode is
    # persisted in relay-policy.json and takes effect immediately for the next
    # register-nkey (relay_mode() reads the file).
    if op == "set-mode":
        try:
            mode = _set_mode(args.get("mode"))
        except ValueError as e:
            return {"error": str(e), "_status": 400}
        print(f"[admin/set-mode] mode set to '{mode}' by {caller_did[:16]}…")
        return {"status": "mode_set", "mode": mode, "_status": 200}

    # enable/disable a zone's SSH reverse tunnel. Auth is
    # already settled (owner / moderation grant in `grant` mode, or the zone's own
    # signature in `open` mode — see the self-serve bypass in authorize). Flips the
    # zone's `ssh_tunnel` sub-record, regenerates the tunnel sshd's authorized_keys,
    # and returns the topology-appropriate connect info.
    if op in ("ssh-enable", "ssh-disable"):
        if ssh_tunnel_mode() == "off" or not SSH_TUNNEL_ENABLED:
            return {"error": "ssh tunnel is disabled on this relay "
                             "(ssh_tunnel_mode=off)", "_status": 409}
        target = args.get("pubkey")
        if not target:
            return {"error": f"{op} needs args.pubkey", "_status": 400}
        with lock:
            regs = load_registrations()
            rec = regs.get(target)
            if rec is None:
                # first-time ssh-enable for a zone that
                # registered via password-mode `wyrd relay join`: it has an hh-*
                # NATS household but NO registration keyed by its NKey pubkey.
                # Create a tunnel-only kind:"zone" record to carry the ssh_tunnel
                # + authorized_keys line. It holds NO NATS account
                # (update_nats_config skips token-less zone entries) and the
                # caller was already authorised above (owner or open-mode self).
                if op == "ssh-disable":
                    return {"error": "no such registration", "_status": 404}
                rec = {"kind": "zone", "did": caller_did, "active": True,
                       "registered_at": datetime.utcnow().isoformat()}
                regs[target] = rec
            if op == "ssh-disable":
                if rec.get("ssh_tunnel"):
                    rec["ssh_tunnel"]["enabled"] = False
                save_registrations(regs)
                try:
                    update_ssh_authorized_keys(regs)
                except Exception as e:  # noqa: BLE001
                    print(f"[admin/ssh-disable] authkeys rewrite warned: {e}")
                return {"status": "ssh_disabled", "pubkey": target, "_status": 200}
            # ssh-enable
            pub = _sanitize_ssh_pubkey(args.get("ssh_pubkey", ""))
            if not pub:
                return {"error": "valid ssh_pubkey (ssh-ed25519 <base64> [comment]) "
                                 "required", "_status": 400}
            port = _assign_ssh_tunnel_port(regs, target)
            if port is None:
                return {"error": "ssh tunnel port range exhausted",
                        "range": [SSH_TUNNEL_PORT_BASE,
                                  SSH_TUNNEL_PORT_BASE + SSH_TUNNEL_PORT_COUNT],
                        "_status": 503}
            existing = rec.get("ssh_tunnel") or {}
            rec["ssh_tunnel"] = {
                "enabled": True, "pubkey": pub, "assigned_port": port,
                "enabled_at": existing.get("enabled_at") or datetime.utcnow().isoformat(),
                "enabled_by": caller_did,
            }
            save_registrations(regs)
            try:
                update_ssh_authorized_keys(regs)
            except Exception as e:  # noqa: BLE001
                print(f"[admin/ssh-enable] authkeys rewrite warned: {e}")
        topo = ssh_tunnel_topology()
        resp = {"status": "ssh_enabled", "pubkey": target, "topology": topo,
                "assigned_port": port, "relay_ssh_port": SSH_TUNNEL_CTRL_PORT,
                "relay_host": os.environ.get("RELAY_PUBLIC_HOST", "") or "",
                "_status": 200}
        fp = _ssh_host_fingerprint()
        if fp:
            resp["tunnel_host_fingerprint"] = fp
        # In `jump` topology the connecting human reaches the zone via ProxyJump
        # through the shared forward-only jump principal. That principal authenticates
        # the SYSTEM user `wyrd-tunnel` with the relay-held jump key, so the zone owner
        # needs the PRIVATE half to put behind `IdentityFile` in the emitted ssh_config
        # stanza. It is low-value by construction (forward-only, `permitopen`-pinned to
        # the active zone loopback ports — it can only TCP-reach invite-gated zone MUDs,
        # never listen or open a shell), so handing it back over the TLS-pinned /admin
        # channel is within the security model. Absent → the stanza can't be self-contained.
        if topo == "jump":
            try:
                if SSH_JUMP_KEY.exists():
                    resp["jump_private_key"] = SSH_JUMP_KEY.read_text()
            except OSError:
                pass
        return resp

    # set per-tier policy (quotas/limits) into
    # relay-policy.json. ENFORCED now:
    #   - reaper-window-by-tier (§3): FLOOR reaped fast, VOUCHED/HOUSEHOLD slow.
    #   - max_registrations (§5/§8): HARD cap at the register-nkey gate.
    #   - max_connections (§5/§8): per-DID ceiling, detection-grade — the reaper
    #     stamps `over_connection_limit` for the operator (the sidecar can't sever
    #     a single NATS connection without dropping the record from auth).
    # NOT enforced here: NATS-native bandwidth throttling (max_data/max_subs) is
    # account-scoped and needs a per-tier-account split with federation-subject
    # exports/imports — a restructure pending a live two-zone federation soak.
    if op == "set-policy":
        with lock:
            p = load_policy()
            for tier_key in ("floor", "vouched", "household"):
                if tier_key in args:
                    p.setdefault("tiers", {})[tier_key] = args[tier_key]
            # Allow a flat policy blob too (forward-compatible).
            if "tiers" in args and isinstance(args["tiers"], dict):
                p.setdefault("tiers", {}).update(args["tiers"])
            # the ssh-tunnel opt-in mode + exposure topology
            # ride the same policy doc. Validate before persisting.
            if "ssh_tunnel_mode" in args:
                m = str(args["ssh_tunnel_mode"]).strip().lower()
                if m not in _VALID_SSH_MODES:
                    return {"error": f"invalid ssh_tunnel_mode '{m}'", "_status": 400}
                p["ssh_tunnel_mode"] = m
            if "ssh_tunnel_topology" in args:
                t = str(args["ssh_tunnel_topology"]).strip().lower()
                if t not in _VALID_SSH_TOPOLOGIES:
                    return {"error": f"invalid ssh_tunnel_topology '{t}'", "_status": 400}
                p["ssh_tunnel_topology"] = t
            p["updated_at"] = datetime.utcnow().isoformat()
            save_policy(p)
        # Echo the EFFECTIVE quota per tier (defaults overlaid by the new policy)
        # so the caller sees exactly what's enforced.
        effective = {t: tier_quota(t) for t in ("floor", "vouched", "household")}
        return {"status": "policy_set", "policy": load_policy(),
                "effective_quotas": effective,
                "note": "max_registrations enforced at the gate; max_connections "
                        "is detection-grade (over_connection_limit surfaced in "
                        "list); bandwidth throttle pending NATS-account split.",
                "_status": 200}

    # relay-LOCAL Web of Trust. A caller (already
    # authorized at moderation scope, OR the owner) records a vouch for a
    # subject DID. Only a VOUCHED+ voucher (or owner/delegate) counts toward
    # promotion. The deep Java WoT-graph (#150-151) integration is a documented
    # P6/follow-up — this is the relay-local enforcement copy.
    if op == "vouch":
        subject = args.get("subject_did")
        if not subject or not str(subject).startswith("did:key:z"):
            return {"error": "vouch needs args.subject_did (did:key:…)", "_status": 400}
        # The VOUCHER is the caller. Eligibility: owner always; otherwise the
        # caller's own tier must be ≥ VOUCHED (a FLOOR newcomer can't vouch).
        if not is_owner and _tier_rank(did_tier(caller_did)) < _TIER_RANK[TIER_VOUCHED]:
            return {"error": "voucher must be tier ≥ VOUCHED (or owner/delegate)",
                    "_status": 403}
        with lock:
            vouches = load_vouches()
            lst = vouches.setdefault(subject, [])
            if caller_did not in lst:
                lst.append(caller_did)
            save_vouches(vouches)
        promoted = _maybe_auto_promote(subject)
        print(f"[admin/vouch] {caller_did[:16]}… vouched {subject[:16]}…"
              + (" → auto-promoted to VOUCHED" if promoted else ""))
        return {"status": "vouched", "subject_did": subject,
                "vouch_count": len(load_vouches().get(subject, [])),
                # the WoT score that drives promotion
                # (tier-weighted, not a flat count) + the bar it must clear.
                "wot_score": round(wot_promotion_score(subject), 3),
                "wot_threshold": WOT_PROMOTE_THRESHOLD,
                "auto_promoted": promoted, "_status": 200}

    # operator/delegate direct promote. Raises a
    # subject's tier (FLOOR→VOUCHED→HOUSEHOLD). moderation+ already authorized.
    if op == "promote":
        subject = args.get("subject_did")
        target = (args.get("tier") or TIER_VOUCHED).upper()
        if not subject or not str(subject).startswith("did:key:z"):
            return {"error": "promote needs args.subject_did (did:key:…)", "_status": 400}
        if target not in _TIER_RANK:
            return {"error": f"invalid tier '{target}' "
                             f"(FLOOR|VOUCHED|HOUSEHOLD)", "_status": 400}
        res = _set_tier(subject, target, require_increase=True)
        if "error" in res:
            return res
        print(f"[admin/promote] {subject[:16]}… → {target} by {caller_did[:16]}…")
        return {"status": "promoted", "subject_did": subject, "tier": target,
                "_status": 200}

    # soft enforcement: lower a DID's tier
    # without ejecting. Dropping to FLOOR clears its vouches (a demoted node
    # must re-earn trust). moderation+ already authorized.
    if op == "demote":
        subject = args.get("subject_did")
        target = (args.get("tier") or TIER_FLOOR).upper()
        if not subject or not str(subject).startswith("did:key:z"):
            return {"error": "demote needs args.subject_did (did:key:…)", "_status": 400}
        if target not in _TIER_RANK:
            return {"error": f"invalid tier '{target}' "
                             f"(FLOOR|VOUCHED|HOUSEHOLD)", "_status": 400}
        res = _set_tier(subject, target, require_decrease=True)
        if "error" in res:
            return res
        if target == TIER_FLOOR:
            with lock:
                vouches = load_vouches()
                if subject in vouches:
                    del vouches[subject]
                    save_vouches(vouches)
        print(f"[admin/demote] {subject[:16]}… → {target} by {caller_did[:16]}…")
        return {"status": "demoted", "subject_did": subject, "tier": target,
                "_status": 200}

    # file an abuse report. Open to ANY valid
    # signer (the authorize gate exempted `report`): caller_did IS the verified
    # reporter. The subject need not be present/registered (you may report a DID
    # that already left). Anti-spam: per-op rate-limit (HTTP layer) + a cap on
    # OPEN reports per (reporter, subject) pair.
    if op == "report":
        subject = args.get("subject_did")
        reason = (args.get("reason") or "").strip()
        if not subject or not str(subject).startswith("did:key:z"):
            return {"error": "report needs args.subject_did (did:key:…)", "_status": 400}
        if not reason:
            return {"error": "report needs args.reason (why)", "_status": 400}
        if subject == caller_did:
            return {"error": "cannot report yourself", "_status": 400}
        return file_report(subject_did=subject, reporter_did=caller_did,
                           reason=reason)

    # view the reports queue (moderator-only
    # already authorized at moderation scope above). Returns OPEN reports by
    # default; pass {"include_resolved": true} for the full ledger.
    if op == "report-queue":
        include_resolved = bool(args.get("include_resolved"))
        return report_queue(include_resolved=include_resolved)

    # resolve a report (moderator-only). `action`
    # ∈ dismiss | noted | removed. `removed` is ADVISORY — the actual kick is the
    # separate `remove` op; we only record the linkage + stamp the resolver.
    if op == "resolve-report":
        report_id = args.get("report_id")
        action = (args.get("action") or "").strip().lower()
        if not report_id:
            return {"error": "resolve-report needs args.report_id", "_status": 400}
        return resolve_report(report_id=report_id, action=action,
                              resolved_by=caller_did)

    return {"error": f"op '{op}' not implemented", "_status": 501}


def _set_tier(subject_did: str, tier: str, require_increase: bool = False,
              require_decrease: bool = False) -> dict:
    """Set the trust tier on the registration whose DID matches `subject_did`.
    Enforces direction (promote must raise, demote must lower) when requested.
    Returns {} on success or {error, _status}."""
    tier = tier.upper()
    with lock:
        regs = load_registrations()
        pubkey, entry = _record_for_did(regs, subject_did)
        if entry is None:
            return {"error": f"no registration for {subject_did[:20]}…", "_status": 404}
        cur = (entry.get("tier") or TIER_FLOOR).upper()
        if require_increase and _tier_rank(tier) <= _tier_rank(cur):
            return {"error": f"promote must raise the tier "
                             f"(current {cur}, target {tier})", "_status": 400}
        if require_decrease and _tier_rank(tier) >= _tier_rank(cur):
            return {"error": f"demote must lower the tier "
                             f"(current {cur}, target {tier})", "_status": 400}
        entry["tier"] = tier
        save_registrations(regs)
    return {}


def wot_voucher_weight(voucher_did: str) -> float:
    """How much trust a voucher lends, by its position in the web: the owner and
    any owner-direct delegate count as 1.0; otherwise the voucher's earned trust
    tier sets the weight (HOUSEHOLD 1.0, VOUCHED 0.6, FLOOR 0.0). This is the
    relay-side consumption of the WoT — a voucher's standing IS its tier, which
    it reached only through the web."""
    if voucher_did and voucher_did == owner_did():
        return 1.0
    return WOT_TIER_WEIGHT.get(did_tier(voucher_did), 0.0)


def wot_promotion_score(subject_did: str) -> float:
    """A candidate's WoT promotion score: Σ over its DISTINCT vouchers of each
    voucher's web standing (wot_voucher_weight). One HOUSEHOLD/owner voucher
    scores 1.0; two VOUCHED vouchers 0.6+0.6=1.2; a lone FLOOR voucher 0.0. This
    replaces the old flat COUNT — it weights each voucher by its earned trust."""
    vouchers = load_vouches().get(subject_did, [])
    return sum(wot_voucher_weight(v) for v in set(vouchers))


def _maybe_auto_promote(subject_did: str) -> bool:
    """auto-promote a FLOOR registration to VOUCHED
    when it (a) has a verified IdentityOutbox AND (b) its owner-rooted WoT
    promotion score is ≥ WOT_PROMOTE_THRESHOLD. identity-unverified BLOCKS
    promotion (the verified record is the price of leaving FLOOR, §4). The WoT
    score replaces the old flat vouch COUNT — it consumes the vouch GRAPH with
    hop-decay rooted at the owner. Returns True if it promoted."""
    regs = load_registrations()
    pubkey, entry = _record_for_did(regs, subject_did)
    if entry is None:
        return False
    if (entry.get("tier") or TIER_FLOOR).upper() != TIER_FLOOR:
        return False  # only FLOOR auto-promotes; higher tiers stay put.
    if not entry.get("identity_verified"):
        return False  # §4 — no verified IdentityOutbox ⇒ ineligible.
    if wot_promotion_score(subject_did) < WOT_PROMOTE_THRESHOLD:
        return False
    res = _set_tier(subject_did, TIER_VOUCHED)
    return "error" not in res


# ---: abuse reports queue ---
#
# A report is a {reporter_did → subject_did + reason} record persisted in
# relay-reports.json. ANY registered DID may FILE one (a user reporting a node,
# §8) — the open-to-any-signer bar in admin_op. MODERATORS (owner / moderation-
# scope delegate) VIEW the queue (`report-queue`) and RESOLVE entries
# (`resolve-report`). The subject DID may or may not be a current registration
# (you can report a DID that already left), so reports never require the subject
# to be present. Open-report cap per (reporter, subject) pair is the anti-spam
# floor on top of the per-op HTTP rate-limit.

# Max OPEN reports a single reporter may hold against a single subject. Beyond
# this, a duplicate file is rejected (the existing open report already flags it;
# more is spam). Env-tunable; conservative default.
REPORT_OPEN_CAP_PER_PAIR = int(os.environ.get("REPORT_OPEN_CAP_PER_PAIR", "3"))
_REPORT_RESOLUTIONS = ("dismiss", "noted", "removed")


def load_reports() -> list:
    """The relay-reports.json ledger: a list of report records. Returns [] if
    absent or malformed (a corrupt file must not wedge moderation)."""
    if RELAY_REPORTS_FILE.exists():
        try:
            data = json.loads(RELAY_REPORTS_FILE.read_text())
            return data if isinstance(data, list) else []
        except json.JSONDecodeError:
            return []
    return []


def save_reports(reports: list) -> None:
    """Atomic write (write tmp + replace) so a crash mid-write can't truncate
    the ledger — same pattern as the other governance stores."""
    tmp = RELAY_REPORTS_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(reports, indent=2, default=str))
    tmp.replace(RELAY_REPORTS_FILE)


def file_report(subject_did: str, reporter_did: str, reason: str) -> dict:
    """record an abuse report against `subject_did`
    by `reporter_did`. Open to any valid signer (gated only by signature +
    rate-limit + the per-pair open-report cap). Returns the stored record (plus
    `_status`), or an error dict if the cap is hit."""
    with lock:
        reports = load_reports()
        open_for_pair = [r for r in reports
                         if r.get("status") == "open"
                         and r.get("reporter_did") == reporter_did
                         and r.get("subject_did") == subject_did]
        if len(open_for_pair) >= REPORT_OPEN_CAP_PER_PAIR:
            return {"error": f"you already have {len(open_for_pair)} open "
                             f"report(s) against this DID (cap "
                             f"{REPORT_OPEN_CAP_PER_PAIR}); a moderator will "
                             "review them", "_status": 429}
        record = {
            "id": "rpt-" + secrets.token_hex(8),
            "subject_did": subject_did,
            "reporter_did": reporter_did,
            "reason": reason[:2000],   # cap the stored reason length
            "created_at": datetime.utcnow().isoformat(),
            "status": "open",
            "resolved_by": None,
            "resolved_at": None,
            "resolution": None,
        }
        reports.append(record)
        save_reports(reports)
    print(f"[admin/report] {reporter_did[:16]}… reported {subject_did[:16]}…")
    return {"status": "filed", "report": record, "report_id": record["id"],
            "_status": 200}


def report_queue(include_resolved: bool = False) -> dict:
    """the reports queue for moderators. Returns OPEN
    reports (newest first); `include_resolved` adds resolved ones too. Each row
    also carries the subject's current `subject_tier` + whether the subject is
    still a registration (`subject_present`) so the furnishing can show context
    for a DID that may already have left."""
    reports = load_reports()
    rows = [dict(r) for r in reports
            if include_resolved or r.get("status") == "open"]
    regs = load_registrations()
    present_dids = {e.get("did") for e in regs.values() if e.get("did")}
    for r in rows:
        subj = r.get("subject_did")
        r["subject_present"] = subj in present_dids
        r["subject_tier"] = did_tier(subj) if subj in present_dids else None
    rows.sort(key=lambda r: r.get("created_at") or "", reverse=True)
    open_count = sum(1 for r in reports if r.get("status") == "open")
    return {"reports": rows, "open_count": open_count,
            "total_count": len(reports), "_status": 200}


def resolve_report(report_id: str, action: str, resolved_by: str) -> dict:
    """resolve a report. `action` ∈ dismiss | noted |
    removed. `removed` is ADVISORY: it records that the moderator removed the
    subject (the actual kick is the separate `remove` op) — we only stamp the
    linkage. Stamps resolved_by/resolved_at; idempotent on an already-resolved
    id (returns its current state)."""
    action = (action or "").strip().lower()
    if action not in _REPORT_RESOLUTIONS:
        return {"error": f"invalid action '{action}' "
                         f"(one of {', '.join(_REPORT_RESOLUTIONS)})",
                "_status": 400}
    with lock:
        reports = load_reports()
        target = None
        for r in reports:
            if r.get("id") == report_id:
                target = r
                break
        if target is None:
            return {"error": f"no report with id '{report_id}'", "_status": 404}
        if target.get("status") != "open":
            # Idempotent: already resolved — return its state, don't re-stamp.
            return {"status": "already_resolved", "report": target,
                    "report_id": report_id, "_status": 200}
        target["status"] = "resolved"
        target["resolution"] = action
        target["resolved_by"] = resolved_by
        target["resolved_at"] = datetime.utcnow().isoformat()
        save_reports(reports)
    print(f"[admin/resolve-report] {report_id} → {action} "
          f"by {resolved_by[:16]}…")
    return {"status": "resolved", "resolution": action, "report": target,
            "report_id": report_id, "_status": 200}


# --- HTTP Handler ---

class RegistrationHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        # Hidden-SSID model: a private relay (RELAY_PUBLIC=false) answers no
        # discovery/enumeration probe — it is in no directory and confirms
        # nothing about itself — but stays fully usable with the join token
        # (/health, /join, /register*, /phone-invite all keep working).
        if not PUBLIC and self.path in ("/status", "/relays"):
            self.json_response(404, {"error": "Not found"})
            return
        if self.path == "/status":
            self.json_response(200, get_status())
        elif self.path == "/relays":
            peers = load_peers()
            self.json_response(200, {"relays": peers})
        elif self.path == "/health":
            self.json_response(200, {"status": "ok"})
        else:
            self.json_response(404, {"error": "Not found"})

    def _effective_ip(self) -> str:
        """Client IP for RATE-LIMITING purposes only.

        Behind Caddy (the single public listener), client_address is the
        proxy's docker IP — every client shares it, so per-IP limits become
        one global bucket: a single noisy client locks EVERYONE out (live-
        observed 2026-06-11: a /join rate-limit test blocked the next real
        join; /register's 1/IP/hr had the same latent bug). Caddy appends
        the real client to X-Forwarded-For; trust its FIRST hop.

        NEVER use this for the localhost-only authorization gates
        (/invite, /peer-invite) — XFF is client-spoofable; those gates must
        keep using the socket address.
        """
        xff = self.headers.get("X-Forwarded-For", "")
        if xff:
            first = xff.split(",")[0].strip()
            if first:
                return first
        return self.client_address[0]

    def do_POST(self):
        if self.path == "/register":
            # Read body (may carry an invite token).
            length = int(self.headers.get("Content-Length", 0))
            body = {}
            if length > 0:
                try:
                    body = json.loads(self.rfile.read(length))
                except json.JSONDecodeError:
                    self.json_response(400, {"error": "Invalid JSON body"})
                    return

            # Invite-token path (canonical, F2.1) takes precedence.
            invite_token = body.get("invite_token")
            if invite_token:
                try:
                    _inv_payload = verify_invite(invite_token)
                except ValueError as e:
                    self.json_response(401, {"error": f"Invite rejected: {e}"})
                    return
                if _inv_payload.get("ss"):
                    # Commons self-serve mints must enroll via /register-nkey,
                    # where the mode gate assigns FLOOR. Redeeming one here
                    # would grant a pw record outside the tier system.
                    self.json_response(403, {
                        "error": "self-serve (commons) joins enroll via NKey, "
                                 "not the legacy register path",
                        "hint": "run: wyrd relay join <host> --fingerprint <fp> "
                                "(fingerprint from the relay's page); if your "
                                "client is old, update it",
                    })
                    return
                # Invite covers auth; rate-limit doesn't apply (single-use already).
                ip = self._effective_ip()
                with lock:
                    rate_limits.pop(ip, None)
            else:
                # Legacy/anonymous path. Allowed only if no invite enforcement.
                if API_KEY:
                    auth = self.headers.get("Authorization", "")
                    if auth != f"Bearer {API_KEY}":
                        self.json_response(401, {
                            "error": "Invalid API key",
                            "hint": "Use a wyrdrelay:// invite URL via wyrd relay register",
                        })
                        return

            ip = self._effective_ip()
            result = register_household(ip)
            # deprecation: surface the migration prompt
            # in the response so any client that hits /register sees it. Also
            # logged once per registration for relay-side observability.
            if "token" in result:
                result["deprecated"] = True
                result["deprecation_notice"] = (
                    "Password-mode relay registration is deprecated. "
                    "Use /register-nkey with an NKey pubkey for per-node auth: "
                    "the node signs a challenge, so no shared secret crosses the wire."
                )
                print(f"[deprecation] /register hit by {ip} — "
                      "client should migrate to /register-nkey")
            code = 200 if "token" in result else 429 if "Rate limited" in result.get("error", "") else 503
            self.json_response(code, result)

        elif self.path == "/register-nkey":
            # /register-nkey. Same invite-token gate as /register
            # (anti-abuse, capacity caps, single-use nonces). Idempotent: same
            # pubkey twice updates metadata in place — drift recovery flow.
            length = int(self.headers.get("Content-Length", 0))
            body = {}
            if length > 0:
                try:
                    body = json.loads(self.rfile.read(length))
                except json.JSONDecodeError:
                    self.json_response(400, {"error": "Invalid JSON body"})
                    return

            pubkey = body.get("pubkey")
            if not pubkey:
                self.json_response(400, {"error": "pubkey required (NATS NKey, 56 chars starting U)"})
                return

            ip = self._effective_ip()
            # the MODE gate decides whether an invite
            # is required and the entrant tier. In commons mode the per-IP
            # rate-limit is the HARD anti-abuse gate (no invite), so enforce it
            # BEFORE registering; invite-only/open consume the invite instead.
            if relay_mode() == "commons" and RATE_LIMIT_SECONDS > 0:
                with lock:
                    last = rate_limits.get(ip, 0)
                    if time.time() - last < RATE_LIMIT_SECONDS:
                        wait = int(RATE_LIMIT_SECONDS - (time.time() - last))
                        self.json_response(429, {
                            "error": f"Rate limited (commons mode). Try again in {wait}s.",
                        })
                        return
            gate = gate_register_nkey(body)
            if "error" in gate:
                self.json_response(gate.get("_status", 401), gate)
                return
            with lock:
                if relay_mode() == "commons":
                    # Stamp the rate-limit clock for the FLOOR self-serve path.
                    rate_limits[ip] = time.time()
                else:
                    rate_limits.pop(ip, None)  # invite consumed; rate-limit doesn't apply
            result = register_nkey(
                ip,
                pubkey=pubkey,
                household_tag=body.get("household_tag"),
                zone_id=body.get("zone_id"),
                node_name=body.get("node_name"),
                entrant_tier=gate["entrant_tier"],
                identity_outbox=gate.get("identity_outbox"),
            )
            if "error" in result:
                code = result.get("_status") or (503 if "capacity" in result.get("error", "").lower() else 400)
            else:
                code = 200
            self.json_response(code, result)

        elif self.path == "/re-register-nkey":
            # /re-register-nkey. Drift recovery without an
            # invite — caller proves seed-ownership via NKey signature over
            # `ts:pubkey`. The pubkey MUST already be in regs.json. Useful when
            # operator wiped regs.json but client still has its NodeIdentity.
            length = int(self.headers.get("Content-Length", 0))
            body = {}
            if length > 0:
                try:
                    body = json.loads(self.rfile.read(length))
                except json.JSONDecodeError:
                    self.json_response(400, {"error": "Invalid JSON body"})
                    return
            ip = self._effective_ip()
            result = re_register_existing_nkey(
                ip,
                pubkey=body.get("pubkey"),
                ts=body.get("ts"),
                signature_b64=body.get("signature"),
            )
            code = result.pop("_status", 200) if "error" in result else 200
            self.json_response(code, result)

        elif self.path == "/deregister":
            # Voluntary teardown — the inverse of /re-register-nkey. The caller
            # signs `deregister:{ts}:{pubkey}` with the seed; on success the
            # record is hard-deleted and the NKey pulled from the live auth
            # config. Idempotent (already-absent → 200). This is what `wyrd
            # relay leave` / `wyrd uninstall` call so a torn-down zone's
            # registration goes away instead of lingering.
            length = int(self.headers.get("Content-Length", 0))
            body = {}
            if length > 0:
                try:
                    body = json.loads(self.rfile.read(length))
                except json.JSONDecodeError:
                    self.json_response(400, {"error": "Invalid JSON body"})
                    return
            result = deregister_nkey(
                pubkey=body.get("pubkey"),
                ts=body.get("ts"),
                signature_b64=body.get("signature"),
            )
            code = result.pop("_status", 200)
            self.json_response(code, result)

        elif self.path == "/invite":
            # Localhost-only — only the relay operator can mint invites.
            client_ip = self.client_address[0]
            if client_ip not in ("127.0.0.1", "::1", "localhost"):
                self.json_response(403, {
                    "error": "Invite minting must be initiated from the relay host (use wyrd relay invite)",
                })
                return
            length = int(self.headers.get("Content-Length", 0))
            body = {}
            if length > 0:
                try:
                    body = json.loads(self.rfile.read(length))
                except json.JSONDecodeError:
                    self.json_response(400, {"error": "Invalid JSON body"})
                    return
            ttl = int(body.get("ttl", INVITE_DEFAULT_TTL))
            try:
                result = mint_invite(ttl)
            except FileNotFoundError as e:
                self.json_response(503, {"error": str(e)})
                return
            # every invite also gets a short typeable
            # join code; `wyrd relay join <host> <code>` redeems it at /join.
            result["join_code"] = _mint_join_code(result)
            self.json_response(200, result)

        elif self.path == "/join":
            # PUBLIC endpoint (rides the Caddy
            # listener). Exchanges a single-use join code for the full
            # invite payload. Security: code is 8 chars over a 31-symbol
            # alphabet (~40 bits), single-use, invite-TTL-bound, and
            # attempts are rate-limited per IP.
            client_ip = self._effective_ip()
            if not _join_rate_ok(client_ip):
                self.json_response(429, {"error": "Too many join attempts — wait a minute"})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length)) if length > 0 else {}
            except json.JSONDecodeError:
                self.json_response(400, {"error": "Invalid JSON body"})
                return
            code = str(body.get("code", "")).strip().lower()
            if not code:
                # commons self-serve: a codeless
                # /join on a commons relay hands out the same v2 invite
                # payload an operator invite carries (CA embedded), minted
                # fresh with a short TTL and consuming nothing. The client
                # MUST verify the payload's ca_fp against operator-published
                # material (the relay's page on the public-PKI website) or
                # confirm it interactively — the fingerprint, not transport,
                # is the trust decision, exactly as with a carried token.
                # Anti-abuse: same per-IP /join rate limit as code attempts;
                # registration itself is further gated by the commons per-IP
                # limit + FLOOR caps at /register-nkey.
                if relay_mode() == "commons":
                    invite = mint_invite(SELF_SERVE_INVITE_TTL_SECONDS, self_serve=True)
                    invite["self_serve"] = True
                    print(f"[join] commons self-serve payload issued to {client_ip}")
                    self.json_response(200, invite)
                    return
                self.json_response(400, {"error": "Missing 'code'"})
                return
            invite = _consume_join_code(code)
            if invite is None:
                self.json_response(404, {
                    "error": "Unknown, expired, or already-used join code. "
                             "Mint a fresh one on the relay: wyrd relay invite",
                })
                return
            print(f"[join] code redeemed by {client_ip}")
            self.json_response(200, invite)

        elif self.path == "/phone-invite":
            # PUBLIC endpoint (rides the Caddy
            # listener). A registered zone mints a phone connection
            # invite by signing a challenge with its household NKey —
            # no localhost gate needed because possession of the seed
            # IS the authorization.
            client_ip = self._effective_ip()
            if not _join_rate_ok(client_ip, bucket=_phone_invite_attempts):
                self.json_response(429, {"error": "Too many phone-invite attempts — wait a minute"})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length)) if length > 0 else {}
            except json.JSONDecodeError:
                self.json_response(400, {"error": "Invalid JSON body"})
                return
            result = mint_phone_invite(
                pubkey=body.get("pubkey"),
                ts=body.get("ts"),
                signature_b64=body.get("signature"),
                household_id=body.get("household_id"),
                token=body.get("token"),
            )
            code = result.pop("_status", 200) if "error" in result else 200
            if "error" not in result:
                print(f"[phone-invite] minted for {body.get('pubkey', '?')[:12]}… by {client_ip}")
            self.json_response(code, result)

        elif self.path == "/peer-invite":
            # localhost-only mint of a peer-invite.
            # Hand the resulting URL to the OTHER relay's operator out-of-band.
            if self.client_address[0] not in ("127.0.0.1", "::1", "localhost"):
                self.json_response(403, {
                    "error": "Peer-invite minting is localhost-only "
                             "(use `wyrd relay peer-invite` on the relay host)",
                })
                return
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length)) if length > 0 else {}
            try:
                result = mint_peer_invite(
                    remote_host_hint=body.get("remote_host_hint"),
                    ttl_seconds=body.get("ttl"))
            except FileNotFoundError as e:
                self.json_response(503, {"error": str(e)})
                return
            self.json_response(200, result)

        elif self.path == "/peer-accept":
            # accept a peer-invite token minted by
            # another relay. Records the peer locally; the operator must then
            # also update relay.conf leafnodes.remotes (template emitted in
            # the response).
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length)) if length > 0 else {}
            invite_token = body.get("invite_token")
            if not invite_token:
                self.json_response(400, {
                    "error": "invite_token required (paste the wyrdrelay-peer:// "
                             "value or its token portion)",
                })
                return
            try:
                payload = verify_peer_invite(invite_token)
            except ValueError as e:
                self.json_response(401, {"error": f"Peer invite rejected: {e}"})
                return
            result = accept_peer_invite(
                remote_token=invite_token,
                remote_url=body.get("remote_url"),
                remote_pubkey=body.get("remote_pubkey"),
                remote_fingerprint=body.get("remote_fingerprint")
                    or payload.get("fp"))
            code = result.pop("_status", 200) if "error" in result else 200
            self.json_response(code, result)

        elif self.path == "/announce":
            # A private (hidden) relay does not advertise itself into any
            # directory — refuse the announce instead of recording it.
            if not PUBLIC:
                self.json_response(403, {"error": "relay is private"})
                return
            # item 3: relay signs its own
            # /announce payload so directory authorities can verify the
            # relay's identity instead of trusting an unauthenticated
            # POST. The relay's leaf cert fingerprint is the binding
            # identity; the signature commits the announce body to that
            # fingerprint so an attacker can't replay an old announce
            # against a freshly rotated cert.
            #
            # Wire shape:
            #   {url, region, capacity, registered, public,
            #    relay_fingerprint, announced_at, signature}
            # signature = HMAC-SHA256(invite_key,
            #                          canonical(url|region|capacity|
            #                                    registered|public|
            #                                    fingerprint|announced_at))
            # invite_key is the per-relay 32-byte secret already used
            # to sign invite tokens (mint_invite). HMAC is sufficient
            # here because the verifier (authority) holds the same
            # invite_key as part of relay registration; promoting to
            # asymmetric Ed25519 over leaf.key is a future hardening.
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length)) if length > 0 else {}
                self.json_response(200, _record_announce_signed(body))
            except Exception as e:
                self.json_response(400, {"error": str(e)})

        elif self.path == "/claim-owner":
            # b — PUBLIC endpoint (fronted by Caddy)
            # the owner-claim token + DID signature are the authorization, so
            # no localhost gate. Records owner_did on a valid, unconsumed claim.
            client_ip = self._effective_ip()
            if not _join_rate_ok(client_ip, bucket=_admin_attempts):
                self.json_response(429, {"error": "Too many claim attempts — wait a minute"})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length)) if length > 0 else {}
            except json.JSONDecodeError:
                self.json_response(400, {"error": "Invalid JSON body"})
                return
            result = claim_owner(
                token=body.get("token"),
                did=body.get("did"),
                ts=body.get("ts"),
                signature_b64=body.get("signature_b64") or body.get("signature"),
            )
            code = result.pop("_status", 200)
            self.json_response(code, result)

        elif self.path == "/admin" or self.path.startswith("/admin/"):
            # signed admin surface (PUBLIC via Caddy
            # signature-gated). The op may be carried in the body OR as the path
            # tail (/admin/<op>); the body always wins if both present.
            client_ip = self._effective_ip()
            if not _join_rate_ok(client_ip, bucket=_admin_attempts, limit=30):
                self.json_response(429, {"error": "Too many admin requests — wait a minute"})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length)) if length > 0 else {}
            except json.JSONDecodeError:
                self.json_response(400, {"error": "Invalid JSON body"})
                return
            path_op = self.path[len("/admin/"):] if self.path.startswith("/admin/") else ""
            op = body.get("op") or path_op
            result = admin_op(
                op=op,
                args=body.get("args"),
                relay_did=body.get("relay_did"),
                ts=body.get("ts"),
                did=body.get("did"),
                signature_b64=body.get("signature_b64") or body.get("signature"),
            )
            code = result.pop("_status", 200)
            self.json_response(code, result)

        elif self.path == "/claim-owner-mint":
            # b — localhost-only mint of an owner-claim
            # token (same trust model as /invite: shell on the relay host).
            # relay.sh calls this at deploy. Never publicly proxied by Caddy.
            if self.client_address[0] not in ("127.0.0.1", "::1", "localhost"):
                self.json_response(403, {
                    "error": "owner-claim minting is localhost-only "
                             "(run relay.sh on the relay host)",
                })
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length)) if length > 0 else {}
            except json.JSONDecodeError:
                self.json_response(400, {"error": "Invalid JSON body"})
                return
            ttl = int(body.get("ttl", INVITE_MAX_TTL))
            try:
                self.json_response(200, mint_owner_claim_token(ttl))
            except FileNotFoundError as e:
                self.json_response(503, {"error": str(e)})

        else:
            self.json_response(404, {"error": "Not found"})

    def json_response(self, code, data):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def log_message(self, format, *args):
        # Quieter logging
        pass


# ── NATS parallel surface ──
#
# Phones + zones can call the same registration ops via NATS request/reply
# instead of HTTP through Caddy on :443. The HTTP server above stays up
# during migration so legacy clients keep working. Operator-only ops
# (`/invite`, `/peer-invite`) intentionally remain HTTP-localhost-only —
# their auth is "you have shell on the relay host" and that doesn't
# translate to NATS subjects.
#
# Subjects:
#   relay.register      — mirror of POST /register-nkey   (invite + pubkey)
#   relay.re-register   — mirror of POST /re-register-nkey (drift recovery)
#   relay.peer.list     — mirror of GET  /relays
#   relay.status        — mirror of GET  /status
#   relay.announce      — mirror of POST /announce
#
# Wire shape: JSON in, JSON out via NATS inbox reply. No `ok:true` envelope
# on the success path — replies match the existing HTTP body shape so
# clients can swap transport without parser changes. On error we emit
# {"error": "...", "_status": <http-equiv>}.

NATS_URL = os.environ.get("NATS_URL", "nats://nats:4222")
NATS_USER = os.environ.get("NATS_USER", "")
NATS_PASSWORD = os.environ.get("NATS_PASSWORD", "")
NATS_ENABLED = os.environ.get("RELAY_NATS_ENABLED", "true").lower() != "false"


async def _nats_dispatch(msg, op_name, handler):
    """Run a synchronous handler in a thread, JSON-encode the reply."""
    try:
        body = json.loads(msg.data.decode()) if msg.data else {}
    except json.JSONDecodeError as e:
        reply = {"error": f"invalid JSON: {e}"}
    else:
        try:
            # The HTTP handlers are sync; run them as-is (fast).
            reply = handler(body, msg)
        except Exception as e:
            reply = {"error": str(e)}
    if msg.reply:
        await msg.respond(json.dumps(reply).encode())


def _nats_handle_register(body, _msg):
    pubkey = body.get("pubkey")
    if not pubkey:
        return {"error": "pubkey required (NATS NKey, 56 chars starting U)",
                "_status": 400}
    # same MODE gate as the HTTP surface.
    gate = gate_register_nkey(body)
    if "error" in gate:
        return gate
    # NATS clients don't have a meaningful client IP (it's the NATS server's
    # connection) — use the metadata.client field if the client sent one,
    # otherwise fall back to "nats" as a placeholder for the rate-limit ledger.
    ip = body.get("source_ip") or "nats"
    with lock:
        rate_limits.pop(ip, None)
    return register_nkey(
        ip,
        pubkey=pubkey,
        household_tag=body.get("household_tag"),
        zone_id=body.get("zone_id"),
        node_name=body.get("node_name"),
        entrant_tier=gate["entrant_tier"],
        identity_outbox=gate.get("identity_outbox"),
    )


def _nats_handle_re_register(body, _msg):
    ip = body.get("source_ip") or "nats"
    return re_register_existing_nkey(
        ip,
        pubkey=body.get("pubkey"),
        ts=body.get("ts"),
        signature_b64=body.get("signature"),
    )


# Hidden-SSID model: a private relay is dark over NATS too — the discovery
# mirrors (peer.list / status / announce) decline so it appears in no
# directory and answers no enumeration over the bus.
def _nats_handle_peer_list(_body, _msg):
    if not PUBLIC:
        return {"error": "relay is private"}
    return {"relays": load_peers()}


def _nats_handle_status(_body, _msg):
    if not PUBLIC:
        return {"error": "relay is private"}
    return get_status()


def _nats_handle_announce(body, _msg):
    if not PUBLIC:
        return {"error": "relay is private"}
    return _record_announce_signed(body)


def _fetch_connection_counts() -> dict | None:
    """Poll the localhost NATS monitoring endpoint (/connz?auth=1) and return a
    map of currently-connected NKey pubkey -> concurrent connection count.

    Returns None (NOT an empty dict) when the endpoint is unreachable or
    unparseable — the caller MUST treat None as "no liveness data this cycle"
    and reap nothing, so a monitoring outage never wipes registrations.

    NATS 2.10's connz exposes the authenticating NKey under the `nkey` field
    when queried with `auth=1`. nkey-mode households authenticate by pubkey, so
    that field is exactly the regs.json key. A node may hold several connections
    at once (multiple surfaces), so we COUNT rather than dedupe — the count is
    what the per-tier max_connections ceiling (§5/§8) is measured against.
    """
    import urllib.request

    url = NATS_MONITOR_URL.rstrip("/") + "/connz?auth=1"
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[reaper] monitoring endpoint unreachable ({url}): {e}; "
              "skipping this cycle (no reaping without liveness data)")
        return None

    return _connection_keys_from_connz(data)


def _connection_keys_from_connz(data: dict) -> dict:
    """Map each connected identity to its concurrent-connection count.

    NKey-mode households show up under `nkey` (their regs.json key IS the
    pubkey). Legacy password-mode households — still the majority of real
    installs, registered via /register — show up under `authorized_user`,
    and THAT is their regs.json key (the household id doubles as the NATS
    user). Counting only `nkey` made every password-mode zone look absent
    forever: its last_seen was never stamped, and the reaper deleted it at
    the end of its window WHILE ITS BRIDGE WAS CONNECTED (a household zone,
    2026-08-24 — the relay regenerated its config without the user and the
    live connection died with an Authorization Violation on reconnect).
    """
    counts = {}
    for conn in data.get("connections", []):
        key = conn.get("nkey") or conn.get("authorized_user")
        if key:
            counts[key] = counts.get(key, 0) + 1
    return counts


def _fetch_connected_nkeys() -> set | None:
    """The set of currently-connected NKey pubkeys (None on monitor outage).
    Thin wrapper over _fetch_connection_counts for callers that only need
    presence, not per-DID connection counts."""
    counts = _fetch_connection_counts()
    return set(counts) if counts is not None else None


def _reap_stale_registrations() -> int:
    """One reaper sweep: refresh last_seen for currently-connected pubkeys,
    then hard-delete any registration absent for the full liveness window.

    Returns the number of records deleted (0 if the monitoring endpoint was
    unreachable — defensive: no liveness data ⇒ no reaping).
    """
    conn_counts = _fetch_connection_counts()
    if conn_counts is not None:
        connected = set(conn_counts)
    else:
        # Counts unavailable — fall back to the presence-only seam (also the
        # patch point exercised by the reaper tests). Overage detection needs
        # per-DID counts, so it's skipped this cycle; pruning still runs.
        connected = _fetch_connected_nkeys()
        if connected is None:
            return 0  # no data — never reap on a blind cycle.

    now = datetime.utcnow()
    policy = load_policy()

    def _parse_iso(s):
        if not s:
            return None
        try:
            return datetime.fromisoformat(s)
        except (ValueError, TypeError):
            return None

    deleted = []
    with lock:
        regs = load_registrations()
        mutated = False

        # 1. Stamp last_seen for everyone currently connected, and detect
        # per-DID connection-ceiling overage.
        #    The sidecar can't sever a single NATS connection without dropping
        #    the record from auth, so this is DETECTION-grade: we stamp the
        #    overage onto the record so list/audit surfaces it for the operator
        #    (who can relay.sh remove an abuser). max_connections < 0 = no cap.
        for pubkey in connected:
            entry = regs.get(pubkey)
            if entry is not None:
                entry["last_seen"] = now.isoformat()
                cap = tier_quota(entry.get("tier"), policy).get("max_connections", -1)
                live = conn_counts.get(pubkey, 0) if conn_counts is not None else None
                if live is not None and cap is not None and cap >= 0 and live > cap:
                    entry["over_connection_limit"] = {
                        "live": live, "cap": cap,
                        "tier": (entry.get("tier") or TIER_HOUSEHOLD).upper(),
                        "observed_at": now.isoformat(),
                    }
                    print(f"[reaper] connection-ceiling overage: {pubkey[:12]}… "
                          f"{live}>{cap} ({(entry.get('tier') or TIER_HOUSEHOLD).upper()})")
                elif live is not None and "over_connection_limit" in entry:
                    # Back within budget — clear the stale overage marker. Only
                    # when we actually have counts this cycle (don't clear on a
                    # counts-unavailable fallback cycle).
                    del entry["over_connection_limit"]
                mutated = True

        # 2. Prune anything past the window. A record is stale when:
        #    - it has a last_seen older than the window, OR
        #    - it never connected (last_seen null) AND registered before the
        #      window opened (registered-but-never-connected past the window).
        for pubkey, entry in list(regs.items()):
            if pubkey in connected:
                continue  # fresh — just stamped.
            # the reaper window is gated by trust
            # tier: FLOOR newcomers are reaped fast (default 24h), VOUCHED/
            # HOUSEHOLD keep the long window (168h). Per-record, not global.
            window = timedelta(hours=reaper_window_hours_for(entry))
            last_seen = _parse_iso(entry.get("last_seen"))
            if last_seen is not None:
                stale = (now - last_seen) > window
            else:
                registered = _parse_iso(entry.get("registered_at"))
                # No last_seen AND no/old registration → reap once past window.
                stale = registered is None or (now - registered) > window
            if stale:
                del regs[pubkey]
                deleted.append(pubkey)
                mutated = True

        if deleted:
            save_registrations(regs)
            try:
                update_nats_config(regs)
            except Exception as e:
                print(f"[reaper] NATS config rewrite warned after reaping "
                      f"{len(deleted)} record(s): {e}")
        elif mutated:
            # last_seen refreshes only — persist the ledger but skip the
            # (more expensive) auth-config rewrite; nothing about auth changed.
            save_registrations(regs)

    for pubkey in deleted:
        print(f"[reaper] reaped stale registration {pubkey[:12]}… "
              f"(no NATS connect within its tier window; "
              f"FLOOR={FLOOR_LIVENESS_TIMEOUT_HOURS}h / "
              f"VOUCHED+HOUSEHOLD={LIVENESS_TIMEOUT_HOURS}h)")
    return len(deleted)


def _start_liveness_reaper():
    """Daemon thread that sweeps connz every LIVENESS_REAP_INTERVAL_SECONDS and
    hard-deletes registrations whose zone hasn't connected within the window.
    Lifecycle mirrors _start_nats_subscriber (daemon thread, never crashes the
    process). Implements the long-promised "tokens revoked if no NATS connect"
    behaviour the module docstring describes."""
    import threading

    def thread_body():
        print(f"[reaper] liveness reaper started — window={LIVENESS_TIMEOUT_HOURS}h, "
              f"interval={LIVENESS_REAP_INTERVAL_SECONDS}s, monitor={NATS_MONITOR_URL}")
        while True:
            try:
                _reap_stale_registrations()
            except Exception as e:
                print(f"[reaper] sweep error: {e}")
            time.sleep(LIVENESS_REAP_INTERVAL_SECONDS)

    t = threading.Thread(target=thread_body, name="liveness-reaper", daemon=True)
    t.start()
    return t


def _start_nats_subscriber():
    """Run an asyncio loop in a daemon thread that subscribes to all
    relay.* subjects. Reconnects forever on the NATS client side; if the
    initial connect fails (NATS not yet up), retry once a second."""
    import asyncio
    import threading

    async def runner():
        import nats
        backoff = 1
        while True:
            try:
                connect_kwargs = dict(
                    name="relay-registration-sidecar",
                    reconnect_time_wait=2,
                    max_reconnect_attempts=-1,
                )
                if NATS_USER:
                    connect_kwargs["user"] = NATS_USER
                    connect_kwargs["password"] = NATS_PASSWORD
                nc = await nats.connect(NATS_URL, **connect_kwargs)
                print(f"[nats] connected to {NATS_URL}, subscribing to relay.*")
                # nats-py 2.14+ requires the cb to be an `async def`, not a
                # sync wrapper. Bind each handler to a fresh coroutine factory.
                async def _on_register(m): await _nats_dispatch(m, "register", _nats_handle_register)
                async def _on_reregister(m): await _nats_dispatch(m, "re-register", _nats_handle_re_register)
                async def _on_peer_list(m): await _nats_dispatch(m, "peer.list", _nats_handle_peer_list)
                async def _on_status(m): await _nats_dispatch(m, "status", _nats_handle_status)
                async def _on_announce(m): await _nats_dispatch(m, "announce", _nats_handle_announce)
                await nc.subscribe("relay.register",     cb=_on_register)
                await nc.subscribe("relay.re-register",  cb=_on_reregister)
                await nc.subscribe("relay.peer.list",    cb=_on_peer_list)
                await nc.subscribe("relay.status",       cb=_on_status)
                await nc.subscribe("relay.announce",     cb=_on_announce)
                print("[nats] subscribed: relay.{register,re-register,peer.list,status,announce}")
                # Stay alive — nats-py auto-reconnects, just sleep forever.
                await asyncio.Event().wait()
            except Exception as e:
                print(f"[nats] connect failed: {e}; retry in {backoff}s")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30)

    def thread_body():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(runner())
        except Exception as e:
            print(f"[nats] subscriber loop crashed: {e}")

    t = threading.Thread(target=thread_body, name="nats-subscriber", daemon=True)
    t.start()
    return t


if __name__ == "__main__":
    print(f"Wyrdsekai Relay Registration Sidecar")
    print(f"  Port:     {PORT}")
    print(f"  Capacity: {CAPACITY}")
    print(f"  Region:   {REGION}")
    print(f"  Public:   {PUBLIC}")
    print(f"  Data:     {DATA_DIR}")
    print()

    # Auto-restore: rehydrate relay.conf from persistent ledger on every boot.
    # Without this, a NATS restart loads whatever's on the bind-mounted host
    # file — which may have been reverted (e.g. compose redeploy). The ledger
    # is the source of truth; relay.conf is derived.
    # F2.2 / federation auto-restore — "reinstall then minor config" goal.
    try:
        boot_regs = load_registrations()
        active_count = sum(1 for v in boot_regs.values() if v.get("active", True))
        if active_count > 0:
            print(f"[boot] Rehydrating relay.conf from {active_count} active registrations…")
            update_nats_config(boot_regs)
            print(f"[boot] relay.conf rebuilt + NATS reload signaled.")
        else:
            print(f"[boot] No active registrations in ledger — relay.conf left as-is.")
    except FileNotFoundError as e:
        print(f"[boot] Skipping rehydrate: {e}")
    except Exception as e:
        # Non-fatal: HTTP API still comes up so operators can re-register.
        print(f"[boot] WARN: relay.conf rehydrate failed: {e}")

    # one-time idempotent DID backfill for any
    # records that pre-date the field (cheap; no auth-config change).
    try:
        n = backfill_dids()
        if n:
            print(f"[boot] Backfilled canonical DID on {n} registration(s).")
    except Exception as e:
        print(f"[boot] WARN: DID backfill failed (non-fatal): {e}")

    # backfill tier=HOUSEHOLD on pre-P5 records
    # (all were invite-bound = operator-vouched). Cheap; no auth change.
    try:
        n = backfill_tiers()
        if n:
            print(f"[boot] Backfilled tier=HOUSEHOLD on {n} registration(s).")
    except Exception as e:
        print(f"[boot] WARN: tier backfill failed (non-fatal): {e}")

    # §4 — seed/print the registration mode (relay-policy.json is the source of
    # truth; RELAY_MODE env only seeds the first read).
    try:
        print(f"[boot] Registration mode: {relay_mode()}")
    except Exception as e:
        print(f"[boot] WARN: could not resolve registration mode: {e}")

    if NATS_ENABLED:
        try:
            _start_nats_subscriber()
        except Exception as e:
            # Subscriber crash mustn't block HTTP server startup.
            print(f"[nats] failed to start subscriber thread: {e}")

    # Liveness reaper (Part 3). Defensive: if the monitoring endpoint is
    # unreachable it reaps nothing, so this is safe to start unconditionally.
    try:
        _start_liveness_reaper()
    except Exception as e:
        print(f"[reaper] failed to start liveness reaper thread: {e}")

    server = HTTPServer((BIND, PORT), RegistrationHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.server_close()
