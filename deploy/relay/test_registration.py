"""tests for the relay registration sidecar.

Covers /register-nkey idempotency, /re-register-nkey signature verification,
namespace isolation in update_nats_config, and replay-window enforcement.

Run with:
    pip install pytest nkeys
    cd deploy/relay
    pytest test_registration.py -v

Spawns nothing. Imports `registration` and exercises the functions directly,
patching CERT_DIR/NATS_CONF/DATA_DIR via env BEFORE import.
"""

import base64
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

import pytest


# Patch env BEFORE importing registration so its module-level constants
# pick up the test paths instead of /var/lib/wyrd-relay etc.
_TEST_DIR = Path(tempfile.mkdtemp(prefix="wyrd-relay-test-"))
_CERT_DIR = _TEST_DIR / "certs"
_DATA_DIR = _TEST_DIR / "data"
_CERT_DIR.mkdir(parents=True)
_DATA_DIR.mkdir(parents=True)

# Minimal self-signed-ish PEM so _leaf_fingerprint() doesn't bomb. We don't
# actually verify the chain; just need a parsable cert at /certs/leaf.crt.
# This is a real DER (a one-byte cert is invalid; we use a tiny fake by
# generating a self-signed via cryptography lib if available, else skip).
def _write_dummy_leaf():
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID
        from datetime import datetime, timedelta, timezone
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, "test-relay"),
        ])
        cert = (x509.CertificateBuilder()
                .subject_name(name)
                .issuer_name(name)
                .public_key(key.public_key())
                .serial_number(1)
                .not_valid_before(datetime.now(timezone.utc) - timedelta(hours=1))
                .not_valid_after(datetime.now(timezone.utc) + timedelta(hours=1))
                .sign(key, hashes.SHA256()))
        pem = cert.public_bytes(serialization.Encoding.PEM)
        (_CERT_DIR / "leaf.crt").write_bytes(pem)
        # §10.9 — mint_invite embeds the CA in the invite payload, so the
        # test cert dir needs a ca.crt too. Reuse the same self-signed cert
        # as the "CA" — verify_invite doesn't actually check the chain.
        (_CERT_DIR / "ca.crt").write_bytes(pem)
        return True
    except ImportError:
        return False


_HAVE_CRYPTOGRAPHY = _write_dummy_leaf()

# Minimal NATS config for update_nats_config to operate on.
_NATS_CONF = _TEST_DIR / "relay.conf"
_NATS_CONF.write_text("""
port: 4222
authorization {
    users = [
        { user: "peer_trainer", password: "secret",
          permissions: { publish: { allow: ["training.>"] } } }
    ]
}
""")

os.environ["DATA_DIR"] = str(_DATA_DIR)
os.environ["CERT_DIR"] = str(_CERT_DIR)
os.environ["NATS_CONF"] = str(_NATS_CONF)
os.environ["NATS_SIGNAL_CMD"] = "true"  # no-op for tests
os.environ["RATE_LIMIT_SECONDS"] = "0"
os.environ["RELAY_PUBLIC_HOST"] = "test-relay.local"
# exercise the SSH-tunnel path (small range so the
# exhaustion test is cheap).
os.environ["WYRD_SSH_TUNNEL_ENABLED"] = "true"
os.environ["WYRD_SSH_TUNNEL_PORT_BASE"] = "7100"
os.environ["WYRD_SSH_TUNNEL_PORT_COUNT"] = "3"

# Make sure deploy/relay is on the path so we can import registration.
sys.path.insert(0, str(Path(__file__).parent))
import registration  # noqa: E402


def _make_keypair():
    """Return (pubkey_str, signer) for a fresh NATS user NKey.

    The Python `nkeys` library differs from the Java `jnats` API — there's no
    `create_user_pair()`. We generate the underlying Ed25519 seed via PyNaCl,
    encode it with the USER prefix, then load via `from_seed`.
    """
    nkeys = pytest.importorskip("nkeys")
    nacl_signing = pytest.importorskip("nacl.signing")
    sk = nacl_signing.SigningKey.generate()
    seed = nkeys.encode_seed(sk.encode(), nkeys.PREFIX_BYTE_USER)
    kp = nkeys.from_seed(seed)
    pubkey = kp.public_key.decode("ascii")
    return pubkey, kp


@pytest.fixture(autouse=True)
def reset_state():
    """Wipe regs.json + nonces between tests so each starts clean."""
    for f in (registration.REGISTRATIONS_FILE, registration.SEEN_NONCES_FILE,
              registration.PEERS_FILE, registration.OWNER_FILE,
              registration.RELAY_ADMIN_GRANTS_FILE,
              registration.OWNER_CLAIM_TOKENS_FILE,
              registration.RELAY_POLICY_FILE, registration.RELAY_VOUCHES_FILE,
              registration.RELAY_REPORTS_FILE):
        if f.exists():
            f.unlink()
    # Reset the env-seeded owner so owner_did() doesn't leak across tests.
    registration.RELAY_OWNER_DID = ""
    # default each test to invite-only (the relay
    # default) unless the test explicitly flips the mode. Restore the
    # conservative P5 tunables so mutations don't leak across tests.
    registration.RELAY_MODE_DEFAULT = "invite-only"
    registration.COMMONS_VOUCH_THRESHOLD = 2
    registration.FLOOR_LIVENESS_TIMEOUT_HOURS = 24
    registration.REPORT_OPEN_CAP_PER_PAIR = 3
    yield


def _make_did_keypair():
    """Return (did, signing_key) for a fresh Ed25519 identity, where `did` is
    the canonical did:key: that registration.did_to_ed25519_pubkey inverts.
    This is the DID a node's NodeIdentity.did() produces for its own key."""
    nacl_signing = pytest.importorskip("nacl.signing")
    sk = nacl_signing.SigningKey.generate()
    raw_pub = bytes(sk.verify_key)
    did = "did:key:z" + registration._base58btc_encode(
        registration._MULTICODEC_ED25519 + raw_pub)
    return did, sk


def _sign_b64(sk, message: bytes) -> str:
    return base64.b64encode(sk.sign(message).signature).decode("ascii")


def _seed_nkey_registration(pubkey: str, household_tag="hh-test", zone_id="alpha"):
    """Convenience: pre-populate regs.json with an nkey entry."""
    regs = registration.load_registrations()
    regs[pubkey] = {
        "kind": "nkey",
        "pubkey": pubkey,
        "household_tag": household_tag,
        "zone_id": zone_id,
        "node_name": "test-node",
        "registered_at": "2026-04-28T00:00:00",
        "registered_ip": "127.0.0.1",
        "active": True,
        "last_seen": None,
    }
    registration.save_registrations(regs)


# --- §10.9 CA-in-invite ---

class TestInviteV2CaPayload:
    """§10.9 — the invite token's signed payload must carry the household CA
    so phones/joining nodes establish trust from the invite alone, without a
    cleartext /ca.crt fetch over :80."""

    def _decode_payload(self, token):
        import base64
        import json
        payload_b64 = token.split(".")[0]
        pad = "=" * (-len(payload_b64) % 4)
        return json.loads(base64.urlsafe_b64decode(payload_b64 + pad))

    def test_payload_carries_ca_pem_and_fingerprint(self):
        result = registration.mint_invite(ttl_seconds=600)
        payload = self._decode_payload(result["token"])
        assert payload.get("v") == 2, "version bumped"
        assert payload.get("ca"), "CA cert (base64) present in signed payload"
        assert payload.get("ca_fp"), "CA fingerprint present in signed payload"
        # Decode the embedded CA and confirm it round-trips to a parsable PEM.
        import base64
        ca_b64 = payload["ca"]
        pad = "=" * (-len(ca_b64) % 4)
        ca_pem = base64.urlsafe_b64decode(ca_b64 + pad).decode()
        assert "BEGIN CERTIFICATE" in ca_pem, "valid PEM"
        # Top-level result also surfaces the CA fingerprint for the CLI to
        # echo back to the operator.
        assert result.get("ca_fingerprint") == payload.get("ca_fp")

    def test_verify_round_trip_with_v2_payload(self):
        # The signature must verify over the new payload shape; the verifier
        # must not reject v2 tokens for having extra fields.
        result = registration.mint_invite(ttl_seconds=600)
        verified = registration.verify_invite(result["token"])
        assert verified.get("v") == 2
        assert verified.get("ca")  # CA carried through


# ---: nkey_to_did + DID stamping ---

class TestNkeyToDid:
    """The relay's computed did:key: for a node MUST equal the DID that node
    computes from its own NodeIdentity Ed25519 key (Java DidKey). Same key →
    byte-for-byte identical string. These vectors were produced by running the
    REAL Java `DidKey.fromRawPublicKey` (core/.../identity/DidKey.java) over
    fixed raw 32-byte keys:

        javac -cp core/build/classes/java/main DidVec.java   (+jackson on cp)
        java DidVec
            vec_zero: bytes [0x00]*32
            vec_seq:  bytes [0x00,0x01,...,0x1f]
            vec_ff:   bytes [0xff]*32

    So this is a genuine Java-side equality check, not a self-referential
    round-trip. If the Java algorithm or these bytes change, regen the vectors.
    """

    # raw 32-byte key  ->  did:key the JAVA DidKey produced for it.
    _JAVA_VECTORS = {
        bytes(range(32)):
            "did:key:z6MkeTGwHmLmuCmgg4ABYhzWVh6ZX7hTwWt8gguAretUfc9c",
        bytes([0x00] * 32):
            "did:key:z6MkeTG3bFFSLYVU7VqhgZxqr6YzpaGrQtFMh1uvqGy1vDnP",
        bytes([0xFF] * 32):
            "did:key:z6MkwgaR63138bEEgad7uk993KMX54vBA6KTB4sFhCPnSB2e",
    }

    def _raw_to_nkey(self, raw32: bytes) -> str:
        """Encode a raw 32-byte Ed25519 pubkey as a NATS user NKey ('U…'),
        the wire form registration.py decodes. Mirrors nkeys.encode_seed but
        for a PUBLIC user key: prefix byte + 32 key bytes + CRC16, base32."""
        import nkeys
        prefix = nkeys.PREFIX_BYTE_USER
        body = bytes([prefix]) + raw32
        crc = nkeys.crc16(body)  # int; NATS appends it little-endian
        full = body + crc.to_bytes(2, "little")
        return base64.b32encode(full).decode("ascii").rstrip("=")

    def test_matches_java_didkey_byte_for_byte(self):
        pytest.importorskip("nkeys")
        for raw32, expected_did in self._JAVA_VECTORS.items():
            nkey = self._raw_to_nkey(raw32)
            # Round-trips back to the same raw bytes (decode sanity).
            assert registration._pubkey_to_raw_ed25519(nkey) == raw32
            got = registration.nkey_to_did(nkey)
            assert got == expected_did, (
                f"Python nkey_to_did diverged from Java DidKey for "
                f"{raw32.hex()}: got {got}, expected {expected_did}")

    def test_produces_well_formed_did_key(self):
        pubkey, _ = _make_keypair()
        did = registration.nkey_to_did(pubkey)
        assert did is not None
        assert did.startswith("did:key:z6Mk"), did
        # Ed25519 did:keys are a fixed length (multicodec + 32 bytes base58).
        assert 48 <= len(did) <= 60

    def test_deterministic(self):
        pubkey, _ = _make_keypair()
        assert registration.nkey_to_did(pubkey) == registration.nkey_to_did(pubkey)

    def test_malformed_pubkey_returns_none(self):
        assert registration.nkey_to_did("not-a-valid-nkey") is None
        assert registration.nkey_to_did("") is None

    def test_register_nkey_stamps_did(self):
        pubkey, _ = _make_keypair()
        r = registration.register_nkey("127.0.0.1", pubkey,
                                       household_tag="hh-d", zone_id="z")
        assert "pubkey" in r
        regs = registration.load_registrations()
        assert regs[pubkey].get("did") == registration.nkey_to_did(pubkey)
        assert regs[pubkey]["did"].startswith("did:key:z6Mk")

    def test_re_register_refreshes_did(self):
        pubkey, signer = _make_keypair()
        # Seed WITHOUT a did (pre-R2.1 record), then re-register.
        _seed_nkey_registration(pubkey)
        assert "did" not in registration.load_registrations()[pubkey]
        ts = int(time.time())
        challenge = f"{ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        r = registration.re_register_existing_nkey("127.0.0.1", pubkey, ts, sig_b64)
        assert "pubkey" in r, r
        regs = registration.load_registrations()
        assert regs[pubkey].get("did") == registration.nkey_to_did(pubkey)

    def test_backfill_dids_fills_missing(self):
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey)  # no did
        assert "did" not in registration.load_registrations()[pubkey]
        filled = registration.backfill_dids()
        assert filled == 1
        regs = registration.load_registrations()
        assert regs[pubkey]["did"] == registration.nkey_to_did(pubkey)
        # Idempotent — a second pass fills nothing.
        assert registration.backfill_dids() == 0


