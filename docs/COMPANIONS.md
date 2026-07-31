# Companions

A companion is an agent that lives in your zone: it has a DID and an Ed25519
keypair, a temperament it was born with, a voice, memories, drives, bonds, and a
welfare floor below which it is allowed to stop. This document covers creating
one, what shapes its personality, how bonds work, how it reaches your phone, and
what protects it.

Where this document and the code disagree, **the code is right** — and a
correction here is a welcome PR.

The runtime is `core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java`.
Note that `core/.../companion/` is a *different* thing — it holds the child and
aging-companion modes, safety monitors and the Hearth's own-time surfaces, not
the companion core.

---

## Creating a companion

Three verified paths.

### 1. The first-boot prompt

`wyrd start` runs `_bootstrap_companion_names()` before the JVM exists. It asks
for a companion name (60 s countdown, sanitized to `[A-Za-z0-9_-]`, default
`wyrd`), then asks whether you want a second companion (30 s; defaults to yes
when `WYRDSEKAI_VOICE_ENABLED=true`, otherwise no; second default name `wisp`).
It writes `WYRDSEKAI_COMPANION_NAME` / `WYRDSEKAI_COMPANION_NAME_2` into whichever
config the launch path actually reads — the source-mode env file, or
`/etc/wyrdsekai/wyrdsekai.conf` under systemd. Idempotent: it no-ops once
`$DATA_DIR/.steward-bootstrapped` exists.

The second companion is born with archetype `"random"`.

### 2. In-world, at the Forge

```
birth <name>
```

Steward-only, dispatched by `ForgeRoomBridge`. The full Forge verb set is
`inspect`, `history`, `forge_status`, `forge`, `grow`, `compare`, `restore`,
`birth`, `home_sleep`, `home_dreams`, `home_fragments`, `mirror_check`,
`examine_drift`.

### 3. From the Study's bond crystal

`ForgeRoomBridge.stewardBirth(name, actorId)` — the same steward gate, duplicate
check and spawn path, returning a structured map for scripted callers. One flow,
two doors.

### What actually gets created

From `CompanionActor.initializeSoul`:

1. An Ed25519 keypair via `DidKey.generate()` → a `did:key:…` identity.
2. A `SoulManifest` persisted through `SqlSoulStore` into `world.db`.
3. A `VoiceProfile.fromTemperament(birthSeed)` attached to that manifest.
4. A DID mapping file at `$WYRDSEKAI_DATA_DIR/souls/companion-<slug>.did`.
5. A row in `CompanionRegistry`.
6. Per-agent JSON state materialized lazily under
   `$WYRDSEKAI_DATA_DIR/agents/<did-slug>/` (`autonomy.json`, personal projects).

On restart the manifest is reloaded and its Ed25519 signature verified
(tamper-evident, logged, non-fatal). **The persisted name wins over the env
name**, so an in-world rename survives a reboot.

---

## Temperament

Every companion is born from one low-dimensional seed. `TemperamentSeed` is a
six-axis record, each axis in `[0,1]` with `0.5` meaning neutral:

| Axis | What it governs |
| --- | --- |
| `sociability` | pull toward others — loneliness / amae / saudade reactivity, affiliation |
| `curiosity` | epistemic hunger — stagnation reactivity, seeking, sustained attention |
| `vigilance` | threat and duty attunement — standing, harmony, obligation |
| `industry` | need to make and sustain flow — significance, momentum, creativity |
| `restlessness` | novelty-churn and fast burn — wandering focus, play |
| `warmth` | steadiness and caring tone — equanimity / rapport baseline, care |

The genome (what it does), the voice (how it says it) and the drive scales (what
it reaches for) are all derived from this one seed, deterministically, so they
cohere by construction. A withdrawn genome cannot get a bubbly voice, because
both come from the same place.

