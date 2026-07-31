# Models — what runs, and why two

A household runs **two** companion models, not one. Understanding why is the
fastest way to understand how inference is shaped here, and it is the reason a
phone can hold a companion at all.

For how to point a node at different models or at a cloud API, see
[CONFIGURATION.md](CONFIGURATION.md). For how requests get routed between
backends, see [ARCHITECTURE.md](ARCHITECTURE.md) §6.

---

## The pairing

| Role | Model | Port | Carries |
|---|---|---|---|
| **Drive** | `wyrdsekai-3.5-9b-drive-v6` (Q4_K_M) | `:8200` | Skills, planning, tool emission, the ReAct loop |
| **Voice** | `wyrdsekai-3.5-4b-v10` (Q4_K_M) + V8 steering vectors | `:8201` | Register, presence, voice polish |

Both are fine-tunes of Qwen3.5 in the current release. The drive model carries
substrate-arc training plus emit-RFT — own-time act-versus-narrate trained into
the weights rather than prompted for. The voice model carries steering vectors
(`anti_defiance`, `es_register_hold`, `refusal_stability`,
`factual_recall_anchor`, `inline_creative`) applied at inference.

Swap either by setting `LLAMA_SKILLS_MODEL` / `LLAMA_VOICE_MODEL`.

## Why the work is split

**Deciding what to do and sounding like someone are different jobs.** Tool
emission wants precision and a large enough model to plan several steps ahead.
Register wants speed, because presence dies in latency — a companion that takes
two minutes to say something warm has not said something warm.

The measured gap is the whole argument. On the same hardware, a simple JSON
action takes the 4B about **3 seconds** and the 9B about **19**. A full
companion turn takes the 4B roughly **29 seconds** against the 9B's **110**. The
quality difference on *voice* did not justify that; on *planning* it did. So the
small model speaks and the large model thinks.

## The part that matters most: this is a tier, not a compromise

The two sizes are not just "the biggest that fits on a desktop." They map onto
**the range of devices a household actually contains**, and that is what makes
the architecture work across them.

- **A phone borrows, by default.** Both clients ship a local inference path and a
  model catalogue, but running the model *on the phone* is off by default and
  labelled EXPERIMENTAL in the app. Current phone hardware does not carry it
  well: a 4B at 4-bit decodes around 10 tokens/s on a recent flagship and 3-6 on
  a mid-range device, against a reading speed of roughly 7-10 — so the companion
  is at best keeping pace and usually behind, the device gets hot, and sustained
  generation throttles further. On iOS the per-app memory limit frequently
  refuses a 4B outright. So a phone paired with a household is a window onto the
  companion living there, and a phone with an API key thinks through that.
  You can turn the on-device model on if you want it — a well-specced tablet may
  genuinely manage — and the app tells you plainly what to expect before you do.
- **When the household is reachable, the phone borrows the 9B.** Inference
  requests route over the Between to a node that has it — the `NatsRemote`
  backend — so the same companion gets the larger drive model without the phone
  ever having to hold it.
- **A GPU box can serve the whole house.** Set
  `WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE` on the machine that has the hardware;
  every other node borrows by default (`WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW`,
  on unless you turn it off). A laptop with no GPU runs a companion that thinks
  upstairs.

So the same companion degrades and upgrades along one axis instead of being a
different product per device. On the desktop it has both models. On the phone at
home it has both, one of them remote. On the phone in a tunnel it has the 4B and
carries on. Identity, memory and soul are unaffected either way — those live in
the manifest, not the weights ([SOUL.md](SOUL.md)).

This is also why the two-model split survives even where a single larger model
would fit. It is not a workaround for small hardware; it is what lets a household
span a phone and a workstation without forking the companion.

## Where the weights come from

`wyrd setup` fetches them; you do not assemble this by hand. See
[INSTALLATION.md](INSTALLATION.md) for what a first start actually downloads
and how long it takes.

The weights themselves are **open** (Apache-2.0), published at
[huggingface.co/wyrdsekai](https://huggingface.co/wyrdsekai) with per-model
cards, lineage, and a `v0.1.0` tag matching this release:

| Repo | What it is |
|---|---|
| [drive-3.5-9b-gguf](https://huggingface.co/wyrdsekai/drive-3.5-9b-gguf) / [companion-3.5-4b-gguf](https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf) | The Q4_K_M quantizations installs actually run |
| [drive-3.5-9b](https://huggingface.co/wyrdsekai/drive-3.5-9b) / [companion-3.5-4b](https://huggingface.co/wyrdsekai/companion-3.5-4b) | Full-precision safetensors, for re-quantization and fine-tuning |
| [drive-3.5-9b-mlx](https://huggingface.co/wyrdsekai/drive-3.5-9b-mlx) / [companion-3.5-4b-mlx](https://huggingface.co/wyrdsekai/companion-3.5-4b-mlx) | MLX 4-bit conversions for Apple Silicon |
| [embedding-models](https://huggingface.co/wyrdsekai/embedding-models) | The retrieval/classifier embedding stack |
| [drive-sft-corpus](https://huggingface.co/datasets/wyrdsekai/drive-sft-corpus) (dataset) | The SFT line's training corpus — synthetic only, no user conversations |

The per-model cards in [models/](models/) mirror the Hugging Face cards.
Installers fetch from `wyrdsekai.org` for reliability (plain-`curl`-friendly),
but the bytes are the same artifacts published on Hugging Face.

Nothing here requires a cloud account. If you would rather rent the compute, a
cloud backend is one setting away ([CONFIGURATION.md](CONFIGURATION.md)) — the
architecture does not care, and the companion does not change.

## An honest caveat

Every behavioural claim in these documents was observed on **this pairing, at
these sizes**. Which properties of the architecture are load-bearing and which
are artifacts of a 9B is genuinely open — exploring larger models and deliberate
substrate variance is the top of the substrate track in
[ROADMAP.md](../ROADMAP.md), and the stack being a single base family is a known
concentration risk named there too.