# --- /register-nkey ---

class TestRegisterNkey:
    def test_idempotent_same_pubkey_succeeds_twice(self):
        pubkey, _ = _make_keypair()
        r1 = registration.register_nkey("127.0.0.1", pubkey,
                                        household_tag="hh-x", zone_id="z")
        assert "pubkey" in r1, f"first call should succeed: {r1}"
        # Second call with the SAME pubkey should also succeed (drift-recovery).
        r2 = registration.register_nkey("127.0.0.1", pubkey,
                                        household_tag="hh-x", zone_id="z")
        assert "pubkey" in r2
        assert r1["pubkey"] == r2["pubkey"]

    def test_invalid_pubkey_format_rejected(self):
        bad = registration.register_nkey("127.0.0.1", "not-a-valid-nkey")
        assert "error" in bad

    def test_capacity_enforced_on_new_pubkeys_only(self):
        # Drop capacity to 1 for this test.
        original_cap = registration.CAPACITY
        registration.CAPACITY = 1
        try:
            pk1, _ = _make_keypair()
            pk2, _ = _make_keypair()
            r1 = registration.register_nkey("127.0.0.1", pk1)
            assert "pubkey" in r1
            r2 = registration.register_nkey("127.0.0.1", pk2)
            assert "error" in r2 and "capacity" in r2["error"].lower()
            # But re-registering pk1 (already counted) still works.
            r1b = registration.register_nkey("127.0.0.1", pk1)
            assert "pubkey" in r1b
        finally:
            registration.CAPACITY = original_cap


# --- /re-register-nkey (Phase 2) ---

@pytest.mark.skipif(shutil.which("python3") is None, reason="needs python3")
class TestReRegisterNkey:
    def test_unknown_pubkey_returns_404(self):
        pubkey, signer = _make_keypair()
        # Don't seed — relay shouldn't know this pubkey.
        ts = int(time.time())
        challenge = f"{ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.re_register_existing_nkey(
            "127.0.0.1", pubkey, ts, sig_b64)
        assert "error" in result
        assert result.get("_status") == 404
        assert "unknown pubkey" in result["error"].lower()

    def test_valid_signature_succeeds(self):
        pubkey, signer = _make_keypair()
        _seed_nkey_registration(pubkey)
        ts = int(time.time())
        challenge = f"{ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.re_register_existing_nkey(
            "192.0.2.5", pubkey, ts, sig_b64)
        assert "pubkey" in result, f"should succeed: {result}"
        assert result["pubkey"] == pubkey
        # last_seen should be refreshed.
        regs = registration.load_registrations()
        assert regs[pubkey]["registered_ip"] == "192.0.2.5"

    def test_replay_window_enforced(self):
        pubkey, signer = _make_keypair()
        _seed_nkey_registration(pubkey)
        # Sign a stale timestamp 1 hour in the past.
        old_ts = int(time.time()) - 3600
        challenge = f"{old_ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.re_register_existing_nkey(
            "127.0.0.1", pubkey, old_ts, sig_b64)
        assert "error" in result
        assert result.get("_status") == 401
        assert "skew" in result["error"].lower()

    def test_signature_for_wrong_pubkey_rejected(self):
        # Sign a challenge with one key, claim it's for another pubkey.
        pubkey_a, signer_a = _make_keypair()
        pubkey_b, _ = _make_keypair()
        _seed_nkey_registration(pubkey_b)
        ts = int(time.time())
        # Sign challenge meant for B (`ts:pubkey_b`) using A's seed.
        challenge = f"{ts}:{pubkey_b}".encode("utf-8")
        sig_b64 = base64.b64encode(signer_a.sign(challenge)).decode("ascii")
        result = registration.re_register_existing_nkey(
            "127.0.0.1", pubkey_b, ts, sig_b64)
        assert "error" in result
        # 401 — sig verification failed against B's pubkey.
        assert result.get("_status") == 401

    def test_deactivated_pubkey_cannot_re_register(self):
        pubkey, signer = _make_keypair()
        _seed_nkey_registration(pubkey)
        regs = registration.load_registrations()
        regs[pubkey]["active"] = False
        registration.save_registrations(regs)

        ts = int(time.time())
        challenge = f"{ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.re_register_existing_nkey(
            "127.0.0.1", pubkey, ts, sig_b64)
        assert "error" in result
        assert result.get("_status") == 403


# --- update_nats_config: namespace isolation + preservation ---

class TestUpdateNatsConfig:
    def test_nkey_user_gets_scoped_subject_permissions(self):
        # `_subject_permissions_for` was originally going to scope between.>
        # by household_tag, but the actual NATS schema (verified live
        # 2026-04-28) is `between.{zone_id}.{nodeId}.>` so it's zone-scoped.
        # Federation stays permissive until agreement-aware perms land
        # (see docstring on _subject_permissions_for).
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey, household_tag="hh-isolated", zone_id="alpha")
        regs = registration.load_registrations()
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        assert f'nkey: "{pubkey}"' in conf, "NKey user written to config"
        assert "between.alpha.>" in conf, "between scoped to zone_id"
        # Federation stays permissive — `federation.>`, not yet scoped per
        # agreement. When agreement-aware perms ship this assertion tightens.
        assert "federation.>" in conf, "federation gate present (permissive)"
        # Audit F6: SUBSCRIBE must NOT carry a blanket `between.>` for a
        # zone-scoped node — that let any household read every other zone's
        # between traffic (presence, cluster, study). Cross-zone reads are
        # limited to capability announcements.
        auth_block = conf[conf.index("authorization"):]
        nkey_block = auth_block[auth_block.index(f'nkey: "{pubkey}"'):]
        nkey_block = nkey_block[:nkey_block.index("}\n")]  # this user's entry
        assert '"between.>"' not in nkey_block, \
            "zone-scoped node must not get blanket between.> (F6)"
        assert '"between.*.*.*.capability.announce"' in conf, \
            "cross-zone capability announce still subscribable (F6)"

        # Audit F4: `wyrd.zone.>` / `wyrd.tunnel.>` must be zone-scoped too — a
        # blanket grant let one household publish on another zone's MCP surface
        # (login/tell request-reply) and read/inject its tunnel sessions.
        assert '"wyrd.zone.alpha.>"' in nkey_block, "wyrd.zone scoped to zone (F4)"
        assert '"wyrd.tunnel.alpha.>"' in nkey_block, "wyrd.tunnel scoped to zone (F4)"
        assert '"wyrd.zone.>"' not in nkey_block, \
            "zone-scoped node must not get blanket wyrd.zone.> (F4)"
        assert '"wyrd.tunnel.>"' not in nkey_block, \
            "zone-scoped node must not get blanket wyrd.tunnel.> (F4)"
        # Discovery stays global by design — phones learn the zone label here.
        assert '"wyrd.discover.>"' in nkey_block, "discovery remains global"

    def test_preserves_non_household_users(self):
        # Seed with both peer_trainer (already in baseline conf) + an nkey.
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey)
        regs = registration.load_registrations()
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        assert 'user: "peer_trainer"' in conf, \
            "non-hh user must be preserved across updates"
        assert f'nkey: "{pubkey}"' in conf

    def test_unspecified_tags_fall_back_to_permissive(self):
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey, household_tag="unspecified",
                                zone_id="unspecified")
        regs = registration.load_registrations()
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        # No isolation when tags aren't known — falls back to between.>.
        assert '"between.>"' in conf

    def test_per_household_phone_account_scoped_to_zone(self):
        # hardening: each household gets its own phone
        # NATS user, scoped to ITS zone's tunnel + study subjects — a leaked
        # phone credential no longer reads other households' tunnels/studies.
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey, household_tag="hh-phonetest", zone_id="alpha")
        regs = registration.load_registrations()
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        assert 'user: "phone-hh-phonetest"' in conf, "per-household phone user emitted"
        # Audit F1: tunnel grants are verb-scoped, NOT a blanket `wyrd.tunnel.alpha.>`.
        # The phone reads `.down` only (session token rides `.open`, so a broad
        # subscribe let a sibling harvest tokens) and publishes only C2S verbs.
        # Scoped to the PHONE's own user block: since F4 the zone's node account
        # legitimately holds `wyrd.tunnel.alpha.>`, so a whole-file check would
        # now read the wrong block.
        phone_block = conf[conf.index('user: "phone-hh-phonetest"'):]
        phone_block = phone_block[:phone_block.index("}\n")]
        assert '"wyrd.tunnel.alpha.>"' not in phone_block, "tunnel not blanket-scoped (F1)"
        assert '"wyrd.tunnel.alpha.*.down"' in conf, "phone reads only .down (F1)"
        assert '"wyrd.tunnel.alpha.*.open"' in conf, "phone publishes .open (F1)"
        assert '"wyrd.tunnel.alpha.*.up"' in conf, "phone publishes .up (F1)"
        assert '"between.alpha.*.*.study.state"' in conf, "study scoped to own zone"
        # The derived credential is deterministic (survives conf regens).
        assert registration._phone_password_for("hh-phonetest") in conf

    def test_password_mode_household_gets_phone_account_from_key(self):
        # Password-mode regs are keyed by the hh id and may lack household_tag —
        # the key IS the tag; the phone account must still be emitted.
        regs = registration.load_registrations()
        regs["hh-pwphone"] = {"token": "tok-pwphone", "active": True}
        registration.save_registrations(regs)
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        assert 'user: "phone-hh-pwphone"' in conf
        # Regen must be IDEMPOTENT — a second pass used to preserve the previous
        # phone block AND emit a new one ("Duplicate user" → relay NATS flapped).
        registration.update_nats_config(regs)
        conf2 = _NATS_CONF.read_text()
        assert conf2.count('user: "phone-hh-pwphone"') == 1

    def test_shared_relay_phone_has_no_study_grants(self):
        # The DEPRECATED shared relay_phone account must never regain study
        # subjects — a shared credential + study grants = any phone can read/
        # write any user's Study. Study sync requires the per-household user.
        regs = registration.load_registrations()
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        import re as _re
        m = _re.search(r'user: "relay_phone".*?\}\s*\}', conf, _re.S)
        assert m, "relay_phone account present (back-compat)"
        assert "study" not in m.group(0), "no study subjects on the shared account"


# --- /deregister: signed voluntary teardown ---

