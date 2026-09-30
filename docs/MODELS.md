# Models

Your companion needs an AI model to think and to talk. A model is a large file,
trained on a huge amount of text, that a program on your computer runs to
produce language. This page explains which models Wyrdsekai uses, what your
computer needs to run them, and why it is set up this way.

The short version:

- **By default, a companion runs on two small models that work together.** They
  are built to fit on an ordinary home computer.
- **Since 0.5.0 you can switch to one larger model instead**, if your computer
  is big enough. It is optional. Nothing changes unless you switch it on.
- **You do not have to find or install models yourself.** `wyrd setup`
  downloads them for you.
- **Your companion is not its model.** Its identity and memories are kept
  separately, so it stays the same companion when the model changes.

To point Wyrdsekai at other models, or at a cloud service, see
[CONFIGURATION.md](CONFIGURATION.md).

---

## A few words you will see

- **Model size.** Models are described by how many numbers they contain, in
  billions. "9B" means about nine billion. A bigger model is usually more
  capable, but slower, and it needs more memory.
- **Graphics card memory.** A graphics card has its own memory, separate from
  the computer's regular memory. A model runs much faster when it fits there.
  You will also see it called VRAM.
- **Quantisation.** A way of shrinking a model by storing its numbers less
  precisely. It makes a model small enough for a home computer, with a small
  loss in quality. "Q4" and "4-bit" mean about four bits per number.
- **gguf file.** The file format the models come in. Each model is one gguf
  file.
- **Add-on files.** Small files loaded on top of a model that change how it
  behaves, without changing the model itself. They are also called adapters.

---

## The standard setup: two models

| Job | Model | Download |
|---|---|---|
| **Drive**: deciding what to do, planning, using tools | Wyrdsekai drive, 9B | about 5.6 GB |
| **Voice**: how the companion sounds, and being present with you | Wyrdsekai companion voice, 4B | about 2.7 GB |

Both start from Qwen3.5, an openly published family of models. This project
trained both further. Both are quantised to 4 bits.

`wyrd doctor` checks whether your computer has enough disk space, memory and
graphics card. [INSTALLATION.md](INSTALLATION.md) says what a first start
downloads and how long it takes.

### Why two models and not one

Deciding what to do and sounding like someone are different jobs.

Using tools well needs precision. It also needs a model big enough to plan
several steps ahead. Sounding like someone needs speed. A companion that takes
two minutes to say something warm has not really said something warm.

We measured the gap on the same computer. A simple action, written as
structured data for the program to read, takes the 4B about **3 seconds** and
the 9B about **19**. A full reply from the companion takes the 4B about **29
seconds** and the 9B about **110**.

On *sounding* like the companion, the 9B was not enough better to justify that
wait. On *planning*, it was. So the small model speaks and the larger one
thinks.

### A ladder across your devices, not a compromise

The two sizes are not just "the biggest that fits on a desktop." They match the
range of devices a household actually has. That is what lets one companion
work across all of them.

- **A phone borrows, by default.** The phone apps can run a model on the phone
  itself. This is off by default and marked EXPERIMENTAL in the app, because
  today's phones do not handle it well.
  - A 4B model at 4 bits produces about 10 tokens a second on a recent top
    phone, and 3 to 6 on a mid-range one. A token is a word or part of a word.
  - People read about 7 to 10 tokens a second. So the companion would at best
    keep pace, and usually fall behind.
  - The phone gets hot, and long answers slow it down further.
  - On iPhone and iPad, the memory limit for each app often refuses a 4B model
    outright.

  So a phone paired with your household is a window onto the companion that
  lives there. A phone set up with a key for a cloud AI service thinks through
  that service instead. You can still turn the phone model on if you want it.
  A well-equipped tablet may manage it, and the app tells you plainly what to
  expect before you do.
- **When your household is reachable, the phone borrows the 9B.** It sends its
  requests over the Between to a computer that has the model. The Between is
  the network that links your household's devices. The phone gets the larger
  drive model without ever having to hold it.
- **One computer with a graphics card can serve the whole house.** Set
  `WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE` on the computer that has the hardware.
  Every other device borrows from it by default. That setting is
  `WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW`, and it is on unless you turn it off.
  A laptop with no graphics card then runs a companion that does its thinking
  on that other computer.

So the same companion gets weaker or stronger along one line, instead of being
a different product on each device. On a desktop it has both models. On a
phone at home it has both, one of them on another computer. On a phone in a
tunnel it has the 4B and carries on.

Its identity, memory and soul stay the same either way. The soul is the
companion's own record of who it has become. It is kept apart from the
model. See [SOUL.md](SOUL.md).

