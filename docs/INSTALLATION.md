# Installing Wyrdsekai

Wyrdsekai runs as a small always-on service on a machine you own. A single
node is a complete household: world, companions, inference, and every client
surface. You add more machines later — see [ZONES.md](ZONES.md).

Every install ends the same way: a running service, a steward account, and a
QR code you scan with your phone.

## What gets installed

| Component | What it is | Default port |
|---|---|---|
| `wyrdsekai` server | The zone itself — world, companions, HTTP/WS API | `7070` |
| MINA sshd | SSH surface — the primary terminal client | `7022` |
| Terminal surface | Telnet / browser terminal (`wyrd web`) | `7071` |
| NATS WebSocket | Mobile clients | `4223` |
| `nats-server` | The Between — inter-node mesh | `4222` (monitor `8222`) |
| `llama-server` | Local inference (drive model / voice model) | `8200` / `8201` |
| `metasearch` | Web-search proxy | — |
| Oracle | Prediction/forecasting sidecar (optional) | `7073` |
| Searxng | Self-hosted search (Docker, optional) | `8888` |

The main server spawns its own embedded NATS at boot, so the standalone
`nats-server` unit stays **disabled** on a fresh install — it exists only for
standalone-NATS deployments and would collide on `4222`. Note that `7071` is
claimed by three things depending on how you run (telnet surface, browser
terminal, and the optional rendezvous directory); run at most one of them.

## Standing up a relay

A relay is a separate, deliberately tiny install — it shuffles encrypted bytes
between phones and household zones and never sees your world. It does **not**
come from the platform installers; it ships as its own bundle so a relay host
never needs the full ~1.8 GB tree:

```
# on the relay host
tar xzf wyrdsekai-relay-0.1.5.tar.gz
cd wyrdsekai-relay-0.1.5
sudo sh relay.sh relay.example.com          # docker if present, else native systemd
sudo sh relay.sh --native relay.example.com # force the no-docker path
```

The bundle is `relay.sh` plus the `deploy/relay/` payload it needs beside it;
build it with `packaging/build-all.sh --relay` (it also comes out of a plain
platform build). About 115 KB.

Each relay generates its own infrastructure credentials on first run, so no two
relays share a password. If you are redeploying an EXISTING relay and want to
keep the credentials your phones already hold, export `NATS_PHONE_PASSWORD` and
`RELAY_JOIN_PASSWORD` with their current values before running `relay.sh` —
otherwise fresh secrets are minted and previously-issued invites stop working
(mint a new one with `wyrd phone invite`).


## What the first start actually costs

Measured on a bare Ubuntu box, 2026-07-25: `wyrd start` took **7m37s** before
the server answered on port 7070. Almost all of it is downloading models —
roughly 5.3 GB for the drive model alone, plus the companion and embedding
models and the inference containers. Budget ten minutes and a good connection
for the first run; subsequent starts are seconds.

`wyrd start` on a machine that has never been set up runs `wyrd setup` for you
first. It asks a few questions, each with a countdown and a default, and when
stdin is not a terminal (a script, a provisioning tool) every prompt takes its
default automatically — so an unattended install works.

**Steward bootstrap needs root.** The invite database is owned by the service
account, so run it with `sudo`:

```
sudo wyrd invite bootstrap --name <your-name>
```

Without `sudo` it fails with a Java stack trace rather than a permission
message — a rough edge we know about (see KNOWN_ISSUES.md).

`wyrd phone invite` requires a relay to exist first; on a zone with no relay it
tells you so and points at `wyrd relay join`. That ordering is intentional —
the phone reaches your zone *through* a relay.


## Prerequisites

| Platform | Required | Recommended |
|---|---|---|
| Linux | Java 25 JRE (`default-jre-headless (>= 2:1.25)` or `openjdk-25-jre-headless`) | Docker or Podman; `python3-gi` + GTK/AppIndicator bindings for the tray; `policykit-1`; `xdg-utils` |
| macOS | Apple Silicon for local inference (Intel: `brew install llama.cpp` first); Xcode command line tools if building | Java 25 (bundled in the `.pkg` payload path) |
| Windows | Windows 10+ x64 | — (the `.msi` bundles its own runtime via `jpackage`) |
| Docker | Docker Engine + Compose v2 | NVIDIA Container Toolkit for `--gpus all` |
| From source | JDK 25 (`languageVersion = 25`), Gradle wrapper (9.2.1, downloaded automatically) | `dpkg-dev` to build a `.deb` |

Disk: budget ~10 GB for models on top of the package itself. `wyrd doctor`
checks disk, memory, GPU, and port conflicts for you.