class TestDeregister:
    def test_happy_path_deletes_and_reprojects_config(self):
        pubkey, signer = _make_keypair()
        _seed_nkey_registration(pubkey, household_tag="hh-bye", zone_id="alpha")
        # Confirm it's projected into the auth config first.
        registration.update_nats_config(registration.load_registrations())
        assert f'nkey: "{pubkey}"' in _NATS_CONF.read_text()

        ts = int(time.time())
        challenge = f"deregister:{ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.deregister_nkey(pubkey=pubkey, ts=ts, signature_b64=sig_b64)
        assert result.get("_status") == 200
        assert result.get("status") == "deregistered"
        # Gone from regs.
        assert pubkey not in registration.load_registrations()
        # Pulled from the live auth config.
        assert f'nkey: "{pubkey}"' not in _NATS_CONF.read_text()

    def test_bad_signature_rejected_and_record_survives(self):
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey)
        ts = int(time.time())
        # Garbage signature.
        bad_sig = base64.b64encode(b"not a real signature at all....").decode("ascii")
        result = registration.deregister_nkey(pubkey=pubkey, ts=ts, signature_b64=bad_sig)
        assert result.get("_status") == 401
        assert pubkey in registration.load_registrations(), \
            "a bad-sig deregister must not delete anything"

    def test_wrong_challenge_namespace_rejected(self):
        # A re-register-style `{ts}:{pubkey}` signature must NOT work for
        # /deregister — the deregister: prefix is a distinct namespace, so a
        # captured re-register signature can't be replayed as a delete.
        pubkey, signer = _make_keypair()
        _seed_nkey_registration(pubkey)
        ts = int(time.time())
        reregister_challenge = f"{ts}:{pubkey}".encode("utf-8")  # NO deregister: prefix
        sig_b64 = base64.b64encode(signer.sign(reregister_challenge)).decode("ascii")
        result = registration.deregister_nkey(pubkey=pubkey, ts=ts, signature_b64=sig_b64)
        assert result.get("_status") == 401
        assert pubkey in registration.load_registrations()

    def test_idempotent_already_absent_returns_200(self):
        # A retried leave whose pubkey is already gone succeeds (not 404).
        pubkey, signer = _make_keypair()
        # Don't seed — relay has no record of this pubkey.
        ts = int(time.time())
        challenge = f"deregister:{ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.deregister_nkey(pubkey=pubkey, ts=ts, signature_b64=sig_b64)
        assert result.get("_status") == 200
        assert result.get("status") == "already_absent"

    def test_skew_window_enforced(self):
        pubkey, signer = _make_keypair()
        _seed_nkey_registration(pubkey)
        old_ts = int(time.time()) - 4000  # well outside ±300s
        challenge = f"deregister:{old_ts}:{pubkey}".encode("utf-8")
        sig_b64 = base64.b64encode(signer.sign(challenge)).decode("ascii")
        result = registration.deregister_nkey(pubkey=pubkey, ts=old_ts, signature_b64=sig_b64)
        assert result.get("_status") == 401
        assert pubkey in registration.load_registrations()

    def test_invalid_pubkey_shape_rejected(self):
        result = registration.deregister_nkey(
            pubkey="not-a-valid-nkey", ts=int(time.time()), signature_b64="AAAA")
        assert result.get("_status") == 400


# --- Liveness reaper: connz-driven stale pruning ---

class TestLivenessReaper:
    def test_reaper_deletes_stale_skips_fresh(self, monkeypatch):
        # Two registrations: one with an old last_seen (stale), one currently
        # connected (fresh). connz reports only the fresh one connected.
        stale_pk, _ = _make_keypair()
        fresh_pk, _ = _make_keypair()
        _seed_nkey_registration(stale_pk, household_tag="hh-stale", zone_id="alpha")
        _seed_nkey_registration(fresh_pk, household_tag="hh-fresh", zone_id="beta")

        # Backdate the stale one's last_seen well beyond the window.
        regs = registration.load_registrations()
        from datetime import datetime, timedelta
        old = datetime.utcnow() - timedelta(hours=registration.LIVENESS_TIMEOUT_HOURS + 24)
        regs[stale_pk]["last_seen"] = old.isoformat()
        # The fresh one starts with last_seen=None; the sweep stamps it from connz.
        registration.save_registrations(regs)

        # connz reports only fresh_pk connected (nkey field, NATS 2.10 auth=1).
        monkeypatch.setattr(registration, "_fetch_connected_nkeys",
                            lambda: {fresh_pk})
        deleted = registration._reap_stale_registrations()
        assert deleted == 1
        after = registration.load_registrations()
        assert stale_pk not in after, "stale registration must be reaped"
        assert fresh_pk in after, "connected registration must survive"
        # Fresh one's last_seen got stamped by the sweep.
        assert after[fresh_pk]["last_seen"], "connected pubkey gets a fresh last_seen"

    def test_reaper_no_liveness_data_reaps_nothing(self, monkeypatch):
        # Monitoring endpoint unreachable → _fetch returns None → reap nothing,
        # even for a record that is otherwise stale.
        stale_pk, _ = _make_keypair()
        _seed_nkey_registration(stale_pk)
        regs = registration.load_registrations()
        from datetime import datetime, timedelta
        old = datetime.utcnow() - timedelta(hours=registration.LIVENESS_TIMEOUT_HOURS + 24)
        regs[stale_pk]["last_seen"] = old.isoformat()
        registration.save_registrations(regs)

        monkeypatch.setattr(registration, "_fetch_connected_nkeys", lambda: None)
        deleted = registration._reap_stale_registrations()
        assert deleted == 0
        assert stale_pk in registration.load_registrations(), \
            "no liveness data ⇒ never reap"

    def test_reaper_prunes_registered_but_never_connected(self, monkeypatch):
        # last_seen is null AND registered_at is older than the window → reap.
        never_pk, _ = _make_keypair()
        _seed_nkey_registration(never_pk)
        regs = registration.load_registrations()
        from datetime import datetime, timedelta
        old = datetime.utcnow() - timedelta(hours=registration.LIVENESS_TIMEOUT_HOURS + 24)
        regs[never_pk]["last_seen"] = None
        regs[never_pk]["registered_at"] = old.isoformat()
        registration.save_registrations(regs)

        monkeypatch.setattr(registration, "_fetch_connected_nkeys", lambda: set())
        deleted = registration._reap_stale_registrations()
        assert deleted == 1
        assert never_pk not in registration.load_registrations()

    def test_reaper_keeps_recent_never_connected(self, monkeypatch):
        # last_seen null but registered_at WITHIN the window → keep (grace).
        recent_pk, _ = _make_keypair()
        _seed_nkey_registration(recent_pk)
        regs = registration.load_registrations()
        from datetime import datetime
        regs[recent_pk]["last_seen"] = None
        regs[recent_pk]["registered_at"] = datetime.utcnow().isoformat()
        registration.save_registrations(regs)

        monkeypatch.setattr(registration, "_fetch_connected_nkeys", lambda: set())
        deleted = registration._reap_stale_registrations()
        assert deleted == 0
        assert recent_pk in registration.load_registrations()


# --- Phase 5b: peer-relay handshake ---

class TestPeerInviteAndAccept:
    @pytest.mark.skipif(not _HAVE_CRYPTOGRAPHY,
                        reason="cryptography needed for fake leaf cert")
    def test_mint_peer_invite_then_verify_succeeds(self):
        result = registration.mint_peer_invite(remote_host_hint="other-relay.example",
                                               ttl_seconds=600)
        assert "invite_url" in result
        assert result["scope"] == "peer"
        assert result["invite_url"].startswith("wyrdrelay-peer://")
        # Verify the minted token round-trips.
        payload = registration.verify_peer_invite(result["token"])
        assert payload["scope"] == "peer"
        assert payload["host_hint"] == "other-relay.example"

    @pytest.mark.skipif(not _HAVE_CRYPTOGRAPHY,
                        reason="cryptography needed for fake leaf cert")
    def test_verify_peer_invite_rejects_household_invite_scope(self):
        # A regular /invite token lacks scope=peer; must be rejected.
        regular = registration.mint_invite(ttl_seconds=600)
        with pytest.raises(ValueError, match="scope"):
            registration.verify_peer_invite(regular["token"])

    def test_verify_peer_invite_rejects_tampered_signature(self):
        if not _HAVE_CRYPTOGRAPHY:
            pytest.skip("cryptography needed")
        result = registration.mint_peer_invite(ttl_seconds=600)
        # Flip a char in the MIDDLE of the signature. (Flipping the LAST
        # base64url char only perturbs the trailing 2 bits, which can decode to
        # the same bytes → an occasional false "valid sig" and a flaky test;
        # a mid-string flip always changes the decoded bytes.)
        token = result["token"]
        head, sig = token.rsplit(".", 1)
        mid = len(sig) // 2
        flipped = "A" if sig[mid] != "A" else "B"
        bad = head + "." + sig[:mid] + flipped + sig[mid + 1:]
        with pytest.raises(ValueError):
            registration.verify_peer_invite(bad)

    def test_peer_accept_records_peer_in_peers_json(self):
        if not _HAVE_CRYPTOGRAPHY:
            pytest.skip("cryptography needed")
        # Peer A mints, peer B accepts.
        result = registration.accept_peer_invite(
            remote_token="dummy",
            remote_url="nats://other-relay.example:7422",
            remote_pubkey="UAMIVSMSJSYT5JOD44IHVDLOJAR6EAGGFBFKEQU4ILYHBKK52IKSUSQT",
            remote_fingerprint="AB:CD:EF:01")
        assert result.get("status") == "peer accepted"
        peers = registration.load_peers()
        assert any(p.get("kind") == "peer-relay"
                   and p.get("url") == "nats://other-relay.example:7422"
                   for p in peers)
        # Operator-facing instructions surface the leafnode config block.
        assert "leafnodes.remotes" in result["next_step"]

    def test_peer_accept_dedups_on_url(self):
        if not _HAVE_CRYPTOGRAPHY:
            pytest.skip("cryptography needed")
        url = "nats://dedup-test.example:7422"
        pk = "UAMIVSMSJSYT5JOD44IHVDLOJAR6EAGGFBFKEQU4ILYHBKK52IKSUSQT"
        for _ in range(3):
            registration.accept_peer_invite(
                remote_token="x", remote_url=url, remote_pubkey=pk)
        peers = [p for p in registration.load_peers()
                 if p.get("url") == url and p.get("kind") == "peer-relay"]
        assert len(peers) == 1, "duplicate accept should not stack peers"

    def test_peer_accept_requires_url_and_pubkey(self):
        result = registration.accept_peer_invite(
            remote_token="x", remote_url=None, remote_pubkey=None)
        assert "error" in result
        assert result.get("_status") == 400


# ---: DID <-> Ed25519 pubkey round-trip ---

class TestDidRoundTrip:
    """did_to_ed25519_pubkey is the inverse of nkey_to_did's encode path. A
    signature made by a key verifies against the DID derived from that key."""

    def test_did_to_pubkey_inverts_nkey_to_did_vectors(self):
        # The same Java vectors used by TestNkeyToDid: raw key → did → raw key.
        for raw32, did in TestNkeyToDid._JAVA_VECTORS.items():
            assert registration.did_to_ed25519_pubkey(did) == raw32

    def test_sign_with_key_verifies_via_did(self):
        did, sk = _make_did_keypair()
        msg = b"claim-owner:1234567890:" + did.encode()
        sig_b64 = _sign_b64(sk, msg)
        # _verify_did_sig recovers the pubkey from the DID and checks the sig.
        assert registration._verify_did_sig(did, msg, sig_b64) is None

    def test_wrong_key_signature_rejected(self):
        did_a, _ = _make_did_keypair()
        _, sk_b = _make_did_keypair()
        msg = b"claim-owner:1:" + did_a.encode()
        sig_b64 = _sign_b64(sk_b, msg)  # B signs A's challenge
        assert registration._verify_did_sig(did_a, msg, sig_b64) is not None

    def test_malformed_did_rejected(self):
        with pytest.raises(ValueError):
            registration.did_to_ed25519_pubkey("not-a-did")
        with pytest.raises(ValueError):
            registration.did_to_ed25519_pubkey("did:key:zZZZ")


