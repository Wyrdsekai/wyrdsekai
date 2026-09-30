# Relays

A relay lets you reach your companion at home when you are away, from your
phone or from another computer. It also lets other households reach yours. You
do not have to open a port on your home router.

A relay is a small, always-on machine on the internet. Your home machine
connects *out* to it and keeps that connection open. Your phone connects *in*
to the relay, and the relay passes messages between the two. Nothing has to
reach into your house.

**What to do:**

1. Find a relay. Someone who runs one can give you a join token. You can use
   the project's public relay. Or you can run your own, which Part 2 explains.
2. Join it from the machine that runs your zone, with `wyrd relay join`.
   A zone is your Wyrdsekai world, or one named part of it. See
   [ZONES.md](ZONES.md).
3. Run `wyrd phone invite` and scan the QR code with the Wyrdsekai app.

## What a relay does and does not do

- **A relay does not hold your world.** Your rooms, your companion, its
  memories and your conversations live on your own machine. The relay keeps a
  registration record for each zone and passes messages along.
- **A relay is not an account.** There is no login, no profile, and no
  identity kept on the relay. Your zone proves who it is with a key it made
  itself and keeps.
- **A relay can see what passes through it.** Your phone's connection to the
  relay is encrypted, but that encryption ends at the relay. Messages between
  zones are signed: agreements, visits, companions moving, `tell`s, gifts, room
  peeks and requests to run a recipe on the other zone's machine. Your home
  checks each one against the key the other zone agreed with, and drops one
  that is forged, changed, stale or sent twice. So nobody can forge those or
  change them on the way. Two kinds are not fully covered yet: the text of a
  request to borrow another zone's AI model and its answer (the request's
  sender is signed, its text is not), and your commands during a visit to
  another zone after you have arrived. Signing does not hide what any message
  says. Since 0.5.0 the link between your home and the relay is encrypted as
  well, so nobody on the network between them can read it. The relay itself
  still can. Your home checks the relay's certificate against the fingerprint
  it saved when it joined. For a relay it holds no fingerprint for, it keeps the
  first certificate it sees and refuses a different one later. A relay that
  stops offering encryption after it once did is refused too.
- **Households on one relay cannot read each other.** Since 0.5.0 each
  registration is bound to one zone name, and the relay lets it use only that
  zone's messages and only its own replies.

So the person who runs a relay can see which identity connected, when, from
which address, how much traffic moved, and which subjects were routed. A
subject is the address label on each message. No relay operator can avoid
seeing that. They are also in a position to read the messages themselves.

If that matters to you, run your own relay, or use one run by someone you
trust. It takes three commands, and the bundle is a download. Part 2 shows
how.

---

# Part 1: Using a relay

## Join one

Someone running a relay gives you a join token. It looks like `wyrdjoin://…`.
On the machine running your zone:

```bash
wyrd relay join wyrdjoin://<token>
```

That redeems the token and receives the relay's address and its certificate
authority, the key that vouches for the relay's identity. It checks the
certificate fingerprint **against the one inside your token**. Then it enrols
your zone and offers to restart so the connection comes up.

If the fingerprint does not match, the join stops and saves nothing. That is
not a glitch to work around. It is the check doing its job, and it means
something is sitting between you and the relay.