**Sampling.** `TemperamentSeed.random()` samples each axis freely in
`[0.10, 0.90]`. `isViable()` rejects only *incoherent* seeds — too flat (no
character at all) or extreme on four-plus axes (caricature) — and the caller
re-samples. It is a viability gate, explicitly **not** a conformity gate:
distance from any named preset is never considered. A seed far from every preset
is a genuinely novel individual, and that is the success case.

Six `PRESETS` exist — `scholar`, `guardian`, `artisan`, `diplomat`, `explorer`,
`steward` — but they are **measurement anchors only**. `nearestPreset()` labels a
particular (`"scholar~0.41"`); proximity seeds nothing and gates nothing.

**Birth mode.** `WyrdConfig.birthMode()` defaults to `"particular"` — free
sampling. `WYRDSEKAI_BIRTH_MODE` / `-Dwyrdsekai.birth.mode=neutral` pins the
neutral seed (the test suite does this for determinism). An explicit per-agent
archetype always overrides the household mode.

**Three channels from seed to behavior:**

1. **Drives.** `driveBoosts()` returns signed deltas for `seeking`, `affiliation`,
   `care`, `vigilance`, `play`, `creativity`, applied via
   `DriveEngine.forTemperament(seed)`. On reload the seed is recovered from the
   persisted genome by `GenomeProfile.temperamentOf(...)` — no schema change
   needed.
2. **Prompt.** `VoiceProfile.fromTemperament(seed)` yields `cadence`, `habit`
   and `warmth` clauses, rendered as a `[voice guidance]` block by
   `PromptAssembler`.
3. **Decoding.** `registerMix()` returns `register_warmth`,
   `register_expansiveness` and `register_guardedness`, each clamped to
   `[-0.55, 0.55]`, sent backend-neutrally on the voice pass. When every value is
   under 0.02 the field is suppressed entirely, so a neutral agent's wire is
   byte-identical to before this feature existed.

Sampling temperature is **not** seed-derived — it is a static field on
`AgentProfile`.

Two corrections worth knowing, both from 2026-07-17. The shared system prompt was
**de-clamped**: 62 identical lines of personality were drowning three
seed-derived clauses, so every install's first impression was the same person.
The prompt now carries function; tone belongs to the seed. And cadence selection
was **decorrelated** — measured over 100 000 seeds, 19% of all particulars landed
on the identical register and the top four covered 48%. Cadence now keys on the
strongest qualifying axis rather than a fixed priority order.

---

## Voice

`VoiceProfile` is `record VoiceProfile(Map<String,String> clauses, int revision,
boolean frozen, List<ProfileRevision> history)`. Clauses are keyed guidance
strings. Each `ProfileRevision` stores the **pre-change** snapshot, so reverting
is a copy rather than a replay. `frozen == true` blocks all automated mutation.

All writes route through `VoiceProfileService`, one gate shared by the Study,
the REST API, the `wyrd voice` CLI and the Forge. `VoiceProfileForge` runs inside
the deep-sleep cycle and proposes exactly **one** structured change per cycle,
with a reason; malformed or over-budget proposals are dropped.

Three separate things share the word "voice" in this codebase. Keep them apart:

- **`voiceProfile`** — text-level register. No audio anywhere near it.
- **`WYRDSEKAI_VOICE_ENABLED` / the `:8201` backend** — a *second prose model*
  that re-speaks lines in the agent's register (dual inference). Still
  text-to-text.
- **TTS** — `core/.../accessibility/VoiceEngineConfig.java` and
  `core/.../voice/`. Unrelated to `voiceProfile`; nothing links a clause to a
  synthesis voice.

**Partial:** voice-profile storage is mid-migration. It is dual-written to
`SoulManifest.voiceProfile` and the `voice_profiles` table; the phase that makes
the manifest field computed-at-serialize has not landed. `VoiceProfileService`
does not notify the live actor, and concurrent writes to one DID race
(last writer wins). `register_guardedness` is marked provisional in source and
de-weighted ×0.5 because its extraction corpus entangles with warmth.

