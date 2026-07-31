# Wyrdsekai Relay

A relay is the public meeting point for your household: zones connect out
to it, phones connect in through it, and federation traffic between zones
rides it. It is a dumb pipe — NATS routes subjects, Caddy terminates TLS,
nothing is inspected or stored. Between envelopes stay Ed25519-signed
end-to-end; the relay operator sees subjects and traffic metadata, not
content (payload privacy is the argot/X25519 layer's job).

Run your own. A relay you control is
the right trust boundary — nobody else's outage or policy can cut your
household off.

## The whole setup

```
VPS:    sh packaging/relay.sh relay.example.com   # → up + one-time join token
zone:   wyrd relay join wyrdjoin://…              # paste the token it printed
phone:  wyrd phone invite                         # → QR, scan/paste in app
```

That's it. Everything below is detail for operators who want it.

### One trust model, no web PKI

Nothing browses a relay, so it never needs a publicly-trusted
certificate. TLS is the **household CA** (generated once by certinit),
and every device learns to trust it from **invite material the steward
carries**:

- the **join token** relay.sh prints embeds the CA's fingerprint — the
  zone verifies the relay against it before enrolling;
- the **wyrdphone:// invite** embeds `fp`/`ca_fp` — the app pins the
  relay the moment you paste or scan.

No TOFU, no first-contact leap, no certificate prompts — and the relay
never fights your web server for ports 80/443.

### relay.sh

One optional argument, `<domain-or-ip>[:port]` — the address your
devices will dial. Omit it and the script uses the first non-loopback
LAN IP; that address only seeds the dial default baked into join tokens
(the leaf cert covers every interface IP and devices pin the household-CA
fingerprint, not the hostname, so any reachable address works and a
per-token override always wins). Default port 4443; any port works
(`RELAY_PORT` moves it; invite material carries host:port so clients
follow). The
script locates the bundle (or clones it), generates per-deploy random
sidecar + phone credentials with detected cert SANs, brings the relay
up, waits on `/health`, and prints the join token:

```
wyrd relay join wyrdjoin://relay.example.com:4443/<code>.<ca_fp>
```

Two install modes, picked automatically:

- **docker** (default when docker + compose v2 are present) — the
  compose stack below; state lives in named volumes, config in
  `$BUNDLE_DIR/.env`.
- **native** (`--native`, or automatic when docker is absent) — no
  containers: pinned static `nats-server` + `caddy` binaries, a python
  venv for the registration sidecar, the same `gen-cert.sh` for the
  household CA. Everything lands under `/opt/wyrdsekai-relay`
  (override: `WYRD_RELAY_PREFIX`). As root with systemd it installs
  `wyrd-relay-{nats,registration,caddy}` units (survives reboot);
  otherwise it falls back to nohup. The NATS websocket + registration
  ports bind loopback-only — caddy is still the only public TLS front,
  exactly like the docker-internal network.

Re-running upgrades in place (never rotates identity or credentials);
`--reset` wipes the relay identity and starts over. `sh relay.sh
uninstall` cleanly removes whichever install exists — docker: containers
+ volumes + `.env`; native: units/processes + the install root.
Uninstall destroys the household CA, so every invite and device pin
issued by the relay dies with it.

### Registration mode, owner & per-tier quotas

`--mode` picks who may join:

- **invite-only** (default) — every join needs a `relay.sh invite` token;
  entrants enter at the **HOUSEHOLD** tier. The right default for a
  private friends/family relay (e.g. relayB).
- **open** — invite-less join; the LAN/firewall perimeter *is* the trust
  boundary. Entrants land at HOUSEHOLD-equivalent. Use for an in-house
  trusted relay (e.g. relay-node).
- **commons** — invite-less self-serve join for a public relay (e.g.
  wyrdsekai.org). Entrants enter at the **FLOOR** tier; a verified
  IdentityOutbox plus Web-of-Trust vouches promote them to VOUCHED. A hard
  per-IP rate-limit applies.

`--owner did:key:z…` records the relay's admin owner at deploy (find a
zone's DID with `wyrd whoami`). Without it the deploy mints a one-time
owner-claim token to redeem with `wyrd relay claim <token>`. The mode is
runtime-changeable via the signed `set-mode` admin op (the flag only seeds
the first value).

**Per-tier quotas are enforced** (`set-policy`, run from the owner zone or
the in-world Warden furnishing):

- `max_registrations` is a **hard cap at the join gate** — a new entrant at
  a full tier is refused (the commons Sybil/flood defense). Defaults: FLOOR
  500, VOUCHED/HOUSEHOLD unlimited. A re-register of an existing node is
  never blocked.
- `max_connections` is a **per-DID ceiling**, detection-grade: the liveness
  reaper stamps `over_connection_limit` on any node over budget, surfaced in
  `relay.sh list` for the operator to `remove`. Defaults: FLOOR 2, VOUCHED 5,
  HOUSEHOLD 20.

> NATS-native *bandwidth* throttling (per-tier max_data/subscriptions) is
> account-scoped and would need a per-tier-account split with federation
> exports/imports — a change pending a live two-zone federation soak, so it
> is **not** wired yet. The registration cap above is the enforced flood
> defense.

### Operator commands (no host arg)

Run on the relay box; they auto-detect docker vs native:

- `relay.sh list` (alias `status`) — every registration, each led by its
  full ledger KEY (the exact `remove` argument), with DID, tier, IDV,
  household, kind, active, **LIVE**, last-seen beneath + the current mode +
  any open abuse reports + connection-overage flags.
- `relay.sh update [new-bundle.tgz]` — redeploy with the flags recorded at
  the last deploy (nothing to remember, identity untouched). With a bundle
  path it extracts and hands off to the NEW code; that is the whole upgrade
  story: `scp` the bundle, `sh relay.sh update wyrdsekai-relay-*.tar.gz`.
- `relay.sh invite [--ttl SECS]` — mint a fresh single-use join token from
  the running relay (no redeploy).
- `relay.sh remove <key|did>` — operator force-kick (takes the ledger KEY from `list`, or a did:key:… which is resolved) (you have root; no node
  signature needed). For node-initiated self-removal use the signed
  `wyrd relay leave` instead, which also fires automatically on
  `wyrd uninstall`. The liveness reaper additionally prunes any node absent
  past its tier window (FLOOR 24h, VOUCHED/HOUSEHOLD 7d).
- `relay.sh backup` / `relay.sh restore <tgz>` — see *Back up your relay
  identity* below.

### One public port (plus the zone leg)

Caddy is the only TLS ingress. One routing table: registration paths
(`/join`, `/register*`, `/phone-invite`, `/status`, …) go to the Python
registration sidecar; everything else — including the WebSocket upgrade
phones use — goes to NATS (plaintext, docker-internal only). NATS
native :4222 is also published: the zone leg dials it directly with
NKey challenge-signature auth (no secret crosses the wire; envelopes
are Ed25519-signed end-to-end). Migrating the zone leg onto the wss
listener — then closing :4222 — is a named follow-up.

### Joining a zone

`wyrd relay join <wyrdjoin://token>` redeems the embedded code at
`/join` (codes are 8-char, single-use, TTL-bound, per-IP rate-limited),
receives the full invite payload (relay URL + embedded CA + NKey
challenge), **verifies the invite's CA fingerprint against the token**,
enrolls, persists `WYRDSEKAI_RELAY_*`, and offers to restart a running
zone so the relay leg comes up. A fingerprint mismatch aborts hard —
that is the on-path-attacker case. Legacy forms still work:
`wyrd relay join <host>[:port] <code>` and
`wyrd relay register '<wyrdrelay://…>'`. In-session, stewards can run
`/relay join <token>` over SSH/telnet.

### Putting the app on a phone

`wyrd phone invite` (zone-side) asks the relay's `/phone-invite` endpoint
to mint a device invite — authorization is proof of registration (the
zone's NKey signature or its household token), so only enrolled zones can
mint. The result is a `wyrdphone://` URL rendered as a QR code (qrencode
or python3-qrcode; URL fallback). The payload carries an ordered relay
list with the phone NATS credential and the fingerprint pins the app
verifies at scan/paste time. In-session: `/invite phone` (steward;
SSH/telnet render ASCII QR, web /app renders a QR image).

### Back up your relay identity

The relay's identity (household CA + leaf, `invite-key`,
`registrations.json`, `owner.json` / grants / `relay-policy.json`) lives in
one place. Losing it means every paired device (zone and phone) must re-pin
from **fresh** invites — the old join tokens and wyrdphone:// URLs all stop
verifying, because they pin a CA that no longer exists. Two commands handle
it, auto-detecting docker vs native:

```
sudo sh relay.sh backup                 # → relay-identity-<host>-<UTC>.tgz
sudo sh relay.sh backup --out path.tgz  # custom output path
```

Do this **before** a `--reset` and **before** migrating the relay to a new
box. Keep the archive offline — it carries the CA + invite key.

To move a relay to a new host (or recover one) without re-pairing any
device:

```
# on the new box: deploy first (creates fresh identity + volumes/prefix),
sudo sh relay.sh <host>
# then restore the OLD identity over it and restart:
sudo sh relay.sh restore relay-identity-<host>-<UTC>.tgz
```

Restore stops the relay, swaps in the archived identity (docker: into the
`relay_data`/`relay_certs` volumes, resolving the compose-prefixed names
automatically; native: into `$WYRD_RELAY_PREFIX/{data,certs}`), and brings
it back up. Because the household CA is unchanged, every existing device pin
stays valid.

Re-running `relay.sh` to deploy never rotates this material; only `--reset`
or `uninstall` destroys it.

### Security ledger

- The trust anchor is steward-carried invite material — join token for
  zones, wyrdphone:// URL for phones. No web PKI: CT logging and
  revocation protect nothing when nothing browses the endpoint;
  compromised-relay recovery is redeploy (`--reset`) + re-invite.
- Join codes and invites are single-use / TTL-bound; the NKey enrollment
  underneath is unchanged.
- `/phone-invite` requires a registered zone's signature or token — the
  phone credential is invite material a steward hands out, not a constant
  baked into the app. Both internal NATS credentials are randomized
  per-deploy.
- Rate limits key on the X-Forwarded-For client (Caddy fronts the HTTP
  surface); localhost-only gates deliberately keep the socket address.
- :4222 (zone leg) is NKey-only for households; the firewall line is
  "open RELAY_PORT and 4222".

### Files

| File | Role |
|---|---|
| `relay.sh` (in `packaging/`) | one-shot installer (both modes) |
| `Dockerfile` | the single-container ("all-in-one") image: nats + caddy + registration + first-boot cert-gen |
| `aio-entrypoint.sh` | container PID-1 — seeds the conf, runs cert-gen, then supervises the three processes |
| `Caddyfile` | the single routing table, one household-CA listener |
| `relay.conf` | NATS template (system users; households appended at enroll) |
| `registration.py` | enrollment sidecar (/join, /register-nkey, /phone-invite, …) |
| `certinit/gen-cert.sh` | household CA + leaf cert generator (run at first boot) |

Docker mode is ONE container (`relay.sh --docker` builds `Dockerfile` and
`docker run`s it). Native mode (`relay.sh --native`) runs the same three
processes as host services — no docker. Manual run (power users):
`docker build -t wyrdsekai-relay:aio . && docker run -d --env-file .env
-p 4443:4443 -p 4222:4222 -v relay-certs:/certs -v relay-data:/var/lib/wyrd-relay
wyrdsekai-relay:aio`, with `RELAY_HOST_NAMES`/`RELAY_HOST_IPS` in the env for
the cert SANs. relay.sh wraps exactly this.
