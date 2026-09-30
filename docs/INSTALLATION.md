# Installing Wyrdsekai

This guide gets Wyrdsekai running on a computer you own. For most people it is
one command and about ten minutes, most of it spent downloading.

Wyrdsekai runs as a small service that stays on in the background. A computer
running it is called a **node**, and one node is already a complete household:
the world, the companions, the AI models and every way to connect. You can add
more computers later. [ZONES.md](ZONES.md) explains how.

Every install ends the same way. The service is running, you have a
**steward** account, which is the household's main administrator, and you scan
a QR code with your phone.

To get going, read "Before you start" and "The quick way", then go to
"First run". The rest of this guide is reference.

## Before you start

| Platform | You need | Also good to have |
|---|---|---|
| Linux | Java 25 JRE (`default-jre-headless (>= 2:1.25)` or `openjdk-25-jre-headless`) | Docker or Podman; `python3-gi` + GTK/AppIndicator bindings for the tray; `policykit-1`; `xdg-utils` |
| macOS | Apple Silicon for local inference (Intel: `brew install llama.cpp` first); Xcode command line tools if building | Java 25 (bundled in the `.pkg` payload path) |
| Windows | Windows 10+ x64 | Nothing more. The `.msi` bundles its own Java runtime via `jpackage` |
| Docker | Docker Engine + Compose v2 | NVIDIA Container Toolkit for `--gpus all` |
| From source | JDK 25 (`languageVersion = 25`), Gradle wrapper (9.2.1, downloaded automatically) | `dpkg-dev` to build a `.deb` |

Plan for about 10 GB of disk for the AI models, on top of the program itself.
`wyrd doctor` checks your disk, memory, graphics card and ports for you.

**The first start is the slow one.** On a bare Ubuntu machine, measured on
2026-07-25, `wyrd start` took 7 minutes 37 seconds before the server answered
on port 7070. Almost all of that is downloading models. A companion normally
uses two: a "drive" model that plans and uses tools, and a small "voice" model
that speaks. The drive model alone is about 5.3 GB, and the companion and
embedding models and the inference containers come on top. Allow ten minutes
and a good connection. Later starts take seconds. The optional larger model
needs more; see "Choosing where the AI runs".

## The quick way: one line

```bash
# Linux and macOS
curl -fsSL https://wyrdsekai.org/install | bash
```

```powershell
# Windows (PowerShell)
irm https://wyrdsekai.org/install.ps1 | iex
```

This picks the right installer for your computer and downloads it. It checks
the file against the release's list of checksums, `SHA256SUMS`, and **refuses
to install if they do not match**. It is the same file and the same check you
would use by hand, with fewer chances to skip the check.

Only the script itself comes from `wyrdsekai.org`. The installer and the
checksums both come from the same GitHub release, so the script cannot hand you
a file that the published checksums do not match. Both scripts are in the
repository, at [`site/install`](../site/install) and
[`site/install.ps1`](../site/install.ps1), if you want to read them first.
Reading a script before you pipe it into a shell is a sensible instinct, and
this project will not talk you out of it. Installing by hand stays fully
supported and always will.