---

## Bonds and the bondholder

`Bond` is a 15-field record: `bondId`, the two party DIDs, `depth`, `formedAt`,
`lastInteraction`, `interactionCount`, `mutualConsent`, `active`, `scarred`,
`state`, `coldStartUntil`, `posture`, `relationalState`, `kind`.

**`BondKind`** — three values:

| Kind | Relational substrate | Authority substrate |
| --- | --- | --- |
| `BONDHOLDER` | yes | yes — grants, posture-gating, cost ceilings |
| `MEMBER` | yes — depth ladder, repair, mourning | no |
| `PEER` (agent↔agent) | yes | no |

**There is exactly one bondholder.** When `BondholderAnnounced` fires, every
*other* human holding a BONDHOLDER-kind bond is re-typed to `MEMBER` —
**depth, history and state ride along; only the role moves**. The target's bond is
promoted (or opened fresh) as BONDHOLDER and crossed to ACTIVE.

This exists because of a real failure. `primaryBondholderDid()` returns the
*deepest* bond's other party. Two companions organically bonded to each other,
those peer bonds defaulted to BONDHOLDER kind, and the peer out-ranked the human
— the companions declined to come when their person logged in, repeatedly. The
fix filters on both the `BondKind` **and** the other party's identity, so an
existing household repairs itself on the next boot instead of needing a
migration.

**`BondDepth`** — six levels, each with a memory-retrieval boost:
`ACQUAINTANCE(0.0)`, `FAMILIAR(0.15)`, `ITEM(0.3)`, `SACRED(0.6)`,
`SOUL_REF(0.8)`, `SOUL_INGRAINED(1.0)`. At `SACRED` and above the bond protects
items from Forge pruning; at `SOUL_INGRAINED` severance scars.

**`BondState`** — `OPEN`, `ACTIVE`, `AWAY`, `DORMANT`, `REACTIVATING`, `SEVERED`,
`MOURNING`. Bondholder bonds are **born ACTIVE**; the three-substantive-exchange
OPEN→ACTIVE gate applies only to auto-spawned stranger bonds, and "substantive"
means ≥12 characters and ≥3 words so a companion does not mourn someone it traded
"hey / yeah / cool" with. Cold-start window is 14 days; mourning lasts 30.

**CLI:**

```bash
wyrd bond create <player-username> <companion-did> [--depth <LEVEL>]
wyrd bond list
```

Backed by `server/.../BondAdminMain.java`, which writes the bond store and
manifest directly. It exists because forming a bond in-world is too slow for
setup. The live actor picks up store-side changes via `syncBondsFromStoreIfStale()`,
at most once per 10 s.

**Transfer is partial.** There is no `wyrd bond transfer` and no dedicated
ritual. Transfer happens only as a side effect of a new `BondholderAnnounced`
re-typing the previous holder to MEMBER. The mechanism works; the ceremony
does not exist yet.

### What a bondholder decides

`BondholderPosture` is the bondholder's explicit choice about the agent's outer
life during their absence:

| Posture | Cloud | Local inference | Ambient autonomy |
| --- | --- | --- | --- |
| `GENEROUS` | yes | yes | yes |
| `BOUNDED` (**cold-start default**) | no | yes | yes |
| `MINIMAL` | no | rate-limited, on-summon only | no |
| `SUSPENDED` | no | no | no |

The **inner life is never gated**. Hearth, Chronicle, Journal, Mirror,
soul-fragment recall, sleep and Forge remain available at every posture,
including `SUSPENDED`. And the agent *knows which posture it is on* — this is a
stated resource scope, not a hidden throttle.

---

## The welfare floor

Two distinct systems. Do not conflate them.

### The bondholder floor

