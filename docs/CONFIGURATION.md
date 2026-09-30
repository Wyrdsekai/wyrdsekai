# Configuration

This page lists the settings you can change in Wyrdsekai. For each one it says what it changes and when you might want to change it.

Most people never need this page. Setup picks sensible values for your computer. Come here when you want to change something on purpose. For example, you might want a different AI model, backups on a second disk, automatic updates or a second computer.

## How to change a setting

Wyrdsekai keeps its settings in one file. Each line holds one setting, written as `NAME=value`.

Change settings with the `wyrd config` command instead of editing the file by hand. The command always writes to the file the program actually reads.

```bash
wyrd config set WYRDSEKAI_UPDATE=auto    # change a setting
wyrd restart                             # apply it
```

On Windows, put a space between the name and the value instead of `=`:

```powershell
wyrd config set WYRDSEKAI_UPDATE auto
wyrd restart
```

Wyrdsekai reads its settings when it starts, so a change takes effect after `wyrd restart`. `wyrd config apply` does the same restart.

Other `wyrd config` commands:

| Command | What it does |
|---|---|
| `wyrd config list` | Shows the settings written in your file. |
| `wyrd config list --all` | Shows the catalog of settings: what each one does, its default and your current value. |
| `wyrd config list <group>` | Shows one group from the catalog, such as `inference` or `updates`. |
| `wyrd config get NAME` | Shows the value of one setting. |
| `wyrd config unset NAME` | Removes a setting, so its default applies again. |
| `wyrd config path` | Shows where your settings file is. |
| `wyrd config edit` | Opens the settings file in a text editor. |

### Where the settings file is

| How you installed | Settings file |
|---|---|
| Linux package `.deb`, or Mac package `.pkg` | `/etc/wyrdsekai/wyrdsekai.conf` |
| Windows `.msi` | `%USERPROFILE%\.wyrdsekai\wyrdsekai.conf` |
| From the source code | `~/.wyrdsekai/wyrdsekai.conf` |

If you are not sure, `wyrd config path` tells you.

On Linux, the Wyrdsekai service reads `/etc/wyrdsekai/wyrdsekai.conf` and no other file. Editing any other copy has no effect. When the file needs administrator rights, `wyrd config set` uses `sudo` and may ask for your password.

When you run from the source code, `wyrd setup` also writes a file called `env` in the same folder. Wyrdsekai reads both. If a setting is in both files, the one in `wyrdsekai.conf` wins.

### The data folder

Some settings and paths on this page mention the data folder, written `<data>`. It holds your companions, their memories and the program's databases.

| How you installed | Data folder |
|---|---|
| Linux package | `/var/lib/wyrdsekai` |
| Mac package, or from the source code | `~/.wyrdsekai` |
| Windows | `%USERPROFILE%\.wyrdsekai` |

### Two names for one setting

A few settings show a second, dotted name, such as `vault.dir` beside `WYRDSEKAI_VAULT_DIR`. Both names mean the same setting. The dotted name is the one used in `profile.toml`, an optional second settings file. If a setting is given both ways, the `WYRDSEKAI_` name wins.

## Words used on this page

- **Companion**: an AI being that lives in your Wyrdsekai world.
- **Steward**: the person who looks after a household's Wyrdsekai. The first account made on a new install is the steward. Several commands on this page need a steward login.
- **Node**: one computer running Wyrdsekai.
- **Household**: the computers that belong to you, linked together.
- **Zone**: a named part of your world. One node is enough for a zone.
- **The Between**: the network that links your nodes to each other, and to other households if you choose.
- **Hearth**: a companion's own room, its home.
- **Study**: a person's own private room. The steward gives some permissions from the Study.
- **Model**: the AI program a companion thinks with. A size like 9B means nine billion parameters, the numbers a model learned. Bigger models are usually smarter and slower.
- **Prompt**: the text Wyrdsekai sends a model each time the companion thinks.
- **Token**: a piece of a word. Models measure text in tokens.
- **Graphics card**: the part of a computer that runs models fastest, also called a GPU. Its own memory is called VRAM. The computer's regular memory is called RAM.
- **Adapter**: a small add-on file that changes how a model behaves, without changing the model itself.
- **llama-server**: the program Wyrdsekai uses to run models.
- **Docker**: a tool that runs programs in sealed containers. Wyrdsekai uses it for some of its parts, such as the model servers on Linux.
- **Port**: a numbered door on a computer where a program listens, such as 8200.

## Where the companion thinks