The installers are 1.2 to 1.8 GB each. They include a Java runtime, an
inference runtime that runs the models, and the embedding model that search
uses. The companion models are not inside: `wyrd setup` downloads them on the
first run. Those models are open, under the Apache-2.0 license, and published
at [huggingface.co/wyrdsekai](https://huggingface.co/wyrdsekai).
[MODELS.md](MODELS.md) lists every one.

## Installing by hand

Release files are published on GitHub. Download the one for your computer:

```
# Linux
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.5.0/wyrdsekai_0.5.0_amd64.deb

# macOS (Apple Silicon; see the Intel note below)
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.5.0/Wyrdsekai-0.5.0.pkg

# Windows (PowerShell)
curl.exe -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.5.0/Wyrdsekai-0.5.0.msi

# Relay host: 115 KB, no Java and no models
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.5.0/wyrdsekai-relay-0.5.0.tar.gz
```

**Check what you downloaded before you run it.** The checksums sit beside the
files:

```
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.5.0/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing     # Linux
shasum -a 256 -c SHA256SUMS --ignore-missing # macOS
```

## Linux: the `.deb` package

```bash
sudo dpkg -i wyrdsekai_0.5.0_amd64.deb
sudo apt-get install -f          # if dependencies are missing
```

The prebuilt packages are for x86_64 (amd64) machines. On an arm64 machine,
build from source: there, `./packaging/build-all.sh --deb` produces
`wyrdsekai_<version>_arm64.deb`.

The program goes to `/opt/wyrdsekai`, with links in `/usr/local/bin` so `wyrd`
is on your `PATH`. Its services, the systemd units, go under
`/usr/lib/systemd/system/`. Your data goes to `/var/lib/wyrdsekai`.

**Keep the invite it prints.** The package creates a one-time invite for the
first account, the steward, and prints it. It also saves it to
`/etc/wyrdsekai/steward-bootstrap.invite` with mode `0600`, so other users
cannot read it. It expires in 24 hours.

A fresh install does **not** start the service by itself. Run `wyrd setup`
first.

**Upgrades keep everything.** Install a newer `.deb` over the old one, or run
`wyrd update now`, which fetches this platform's package from the GitHub
release and checks it against `SHA256SUMS` first. Either way, the upgrade
snapshots the databases, stops the service, swaps the program files and brings
it back exactly as it was. `wyrd update` says which release is running
and which is the latest. `wyrd update auto on` lets the node install a newer
release by itself, at a quiet moment inside its window. See
[CONFIGURATION.md](CONFIGURATION.md#updates). The upgrade to 0.5.0 also
encrypts the household's own connections; read
[Upgrading to 0.5.0: encryption at home](#upgrading-to-050-encryption-at-home)
first if you have more than one machine or a phone.

**Memory.** The package sets `vm.swappiness=10` in
`/etc/sysctl.d/90-wyrdsekai.conf`. With it, the kernel drops model files it has
mapped into memory before it swaps out the server. The file is marked as your
configuration, so you can edit or remove it.

**Optional helpers.** The package recommends three more programs:

- `sqlite3`, for the brainstem's database snapshots. The brainstem is a small
  watcher that restarts the server if it stops answering.
- `nftables`, for doors as firewall sets. A door is a connection to the
  outside, such as the relay, that can be closed.
- `bpftrace`, for the kernel hooks that watch what the companions' tools do.

`apt-get install` pulls them in, but `dpkg -i` does not. A node without them
runs with those parts missing, and `wyrd body` says so.

Each companion gets its own Linux user the first time it runs a tool. The users
are named `wyrd-being-<slug>`, with user ids from 62000 to 62999.

The package ships these services (systemd units):

- `wyrdsekai`: the zone, the main server. `wyrd start` enables it.
- `wyrdsekai-brainstem`: the watcher outside the main program. The package
  enables it.
- `wyrdsekai-oracle`: the optional prediction helper, on `:7073`. Enabled by
  default.
- `wyrdsekai-nats`: disabled. Only for running NATS on its own.
- `wyrdsekai-llama`: disabled. `wyrd inference local` enables it when needed.
- `wyrdsekai-metasearch`: the web-search proxy.
- `wyrdsekai-rendezvous`: disabled.

To remove it, run `sudo apt-get remove --purge wyrdsekai`, or the interactive
`wyrd uninstall`. `wyrd purge` is different: it purges and reinstalls, for a
clean zone reset.

## macOS: the `.pkg` package

This is for Apple Silicon Macs. Intel Macs need one extra step, described
below.

```bash
open Wyrdsekai-0.5.0.pkg
# or:
sudo installer -pkg Wyrdsekai-0.5.0.pkg -target /
```

The program goes to `/usr/local/wyrdsekai`, with links in `/usr/local/bin`.
Unlike on Linux, **all your data lives in the installing user's home folder**,
at `~/.wyrdsekai`, not under `/var/lib`.

After it installs, the package:

- writes a starting configuration and installs a background service, a
  **LaunchDaemon**, at `/Library/LaunchDaemons/com.wyrdsekai.server.plist`. It
  runs as root with `HOME` set to the installing user, so its data folder is
  that user's `~/.wyrdsekai`.
- sets up the Oracle helper in its own Python environment and starts it on
  `:7073`.
- on Apple Silicon, sets up the MLX runtime in `~/.wyrdsekai/mlx-venv` and
  installs the MLX voice LaunchAgent. MLX is Apple's way of running AI models
  on its own chips.
- installs the menu-bar app's LaunchAgent.
- creates the one-time steward invite and prints it.

### Intel Macs

On a Mac, local AI runs through MLX, and **MLX only works on Apple Silicon**.
The `.pkg` does not include an engine for Intel. On an Intel Mac, `wyrd setup`
notices this and tells you. The companion cannot think locally until you give
it an engine. There are three ways, in the order most people want them:

```bash
brew install llama.cpp        # local inference on Intel
wyrd setup                    # re-run; it picks up llama-server from PATH

wyrd inference remote http://<host>:8200   # or borrow it from another node
```

The third way is to rent the compute from a model provider over the internet.
[CONFIGURATION.md](CONFIGURATION.md#where-inference-runs) shows how.

Setup tells the two kinds of Mac apart with the `hw.optional.arm64` sysctl,
not `uname -m`. Under Rosetta, `uname` reports `x86_64` even on Apple Silicon,
which would send a perfectly capable M-series Mac down this path.

If the LaunchDaemon did not come up:

```bash
sudo launchctl bootstrap system /Library/LaunchDaemons/com.wyrdsekai.server.plist
sudo launchctl enable system/com.wyrdsekai.server
sudo launchctl kickstart -k system/com.wyrdsekai.server
log show --predicate 'subsystem == "com.wyrdsekai.server"' --last 5m
```

If the Oracle setup was put off because there was no network at install time,
run `wyrd oracle bootstrap`.

To remove it, run `wyrd uninstall`, or use the menu-bar icon and choose
**Uninstall…**. Dragging `Wyrdsekai.app` to the Trash only removes the icon.
The background services keep running and your data stays.

## Windows: the `.msi` package

Double-click `Wyrdsekai-0.5.0.msi`, or run `msiexec /i Wyrdsekai-0.5.0.msi`.

It installs for every user of the computer, in `C:\Program Files\Wyrdsekai`.
Start Menu and Desktop shortcuts open the tray app, `Wyrdsekai.Tray.exe`. The
Java runtime is included, so you do not need to install Java to *run* it. The
command-line tools `wyrd.cmd` and `wyrd.ps1` are in the install folder.

`wyrd setup` on Windows ends the same way as everywhere else: with a companion
that can think. The MSI does not include llama.cpp, the engine that runs the
models, because the right build depends on your graphics card: cpu, vulkan or
cuda. So setup detects your card, downloads the matching llama.cpp build and
pulls the model.

To use another household node or a cloud service instead, set
`WYRDSEKAI_SKIP_INFERENCE_INSTALL=1` before you run setup. Then run
`wyrd inference remote http://<node-ip>:8200`, which tests the address before
saving it, or put a cloud API key in the Key Chest. `wyrd inference install`
runs the local setup again at any time.

**Not yet on Windows.** These commands say "not yet available on Windows"
there: `web`, `connect`, `reset`, `nuke`, `bond`, `voice`, `issue`, `residency`,
`verify-release`, `embedding-model`, `embed-migrate`, `reseed`, `daemon`,
`relay-server` and `rendezvous`. `consent`, `feed`, `forge`, `grants`, `home`,
`openclaw`, `passwd`, `repair`, `skill`, `sleepwrite` and `wards` are not there
at all. Some subcommands are missing too: `inference local`,
`zone` and `disable`, `coding list` and `probe`, `config audit`,
`brain vram` and `reshape`, `vault sync`, `stage` and `restore`,
`household audit`, `library publish` and `setup --auto-yes`. On Windows,
`wyrd config set` takes `KEY VALUE` with a space, not `KEY=VALUE`.

To remove it, use **Settings → Apps**, or run
`msiexec /x Wyrdsekai-0.5.0.msi`. This removes `C:\Program Files\Wyrdsekai`
entirely.

**Your companion is not removed.** The world database, souls, journals and
downloaded models live in `%USERPROFILE%\.wyrdsekai`. Uninstalling the program
leaves them alone on purpose. If you reinstall, you pick up where you left off,
and the models, several GB, do not have to be downloaded again. If you want
that data gone too, delete the folder by hand:

```powershell
Remove-Item -Recurse -Force "$env:USERPROFILE\.wyrdsekai"
```

That cannot be undone. A companion's soul and history are in there, and
nothing else has a copy.

## Docker

### All-in-one image

One container holds the server, the command-line tool and a bundled
`llama-server`, the program that runs the models. Build it from the
repository, then run it:

```bash
./packaging/build-aio.sh          # -> wyrdsekai/wyrdsekai:cpu
./packaging/build-aio.sh cuda     # -> wyrdsekai/wyrdsekai:cuda

docker run -d --name wyrdsekai \
  -v wyrd-data:/data \
  -p 127.0.0.1:7070:7070 -p 7443:7443 -p 7022:7022 \
  wyrdsekai/wyrdsekai:cpu
```

Port `7443` is the encrypted door for phones and other machines. The plain port
`7070` is for a browser on this same machine: publish it on `127.0.0.1` as
above, never on all addresses, because what crosses it is not encrypted.

For the CUDA version, add `--gpus all`. The steward invite is printed to the
container log: `docker logs -f wyrdsekai`.

### Compose

From the repository root:

```bash
docker compose up                      # server only
docker compose --profile nats up       # + NATS (multi-node mesh)
docker compose --profile inference up  # + llama-server (default inference)
docker compose --profile sglang up     # + SGLang (NVIDIA, multi-companion)
docker compose --profile ollama up     # + Ollama (fallback only)
```

Useful settings, used in `docker-compose.yml`: `WYRDSEKAI_PORT` (7070),
`WYRDSEKAI_TELNET_PORT` (7071), `WYRDSEKAI_TLS_PORT` (7443), `WYRDSEKAI_DATA`
(the folder on your computer that appears as `/data` inside), `WYRDSEKAI_MODEL`,
`WYRDSEKAI_ZONE_ID`, `WYRDSEKAI_NATS_URL`. Versions for AMD cards (ROCm) and for
Apple are in `docker/docker-compose.rocm.yml` and
`docker/docker-compose.apple.yml`.

## Linux: building from source

```bash
git clone https://github.com/Wyrdsekai/wyrdsekai.git
cd wyrdsekai

# JDK 25 must be on PATH; the Gradle wrapper fetches Gradle itself.
./gradlew :server:installDist :cli:installDist

./bin/wyrd setup     # first-run: profile, models, inference, services
./bin/wyrd start
./bin/wyrd status
```

`bin/wyrd` knows it is running from a source checkout because `gradlew` sits at
the project root. It then builds on demand: most commands run
`:server:installDist` for you if the build is missing. From source, your data
lives in `~/.wyrdsekai`.

## First run

### 1. Create the steward account

The first account on a new zone is the **steward**, the household's main
administrator. The installer created a one-time invite for it. Use it over SSH:

```bash
ssh steward@localhost -p 7022     # password = the bootstrap code
```

On Linux the code is also in `/etc/wyrdsekai/steward-bootstrap.invite`, mode
`0600`. Under Docker it is in `docker logs wyrdsekai`. To skip the invite
altogether, put your SSH public key at `<data-dir>/authorized_keys` before you
first connect. A key gets in without it.

When the steward account is made, Wyrdsekai shows a **recovery key**: eight
words joined by dashes. It is shown this once and cannot be shown again. Write
it down and keep it somewhere safe. It is the only way back in if you lose the
steward password. Every way of making the first account shows it: SSH, the web
page, the phone app and telnet.

To make a fresh invite, run
`wyrd invite bootstrap [--name steward] [--ttl-hours 24]`. This only works on a
fresh install and fails once any account exists. It **needs root on Linux**,
because the invite database belongs to the service account:

```
sudo wyrd invite bootstrap --name <your-name>
```

Without `sudo` it fails with a Java stack trace instead of a permission
message. We know about this rough edge; see [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

Lost the steward password? Run `wyrd recover <recovery-key> <new-password>`.

### 2. Invite the rest of the household

```bash
wyrd invite create <name> [--role member|guest|child] [--ttl-hours 24] [--as <steward>]
```

`create` needs an existing steward. It prints a six-word passphrase on
**stdout** and everything else on stderr, so you can pipe it cleanly.

The new person connects the same way and enters the passphrase:

```bash
ssh <name>@home-server -p 7022
```

### 3. Pair a phone

A phone reaches your home *through* a relay. A relay is a small server that
passes encrypted traffic between your phone and your household when you are
out. So `wyrd phone invite` needs a relay first. On a zone without one, it
tells you so and points you to `wyrd relay join`. See "Running a relay" below
and [RELAY.md](RELAY.md).

```bash
wyrd phone invite
```

This prints a QR code in your terminal, with the raw invite link underneath.
No extra tools such as `qrencode` are needed. It works the same on Windows.
Scan it with the Wyrdsekai mobile app. Optional flags: `--relay <url>` and
`--fingerprint <fp>`.

The invite carries **this zone's id**. If the zone id cannot be found,
`wyrd phone invite` fails loudly. It will not print an invite that leads
nowhere and would quietly leave the phone in local mode.

### 4. Connect

```bash
ssh -p 7022 $USER@localhost      # SSH, the main way in
wyrd connect                     # built-in terminal client, local zone
wyrd connect home-server --ca-fp <fingerprint>   # built-in client, another machine
wyrd web enable                  # browser terminal on :7071 (requires ttyd)
```

`wyrd connect` to another machine uses that machine's encrypted port `7443`.
It needs that machine's certificate fingerprint: `wyrd doctor` on it prints
"household CA fingerprint".

`wyrd web` wraps `wyrd connect` in `ttyd`. It answers this machine only. To
open it to your home network, set `WYRDSEKAI_WEB_TERMINAL_BIND=0.0.0.0` before
`wyrd web enable`: it is then served over HTTPS with the household certificate
(your browser will not know that certificate and will ask). Every visitor still
has to log in.

## Start, stop and check

```bash
wyrd setup      # first-time setup: models, inference, docker services, profile
wyrd start      # start the server (on systemd: enable --now, so it survives reboot)
wyrd stop
wyrd restart
wyrd status     # what's running: pid, port health, inference backend, docker
wyrd log        # tail the server log (wyrd logs does the same)
wyrd doctor     # diagnose disk, memory, GPU, ports
wyrd nuke       # kill every wyrdsekai process (last resort)
```

`wyrd setup` is safe to run again. If it has never run, `wyrd start` runs it
for you first. `WYRDSEKAI_NO_AUTO_SETUP=1` turns that off, so `start` fails
straight away instead.

Setup asks a few questions, each with a countdown and a default. When it is not
running in a terminal, for example from a script or a provisioning tool, every
question takes its default by itself, so an unattended install works. Use
`wyrd setup --auto-yes` (`-y`) for unattended installs.

To check by hand, run `curl -sf http://localhost:7070/health` and
`curl -sf http://localhost:7070/.well-known/wyrd-zone`. On a systemd node,
`systemctl status wyrdsekai` works too.

## Where your data lives

`bin/wyrd` and the server agree on one data folder. They look for it in this
order:

1. `$WYRDSEKAI_DATA_DIR`, if you set it. It always wins.
2. **Linux, `.deb` install:** `/var/lib/wyrdsekai`, found through
   `/etc/wyrdsekai/wyrdsekai.conf`.
3. **macOS, `.pkg` install:** the path set in the LaunchDaemon plist, which is
   the installing user's `~/.wyrdsekai`.
4. Otherwise `$HOME/.wyrdsekai`, for source and development installs.

What is inside:

| Path | What it holds |
|---|---|
| `world.db` | the world, accounts, invites and bonds |
| `models/` | downloaded GGUF and embedding models |
| `env` | settings the command-line tool can see |
| `credentials.safe` | encrypted credential slots, mode `0600` |
| `vault.key`, `vault-store/` | the sealed backup copies of the companion. **Back up the key**: a copy cannot be read without it |
| `brainstem/` | the watcher's heartbeat, its door state and the hooks' records |
| `beings/<slug>/` | each companion's home and workspace |
| `.server.pid`, `.server.log` | the running server's process id and log |
| `oracle/`, `.venv-oracle/` | the Oracle helper |

Under Docker, all of it lives at `/data` inside the container.

On an installed Linux node the settings file is
`/etc/wyrdsekai/wyrdsekai.conf`. The systemd unit reads it, and
`wyrd config set`, `wyrd relay` and `wyrd join` save to it. Everywhere else it
is `<data-dir>/wyrdsekai.conf`.

On a `.deb` install, `/root/.wyrdsekai` and the installing user's
`~/.wyrdsekai` are links to `/var/lib/wyrdsekai`. Every path leads to the same
place, whichever user runs the command-line tool.

## The Recovery Seed

Backups live on the same machine as your companion. If that machine's disk
dies, the backups go with it. The Recovery Seed is a small sealed file that can
bring a companion back on another machine. It holds her soul (who she is and
what she has made of her days), her bonds, and her own signing key, the key
that proves she is who she says. It does not hold every conversation word for
word. Only the steward can make one or use one.

```bash
wyrd seed generate                 # asks for a passphrase twice; saves <name>-recovery-seed-<date>.wsrs in your home folder
wyrd seed generate Mia --out mia.wsrs   # name her when more than one companion lives here
wyrd seed verify mia.wsrs          # whose seed it is and when it was made; changes nothing
wyrd seed restore mia.wsrs         # on the new machine; she wakes at the next start
```

- **The passphrase** must be at least 12 characters. A few ordinary words in a
  row are easier to remember than a jumble. Nobody can recover it for you.
  Without it the file cannot be opened. With it, anyone can bring her back and
  act as her, so keep the file and the passphrase in different places.
- **Copy the file off the machine**: a USB stick, your email, a cloud drive.
  Wyrdsekai does not send it anywhere for you. The node keeps a sealed copy of
  the newest seed in `<data-dir>/recovery-seed/`, which helps only while that
  disk lives.
- **Make a new one now and then.** A seed holds her as she was on the day it was
  made. When she is restored, her chronicle says so: the seed's date, and that
  what came after is not in her.
- **Restoring never pushes anyone out.** If the new machine already has her, or
  has a different companion under the same name, restore refuses and changes
  nothing. On a new machine, restore before you give a new companion her name.
- **Where it runs.** Run it on the node itself, or log in first with
  `wyrd login` as the steward. From another machine it is only accepted over
  an encrypted connection. It works the same on Windows.
- `wyrd doctor` reminds you while no seed has been made on the node.

## Upgrading to 0.5.0: encryption at home

Before 0.5.0 the web port (`7070`), the household bus (`4222`) and the phones'
websocket (`4223`) were unencrypted on your home network, and the bus had no
login. From 0.5.0:

- Each home makes its own certificate the first time it starts, and serves
  HTTPS and WSS on port `7443`.
- Port `7070` answers only the machine it runs on.
- The bus and the phones' websocket are encrypted, and every machine and phone
  needs its own login.

What that means for you:

**One machine, no phone app.** Nothing to do. The browser on the same machine,
`wyrd connect` and SSH work as before.

**Several machines.** Update the hub first. Then, on every other machine, run
`wyrd household key` on the hub, `wyrd join <hub> --household-key <the whole
line>` on the machine, and `wyrd restart`. Until a machine has joined again, it
cannot reach the hub's bus. To keep old machines working while you do this,
set `WYRDSEKAI_NATS_LAN_PLAINTEXT=true` on the hub (see
[CONFIGURATION.md](CONFIGURATION.md)), then remove it.

**Phone apps.** Apps from before 0.5.0 talk to port `7070` and to the bus
without encryption, so on your home network they can no longer reach the home.
Through a relay they keep working. Update the app and pair the phone again: the
new pairing carries the home's certificate fingerprint and the phone's own
login. To let old apps in on the home network for a while, set
`WYRDSEKAI_HTTP_LAN_PLAINTEXT=true` (and `WYRDSEKAI_NATS_LAN_PLAINTEXT=true`
for the bus) on the home. Their traffic is then readable on your network, as
before. `wyrd doctor` warns while either is on.

`wyrd doctor` shows the home's certificate fingerprint, how long the
certificate has left, and whether the bus asks for a login and encryption.

## What gets installed

These are the parts and the network ports they use.

| Part | What it is | Default port |
|---|---|---|
| `wyrdsekai` server | The zone itself, encrypted: phones, browsers and other machines | `7443` (HTTPS, WSS) |
| `wyrdsekai` server | The same, unencrypted, for this machine only | `7070` |
| MINA sshd | The built-in SSH server, the main terminal client | `7022` |
| Terminal surface | Telnet and browser terminal (`wyrd web`), this machine only | `7071` |
| NATS WebSocket | Mobile clients, encrypted, login required | `4223` |
| `nats-server` | The Between, the network that links your household's machines; encrypted, login required | `4222` (monitor `8222`, this machine only) |
| `llama-server` | Runs the models locally: drive model / voice model | `8200` / `8201` |
| `metasearch` | Web-search proxy | none |
| Oracle | Prediction and forecasting helper (optional) | `7073` |
| Searxng | Self-hosted search engine (Docker, optional) | `8888` |

NATS is the messaging system your machines use to talk to each other. The main
server starts its own copy of it at boot. So the separate `nats-server` unit
stays **disabled** on a fresh install. It exists only for setups that run NATS
on its own, and would clash on `4222`.

Port `7071` can be taken by three things, depending on how you run: the telnet
surface, the browser terminal, and the optional rendezvous directory. Run at
most one of them.

## Choosing where the AI runs

`wyrd inference` decides where the models run:

```
wyrd inference status | local [model-path] | remote <url> | zone <zoneId> | share on|off|status | disable
wyrd inference restart          # stop and start the model servers, so a new setting takes effect
wyrd inference start | stop     # only the model servers; the rest of the node keeps running
```

`wyrd model` manages the model files themselves:

```
wyrd model status|verify|check|history|update <id>|rollback <id>
```

[ZONES.md](ZONES.md) explains how to use one machine's models from another.

### The larger model

Since 0.5.0 a companion can run on one larger model instead of two small ones.
You need an NVIDIA graphics card with 16 GB of memory and 32 GB of regular
memory. Nothing changes unless you switch it on. It works on Linux today.
Windows and Mac are included but have not been tested yet.

```
wyrd brain plan              # does this computer qualify?
wyrd brain setup             # download the model, about 22 GB; it asks first
wyrd brain enable --single   # switch to it
wyrd restart
wyrd brain disable           # switch back
```

Nothing is deleted either way. If a game or another program needs the graphics
card, the companion moves part of itself into regular memory, and moves back
when the card is free again. `wyrd brain vram 8` keeps it under 8 GB of the
card. [CONFIGURATION.md](CONFIGURATION.md#serving-profiles) has the details.

## Your coding helper works out of the box

Every installer includes **CodeZaiku**, the coding helper companions use by
default. It is one file that works on every platform, checked against its
published checksum when the installer is built. There is nothing to download
and nothing to configure. It uses the node's own models.

To prove it works on your computer, with a real task judged by what ends up on
disk:

```bash
wyrd coding probe codezaiku
```

`probe: OK — the backend did real work on this machine` is the line that
matters. Other coding helpers are one `wyrd coding install <name>` away.
`wyrd coding list` shows what is available. Anything that needs a login or key
says exactly what, and how to get it, when it declines to run.

## The research librarian (optional)

ResearchZosho, the household's research librarian, is a separate program.
`wyrd researcher setup` installs it with its own checked installer, runs its
setup and connects this node:

```
wyrd researcher setup
wyrd researcher status
```

By default the library reads with the companions' brain. A research run can
hold that model for up to 90 minutes, and the companions answer more slowly
while it does. A household with a second graphics card, or another machine with
one, can give the library a model of its own:

```
wyrd researcher gpu              # where the library's model runs, and the command to change it
wyrd researcher gpu 1            # card 1 of this machine (Linux)
wyrd researcher gpu <machine>    # another machine; run there first: researchzosho model install --share
wyrd researcher gpu shared       # back to the companions' brain
```

[CONFIGURATION.md](CONFIGURATION.md#giving-the-library-its-own-gpu) has the
details.

## Running a relay

A relay is a separate, very small install. It passes encrypted bytes between
phones and household zones, and never sees your world. It does **not** come
from the platform installers. It ships as its own bundle, so a relay host never
needs the full ~1.8 GB program:

```
# on the relay host
tar xzf wyrdsekai-relay-0.5.0.tar.gz
cd wyrdsekai-relay-0.5.0
sudo sh relay.sh relay.example.com          # docker if present, else native systemd
sudo sh relay.sh --native relay.example.com # force the no-docker path
```

The bundle is `relay.sh` plus the `deploy/relay/` files it needs beside it,
about 115 KB. You can build it with `packaging/build-all.sh --relay`. It also
comes out of a plain platform build.

Each relay makes its own passwords on first run, so no two relays share one.
If you are setting up an **existing** relay again and want the phones to keep
working, export `NATS_PHONE_PASSWORD` and `RELAY_JOIN_PASSWORD` with their
current values before you run `relay.sh`. Otherwise new secrets are made and
invites you already gave out stop working. You can then make a new one with
`wyrd phone invite`.

## Troubleshooting

**`wyrd setup` cannot write the data folder.** On a `.deb` installed as bare
root, the data folder stays owned by root. Run `sudo wyrd setup`, or
`chown -R "$USER" /var/lib/wyrdsekai`.

**A port is already in use.** `wyrd doctor` checks `7070`, `7071`, `7022`,
`4222`, `8222` and `8888`. `wyrd nuke` frees them. If another program holds
`7443`, the server still starts, but phones and other machines cannot reach it
on your network; the log says so. Free the port or set `WYRDSEKAI_TLS_PORT`.

**NATS will not use `4222`.** The main server starts its own NATS. Do not
enable `wyrdsekai-nats` unless you really mean to run NATS on its own.

**After a reboot, a service is "enabled but not started".** Enabled does not
mean started. Check both `systemctl is-enabled` *and* `systemctl is-active` for
each unit you rely on.

**Nothing works and you want a clean slate.** `wyrd reset soft` stops the
services and clears state. `wyrd reset-zone <recovery-key>` is a factory reset.
`wyrd purge` purges and reinstalls on Linux.

**Backups.** `wyrd backup` takes a snapshot. `wyrd restore` with no argument
lists the snapshots you have. `wyrd state dump --summary` prints an overview of
the node's state.

## For developers: building the installers

```bash
./packaging/build-all.sh            # dist archive + the native package for this OS
./packaging/build-all.sh --dist     # distribution tarball only
./packaging/build-all.sh --deb      # .deb only (needs dpkg-deb: sudo apt install dpkg-dev)
./packaging/build-all.sh --pkg      # .pkg only (macOS only)
./packaging/build-all.sh --msi      # .msi only (Windows, needs PowerShell + WiX)
```

Packages are copied into `build/installers/`, the one folder to ship from.
`build/deb/`, `build/pkg/` and `build/win/` hold each platform's build tree.

Before packaging, `build-all.sh` runs a release-time classifier evolution bake,
and **stops the release if a head gets worse**. `WYRDSEKAI_SKIP_BAKE=1` skips it
for emergency builds. Set the version with `WYRDSEKAI_VERSION=0.2.0`.

### Building the MSI

You need **Java 25+** for `jpackage`, and **WiX Toolset 3.x**, with
`candle.exe`, `light.exe` and `heat.exe` on `PATH` or installed at
`C:\Program Files (x86)\WiX Toolset v3.11|v3.14\bin`.

PowerShell's default execution policy blocks running `.ps1` *files*, but not
script blocks. So run the script's text rather than the file:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk25'
$env:PATH = "$env:JAVA_HOME\bin;C:\path\to\wix;$env:PATH"
$sb = [scriptblock]::Create((Get-Content '.\packaging\windows\build-msi.ps1' -Raw))
& $sb -Version "0.5.0"
```

The build fetches its own model files first. The embedding and classifier ONNX
files and the voice steering vectors are large, so they are kept out of the
repository. `build-msi.ps1` downloads them from the pinned revisions in
`packaging\build-assets.json` and `models-index.json`, and checks each sha256.
Expect about 400 MB on the first build. Later builds reuse what is already
there. If a download cannot be verified, the build stops. It will not produce
an installer with a weaker classifier and a voice missing its steering vectors.

The result lands in `build\win\Wyrdsekai-<version>.msi` and is copied to
`build\installers\`.
