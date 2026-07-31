# Security Model

Wyrdsekai's trust boundary is the **household**, not the process and not the
cloud. Everything below describes what the shipped code actually enforces —
and, in the last section, what it does not.

## Reporting a vulnerability

**Do not open a public issue.**

Email **wyrd@wyrdsekai.org**. We acknowledge receipt within **3 business days**
and give you an initial assessment within **14 days**. If a fix is warranted we
aim to release it and publish an advisory within **90 days** of the report — the
usual disclosure window — and sooner when an issue is being actively exploited.
If we need longer, we will tell you why rather than let the deadline pass in
silence.

Unless you ask us not to, we will credit you in the advisory and the release
notes; tell us how you would like to be named. There is no bug bounty. We will
not pursue or support legal action against anyone reporting in good faith who
stays in scope, avoids privacy violations and service disruption, and gives us
reasonable time before disclosing.

In scope: the server (`server/`, `core/`, `between/`), the wire protocols
(WebSocket, Telnet, SSH, Between/NATS), authentication and authorization, the
relay and its registration sidecar, the mobile clients' trust stores, agent
safety systems, and the mesh update / release-signing path.

Supported: the latest release fully, the previous release for security fixes,
nothing older.

## Identity and zone auth

Every node holds one Ed25519 keypair (`node-identity.json`, encrypted at rest).
That single key is the household fingerprint (`did:wyrd:z6Mk…`), the signer on
every `BetweenEnvelope`, and the NATS credential a zone presents to a relay.

Relay transport auth is **per-node NKey**: the NATS client derives a 56-char
NKey public key from the node's Ed25519 public key and signs a server-issued
nonce at connect time. The private key never leaves the node (mode `0600`); the
public key is safe to store in the relay's registration records and config.
There is no shared household secret to drift, and nonce signing — delegated to
NATS, we do not roll our own — prevents replay. If a node loses its key, remove
its public key from the relay and let it generate a fresh identity on reinstall.

Between envelopes are Ed25519-signed **end to end**. A relay operator sees
subjects and traffic metadata, not content.

## The steward role

The first account registered on a fresh zone automatically becomes the
**steward**. Creating it also generates a one-time recovery key and
**auto-closes open registration**, so the window in which anyone can claim a
zone is exactly one account wide.

Steward is root, on the Unix model: the permission check short-circuits to
`true` for the role. Everything else is granular — `agent:create`,
`agent:config`, `budget:set`, `trust:manage`, `safety:set`, `mcp:manage`,
`topology:manage`, `member:manage`, `room:script`, `export`. Steward-gated
surfaces include user administration, invite minting, parental controls,
maintenance operations, zone/home admin, and steward-only familiar tools.

There can be more than one steward — a steward may promote any member — with
two guardrails: the last steward cannot be unregistered or deactivated, and
non-stewards cannot modify stewards at all.

Recovery: an 8-word recovery key (64 bits, `SecureRandom`, from a 256-word
list) is shown once at steward creation; only its bcrypt hash is stored.
`wyrd recover <key> <new-password>` resets the steward password.

## Invite and redeem

Accounts after the first require an invite. `wyrd invite create <name>
[--role member|guest|child]` mints a 6-word passphrase; `wyrd invite bootstrap`
mints the one-time steward invite and only succeeds while zero accounts exist.
Redemption is atomic — a single conditional `UPDATE` on the unconsumed,
unexpired row. Before that fix a lost race still created an account, so one
single-use (possibly steward-role) invite could mint duplicate or elevated
accounts.

Passwords are hashed with bcrypt at cost 12. Unknown usernames are verified
against a real dummy hash at the same cost, so login timing does not leak
whether an account exists. Login is rate-limited per source IP **and** per
targeted account, returning 429 before bcrypt runs, on the HTTP and SSH
surfaces alike.

Relay join codes are 8 characters, single-use, TTL-bound, and per-IP
rate-limited. Relay invite tokens are 256-bit, HMAC-SHA256-signed with a key
generated on first run and stored `0600`.

## Credential isolation

**Item-script credentials** live in an encrypted, mode-`0600`
`credentials.safe` under the data dir. `wyrd cred set` takes values on stdin —
never argv — and re-executes as the data dir's owner so the safe stays readable
by the service that needs it.

**Subprocess egress** goes through `EgressGate`, enforcing by default: the
inherited environment is cleared and only an allowlist is restored (`PATH`,
`HOME`, locale/TZ/TERM, `TMPDIR`, and the coding-backend provider vars),
deliberately excluding `SSH_AUTH_SOCK` and every `*_KEY` / `*_TOKEN` /
`*_SECRET`. Every coding backend and CLI skill executor routes through it.

**SSH public keys** submitted through relay registration are accepted only as a
bare `ssh-ed25519 <base64> [comment]` line, with blob length and header
verified, so a registrant cannot smuggle `command=` or `permitlisten` options
into `authorized_keys`. **Release artifacts** are Ed25519-signed and verified by
`wyrd verify-release`.

