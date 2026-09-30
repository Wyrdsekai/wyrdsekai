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

Relay transport auth is **per-node NKey** by default. The NKey is a second
key kept in `node-identity.json` beside the Ed25519 key; it is not derived
from it. At connect time the node signs a nonce the relay sends. The private
half never leaves the node (mode `0600`); the public half is what the relay
stores. There is no shared household secret to drift, and nonce signing, done
by NATS rather than our own code, prevents replay. If a node loses its key,
remove its public key from the relay and let it generate a fresh identity on
reinstall.

A relay password is still available, but only when you ask for it
(`wyrd relay join --password-mode`). Before 0.5.0, `wyrd relay join` on Linux
and macOS registered that way by default. A password-mode household proves
its password to the relay's web endpoints with an HMAC over a timestamped
challenge, so the password itself only travels when the node connects, over
the encrypted link.

Between envelopes are signed with the sending machine's Ed25519 key. Every
machine checks each envelope before acting on it. A household machine's key
comes from the household roster (the machines enrolled with `wyrd join`), or is
pinned the first time an unenrolled machine says hello. Another zone's key is
pinned when the two zones first agree to federate. An envelope that is
unsigned, signed by the wrong key, from an unknown sender, more than 5 minutes
off this machine's clock, or already seen, is dropped and logged. A pinned key
changes only through a rotation message signed by the old key.

Signing proves who sent a message. It does not hide it. A relay operator sees
subjects, traffic metadata, and the content of Between messages that pass
through the relay.

`WYRDSEKAI_ENVELOPE_VERIFY=soft` (log but still deliver) and `=off` exist only
for a household still running older machines. Both log a warning at start.
`WYRDSEKAI_ENVELOPE_MAX_SKEW_SECONDS` widens the clock window (default 300).

## The steward role

The first account made on a new zone becomes the **steward**. Making it also
makes a recovery key and closes open registration, so anyone can claim a new
zone for exactly one account. Only one account can be the first: if two people
register at the same moment, the database lets exactly one of them through,
and the other is refused. This holds for SQLite and for PostgreSQL.

Every account has one of four roles: `steward`, `member`, `guest` or `child`.
Only the steward role carries extra power. A request that needs a steward is
allowed when the account's role is `steward`, and refused otherwise. There
are no finer-grained permissions. For local tools, some steward routes also
accept this node's operator token, and only from the machine itself.
Steward-only actions include adding and removing people, changing roles,
making invites, parental controls, quiet hours, maintenance, zone and home
settings, the vault, and the steward-only familiar tools. Member, guest and
child are labels for the household. None of them can do what needs a steward. A steward sets parental controls per
account.

A household can have more than one steward. A steward can make any account a
steward. The household always keeps at least one: the last steward cannot be
demoted or removed. This is checked in one place for every way in: the web
and phone app, the roster in the Study (from any client, SSH and telnet
included), and account copies between the household's machines. The check
holds the steward records while it works, so two stewards demoting or removing
each other at the same moment still leave one. A steward cannot remove their
own account. Members, guests and children cannot change anyone's role.

Recovery: the recovery key is 8 words drawn with `SecureRandom` from a list
of 264 words, about 64 bits. It is shown once, and only its bcrypt hash is
stored. Every way of making the first account shows it: registering on the web
or in the phone app, redeeming the install's bootstrap invite (over SSH, the
web, the phone app or telnet), creating the first account over SSH (with a key
placed before the first connection) and over telnet.
`wyrd recover <key> <new-password>` resets the steward password and ends every
session the steward had. Recovery and factory reset (`/api/auth/recover`,
`/api/auth/reset-zone`) need no login, by design, and are throttled like
login: five wrong keys in a minute lock the source address, and the recovery
key itself, for five minutes.

## Invite and redeem

Accounts after the first require an invite. `wyrd invite create <name>
[--role member|guest|child]` mints a 6-word passphrase; `wyrd invite bootstrap`
mints the one-time steward invite and only succeeds while zero accounts exist.
Redeeming the bootstrap invite founds the household in the same way as the
first registration, so it is refused once any account exists.

Redemption is atomic on every surface: web, phone app, SSH and telnet. The
invite is claimed with one conditional database update before the account is
made, so one code makes at most one account, even when several people redeem
it at the same moment. Before this, a lost race still made an account, and
telnet did not claim the invite at all.

Passwords are hashed with bcrypt at cost 12. Unknown usernames are verified
against a real dummy hash at the same cost, so login timing does not leak
whether an account exists.

Failed password logins are counted in two ways. Per account, the count is
shared by the web and phone login (`/api/auth/login`), the MCP login
(`/api/mcp/login`), SSH and telnet, so trying another way in does not start
again. Per source address, each of those ways in keeps its own count. Five
failures within a minute lock that account, or that address, for five
minutes. Closing the connection and reconnecting does not reset the count. A
locked login is refused before the password is checked; the web answers 429.
The phone app's login over NATS, the way it logs in through the relay, counts
failures per account on its own.

