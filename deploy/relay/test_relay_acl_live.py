"""Live relay ACL test (security review 2026-09-28).

Builds a relay.conf authorization block with registration.update_nats_config,
starts a throwaway nats-server in docker bound to 127.0.0.1, and checks with
real clients that one household cannot read another's zone subjects or reply
inbox, while its own request/reply still works.

Opt-in (needs docker and a local nats image; no network):
    RELAY_ACL_LIVE=1 pytest test_relay_acl_live.py -v
Image: RELAY_ACL_IMAGE (default nats:2.11.4). The container is removed at the end.
"""

import asyncio
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("RELAY_ACL_LIVE") != "1" or shutil.which("docker") is None,
    reason="live ACL test: set RELAY_ACL_LIVE=1 (needs docker)")

_TMP = Path(tempfile.mkdtemp(prefix="wyrd-relay-acl-"))
for _k, _v in (("DATA_DIR", _TMP / "data"), ("CERT_DIR", _TMP / "certs"),
               ("NATS_CONF", _TMP / "unused.conf"), ("NATS_SIGNAL_CMD", "true")):
    os.environ.setdefault(_k, str(_v))
(_TMP / "data").mkdir(exist_ok=True)
sys.path.insert(0, str(Path(__file__).parent))
import registration  # noqa: E402

IMAGE = os.environ.get("RELAY_ACL_IMAGE", "nats:2.11.4")


def _keypair():
    nkeys = pytest.importorskip("nkeys")
    signing = pytest.importorskip("nacl.signing")
    seed = nkeys.encode_seed(signing.SigningKey.generate().encode(), nkeys.PREFIX_BYTE_USER)
    return nkeys.from_seed(seed).public_key.decode(), seed.decode()


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def relay():
    a_pub, a_seed = _keypair()
    b_pub, b_seed = _keypair()
    regs = {
        a_pub: {"kind": "nkey", "pubkey": a_pub, "zone_id": "alpha",
                "household_tag": registration._server_household_tag(a_pub), "active": True},
        b_pub: {"kind": "nkey", "pubkey": b_pub, "zone_id": "beta",
                "household_tag": registration._server_household_tag(b_pub), "active": True},
        "hh-0123456789ab": {"token": "tok-gamma-0123456789", "household_tag": "hh-0123456789ab",
                            "zone_id": "gamma", "active": True},
    }
    conf = _TMP / "relay.conf"
    conf.write_text("listen: 0.0.0.0:4222\nauthorization {\n    users = []\n}\n")
    saved = (registration.NATS_CONF, registration.NATS_SIGNAL)
    registration.NATS_CONF, registration.NATS_SIGNAL = str(conf), "true"
    try:
        registration.update_nats_config(regs)
    finally:
        registration.NATS_CONF, registration.NATS_SIGNAL = saved
    conf.chmod(0o644)
    port = _free_port()
    name = f"wyrd-relay-acl-{os.getpid()}"
    subprocess.run(["docker", "run", "-d", "--rm", "--name", name,
                    "-p", f"127.0.0.1:{port}:4222",
                    "-v", f"{conf}:/relay.conf:ro", IMAGE, "-c", "/relay.conf"],
                   check=True, capture_output=True, timeout=60)
    try:
        deadline = time.time() + 20
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=1) as s:
                    if s.recv(5) == b"INFO ":
                        break
            except OSError:
                time.sleep(0.3)
        else:
            logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True).stdout
            pytest.fail(f"nats-server did not start:\n{logs}")
        yield {
            "url": f"nats://127.0.0.1:{port}",
            "a": (a_pub, a_seed), "b": (b_pub, b_seed),
            "phone_a": ("phone-" + registration._server_household_tag(a_pub),
                        registration._phone_password_for(registration._server_household_tag(a_pub))),
            "phone_b": ("phone-" + registration._server_household_tag(b_pub),
                        registration._phone_password_for(registration._server_household_tag(b_pub))),
            "gamma": ("hh-0123456789ab", "tok-gamma-0123456789"),
            "conf": conf.read_text(),
        }
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=60)
        shutil.rmtree(_TMP, ignore_errors=True)


async def _connect(url, errors, *, seed=None, user=None, password=None):
    import nats
    async def on_error(e):
        errors.append(str(e))
    kw = {"error_cb": on_error, "allow_reconnect": False, "connect_timeout": 5}
    if seed:
        from nkeys import from_seed
        pub = from_seed(seed.encode()).public_key.decode()
        kw.update(nkeys_seed_str=seed, inbox_prefix=f"_INBOX.{pub}")
    else:
        kw.update(user=user, password=password, inbox_prefix=f"_INBOX.{user}")
    return await nats.connect(url, **kw)


async def _denied(nc, errors, subject) -> bool:
    """True when the server refuses a subscription to `subject`."""
    errors.clear()
    got = []
    async def cb(msg):
        got.append(msg)
    await nc.subscribe(subject, cb=cb)
    await nc.flush()
    await asyncio.sleep(0.4)
    # nats-py lowercases the server's -ERR text.
    return any("permissions violation" in e.lower() and subject.lower() in e.lower() for e in errors)