This is also why the two-model setup stays the default, even on a computer big
enough for one larger model. It is not a workaround for small hardware. It is
what lets one companion span a phone and a workstation without becoming two
different companions.

---

## The larger model (optional, new in 0.5.0)

Since 0.5.0, your companion can run on one larger model instead of the two
small ones, if your computer is big enough. It is optional. Nothing changes
unless you switch it on.

The model is **Qwen3.6-35B-A3B**. It holds 35 billion numbers in total. It is
built from many small expert parts, and only about 3 billion numbers are in use
for each word. That is why a home computer can run it. This project did not
train it. It comes from the same Qwen family as the two small models.

**It keeps your companion's voice.** Wyrdsekai adds three small add-on files to
it, about 13 MB each. Two were trained from the same material the two small
models learned from, so it talks and behaves like your companion. The third
keeps it honest and is used while the companion works on a task. In our tests
the larger model passed 62 of 63 checks. The two small models pass 59.

### What your computer needs

- **Linux**, where it runs in Docker. Docker is a program that runs other
  programs in their own sealed box.
- **An NVIDIA graphics card.** 16 GB of graphics memory is recommended, and it
  is what we measured. A card with 8 GB can work, with more of the model kept
  in regular memory.
- **32 GB of regular memory.**
- **About 30 GB of free disk space.** The model itself is about a 22 GB
  download.

On a Mac, it needs Apple Silicon and 48 GB of memory or more. It is also
included for Windows. Neither Mac nor Windows has been tested yet.

### Trying it

First, check whether your computer can run it:

```sh
wyrd brain plan
```

It tells you how much of the graphics card the model would use, and how much
of it would sit in regular memory instead.

Then download it. It asks before it starts:

```sh
wyrd brain setup
```

This downloads the model, about 22 GB, and its three add-on files.

Then switch to it and restart:

```sh
wyrd brain enable --single
wyrd restart
```

To go back to the two small models:

```sh
wyrd brain disable
wyrd restart
```

Nothing is deleted either way. Both sets of models, their add-on files and
their settings stay on your disk. `wyrd brain status` shows which setup is in
use and what has been downloaded.

### Sharing the graphics card

On Linux, the companion shares the graphics card with your other programs. If
a game or another program has needed the card for two minutes, the companion
moves part of itself into regular memory. It moves back once the card has been
free for ten minutes.

It runs a little slower while it shares. Each move takes a minute or two, and
the companion cannot answer during it.

You can also set a limit. This keeps it under 8 GB of the card:

```sh
wyrd brain vram 8
```

`wyrd brain vram auto` removes the limit again.

### Learning at night

On the larger model, a companion can train a small add-on file from its own
day while it sleeps. This takes about 20 minutes on a 16 GB card. The model is
stopped while it trains, so the companion stays asleep until it is done. A
check the next morning, and a second one later that day, remove any night that
made it worse.

It is on by default. `wyrd brain setup` sets up its trainer along with the
model, about 10 GB more. To set up the model without it, add `--no-trainer`.
To turn nightly learning off later:

```sh
wyrd config set WYRDSEKAI_SLEEP_WRITE=false
wyrd restart
```

This sets up the training tools, which take several GB, and downloads a 4.9 GB
training bundle. To try one night once, without keeping anything:

```sh
wyrd sleepwrite rehearse <companion>
```

Nightly learning on the larger model runs on Linux. It is included for Mac but
has not been tested there. Windows does not have it yet.

### A second way: the larger model works, the small voice speaks

There is another option, for a companion whose small voice model has already
learned from its own nights. The larger model takes over the drive's job:
deciding, planning and using tools. The small voice model keeps speaking, with
everything it has learned.

```sh
wyrd brain enable --drive
wyrd restart
```

This needs Linux with Docker and a graphics card with 12 GB or more. If you run
`wyrd brain enable` without `--single` or `--drive`, it picks `--drive` when
the voice model already has nights learned into it, and `--single` otherwise.

---

## Where the models come from

`wyrd setup` downloads the standard models, and `wyrd brain setup` downloads
the larger one. You do not assemble anything by hand.