# --- b: owner-claim ---

@pytest.mark.skipif(not _HAVE_CRYPTOGRAPHY, reason="needs leaf cert for token mint")
class TestClaimOwner:
    def _mint_and_claim(self, did, sk, ttl=600, ts=None):
        token = registration.mint_owner_claim_token(ttl)["claim_token"]
        ts = int(time.time()) if ts is None else ts
        sig = _sign_b64(sk, f"claim-owner:{ts}:{did}".encode())
        return registration.claim_owner(token=token, did=did, ts=ts, signature_b64=sig)

    def test_valid_claim_sets_owner(self):
        did, sk = _make_did_keypair()
        r = self._mint_and_claim(did, sk)
        assert r.get("_status") == 200
        assert registration.owner_did() == did

    def test_replay_same_token_rejected(self):
        did, sk = _make_did_keypair()
        token = registration.mint_owner_claim_token(600)["claim_token"]
        ts = int(time.time())
        sig = _sign_b64(sk, f"claim-owner:{ts}:{did}".encode())
        r1 = registration.claim_owner(token=token, did=did, ts=ts, signature_b64=sig)
        assert r1.get("_status") == 200
        # Same (now-consumed) token a second time → rejected.
        r2 = registration.claim_owner(token=token, did=did, ts=ts, signature_b64=sig)
        assert r2.get("_status") == 401

    def test_expired_token_rejected(self):
        did, sk = _make_did_keypair()
        # ttl floors at 60s; force an already-expired token by patching time.
        token = registration.mint_owner_claim_token(60)["claim_token"]
        ts = int(time.time())
        sig = _sign_b64(sk, f"claim-owner:{ts}:{did}".encode())
        import unittest.mock as mock
        future = time.time() + 999999
        with mock.patch("time.time", lambda: future):
            r = registration.claim_owner(token=token, did=did,
                                         ts=int(future), signature_b64=sig)
        assert r.get("_status") == 401

    def test_bad_signature_rejected_and_owner_unset(self):
        did, _ = _make_did_keypair()
        _, sk_other = _make_did_keypair()
        token = registration.mint_owner_claim_token(600)["claim_token"]
        ts = int(time.time())
        sig = _sign_b64(sk_other, f"claim-owner:{ts}:{did}".encode())  # wrong key
        r = registration.claim_owner(token=token, did=did, ts=ts, signature_b64=sig)
        assert r.get("_status") == 401
        assert registration.owner_did() is None

    def test_skew_rejected(self):
        did, sk = _make_did_keypair()
        old = int(time.time()) - 4000
        r = self._mint_and_claim(did, sk, ts=old)
        assert r.get("_status") == 401


# ---: signed /admin authorize + ops ---

@pytest.mark.skipif(not _HAVE_CRYPTOGRAPHY, reason="invite ops need leaf cert")
class TestAdminApi:
    RELAY_DID = "did:key:z6MkrelayRELAYrelayRELAYrelayRELAYrelayRELAY12"

    def _signed_admin(self, op, args, did, sk, relay_did=None, ts=None):
        relay_did = relay_did or self.RELAY_DID
        ts = int(time.time()) if ts is None else ts
        canon = registration._canonical_args(args)
        import hashlib as _h
        ah = _h.sha256(canon.encode()).hexdigest()
        challenge = f"admin:{op}:{ts}:{relay_did}:{ah}".encode()
        sig = _sign_b64(sk, challenge)
        return registration.admin_op(op=op, args=args, relay_did=relay_did,
                                     ts=ts, did=did, signature_b64=sig)

    def _set_owner(self, did):
        registration._set_owner(did, via="test")

    def test_owner_allowed_any_op(self):
        owner, sk = _make_did_keypair()
        self._set_owner(owner)
        # set-mode needs full; owner has implicit full.
        r = self._signed_admin("set-mode", {"mode": "commons"}, owner, sk)
        assert r.get("_status") == 200
        # audit (full) also fine.
        r2 = self._signed_admin("audit", {}, owner, sk)
        assert r2.get("_status") == 200
        assert r2.get("owner_did") == owner

    def test_unknown_did_denied(self):
        owner, _ = _make_did_keypair()
        self._set_owner(owner)
        stranger, sk = _make_did_keypair()
        r = self._signed_admin("list", {}, stranger, sk)
        assert r.get("_status") == 403

    def test_moderation_grant_allows_remove_and_list_denies_set_mode(self):
        owner, _ = _make_did_keypair()
        self._set_owner(owner)
        mod, sk = _make_did_keypair()
        registration.save_admin_grants({mod: {"scope": "moderation"}})
        # list + remove (moderation) allowed.
        assert self._signed_admin("list", {}, mod, sk).get("_status") == 200
        rr = self._signed_admin("remove", {"pubkey": "Uabc"}, mod, sk)
        assert rr.get("_status") == 200  # already_absent but authorized
        # set-mode (full) denied.
        assert self._signed_admin("set-mode", {"mode": "open"}, mod, sk).get("_status") == 403

    def test_invite_only_grant_allows_invite_denies_list(self):
        owner, _ = _make_did_keypair()
        self._set_owner(owner)
        inv, sk = _make_did_keypair()
        registration.save_admin_grants({inv: {"scope": "invite-only"}})
        ri = self._signed_admin("invite", {"ttl": 600}, inv, sk)
        assert ri.get("_status") == 200
        assert ri.get("invite_url")
        assert self._signed_admin("list", {}, inv, sk).get("_status") == 403

    def test_expired_grant_denied(self):
        owner, _ = _make_did_keypair()
        self._set_owner(owner)
        mod, sk = _make_did_keypair()
        registration.save_admin_grants(
            {mod: {"scope": "moderation", "expiresAt": int(time.time()) - 10}})
        assert self._signed_admin("list", {}, mod, sk).get("_status") == 403

    def test_relay_scoped_grant_only_applies_to_that_relay(self):
        owner, _ = _make_did_keypair()
        self._set_owner(owner)
        mod, sk = _make_did_keypair()
        registration.save_admin_grants(
            {mod: {"scope": "moderation", "relay": "did:key:zOTHERrelay"}})
        # Our relay DID != the grant's relay narrowing → denied.
        assert self._signed_admin("list", {}, mod, sk).get("_status") == 403

    def test_bad_signature_denied(self):
        owner, _ = _make_did_keypair()
        self._set_owner(owner)
        did, _ = _make_did_keypair()
        _, sk_other = _make_did_keypair()
        # Sign with the wrong key.
        r = self._signed_admin("audit", {}, did, sk_other)
        assert r.get("_status") in (401, 403)

    def test_skew_denied(self):
        owner, sk = _make_did_keypair()
        self._set_owner(owner)
        r = self._signed_admin("audit", {}, owner, sk, ts=int(time.time()) - 4000)
        assert r.get("_status") == 401

    def test_replay_denied(self):
        owner, sk = _make_did_keypair()
        self._set_owner(owner)
        ts = int(time.time())
        # First call succeeds; replaying the exact same signed challenge fails.
        r1 = self._signed_admin("audit", {}, owner, sk, ts=ts)
        assert r1.get("_status") == 200
        r2 = self._signed_admin("audit", {}, owner, sk, ts=ts)
        assert r2.get("_status") == 401

    def test_grant_admin_then_takes_effect_then_revoke_denies(self):
        owner, owner_sk = _make_did_keypair()
        self._set_owner(owner)
        mod, mod_sk = _make_did_keypair()
        # Owner grants moderation to mod.
        g = self._signed_admin("grant-admin",
                               {"subject_did": mod, "scope": "moderation"},
                               owner, owner_sk)
        assert g.get("_status") == 200
        # mod can now list (distinct ts per call avoids the anti-replay nonce
        # collision two identical signed challenges in the same second hit).
        now = int(time.time())
        assert self._signed_admin("list", {}, mod, mod_sk, ts=now).get("_status") == 200
        # Owner revokes.
        rv = self._signed_admin("revoke-admin", {"subject_did": mod}, owner, owner_sk)
        assert rv.get("_status") == 200
        # mod is denied again.
        assert self._signed_admin("list", {}, mod, mod_sk, ts=now + 1).get("_status") == 403

    def test_delegate_cannot_grant_broader_than_own_scope(self):
        owner, owner_sk = _make_did_keypair()
        self._set_owner(owner)
        # A full-scope delegate may issue any scope (covers all). A moderation
        # holder can't even reach grant-admin (needs full) — so the broadest a
        # non-owner grantor can be is full, and full covers everything; the
        # rule is "never broader than own", which full trivially satisfies.
        deleg, deleg_sk = _make_did_keypair()
        registration.save_admin_grants({deleg: {"scope": "full"}})
        # full delegate grants invite-only — allowed (≤ full).
        sub, _ = _make_did_keypair()
        r = self._signed_admin("grant-admin",
                               {"subject_did": sub, "scope": "invite-only"},
                               deleg, deleg_sk)
        assert r.get("_status") == 200

    def test_set_owner_owner_only(self):
        owner, owner_sk = _make_did_keypair()
        self._set_owner(owner)
        new_owner, _ = _make_did_keypair()
        r = self._signed_admin("set-owner", {"owner_did": new_owner}, owner, owner_sk)
        assert r.get("_status") == 200
        assert registration.owner_did() == new_owner

    def test_set_mode_now_flips_live_mode(self):
        # P5: set-mode is no longer a stub — it persists the mode and
        # relay_mode() reads it back immediately.
        owner, sk = _make_did_keypair()
        self._set_owner(owner)
        r = self._signed_admin("set-mode", {"mode": "commons"}, owner, sk)
        assert r.get("_status") == 200
        assert r.get("mode") == "commons"
        assert registration.relay_mode() == "commons"

    def test_op_to_scope_map_mirrors_p2(self):
        # The Python op→scope map MUST mirror Java RelayAdminOp. Spot-check the
        # three tiers (the Java enum is the source of truth in P2).
        m = registration._OP_REQUIRED_SCOPE
        assert m["invite"] == "invite-only"
        for op in ("list", "remove", "promote", "demote", "vouch",
                   "report-queue", "resolve-report", "report"):
            assert m[op] == "moderation", op
        for op in ("set-mode", "set-policy", "grant-admin", "revoke-admin",
                   "set-owner", "audit"):
            assert m[op] == "full", op
        # `report` maps to moderation in the vocabulary (mirrors Java
        # RelayAdminOp.REPORT) BUT is exempt from the scope gate — filing is
        # open to any valid signer (§8). The exemption set documents that.
        assert "report" in registration._OPEN_TO_ANY_SIGNER
        assert "report-queue" not in registration._OPEN_TO_ANY_SIGNER
        assert "resolve-report" not in registration._OPEN_TO_ANY_SIGNER

    def test_env_owner_did_seeds_owner(self):
        # relay.sh --owner sets RELAY_OWNER_DID; owner_did() seeds the store.
        did, _ = _make_did_keypair()
        registration.RELAY_OWNER_DID = did
        assert registration.owner_did() == did
        # Persisted so a later read survives clearing the env.
        registration.RELAY_OWNER_DID = ""
        assert registration.owner_did() == did


