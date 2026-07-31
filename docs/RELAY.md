# Relays

A relay is a small always-on machine that lets your phone — and other
households — reach your zone without you opening a port on your home router.

That is the whole job. It is worth being precise about what it *doesn't* do,
because the difference is the reason the design looks the way it does:

- **A relay does not hold your world.** Your rooms, your companion, her
  memories and your conversations live on your own machine. The relay stores a
  registration record and moves bytes.
- **A relay cannot read what passes through it.** Envelopes are signed and
  encrypted end to end; the tunnel terminates on your devices. This is a
  property of the protocol, not a promise about the operator's manners.
- **A relay is not an account.** There is no login, no profile, no server-side
  identity. Your zone proves who it is with a key it generated and keeps.

What a relay operator *can* see is metadata: which identity connected, when,
roughly how much traffic moved, and which subjects were routed. No relay
operator can avoid seeing that. If that pattern is sensitive to you, run your
own — which is the second half of this document.

---

# Part 1 — Using a relay

## Join one

Someone running a relay gives you a join token. It looks like
`wyrdjoin://…`. On the machine running your zone:

```bash
wyrd relay join wyrdjoin://<token>
```

That redeems the token, receives the relay's address and its certificate
authority, checks the certificate fingerprint **against the one embedded in
your token**, enrols your zone, and offers to restart so the connection comes
up.

If the fingerprint doesn't match, the join aborts. That is not a glitch to
work around — it is the check firing, and it means something is sitting
between you and the relay.

Join codes are single-use and time-limited. If yours has expired, ask the
operator for a fresh one.

<details>
<summary>Other forms that still work</summary>

```bash
wyrd relay join <host>[:port] <code>       # host and code separately
wyrd relay register 'wyrdrelay://…'        # older URL form
```

From inside the world over SSH or telnet, a steward can run `/relay join <token>`.
</details>

## Check on it

```bash
wyrd relay status     # is the connection up?
wyrd relay legs       # every relay this zone is registered with
```

## Put your companion on your phone

With your zone registered to a relay:

```bash
wyrd phone invite
```