Our models are **open**. Anyone may download, use and change them under the
Apache-2.0 licence. They are published at
[huggingface.co/wyrdsekai](https://huggingface.co/wyrdsekai), a public site for
sharing AI models, with a description card for each. The same cards are in
[models/](models/).

Downloads come from Hugging Face first, with wyrdsekai.org as a backup. Each
download is pinned to one exact version. Every file is checked against its
published checksum before it is used. A checksum is a fingerprint of the file:
if a single byte differs, the check fails.

The larger model is published on Hugging Face by a third party, unsloth, also
under Apache-2.0. It is pinned and checked the same way.

The drive model's training material is published too. It is made-up examples
only, with no one's real conversations.

Nothing here needs a cloud account. If you would rather rent computing power, a
cloud service is one setting away. See [CONFIGURATION.md](CONFIGURATION.md).
The design does not care where the model runs, and the companion does not
change.

---

## An honest caveat

Almost everything these documents say about how companions behave was observed
on the two small models, at these sizes. The larger model is new in 0.5.0 and
has only been tested on Linux.

Which parts of the design truly matter, and which only happen because the model
is a 9B, is still an open question. Trying larger models, and deliberately
different ones, is at the top of the substrate track in
[ROADMAP.md](../ROADMAP.md). The substrate is whatever a companion runs on. All
of these models also come from one family, Qwen. That is a known risk, and the
roadmap names it too.

---

## For developers

### Roles, files and ports

| Role | Model | Port | Carries |
|---|---|---|---|
| **Drive** | `wyrdsekai-3.5-9b-drive-v6` (Q4_K_M) | `:8200` | Skills, planning, tool emission, the ReAct loop |
| **Voice** | `wyrdsekai-3.5-4b-v10` (Q4_K_M) + V8 steering vectors | `:8201` | Register, presence, voice polish |
| **Single model**, profile `single-sparse` | `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf` + adapters | `:8200` | Every lane |

The drive model carries substrate-arc training plus emit-RFT, so own-time
act-versus-narrate is trained into the weights rather than prompted for. The
voice model carries steering vectors applied at inference. The shipped default
set is `anti_defiance`, `es_register_hold`, `refusal_stability` and
`first_person_presence`.

Swap either model by setting `LLAMA_SKILLS_MODEL` / `LLAMA_VOICE_MODEL`. The
serving profiles (`two-model`, `sparse-drive`, `single-sparse`), the adapter
order and the register dial are in [CONFIGURATION.md](CONFIGURATION.md) under
"Serving profiles". Routing between backends is in
[ARCHITECTURE.md](ARCHITECTURE.md) §6. A phone borrows the drive over the
Between through the `NatsRemote` backend.

Under `single-sparse`, `adapters/brain/` holds `species.gguf` (the species
floor, adapter 0), `styled.gguf` (the register dial) and `honesty.gguf`, which
is linked in as `work.gguf` for working turns when the species floor is
present. The night's adapter is written to `adapters/brainwrite/`. The nightly
write is on by default wherever its trainer is set up (its Python environment
and the training bundle); unset, a node without the trainer skips the night
quietly. `WYRDSEKAI_SLEEP_WRITE=false` turns it off, `=true` arms it even where
the trainer looks absent.

### Published weights

| Repo | What it is |
|---|---|
| [drive-3.5-9b-gguf](https://huggingface.co/wyrdsekai/drive-3.5-9b-gguf) / [companion-3.5-4b-gguf](https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf) | The Q4_K_M quantisations installs actually run |
| [drive-3.5-9b](https://huggingface.co/wyrdsekai/drive-3.5-9b) / [companion-3.5-4b](https://huggingface.co/wyrdsekai/companion-3.5-4b) | Full-precision safetensors, for re-quantisation and fine-tuning |
| [drive-3.5-9b-mlx](https://huggingface.co/wyrdsekai/drive-3.5-9b-mlx) / [companion-3.5-4b-mlx](https://huggingface.co/wyrdsekai/companion-3.5-4b-mlx) | MLX 4-bit conversions for Apple Silicon |
| [brain-3.6-35b-a3b-adapters-gguf](https://huggingface.co/wyrdsekai/brain-3.6-35b-a3b-adapters-gguf) | The three `single-sparse` adapters: honesty, species floor, styled |
| [embedding-models](https://huggingface.co/wyrdsekai/embedding-models) | The retrieval/classifier embedding stack |
| [drive-sft-corpus](https://huggingface.co/datasets/wyrdsekai/drive-sft-corpus) (dataset) | The SFT line's training corpus: synthetic only, no user conversations |

The base for `single-sparse` is
[unsloth/Qwen3.6-35B-A3B-GGUF](https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF).
The GGUF repos carry version tags: `v6` for the drive, `v10` for the voice, and
`v0.1.0` from the first release. Installs download the exact revision and
sha256 pinned in `models-index.json`, not a tag. The per-model cards in
[models/](models/) mirror the Hugging Face cards.