## Per-household NATS accounts and ACL scoping

The relay writes one NATS account per registered zone, plus one phone account
per household, with subjects scoped to the registrant's own zone label. A
labelled zone node is granted:

```
publish:    between.{zone}.>  federation.>  wyrd.zone.{zone}.>
            wyrd.tunnel.{zone}.>  wyrd.discover.>  _INBOX.>
subscribe:  the same, plus between.*.*.*.capability.announce
```

A household **phone** account is scoped far tighter:

```
publish:    wyrd.zone.{zone}.>  wyrd.discover.>  _INBOX.>
            wyrd.tunnel.{zone}.*.open|.up|.close
            between.{zone}.*.*.study.state|.sync
subscribe:  wyrd.tunnel.{zone}.*.down  _INBOX.>
            between.{zone}.*.*.study.state|.sync
```

Publish is scoped to the three client-to-server tunnel verbs, so a household
phone cannot spoof server-side `.down` frames into a sibling's session.
Subscribe is scoped to `.down` only: the login session token rides the `.open`
payload, and a broad `wyrd.tunnel.>` subscribe previously let one household
phone harvest a sibling's session token — full account impersonation. Phone
credentials are **derived, not stored**:
`HMAC-SHA256(master-phone-secret, household_tag)`, so rotating the master
rotates every household's credential at once.

## The relay tunnel session model

The relay tunnel is a dumb pipe: the zone subscribes
`wyrd.tunnel.{zoneId}.*.{open,up,close}` and publishes `{session}.down`, and
the relay shuffles bytes without ever parsing them.

A session id is a **capability, not a correlation key**. Clients mint 128
CSPRNG bits — `SecureRandom` on Android, `SecRandomCopyBytes` on iOS,
`crypto.getRandomValues` in React Native. The server validates shape only
(16–64 chars, `[A-Za-z0-9_-]`), the length bound preventing a flood from
growing the session map with long keys.

On `open`, the handler opens a **loopback** WebSocket to the zone's own `/ws`
carrying the session token, so the tunnel authorizes exactly what that token
authorizes — it makes no authorization decision of its own. Token validation
happens in the WebSocket layer, which closes with 4001 on an invalid or expired
token; session tokens live 7 days. Live sessions are capped at 64 per zone
(excess gets a `tunnel_busy` frame) because household phones share one relay
NATS account — otherwise a buggy or hostile device could exhaust the zone's
memory and loopback sockets. Pending uplink frames are capped at 64 per session.

## Household TLS trust on phones

**The shipped mechanism is invite-fingerprint pinning, not TOFU.** The pairing
invite carries the household CA fingerprint and the app pins it at scan or paste
time — no cleartext bootstrap, no certificate prompt. The TOFU design is a
discarded alternative.

Clients install a per-host `X509ExtendedTrustManager` that tries the system
trust manager first, then the pinned household CA. The pinned material is the
**CA**, not the leaf, so relay certificate rotation does not force every device
to re-pin; validation is a real path build against a keystore seeded with only
that CA. Pins live in platform secure storage — `expo-secure-store` (React
Native), `EncryptedSharedPreferences` (KMP Android), Keychain with
`kSecAttrAccessibleAfterFirstUnlock` (iOS). No CA is bundled in the app.

A relay never needs a publicly-trusted certificate, because nothing browses it.
That also means it never fights your web server for ports 80/443.

Since 2026-07-24 the React Native Android trust manager includes a **host-key
fallback**: if system trust rejected the chain and no pin matches the resolved
host, it tries every pinned household CA. This exists because
`SSLSession.peerHost` is frequently null, so host extraction falls back to
reverse DNS — silently breaking login to any DNS-named relay while
IP-addressed relays worked. It is a deliberate relaxation of host-to-pin
binding; see Known limitations.

## Content gating

**`OutputSanitizer`** scans tool output for prompt injection before it reaches
an agent. Patterns come from `SecurityPatternManager` (seed, user, and built-in
trust tiers), not from hardcoded rules. Three modes: `BLOCK` replaces each match
with `[BLOCKED]`, `WARN` passes text through but flags it, `LOG_ONLY` records
only. It runs on skill/tool output, library tool responses, `SKILL.md` skill manifests
imports, and — in WARN mode — all in-world speech.

**`ContentQuarantine`** handles external content entering the inference
context: it strips zero-width and bidi characters and HTML tags, detects a fixed
set of injection phrasings, tags content by source, and fences it in explicit
data markers. It is applied to MCP web results.

**`ActionGrantCheck`** is the owner-issued per-action grant axis
(`home://owner/action/{name}`). Maturity-tier gating runs first; grants are a
second axis on top and never elevate tier. The server wires it in strict mode.
**`AutonomyGate`** adds the consent axis, for non-human-directed actions only:
`AMBIENT` and `VISIBLE` verbs pass, `CONSENT` verbs pass unless
`WYRDSEKAI_ACTION_STRICT_GRANTS=true`, and `FORBIDDEN` verbs never fire
autonomously without an explicit owner grant. `emergency_call` is a safety
floor that is never consent-blocked. **MCP tool access** can be locked to
steward approval at setup time (`WYRDSEKAI_MCP_STRICT_GRANTS`), with grants
handed out through the in-world Tool Warden.