def _run(coro):
    return asyncio.run(coro)


def test_household_cannot_subscribe_another_zone(relay):
    async def go():
        errs = []
        nc = await _connect(relay["url"], errs, seed=relay["a"][1])
        try:
            for subject in ("between.beta.>", "federation.beta.>", "federation.beta.gate.>",
                            "federation.inference.beta.complete", "federation.inference.stream.beta.>",
                            "federation.inference.stream.*", "federation.>", "between.>",
                            "wyrd.zone.beta.>", "wyrd.tunnel.beta.>", "wyrd.tunnel.>"):
                assert await _denied(nc, errs, subject), subject
            assert not await _denied(nc, errs, "between.alpha.>"), "own zone allowed"
            assert not await _denied(nc, errs, "federation.inference.stream.alpha.x1"), "own streams allowed"
        finally:
            await nc.close()
    _run(go())


def test_household_cannot_read_another_users_inbox(relay):
    async def go():
        errs = []
        nc = await _connect(relay["url"], errs, seed=relay["a"][1])
        try:
            assert await _denied(nc, errs, "_INBOX.>")
            assert await _denied(nc, errs, f"_INBOX.{relay['b'][0]}.>")
            assert await _denied(nc, errs, f"_INBOX.{relay['phone_b'][0]}.>")
            assert not await _denied(nc, errs, f"_INBOX.{relay['a'][0]}.>"), "own inbox allowed"
        finally:
            await nc.close()
    _run(go())


def test_phone_request_reply_works_and_is_private(relay):
    """A phone of zone beta asks its zone over the relay; zone beta answers via
    allow_responses; household alpha can neither see the request nor the reply."""
    async def go():
        zerrs, perrs, aerrs = [], [], []
        zone_b = await _connect(relay["url"], zerrs, seed=relay["b"][1])
        phone_b = await _connect(relay["url"], perrs, user=relay["phone_b"][0], password=relay["phone_b"][1])
        spy = await _connect(relay["url"], aerrs, seed=relay["a"][1])
        try:
            async def answer(msg):
                await msg.respond(b'{"ok":true,"token":"secret-session"}')
            await zone_b.subscribe("wyrd.zone.beta.mcp.login", cb=answer)
            await zone_b.flush()
            assert await _denied(spy, aerrs, "wyrd.zone.beta.mcp.login")
            assert await _denied(spy, aerrs, f"_INBOX.{relay['phone_b'][0]}.>")
            reply = await phone_b.request("wyrd.zone.beta.mcp.login", b"{}", timeout=5)
            assert b"secret-session" in reply.data
            assert not any("violation" in e.lower() for e in zerrs + perrs), zerrs + perrs
        finally:
            for c in (zone_b, phone_b, spy):
                await c.close()
    _run(go())


def test_phone_scoped_to_its_zone(relay):
    async def go():
        errs = []
        phone_a = await _connect(relay["url"], errs, user=relay["phone_a"][0], password=relay["phone_a"][1])
        try:
            for subject in ("federation.inference.stream.*", "federation.inference.stream.beta.>",
                            "wyrd.tunnel.beta.*.down", "wyrd.tunnel.alpha.*.open", "_INBOX.>",
                            f"_INBOX.{relay['phone_b'][0]}.>"):
                assert await _denied(phone_a, errs, subject), subject
            assert not await _denied(phone_a, errs, "wyrd.tunnel.alpha.s1.down")
        finally:
            await phone_a.close()
    _run(go())


def test_phone_may_knock_on_another_zone_and_nothing_else(relay):
    """A phone of zone alpha knocks on zone beta's door (a stranger's one request) and gets
    beta's answer on its own inbox; it cannot send beta any other request."""
    async def go():
        zerrs, perrs = [], []
        zone_b = await _connect(relay["url"], zerrs, seed=relay["b"][1])
        phone_a = await _connect(relay["url"], perrs, user=relay["phone_a"][0], password=relay["phone_a"][1])
        try:
            seen = []
            async def answer(msg):
                seen.append(msg.subject)
                await msg.respond(b'{"ok":true,"status":"pending"}')
            await zone_b.subscribe("wyrd.zone.beta.>", cb=answer)
            await zone_b.flush()
            reply = await phone_a.request("wyrd.zone.beta.directory.knock", b"{}", timeout=5)
            assert b"pending" in reply.data
            await phone_a.publish("wyrd.zone.beta.mcp.login", b"{}")
            await phone_a.flush()
            await asyncio.sleep(0.5)
            assert seen == ["wyrd.zone.beta.directory.knock"], seen
            assert any("violation" in e.lower() for e in perrs), "the other request is refused"
        finally:
            for c in (zone_b, phone_a):
                await c.close()
    _run(go())


def test_password_household_scoped_too(relay):
    async def go():
        errs = []
        nc = await _connect(relay["url"], errs, user=relay["gamma"][0], password=relay["gamma"][1])
        try:
            assert await _denied(nc, errs, "between.alpha.>")
            assert await _denied(nc, errs, "_INBOX.>")
            assert not await _denied(nc, errs, "between.gamma.>")
        finally:
            await nc.close()
    _run(go())