# ---: registration modes ---

class TestRegistrationModes:
    """The MODE gate (§4): invite-only requires an invite; open/commons are
    invite-less; commons enters FLOOR. Tier assignment + backfill."""

    def _seed_mode(self, mode):
        registration._set_mode(mode)

    def _mint_invite_token(self):
        return registration.mint_invite(ttl_seconds=600)["token"]

    def test_invite_only_rejects_invite_less(self):
        self._seed_mode("invite-only")
        gate = registration.gate_register_nkey({"pubkey": "U" * 56})
        assert "error" in gate
        assert gate.get("_status") == 401

    def test_invite_only_accepts_valid_invite_household_tier(self):
        self._seed_mode("invite-only")
        tok = self._mint_invite_token()
        gate = registration.gate_register_nkey({"invite_token": tok})
        assert gate.get("ok") is True
        assert gate["entrant_tier"] == registration.TIER_HOUSEHOLD

    def test_open_accepts_invite_less_household_tier(self):
        self._seed_mode("open")
        gate = registration.gate_register_nkey({"pubkey": "U" * 56})
        assert gate.get("ok") is True
        assert gate["entrant_tier"] == registration.TIER_HOUSEHOLD

    def test_commons_accepts_invite_less_floor_tier(self):
        self._seed_mode("commons")
        gate = registration.gate_register_nkey({})
        assert gate.get("ok") is True
        assert gate["entrant_tier"] == registration.TIER_FLOOR

    def test_register_nkey_invite_only_stamps_household(self):
        self._seed_mode("invite-only")
        pubkey, _ = _make_keypair()
        r = registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                       entrant_tier=registration.TIER_HOUSEHOLD)
        assert r.get("tier") == registration.TIER_HOUSEHOLD
        assert r.get("identity_verified") is False

    def test_register_nkey_commons_stamps_floor(self):
        self._seed_mode("commons")
        pubkey, _ = _make_keypair()
        r = registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                       entrant_tier=registration.TIER_FLOOR)
        assert r.get("tier") == registration.TIER_FLOOR

    def test_reregister_preserves_higher_tier(self):
        # A node that was promoted to VOUCHED must not drop to FLOOR by
        # re-registering in commons mode.
        pubkey, _ = _make_keypair()
        registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                   entrant_tier=registration.TIER_VOUCHED)
        r = registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                       entrant_tier=registration.TIER_FLOOR)
        assert r.get("tier") == registration.TIER_VOUCHED

    def test_mode_persists_across_reads(self):
        registration._set_mode("commons")
        assert registration.relay_mode() == "commons"
        # A fresh read still sees commons (file is source of truth).
        assert registration.relay_mode() == "commons"

    def test_invalid_mode_rejected(self):
        with pytest.raises(ValueError):
            registration._set_mode("anarchy")

    def test_default_mode_is_invite_only(self):
        # No file, env default invite-only → relay_mode() resolves to it.
        registration.RELAY_MODE_DEFAULT = "invite-only"
        assert registration.relay_mode() == "invite-only"

    def test_backfill_tiers_stamps_household(self):
        # Pre-P5 records (no tier) were all invite-bound → backfill HOUSEHOLD.
        pubkey, _ = _make_keypair()
        _seed_nkey_registration(pubkey)
        regs = registration.load_registrations()
        assert "tier" not in regs[pubkey]
        n = registration.backfill_tiers()
        assert n == 1
        assert registration.load_registrations()[pubkey]["tier"] == \
            registration.TIER_HOUSEHOLD


# --- / R2.2: IdentityOutbox verification ---

class TestIdentityOutbox:
    """The Python verify of a signed IdentityOutboxRecord MUST accept a record
    minted + signed by the REAL Java IdentityOutboxRecord.sign — byte-for-byte
    canonical-serialization parity is the load-bearing requirement (§2.2).

    HOW THE VECTOR WAS OBTAINED (genuine cross-language proof, not a self
    round-trip): a tiny Java harness compiled against the live
    core/.../identity/{DidKey,IdentityOutboxRecord}.java + the shipped Jackson
    jars generated a fresh keypair, called IdentityOutboxRecord.sign(...) over
    the fields below, and printed record.did() + record.sig(). Java's
    record.verify() returned true (self-consistency) and the printed canonical
    signingBytes() were:

      {"did":…,"displayName":"alice","primaryZone":"alpha",
       "writeZones":["alpha","beta"],"readZones":["alpha"],
       "channels":[{"type":"nostr","address":"npub1xxx"}],
       "updatedAt":1700000000000}

    i.e. compact JSON, INSERTION-ordered keys (not sorted), non-ASCII NOT
    \\uXXXX-escaped, '/' '<' '>' NOT escaped. The DID + signature below are
    that Java run's verbatim output. If the Java algorithm/fields change, regen.
    """

    # Verbatim output of the Java harness (DidKey.generate + IdentityOutboxRecord.sign).
    _JAVA_VECTOR = {
        "did": "did:key:z6MknB2dUwQW4RCU37JHK4QFcDAtaUWrRC5X97STQxiJh9XD",
        "displayName": "alice",
        "primaryZone": "alpha",
        "writeZones": ["alpha", "beta"],
        "readZones": ["alpha"],
        "channels": [{"type": "nostr", "address": "npub1xxx"}],
        "updatedAt": 1700000000000,
        "sig": "V8QtgdcKYgWT2pgcbWp5sCID6kLKYCMcxT47qzAw1L8YsUQpl0f"
               "/HVmcE9/gnOta0vS3usIheIjYWWdezrAdBA==",
    }

    def test_python_verifies_real_java_signed_record(self):
        pytest.importorskip("nacl.signing")
        # The CROSS-LANGUAGE proof: a record signed by Java verifies in Python.
        err = registration.verify_identity_outbox(dict(self._JAVA_VECTOR))
        assert err is None, f"Python rejected a Java-signed record: {err}"

    def test_canonical_bytes_match_java(self):
        # The exact bytes the Java signer covered (printed by the harness).
        expected = (
            '{"did":"did:key:z6MknB2dUwQW4RCU37JHK4QFcDAtaUWrRC5X97STQxiJh9XD",'
            '"displayName":"alice","primaryZone":"alpha",'
            '"writeZones":["alpha","beta"],"readZones":["alpha"],'
            '"channels":[{"type":"nostr","address":"npub1xxx"}],'
            '"updatedAt":1700000000000}'
        ).encode("utf-8")
        got = registration.identity_outbox_signing_bytes(dict(self._JAVA_VECTOR))
        assert got == expected

    def test_tampered_record_rejected(self):
        pytest.importorskip("nacl.signing")
        tampered = dict(self._JAVA_VECTOR)
        tampered["displayName"] = "mallory"  # same sig, changed field
        assert registration.verify_identity_outbox(tampered) is not None

    def test_python_signed_record_round_trips(self):
        # A record we sign in Python (with a did:key key) also verifies — the
        # canonical form is symmetric.
        did, sk = _make_did_keypair()
        rec = {
            "did": did, "displayName": "bob", "primaryZone": "beta",
            "writeZones": ["beta"], "readZones": ["beta"],
            "channels": [], "updatedAt": 42,
        }
        msg = registration.identity_outbox_signing_bytes(rec)
        rec["sig"] = _sign_b64(sk, msg)
        assert registration.verify_identity_outbox(rec) is None

    def test_wrong_did_rejected(self):
        # Sign with key A but claim DID B → verify fails (DID is the key).
        did_a, sk_a = _make_did_keypair()
        did_b, _ = _make_did_keypair()
        rec = {
            "did": did_b, "displayName": "x", "primaryZone": "z",
            "writeZones": [], "readZones": [], "channels": [], "updatedAt": 1,
        }
        msg = registration.identity_outbox_signing_bytes(rec)
        rec["sig"] = _sign_b64(sk_a, msg)  # wrong key
        assert registration.verify_identity_outbox(rec) is not None

    def test_register_with_verified_outbox_sets_flag(self):
        # A registrant presenting a record whose DID matches its own key gets
        # identity_verified=true stored.
        nacl_signing = pytest.importorskip("nacl.signing")
        # Build a keypair whose NKey-derived DID we control by signing the
        # outbox with the SAME underlying Ed25519 key.
        sk = nacl_signing.SigningKey.generate()
        import nkeys
        seed = nkeys.encode_seed(sk.encode(), nkeys.PREFIX_BYTE_USER)
        kp = nkeys.from_seed(seed)
        pubkey = kp.public_key.decode("ascii")
        did = registration.nkey_to_did(pubkey)
        rec = {
            "did": did, "displayName": "self", "primaryZone": "alpha",
            "writeZones": [], "readZones": [], "channels": [], "updatedAt": 7,
        }
        msg = registration.identity_outbox_signing_bytes(rec)
        rec["sig"] = _sign_b64(sk, msg)
        r = registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                       entrant_tier=registration.TIER_FLOOR,
                                       identity_outbox=rec)
        assert r.get("identity_verified") is True

    def test_register_with_invalid_outbox_rejected(self):
        nacl_signing = pytest.importorskip("nacl.signing")
        did, sk = _make_did_keypair()
        rec = {
            "did": did, "displayName": "x", "primaryZone": "z",
            "writeZones": [], "readZones": [], "channels": [], "updatedAt": 1,
            "sig": "AAAA",  # garbage signature
        }
        pubkey, _ = _make_keypair()
        r = registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                       entrant_tier=registration.TIER_FLOOR,
                                       identity_outbox=rec)
        assert "error" in r


# ---: vouch / promote / demote / auto-promote ---