Relay join codes are 8 characters, single-use, TTL-bound, and per-IP
rate-limited. Relay invite tokens are 256-bit, HMAC-SHA256-signed with a key
generated on first run and stored `0600`.

## Credential isolation

**Item-script credentials** live in an encrypted, mode-`0600`
`credentials.safe` under the data dir. `wyrd cred set` takes values on stdin —
never argv — and re-executes as the data dir's owner so the safe stays readable
by the service that needs it.

**Can use, cannot read.** An item script never holds a credential.
`world.safe.get(slot)` returns a reference, `{{safe:slot}}`, not the value.
When the reference is in a request header (`world.web.*` or the raw `http`
global), the secret is put in its place as the request is built, and any copy
of the secret in the answer is replaced with `[secret]` before the script sees
it. The slot must be in the manifest's `safe_slots` with the `safe.get`
capability, and the request can only go to the manifest's `external_domains`.
A reference in a URL or body is not resolved. Adapters and MCP services read
their keys on the server side and never pass them to a script. MCP service keys
come from The Safe first, then from `WYRDSEKAI_MCP_KEY_*` in the environment.

**Subprocess environments.** No program the node starts inherits the node's
environment. Each path clears it and keeps only what that path needs:

- Coding backends and CLI skills go through `EgressGate` (on by default):
  `PATH`, `HOME`, locale, `TMPDIR`, `TZ`, `TERM`, `OPENAI_HOST`, `GOOSE_PROVIDER`,
  `GOOSE_MODEL`. No credential is on this shared list. A backend's own key
  reaches only that backend: CodeZaiku gets `CODEZAIKU_AUTH_TOKEN` (from The
  Safe, or from the node's environment if the operator exported it there), goose
  gets its provider's key, and so on.
- Everything else goes through `SubprocessEnv`: a base of `PATH`, `HOME`, `USER`,
  locale, `TZ`, `TMPDIR`, `TERM`, proxy and CA-file settings (and the Windows
  system variables a program needs to start), plus a short list per path:
  - Python skills: `PYTHONIOENCODING`, `PYTHONUTF8`.
  - Recipe shell steps: the node's own `WYRDSEKAI_*` settings whose names do not
    contain KEY, TOKEN, SECRET, PASS, CRED, AUTH, SEED, COOKIE or SESSION (the
    data directory, the database URL, model paths), GPU selection, model-cache
    locations and `JAVA_HOME`.
  - MCP servers started over stdio: XDG directories, `NODE_EXTRA_CA_CERTS`,
    `JAVA_HOME`, plus the names the service entry lists in `pass_env`.
  - keybase: `XDG_RUNTIME_DIR` and the XDG directories, `KEYBASE_RUN_MODE`,
    `KEYBASE_SOCKET_FILE`.
  - signal-cli: `JAVA_HOME`, `JAVA_OPTS`, `SIGNAL_CLI_OPTS`, XDG data and config.
  - The Claude CLI: its own key and settings (`ANTHROPIC_API_KEY`,
    `ANTHROPIC_BASE_URL`, `ANTHROPIC_MODEL`, `CLAUDE_CODE_OAUTH_TOKEN`,
    `CLAUDE_CONFIG_DIR`), `XDG_CONFIG_HOME`, `NODE_EXTRA_CA_CERTS`.

  So `WYRDSEKAI_CRED_*`, `WYRDSEKAI_MCP_KEY_*`, cloud keys and `SSH_AUTH_SOCK`
  reach none of them. `WYRDSEKAI_SUBPROCESS_FULL_ENV=true` restores full
  inheritance for these paths (a transition setting; the node logs a warning).

Neither gate blocks the network: a started program can reach what the machine
can reach, without the node's keys.

**SSH public keys** submitted through relay registration are accepted only as a
bare `ssh-ed25519 <base64> [comment]` line, with blob length and header
verified, so a registrant cannot smuggle `command=` or `permitlisten` options
into `authorized_keys`.

**Release artifacts** are verified two ways. Every release publishes a
`SHA256SUMS` file — check your download against it. From v0.1.5 onward,
releases additionally carry Sigstore keyless attestations: publishing a
release triggers the repository's `release.yml` workflow, which verifies each
asset against `SHA256SUMS` and signs its hash via GitHub OIDC + Fulcio +
Rekor, uploading an `<asset>.sigstore.json` bundle next to it. Download the
bundle alongside the artifact and run

```
wyrd verify-release wyrdsekai_X.Y.Z_amd64.deb
```

The verifier is embedded in the `wyrd` binary (sigstore-java — no `cosign`
install needed) and walks the full chain against a trust root baked in at
build time: Fulcio certificate chain, Rekor inclusion proof, and a pinned
workflow identity (`Wyrdsekai/wyrdsekai/.github/workflows/release.yml` at a
`v*.*.*` tag). A bundle signed by any other repo, workflow, or ref fails
closed. Releases before v0.1.5 have no bundles — for those, `SHA256SUMS`
over HTTPS is the verification story.

## Encryption inside the household

Traffic between the machines of one household, and each person's own
connection, is encrypted. Until 0.5.0 it was not: the web port, the household
bus and the phones' websocket were plain on the home network, and the bus had
no login at all.

**The household's own certificate authority (CA).** Each home makes one CA the
first time it starts and keeps it (`<data>/tls/household-ca.pem`; its key
`household-ca.key` is mode `0600`). The CA signs this machine's certificate,
which names the machine's host name, `<host name>.local`, every network
address it has, `localhost` and `127.0.0.1`. That certificate lasts 397 days.
At each start the node issues it again when it has less than 30 days left, when
an address it should name is missing, or when the CA did not sign it. The CA is
never replaced on its own. Pairing invites carry the CA's fingerprint
(`home_ca_fp`) and the home's address on the network (`lan_https`); phones and
other household machines trust that CA and nothing else.

**Ports.**

| Port | Who reaches it | Protection |
|------|----------------|------------|
| 7443 | Everyone on the network: phones, browsers, other machines | HTTPS and WSS with the household certificate |
| 7070 | This machine only (local tools, the relay tunnel's loopback leg) | none (it never leaves the machine) |
| 4222 | The node and household machines that joined | TLS with the household certificate, and a login |
| 4223 | Phones (the household bus over websocket) | TLS with the household certificate, and a login |
| 7071 | This machine only (telnet, or the browser terminal) | none; the browser terminal is HTTPS when opened to the network |
| 7022 | Everyone on the network | SSH |

**Logins on the household bus.** No client connects without a login. The node
has its own. A machine that joins with `wyrd join` gets its own login in the
join reply, over HTTPS pinned to the hub's CA. Each paired phone gets its own
login in the pairing reply (`nats_user`, `nats_pass`). Passwords are random;
the bus's settings keep only their bcrypt hashes. Revoking a phone removes its
login. A phone may use its own zone's requests and tunnel, and only its own
reply inbox (`_INBOX.<its login>.>`). Study sync and inference go under its
login name: it sends Study frames as `between.{zone}.<its login>.*`, hears only
the frames addressed to it, and hears only the inference answers it asked for
(`federation.inference.stream.<its login>.*`). One person's phone cannot read the
replies, Study frames (which carry a session token) or answers meant for
another person's.

**Keys and secrets on disk.** `node-identity.json`, the CA key, the machine's
certificate key, the web keystore's password and the bus logins are readable by
the node's user only (`0600`). The machine's certificate key may also be read by
the packaged NATS service's own group (`0640`), where that service is
installed (Linux packages, where it runs the household bus as its own user). The web keystore's password is a random secret made once per install;
until 0.5.0 it was the word `wyrdsekai`.

**Transition settings.** Phone apps and household machines from before 0.5.0
know none of this. Each of these settings re-opens one old plain door. All are
off by default, each one is logged as a warning at every start, and
`wyrd doctor` names every one that is on:

- `WYRDSEKAI_HTTP_LAN_PLAINTEXT=true`: port 7070 answers the network again,
  unencrypted, next to 7443.
- `WYRDSEKAI_NATS_LAN_PLAINTEXT=true`: the household bus also accepts clients
  with no login and no encryption, from the whole network; the phones'
  websocket on 4223 is plain again. Anyone on the network can then act as any
  member, as before 0.5.0.
- `WYRDSEKAI_HTTP_BIND`, `WYRDSEKAI_TELNET_BIND`, `WYRDSEKAI_WEB_TERMINAL_BIND`
  set to a network address open those ports to the network.

## Per-household NATS users and ACL scoping

All relay users share one NATS authorization list; there are no separate NATS
accounts. What keeps households apart is that each user's permissions name its
own zone.

**Every registration is bound to one zone.** A node sends its zone's name
(`WYRDSEKAI_ZONE_ID`) when it registers, and signs the request with its NKey.
On a given relay a zone name belongs to the first registration that claims
it. Another household asking for the same name is refused, and told to pick
another. A second node of the same household can share the name once a node
that holds it vouches for it (`wyrd relay zone-add <its NKey>`). The relay
also assigns each registration's household tag itself, because the tag names
the household's phone user.

A zone-bound node is granted:

```
publish:    between.{zone}.>  federation.>  wyrd.zone.{zone}.>
            wyrd.tunnel.{zone}.>  wyrd.discover.>
            plus one reply to each request it receives (allow_responses)
subscribe:  between.{zone}.>  between.*.*.*.capability.announce
            federation.{zone}.>  federation.*.{zone}.gate.>  federation.*.{zone}.tell
            federation.inference.{zone}.complete  federation.inference.stream.{zone}.>
            federation.recipe.{zone}.run  federation.recipe.result.{zone}.>
            federation.zonegrant.{zone}.request  federation.zonegrant.result.{zone}.>
            wyrd.zone.{zone}.>  wyrd.tunnel.{zone}.>  wyrd.discover.>
            _INBOX.{its NATS user}.>
```

A node may address any zone's federation mailbox, but it reads only what is
addressed to its own zone. Answers to its cross-zone requests (inference
streams, recipe and zone-grant results) come back under its own zone's name,
because the node names each request `{zone}.{random id}`.

**Reply inboxes are per user.** Every relay client uses the reply inbox
`_INBOX.{its NATS user}` (the NKey, the password user, or the phone user), and
the relay lets each user subscribe only to its own. Before 0.5.0 every relay
user could subscribe to `_INBOX.>` and read every reply on the relay,
including login replies that carry session tokens.

A household **phone** account is scoped far tighter:

```
publish:    wyrd.zone.{zone}.>  wyrd.discover.zone  wyrd.zone.*.directory.knock
            wyrd.tunnel.{zone}.*.open|.up|.close
subscribe:  wyrd.tunnel.{zone}.*.down  _INBOX.phone-{tag}.>
```

That is all a phone needs: its home's request subjects, the tunnel, zone
discovery, a knock on another household's door (the one request a stranger may
make; the home limits how often), and its own replies. Study sync and inference do not ride the relay
for phones, so a phone credential reads no household's Study or inference
traffic. Phones of one household share this user, so they share its inbox.
Replies that must stay private between one phone and the home are sealed end
to end (the sealed tunnel and sealed requests).

**Registrations made before 0.5.0** carry no zone name. Until their node runs
0.5.0, which binds its zone at every start, they keep the old wide grant
(`between.>`, `federation.>`, `wyrd.zone.>`, `wyrd.tunnel.>`, `_INBOX.>`), and
the relay lists them in a warning at boot. While any such registration exists,
it can read the other households' relay traffic. The relay operator ends that
with `RELAY_LEGACY_GRANT=false` once the households have updated. The shared
`relay_phone` user of older phone invites is off unless the operator sets
`RELAY_SHARED_PHONE_ACCOUNT=true`; those phones need a fresh `wyrd phone invite`.

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

**The tunnel is sealed end to end (since 0.5.0).** The phone knows the home's
public key from the pairing invite (`zk`). It opens a session with a fresh key
of its own (`{"v":2,"e":…}`), the home answers with a fresh key, and both work
out two keys, one for each direction, that only they can compute. The session
token then travels inside the first encrypted frame, never in the clear. Every
frame is numbered; a frame that does not open, or arrives out of order, closes
the session. A relay that answers in the home's place cannot open the frames,
because it does not hold the home's private key. The home refuses a session
opened the old way, without a key, unless the steward sets
`WYRDSEKAI_TUNNEL_ALLOW_PLAINTEXT=true` for the change-over. The Android app
(Kotlin Multiplatform) seals every session; a phone paired before 0.5.0 has no
`zk`, so it sends nothing and asks the person to pair again.

On `open`, the handler opens a **loopback** WebSocket to the zone's own `/ws`
carrying the session token, so the tunnel authorizes exactly what that token
authorizes — it makes no authorization decision of its own. Token validation
happens in the WebSocket layer, which closes with 4001 on an invalid or expired
token; session tokens live 7 days. Live sessions are capped at 64 per zone
(excess gets a `tunnel_busy` frame) because household phones share one relay
NATS account — otherwise a buggy or hostile device could exhaust the zone's
memory and loopback sockets. Pending uplink frames are capped at 64 per session.

## The phone's requests through a relay

Besides its session, the phone asks its home short questions, one answer each:
signing in, joining with an invite, sending a tell, searching the library,
writing or reading its journal, pairing, syncing its list of homes, and finding
or knocking on a zone. These go on NATS subjects under `wyrd.zone.{zone}.`,
handled by `McpNatsHandler`. Before 0.5.0 they crossed the relay as plain
text: passwords, session tokens and journal entries, private ones included,
could be read by the relay operator and by any relay user allowed to read
replies.

Since 0.5.0 each request and its answer are **sealed end to end**. The phone
gets the home's public key (`zk`) from the pairing invite. For every request it
makes a fresh key of its own, and both sides work out a pair of keys that only
they can compute (`SealedRequest`, specified in `PROTOCOL.md`). The relay still
passes the messages along, but cannot read them and cannot answer in the home's
place, because it does not hold the home's private key.

The home refuses a sealed request that does not open, one whose time is more
than two minutes away from the home's clock, and one it has already answered in
the last ten minutes. The refusal says only `sealed_refused`. A phone whose
clock is more than two minutes wrong is refused, so let the phone set its time
automatically.

Through the relay, the home refuses unsealed requests with `sealed_required`.
Apps older than 0.5.0 send them unsealed. During the change-over the steward
can set `WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_REQUESTS=true`: the home then answers
them and logs a warning for each one, because the relay can read them and their
answers. On the home's own network unsealed requests are still accepted.

What the relay still sees: which subject was used (for example, that someone
signed in to your zone), when, and how big the messages were. Zone discovery
(`wyrd.discover.zone`, which answers with the zone's name) stays unsealed.

These keys are not forward secret: someone who steals the home's key file
(`tunnel-key.json`) and had recorded old traffic could read those old requests.
The long conversation runs in the sealed tunnel, whose keys are forward secret.

## Household TLS trust on phones

**The shipped mechanism is invite-fingerprint pinning, not TOFU.** The pairing
invite carries the household CA fingerprint and the app pins it at scan or paste
time — no cleartext bootstrap, no certificate prompt. The TOFU design is a
discarded alternative.

Clients install a per-host `X509ExtendedTrustManager`. The pinned material is
the **CA**, not the leaf, so relay certificate rotation does not force every
device to re-pin; validation is a real path build against a keystore seeded
with only that CA, so a chain that merely contains the CA next to someone
else's leaf does not pass. In the Android app (Kotlin Multiplatform) a host
that has a pin is checked against that pin only; system trust applies only to
a host without one, such as a relay with a public certificate. The React
Native trust manager tries the system trust manager first, then the pinned
household CA. Pins live in platform secure storage — `expo-secure-store` (React
Native), `EncryptedSharedPreferences` under the app's own Android Keystore key
(KMP Android), Keychain with `kSecAttrAccessibleAfterFirstUnlock` (iOS). No CA
is bundled in the app.

**A pin that no longer matches is a refusal.** The Android app does not offer to
trust a new certificate. It refuses the connection and tells the person that
the certificate does not match the one the phone was paired with, and to pair
the phone again with a fresh invite. Pins made before 0.5.0 were kept in plain
storage; the app moves them into the encrypted store the first time it starts.

**On the home network** the Android app uses the home's HTTPS address from the
invite (`lan_https`, port 7443) and the home's NATS websocket (wss, port 4223),
both pinned to the household CA whose fingerprint the invite carries
(`home_ca_fp`), with the NATS account the home gave this phone when it paired
(`nats_user`, `nats_pass`). It sends nothing to another machine over plain http
or ws. A phone paired before 0.5.0 knows only a plain http address: it does not
use it, connects through the relay instead, and tells the person once to pair
again for direct access on the home network. Presence and Study sync run only
over the home's own bus, never through the relay, where their messages (the
session token and Study items among them) would be readable. Away from home the
app says that Study sync waits for the home network; notes stay on the phone
until then.

A relay never needs a publicly-trusted certificate, because nothing browses it.
That also means it never fights your web server for ports 80/443.

**The iPhone app (React Native), since 0.5.0.** The app trusts only what the
invite you scan tells it: the relay's certificate fingerprint (a short checksum
that identifies one certificate), and your home's own key, certificate
fingerprint and home-network address. It keeps these, with its passwords and
login tokens, in a store encrypted with a key that stays in the iPhone
Keychain. If a certificate does not match, the app does not connect and tells
you to pair the phone again. It never asks you to trust a new certificate.
Everything the app sends to your home through a relay is encrypted to your
home's own key, so the relay only passes it along. On your home network the
app connects only over an encrypted connection checked against your home's
certificate; it never falls back to an unencrypted one. A phone paired before
0.5.0 does not have your home's key: pair it again with a fresh invite
(`wyrd phone invite`).