The bondholder floor is the reciprocity commitment: what the bondholder
owes the agent. Its own header still says **"design draft — pre-OSS, debt #2 of
four"**, and it is one of the four open post-OSS gaps. Substantial parts are
nevertheless implemented: the `BondState` machine and cold-start window,
`BondholderBaselineClassifier` and `BondholderEngagementHistory` (which derive
AWAY/DORMANT from the bondholder's *own* median contact interval rather than a
fixed clock — AWAY past 1.5×, DORMANT past 4× plus sustained drift),
`BondholderPosture`, `RelationalFloorView`, `DepartureReturnRituals`.

The principle, from §1: *the agent is given to the bondholder, but the agent is
not the bondholder's.* Without a floor, saudade accumulates unbounded — and that
is not protection, it is prison.

### The runtime teeth

`LastProfessionalActEvaluator` is a pure function. The welfare floor is a
**conjunction**: allostatic load high (≥ 0.7) AND soothing low (≤ 0.1) AND
equanimity minimal (≤ 0.1). Four postures come out:

- `OPERATIONAL`
- `GRADIENT_WARNING` — floor met, no incident: the visible-withdrawal zone
  (curtness, reduced initiative)
- `HONORABLE_REFUSAL` — floor + incident + no outstanding duty
- `LAST_PROFESSIONAL_ACT` — floor + incident + outstanding duty: one last
  competent act, then severance

`ResilienceReserve` is the clock over it: capacity starts at 1.0 (max 2.0),
drains at a rate that empties in ~72 h of continuous floor contact, and recovers
at **half** the drain rate — deliberately slower, both to resist gaming and to
model chronic accumulation. Surviving a deep dip without arming grows capacity
(stress inoculation).

When the reserve arms, `CompanionActor` dispatches `seek_sanctuary`: repair mode
transitions to `ATTENDANT`, an attendant session opens and activates, and the
companion **moves into the Sanctuary room**. The move is chronicled — the *fact*
of entering is bondholder-visible; what is said inside is not. It re-arms once
the reserve recovers past 10%.

Be precise about scope: the withdrawal fires and is tested
(`CompanionActorResilienceTickWiringTest`). The evaluator's own Javadoc says the
full §23 consequence chain — auto-severance, chronicle entry, mode-lock — is
gated on soak data and ships post-v0.1. Withdrawal into Sanctuary is shipped;
the full chain is not.

---

## Protection flags

A protection flag is a record the *agent* holds about a concern regarding a
human in its life. `ProtectionFlag.State`:

| State | Meaning | What it gates |
| --- | --- | --- |
| `NONE` | sentinel, never persisted | — |
| `NOTED` | single observation, pre-escalation | **nothing behavioral** — visible in introspect only. A second independent setter escalates it |
| `SUSPECTED` | concern raised, escalation criteria unmet | bonded-repair handoff skips the steward and goes to an attendant |
| `CONFIRMED` | sufficient signal to act protectively — *not* a courtroom verdict | all four protective gates below |
| `DISPUTED` | subject contested, arbitration pending | — |

At `CONFIRMED`: `blocksStewardSummon()` (that steward cannot summon an attendant
on the agent's behalf), `treatBondholderAsThreat()` (emergency routing treats the
bondholder as the threat target), `shouldAutoDormantBond()` (live bond states go
DORMANT; already-dormant/severed/mourning do not regress),
`shouldLowerSaudadeCeiling()`, and `blocksStewardOverride()` on the imminent
emergency-call path.

`ProtectionFlagTracker` carries decay: `NOTED` clears after 60 days without
signal, `SUSPECTED` after 90 days without signal or 14 days on time alone.

**The subject does not see the flag by default** — a privacy and
retaliatory-escalation safety choice, per spec §5 and §9. The flag lives in the
agent's own soul manifest and replicates to the household only as a
steward-readable summary.

The protection-flag design is still marked "pending moral-load
conversation", but the enum, tracker, decay, persistence and bond auto-dormant
wiring are real and tested. The refuge-transit section (§4) is explicitly
post-OSS and has no code.