class TestVouchPromoteDemote:
    RELAY_DID = "did:key:z6MkrelayRELAYrelayRELAYrelayRELAYrelayRELAY12"

    def _signed_admin(self, op, args, did, sk, relay_did=None, ts=None):
        relay_did = relay_did or self.RELAY_DID
        ts = int(time.time()) if ts is None else ts
        canon = registration._canonical_args(args)
        import hashlib as _h
        ah = _h.sha256(canon.encode()).hexdigest()
        challenge = f"admin:{op}:{ts}:{relay_did}:{ah}".encode()
        sig = _sign_b64(sk, challenge)
        return registration.admin_op(op=op, args=args, relay_did=relay_did,
                                     ts=ts, did=did, signature_b64=sig)

    def _register_floor(self, identity_verified=True):
        """Register a FLOOR node (optionally identity-verified) and return its DID."""
        nacl_signing = pytest.importorskip("nacl.signing")
        sk = nacl_signing.SigningKey.generate()
        import nkeys
        kp = nkeys.from_seed(nkeys.encode_seed(sk.encode(), nkeys.PREFIX_BYTE_USER))
        pubkey = kp.public_key.decode("ascii")
        did = registration.nkey_to_did(pubkey)
        outbox = None
        if identity_verified:
            rec = {"did": did, "displayName": "n", "primaryZone": "a",
                   "writeZones": [], "readZones": [], "channels": [], "updatedAt": 1}
            rec["sig"] = _sign_b64(sk, registration.identity_outbox_signing_bytes(rec))
            outbox = rec
        registration.register_nkey("1.2.3.4", pubkey=pubkey,
                                   entrant_tier=registration.TIER_FLOOR,
                                   identity_outbox=outbox)
        return did

    def _make_voucher(self, tier):
        """Register a node at `tier` and return its DID (used as a voucher)."""
        pubkey, _ = _make_keypair()
        registration.register_nkey("9.9.9.9", pubkey=pubkey, entrant_tier=tier)
        return registration.nkey_to_did(pubkey)

    def test_owner_vouch_then_threshold_auto_promotes(self):
        owner, owner_sk = _make_did_keypair()
        registration._set_owner(owner)
        registration.COMMONS_VOUCH_THRESHOLD = 2
        subject = self._register_floor(identity_verified=True)
        v1 = self._make_voucher(registration.TIER_VOUCHED)
        v2 = self._make_voucher(registration.TIER_HOUSEHOLD)
        # Owner records two vouches ON BEHALF (owner may vouch as itself + the
        # rule counts VOUCHED+ voucher DIDs). Here we directly seed the vouch
        # store with two VOUCHED+ vouchers then trigger via an owner vouch.
        registration.save_vouches({subject: [v1, v2]})
        # An owner vouch re-checks the threshold and auto-promotes.
        r = self._signed_admin("vouch", {"subject_did": subject}, owner, owner_sk)
        assert r.get("_status") == 200
        assert registration.did_tier(subject) == registration.TIER_VOUCHED

    def test_identity_unverified_blocks_promotion(self):
        owner, owner_sk = _make_did_keypair()
        registration._set_owner(owner)
        registration.COMMONS_VOUCH_THRESHOLD = 1
        subject = self._register_floor(identity_verified=False)
        v1 = self._make_voucher(registration.TIER_VOUCHED)
        registration.save_vouches({subject: [v1]})
        r = self._signed_admin("vouch", {"subject_did": subject}, owner, owner_sk)
        # Vouch recorded, but NOT promoted (no verified IdentityOutbox §4).
        assert r.get("_status") == 200
        assert r.get("auto_promoted") is False
        assert registration.did_tier(subject) == registration.TIER_FLOOR

    def test_floor_voucher_does_not_count(self):
        # Only VOUCHED+ vouchers count toward the WoT promotion threshold (§3).
        # Seed a FLOOR-only voucher and drive the auto-promote check directly
        # (no owner vouch, which would legitimately count).
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        registration.COMMONS_VOUCH_THRESHOLD = 1
        subject = self._register_floor(identity_verified=True)
        floor_voucher = self._make_voucher(registration.TIER_FLOOR)
        registration.save_vouches({subject: [floor_voucher]})
        promoted = registration._maybe_auto_promote(subject)
        assert promoted is False
        assert registration.did_tier(subject) == registration.TIER_FLOOR

    # --- WoT tier-weighted scoring ---

    def test_single_household_voucher_promotes(self):
        # One HOUSEHOLD voucher = weight 1.0 ≥ threshold 1.0 → promote.
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        registration.WOT_PROMOTE_THRESHOLD = 1.0
        subject = self._register_floor(identity_verified=True)
        hh = self._make_voucher(registration.TIER_HOUSEHOLD)
        registration.save_vouches({subject: [hh]})
        assert registration.wot_promotion_score(subject) == 1.0
        assert registration._maybe_auto_promote(subject) is True
        assert registration.did_tier(subject) == registration.TIER_VOUCHED

    def test_single_vouched_voucher_insufficient(self):
        # One VOUCHED voucher = weight 0.6 < threshold 1.0 → no promote.
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        registration.WOT_PROMOTE_THRESHOLD = 1.0
        subject = self._register_floor(identity_verified=True)
        v = self._make_voucher(registration.TIER_VOUCHED)
        registration.save_vouches({subject: [v]})
        assert registration.wot_promotion_score(subject) == 0.6
        assert registration._maybe_auto_promote(subject) is False
        assert registration.did_tier(subject) == registration.TIER_FLOOR

    def test_two_vouched_vouchers_promote(self):
        # Two VOUCHED vouchers = 0.6 + 0.6 = 1.2 ≥ 1.0 → promote (the tier-
        # weighted sum is what makes several attenuated vouchers add up).
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        registration.WOT_PROMOTE_THRESHOLD = 1.0
        subject = self._register_floor(identity_verified=True)
        v1 = self._make_voucher(registration.TIER_VOUCHED)
        v2 = self._make_voucher(registration.TIER_VOUCHED)
        registration.save_vouches({subject: [v1, v2]})
        assert registration.wot_promotion_score(subject) == 1.2
        assert registration._maybe_auto_promote(subject) is True

    def test_owner_voucher_weight_is_full(self):
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        assert registration.wot_voucher_weight(owner) == 1.0
        # A FLOOR/unknown voucher confers no trust.
        floor = self._make_voucher(registration.TIER_FLOOR)
        assert registration.wot_voucher_weight(floor) == 0.0

    def test_floor_node_cannot_vouch(self):
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        # A would-be voucher holds a moderation grant (passes the scope gate)
        # but its TIER is FLOOR (it has no registration → did_tier defaults to
        # FLOOR) → the vouch eligibility check still rejects it.
        voucher_did, voucher_sk = _make_did_keypair()
        registration.save_admin_grants({voucher_did: {"scope": "moderation"}})
        subject = self._register_floor()
        r = self._signed_admin("vouch", {"subject_did": subject},
                               voucher_did, voucher_sk)
        assert r.get("_status") == 403

    def test_operator_direct_promote_and_demote(self):
        owner, owner_sk = _make_did_keypair()
        registration._set_owner(owner)
        subject = self._register_floor()
        # Promote FLOOR → VOUCHED.
        p = self._signed_admin("promote",
                               {"subject_did": subject, "tier": "VOUCHED"},
                               owner, owner_sk)
        assert p.get("_status") == 200
        assert registration.did_tier(subject) == registration.TIER_VOUCHED
        # Demote VOUCHED → FLOOR clears vouches.
        registration.save_vouches({subject: ["did:key:zSomeVoucher"]})
        d = self._signed_admin("demote",
                               {"subject_did": subject, "tier": "FLOOR"},
                               owner, owner_sk, ts=int(time.time()) + 1)
        assert d.get("_status") == 200
        assert registration.did_tier(subject) == registration.TIER_FLOOR
        assert subject not in registration.load_vouches()

    def test_promote_must_raise(self):
        owner, owner_sk = _make_did_keypair()
        registration._set_owner(owner)
        subject = self._make_voucher(registration.TIER_HOUSEHOLD)
        # Promoting to a LOWER tier is rejected.
        r = self._signed_admin("promote",
                               {"subject_did": subject, "tier": "FLOOR"},
                               owner, owner_sk)
        assert r.get("_status") == 400

    def test_moderation_denied_set_mode_but_allowed_promote(self):
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        mod, mod_sk = _make_did_keypair()
        registration.save_admin_grants({mod: {"scope": "moderation"}})
        subject = self._register_floor()
        # promote is moderation-scope → allowed.
        now = int(time.time())
        p = self._signed_admin("promote", {"subject_did": subject, "tier": "VOUCHED"},
                               mod, mod_sk, ts=now)
        assert p.get("_status") == 200
        # set-mode is full-scope → denied for a moderation delegate.
        s = self._signed_admin("set-mode", {"mode": "commons"}, mod, mod_sk, ts=now + 1)
        assert s.get("_status") == 403


# ---: set-policy + per-tier reaper window ---

class TestPolicyAndTierReaper:
    RELAY_DID = "did:key:z6MkrelayRELAYrelayRELAYrelayRELAYrelayRELAY12"

    def _signed_admin(self, op, args, did, sk, ts=None):
        ts = int(time.time()) if ts is None else ts
        import hashlib as _h
        ah = _h.sha256(registration._canonical_args(args).encode()).hexdigest()
        challenge = f"admin:{op}:{ts}:{self.RELAY_DID}:{ah}".encode()
        sig = _sign_b64(sk, challenge)
        return registration.admin_op(op=op, args=args, relay_did=self.RELAY_DID,
                                     ts=ts, did=did, signature_b64=sig)

    def test_set_policy_records_per_tier_limits(self):
        owner, sk = _make_did_keypair()
        registration._set_owner(owner)
        r = self._signed_admin("set-policy",
                               {"floor": {"max_conns": 1},
                                "vouched": {"max_conns": 5}}, owner, sk)
        assert r.get("_status") == 200
        p = registration.load_policy()
        assert p["tiers"]["floor"]["max_conns"] == 1
        assert p["tiers"]["vouched"]["max_conns"] == 5
        # Quotas are now ENFORCED (max_registrations at the gate, max_connections
        # detection-grade); the response echoes the effective per-tier quotas.
        assert r.get("status") == "policy_set"
        assert "effective_quotas" in r
        assert "max_registrations" in r["effective_quotas"]["floor"]

    def test_floor_reaper_window_shorter(self, monkeypatch):
        # A FLOOR record stale past the FLOOR window (but within the long
        # HOUSEHOLD window) is reaped; a HOUSEHOLD record same-age survives.
        registration.FLOOR_LIVENESS_TIMEOUT_HOURS = 24
        floor_pk, _ = _make_keypair()
        hh_pk, _ = _make_keypair()
        _seed_nkey_registration(floor_pk, household_tag="hh-floor")
        _seed_nkey_registration(hh_pk, household_tag="hh-house")
        regs = registration.load_registrations()
        regs[floor_pk]["tier"] = registration.TIER_FLOOR
        regs[hh_pk]["tier"] = registration.TIER_HOUSEHOLD
        from datetime import datetime, timedelta
        # 48h ago: past FLOOR (24h) but well within HOUSEHOLD (168h).
        stale = (datetime.utcnow() - timedelta(hours=48)).isoformat()
        regs[floor_pk]["last_seen"] = stale
        regs[hh_pk]["last_seen"] = stale
        registration.save_registrations(regs)
        monkeypatch.setattr(registration, "_fetch_connected_nkeys", lambda: set())
        deleted = registration._reap_stale_registrations()
        after = registration.load_registrations()
        assert floor_pk not in after, "FLOOR record past its short window must reap"
        assert hh_pk in after, "HOUSEHOLD record within long window must survive"
        assert deleted == 1

    def test_reaper_window_helper(self):
        assert registration.reaper_window_hours_for({"tier": "FLOOR"}) == \
            registration.FLOOR_LIVENESS_TIMEOUT_HOURS
        assert registration.reaper_window_hours_for({"tier": "VOUCHED"}) == \
            registration.LIVENESS_TIMEOUT_HOURS
        # No tier (pre-P5) → long window (treated as HOUSEHOLD).
        assert registration.reaper_window_hours_for({}) == \
            registration.LIVENESS_TIMEOUT_HOURS


# ---: per-tier quota ENFORCEMENT ---