## Hardening landed 2026-07-25

**Relay infrastructure passwords are generated per install.** The
`relay_sidecar`, `relay_phone`, and `relay_join` accounts previously fell back
to literal constants in the source. Published, that meant every relay on the
internet shipped with the same known password — and `relay_join` alone would
let a stranger drive node registration on anyone's relay. They are now
`secrets.token_urlsafe(32)`, persisted to a mode-`0600` `relay-secrets.json` on
first run, with no literal fallback in the entrypoint or the config template.

**Tunnel session ids are 128-bit CSPRNG capabilities.** They were
milliseconds-hex plus 32 bits of non-cryptographic random — low-entropy and
largely predictable from the clock. Both clients now use platform CSPRNGs, and
the server rejects malformed ids.

**`wyrd.zone` and `wyrd.tunnel` ACLs are zone-scoped.** They used to be blanket
grants in both directions for every household node, which let any registered
household impersonate another zone's MCP surface — publishing replies on the
phone request/reply channel that carries login — and read or inject other
households' tunnel sessions.

All three are covered by regression tests.

## Known limitations

We would rather you knew these than discovered them.

**Tunnel sessions.** Within a household they are not per-session
authenticated: ids are client-chosen and static NATS ACLs cannot express
"sessions you own", so a sibling device that learns or guesses an id can inject
`.up` frames or read `.down` — 128 bits of entropy is the only mitigation.
There is no idle timeout, TTL, or per-session revocation; the only bounds are
the 64-session cap and the 7-day `/ws` token. An `open` frame with no token
yields a guest session rather than a rejection. React Native falls back to
`Math.random()` for ids if `crypto.getRandomValues` is unavailable — it logs
loudly, but it proceeds.

**Relay ACLs and credentials.** Zones registered without a zone label keep the
legacy broad grant (`between.>`, `wyrd.zone.>`, `wyrd.tunnel.>`) until they
re-register. `federation.>` is not scoped per agreement in either direction —
that needs agreement-aware permissions. The deprecated shared `relay_phone`
account still holds blanket `wyrd.zone.>` / `wyrd.tunnel.>` so pre-per-household
invites keep working; re-mint old invites. The `peer_trainer` account in the
shipped `relay.conf` still carries the `__GENERATED_ON_FIRST_RUN__`
placeholder — no code path replaces it, so operators using peer-training
subjects must set it by hand. NATS account passwords are cleartext in
`relay.conf` and the phone credential travels cleartext inside the
`wyrdphone://` payload (inherent to NATS static auth — treat invite material as
secret). Password-mode relay auth still exists; the "remove password mode"
phase has not landed, and there is no per-node revocation-list propagation
between peer relays. `wyrd.discover.>` is globally readable and writable by
design — phones need it to learn a zone label first.

**Phone TLS trust.** The React Native Android host-key fallback deliberately
relaxes host-to-pin binding: any pinned household CA can validate any host
system trust rejected, so a phone enrolled in two households can have one
household's CA vouch for the other's hostname. The Kotlin Multiplatform
Android client has no such fallback and still fails against DNS-named relays
whose reverse DNS differs from the pinned name — a real client-parity gap.
**iOS app-layer pinning status is contradictory in-repo; do not assume it** —
until it is confirmed, the iOS posture trusts any system-trusted root, so a
compromised public CA could intercept the relay connection. CA rotation
policy is not implemented.

**Gating.** `EgressGate` does not block network egress — it scrubs credentials
from the subprocess environment; OS-enforced isolation (netns/nftables) is a
follow-up. `ActionGrantCheck` defaults to fail-open when strict mode is off (the
server wires strict on; embedders inherit the permissive default). Speech-path
injection scanning is WARN-only — flagged, never redacted. `OutputSanitizer`
ships no built-in patterns, so an unseeded pattern manager means zero
enforcement, and invalid regexes are skipped with a warning.
`ContentQuarantine` is a fixed English-language denylist plus
invisible-character stripping: it raises cost, it is not a boundary.

**Account primitives.** Minimum password length is 4 characters. Session tokens
are UUIDv4 strings (122 bits from `SecureRandom`) and changing a password
deliberately leaves existing sessions valid. Invite passphrases are 6 words from
a 256-word list — 48 bits, so the TTL and single-use consumption carry the
security, not the length. bcrypt password hashes replicate across the household
mesh on account creation.

**Cryptography is JDK-native with one exception.** Ed25519 signing and
AES-256-GCM soul encryption use the JDK's own primitives; password hashing uses
`at.favre.lib:bcrypt`, a reviewed implementation, in preference to a hand-rolled
key-derivation function.