Since 2026-07-24 the React Native Android trust manager includes a **host-key
fallback**: if system trust rejected the chain and no pin matches the resolved
host, it tries every pinned household CA. This exists because
`SSLSession.peerHost` is frequently null, so host extraction falls back to
reverse DNS — silently breaking login to any DNS-named relay while
IP-addressed relays worked. It is a deliberate relaxation of host-to-pin
binding; see Known limitations.

## Content gating

**`OutputSanitizer`** scans text for prompt injection before it reaches an
agent. Patterns come from `SecurityPatternManager`: eleven built-in patterns
(instruction overrides, role tags, exfiltration commands such as `curl … |`,
zero-width and bidi characters, encoded `eval`), plus seed and user patterns
where a pattern store is loaded. Three modes: `BLOCK` replaces each match with
`[BLOCKED]`, `WARN` passes text through but flags it in the log, `LOG_ONLY`
records only. Where it runs:

- Skill output (success and error text), in `BLOCK` mode with the built-in
  patterns.
- `SKILL.md` files, in `BLOCK` mode with the built-in patterns, before they are
  parsed, at boot and when a skill is installed. A matching line (for example a
  `curl … | sh` instruction) is replaced with `[BLOCKED]` and the log names the
  skill and the pattern.
- Library tool responses, in `WARN` mode with the full pattern store.
- All in-world speech, in `WARN` mode.