This prints a QR code (and a `wyrdphone://` URL if you'd rather paste). Scan
it with the app. The invite carries the relay list, the phone's credential,
and the certificate fingerprint the app pins at scan time.

Only an enrolled zone can mint a phone invite — the relay checks your zone's
signature before it issues one.

From inside the world: `/invite phone` as a steward.

## Reach your zone with plain `ssh`

If the relay operator has enabled it, you can SSH to your home zone from
anywhere without opening a port at home. On your zone:

```bash
wyrd relay ssh-enable
```

Your zone dials *out* to the relay and holds that connection open. Nothing
dials in to your house, which is why this works from behind a router you don't
control.

What you type afterwards depends on how the relay operator set theirs up.
`ssh-enable` tells you which it is; here is what each looks like in practice.

**If the relay uses `port` topology** (usual for a household or friends-and-family
relay), your zone is handed its own port number on the relay:

```
$ wyrd relay ssh-enable
[wyrd] ssh-enable: generated tunnel key /home/you/.wyrdsekai/ssh_tunnel_key
[wyrd] ssh-enable: persisted tunnel config → /etc/wyrdsekai/wyrdsekai.conf
[wyrd] Reach this zone with a bare ssh:

    ssh -p 7103 <your-zone-account>@relay.example.com

[wyrd] ssh-tunnel: SSH-over-relay is live now — no 'wyrd restart' needed.
```

It hands you the exact command. So if your account on the home machine is
`you`, then from your laptop, anywhere in the world:

```bash
ssh -p 7103 you@relay.example.com
```

That is an ordinary SSH login to *your own machine at home*. The relay is
carrying the bytes; the username and password (or key) are your home
machine's, not the relay's.

To avoid typing the port every time, add this to your laptop's `~/.ssh/config`:

```
Host myzone
    HostName relay.example.com
    Port 7103
    User you
```

Then it is just `ssh myzone`.

**If the relay uses `jump` topology** (usual for a big public relay, because it
doesn't need a port per household), you go through one shared entry point.
`ssh-enable` saves a key for you and prints a ready-made block to paste into
your laptop's `~/.ssh/config` — two entries, because one hop reaches the relay
and the second continues to your zone:

```
Host relay-example-com
    HostName 127.0.0.1
    Port 7103
    User you
    ProxyJump relay-example-com-jump

Host relay-example-com-jump
    HostName relay.example.com
    Port 2222
    User wyrd-tunnel
    IdentityFile /home/you/.wyrdsekai/jump_key
    IdentitiesOnly yes
```

Paste it as printed — the names, the `127.0.0.1`, and the `IdentityFile` all
matter, and the key it points at is one the relay issued to you. Then:

```bash
ssh relay-example-com
```

`wyrd-tunnel` is the relay's own account and it has no shell; it exists purely
to forward your connection onward.

**Where to find your port.** It is saved on your zone as
`WYRDSEKAI_SSH_TUNNEL_REMOTE_PORT` in the config file `ssh-enable` names
(usually `/etc/wyrdsekai/wyrdsekai.conf`, or `~/.wyrdsekai/wyrdsekai.conf` for
a single-user install):

```bash
grep SSH_TUNNEL /etc/wyrdsekai/wyrdsekai.conf
```

**If it doesn't connect,** check in this order: is your zone running
(`wyrd status`); is the tunnel up (`wyrd relay status`); did the operator
actually enable SSH on the relay — if `ssh-enable` was refused, that is their
policy setting, not a fault on your side.

Two things worth knowing. The relay forwards raw bytes and never gets a
shell — your SSH session stays encrypted end to end, exactly as if you had
connected directly. And this is **off by default and requires the operator to
allow it**; if `ssh-enable` is refused, that is the relay's policy, not a bug.

Turn it back off with `wyrd relay ssh-disable`.

## Leave

```bash
wyrd relay leave <url>     # leave one relay
wyrd relay leave           # leave all of them
wyrd relay remove          # deregister and delete the local config too
```

Leaving is signed by your zone, so the relay knows it was really you. It also
happens automatically on `wyrd uninstall`. If you simply stop connecting, the
relay's reaper prunes your record on its own — after 24 hours at the newcomer
tier, seven days above it.

## Don't depend on exactly one

If the only relay you know goes down, cross-zone messaging stops. `wyrd` will
warn you when it notices you're in that position. Two ways out: register with
a second household's relay, or run your own. The second is the next section,
and it is less work than it sounds.

---

# Part 2 — Running a relay

## What you need

A machine that is reachable from the internet and stays on: a cheap VPS is
plenty, and a home box with a forwarded port works too. Docker is used if it
is present; otherwise the installer falls back to native binaries under
systemd. You do **not** need to run a wyrdsekai zone on the same machine.

## Install

Download the relay bundle (`wyrdsekai-relay-<version>.tar.gz`), unpack it, and:

```bash
sudo sh relay.sh relay.example.com
```

That provisions TLS, generates the relay's own certificate authority, starts
the services, and prints a join token. Some variations:

```bash
sh relay.sh                                  # no arguments: print help
sudo sh relay.sh deploy                      # auto-detect the address
sudo sh relay.sh relay.example.com:5000      # non-default port (default 4443)
sudo sh relay.sh --native relay.example.com  # force the no-docker install
sudo sh relay.sh relay.example.com --private # not listed in any directory
```

A `--private` relay answers no discovery probe and appears in no directory. It
is still completely usable by anyone holding a join token — it is unlisted,
not restricted.

**Running a relay on a machine that also runs a zone.** This works, and the
installer handles it: a zone already occupies the NATS port a relay wants, so
the relay shifts its backend ports (usually by +100) and tells you what it
picked. Zones joining it are handed the real port during registration, so
nothing needs configuring by hand — just open the port the installer reports
rather than the default. If you would rather choose yourself, set
`WYRD_RELAY_PORT_OFFSET` and it will be used as given. The docker install
publishes fixed ports and cannot move, so co-hosting needs `--native`.

## Decide who may join

`--mode` sets the joining rule. It can be changed later through a signed admin
operation; the flag only seeds the first value.

| Mode | Who may join | Entrants start at |
|---|---|---|
| `invite-only` *(default)* | anyone with a token you minted | HOUSEHOLD |
| `open` | anyone who can reach it — the network perimeter is the trust boundary | HOUSEHOLD-equivalent |
| `commons` | anyone, self-serve, no invite | FLOOR |

`invite-only` is the right default for a relay you run for friends and family.
Use `open` only on a relay that is already behind a firewall or confined to a
LAN. Use `commons` when you mean to run a public relay that strangers can join.

## Trust tiers

Every registered zone sits at a tier, and the tier decides its limits.

| Tier | Registration cap | Connections per zone | Pruned after |
|---|---|---|---|
| FLOOR | 500 | 2 | 24 hours |
| VOUCHED | unlimited | 5 | 7 days |
| HOUSEHOLD | unlimited | 20 | 7 days |

On a `commons` relay, newcomers land at FLOOR. A verified identity plus
vouches from zones already trusted promote them to VOUCHED. The tier is the
output of that web of trust, not something an entrant asserts.

The registration cap is a **hard refusal at the join gate** — that is the flood
defence, and it is what stops a stranger from minting ten thousand identities
on your commons relay. The connection ceiling is softer: the reaper flags any
zone over budget so you can see it in `relay.sh list` and act.

> Per-tier *bandwidth* throttling is not wired yet. The registration cap is the
> enforced defence today.

## Become the owner

The owner is the admin identity for the relay: it can change the mode, set
policy, and act on abuse reports.

```bash
sudo sh relay.sh relay.example.com --owner did:key:z…   # record it at deploy
```

Find a zone's DID with `wyrd whoami`. If you skip `--owner`, the deploy mints a
one-time owner-claim token instead; redeem it from the zone that should own the
relay:

```bash
wyrd relay claim <token>
```

## Day to day

Run these on the relay host — they detect docker vs native themselves:

```bash
sh relay.sh list                    # registrations, tiers, who is live, abuse reports
sh relay.sh invite --ttl 3600       # mint a fresh single-use join token
sh relay.sh claim-mint              # mint an owner-claim token
sh relay.sh remove <pubkey>         # force-remove a node
sh relay.sh backup                  # archive the relay identity
sh relay.sh restore <archive.tgz>   # restore it onto a fresh deploy
sh relay.sh uninstall               # remove the relay cleanly
```

`remove` is the operator's hammer — you have root, so no node signature is
needed. A zone leaving on its own uses the signed `wyrd relay leave` instead.

## Allow SSH-over-relay (optional)

Off unless you turn it on:

```bash
sudo sh relay.sh relay.example.com --ssh-tunnel        # per-zone public ports
sudo sh relay.sh relay.example.com --ssh-tunnel=jump   # one ProxyJump port for all zones
```

`port` topology publishes a control port plus a range, and each zone gets its
own public port — simple, good for a household relay. `jump` publishes only
the control port and fans out via ProxyJump, which scales to many zones without
burning a port each — the right choice for a public relay.

Per-zone opt-in is still required on top of this. `--ssh-tunnel-mode` controls
who may opt in: `off`, `grant` (the owner enables specific zones — the default),
or `open` (any registered zone enables itself).

The tunnel daemon is forwarding-only. It never provides a shell, on the relay
or anywhere else.

## Back up the relay's identity

**Do this before you need it.** The relay's identity is its certificate
authority, its invite key, and its registration records. Lose them and every
zone and every phone must re-pair from scratch — there is no recovery path,
by design, because a relay identity that could be reconstructed by someone else
would not be worth pinning.

```bash
sh relay.sh backup                          # writes relay-identity-<stamp>.tgz
sudo sh relay.sh restore relay-identity-….tgz
```

To move a relay to a new machine: deploy on the new box first, then restore the
old identity over it and restart. Existing zones and phones will not notice.

## What you are taking on

Running a relay for other people means you can see their connection metadata,
and it means their federated messaging stops when your box does. Neither is
avoidable. Both are reasons the project would rather see many small relays than
a few large ones — every household running its own is one less place the
network can break, and one less operator anyone has to trust.

---

## See also

- `deploy/relay/README.md` in the relay bundle — the full operator reference:
  port layout, the registration sidecar, the security ledger, file locations.
- [ZONES.md](ZONES.md) — zones, federation, and how they find each other.
- [SECURITY_MODEL.md](SECURITY_MODEL.md) — the trust model in full.