The join registers your zone's name (`WYRDSEKAI_ZONE_ID`) with the relay. On
one relay a name belongs to the first household that registers it. If yours is
taken, the join says so and saves nothing; set another name and join again.
To register a second machine of your own household under the same zone name,
run `wyrd relay zone-add <that machine's NKey>` on a machine that already
holds it (`wyrd relay nkey-pubkey` prints a machine's NKey).

Your zone joins with its own key (NKey). `--password-mode` registers with a
relay password instead, the way `wyrd relay join` did on Linux and macOS
before 0.5.0.

Join codes are 8 characters. Each one works once, expires after a set time,
and a relay limits how many tries come from one address. If yours has expired,
ask the operator for a fresh one.

<details>
<summary>Other forms that still work</summary>

```bash
wyrd relay join <host>[:port] <code>       # host and code separately
wyrd relay register 'wyrdrelay://…'        # older URL form
```

From inside the world over SSH or telnet, a steward can run
`/relay join <token>`. The steward is the person who looks after the
household, its administrator.

A zone can be registered with more than one relay. `join`, `register` and
`setup` all accept `--replace` to start over with just this relay instead of
adding it, `--visibility private|public` (default private), and
`--allow-public-leg`.
</details>

## The public relay

We run one at **`relay.wyrdsekai.org`**, so that reaching your household from
a phone does not first require a second machine with a public address. It is
a convenience, not a dependency. Nothing in the design goes through it, and
running your own stays the documented path.

```bash
wyrd relay join relay.wyrdsekai.org      # self-serve; no invite code needed
wyrd relay status
```

It runs in `commons` mode, so anyone can join without an invite. You start at
the **FLOOR** tier, for newcomers. See [Trust tiers](#trust-tiers) below.

Without an invite there is no fingerprint to check the relay against. On Linux
and macOS the join shows you the relay's fingerprint and asks you to compare it
with the one on the relay's web page; `--fingerprint <fp>` passes it in
advance. On Windows, and from inside the world, the join keeps the fingerprint
it is shown and prints it: compare it with the relay's page, and if it
differs, run `wyrd relay leave`.

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

This prints a QR code, and a `wyrdphone://` link if you would rather paste.
Scan it with the app. The invite carries the list of relays, the phone's
credential, and the certificate fingerprint the app remembers when you scan.

Only an enrolled zone can make a phone invite. The relay checks your zone's
signature before it issues one. A zone registered with a relay password
proves the password instead, without sending it.

From inside the world, a steward can type `/invite phone`.

`wyrd phone invite` works the same on Windows.

## Reach your zone with plain `ssh`

If the relay operator allows it, you can SSH to your home zone from anywhere
without opening a port at home. On your zone:

```bash
wyrd relay ssh-enable
```

Your zone connects *out* to the relay and holds that connection open. Nothing
connects in to your house, which is why this works behind a router you do not
control.

What you type afterwards depends on how the relay operator set it up.
`ssh-enable` tells you which setup it is. Here is what each looks like.

**If the relay uses `port` topology**, usual for a household or
friends-and-family relay, your zone gets its own port number on the relay:

```
$ wyrd relay ssh-enable
[wyrd] ssh-enable: generated tunnel key /home/you/.wyrdsekai/ssh_tunnel_key
[wyrd] ssh-enable: persisted tunnel config → /etc/wyrdsekai/wyrdsekai.conf
[wyrd] Reach this zone with a bare ssh:

    ssh -p 7103 <your-zone-account>@relay.example.com

[wyrd] ssh-tunnel: SSH-over-relay is live now — no 'wyrd restart' needed.
```

It gives you the exact command. `<your-zone-account>` is your Wyrdsekai login
on that zone. So if your login is `you`, then from your laptop, anywhere:

```bash
ssh -p 7103 you@relay.example.com
```

That reaches your zone's own SSH door, the same one you use at home with
`ssh -p 7022`. The relay only carries the bytes. The login is your zone's, not
the relay's.

To avoid typing the port every time, add this to your laptop's
`~/.ssh/config`:

```
Host myzone
    HostName relay.example.com
    Port 7103
    User you
```

Then it is just `ssh myzone`.

**If the relay uses `jump` topology**, usual for a big public relay because it
does not need a port per household, you go through one shared entry point.
`ssh-enable` saves a key for you and prints a ready-made block for your
laptop's `~/.ssh/config`. It has two entries, because one hop reaches the
relay and the second continues to your zone:

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

Paste it as printed. The names, the `127.0.0.1` and the `IdentityFile` all
matter, and the key it points at is one the relay issued to you. Then:

```bash
ssh relay-example-com
```

`wyrd-tunnel` is the relay's own account. It has no shell. It exists only to
pass your connection on.

**Where to find your port.** It is saved on your zone as
`WYRDSEKAI_SSH_TUNNEL_REMOTE_PORT`, in the config file `ssh-enable` names. On
an installed system that is usually `/etc/wyrdsekai/wyrdsekai.conf`.
Otherwise it is the `env` file in your data directory, such as
`~/.wyrdsekai/env`.

```bash
grep SSH_TUNNEL /etc/wyrdsekai/wyrdsekai.conf
```

**If it does not connect,** check in this order. Is your zone running
(`wyrd status`)? Is the tunnel up (`wyrd relay status`)? Did the operator
enable SSH on the relay? If `ssh-enable` was refused, that is the relay's
policy, not a fault on your side.

The relay passes raw bytes and never gets a shell. Your SSH session stays
encrypted from end to end, exactly as if you had connected directly. This is
**off by default and needs the operator to allow it**.

Turn it back off with `wyrd relay ssh-disable`.

## Leave

```bash
wyrd relay leave <url>     # leave one relay
wyrd relay leave           # leave all of them
wyrd relay remove          # deregister and delete the local config too
```

Leaving removes your zone's registration on that relay, not only your own
settings. Your zone signs the request to leave, so the relay knows it really
was you. A zone registered with a relay password proves the password instead.
Leaving also happens by itself on `wyrd uninstall`. If you simply stop
connecting, the relay's cleanup job removes your record on its own: after 24
hours at the newcomer tier, and after seven days above it.

## Don't depend on exactly one

If the only relay you know goes down, messages between zones stop. `wyrd`
warns you when it notices you are in that position. There are two ways out:
register with a second household's relay, or run your own. The second is the
next part, and it is less work than it sounds.

## Other zone-side commands

```bash
wyrd relay disable
wyrd relay rotate-cert [--ca] | show-cert
wyrd relay peer-invite | peer-accept     # bilateral relay-to-relay peering
```

---

# Part 2: Running a relay

This part is for people comfortable running a server.

## What you need

A machine that is reachable from the internet and stays on. A cheap VPS is
plenty, and a home machine with a forwarded port works too. The installer uses
Docker if it is present. Otherwise it installs plain programs run by systemd.
You do **not** need to run a Wyrdsekai zone on the same machine.

## Install

Download the relay bundle (`wyrdsekai-relay-<version>.tar.gz`), unpack it,
and run:

```bash
sudo sh relay.sh relay.example.com
```

In a source checkout the same script is `packaging/relay.sh`.

That sets up TLS, creates the relay's own certificate authority, starts the
services and prints a join token. Some variations:

```bash
sh relay.sh                                  # no arguments: print help
sudo sh relay.sh deploy                      # auto-detect the address
sudo sh relay.sh relay.example.com:5000      # non-default port (default 4443)
sudo sh relay.sh 192.0.2.50:5000             # bare IP, custom port
sudo sh relay.sh --native relay.example.com  # force the no-docker install
sudo sh relay.sh relay.example.com --private # not listed in any directory
```

A `--private` relay answers no discovery request and appears in no directory.
Anyone holding a join token can still use it fully. It is unlisted, not
restricted.

The host name is optional. It only sets the address written into join tokens.
The relay's certificate covers every IP address of the machine, and devices
remember the **household CA fingerprint**, not the host name.

Other flags: `--docker` / `--native`, `--public` / `--private`,
`--mode invite-only|open|commons`, `--owner <did>`, `--bundle-dir DIR`,
`--ssh-tunnel[=jump]`.

**Two ways it installs.**

- Docker mode is a single container built from `deploy/relay/Dockerfile`. It
  holds NATS, Caddy, the registration sidecar and first-boot certificate
  generation. There is no compose file.
- Native mode installs pinned static `nats-server` and `caddy` programs plus a
  Python virtual environment under `/opt/wyrdsekai-relay`. Change that with
  `WYRD_RELAY_PREFIX`. It adds the systemd units
  `wyrd-relay-{nats,registration,caddy}`.

Running the installer again upgrades in place and never changes the relay's
identity. `--reset` wipes the identity. `uninstall` destroys the household CA,
and with it every device's trust in this relay.

## Running a relay next to a zone

This works, and the installer handles it. A zone already uses port `4222`, the
NATS port a relay wants for its zone leg, the connection zones make to it. So
the relay moves its four backend ports, usually by +100, which makes the zone
leg `4322`. It tells you what it picked during the install. Zones joining it
learn the real port when they register, so nothing needs setting by hand.

Set `WYRD_RELAY_PORT_OFFSET` to choose the shift yourself. It is used exactly
as given, which also suits a second relay on one machine.
`WYRD_RELAY_INSTANCE` adds a suffix to the systemd unit names. Both work in
native mode only. The Docker install publishes fixed ports and cannot move, so
sharing a machine needs `--native`.

## Firewall

Open `4443`, or the port you chose, and the zone-leg port. The zone leg is
`4222` by default. If the installer moved its ports, open the port it
reports, not `4222` out of habit.

Inside the relay, `9222` is the internal websocket and `9280` the registration
sidecar. [ZONES.md](ZONES.md) lists every port Wyrdsekai uses.

## Decide who may join

`--mode` sets the joining rule. It can be changed later through a signed admin
operation. The flag only sets the first value.

| Mode | Who may join | Entrants start at |
|---|---|---|
| `invite-only` *(default)* | anyone with a token you minted | HOUSEHOLD |
| `open` | anyone who can reach it. The network perimeter is the trust boundary | HOUSEHOLD-equivalent |
| `commons` | anyone, self-serve, no invite | FLOOR |

`invite-only` is the right default for a relay you run for friends and family.
Use `open` only on a relay that is already behind a firewall or limited to a
home network. Use `commons` when you mean to run a public relay that strangers
can join. On a `commons` relay each address is held to a hard limit on how
often it can try.

## Trust tiers

Every registered zone sits at a tier, and the tier sets its limits.

| Tier | How you get there | Registrations | Connections per identity | Vouch weight | Pruned after |
|---|---|---|---|---|---|
| FLOOR | self-serve join on a commons relay | capped at 500 | 2 | 0.0 | 24 hours absent |
| VOUCHED | verified IdentityOutbox + vouches totalling ≥ 1.0 | unlimited | 5 | 0.6 | 7 days absent |
| HOUSEHOLD | invited by the operator, or promoted | unlimited | 20 | 1.0 | 7 days absent |

On a `commons` relay newcomers land at FLOOR. They move up to VOUCHED with a
verified identity record, the IdentityOutbox, plus vouches from zones already
trusted. This is a web of trust: the tier comes out of it, and an entrant
cannot simply claim one.

A newcomer's vouch is worth nothing, weight 0.0. That is deliberate. It stops
a flood of fresh identities from promoting each other. Two VOUCHED vouches
(0.6 each) or one HOUSEHOLD vouch reaches the threshold.

**What is enforced, and what is not.**

- The registration cap (`max_registrations`) is a **hard refusal at the join
  gate**. A new entrant is turned away when the tier is full. That is the
  flood defence. It is what stops a stranger from minting ten thousand
  identities on your commons relay.
- The connection limit (`max_connections`) only detects. The relay's cleanup
  job, the reaper, marks a record `over_connection_limit` so you can see it in
  `relay.sh list` and act. The registration sidecar cannot cut a single NATS
  connection without removing the record from authentication entirely, so
  cutting stays a deliberate operator action. The reaper also removes zones
  that have been absent longer than their tier's window.
- There are **no per-tier bandwidth limits** yet. NATS throttles per account,
  not per user. Limiting per tier would mean splitting into one account per
  tier, with exports and imports for the shared federation subjects.

The registration cap is the flood defence that is actually enforced today.

## Become the owner

The owner is the admin identity for the relay. It can change the mode, set
policy and act on abuse reports.

```bash
sudo sh relay.sh relay.example.com --owner did:key:z…   # record it at deploy
```

`--owner` accepts either a `did:key:z…` or a `did:wyrd:z…` value. Run
`wyrd whoami` on the zone that will administer the relay. It prints a
`did:wyrd:…`, which you can pass as it is.

If you skip `--owner`, the deploy makes a one-time owner-claim token instead.
Redeem it from the zone that should own the relay:

```bash
wyrd relay claim <token>
```

Once the relay has an owner, deploys and updates make no claim token, and an
ordinary claim token is refused. To hand the relay to another zone, make a
replacement token on the relay host and redeem it from that zone:

```bash
sudo sh relay.sh claim-mint --replace-owner
```

`wyrd relay set-policy` appears in some relay documents but has **no command
yet**. Tier policy is currently a signed admin operation on the relay side
only.

## Day to day

Run these on the relay host. They detect Docker or native by themselves:

```bash
sh relay.sh list                    # registrations, tiers, who is live, abuse reports
sh relay.sh invite --ttl 3600       # mint a fresh single-use join token
sh relay.sh claim-mint              # mint an owner-claim token (unowned relay; --replace-owner hands it over)
sh relay.sh remove <pubkey>         # force-remove a node
sh relay.sh backup                  # archive the relay identity
sh relay.sh restore <archive.tgz>   # restore it onto a fresh deploy
sh relay.sh uninstall               # remove the relay cleanly
```

A zone then joins with the token `invite` prints:

```bash
wyrd relay join wyrdjoin://relay.example.com:4443/<code>.<ca_fp>
```

`remove` is the operator's hammer. You have root, so no signature from the
node is needed. A zone leaving on its own uses the signed `wyrd relay leave`
instead.

## Security settings

Set these in the environment when you run `relay.sh` (for example
`sudo RELAY_LEGACY_GRANT=false sh relay.sh update`). The installer keeps them
for later updates.

| Setting | Default | What it does |
|---|---|---|
| `RELAY_ALLOW_NON_TLS` | `true` | `false` makes the zone-leg port refuse zones that connect unencrypted. Turn it off once every household on the relay runs 0.5.0 or later. |
| `RELAY_LEGACY_GRANT` | `true` | Registrations made before 0.5.0 have no zone name and keep the old wide permissions until their zone updates and binds its name. While any does, it can read other households' traffic. `false` takes those permissions away. |
| `RELAY_SHARED_PHONE_ACCOUNT` | `false` | `true` brings back the shared `relay_phone` user of old phone invites. It can read every household's tunnel traffic. Re-invite those phones instead. |
| `RELAY_PEER_TRAINER` | `false` | `true` adds the shared `peer_trainer` user for peer training between homes, with a generated password (`data/relay-secrets.json`, key `peer_trainer`). Every household that uses it can read the others' peer-training traffic. Before 0.5.0 this user was always present, and its password was the template's placeholder. |
| `RELAY_LEGACY_INBOX` | `false` | `true` lets zone-bound users read every reply inbox again, for apps from before 0.5.0. |
| `RELAY_ALLOW_PLAIN_TOKEN_PROOF` | `false` | `true` accepts a relay password sent in the clear to make a phone invite, for zones still on an older version. |

At start the relay prints which registrations are bound to a zone name and
which still have the old permissions. A zone on 0.5.0 binds its name at every
start, so the list shrinks as households update. When it is empty, set
`RELAY_LEGACY_GRANT=false`.

## Allow SSH-over-relay (optional)

This is off unless you turn it on:

```bash
sudo sh relay.sh relay.example.com --ssh-tunnel        # per-zone public ports
sudo sh relay.sh relay.example.com --ssh-tunnel=jump   # one ProxyJump port for all zones
```

`port` topology publishes a control port plus a range, and each zone gets its
own public port. It is simple, and good for a household relay. `jump`
publishes only the control port and sends everyone through it with
ProxyJump. That scales to many zones without using up a port each, which makes
it the right choice for a public relay.

Each zone must still turn it on for itself. `--ssh-tunnel-mode` controls who
may do that: `off`, `grant` where the owner enables specific zones (the
default), or `open` where any registered zone can enable itself.

The tunnel program only forwards. It never provides a shell, on the relay or
anywhere else.

## Back up the relay's identity

**Do this before you need it, and always before a `--reset` or a move to a
new machine.** The relay's identity is its certificate authority, its invite
key and its registration records. Lose them and every zone and every phone
must pair again from scratch. There is no recovery path, by design. A relay
identity that someone else could rebuild would not be worth trusting.

```bash
sh relay.sh backup                          # writes relay-identity-<stamp>.tgz
sudo sh relay.sh restore relay-identity-….tgz
```

`backup` also takes `--out path.tgz` to choose the file name.

To move a relay to a new machine, deploy on the new machine first, then
restore the old identity over it and restart. Existing zones and phones will
not notice, because every device's saved trust stays valid.

## What you are taking on

Running a relay for other people means you can see their connection metadata,
and are in a position to see more. It also means their messaging between zones
stops when your machine does. Neither can be avoided. Both are reasons the
project would rather see many small relays than a few large ones. Every
household running its own is one less place the network can break, and one
less operator anyone has to trust.

---

## See also

- `deploy/relay/README.md` in the relay bundle: the full operator reference,
  with the port layout, the registration sidecar, the security ledger and file
  locations.
- [ZONES.md](ZONES.md): zones, federation, and how machines find each other.
- [SECURITY_MODEL.md](SECURITY_MODEL.md): the trust model in full.