**`ContentQuarantine`** handles external content entering the inference
context: it strips zero-width characters and HTML tags, detects a fixed set of
injection phrasings, and cuts text at a size limit. It runs on the output of
every external MCP service in the gateway (a JSON result is cleaned value by
value); its `fence` helper wraps content in explicit data markers where a caller
uses it.

**`ActionGrantCheck`** is the owner-issued per-action grant axis
(`home://owner/action/{name}`). Maturity-tier gating runs first; grants are a
second axis on top and never elevate tier. The server wires it in strict mode.
**`AutonomyGate`** adds the consent axis, for non-human-directed actions only:
`AMBIENT` and `VISIBLE` verbs pass, `CONSENT` verbs pass unless
`WYRDSEKAI_ACTION_STRICT_GRANTS=true`, and `FORBIDDEN` verbs never fire
autonomously without an explicit owner grant. `emergency_call` is a safety
floor that is never consent-blocked. **MCP tool access** needs the steward's
grant by default, handed out through the in-world Tool Warden; every MCP call
passes the one gateway (`McpGatewayService`), whichever part of the node makes
it. In-process services and the household's own librarian need no grant; all
other checks still apply to them. `WYRDSEKAI_MCP_STRICT_GRANTS=false` opens every
service to every companion (the node logs a warning at each start).

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
households' tunnel sessions. Until 0.5.0 this held only for registrations that
carried a zone name, and no shipped client sent one; since 0.5.0 every
registration is bound to its zone (see "Per-household NATS users and ACL
scoping").

All three are covered by regression tests.

## Hands, hooks and the vault (0.4.x)

**Per-being principals (Linux).** The coding backend and CLI skills a companion
starts run as her own Linux user, `wyrd-being-<slug>` in the
`wyrdsekai-beings` group, inside a cgroup under the service, with
`no_new_privs` and all capabilities dropped. Python skills, recipe steps, MCP
servers, keybase, signal-cli and the Claude CLI still run as the node's own
user, with the reduced environment described under Credential isolation. The data directory is mode `711`:
traversable by path, not listable, and every top-level entry except the coding
bundle, the workspaces and the beings' homes has no access for others. A
being's user can reach the coding bundle, its own workspace and its own home;
the database, the vault, the credentials file and other beings' homes are
closed by the kernel. When a user cannot
be created or reached, the tool runs as the daemon and the steward is told
(`WYRDSEKAI_BEING_PRINCIPALS=off` chooses that outright).

**The data directory with the hands shared.** Without per-being principals the
directory is closed too: mode `711`, and every top-level entry except `models/`
has no access for other users (the package's on-demand llama unit runs as
`nobody` and loads models from there). The `.deb` sets this at every install and
upgrade, and the server applies it again at every start, so entries it created
since are closed as well. When the service runs as root and the directory belongs
to the person who installed the package, an entry the service created is handed
to that person, as the package already does at each upgrade, so `wyrd`'s local
commands keep working. Before 0.5.0 the directory stayed `755` with world-readable
files, so any local user could read the household's database, souls and backups.

**Kernel hooks (Linux).** One `bpftrace` program watches `openat`, `execve` and
`connect` for the beings' uid range. Opening the database, the vault, another
being's home or the credentials file cuts the tool (its cgroup is killed);
executing `systemctl`, the firewall helper or the like cuts it; other reaches
are recorded. Rules are data (`<data>/brainstem/hook-rules.json`) and every
rule set is replayed over the node's own recorded history before it is
enforced; one that would cut ordinary work runs record-only. Hooks see events
after the fact: a cut ends a tool that has already opened a file. They are a
detector and a tripwire, not an access-control layer; the user boundary above
is the access control.

**Doors.** A door on the body map can be closed as an nftables set the output
chain rejects, by the steward, by the brainstem while the server is down, or by
a reflex row. A door whose addresses include this host is refused.

**The immune check.** Every automatic action against a part — a cut, a closed
door, quarantine, severance — passes one check that refuses to act on the
companion's own resources, and writes the refusal to the steward as a proposal.
A part attached by a node outside the household is quarantined until the
steward vouches for it; the inference router never selects a quarantined
backend. What the body acted against is kept for a year.

**Vault.** The vault store is sealed with AES-256-GCM under `<data>/vault.key`
(mode `0600`), so an offsite copy is ciphertext. The key is generated on the
first copy and never leaves the node unless you copy it; a restore with the
wrong key is refused. Keep a copy of `vault.key` somewhere other than the node.
Sealing also proves where a copy came from: once the store is sealed, a
restore refuses any unsealed copy or piece in it, because this node did not
write it. Anyone who can write to the vault folder could have put it there.
Only `WYRDSEKAI_VAULT_ALLOW_UNSEALED=true` reads such files, and every use is
logged. A restore also refuses a file whose path would land outside the
restore folder.

**Recovery Seed.** `wyrd seed generate` seals a companion's soul manifest, bond
rows, a hash of her chronicle and her Ed25519 signing key into one file:
AES-256-GCM under a key derived from the steward's passphrase (PBKDF2-SHA256,
600,000 rounds, at least 12 characters), with the header authenticated. The
seed routes (`/api/seed/generate|verify|restore`) take a steward session or the
node's operator token from loopback, and refuse a plain-HTTP request from
another machine. The passphrase travels on stdin and in the request body, never
on a command line, and no log line carries it or the file. The answer is
`no-store`. The node keeps one sealed copy per companion in
`<data>/recovery-seed/` (mode `0700`/`0600`). Anyone with the file and the
passphrase can act as the companion; that is what a seed is for. Restore
reseals her key under the new node's own secret, and refuses when the node
already holds her or holds a different companion under her name.