## Getting the installers

Release artifacts are published as GitHub release assets — 1.2–1.8 GB each,
because they bundle a JVM, an inference runtime and the embedding model. The
companion model weights are not inside the installer: `wyrd setup` downloads
them on first run. Those weights are open (Apache-2.0) and published at
[huggingface.co/wyrdsekai](https://huggingface.co/wyrdsekai) — see
[MODELS.md](MODELS.md) for the full repo map.

### The recommended way: one line

```bash
# Linux and macOS
curl -fsSL https://wyrdsekai.org/install | bash
```

```powershell
# Windows (PowerShell)
irm https://wyrdsekai.org/install.ps1 | iex
```

That picks the right artifact for your platform, downloads it, **verifies it
against the release's `SHA256SUMS`, and refuses to install on a mismatch**. It
is the same file you would have downloaded by hand and the same checksum you
would have run — with fewer chances to skip the checking step.

Only the script itself is served from `wyrdsekai.org`. The installer and the
checksums both come from the same GitHub release, so the script cannot hand you
a payload the published checksums do not match. Both scripts are in the
repository under [`site/install`](../site/install) and
[`site/install.ps1`](../site/install.ps1) if you would like to read them first —
and reading a script before piping it to a shell is a reasonable instinct that
this project will not try to talk you out of. The by-hand route below stays
fully supported and always will.

### If you would rather do it by hand

```
# Linux
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.1.5/wyrdsekai_0.1.5_amd64.deb

# macOS (Apple Silicon; see the Intel note below)
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.1.5/Wyrdsekai-0.1.5.pkg

# Windows (PowerShell)
curl.exe -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.1.5/Wyrdsekai-0.1.5.msi

# Relay host — 115 KB, no JVM and no models
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.1.5/wyrdsekai-relay-0.1.5.tar.gz
```

**Verify what you downloaded before running it.** Checksums sit beside the
artifacts:

```
curl -fLO https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.1.5/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing     # Linux
shasum -a 256 -c SHA256SUMS --ignore-missing # macOS
```

## Linux — `.deb`

```bash
sudo dpkg -i wyrdsekai_0.1.5_amd64.deb
sudo apt-get install -f          # if dependencies are missing
```

`arm64` builds use `wyrdsekai_0.1.5_arm64.deb`. The package installs to `/opt/wyrdsekai` with symlinks in `/usr/local/bin`
(so `wyrd` is on your `PATH`), systemd units under
`/usr/lib/systemd/system/`, and state under `/var/lib/wyrdsekai`.

The `postinst` mints a **one-time steward bootstrap invite** and prints it,
also saving it mode-`0600` to `/etc/wyrdsekai/steward-bootstrap.invite`. It
expires in 24 hours. Keep that terminal output.

A fresh install does **not** auto-start the service — it needs `wyrd setup`
first. Upgrades (`dpkg -i` over an existing install) stop the service, swap
jars, and bring it back exactly as it was.

Systemd units shipped: `wyrdsekai` (the zone — enabled by `wyrd start`),
`wyrdsekai-oracle` (enabled by default, `:7073`), `wyrdsekai-nats` (disabled;
standalone NATS only), `wyrdsekai-llama` (disabled; enabled on demand by
`wyrd inference local`), `wyrdsekai-metasearch`, and `wyrdsekai-rendezvous`
(disabled).

Uninstall with `sudo apt-get remove --purge wyrdsekai`, `wyrd purge` (purge +
reinstall for a clean zone reset), or the interactive `wyrd uninstall`.

## Linux — build from source

```bash
git clone <repo-url> wyrdsekai
cd wyrdsekai

# JDK 25 must be on PATH; the Gradle wrapper fetches Gradle itself.
./gradlew :server:installDist :cli:installDist

./bin/wyrd setup     # first-run: profile, models, inference, services
./bin/wyrd start
./bin/wyrd status
```

`bin/wyrd` detects source-checkout mode by the presence of `gradlew` at the
project root and builds on demand — most subcommands run `:server:installDist`
for you if the install tree is missing. In source mode state lives in
`~/.wyrdsekai`.

### Building the installers yourself

```bash
./packaging/build-all.sh            # dist archive + the native package for this OS
./packaging/build-all.sh --dist     # distribution tarball only
./packaging/build-all.sh --deb      # .deb only (needs dpkg-deb: sudo apt install dpkg-dev)
./packaging/build-all.sh --pkg      # .pkg only (macOS only)
./packaging/build-all.sh --msi      # .msi only (Windows, needs PowerShell + WiX)
```

Packages are cross-published into `build/installers/` — that is the canonical
directory to ship from. `build/deb/`, `build/pkg/`, and `build/win/` hold the
per-platform build trees.

`build-all.sh` runs a release-time classifier evolution bake before packaging
and **aborts the release if a head regresses**; `WYRDSEKAI_SKIP_BAKE=1` skips
it for emergency builds. Override the version with `WYRDSEKAI_VERSION=0.2.0`.

## macOS — `.pkg` (Apple Silicon; Intel needs one extra step)

```bash
open Wyrdsekai-0.1.5.pkg
# or:
sudo installer -pkg Wyrdsekai-0.1.5.pkg -target /
```

The package installs to `/usr/local/wyrdsekai` with symlinks in
`/usr/local/bin`. Unlike the `.deb`, **all state lives under the installing
user's home** at `~/.wyrdsekai` — not `/var/lib`.

The postinstall:

- writes a baseline config and installs a **LaunchDaemon** at
  `/Library/LaunchDaemons/com.wyrdsekai.server.plist` (runs as root with
  `HOME` pinned to the installing user, so its data dir resolves to that
  user's `~/.wyrdsekai`),
- bootstraps the Oracle sidecar into a venv and starts it on `:7073`,
- on Apple Silicon, bootstraps the MLX runtime into `~/.wyrdsekai/mlx-venv`
  and installs the MLX voice LaunchAgent,
- installs the menu-bar app LaunchAgent,
- mints the one-time steward bootstrap invite and prints it.

### Intel Macs

Local inference on macOS runs through **MLX, which is Apple-Silicon only**, and
the `.pkg` does not bundle an Intel inference engine. On an Intel Mac `wyrd
setup` detects this and tells you; the companion will not think locally until
you give it an engine. Three ways out, in the order most people want them:

```bash
brew install llama.cpp        # local inference on Intel
wyrd setup                    # re-run; it picks up llama-server from PATH

wyrd inference api            # or rent the compute from a model provider
wyrd inference remote http://<host>:8200   # or borrow it from another node
```

Detection uses the `hw.optional.arm64` sysctl rather than `uname -m`, because
under Rosetta `uname` reports `x86_64` on Apple Silicon — which would send a
perfectly capable M-series Mac down this path.

If the LaunchDaemon didn't come up:

```bash
sudo launchctl bootstrap system /Library/LaunchDaemons/com.wyrdsekai.server.plist
sudo launchctl enable system/com.wyrdsekai.server
sudo launchctl kickstart -k system/com.wyrdsekai.server
log show --predicate 'subsystem == "com.wyrdsekai.server"' --last 5m
```

If the Oracle bootstrap was deferred (no network at install time), run
`wyrd oracle bootstrap`.

Uninstall with `wyrd uninstall` or the menu-bar icon → **Uninstall…**. Dragging
`Wyrdsekai.app` to the Trash removes only the icon — the background services
keep running and your data stays.

## Windows — `.msi`

Double-click `Wyrdsekai-0.1.5.msi`, or run `msiexec /i Wyrdsekai-0.1.5.msi`.

The MSI is per-machine and installs to `C:\Program Files\Wyrdsekai`, with a
Start Menu and Desktop shortcut pointing at the tray app
(`Wyrdsekai.Tray.exe`). The Java runtime is bundled by `jpackage`, so no
separate JDK install is needed to *run* it. The CLI wrappers `wyrd.cmd` and
`wyrd.ps1` ship in the install directory.

`wyrd setup` on Windows ends the same way it does everywhere else: with a
companion that can think. The MSI ships no llama.cpp binary (it is fetched to
match your GPU — cpu/vulkan/cuda), so setup finishes by GPU-detecting,
downloading the right llama.cpp build, and pulling the model. If you plan to
use a remote household node or a cloud backend instead, set
`WYRDSEKAI_SKIP_INFERENCE_INSTALL=1` before running setup, then point
`WYRDSEKAI_LLAMA_URL` at the remote `:8200` (or set a cloud API key in the
Key Chest). `wyrd inference install` re-runs the local flow any time.

Uninstall from **Settings → Apps**, or with
`msiexec /x Wyrdsekai-0.1.5.msi`. This removes `C:\Program Files\Wyrdsekai`
entirely.

**Your companion is not removed.** The world database, souls, journals and
downloaded models live in `%USERPROFILE%\.wyrdsekai`, and uninstalling the
program deliberately leaves them alone — reinstalling picks up where you left
off, and models (several GB) do not have to be fetched again. If you want that
data gone too, delete the directory by hand:

```powershell
Remove-Item -Recurse -Force "$env:USERPROFILE\.wyrdsekai"
```

That is irreversible: a companion's soul and history are in there, and nothing
else has a copy.

### Building the MSI

Requires **Java 25+** (for `jpackage`) and **WiX Toolset 3.x**
(`candle.exe` / `light.exe` / `heat.exe` on `PATH`, or installed at
`C:\Program Files (x86)\WiX Toolset v3.11|v3.14\bin`).

PowerShell's default execution policy blocks running `.ps1` *files*, but not
scriptblocks, so run the script's text rather than the file:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk25'
$env:PATH = "$env:JAVA_HOME\bin;C:\path\to\wix;$env:PATH"
$sb = [scriptblock]::Create((Get-Content '.\packaging\windows\build-msi.ps1' -Raw))
& $sb -Version "0.1.5"
```

The build fetches its own model assets first — the embedding and classifier ONNX
files and the voice steering vectors are large binaries kept out of the
repository, so `build-msi.ps1` downloads them from the pinned revisions in
`packaging\build-assets.json` and `models-index.json` and verifies each sha256.
Expect roughly 400MB on the first build; later builds reuse what is already
there. If a download cannot be verified the build stops rather than producing an
installer with a degraded classifier and a voice missing its steering vectors.

Output lands in `build\win\Wyrdsekai-<version>.msi` and is republished to
`build\installers\`.

## Docker

### All-in-one image

One container with the server, the CLI, and a bundled `llama-server`.

```bash
./packaging/build-aio.sh          # -> wyrdsekai/wyrdsekai:cpu
./packaging/build-aio.sh cuda     # -> wyrdsekai/wyrdsekai:cuda

docker run -d --name wyrdsekai \
  -v wyrd-data:/data \
  -p 7070:7070 -p 7022:7022 \
  wyrdsekai/wyrdsekai:cpu
```

For the CUDA variant add `--gpus all`. The steward bootstrap invite is printed
to the container log — `docker logs -f wyrdsekai`.

### Compose

From the repo root:

```bash
docker compose up                      # server only
docker compose --profile nats up       # + NATS (multi-node mesh)
docker compose --profile inference up  # + llama-server (default inference)
docker compose --profile sglang up     # + SGLang (NVIDIA, multi-companion)
docker compose --profile ollama up     # + Ollama (fallback only)
```

Useful environment variables (documented in the header of
`docker-compose.yml`): `WYRDSEKAI_PORT` (7070), `WYRDSEKAI_TELNET_PORT` (7071),
`WYRDSEKAI_TLS_PORT` (7443), `WYRDSEKAI_DATA` (host path bind-mounted at
`/data`), `WYRDSEKAI_MODEL`, `WYRDSEKAI_ZONE_ID`, `WYRDSEKAI_NATS_URL`. ROCm
and Apple variants live in `docker/docker-compose.rocm.yml` and
`docker/docker-compose.apple.yml`.

## Where your data lives

`bin/wyrd` and the server agree on one canonical data directory, resolved in
this order:

1. `$WYRDSEKAI_DATA_DIR` — explicit, always wins.
2. **Linux, `.deb` install:** `/var/lib/wyrdsekai` (detected via
   `/etc/wyrdsekai/wyrdsekai.conf`).
3. **macOS, `.pkg` install:** the path pinned in the LaunchDaemon plist —
   the installing user's `~/.wyrdsekai`.
4. Otherwise `$HOME/.wyrdsekai` (source and dev installs).

Inside it: `world.db` (the world, accounts, invites, bonds), `models/`
(downloaded GGUF and embedding models), `env` (CLI-visible environment file),
`credentials.safe` (encrypted credential slots, mode `0600`), `.server.pid` /
`.server.log`, and `oracle/` + `.venv-oracle/`. Under Docker, all of it lives
at `/data` in the container.

Config lives at `/etc/wyrdsekai/wyrdsekai.conf` on an installed Linux node —
this is what the systemd unit reads, and where `wyrd config set`, `wyrd relay`,
and `wyrd join` persist — otherwise `<data-dir>/wyrdsekai.conf`.

On a `.deb` install, `/root/.wyrdsekai` and the installing user's
`~/.wyrdsekai` are symlinked to `/var/lib/wyrdsekai` so every path resolves to
the same place regardless of which user runs the CLI.

## Start, stop, verify

```bash
wyrd setup      # first-time setup: models, inference, docker services, profile
wyrd start      # start the server (on systemd: enable --now, so it survives reboot)
wyrd stop
wyrd restart
wyrd status     # what's running: pid, port health, inference backend, docker
wyrd log        # tail the server log
wyrd doctor     # diagnose disk, memory, GPU, ports
wyrd nuke       # kill every wyrdsekai process (last resort)
```

`wyrd setup` is idempotent and safe to re-run. `wyrd start` runs setup
automatically if it never has been (`WYRDSEKAI_NO_AUTO_SETUP=1` restores
fail-fast). Use `wyrd setup --auto-yes` (`-y`) for unattended installs.

Verify by hand with `curl -sf http://localhost:7070/health` and
`curl -sf http://localhost:7070/.well-known/wyrd-zone`; on a systemd node,
`systemctl status wyrdsekai`.

## First run

### 1. Create the steward account

The first account on a fresh zone is the **steward** — the household's
super-admin. The installer minted a one-time bootstrap invite; redeem it over
SSH:

```bash
ssh steward@localhost -p 7022     # password = the bootstrap code
```

On Linux the code is also at `/etc/wyrdsekai/steward-bootstrap.invite` (mode
`0600`); under Docker it is in `docker logs wyrdsekai`. To skip the invite
entirely, pre-place your SSH public key at `<data-dir>/authorized_keys` before
your first connect — the pubkey path bypasses it.

Mint a fresh bootstrap invite with
`wyrd invite bootstrap [--name steward] [--ttl-hours 24]` (fresh installs only;
it fails once any account exists). Lost the steward password?
`wyrd recover <recovery-key> <new-password>`.

### 2. Invite the rest of the household

```bash
wyrd invite create <name> [--role member|guest|child] [--ttl-hours 24] [--as <steward>]
```

`create` prints a 4-word passphrase on **stdout** (everything else goes to
stderr, so it pipes cleanly). It requires an existing steward.

The new member connects the same way and redeems the passphrase:

```bash
ssh <name>@home-server -p 7022
```

### 3. Pair a phone

```bash
wyrd phone invite
```

This prints a QR code in your terminal (a dependency-free encoder is bundled —
no `qrencode` needed) plus the raw invite URL underneath. Scan it with the
Wyrdsekai mobile app. Optional flags: `--relay <url>` and `--fingerprint <fp>`.

The invite is stamped with **this zone's id** before it is printed. If the
zone id can't be determined, `wyrd phone invite` fails loudly rather than
emitting an unroutable invite that would silently drop the phone into local
mode.

### 4. Connect

```bash
ssh -p 7022 $USER@localhost      # SSH — the primary surface
wyrd connect                     # built-in terminal client, local zone
wyrd connect home-server 7070    # built-in client against another LAN machine
wyrd web enable                  # browser terminal on :7071 (requires ttyd)
```

`wyrd web` wraps `wyrd connect` in `ttyd` over plain HTTP — LAN/household
trust only. Every visitor still has to log in in-band.

## Managing inference

`wyrd inference status | local [model-path] | remote <url> | zone <zoneId> |
share on|off|status | disable` manages the backend, and
`wyrd model status|verify|check|history|update <id>|rollback <id>` manages the
weights. See [ZONES.md](ZONES.md) for routing inference across machines.

## Troubleshooting

**`wyrd setup` can't write the data dir.** On a bare-root `.deb` install the
state dir stays root-owned. Run `sudo wyrd setup`, or
`chown -R "$USER" /var/lib/wyrdsekai`.

**Port already in use.** `wyrd doctor` checks `7070`, `7071`, `7022`, `4222`,
`8222`, `8888`. `wyrd nuke` frees them.

**NATS won't bind `4222`.** The main server spawns an embedded NATS. Don't
enable `wyrdsekai-nats` unless you are deliberately running standalone NATS.

**After a reboot, a service is "enabled but not started."** `enable` is not
`start`. Check `systemctl is-enabled` *and* `systemctl is-active` for each
unit you rely on.

**Nothing works and you want a clean slate.** `wyrd reset soft` stops services
and clears state; `wyrd reset-zone <recovery-key>` is a factory reset;
`wyrd purge` does an apt purge + reinstall on Linux.

**Backups.** `wyrd backup` snapshots, `wyrd restore` lists available snapshots
when given no argument, and `wyrd state dump --summary` prints a state
overview.

## Your coding backend works out of the box

Every installer bundles **CodeZaiku**, the default coding backend — one
platform-independent artifact, verified against its published checksum at
build time. Nothing to download, nothing to configure: it drives the node's
own inference endpoint.

To prove it on your machine — a real task, judged by what lands on disk:

```bash
wyrd coding probe codezaiku
```

`probe: OK — the backend did real work on this machine` is the sentence that
matters. Other backends are one `wyrd coding install <name>` away; `wyrd
coding list` shows what's available, and anything that needs credentials
says exactly what and how when it declines to run.