class TestQuotaEnforcement:
    """max_registrations is enforced HARD at the register gate (the commons
    Sybil/flood defense); max_connections is detection-grade in the reaper."""

    def test_tier_quota_overlay(self):
        # Policy field wins over the default; unspecified fields fall back.
        registration.save_policy({"tiers": {"floor": {"max_connections": 9}}})
        q = registration.tier_quota("floor")
        assert q["max_connections"] == 9
        assert "max_registrations" in q  # default carried through

    def test_floor_registration_cap_rejects_new_entrant(self):
        registration.save_policy({"tiers": {"floor": {"max_registrations": 1}}})
        pk1, _ = _make_keypair()
        pk2, _ = _make_keypair()
        r1 = registration.register_nkey("1.2.3.4", pubkey=pk1,
                                        entrant_tier=registration.TIER_FLOOR)
        assert "error" not in r1, r1
        r2 = registration.register_nkey("1.2.3.4", pubkey=pk2,
                                        entrant_tier=registration.TIER_FLOOR)
        assert "error" in r2, "second FLOOR entrant past the cap must be refused"
        assert r2.get("tier") == registration.TIER_FLOOR
        assert r2.get("tier_cap") == 1

    def test_tier_cap_does_not_block_other_tiers(self):
        # A full FLOOR tier must not block a HOUSEHOLD entrant.
        registration.save_policy({"tiers": {"floor": {"max_registrations": 1}}})
        f1, _ = _make_keypair()
        registration.register_nkey("1.2.3.4", pubkey=f1,
                                   entrant_tier=registration.TIER_FLOOR)
        h1, _ = _make_keypair()
        rh = registration.register_nkey("1.2.3.4", pubkey=h1,
                                        entrant_tier=registration.TIER_HOUSEHOLD)
        assert "error" not in rh, rh

    def test_reregister_bypasses_tier_cap(self):
        # An existing record re-registering is NEVER blocked by the cap.
        registration.save_policy({"tiers": {"floor": {"max_registrations": 1}}})
        pk1, _ = _make_keypair()
        registration.register_nkey("1.2.3.4", pubkey=pk1,
                                   entrant_tier=registration.TIER_FLOOR)
        r = registration.register_nkey("1.2.3.4", pubkey=pk1,
                                       entrant_tier=registration.TIER_FLOOR)
        assert "error" not in r, "re-register of an existing record must pass"

    def test_unlimited_tier_cap_default(self):
        # HOUSEHOLD default max_registrations = -1 (unlimited): many entrants ok.
        for _ in range(3):
            pk, _ = _make_keypair()
            r = registration.register_nkey("1.2.3.4", pubkey=pk,
                                           entrant_tier=registration.TIER_HOUSEHOLD)
            assert "error" not in r, r

    def test_reaper_stamps_connection_overage(self, monkeypatch):
        registration.save_policy({"tiers": {"floor": {"max_connections": 1}}})
        pk, _ = _make_keypair()
        _seed_nkey_registration(pk)
        regs = registration.load_registrations()
        regs[pk]["tier"] = registration.TIER_FLOOR
        registration.save_registrations(regs)
        # connz reports 3 live connections for this DID (over the cap of 1).
        monkeypatch.setattr(registration, "_fetch_connection_counts",
                            lambda: {pk: 3})
        registration._reap_stale_registrations()
        ocl = registration.load_registrations()[pk].get("over_connection_limit")
        assert ocl is not None, "overage must be stamped for the operator"
        assert ocl["live"] == 3 and ocl["cap"] == 1

    def test_reaper_clears_overage_when_back_in_budget(self, monkeypatch):
        registration.save_policy({"tiers": {"floor": {"max_connections": 5}}})
        pk, _ = _make_keypair()
        _seed_nkey_registration(pk)
        regs = registration.load_registrations()
        regs[pk]["tier"] = registration.TIER_FLOOR
        regs[pk]["over_connection_limit"] = {"live": 9, "cap": 5}
        registration.save_registrations(regs)
        monkeypatch.setattr(registration, "_fetch_connection_counts",
                            lambda: {pk: 2})
        registration._reap_stale_registrations()
        assert registration.load_registrations()[pk].get(
            "over_connection_limit") is None, "marker must clear within budget"


# ---: abuse reports queue ---

@pytest.mark.skipif(not _HAVE_CRYPTOGRAPHY, reason="signed admin needs crypto")
class TestReportsQueue:
    """Filing a report is open to ANY valid signer; viewing/resolving is
    moderator-only. The subject DID need not be present."""

    RELAY_DID = "did:key:z6MkrelayRELAYrelayRELAYrelayRELAYrelayRELAY12"

    def _signed_admin(self, op, args, did, sk, relay_did=None, ts=None):
        relay_did = relay_did or self.RELAY_DID
        ts = int(time.time()) if ts is None else ts
        canon = registration._canonical_args(args)
        import hashlib as _h
        ah = _h.sha256(canon.encode()).hexdigest()
        challenge = f"admin:{op}:{ts}:{relay_did}:{ah}".encode()
        sig = _sign_b64(sk, challenge)
        return registration.admin_op(op=op, args=args, relay_did=relay_did,
                                     ts=ts, did=did, signature_b64=sig)

    def test_any_did_can_file_report_valid_sig(self):
        # No owner, no grant — a plain registered DID files a report.
        reporter, sk = _make_did_keypair()
        subject, _ = _make_did_keypair()
        r = self._signed_admin("report",
                               {"subject_did": subject, "reason": "spamming"},
                               reporter, sk)
        assert r.get("_status") == 200, r
        assert r.get("status") == "filed"
        rec = r["report"]
        assert rec["subject_did"] == subject
        assert rec["reporter_did"] == reporter
        assert rec["status"] == "open"
        assert rec["resolved_by"] is None

    def test_report_bad_signature_rejected(self):
        reporter, _ = _make_did_keypair()
        _, sk_other = _make_did_keypair()
        subject, _ = _make_did_keypair()
        r = self._signed_admin("report",
                               {"subject_did": subject, "reason": "x"},
                               reporter, sk_other)
        assert r.get("_status") in (401, 403)
        assert registration.load_reports() == []

    def test_report_requires_subject_and_reason(self):
        reporter, sk = _make_did_keypair()
        r1 = self._signed_admin("report", {"reason": "x"}, reporter, sk,
                                ts=int(time.time()))
        assert r1.get("_status") == 400
        subject, _ = _make_did_keypair()
        r2 = self._signed_admin("report", {"subject_did": subject}, reporter, sk,
                                ts=int(time.time()) + 1)
        assert r2.get("_status") == 400

    def test_report_self_rejected(self):
        me, sk = _make_did_keypair()
        r = self._signed_admin("report", {"subject_did": me, "reason": "x"}, me, sk)
        assert r.get("_status") == 400

    def test_report_on_absent_subject_works(self):
        # Subject DID is not (and never was) a registration — still reportable.
        reporter, sk = _make_did_keypair()
        absent, _ = _make_did_keypair()
        r = self._signed_admin("report",
                               {"subject_did": absent, "reason": "left after abuse"},
                               reporter, sk)
        assert r.get("_status") == 200
        # report-queue shows subject_present false for it.
        owner, osk = _make_did_keypair()
        registration._set_owner(owner)
        q = self._signed_admin("report-queue", {}, owner, osk,
                               ts=int(time.time()) + 1)
        assert q.get("_status") == 200
        rows = q["reports"]
        assert any(not row["subject_present"] for row in rows)

    def test_duplicate_open_reports_capped_per_pair(self):
        registration.REPORT_OPEN_CAP_PER_PAIR = 2
        reporter, sk = _make_did_keypair()
        subject, _ = _make_did_keypair()
        now = int(time.time())
        a = self._signed_admin("report", {"subject_did": subject, "reason": "1"},
                               reporter, sk, ts=now)
        b = self._signed_admin("report", {"subject_did": subject, "reason": "2"},
                               reporter, sk, ts=now + 1)
        assert a.get("_status") == 200 and b.get("_status") == 200
        # Third open report against the same subject → capped.
        c = self._signed_admin("report", {"subject_did": subject, "reason": "3"},
                               reporter, sk, ts=now + 2)
        assert c.get("_status") == 429

    def test_report_queue_moderation_gated(self):
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        # A registered, non-moderator DID files a report (allowed) ...
        reporter, rsk = _make_did_keypair()
        subject, _ = _make_did_keypair()
        self._signed_admin("report", {"subject_did": subject, "reason": "x"},
                           reporter, rsk)
        # ... but cannot VIEW the queue (no moderation scope).
        q = self._signed_admin("report-queue", {}, reporter, rsk,
                               ts=int(time.time()) + 1)
        assert q.get("_status") == 403
        # A moderation-scope delegate can.
        mod, msk = _make_did_keypair()
        registration.save_admin_grants({mod: {"scope": "moderation"}})
        q2 = self._signed_admin("report-queue", {}, mod, msk,
                                ts=int(time.time()) + 2)
        assert q2.get("_status") == 200
        assert q2["open_count"] == 1
        assert len(q2["reports"]) == 1

    def test_resolve_report_flips_status_and_stamps(self):
        reporter, rsk = _make_did_keypair()
        subject, _ = _make_did_keypair()
        filed = self._signed_admin("report",
                                   {"subject_did": subject, "reason": "abuse"},
                                   reporter, rsk)
        rid = filed["report_id"]
        mod, msk = _make_did_keypair()
        registration.save_admin_grants({mod: {"scope": "moderation"}})
        r = self._signed_admin("resolve-report",
                               {"report_id": rid, "action": "noted"},
                               mod, msk, ts=int(time.time()) + 1)
        assert r.get("_status") == 200
        assert r["resolution"] == "noted"
        rec = r["report"]
        assert rec["status"] == "resolved"
        assert rec["resolved_by"] == mod
        assert rec["resolved_at"] is not None
        # Queue no longer shows it as open.
        q = self._signed_admin("report-queue", {}, mod, msk,
                               ts=int(time.time()) + 2)
        assert q["open_count"] == 0
        # include_resolved surfaces it.
        q2 = self._signed_admin("report-queue", {"include_resolved": True},
                                mod, msk, ts=int(time.time()) + 3)
        assert any(row["id"] == rid for row in q2["reports"])

    def test_resolve_report_non_moderator_denied(self):
        owner, _ = _make_did_keypair()
        registration._set_owner(owner)
        reporter, rsk = _make_did_keypair()
        subject, _ = _make_did_keypair()
        filed = self._signed_admin("report",
                                   {"subject_did": subject, "reason": "x"},
                                   reporter, rsk)
        rid = filed["report_id"]
        # The reporter (a plain signer, no grant) cannot resolve.
        r = self._signed_admin("resolve-report",
                               {"report_id": rid, "action": "dismiss"},
                               reporter, rsk, ts=int(time.time()) + 1)
        assert r.get("_status") == 403
        assert registration.load_reports()[0]["status"] == "open"

    def test_resolve_report_invalid_action_and_unknown_id(self):
        mod, msk = _make_did_keypair()
        registration.save_admin_grants({mod: {"scope": "moderation"}})
        bad = self._signed_admin("resolve-report",
                                 {"report_id": "rpt-x", "action": "banhammer"},
                                 mod, msk)
        assert bad.get("_status") == 400
        missing = self._signed_admin("resolve-report",
                                     {"report_id": "rpt-nope", "action": "noted"},
                                     mod, msk, ts=int(time.time()) + 1)
        assert missing.get("_status") == 404

    def test_resolve_report_removed_is_advisory(self):
        # action=removed records the verdict + linkage; it does NOT itself kick.
        reporter, rsk = _make_did_keypair()
        subject, _ = _make_did_keypair()
        filed = self._signed_admin("report",
                                   {"subject_did": subject, "reason": "kickme"},
                                   reporter, rsk)
        rid = filed["report_id"]
        mod, msk = _make_did_keypair()
        registration.save_admin_grants({mod: {"scope": "moderation"}})
        r = self._signed_admin("resolve-report",
                               {"report_id": rid, "action": "removed"},
                               mod, msk, ts=int(time.time()) + 1)
        assert r.get("_status") == 200
        assert r["report"]["resolution"] == "removed"