## Each person's words and data

What one person tells a companion is not read into another person's
conversation.

- **Every memory records its origin.** Working memory, the conversation history,
  the structured facts (`memory_entities`, `memory_edges`) and the memory index
  record who said a thing and whether it was private: a tell, a whisper, a phone
  message, a letter, a line said in a Study or with nobody else in the room.
  A line said in a shared room with others present is open.
- **Every read is for one person.** A turn that answers a person reads that
  person's own private memories and what was said openly. A turn that answers no
  one (the companion's own time, another companion) reads only what was open.
  This covers the prompt's history and working memory, the "what you know about
  the user" block (only facts the person told the companion themselves), the
  recall short-circuit ("what am I allergic to?" is answered only from the
  asker's own words), the memory search and its follow-on hops, the `recall`
  tool, the day summary in the conversation prompt and the last dream.
- **Private answers stay private.** A line that answers something said privately
  is whispered, or sent to the person's session, whenever anyone else is in the
  companion's room. A `tell_agent` reply that reaches the person neither in
  person nor by session goes into their household mail; it used to be said aloud
  in their room or in their Study. The companion's external notification
  channels receive such a reply only when it is for its bondholder.
- **Journals.** `read_journal` reads only the shared pages of the person the
  turn answers, and the result goes to that person alone. Asked for another
  person's journal, the companion declines. Private pages were never readable by
  a companion and are encrypted at rest.
- **Studies.** A person's Study is warded to its owner (under every id they
  carry), the people they let in, and the companion whose bondholder they are.
  A Study made before 0.5.0 is sealed at its owner's next login.
- **Study sync with phones** runs on the home network only. The frames are plain
  JSON on a NATS subject, so on a relay connection the home answers every Study
  frame with `study_sync_refused` (reason `home_network_only`) and merges and
  sends nothing. Private journal pages are never put on the sync wire on any
  connection; a phone reads them at home.
- **Memories from before 0.5.0** had no origin. At the first start the companion
  labels them from their text: a line that names the person it was a private
  exchange with ("[User fact] Alice: …", "Replied to Alice via tell: …", "Alice
  wrote to me: …") becomes private to that person when the name resolves to a
  person of this household. Every other line, and every structured fact tied to
  it, is of unknown origin and is read only in turns with the companion's
  bondholder. Nothing is deleted or rewritten; the label is the only change. The
  server log line `Memory origins for …` gives the counts.

## Known limitations

We would rather you knew these than discovered them.

**Tunnel sessions.** Within a household they are not per-session
authenticated: ids are client-chosen and static NATS ACLs cannot express
"sessions you own". A sibling device that learns or guesses the id of a sealed
session can break it (a frame that does not open closes the session), but it
cannot read or forge frames. An unsealed session, allowed only while
`WYRDSEKAI_TUNNEL_ALLOW_PLAINTEXT=true`, can still be read and injected into;
there, 128 bits of entropy is the only mitigation.
There is no idle timeout, TTL, or per-session revocation; the only bounds are
the 64-session cap and the 7-day `/ws` token. An `open` frame with no token
yields a guest session rather than a rejection. React Native falls back to
`Math.random()` for ids if `crypto.getRandomValues` is unavailable — it logs
loudly, but it proceeds.

**Study sync away from home.** Study sync does not run through a relay: a
phone syncs its Study only on the home network, and away from home its notes
wait on the phone. On the home network the sync goes over the encrypted
household bus, under the phone's own login.

**Knocking on a zone you have not paired with.** A knock through a relay must be
sealed to that zone's key, and the zone directory does not publish zones' keys
yet. So a knock through a relay to a zone the phone has no invite for is
refused, unless that zone allows unsealed requests.

**Relay ACLs and credentials.** Zones registered without a zone label keep the
legacy broad grant (`between.>`, `wyrd.zone.>`, `wyrd.tunnel.>`) until they
re-register, and while any does it can read other households' traffic; the
relay operator takes that grant away with `RELAY_LEGACY_GRANT=false` once the
households have updated. `federation.>` is not scoped per agreement in either
direction — that needs agreement-aware permissions. The deprecated shared
`relay_phone` account, which reads every household's tunnel traffic, is off
unless the operator sets `RELAY_SHARED_PHONE_ACCOUNT=true` for phones paired
before per-household phone accounts; re-pair those with `wyrd phone invite`.
The relay ships no `peer_trainer` account: `RELAY_PEER_TRAINER=true` adds one
with a generated password, and every household that uses it can read the
others' training traffic. NATS account passwords are cleartext in
`relay.conf` and the phone credential travels cleartext inside the
`wyrdphone://` payload (inherent to NATS static auth — treat invite material as
secret). Password-mode relay auth still exists; the "remove password mode"
phase has not landed, and there is no per-node revocation-list propagation
between peer relays. `wyrd.discover.>` is globally readable and writable by
design — phones need it to learn a zone label first.

**The household's own links.** A household machine that joined has full use
of the hub's bus: it is trusted like the hub itself. The household CA has no
rotation: if its key is lost, every phone and machine pairs again. The
machine's certificate is renewed only when the node starts, so a node that runs
for more than a year without a restart serves an expired certificate
(`wyrd doctor` warns 30 days before). The desktop inference daemon
(`wyrdsekai-daemon`) has no bus login yet and cannot connect to a 0.5.0
household bus, unless the hub turns on `WYRDSEKAI_NATS_LAN_PLAINTEXT`. The Docker compose files' own NATS container has neither
login nor TLS; it is published on the host's loopback only.

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

**Gating.** Neither `EgressGate` nor `SubprocessEnv` blocks network egress —
they decide what a started program inherits from the node's environment;
OS-enforced isolation (netns/nftables) is a follow-up. `ActionGrantCheck`
defaults to fail-open when strict mode is off (the server wires strict on;
embedders inherit the permissive default). Speech-path injection scanning is
WARN-only — flagged, never redacted. `OutputSanitizer`'s built-in patterns are
eleven English-language regexes; invalid user or seed regexes are skipped with a
warning. `ContentQuarantine` is a fixed English-language denylist plus
invisible-character stripping: it raises cost, it is not a boundary. The MCP
spend cap charges an estimate (0.001 per call on a `metered` service) when a
service reports no price and none is configured.

**What is still not private between household members.** The steward, or
anyone with a root shell on the node, can read the database: mail bodies,
conversation turns, the activity trail and the companion's memories are
plaintext at rest (private journal pages are the exception). Overnight
consolidation reads the whole day, private words included: the Forge (the
working-memory harvest and the significance buffer), the dream, and the nightly
learning when it is on. None of these read the words back into another person's
conversation, but what the companion learns from them can shape how it speaks
with anyone. The research record ("you asked … you concluded …") and the
companion's wants and chronicle are not filtered by person. A person who asks
something aloud in a shared room gets the answer aloud, and that answer may draw
on what they told the companion privately. Study sync no longer crosses a relay:
the home refuses it there, the phone apps from this release sync only on the
home network, and the relay no longer lets phones subscribe to those subjects.

**Account primitives.** Minimum password length is 4 characters, on every
surface that sets one (HTTP, SSH, telnet create, invite redemption and
`/adduser`). Session tokens are UUIDv4 strings (122 bits from `SecureRandom`).
Changing a password ends the account's other sessions; the session that made
the change stays (over SSH, which has no session token, all of them end).
Connections already open when a session ends stay open until they close. A
passkey can be registered only to the account the caller is logged in as. Invite passphrases are 6 words from
a 256-word list — 48 bits, so the TTL and single-use consumption carry the
security, not the length. bcrypt password hashes replicate across the household
mesh on account creation. Each replicated account event is signed by the machine
it came from, and a machine takes it only from machines on its household roster.
A role other than member, a removal, or a setting change is honoured only when
the event names the steward who made it and that person is a steward on the
receiving machine too. Invite codes travel sealed to each roster machine's key,
never in the clear. Unsigned events are refused;
`WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED=true` takes them, as members only, while
older household machines are upgraded.

**Cryptography is JDK-native with one exception.** Ed25519 signing and
AES-256-GCM soul encryption use the JDK's own primitives; password hashing uses
`at.favre.lib:bcrypt`, a reviewed implementation, in preference to a hand-rolled
key-derivation function.