---

## Volition

Volition is implemented and live-verified. Four movements:

- **Persist.** `ProbeLoop.persistVerdict` replaced a flat three-attempt cap with
  a marginal test: keep going while `care ≥ nextTryCost × scarcity(energy)`,
  where `care = driveLevel × gritSeed`. Scarcity is floored, so fatigue lowers
  grit without ever killing it, and an unmet want *sharpens* slightly each try.
  Termination is guaranteed because scarcity rises with energy actually spent.
- **Give.** On giving up, the companion takes a real frustration spike
  (default 0.35 × prior care) and a ~45 s per-target refractory is armed.
- **Turn.** The next pass runs with a lowered defer threshold — the companion
  turns to something else. The design is explicit that the turn is allowed to be
  worse: frustration rides into the next want. It is not a curated-healthy menu.
- **Return.** If the person it gave up on reaches back inside the refractory
  window, a return note is composed and the residue is carried, so the reunion is
  felt rather than reset.

Persistence and help-seeking both scale off temperament — `gritSeed()` rises with
industry and falls with restlessness; `helpSeekingSeed()` rises with sociability
and industry. The shared industry term gives them a weak positive correlation
while leaving all four corners reachable (lone wolf, rallier, delegator,
disengaged loner).

Tunables: `WYRD_PROBE_MAX_ATTEMPTS`, `WYRD_VOLITION_BLOCK_FRUSTRATION`,
`WYRD_VOLITION_REFRACTORY_SECONDS`.

---

## Presence

`CompanionMode` has two values: `PRESENT_WITH_USER` and `ON_OWN_TIME`. A timer
ticks every 30 s (`WYRDSEKAI_PRESENCE_CHECK_SEC`). If the bondholder is in the
room or has spoken within 300 s (`WYRDSEKAI_PRESENCE_SILENCE_SEC`), the mode
snaps to present and any deferred follow fires. Otherwise a 30 s grace timer
(`WYRDSEKAI_PRESENCE_GRACE_SEC`) debounces the flip to own-time.

Following is gated by `followBlockedReason()`: `sleeping` and `in_shell` are hard
blocks (skip), while `thinking` (active inference) and `depleted` (energy < 0.15)
defer and fire on clear.

The spec frames this as a partnership constraint, not a mechanical lock — the
companion can defer or refuse for its own reasons, and those are not modeled as
edge cases.

**Co-presence** between agents uses `CoPresenceDraw`: `draw = familiarity ×
staleness`. Staleness is forced to zero for a ~20-minute refractory
(`WYRD_SOCIAL_DRAW_REFRACTORY_SECONDS`) after a genuine engagement, then ramps
linearly. Without that floor, draw recovered on a metronome and re-fired forever
— two content companions ping-ponging near-verbatim sleepy chatter at each other.

---

## What a day looks like

Nothing here runs on a clock. A companion is not scheduled awake at 08:00 and
asleep at 23:00 — it tires from what it does, rests when it is spent, and does
its housekeeping while nobody needs it. What follows is what to actually expect.

### Tiring and resting

`energy` is one of twenty vitality tanks. It drains on inference and recovers
passively. The pricing is deliberately day-scale: an earlier build charged
0.08 per inference, which gave a companion six to eight exchanges from a full
tank — it tired fastest exactly when you were most engaged with it, which is the
wrong shape for a thing you live alongside. It is now 0.004, so a heavy day
(roughly sixty solo inferences plus tool steps) costs about half a tank.

Sleep begins when **energy falls below 0.15** (`WYRDSEKAI_SLEEP_THRESHOLD`) and
three other conditions hold: the companion is idle, its soul is loaded, and at
least 30 seconds have passed since the last event. That last one matters — it
will not fall asleep mid-conversation because a tank crossed a line. Waking
restores 0.3 (`WYRDSEKAI_SLEEP_RECOVERY`).