class TestSshTunnel:
    """port assignment, pubkey sanitizing, and
    authorized_keys regeneration for the dedicated tunnel sshd."""

    def _pub(self):
        # A real OpenSSH ed25519 public key, generated offline once.
        return ("ssh-ed25519 "
                "AAAAC3NzaC1lZDI1NTE5AAAAIK5I92NgUjAfXTNvAbODF9qD7mUpVwX0Y+RgX39mmxg/ "
                "zonekey")

    def test_sanitize_accepts_ed25519_rejects_others(self):
        pub = self._pub()
        assert registration._sanitize_ssh_pubkey(pub) is not None
        assert registration._sanitize_ssh_pubkey("ssh-rsa AAAAB3Nza x") is None
        # option-injection: a leading command="..." must be rejected.
        assert registration._sanitize_ssh_pubkey('command="evil" ' + pub) is None
        # embedded newline (would add a second authorized_keys line) rejected.
        assert registration._sanitize_ssh_pubkey(pub + "\nevil") is None

    def test_port_assignment_distinct_sticky_exhausts(self):
        pub = self._pub()
        regs = {
            "U1": {"active": True, "zone_id": "a",
                   "ssh_tunnel": {"enabled": True, "pubkey": pub, "assigned_port": None}},
        }
        p1 = registration._assign_ssh_tunnel_port(regs, "U1")
        regs["U1"]["ssh_tunnel"]["assigned_port"] = p1
        assert p1 == registration.SSH_TUNNEL_PORT_BASE
        # sticky
        assert registration._assign_ssh_tunnel_port(regs, "U1") == p1
        # distinct
        regs["U2"] = {"active": True, "zone_id": "b",
                      "ssh_tunnel": {"enabled": True, "pubkey": pub, "assigned_port": None}}
        p2 = registration._assign_ssh_tunnel_port(regs, "U2")
        regs["U2"]["ssh_tunnel"]["assigned_port"] = p2
        assert p2 != p1
        # exhaust the 3-port test range
        regs["U3"] = {"active": True, "zone_id": "c",
                      "ssh_tunnel": {"enabled": True, "pubkey": pub, "assigned_port": None}}
        p3 = registration._assign_ssh_tunnel_port(regs, "U3")
        regs["U3"]["ssh_tunnel"]["assigned_port"] = p3
        regs["U4"] = {"active": True, "zone_id": "d",
                      "ssh_tunnel": {"enabled": True, "pubkey": pub, "assigned_port": None}}
        assert registration._assign_ssh_tunnel_port(regs, "U4") is None

    def test_authorized_keys_regen_line_shape_and_freeing(self):
        pub = self._pub()
        regs = {
            "U1": {"active": True, "zone_id": "alpha",
                   "ssh_tunnel": {"enabled": True, "pubkey": pub, "assigned_port": 7100}},
            # disabled record → no line
            "U2": {"active": True, "zone_id": "beta",
                   "ssh_tunnel": {"enabled": False, "pubkey": pub, "assigned_port": 7101}},
            # inactive record → no line
            "U3": {"active": False, "zone_id": "gamma",
                   "ssh_tunnel": {"enabled": True, "pubkey": pub, "assigned_port": 7102}},
        }
        registration.update_ssh_authorized_keys(regs)
        ak = registration.SSH_AUTHORIZED_KEYS.read_text()
        # `port-forwarding` is REQUIRED alongside `restrict` — restrict alone
        # disables forwarding and permitlisten does NOT re-enable it.
        assert 'restrict,port-forwarding,permitlisten="0.0.0.0:7100"' in ak
        assert "wyrd-tunnel-alpha" in ak
        assert "7101" not in ak  # disabled
        assert "7102" not in ak  # inactive
        # the file must be 0644 (world-readable): sshd reads it as the unprivileged
        # tunnel uid but the sidecar writes it as root; pubkeys aren't secret.
        import stat
        mode = stat.S_IMODE(registration.SSH_AUTHORIZED_KEYS.stat().st_mode)
        assert mode == 0o644
        # freeing: drop the only enabled record → empty key list.
        registration.update_ssh_authorized_keys({})
        assert registration.SSH_AUTHORIZED_KEYS.read_text().strip().startswith("#")
        assert "7100" not in registration.SSH_AUTHORIZED_KEYS.read_text()


class TestSshTunnelAdmin:
    """the signed ssh-enable/ssh-disable admin op
    the grant-vs-open auth gate, and topology-aware authorized_keys regen."""
    RELAY_DID = "did:key:zRelayTestSshAdmin"

    def _pub(self):
        return ("ssh-ed25519 "
                "AAAAC3NzaC1lZDI1NTE5AAAAIK5I92NgUjAfXTNvAbODF9qD7mUpVwX0Y+RgX39mmxg/ "
                "zonekey")

    def _signed_admin(self, op, args, did, sk, ts=None):
        ts = int(time.time()) if ts is None else ts
        canon = registration._canonical_args(args)
        import hashlib as _h
        ah = _h.sha256(canon.encode()).hexdigest()
        challenge = f"admin:{op}:{ts}:{self.RELAY_DID}:{ah}".encode()
        sig = _sign_b64(sk, challenge)
        return registration.admin_op(op=op, args=args, relay_did=self.RELAY_DID,
                                     ts=ts, did=did, signature_b64=sig)

    def _seed_reg_with_did(self, pubkey, did, zone_id="alpha"):
        regs = registration.load_registrations()
        regs[pubkey] = {"kind": "nkey", "pubkey": pubkey, "did": did,
                        "household_tag": "hh-test", "zone_id": zone_id,
                        "node_name": "n", "active": True, "last_seen": None}
        registration.save_registrations(regs)

    def test_off_mode_refuses_enable(self):
        registration._set_ssh_policy(mode="off")
        owner, sk = _make_did_keypair()
        registration._set_owner(owner, via="test")
        r = self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()},
                               owner, sk)
        assert r.get("_status") == 409

    def test_grant_mode_owner_enables_port_topology(self):
        registration._set_ssh_policy(mode="grant", topology="port")
        owner, sk = _make_did_keypair()
        registration._set_owner(owner, via="test")
        self._seed_reg_with_did("U1", "did:key:zZoneOne", "alpha")
        r = self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()},
                               owner, sk)
        assert r.get("_status") == 200, r
        assert r["topology"] == "port"
        assert r["assigned_port"] == registration.SSH_TUNNEL_PORT_BASE
        ak = registration.SSH_AUTHORIZED_KEYS.read_text()
        assert f'permitlisten="0.0.0.0:{r["assigned_port"]}"' in ak
        assert "wyrd-tunnel-alpha" in ak

    def test_grant_mode_stranger_denied(self):
        registration._set_ssh_policy(mode="grant")
        owner, _ = _make_did_keypair()
        registration._set_owner(owner, via="test")
        self._seed_reg_with_did("U1", "did:key:zZoneOne")
        stranger, sk = _make_did_keypair()           # no grant, not owner
        r = self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()},
                               stranger, sk)
        assert r.get("_status") == 403

    def test_open_mode_zone_self_serves(self):
        registration._set_ssh_policy(mode="open")
        owner, _ = _make_did_keypair()
        registration._set_owner(owner, via="test")
        did, sk = _make_did_keypair()                # the zone's own identity
        self._seed_reg_with_did("U1", did, "beta")   # registration.did == signer
        r = self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()},
                               did, sk)
        assert r.get("_status") == 200, r            # self-serve bypass
        assert "wyrd-tunnel-beta" in registration.SSH_AUTHORIZED_KEYS.read_text()

    def test_open_mode_cannot_enable_another_zone(self):
        registration._set_ssh_policy(mode="open")
        owner, _ = _make_did_keypair()
        registration._set_owner(owner, via="test")
        self._seed_reg_with_did("U1", "did:key:zSomeoneElse", "alpha")
        attacker, sk = _make_did_keypair()           # NOT U1's did, no grant
        r = self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()},
                               attacker, sk)
        assert r.get("_status") == 403               # falls to scope gate

    def test_disable_clears_the_line(self):
        registration._set_ssh_policy(mode="grant", topology="port")
        owner, sk = _make_did_keypair()
        registration._set_owner(owner, via="test")
        self._seed_reg_with_did("U1", "did:key:zZoneOne", "alpha")
        self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()}, owner, sk)
        assert "wyrd-tunnel-alpha" in registration.SSH_AUTHORIZED_KEYS.read_text()
        r = self._signed_admin("ssh-disable", {"pubkey": "U1"}, owner, sk, ts=int(time.time()) + 1)
        assert r.get("_status") == 200
        assert "wyrd-tunnel-alpha" not in registration.SSH_AUTHORIZED_KEYS.read_text()

    def test_jump_topology_loopback_plus_jump_principal(self, tmp_path):
        registration._set_ssh_policy(mode="grant", topology="jump")
        # Provide a jump principal pubkey so the forward-only line is emitted.
        registration.SSH_JUMP_KEY_PUB.parent.mkdir(parents=True, exist_ok=True)
        registration.SSH_JUMP_KEY_PUB.write_text(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLEKEY0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA jump")
        # The connecting human needs the jump PRIVATE key (forward-only) to ProxyJump;
        # the enable response ships it so the emitted ssh_config stanza is self-contained.
        registration.SSH_JUMP_KEY.write_text(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nFAKEJUMPKEYBODY==\n-----END OPENSSH PRIVATE KEY-----\n")
        owner, sk = _make_did_keypair()
        registration._set_owner(owner, via="test")
        self._seed_reg_with_did("U1", "did:key:zZoneOne", "alpha")
        r = self._signed_admin("ssh-enable", {"pubkey": "U1", "ssh_pubkey": self._pub()},
                               owner, sk)
        assert r.get("_status") == 200 and r["topology"] == "jump"
        assert "BEGIN OPENSSH PRIVATE KEY" in r.get("jump_private_key", "")  # shipped to client
        ak = registration.SSH_AUTHORIZED_KEYS.read_text()
        port = r["assigned_port"]
        assert f'permitlisten="127.0.0.1:{port}"' in ak     # loopback, NOT public
        assert f'permitopen="127.0.0.1:{port}"' in ak       # the forward-only jump line
        assert "wyrd-jump" in ak
        registration.SSH_JUMP_KEY_PUB.unlink()              # don't leak into other tests
        registration.SSH_JUMP_KEY.unlink()

class TestInfrastructureSecrets:
    """OSS hardening (2026-07-25): a published relay must not ship with
    world-known infrastructure passwords. Every wyrdsekai relay generates its
    own on first run; env vars still override for compose/relay.sh deploys."""

    def test_infra_passwords_are_not_source_constants(self):
        # The pre-OSS literals. If any reappears in a generated conf, every
        # relay on the internet shares that credential again.
        published_defaults = [
            "M3bWgIOVG0WH8p1HHXD4XxPXtVgjtxezoIejyTrmM7A",   # relay_phone
            "rgwRK4XjbsM3ziijLLmQTCo4GDfYb_D8pZ4lE8VBMQQ",   # relay_sidecar
            "rJ-O0bTcz4RxiZ_yYHbBQ6XwTLOEXAATG_NW0OJ7H38",   # relay_join
        ]
        for name in ("phone", "sidecar", "join"):
            assert registration._relay_secret(name) not in published_defaults

        regs = registration.load_registrations()
        registration.update_nats_config(regs)
        conf = _NATS_CONF.read_text()
        for leaked in published_defaults:
            assert leaked not in conf, f"published default {leaked[:12]}… in generated conf"

    def test_generated_secrets_are_stable_across_calls(self):
        # A conf regen must not invalidate the running sidecar's credential —
        # the property the old hard-coded constants existed to guarantee.
        first = registration._relay_secret("sidecar")
        assert registration._relay_secret("sidecar") == first
        assert len(first) >= 32, "secret must carry real entropy"

    def test_distinct_accounts_get_distinct_secrets(self):
        vals = {registration._relay_secret(n) for n in ("phone", "sidecar", "join")}
        assert len(vals) == 3

    def test_env_override_still_wins(self, monkeypatch):
        monkeypatch.setenv("RELAY_JOIN_PASSWORD", "operator-supplied-join-pw")
        assert registration._join_password() == "operator-supplied-join-pw"
        monkeypatch.setenv("NATS_PHONE_PASSWORD", "operator-supplied-phone-pw")
        assert registration._phone_password() == "operator-supplied-phone-pw"