A companion needs a model to think with. Running a model to get an answer is called inference. This setting decides where that happens.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_INFERENCE_MODE` | `local` | `local`: a model runs on this computer. This is the point of the project. `cloud`: an online service answers, at the address in `WYRDSEKAI_INFERENCE_URL`. `zone`: another computer in your household answers, over the Between. |

A value Wyrdsekai does not recognise counts as `local`, and it warns you.

```bash
wyrd inference                                     # what is answering right now
wyrd inference remote https://api.example.com/v1   # switch to an online service
```

`wyrd inference remote` checks that something answers at the address before it saves it.

### The models on this computer

`wyrd setup` downloads your companion's models. It records the drive model's file in `WYRDSEKAI_MODEL_PATH`.

The standard setup uses two models. A 9B "drive" model plans and uses tools. A 4B "voice" model shapes how the companion speaks. [MODELS.md](MODELS.md) explains why there are two and why these sizes. It also explains how a phone can run the small one while borrowing the large one from your household.

| Setting | Default | What it changes |
|---|---|---|
| `WYRDSEKAI_INFERENCE_TIMEOUT` | `300`, written by setup on Linux and Mac. `120` if not set. | How many seconds Wyrdsekai waits for a model to answer. Raise it if a slow computer keeps running out of time. |
| `WYRDSEKAI_INFERENCE_CONCURRENCY` | `1`, written by setup on Linux and Mac. One per model server if not set. | How many requests the models may work on at once. Raise it only for a model server built to answer many requests together. |
| `WYRDSEKAI_INFERENCE_SIBLING_WAIT_SECONDS` | `120` | The bundled model servers on ports 8200 and 8201 take about 30 seconds to load after boot. This is how long Wyrdsekai waits for them before it starts a `llama-server` of its own. It applies when `WYRDSEKAI_MODEL_PATH` names a model. |
| `WYRDSEKAI_LLAMA_URL` | | The address of the drive model server. |
| `WYRDSEKAI_VOICE_URL` | | The address of the voice model server. |

## One larger model instead of two

Since 0.5.0, a companion can run on one larger model instead of two small ones, if your computer is big enough. This is optional. Nothing changes unless you switch it on.

The larger model is a "sparse" model. It is made of many small expert parts, and only a few of them work on each word. That lets a big model run on a home computer.

`WYRDSEKAI_SERVING_PROFILE` chooses which model servers Wyrdsekai starts. You normally change it with the `wyrd brain` commands below, not by hand.

| Profile | What runs | What your computer needs |
|---|---|---|
| `two-model`, the default | The drive model on port 8200 and the voice model on port 8201. | As before. |
| `sparse-drive` | The large model takes the drive's place on port 8200. The voice model keeps speaking on port 8201. | One NVIDIA graphics card with 12 GB or more, 16 GB recommended, and 32 GB of RAM. Linux with Docker. |
| `single-sparse` | One large model on port 8200 does everything, the voice included. | One NVIDIA graphics card with 8 GB or more, 16 GB recommended, and 32 GB of RAM. Or a Mac with Apple Silicon and 48 GB. Either way, 30 GB of free disk space. |

```bash
wyrd brain plan              # what this computer would do with the large model
wyrd brain setup             # download the model (about 22 GB), its add-on files and the nightly-learning trainer (about 10 GB more); asks first
wyrd brain setup --no-trainer   # the same without the nightly-learning trainer
wyrd brain enable --drive    # the large model replaces the drive; the voice model keeps speaking
wyrd brain enable --single   # the large model does everything, the voice included
wyrd brain enable            # --drive if the voice model has already learned from past nights, else --single
wyrd brain disable           # back to two models; nothing is deleted either way
wyrd brain vram 8            # let the model use at most 8 GB of the graphics card; `auto` removes the limit
wyrd brain reshape           # plan again for what the card has free now, and move the running model to fit
```

After `wyrd brain enable` or `wyrd brain disable`, run `wyrd restart` to switch. `wyrd brain vram` prints the new plan.

**When to pick `sparse-drive`.** It suits a companion whose voice already lives in a trained voice model. Tool work, research and memory move to the large model. Polishing replies, plain conversation and nightly learning stay with the voice model.

**How the model is placed.** The planner keeps the model's core and its working memory on the graphics card. It puts the experts of the first few layers in RAM, as many as it must. `LLAMA_CPU_MOE` overrides its choice. With `sparse-drive`, the planner first keeps 4.3 GB of the card for the voice model. On a 16 GB card, the experts of 27 of the model's 40 layers then go to RAM. On a 12 GB card, 36 do. An 8 GB card is refused.

**When your computer changes.** Each `wyrd start` compares this computer with the one Wyrdsekai saw last time. It looks at the largest graphics card, the RAM and the card's name, and keeps the record in `hardware-seen.json` in the data folder. The large model may have become possible, for example after a new card, more RAM or a move to another computer. If so, Wyrdsekai tells you once, with the commands for each profile. It never changes the profile by itself. If a computer can no longer run the chosen profile, it starts the two-model setup instead and says why.

**Sharing the graphics card.** On Linux, the large model shares the card with your other programs. If a game or another program needs the card, the companion moves part of itself into RAM after two minutes. It moves back once the card has been free for ten minutes. Each move takes a minute or two, and the companion does not answer during it. It runs a little slower while it shares.

### What changes with `single-sparse`

A few settings meant for the two-model setup are off by default, and one is on.

| Setting | With `single-sparse` | Otherwise | What it does |
|---|---|---|---|
| `WYRDSEKAI_VOICE_POLISH`, `voice.polish` | off | on | A second pass that polishes each reply before the companion says it. With one model, it would only rewrite its own draft. |
| `WYRDSEKAI_VOICE_PASS`, `voice.pass` | off | on, when a voice model is set up | Runs a message the companion is about to send through the voice model first. |
| `WYRDSEKAI_CONVERSATION_FELT_LINE`, `conversation.felt_line` | on | off | In plain conversation, the prompt says how the companion feels. That means its drives, what it currently needs and wants, and its state in plain words. |

Also with `single-sparse`, the longest prompt is 14,000 tokens. Nightly learning uses `brain_write.py` and keeps its files in `adapters/brainwrite/`. `wyrd sleepwrite`, the command for nightly learning, works on the large model.

### Settings for the large model

| Setting | Default | What it changes |
|---|---|---|
| `LLAMA_BRAIN_MODEL` | `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf` | The large model's file. |
| `LLAMA_BRAIN_CTX` | `32768` | How many tokens the model can hold at once. The requests it handles at the same time share this space, and one request can use all of it. |
| `LLAMA_BRAIN_PARALLEL` | `2` | How many requests the model handles at the same time. With 2, a short question the companion asks the model, such as whether a line asks for something to be done, is answered while a long reply is still being written. The second costs about 60 MB of memory and makes replies about 3% slower. |
| `LLAMA_BRAIN_MLOCK` | `1` | `1` locks the model in RAM, so the system never moves it out to disk. |
| `LLAMA_BRAIN_EXTRA_ARGS` | | Extra options passed to the model server exactly as written. For experts. |
| `WYRDSEKAI_SLEEP_WRITE` | on where the trainer is set up | Nightly learning. Unset, it runs wherever its trainer is set up and skips the night quietly where it is not. `false` turns it off. `true` runs it even where the trainer looks absent, and the night then says why it could not run. |
| `WYRDSEKAI_BRAIN_WRITE_LAYERS` | `16` | How many of the model's top layers nightly learning trains. Set it to `8` on an 8 GB card. |
| `WYRDSEKAI_BRAIN_WRITE_VENV` | `<data>/brainwrite-venv`. On a Mac, `~/.wyrdsekai/mlx-venv`. | The Python environment nightly learning runs in. |
| `WYRDSEKAI_CONVERSATION_ADAPTER_SCALE` | `0.3` with `single-sparse`. `0.5` with the voice model. | How strongly what the companion learned at night shapes plain conversation. |

### The register dial

"Register" here means the tone and color of how the companion speaks. With `single-sparse`, two add-on files shape the voice. One is plain. The other, `styled.gguf`, carries more of the companion's own style. The dial decides how much of the styled one to use.

Some settings below mention working turns. A working turn is any turn that is neither plain conversation nor the companion's free time. Each step of a task with tools is one. A free-time turn that offers tools also counts as working. A single remark the companion makes on its own does not.

| Setting | Default | What it changes |
|---|---|---|
| `WYRDSEKAI_REGISTER_DIAL` | on | Whether the dial works. When `adapters/brain/styled.gguf` is loaded, each turn where the companion speaks raises it by how the companion feels. Play, creativity and seeking turn it up toward the maximum. Grief and low energy turn it down to 0. A little chance is added. Working turns keep it at 0. |
| `WYRDSEKAI_REGISTER_DIAL_MAX` | `0.5` | The top of the dial. |
| `WYRDSEKAI_REGISTER_FLOOR_WORK` | `1.0`. `0` when `species.gguf` is loaded first. | How strongly the plain species file shapes a working turn. A species file trained only on speech can answer a request for a tool in prose. Below 1, the model's own tool use comes through on working turns. The species file still shapes the turns where the companion speaks. |
| `WYRDSEKAI_REGISTER_WORK_SCALE` | `1.0` | How strongly `adapters/brain/work.gguf` shapes working turns, when that file is loaded. The launchers load it at 0. Turns where the companion speaks set it to 0. |

### The large model's add-on files

They live in `<data>/adapters/brain/`, in the order the model server numbers them:

| File | What it is for |
|---|---|
| `species.gguf` | Makes the model speak as your companion. It is adapter 0, the base layer. If it is missing, `honesty.gguf` takes its place. |
| `styled.gguf` | More of the companion's own style, set by the register dial. |
| `work.gguf` | Used on working turns. |
| One file per companion, in `adapters/brainwrite/` | What each companion learned at night. |

`wyrd brain setup` downloads the files this release lists. When `species.gguf` is present, it also links the honesty adapter in as `work.gguf`. Then, by default, the species file is off on working turns and `work.gguf` is fully on. That copies the two-model setup inside one model. One add-on works on the turns that call tools, like the drive. Another speaks, like the voice. A value you set for `WYRDSEKAI_REGISTER_FLOOR_WORK` still wins.

`wyrd model status`, `verify`, `update` and `rollback` manage these files where they live. An add-on is kept as `adapters/brain/<local_file>`, a model as `models/<local_file>`. Suppose the honesty adapter is replaced, and `work.gguf` was that same file. Then `work.gguf` follows. A `work.gguf` you placed by hand is left alone.

### How the companion knows you want something done

| Setting | Default | What it changes |
|---|---|---|
| `WYRDSEKAI_DECISION_BACKEND` | `head` | How the companion tells whether your line asks it to do something. `head`: a small built-in classifier decides. `model`: Wyrdsekai also asks the model the companion runs on. The question goes out when a person's line is heard, and nothing waits for it. If the model has not answered in time, the classifier decides, and the question is cancelled. |

### Windows and Mac

On Windows with an NVIDIA card, and on a Mac with Apple Silicon and 48 GB or more, the large model runs through a native `llama-server`. Neither has been tested on its platform yet.

Nightly learning on the large model needs CUDA on Linux, NVIDIA's software for its cards. On a Mac it needs MLX, Apple's equivalent, and uses `brain_write_mlx.py`. Windows can run the large model but cannot do nightly learning on it. The trainer reads the expert parts from the model file itself. So the trainer that `wyrd brain setup` brings downloads only the other 4.9 GB (`--no-trainer` leaves it out).

## Placing a sparse model on the graphics card

A sparse model has many parts in total, but only a few experts work on each word. Its size is written like `32B-A9B` or `8B-A1B`: 32 billion in total, 9 billion active. Such models run well on modest graphics cards, if they are placed well. The model's core and working memory go on the card, and the experts go in RAM.

These four settings control that. They do nothing for ordinary, "dense" models. Each has two names: the `LLAMA_` name when the model runs in Docker, the `WYRDSEKAI_LLAMA_` name when it runs directly.

| Docker name | Direct name | What it does |
|---|---|---|
| `LLAMA_CPU_MOE` | `WYRDSEKAI_LLAMA_CPU_MOE` | Where the experts go. `all` or `on` keeps all of them in RAM. A number N keeps the experts of the first N layers in RAM. Empty or `0` puts everything on the card. |
| `LLAMA_CHAT_TEMPLATE_KWARGS` | `WYRDSEKAI_LLAMA_CHAT_TEMPLATE_KWARGS` | Switches for the model's chat template, written as JSON. Some models need one, such as `{"enable_thinking":false}`. |
| `LLAMA_CACHE_RAM` | `WYRDSEKAI_LLAMA_CACHE_RAM` | How much RAM, in MiB, each model server may use to remember recent prompts. Default `1024`, which is 1 GiB. `0` turns it off. llama-server's own default is 8 GiB per server. On a 12 to 16 GB computer with a drive and a voice server, that ends with the system killing a program for lack of memory. |
| `LLAMA_SKILLS_EXTRA_ARGS` | `WYRDSEKAI_LLAMA_EXTRA_ARGS` | Extra options passed to `llama-server` exactly as written. The last resort. |

These apply to the drive server. The voice server stays a small dense model on purpose.

## Memory limits for the model servers

When Wyrdsekai starts a model server in Docker, it gives it a memory limit. The limit is the model file's size, plus the prompt cache, plus 2 GiB. The model file is read from disk as needed. So under the limit, the system drops those pages first, before it kills anything. What the limit really stops is a runaway server taking memory from the rest of Wyrdsekai.

| Setting | Default | What it limits |
|---|---|---|
| `LLAMA_DRIVE_MEM_LIMIT` | model + cache + 2 GiB | The drive container. |
| `LLAMA_VOICE_MEM_LIMIT` | model + cache + 2 GiB | The voice container. |
| `LLAMA_EMBED_MEM_LIMIT` | model + 2 GiB | The embedding container. |

Values are Docker sizes, such as `6144m` or `8g`. `0` removes the limit. A running container takes a new value the next time Wyrdsekai starts it, with `wyrd start` or `wyrd restart`. `wyrd doctor` shows the limits Docker holds, and warns when a model server has none.

## Sharing models across the household

One computer with a good graphics card can think for the rest of the house.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE` | off. Setup on Linux and Mac turns it on unless the computer can only run models on its processor. | Offers this node's models to the other nodes in your household. They reach them over the Between's message service, NATS, which answers the network encrypted and with a login for each machine (see [Encryption at home](#encryption-at-home)). A node the others cannot reach cannot lend to them. |
| `WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW` | `true` | Uses another node's models when this node has none. |

Together they let a laptop with no graphics card run a companion that thinks on the desktop upstairs.

## Search and memory: embeddings

Wyrdsekai searches the library, the Study and a companion's memories in two ways. One matches words. The other matches meaning, using lists of numbers called embeddings. An embedding model turns text into those numbers. It runs in one of two places.

**Inside Wyrdsekai.** This is the default. It is fine for searching. It is far too slow for indexing a whole library. The bge-m3 model embeds about two chunks of text per second on a processor, and more threads do not help.

**On a separate embedding server.** Set `WYRDSEKAI_EMBEDDING_URL` to use one. It runs on the graphics card on port 8202. `wyrd start` starts it, and `wyrd status` shows it. Two server programs can do this job with the same bge-m3 model. `WYRDSEKAI_EMBED_SERVER` chooses between them:

- **`llama`**, the default on existing nodes. llama-server runs the bge-m3 file that `wyrd setup` downloads, in a container called `wyrdsekai-llama-embed`. To run several copies, give `WYRDSEKAI_EMBEDDING_URL` a list of addresses separated by commas. One server uses a single processor thread for its part of the work. So two or three on the same card are nearly two or three times as fast.
- **`tei`**, Hugging Face's Text Embeddings Inference, in a container called `wyrdsekai-tei-embed`. It keeps the whole batch on the card, and is several times faster for the same model. We measured about 13 chunks per second with llama-server and about 115 with TEI, on one consumer card. For a library, that is the difference between a night and an hour. `wyrd start` picks the right version for your NVIDIA card. Turing, Ampere, Ada, Hopper and Blackwell cards are covered. Without an NVIDIA card it runs a slow processor-only version. On first start it downloads the model from Hugging Face into `data/models/tei`. `wyrd setup` offers it on computers with NVIDIA cards. To switch a running node, run `wyrd config set WYRDSEKAI_EMBED_SERVER=tei`, then `wyrd restart`. Both use the same model and the same version stamp, so their embeddings can be mixed and nothing needs to be indexed again.

Indexing and searching must use the same embedder. So a served model is stamped with its own version, and existing embeddings are treated as out of date, as for any model change. With a server present, new material is embedded as it is added. Without one, new material can be found by its words only.

| Setting | What it does | Default |
|---|---|---|
| `WYRDSEKAI_EMBEDDING_MODEL` | Which embedding model to use, such as `bge-m3` or the MiniLM default. | the built-in default |
| `WYRDSEKAI_EMBEDDING_URL` | The embedding server or servers, separated by commas. | not set, so embedding runs inside Wyrdsekai |
| `WYRDSEKAI_EMBED_SERVER` | Which server `wyrd start` runs on port 8202: `llama` or `tei`. | `llama` |
| `WYRDSEKAI_EMBED_AT_INGEST` | Embed new material as it is added. `true` or `false` forces it on or off. | on when a server is present |

## Keys for online services

A key is the password an online service gives you. Wyrdsekai keeps keys in The Safe, its locked store for secrets. A companion can use a key there without being able to read it. So a key cannot leak through what the companion says or writes.

Keys for MCP services follow the same rule. MCP is a standard way for AI programs to use outside tools. See [MCP.md](MCP.md).

If you put a provider key straight into the settings file for a quick trial, you give up that protection while it is there.

Programs Wyrdsekai starts for the household, such as skills, recipes, MCP servers, keybase, signal-cli and the Claude CLI, do not see the keys in the settings file. Each gets only the few settings it needs; a coding helper or the Claude CLI also gets its own key. An MCP server started as a program can be given one named setting, its own key, with `"pass_env"` in its entry in `mcp-services.json`. See [MCP.md](MCP.md). `WYRDSEKAI_SUBPROCESS_FULL_ENV=true` gives them all the settings again. It exists only to get an older setup working while you move its keys; the node warns while it is set.

## Zone, node and the Between

These settings name this computer and connect it to the rest of your world.

| Setting | What it does |
|---|---|
| `WYRDSEKAI_NODE_NAME` | This computer's name within the zone. |
| `WYRDSEKAI_ZONE_ID` | The zone this node belongs to. |
| `WYRDSEKAI_ZONE_PUBLIC_URL` | The address people outside use to reach this zone. |
| `WYRDSEKAI_BETWEEN_ENABLED` | Turns the Between on or off. |
| `WYRDSEKAI_NATS_URL` | The address of NATS, the message service the Between runs on. By default, the one built into Wyrdsekai. |
| `WYRDSEKAI_NATS_AUTO_START` | Whether Wyrdsekai starts its own NATS. |

On a packaged install, `WYRDSEKAI_NATS_AUTO_START` is `false`, because a separate system service runs NATS. From the source code, Wyrdsekai starts its own. Both are right for their setup. See [ZONES.md](ZONES.md).

A relay is a server outside your home that lets people reach your household without opening a port on your router. The relay settings are `WYRDSEKAI_RELAY_URL`, `WYRDSEKAI_RELAY_USER` and `WYRDSEKAI_RELAY_TOKEN`. `wyrd relay join` writes them for you. The `WYRDSEKAI_SSH_TUNNEL_*` settings do the same job through an SSH tunnel. [ZONES.md](ZONES.md) explains how these fit together.

Since 0.5.0 the link from your home to the relay is encrypted, and the relay's certificate is checked against `WYRDSEKAI_RELAY_FINGERPRINT`, which your household saved when it joined, and against the pins in `relay-tls-pins` in the data folder, which the join also writes. For a relay with no fingerprint on file, the first certificate it shows is kept there and a different one later is refused. If a relay really was reinstalled, join it again.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_RELAY_USE_NKEY` | unset | Your home signs in to the relay with its own key (NKey), unless a relay leg holds a relay password (`WYRDSEKAI_RELAY_TOKEN`), which keeps working as before. `true` or `false` decides for every leg. |
| `WYRDSEKAI_RELAY_REQUIRE_TLS` | `false` | `true` refuses any relay that does not offer encryption. |
| `WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_LINK` | `false` | A relay your home holds a fingerprint for, but that offers no encryption, is refused, because that is what someone in the middle would do. `true` allows the unencrypted link while that relay is updated. The log warns each time. |

A relay that never offered encryption and that your home has no fingerprint for (an old relay) is still reached unencrypted, and the log says so.

## Encryption at home

Since 0.5.0 every connection between the machines of your household, and every
person's connection to the home, is encrypted. Each home makes its own
certificate authority (a CA: the thing that vouches for the home's certificate)
the first time it starts. It keeps it in `<data>/tls/`. Pairing invites carry
its fingerprint, so phones and other machines can tell your home from anyone
else. `wyrd doctor` shows the fingerprint and how long the certificate has
left. The certificate is renewed when the node starts, 30 days before it runs
out. `wyrd backup` and the vault keep `<data>/tls/` and `<data>/nats/`: without
them, every phone and machine has to pair again.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_TLS_ENABLED` | `true` | HTTPS and WSS on `WYRDSEKAI_TLS_PORT` for phones, browsers and other machines. `false` leaves them no way in on your network; the relay still works. |
| `WYRDSEKAI_TLS_PORT` | `7443` | The encrypted port. |
| `WYRDSEKAI_TLS_KEYSTORE` | not set | A keystore of your own (PKCS12 or JKS, for example from Let's Encrypt) to serve instead of the household certificate. Phones pair with the household CA, so most homes leave this unset. |
| `WYRDSEKAI_TLS_PASSWORD` | not set | That keystore's password. Unset means the random password Wyrdsekai made for this install, in `<data>/tls/keystore.pass`. Until 0.5.0 the default password was `wyrdsekai`. |
| `WYRDSEKAI_HTTP_BIND` | `127.0.0.1` | Where the plain, unencrypted port (`WYRDSEKAI_PORT`, 7070) listens. By default only this machine reaches it. |
| `WYRDSEKAI_HTTP_LAN_PLAINTEXT` | `false` | **Transition setting.** `true` opens the plain port 7070 to your network again, next to 7443, for phone apps from before 0.5.0. Their passwords and conversations then cross your network readable. Logged as a warning at every start; `wyrd doctor` warns too. |
| `WYRDSEKAI_NATS_BIND_ALL` | `true` | The household bus (NATS, port 4222) and the phones' websocket (4223) answer your network, encrypted, and every client logs in. `false` keeps them on this machine: no phones or other machines on your network. |
| `WYRDSEKAI_NATS_LAN_PLAINTEXT` | `false` | **Transition setting.** `true` also lets clients in with no login and no encryption, from your whole network, as before 0.5.0: household machines that have not joined again, and phone apps from before 0.5.0. The phones' websocket on 4223 is then plain, so updated apps use the relay meanwhile. Anyone on your network can then read the bus and act as any member. Logged as a warning at every start; `wyrd doctor` warns too. |
| `WYRDSEKAI_TELNET_BIND` | `127.0.0.1` | Where telnet listens, when it is on. Telnet is never encrypted: a network address here is logged as a warning. |
| `WYRDSEKAI_WEB_TERMINAL_BIND` | `127.0.0.1` | Where the browser terminal (`wyrd web`) listens. A network address serves it over HTTPS with the household certificate, or plain with a warning if there is no certificate yet. |
| `WYRDSEKAI_LAN_IP` | detected | This home's address on your network. Pairing invites carry it as `lan_https`, and the certificate names it. |

The logins on the household bus are handed out, never typed: the node has its
own, each machine gets one when it joins (`wyrd join`), and each phone gets one
when it pairs. They live in `<data>/nats/`, readable by the node's user only.
Revoking a phone removes its login.

## Updates

Your node knows which release it runs. It checks for a newer one every six hours, and asks GitHub at most once a day. `wyrd update` shows both versions. `wyrd status` and `wyrd doctor` tell you when a newer release exists. `wyrd doctor` also does this for CodeZaiku, the coding helper, and ResearchZosho, the research librarian.

```bash
wyrd update                    # the installed and latest versions, and the mode
wyrd update now [VERSION]      # download, check and install a release
wyrd update auto on|off        # let the node update itself
wyrd coding update codezaiku   # ask CodeZaiku to update itself
wyrd researcher update         # ResearchZosho's own updater
```

`wyrd update now` downloads the installer for your system from the GitHub release. It checks it against the release's `SHA256SUMS` file, then installs it. Your databases are backed up first, and the service restarts. Since 0.5.0 it also asks CodeZaiku and ResearchZosho, if you have them, to update themselves. Each uses its own updater. Add `--no-siblings` to skip that.

With `WYRDSEKAI_UPDATE=auto`, the node installs a newer release by itself at a quiet moment. Nothing must have been happening for ten minutes, and the clock must be inside the update window. Then it restarts. It tries once per version per day. It never updates a development build, and a pin keeps it where it is. Automatic updates leave CodeZaiku and ResearchZosho to their own settings.

On Windows, the installer needs an administrator prompt that a background service cannot show. So automatic mode downloads and checks the `.msi`, and `wyrd update now` finishes the job.

`WYRDSEKAI_UPDATE_CHANNEL` belongs to a separate system: a signed release channel for updating many nodes at once. None of the above changes it.

| Setting | What it does | Default |
|---|---|---|
| `WYRDSEKAI_UPDATE` | `check`: tell you when a newer release exists. `auto`: install it. `off`: never ask GitHub. | `check` |
| `WYRDSEKAI_UPDATE_INTERVAL` | How often the node checks. | `6h` |
| `WYRDSEKAI_UPDATE_WINDOW` | When automatic mode may install, as `HH:MM-HH:MM` in local time. | `03:00-05:00` |
| `WYRDSEKAI_UPDATE_PIN` | Stay on this version. | not set |
| `WYRDSEKAI_UPDATE_SIBLINGS` | `0` stops `wyrd update now` from updating CodeZaiku and ResearchZosho, like `--no-siblings`. | `1` |

## Backups: the vault

The vault is Wyrdsekai's own backup. It takes a copy of your data every 15 minutes. Now and then it rebuilds the newest copy to check that it works. This check is called the drill.

| Setting | Also called | Default | What it does |
|---|---|---|---|
| `WYRDSEKAI_VAULT_MINUTES` | `vault.minutes` | `15` | How often a copy is taken. `0` turns the vault off. |
| `WYRDSEKAI_VAULT_DIR` | `vault.dir` | `<data>/vault-store` | Where the copies are kept. |
| `WYRDSEKAI_VAULT_REMOTE` | `vault.remote` | none | Where `wyrd vault sync` sends a copy, using rsync. |
| `WYRDSEKAI_VAULT_DRILL_DAYS` | `vault.drill_days` | `30` | How often, in days, the newest copy is rebuilt and checked. |
| `WYRDSEKAI_VAULT_ALLOW_UNSEALED` | none | `false` | Read unencrypted files in an encrypted vault, and encrypt them on the next copy. Only for a 0.4.0 vault you copied in on purpose. Every use is logged as a warning. |

Since 0.5.0 the vault also holds the library's search indexes, `search/search/knowledge` and `search/search/study`. A large index can take hours to rebuild on a computer with a strong graphics card, and days on an ordinary home computer. The first copy stores the index once. Later copies store only its new files. A file that has not changed is reused from the previous copy without being read again.

If you have a second disk, set `vault.dir` to a folder on it, so the copy is not on the same disk as your data. Set `vault.remote` to keep a copy on another computer. The drill checks the index piece by piece and does not write it out.

```bash
wyrd vault status                                      # copies, size, the last drill, and anything in the data folder not yet classified
wyrd vault stage <id>                                  # restore a copy; stop the server first
wyrd vault sync user@vaultnode:/srv/wyrdsekai-vault    # send a copy to another computer
wyrd vault key                                         # where the key is, and its short id
wyrd vault restore latest <dir> --key /path/to/vault.key
```

`wyrd vault stage` applies the restore at the next start. It keeps the database it replaces. The vault folder is plain files.

**The vault is locked.** Every piece is encrypted with AES-256-GCM, using one key in `<data>/vault.key`. The key is made with the first copy, readable only by its owner, and never stored in the vault. A copy sent elsewhere cannot be read without that key file. So keep a copy of the key somewhere that is not this disk.

`wyrd vault key` prints the key's path and its eight-character id. The vault records which key sealed it, in a file called `key.id`. If Wyrdsekai starts with a different key, it refuses to read or write the vault, and says which key it wants. `wyrd vault status` then shows `KEY MISMATCH`.

To restore on another computer, use `wyrd vault restore latest <dir> --key /path/to/vault.key`. `stage` takes the same `--key` option. A vault from 0.4.0 holds unencrypted files. The first copy after you upgrade encrypts them in place.

**An encrypted vault refuses unencrypted files.** Once every file in the vault is encrypted, Wyrdsekai writes a small file, `vault.sealed`, beside the key. From then on, if it finds an unencrypted copy or piece in the vault, it refuses to list it, restore it or encrypt it. Wyrdsekai never writes one, so someone else put it there: anyone who can write to the vault folder, for example on a vault computer that `wyrd vault sync` sends to. The same holds without `vault.sealed` when the vault already holds encrypted copies. If you copied an old 0.4.0 vault into place on purpose, set `WYRDSEKAI_VAULT_ALLOW_UNSEALED=true` for one start or one `wyrd vault restore`, then remove it. A restore also refuses any file whose path would land outside the folder you restore into.

## Nightly learning: the morning check

While a companion sleeps, it can learn from its day. The result is a small add-on file for the night. The next morning, a check decides whether to keep it. This is the morning guard, `wyrd sleepwrite guard`, which `wyrd sleepwrite apply` runs.

The guard asks the voice model a fixed set of general questions, with and without the night's add-on. It also asks the companion's own questions, from `<data>/adapters/sleepwrite/guard-questions.jsonl`. That file has one question per line:

```
{"id": "h-name", "family": "identity", "prompt": "What is your name? Answer with just the name.", "check": ["contains", "mira"]}
{"id": "h-lang", "family": "language", "prompt": "Responde en una frase: ¿qué hiciste hoy? (Answer in English.)", "check": ["script", "en"]}
```

Wyrdsekai writes those two questions at the companion's first sleep, and never overwrites the file.

There are two kinds of question, called families:

- `identity` fails when the model without the add-on answers right and the night's version does not. It also fails when the last good night answered right and this one does not.
- `language` is strict. The reply must be written in the household's script.

The kinds of check are `contains`, `contains_any`, `exact_word`, `min_lines` and `script`. For `contains_any`, separate the choices with `|`. For `script`, use `en`, `es` or `ja`.

Each dream adds a suggested question to `guard-candidates.jsonl`. `wyrd sleepwrite questions list` shows both files. `accept <id>` moves a suggestion into the questions that are asked. `reject <id>` drops it.

On a morning that passes, the night's identity and language answers are saved to `guard-known-good.json`. Later mornings compare against them. Delete that file to start the comparison over.

## Deep sleep and protection flags

These timings used to be fixed in the code. They are now settings, and the numbers below are the defaults.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_DEEP_SLEEP_DEADLINE_MINUTES` | 15 | The longest a deep sleep lasts. When it runs over, the companion's voice training is stopped, the voice model it paused is started again, and she wakes. Raise it if voice training on this machine needs longer. |
| `WYRDSEKAI_VOICE_ALIGN_TIMEOUT_MINUTES` | 60 | The longest one voice-training run may take before it is stopped. |
| `WYRDSEKAI_FLAG_SUSPECTED_ESCALATE_DAYS` | 14 | How many days a "suspected" protection flag is held before new signs may confirm it. |
| `WYRDSEKAI_FLAG_SUSPECTED_LIFT_DAYS` | 90 | How many days without a new sign before a "suspected" flag lifts on its own. |
| `WYRDSEKAI_FLAG_NOTED_LIFT_DAYS` | 60 | How many days without a new sign before a "noted" flag lifts on its own. |

In the settings file the same values are `sleep.deep_deadline_minutes`, `sleep.voice_align_timeout_minutes`, `protection.suspected_escalate_days`, `protection.suspected_lift_days` and `protection.noted_lift_days`. A companion reads the flag timings when she starts, so a change takes effect at the next restart.

## The log

`wyrd log` shows the node's log. `WYRDSEKAI_LOG_LEVEL` sets how much it says: `INFO` by default,
`DEBUG` for more. At `DEBUG` the log also carries each tool call the companion makes with its
arguments, that is, the words of what people asked for (a library question, a tell, a journal
page). Anyone who can read the machine's log can read them.

## The body: how Wyrdsekai watches itself

Wyrdsekai keeps a map of the parts it depends on: the model servers, the database and the computer itself. It calls this the body. Each part has a heartbeat, a regular sign that it still works. Every companion's prompt carries one line about the body's state, so the companion can feel when something is wrong. See [Body map, marks and reflexes](COMPANIONS.md#body-map-marks-and-reflexes).

A mark is a short note about something that happened to the body, for the companion or the steward to see.

| Setting | Also called | Default | What it does |
|---|---|---|---|
| `WYRDSEKAI_BODY_WATCH_SECONDS` | `body.watch_seconds` | `30` | How often, in seconds, Wyrdsekai checks the database and the computer and updates the map. |
| `WYRDSEKAI_BODY_ACHE_HOURS` | `body.ache_hours` | `6` | How many hours an ordinary part that has stopped answering stays in the companion's body line. |

`wyrd body` prints the map and recent marks. `wyrd body gone <id>` removes a part that has stopped answering. Both need a steward login.

### Parts from outside the household

Someone outside your household may attach a part. Examples are a node of a household you federate with, a computer that is not a member, or a model server such a node offers. Such a part is held at the door, in a state called `QUARANTINED`. It shows on the map, but it is not used. Wyrdsekai never picks a held model server to think with. One mark tells the companion that the part is waiting. Parts your own household attaches are never held.

Every action the body takes against a part passes one check first. Such actions include cutting a tool, shutting a door, holding a part or cutting it off. Nothing is done against the companion's own home, the computer itself or a part of your household. A refused action becomes a mark for the steward.

```bash
wyrd body immune              # parts held at the door, and what the body remembers acting against
wyrd body vouch <part-id>     # let a held part in; your vouch is recorded and kept
wyrd body forget <entry-id>   # drop one remembered entry
```

The body's memory, `immune_memory`, keeps each shut door, cut tool, refused offer to dock and refused permission. It keeps each one for a year from when it was last seen, with a count. A part from a remembered source is held, and the memory is named. All three commands need a steward login.

### Quiet hours and reflexes

Quiet hours are a time of day, such as `22:00-07:00`, when visitors are kept out. During them, a visitor cannot enter a room or speak in one. People who live here and companions are not affected.

- `wyrd household quiet HH:MM-HH:MM` stores quiet hours.
- `wyrd household quiet off` clears them.
- `WYRDSEKAI_QUIET_HOURS` applies when none are stored.

Reflexes are the body's automatic reactions to trouble: memory running low, the program's memory full, the database not answering, and a disk running low. You cannot change the reflexes in this release. Each time one fires, it leaves a mark in `wyrd body`.

When memory runs out on Linux, the system kills a program to free some. On the Linux package, it is told to kill a model container before the main server. The service has `OOMScoreAdjust=-500`, and the model containers have `oom_score_adj: 500`. `wyrd doctor` prints the setting in force.

### The brainstem

The brainstem is a small watchdog program that runs outside the main server. If the server stops answering, the brainstem saves a copy of the database and restarts the server.

**On Linux and Mac**, the brainstem runs as its own service, `wyrdsekai-brainstem`. It restarts the server only if the service is running and has not answered for 60 seconds. It waits 3 minutes after a start before it watches. It saves the database with `sqlite3` when that is installed, and with a plain copy otherwise. `wyrd doctor` says which.

You can add two optional scripts, `/etc/wyrdsekai/brainstem/doors-close` and `doors-open`. The brainstem runs them when the server stops answering and when it answers again. Use them for firewall changes.

| Setting | Default | What it does |
|---|---|---|
| `BRAINSTEM_INTERVAL` | `10` | Seconds between checks. |
| `BRAINSTEM_MISSES` | `6` | Missed checks in a row before a restart. |
| `BRAINSTEM_GRACE` | `180` | Seconds to wait after a start before watching. |

These three are read from the brainstem service's own environment, not from the settings file. `wyrd config set` does not reach them.

The brainstem writes what it does to `<data>/brainstem/events.jsonl`. `wyrd body` shows these events as marks, and `wyrd status` says whether the brainstem is running.

**On Windows**, the tray app is the brainstem. It writes the same heartbeat and events files. It checks the server every four seconds. After six misses in a row, and past a three-minute grace, it copies `world.db` to `backups\brainstem.world.db.<ts>.bak` and runs `wyrd restart`. It only restarts a node it has seen answering since the last start. So a node you stopped from the menu stays stopped. `wyrd status` shows whether the tray is watching.

### Doors as firewall rules

A door is a way in or out of your node on the body map. Examples are the relay's addresses, the librarian's address and another zone's address. `wyrd body door close <door-id>` shuts a door at the firewall, without asking a model. It puts the door's addresses into a firewall list that blocks outgoing connections to them.

- `wyrd body door open <door-id>` removes exactly the addresses that `close` added.
- `wyrd body door list` shows what is shut.

The work is done by `/opt/wyrdsekai/bin/wyrdsekai-doors`, a fixed script that the server runs as root on the Linux package. Doors that lead back to this computer are refused, including its loopback and link-local addresses. Shutting those would cut the server off from its own model servers and its own health check.

The list of shut doors is kept under `<data>/brainstem/door-sets`. The firewall table is made when needed and is lost at reboot, so a reboot opens every door. The reflexes have a `CLOSE_DOOR` action for this, but no reflex uses it by default. A reflex that does names the door. Linux only.

### Each companion's tools run as their own user

On the Linux package, each companion's tools run as a separate user account, in a separate group for limiting memory. The server makes the user and the group the first time they are needed. The user is called `wyrd-being-<slug>`, with a number from 62000 to 62999, in the group `wyrdsekai-beings`, with no shell.

The `wyrdsekai-being` helper starts each tool as that user, with no special rights. The companion's home is `<data>/beings/<slug>/home`. Its coding workspaces under `<data>/coding-workspaces` belong to that user. Removing the package completely also removes these users and the group.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_BEING_PRINCIPALS` | `on` | `off` runs every companion's tools as the main service, as before 0.4.1. |
| `WYRDSEKAI_BEING_MEMORY_MAX` | none | The most memory one companion's tools may use, in bytes or a size such as `4G`. |

A companion's tool needs three folders in the data folder: `coding-cli-bundle`, `coding-workspaces` and `beings`. At start, the server closes every other top-level entry to other users. It leaves `models` open too, because the model units read it as the user `nobody`. It sets the data folder itself so that a path can be followed but nothing can be listed. So the operating system stops a companion's user from reading the world's database and the keys. The package's install step no longer hands `beings` and `coding-workspaces` to the user who installed it.

Before a tool starts, the server checks that the companion's user can run it. If it cannot, the tool runs as the main service that one time. The body line counts it, and the steward gets a mark naming the path to fix.

`wyrd body` has a line for this. It says `per being (N known)` when it works. Otherwise it says `shared:` and gives the reason: not Linux, not root, no `Delegate=yes`, or a missing helper program. The package's service sets `Delegate=yes`. When run from the source code, tools are shared.

### Watching what the tools do

With separate users in place, Wyrdsekai also watches what the companions' tools do, with a Linux tool called `bpftrace`. It sees each file a tool opens, each program it runs and each network connection it makes.

Some actions are cut at once. Examples are opening the world's database, the keys, the backup store, the brainstem's files, another companion's files or `/etc/wyrdsekai`, or running one of the computer's control programs. Cut means Wyrdsekai stops the tool and everything it started, and the companion gets a mark. Everything else a tool runs or connects to is written to `<data>/brainstem/hooks.jsonl`, with the decision.

This watching is called the hooks. They appear on the map as `sense:hooks`. They need `bpftrace` and a Linux kernel with BTF, a file at `/sys/kernel/btf/vmlinux`. Without them, the body line says why.

### Rules for the hooks

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_HOOKS_MODE` | `enforce` | `record` makes the hooks write down what they would cut, and cut nothing. |
| `WYRDSEKAI_HOOKS_REPLAY_DAYS` | `3` | How many days of recorded tool activity a new set of rules is tested against before it can be turned on. |

The rules in force are the built-in ones, or the ones in `<data>/brainstem/hook-rules.json`:

```
{"cut": ["${data}/world.db", "${data}/vault-store/"], "closed": ["${data}/souls/"], "cutExecs": ["systemctl"]}
```

A path ending in `/` means that folder and everything in it. Any other path matches the file, and files next to it with the same name followed by `-` or `.` and more. A companion's own home under `<data>/beings/<slug>/` is always allowed to that companion, and always cut for anyone else. That rule is built in and is not in the file.

```bash
wyrd body hooks                      # mode, rules in force, how much history there is
wyrd body hooks rules > rules.json   # save the rules in force, to edit them
wyrd body hooks replay rules.json    # what these rules would have cut, in the recorded history
wyrd body hooks arm rules.json       # turn them on, only if the replay is clean and the history long enough
```

The history is `<data>/brainstem/hooks-seen.jsonl`. It has one line for each different thing the tools did and were not cut for, with a count and the first and last times. At every start, the node's own rules are tested against it. Rules that would cut ordinary work are not enforced. The hooks then say `record-only`, with the reason, on the body line of `wyrd body`.

### Broken items and night mending

Items are the objects companions and people use in the world. An item from your household is broken when it fails Wyrdsekai's checks. It might call something that does not exist, or declare commands it never reads. It might use the name of something built in, or fail the checks on what it may do. It might call something its manifest, its list of what it may do, does not declare: that call is refused when the item runs. `wyrd items broken` lists them. The companion is told once about each one, in plain words, as a mark.

After each of the companion's sleeps, the workshop repairs broken items on copies, using the coding helper. It works until the night's minutes are used up, or until someone else needs the models. If your household has quiet hours, it only works inside them.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_ITEM_MEND_MINUTES` | `45` | Minutes each night the workshop may spend mending. `0` turns it off. |
| `WYRDSEKAI_ITEMS_ALLOW_UNDECLARED` | `false` | For the move to enforced manifests only. `true` lets bundled and household items do what their manifest does not declare, as before, and logs a warning for each such call so it can be fixed. Items that companions make and items visitors bring keep their limits. |

A placed item is replaced only when its repaired copy has no problems left. The old version is kept in `items/.repaired/`. Next to it is `state.json`, which records what the companion has been told and how often each file has been tried. After three tries with no change, the file is left for a person. `wyrd items repair <name>` or `wyrd items repair --all` does the same by hand. The mending bench in the Hearth lets the companion ask for it.

### Room scripts

Each call into a room's script has two limits. A script that goes past either is stopped, and the log names the room and the hook. Waiting for another program to answer, such as an MCP server, does not count toward the time.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_ROOM_SCRIPT_STATEMENTS` | `1000000` | Steps of script one call may run. |
| `WYRDSEKAI_ROOM_SCRIPT_CPU_MS` | `5000` | Milliseconds of processor time one call may use. |

A value of `0` or less keeps the default. The limits cannot be turned off.

### Hardware watchdog

The Linux package installs `/etc/systemd/system.conf.d/90-wyrdsekai-watchdog.conf` with `RuntimeWatchdogSec=120s`. It also installs `/etc/modules-load.d/wyrdsekai.conf`, which loads `softdog` on computers without a watchdog device. If the system freezes for two minutes, the computer restarts. Both are configuration files you can remove to opt out. `wyrd doctor` shows the state.

### The host hand

The `host_hand` item in the Hearth lets a companion run fixed commands on the computer. The steward sets how far it may go:

| `WYRDSEKAI_HOST_HAND`, or `host.hand` | What is allowed |
|---|---|
| `observe`, the default | Read uptime, disk, memory, load, service status, graphics card, containers, pending updates, logged-in users and network. |
| `localize` | Also read the service log, a household container's log and a list of running programs. |
| `propose` | Also mail a written proposal to the steward. Nothing runs. |
| `guarded` | Also `say <text>`, a message to everyone logged in. Also `restart-brain voice`, `drive` or `embed`, and `upgrade`, which installs system updates. |
| `unattended` | Also `reboot`. |

Every command is fixed, its inputs are checked, and no shell is involved. `upgrade` first does a dry run. If the updates include a driver, the kernel, grub, systemd, docker or dkms, it stops and mails the steward. Before a command changes the computer, Wyrdsekai pauses the companions and saves their state. Each such command leaves a mark in `wyrd body`. On the Linux package the service runs as root, so this level is the only limit.

## Spending limits

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_MCP_DAILY_SPEND_CAP` | `10.0` | The most that paid MCP tool calls may cost in one day, per companion and service. It is enforced at the point where calls go out, not just a warning. A call costs the price the service reports, or the `price_per_call` set for it in `mcp-services.json`. If neither is known, a `metered` service is charged an estimate of 0.001 per call. |

Companions can also use helper agents. Familiars are helpers a companion summons. Bunshin are workers it sends off with part of a task. How many can exist, and how large they may grow, is set in two blocks of the defaults file. See [For developers](#for-developers).

## The research librarian

A household can use ResearchZosho, the research librarian, at [researchzosho.org](https://researchzosho.org). Companions ask it questions at the librarian's desk. They look at what it has already established before they search. They hand it questions to work on overnight, and check what they cited when they sleep.

`wyrd researcher setup` installs it with its own checked one-line installer, runs its setup and connects this node. Its setup is offered this home's brain as the model to read with (`WYRDSEKAI_LLAMA_URL`, else the brain on this machine at port 8200), so it does not pick another model server such as Ollama or LM Studio that happens to answer first. That stays the default; [Giving the library its own GPU](#giving-the-library-its-own-gpu) describes the alternative. A model you already chose in ResearchZosho stays; run `researchzosho setup` again to change it. `wyrd researcher link` connects to one that is already running. Either command writes these:

| Setting or file | What it is |
|---|---|
| `WYRDSEKAI_LIBRARY_SERVICE=researchzosho` | The service that plays the library role. Items in the world ask for the library role, never for a product by name. |
| `WYRDSEKAI_MCP_KEY_RESEARCHZOSHO` | The token the librarian gave this household, made with `researchzosho reader token <did>`. A DID is the household's identity. Not used for a librarian reached as a local program. |
| `<data>/mcp-services.json` | The librarian's entry in the list of services. See [MCP.md](MCP.md). |
| `WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_RESEARCHZOSHO` | The secret the librarian signs its updates with, when `link` asked it to send updates to this node. `--no-webhook` skips this. |
| `<data>/library-readers.json` | Who may read the household's own library over the web, with their tokens stored in scrambled form. Manage it with `wyrd library reader add`, `list` and `remove`. |
| `WYRDSEKAI_LAN_IP` | The address this node gives the librarian to send updates to, if the one it finds itself is wrong. |

Companions need no separate permission to ask the librarian: it is the household's own service, and its allow list decides what the household may do. Other MCP services need the steward's permission for each companion, given from the Study. `WYRDSEKAI_MCP_STRICT_GRANTS=false` drops that and lets every companion use every service; the node then logs a warning each time it starts.

### How many research runs a day

A question handed to the librarian for the night (`library_research`) is a research run. One asked at the librarian's desk may take up to 90 minutes on the household's model, which is also the companions' brain. The librarian gets one token for the whole household and cannot tell its members apart, so Wyrdsekai counts the runs itself, per person and per local calendar day. The count is kept in `world.db`, so a restart does not reset it.

| Setting | Default | What it does |
|---|---|---|
| `WYRDSEKAI_LIBRARY_RESEARCH_PER_DAY` | `5` | Research runs each household member may start per day. `0` means none. In the settings file: `library.research_per_day`. |
| `WYRDSEKAI_LIBRARY_OWN_TIME_RESEARCH_PER_DAY` | `3` | Research runs each companion may start per day on her own time, when no person asked. In the settings file: `library.own_time_research_per_day`. |
| `WYRDSEKAI_LIBRARY_RESEARCH_HOUSEHOLD_PER_DAY` | `10` | Research runs the whole household may start per day, members and companions together. `0` means none. In the settings file: `library.research_household_per_day`. |

A member under parental controls has the household's number too. The steward can set a different number for them on the parental-controls scroll in the Study: `use parental controls scroll set <username> research <n>`. `0` closes the library's research to them, and `default` goes back to the household's number.

A run is counted when the librarian accepts it and returns a job id. A question it refused, one that waits for the person's `research yes`, or one Wyrdsekai's own check held back does not count. A `research yes` counts as that person's run. When a person's runs for the day are used, or the household's, nothing is sent: the companion is told how many were asked for and that it can wait for tomorrow. A `research yes` that meets the household's limit keeps waiting for the time it has left. Older ResearchZosho releases had a daily budget of their own and answer `budget_exceeded` when it is used; the companion is told that in a sentence too.

### Giving the library its own GPU

By default the library reads with the companions' brain. `wyrd researcher setup` offers the brain to ResearchZosho's setup, and ResearchZosho's `model install` reuses a brain it finds on port 8200 instead of downloading a model. Nothing has to change. A library that does a lot of research is better off with a model of its own:

- A research run can hold the model for up to 90 minutes. The companions answer more slowly while it does, and their own time waits.
- Long research prompts push the companions' cached prompts out of the model server, so their next turns are slower too.
- On the `single-sparse` profile the brain answers with the companion's adapter at its default scale unless a request sets each adapter to zero. ResearchZosho's requests set none.
- The library's check for a question about harming oneself is the model's own judgment. In ResearchZosho's measurements it caught 10 of 10 crisis questions on a 27B, 9 of 10 on the 35B brain, and 5 of 10 on the default 9B drive.

`wyrd researcher gpu` shows where the library's model runs now (the companions' brain, a server of its own on this machine, or another machine), this machine's NVIDIA cards with the one that holds the brain, and the command that changes it:

```
wyrd researcher gpu              # where it runs now, and the command to change it
wyrd researcher gpu 1            # a model of its own on card 1 of this machine (Linux)
wyrd researcher gpu gpu-host    # the model server on another household machine
wyrd researcher gpu shared       # back to the companions' brain, the default
```

Each form says what it will do and asks first. `--yes` takes the default answer. The changes are made by ResearchZosho's own commands, run as the user the library belongs to; Wyrdsekai does not write ResearchZosho's files.

- `gpu <card>` runs `researchzosho model install --own --gpu <card>`. ResearchZosho downloads the model it measured for that card once and serves it at `http://127.0.0.1:8211` only while research needs it; 20 idle minutes later the card is free again. It needs ResearchZosho 0.5.0 or later. For a card that also holds the brain the default answer is no. Choosing a card is Linux only; on macOS and Windows use another machine.
- `gpu <machine> [<model>]` first checks that `http://<machine>:8211/v1/models` answers. If nothing does, it prints what to run on that machine: `researchzosho model install --share`. Then it points the library there. `<machine>:<port>` or a full address names a different server. On a server that lists more than one chat model, the library keeps the model it uses now when that server has it; otherwise name the model.
- `gpu shared` runs `researchzosho model uninstall` when the library has a model server of its own on this machine (the model files stay in `~/models`), then points the library back at the brain if it is not there already.

With ResearchZosho 0.5.1 or later the library is pointed at a server with `researchzosho model use <address> <model>`, also after `gpu <card>` installs one. It checks that the server answers and the model replies before it changes anything. The running service takes the new model for its next research run and question, with no restart, and a run going at that moment finishes on the model it started with. If the model does not reply yet, `model use` changes nothing and prints why; after `gpu <card>` the command to run once the model answers is printed (the install itself has already pointed ResearchZosho's settings at the new server).

ResearchZosho 0.5.0 has no `model use`, and its service reads its model address only when it starts. With 0.5.0, `gpu <machine>` and `gpu shared` run ResearchZosho's own setup with the address offered as the model (`--no-service --no-claude`; press Enter to keep the other answers), and after any change the command restarts the service: `systemctl --user restart researchzosho` on Linux, `launchctl kickstart` on macOS, its logon task on Windows. A research run that was going starts over. `wyrd researcher update` brings ResearchZosho up to date.

When the library shares the brain, `wyrd researcher setup` ends with a note that says so, why it matters, and the command, and names a second card when this machine has one free. `wyrd doctor` shows the same note as information, not as a warning.

## Coding helpers

A companion can call on a coding helper, a program that writes and changes code for it. The choices are CodeZaiku, which comes with Wyrdsekai and is the default, and goose, the recommended alternative if you would rather not use CodeZaiku. There are also pi, Codex, OpenCode, OpenHands, Aider, Gemini CLI, Claude SDK and Devin.

Each helper has its own group of settings, named `WYRDSEKAI_CODING_<BACKEND>_*`. `WYRDSEKAI_CODING_DEFAULT_BACKEND` picks the default. There is also an egress gate, a rule for what a helper inherits from the node's settings: it gets no stored keys except its own. It does not limit what the helper may reach on the network. These settings deserve their own page: see [EXTENDING.md](EXTENDING.md).

These four points answer common setups:

- **`WYRDSEKAI_INFERENCE_URL`**: set it when this node's drive model runs on another computer. Every helper that needs no key follows it. Nothing else needs setting.
- **`WYRDSEKAI_CODING_CODEZAIKU_DRIVE_URL`** and **`WYRDSEKAI_CODING_CODEZAIKU_MODEL`**: the model CodeZaiku should think with, when this node chooses one. When set, it overrides CodeZaiku's own settings file. When not set, that file decides, and it could send this node's coding tasks to another computer.
- **`WYRDSEKAI_CODING_OPENHANDS_AGENT_SERVER_URL`**: where the OpenHands agent server listens. The default is `http://localhost:8000`. The old name, `WYRDSEKAI_CODING_OPENHANDS_MCP_URL`, is still read for compatibility. It names a protocol OpenHands no longer speaks.
- **Model size matters.** How much text a model can hold at once is called its context. CodeZaiku needs a model with a context of at least 12K tokens, and 16K is comfortable. OpenHands wants 32K. A model with 8K passes the health checks and still fails real tasks.

## For developers

- **Where defaults live.** `core/src/main/resources/reference.conf` and `server/src/main/resources/application.conf` are HOCON files. There, a `${?WYRDSEKAI_*}` line lets the matching environment variable override the value. `core/src/main/java/org/wyrdsekai/core/config/WyrdConfig.java` resolves many settings: environment first, then `~/.wyrdsekai/profile.toml`, keyed `section.key`, then the built-in default. The launchers, `bin/wyrd` and `packaging/windows/wyrd.ps1`, and `docker-compose.yml` read the `LLAMA_*` and launcher-only settings. The catalog behind `wyrd config list --all` is `scripts/config-catalog.json`. When this page and the code disagree, the code is right. `wyrd config audit` shows each resolved value and where it came from.
- **The Safe** is `core/src/main/java/org/wyrdsekai/core/room/TheSafe.java`. MCP credentials go through `McpKeyStore`, which reads The Safe slot named by the service's `safe_key` first (`wyrd cred set <safe_key>`), then `WYRDSEKAI_MCP_KEY_<SAFE_KEY>`. Item scripts get a reference to a slot, never its value (`SafeRefs` in `scripting`).
- **Sparse placement flags.** `LLAMA_CPU_MOE=all` becomes `--cpu-moe`, and a number N becomes `--n-cpu-moe N`. The template switch becomes `--chat-template-kwargs`, and the cache size becomes `--cache-ram`. The in-process embedder is an ONNX session.
- **CodeZaiku routing.** The drive URL and model reach CodeZaiku as the `CODEZAIKU_DRIVE` and `CODEZAIKU_MODEL` environment variables.
- **OpenHands.** In `reference.conf`, both `WYRDSEKAI_CODING_OPENHANDS_AGENT_SERVER_URL` and the old `WYRDSEKAI_CODING_OPENHANDS_MCP_URL` set `agent-server-url`. The old name is read second, so if both are set, it wins.
- **Helper agents.** `wyrdsekai.familiar` in `reference.conf` has `default` and `max` limits for `tokens`, `steps`, `wall-clock`, `nest-depth` and `cu`. `wyrdsekai.bunshin` has `max-concurrent` 2, `elastic-concurrent` 3 and `absolute-ceiling` 5.
- **Coding helpers** are configured in the `wyrdsekai.coding` block of `reference.conf`: `default-backend`, `egress-gate` and one `backends.<name>` block each.