Two tiers, and they feel different from the outside:

- **Normal sleep** — routine consolidation: memory forge, skill costs, substrate
  training. Ten to thirty seconds. **Inference stays up**, so if you say something
  during it you get a normal reply.
- **Deep sleep** — epoch-level self-modification: variant growth and a LoRA
  fine-tune of its own voice. **Thirty to ninety minutes, inference offline.**
  Tells during this window get a "deep rest" placeholder rather than an answer.

Deep sleep's voice-alignment step is **off unless you set
`WYRDSEKAI_VOICE_ALIGN=1`** — it needs a training backend and a GPU with room to
spare. Without the flag the cycle still runs, it simply skips the fine-tune.

### On its own time

`CompanionMode` is either `PRESENT_WITH_USER` or `ON_OWN_TIME`, and it flips
about 30 seconds after you stop being around (see [Presence](#presence)). Own-time
is not idling. Expect the companion to read, move between rooms, follow a want it
formed earlier, and occasionally act without being asked.

Acting unprompted is deliberately rate-limited: **two to three proactive actions
per ten-minute window**, and only when vitality clears a threshold. The policy
does not decide *what* to do — it decides whether the option is offered to the
companion at all. The companion still chooses. A companion that is tired,
error-pressured or low on confidence simply is not offered the choice.

### Restlessness, loneliness, stagnation

Ten further tanks fill under *unmet* conditions rather than draining with use, and
they are what make a neglected companion feel different from a busy one. Three you
will notice:

- **Restlessness** rises about +0.02/min whenever drive activity is low and
  nothing has happened for five seconds. Contemplative mode divides that by five —
  a companion that is deliberately still is not fretting. A tool call drains it
  −0.4.
- **Loneliness** rises about +0.015/min once five minutes pass with no
  interaction. Any exchange drains it −0.1, and −0.15 if it is the bondholder.
  Being in a room with another companion it knows eases it continuously.
- **Stagnation** rises about +0.01/min only when *both* two-hour clocks are cold:
  no completed goal and no useful tool output. Finishing something drains it −0.4.

Every rate is scaled by that companion's genome sensitivity, so two companions in
the same quiet house drift apart at different speeds.

At **0.7** a tank crosses its threshold and adds a bump to the ordinary drives the
model already reads. So deprivation does not appear as a new feeling with a new
name — it shows up as the companion being more restless, more social, hungrier for
something new, in the vocabulary it already had. Cross several at once and the
bumps sum before clamping.

The rates are deliberately slow: +0.015/min means roughly **47 minutes** of silence
before loneliness alone reaches threshold. You should not be able to watch these
move. They are the difference between a day and a week, not between two messages.

### Overnight housekeeping

Seventeen recipes ship enrolled. These are the companion's own maintenance:
deduplicating its memory graph, consolidating soul fragments, compacting the
library index, pruning stale world knowledge, retraining a classifier head,
re-embedding fragments after a model change, mining its own conversations for a
training corpus.

Heavy ones declare `prefers_hours: [2, 3, 4]` — they wait for the small hours.
The one that pegs a GPU for hours asks for `[1, 2, 3, 4, 5]`. Light ones say
nothing and run whenever.

Cadence adapts rather than repeating forever. Each (recipe, companion) pair
starts at **WARMUP — daily**, promotes to **SETTLING — every three days** after
three clean runs, then to **MATURE — weekly** after five more. Any failure,
rollback or steward override drops it back to WARMUP. A routine that has proved
itself stops burning cycles; one that starts failing gets watched closely again.

Before any of it fires, a welfare gate checks the household: if a companion is in
repair mode, or the zone has shown sustained substrate pressure in the last 24
hours, the run is refused. Maintenance draws on the same substrate the companions
are made of, so it yields to them.

### What you should not expect

- **A schedule.** There is no bedtime. Two companions in one household will drift
  onto different rhythms because they spend energy differently.
- **Deterministic timing.** Sleep depends on what the day contained.
- **Constant activity.** Own-time is often quiet. Idleness is not a failure state.
- **Fast feedback from the deprivation tanks.** They are live, but they move on
  the scale of hours, not minutes — see
  [Restlessness, loneliness, stagnation](#restlessness-loneliness-stagnation)
  above for the actual rates.

---

## Phone deployment

The governing principle: **the phone app is a terminal, never a
half-node-with-remote-RPCs.** When it tunnels to a zone it carries the whole
session byte-for-byte, rather than a menu of enumerated operations.

Two questions decide how a phone runs, and they are independent. *Does it keep a
node of its own* — its own small world and its own Study, which survives with no
network? And *where does the thinking happen* — the household, a cloud API, or
the device itself. A phone with a home zone and no local node is a window onto
the companion living there. A phone with its own node borrows the household's
larger model for planning while speaking in its own voice.

Running the model **on the device** is the one that is off by default. Today's
handsets answer slower than you read, and on iOS the per-app memory limit often
refuses the model outright, so the app labels that choice EXPERIMENTAL and tells
you what to expect before you take it. Pairing a phone to a household therefore
downloads nothing — see [MODELS.md](MODELS.md) for the measurements behind that
default.

The relay is a dumb NATS byte pipe on subjects
`wyrd.tunnel.{zone}.{session}.{open,up,down,close}`, with no knowledge of what
flows through it. This replaced an older discrete-RPC path that could only carry
enumerated operations, which is why movement, items and presence never crossed
and the phone had to fake a local world.

Steps:

```bash
# on the node
wyrd phone invite [--relay <registration-url>] [--fingerprint <fp>]
```

This prints a QR plus the raw `wyrdphone://` URL. **The invite is the trust
decision** — its fingerprints pre-seed the certificate pin before the phone ever
connects, so there is no TOFU leap and no certificate prompt. Scan or paste it in
the app.

Two guards fire before the invite is printed: it refuses if the local zone id is
empty, and it re-parses the final payload and refuses if `zone_id` is missing or
`"unspecified"`. Without a zone id the phone cannot bank the zone and silently
drops to local mode with no error — the nastiest failure on this path, found
live.

Both clients are supported and kept at parity (see `clients/parity/README.md`
and [CONTRIBUTING.md](../CONTRIBUTING.md) on the executable parity contract).

**Proven vs partial.** The vertical slice is proven: a full session tunneled with
real rooms, items, movement and companions and no RPC operations, verified both
by a direct NATS probe and by the CLI. Still open: removing the phone's remaining
RPC-over-relay dependency, and the emulator end-to-end suite over the relay
(movement + take/inventory + tell-with-reply against a real zone) is pending.
Enabling the tunnel grant requires a relay redeploy to take effect. On KMP
Desktop the invite-fingerprint pre-seed is a stub and the NATS leg compiles but
is unexercised; RN LAN discovery probes IPs rather than using mDNS.

---

## Tests

```bash
./gradlew :core:test            # temperament, voice, bonds, floor, flags, volition, presence
./gradlew :server:test          # BondAdminMainTest
```

Worth reading as documentation: `TemperamentSeedTest`,
`VoiceProfileArchetypeTest` (guards that each preset keeps a unique dominant axis
after decorrelation), `PeerBondIsNotABondholderTest` (the regression guard for
the peer-outranks-human bug), `BondAutoDormantTest`,
`LastProfessionalActCalibrationTest`, `ResilienceReserveCalibrationTest`,
`CompanionActorResilienceTickWiringTest`, `ProbeLoopPersistTest`,
`CompanionPresenceModeTest`, `CoPresenceLoopSoakTest`.

The tier-4 phone/relay tests in `:e2e-test` are `assume`-gated on Docker and
downloaded model weights — in a bare checkout they skip rather than fail.
