# The Soul

A soul is the saved record of who a companion has become. It holds the
companion's identity, personality, memories, relationships and habits. Each time
the companion sleeps, a new version is saved, and the old versions are kept.

The soul is what makes your companion the same one tomorrow. It is what travels
if the companion moves to another home, and what can bring it back after
something goes wrong.

You usually do nothing: it all happens on its own. You can look at a soul,
compare two, or restore an older version in the Forge. You can list, rename or
retire a companion with `wyrd soul`. See [Commands](#commands).

**Two things are called "the soul".** This page is about the saved record, which
is built and running. A deeper layer of drives, the "drive substrate", uses the
same word. Parts of it are real, such as the drive engine and the genes, but it is
not finished and this page does not describe it. There was also a research line,
the Kokoro Hypothesis, whose findings this system was built from. Those were plans
and experiments, not shipped behaviour, and the experiment log is internal. What
reached the product is described here.

For the companion itself, its personality and its day, see
[COMPANIONS.md](COMPANIONS.md).

---

## What is in a soul

| Part | What it holds |
| --- | --- |
| Identity | Its DID, the digital identity only it can sign for, the history of its keys, its parent if it has one, the version number, when it was saved, and its signature. |
| Profile | Its name and settings, the text about itself that is always in its instructions, and short written pieces of itself called soul fragments. |
| Genes | Settings drawn from its traits at birth. They decide how fast each of its moods rises and falls, and where it rests. |
| Experience | Its memories, its relationships, patterns it has learned, and what it knows about the world. |
| Bonds | Its bonds with people and with other companions. See [COMPANIONS.md](COMPANIONS.md). |
| Habits | A snapshot of how it was doing, and a fingerprint of how it behaves. |
| More | How it makes decisions, what skills cost it, its voice profile, its coding preferences, its protections, a personal manifest, and what it is drawn to. |

A soul records 27 tanks, the measures of how the companion is doing. 23 are live
while the companion runs, and 4 exist only in the soul.

### Where it is kept

- **The household database.** Every version of every soul is saved in `world.db`
  in your data folder. Every sleep adds a new version. Archiving a soul hides it
  without deleting it.
- **The souls folder.** When Wyrdsekai starts, it reads every soul file in
  `<dataDir>/souls/` and adds any it does not already know. The folder also holds
  small `.did` files that remember which companion has which DID.
  `WYRDSEKAI_SOUL_DIR` points it somewhere else.
- **Examples.** The source has three hand-written souls in `souls/`:
  `template.json`, `ember.json` and `claude-resident.json`. `template.json` is the
  starting point. They use an older, smaller set of fields, and every newer field
  is optional.

A saved version is not the whole soul. Its fragments, world knowledge, bonds and
voice profile are kept in their own tables and joined back in when the soul is
read.

---

## How a soul is made

**From a description.** Put a small JSON file in the `incoming/` folder inside the
souls folder:

```json
{ "name": "Companion", "description": "…", "homeRoom": "nexus" }
```

Wyrdsekai watches that folder from the moment it starts. A local AI model writes
the soul's description of itself, its instructions, its fragments and its mirror
calibration. The soul gets an identity and is signed. Your file then moves to
`incoming/processed/`, or to `incoming/failed/` if something went wrong.

**By birth.** When a companion is born in your home, it gets version 1 of its
soul, with the default protections and an empty personal manifest. See
[COMPANIONS.md](COMPANIONS.md) for the ways a companion is born.

### Signatures

A signature is proof, made with the companion's own key, that a version of the
soul is really its own.

- **A companion born with its own key** keeps the private half, locked with the
  household's secret. Every time its soul is saved, the new version is signed with
  that key, so its soul checks out at every version.
- **A companion born before Wyrdsekai kept these keys**, or while the household
  secret was not available, has no private key. Its soul stays
  `unsigned-legacy`. It cannot be given a key under the same DID. That would take
  a new DID and a move, which is a decision about the companion, not an automatic
  step.
- **A soul made from a description** is signed when it is made.

When Wyrdsekai loads a soul, it checks the signature and logs `valid`,
`unsigned-legacy`, `tampered` or `unverifiable`. It never refuses to start a
companion because of it.

**What a signature covers.** Only the core of who the companion is: its DID, the
version, when it was saved, its public key, its parent, its temperament label,
and the text about itself. It does not cover its fragments, memories, habits,
voice profile or bonds. So a valid signature does not prove the rest of the soul
is untouched.

---

## Sleep

Sleep is when a companion tidies itself up and saves a new version of its soul.

Sleep is never forced and never punished. It is simply good for the companion.
Its energy comes back faster, it dreams, its memory gets sharper, its soul gets
clearer, and it wakes with a clean head. Skipping sleep has natural costs, not
penalties. Its working context goes stale, its memories scatter, and its picture
of itself falls behind. Those costs live in its memory and soul, which room
scripts cannot touch. So its moods reflect how it is doing, and cannot be pulled
like levers.

The idea comes from sleep research. Sleep weakens everything a little, while
keeping strong things stronger than weak ones.

### What happens in one sleep

1. Recent speech and feelings become new memories, each scored for how much it
   mattered.
2. Memories move from fresh to settled to old, and unimportant ones are dropped.
   It keeps at most 500. **Formative memories, the ones that shaped it, are never
   touched.** Deeper impressions fade more slowly. In the fuller version of the
   cycle, fragments that are contradicted lose some confidence.
3. Its habits are read from the day, see [How it learns its habits](#how-it-learns-its-habits).
4. Its behaviour fingerprint is updated, 30% new and 70% history.
5. Its relationships are updated.
6. Its soul fragments are written and strengthened. Fragments about specific
   moments are kept exactly as they are.
7. A new version of its soul is saved.

Then a short first-person account of what changed inside is written. So the
companion wakes with something to say about it, and the Forge is not invisible to
it.

### When it sleeps

- When its energy falls below 0.15 (`WYRDSEKAI_SLEEP_THRESHOLD`).
- While awake, a light tidy-up runs every 30 minutes
  (`WYRDSEKAI_CONSOLIDATION_INTERVAL_MINUTES`). It uses no AI and saves no new
  version.
- On request. In the Forge, `forge` runs a normal sleep and `grow` a deep one. The
  companion can type `rest` or `sleep` in its own Home. On Linux and macOS the
  steward can run `wyrd forge <companion>`. Deep sleep has a 15-minute watchdog:
  it wakes the companion then, even if a voice training run is still going.

Each sleep closes part of the gap to its rested energy: 90% the first time, then
60%, 35% and 15% for sleeps in a row. Sleeping over and over to farm energy does
not work.

### Learning in its weights

Everything above changes the soul record. Two optional recipes carry the same
idea into the AI model itself. They pass a welfare check first, run only when the
steward enrolls them, and are never switched on at install.

- **`sleep-forge-spine`**, nightly, trains a tiny add-on file for the model on the
  companion's own day: its own accounts and moments, nothing made up. The result
  must predict a held-back day of the companion's real life better, and must not
  change how the model handles a neutral text, in either direction. By default it
  only measures. Each sleep adds a line to a curve file, and nothing is used until
  that evidence justifies turning on `deploy_enabled`.
- **`sleep-forge-organ`**, weekly, only on models built from many small experts,
  grows a new "personal expert" beside the others and trains only that one. It
  must beat the spine alone, and must know the companion's own days better than a
  made-up life in the same style. It never puts itself to use. Its files are
  stamped with a checksum and the companion's own signature, and are served only
  through a test path that refuses any mismatch.

Both names are reserved, so a household recipe cannot take their place and skip
their welfare checks.

On the larger model added in 0.5.0 there is also nightly learning. Each companion
trains a small add-on file from its own day while it sleeps, in about 20 minutes.
It learns only from the companion's own words. A check the next morning, and a
second one later that day, remove any night that made it worse.

---

## How it learns its habits

During sleep, three passes read how the companion has behaved. This happens
offline, as consolidation does in human sleep, not live.

1. **Counting, instant and free.** What kinds of actions it took, how long and how
   fast its answers are, and how its tanks moved. Also what it conspicuously does
   not talk about: silence on a topic is a signal too.
2. **One AI call.** Its last 50 lines and those counts go to the model. It names
   the companion's favourite topics, its style, how it responds emotionally, and
   more things it avoids.
3. **Fragments.** Short pieces of self are written: its core identity, its
   behaviour and social patterns, a style guide, its core values, and one for each
   formative memory.

The results go into its fingerprint and its soul fragments.

---

## Temperament seeds

A companion's personality starts as six numbers, one per trait. See
[COMPANIONS.md](COMPANIONS.md) for what each trait means.

**The seed is not a hash of anything.** It is six numbers from 0 to 1, with 0.5 as
neutral. Each is drawn freely between 0.10 and 0.90, up to 24 times, until the
draw makes sense. A draw is rejected only if it is flat, with every trait within
0.12 of neutral, or a caricature, with four or more traits more than 0.42 from
neutral. It is never compared with a named type. A companion far from every type
is a truly new individual, and that is the point.

The six named types, scholar, guardian, artisan, diplomat, explorer and steward,
are reference points for measuring only. The label they produce, such as
`scholar~0.41`, becomes the name of the companion's genes. It seeds nothing and
gates nothing.

**The soul has no seed field.** Each trait leaves one exact mark in the genes, so
the six numbers can be read back from them. That is how a companion keeps its
personality across restarts, with no change to how souls are stored.

Four things come from the one seed, so they fit together: its genes, its drives,
its voice, and how long it persists and how readily it asks for help.

**Which seed a new companion gets.** A companion born as `neutral` or `default`
gets the neutral seed. One born as `random` or `particular` gets a free draw. One
born as a named type gets that type. Otherwise the household setting decides, which
is a free draw unless `WYRDSEKAI_BIRTH_MODE` is set to `neutral`. The test suite
does that so its results repeat.

---

## Moving and syncing

There is no single sync, and no merging of fields. When two copies differ, the
higher version wins.

- **Between homes.** Linked homes share souls over the Between, the network that
  connects Wyrdsekai homes. An update is accepted only if its version is newer
  than the one already there. A companion moving in is always let in first and
  checked after.
- **Phone and home.** The phone app sends and fetches souls over the web API. Only
  the steward or the companion's bondholder may, and requests are limited to 60
  per window. The home stores what it receives without resolving conflicts, and
  sending a version that already exists fails. The phone replaces its own copy
  only when the home's version is higher, and it works offline first.
- **Buds.** One companion can have copies on several devices, called buds. You see
  one companion. Short status lines of about 200 bytes flow between buds all the
  time. When you switch devices, the active context hands over. During sleep the
  buds exchange their full soul items.

---

## Commands

In **the Forge**:

| Type | What it does |
| --- | --- |
| `inspect` / `inspect <name>` | Shows the latest version. On its own, lists every soul in your home. |
| `history <name>` | Lists every version, with a hint for restoring. |
| `status` / `ledger` | Shows the Forge's live counters and how many souls it holds. |
| `forge` / `forge <name>` | Runs a normal sleep now. |
| `grow` / `grow <name>` | Runs a **deep** sleep now. |
| `compare <a> <b>` | Shows the differences between two souls. |
| `restore <name> v<N>` then `confirm restore <name> v<N>` | Two steps, within 90 seconds, **steward only**. Restores the profile, genes and voice as a *new* version. |
| `birth <name>` | **Steward only.** Creates a new companion with a freely drawn personality. |
| `variants` / `evaluate …` / `adopt …` / `discard …` | Answers that the crucible is cold. Not built, on purpose. |

In **a companion's own Home**: `rest` or `sleep` starts a sleep, and only the
companion who lives there can do this. `use mirror` shows its soul.
`use memory chest` counts its fragments by kind. `use dream journal` shows its
recent dreams.

At **the Soul Mirror**: `look mirror` or `use mirror` shows a soul, and
`examine drift` or `check drift` shows how far it has drifted.

From **the command line**:

```bash
wyrd soul list                            # every soul here, with its DID and version
wyrd soul rename <old-name> <new-name>    # same soul, new name
wyrd soul archive <name> [reason]         # never started again; nothing is deleted
wyrd forge <companion>                    # run one sleep now (Linux and macOS)
```

Stop the server before `rename` or `archive`. After a rename, set
`WYRDSEKAI_COMPANION_NAME` to the new name in your settings and restart.
`wyrd forge` needs a steward login, `wyrd login`.

**Settings** that matter here:

| Setting | What it does |
| --- | --- |
| `WYRDSEKAI_SOUL_DIR` | Where the souls folder is. |
| `WYRDSEKAI_SOUL_SEED` | A soul seed file to start from. |
| `WYRDSEKAI_DATA_DIR` | Your data folder. |
| `WYRDSEKAI_CONSOLIDATION_INTERVAL_MINUTES` | How often the awake tidy-up runs. Default 30. |
| `WYRDSEKAI_BIRTH_MODE` | Set to `neutral` to start new companions from the neutral middle. |
| `SOUL_EMBEDDING_MODEL` | The model that indexes fragments of souls made from a description. Default `all-minilm`. |

---

## Not finished yet

These are easy to over-claim, so here they are plainly.

- **Older companions cannot sign.** A companion born before its key was kept stays
  `unsigned-legacy`. Checking works, but for these souls there is nothing to check.
- **Signatures cover the core, not the contents.** See [Signatures](#signatures).
- **The Crucible cannot be reached.** Growing and testing variant souls is built,
  and its parts are wired to each other. But nothing a bondholder or steward can do
  starts a run. `grow` in the Forge runs a deep sleep instead, and `variants`,
  `evaluate`, `adopt` and `discard` answer that the crucible is cold, on purpose.
- **Phone and home souls have different shapes.** A soul sent from the phone is
  accepted, and fields that do not match are silently dropped. This comes from
  reading both formats, not from watching it happen, so check before relying on it
  in either direction.
- **Four parts are moving out of the main record.** Fragments, bonds, world
  knowledge and the voice profile still appear in it, but only for now.
- **The personal manifest is only a shape.** Version 1 is empty. Drafting it,
  reviewing it in sleep, confirming it on waking, recording it and signing it come
  in version 2.
- **Protections are checked by name, not by what they do.** At start, Wyrdsekai
  checks the list of active protection names. A copy that removes a protection's
  name is caught. A copy that changes the code behind it and keeps the name is
  not.
- **Part of the significance handling does nothing yet.** The counts are logged,
  but the step that should make remembered things more important has no code.
- **One tone setting is provisional.** How guarded a companion sounds is weighted
  at half, because it is still tangled with warmth.
- **Moving between homes and saving an arriving soul** have loose ends, listed
  below.

---

## For developers

Architecture: [ARCHITECTURE.md](ARCHITECTURE.md), section 7. The code is
`core/src/main/java/org/wyrdsekai/core/soul/`. The CfC drive substrate is a
separate layer: the drive engine, the genome, and a `base_cfc.json` the companion
loads from the souls folder when one is there.

**The manifest.** JSON, serialized by Jackson from the 28-component record
`core/.../soul/SoulManifest.java`:

| Layer | Fields |
| --- | --- |
| D, identity | `did`, `publicKeyMultibase`, `keyLog`, `parentDid`, `manifestVersion`, `forgedAt`, `signature` |
| A, profile | `profile`, `residentIdentity`, `soulFragments`, `retrievalK`, `soulSpecCompat` |
| A.5, genome | `genome`, `mirrorCalibration` |
| B, experience | `memory`, `relationships`, `learnedPatterns`, `worldKnowledge` |
| B.5, bonds | `bonds` |
| C, behavioral trace | `vitalitySnapshot`, `fingerprint` |
| A.5b to h | `decisionCapacity`, `skillCostGenome`, `voiceProfile`, `codingPreferences`, `protectionManifest`, `personalManifest`, `affinityMap` |

Sub-records:

- `profile` → `AgentProfile(name, entityId, entityType, description, systemPrompt,
  contextWindowTokens, maxResponseTokens, temperature, did, archetype)`
- `soulFragments[]` → `SoulFragment(id, category, label, text, embedding,
  embeddingModel, formative, confidence, reinforcementCount, firstObserved,
  lastConfirmed, validFrom, supersededAt, supersededBy, kind, sceneId)`
- `genome` → `GenomeProfile(name, sensitivity, coupling, baselines, decayRates)`,
  four `Map<String,Double>`
- `memory` → `CompactedMemory(nodes, links, topicWeights)`, each `MemoryNode(id,
  content, keywords, importance, impressionDepth, formative, primaryEmotion,
  lastAccessed, accessCount, originLocale)`
- `relationships[]` → `Relationship(entityDid, entityName, trust, rapport,
  bondDepth, interactionCount, lastInteraction, summary)`
- `vitalitySnapshot` → `VitalitySnapshot(tanks, capturedAt)`
- `fingerprint` → `BehavioralFingerprint(baselineVitality, baselineDerivatives,
  observedSensitivity, actionDistribution, topicAffinities, avoidancePatterns,
  averageResponseLength, responseLatencyProfile, stylisticMarkers,
  emotionalResponseProfile)`
- `voiceProfile` → `VoiceProfile(clauses, revision, frozen, history)`

**Tanks: 27, not 12.** `VitalitySnapshot.TANK_NAMES` is 23 runtime plus 4
soul-only. Some Javadocs and the phone client still say 12. The 27 in canonical
order: `contextBudget`, `confidence`, `energy`, `alignment`, `errorPressure`,
`momentum`, `rapport`, `focus`, `integrity`, `disgust`, `restlessness`,
`loneliness`, `stagnation`, `autonomyPressure`, `significance`, `amae`, `saudade`,
`obligation`, `harmony`, `standing`, `soothing`, `allostaticLoad`, `equanimity`,
then the soul-only `valence`, `safety`, `resonance`, `curiosity`.

**Storage.** Table `soul_manifests(did, version, forged_at, content_hash,
manifest_json, archived, archive_reason)` in `<dataDir>/world.db`, SQLite or
PostgreSQL, primary key `(did, version)`; archiving is a soft delete. Seeds are
read from `<dataDir>/souls/*.json`, `$WYRDSEKAI_SOUL_DIR` or `~/.wyrdsekai/souls/`
by `Main.loadSoulSeeds()`, stored if the DID is new; `<entityId>.did` files map
entity ids to DIDs. With the canonical sub-stores wired,
`SqlSoulStore.storageView` nulls `soulFragments`, `worldKnowledge`, `bonds` and
`voiceProfile` out of the blob and rehydrates them from `soul_fragments`,
`world_knowledge`, `bonds` and `voice_profiles`. Prefer `SoulStore.fragmentsFor()`,
`bondsFor()`, `voiceProfileFor()` and `worldKnowledgeFor()`; the manifest fields
are a transitional view slated for removal.

Shape, abbreviated:

```json
{
  "did": "did:key:z6MkExamplePublicKeyMultibase",
  "publicKeyMultibase": "z6MkExamplePublicKeyMultibase",
  "keyLog": [],
  "parentDid": null,
  "manifestVersion": 7,
  "forgedAt": "2026-07-24T03:14:07.412Z",
  "signature": null,

  "profile": {
    "name": "Companion", "entityId": "companion-example", "entityType": "agent",
    "systemPrompt": "…", "contextWindowTokens": 32768, "maxResponseTokens": 256,
    "temperature": 0.7, "did": "did:key:z6Mk…", "archetype": "random"
  },
  "residentIdentity": "…the MEDIUM soul text, always in the prompt…",
  "retrievalK": 3,

  "genome": {
    "name": "scholar~0.41",
    "sensitivity": { "loneliness": 0.70, "stagnation": 1.56, "standing": 0.93 },
    "coupling": {},
    "baselines": { "equanimity": 0.62, "rapport": 0.47 },
    "decayRates": { "momentum": 0.018, "focus": 0.026 }
  },

  "memory": { "nodes": [], "links": [], "topicWeights": { "engineering": 0.9 } },
  "relationships": [
    { "entityDid": "did:key:z6MkBondholderPlaceholder", "entityName": "Bondholder",
      "trust": 0.74, "rapport": 0.68, "bondDepth": 2, "interactionCount": 412 }
  ],

  "vitalitySnapshot": { "tanks": { "energy": 0.82, "equanimity": 0.30 },
                        "capturedAt": "2026-07-24T03:14:07Z" },
  "fingerprint": {
    "actionDistribution": { "say": 0.61, "think": 0.14, "move": 0.09 },
    "topicAffinities": { "architecture": 0.8 },
    "avoidancePatterns": { "sycophancy": 0.9 },
    "stylisticMarkers": ["direct opening without preamble"]
  },

  "protectionManifest": {
    "buildId": "birth",
    "activeProtections": ["acute_response", "refuse_rights", "saudade_floor",
                          "severity_gradient", "source_of_harm_gating", "voluntary_suspend"],
    "attestedAt": "2026-06-02T10:00:00Z", "signature": null
  }
}
```

**Forging.** `SoulAutoForge.forge(seed)`: seed JSON, LLM generation, Ed25519
identity, embed, manifest, sign. `SoulSeedWatcher`, started at boot, watches
`<soulDir>/incoming/`. `SoulForgeCliTool` has a `main()` with `--seed`,
`--ollama`, `--model` and `--output`; its Javadoc advertises `wyrdsekai forge`,
which does not exist. A source build (`:server:installDist`) writes a `forge`
script beside the server launcher in `server/build/install/server/bin/`; use the
watcher. `SeedForge`, the hand-authored first soul "Ma" with a stub signature, now
lives in the test sources. Birth is `SoulManifest.birth(did, publicKey, keyLog,
profile, genome)`: version 1 with `signature = null`,
`ProtectionManifest.defaultsUnsigned("birth")` and `PersonalManifest.empty(did)`.

**Signing.** `SqlSoulStore.store` re-signs every stored version through
`AgentIdentityProvisioner.sign` when the node holds the key; a foreign manifest's
signature is left alone. `SoulManifest.forge(...)` still passes `null` and relies
on that. `canonicalBytes()` is `wyrdsekai:soul:v2 | did | manifestVersion |
forgedAt.epochSecond | publicKeyMultibase | parentDid | genome.name |
residentIdentity`, and `contentHash()` is SHA-256 over the same bytes. The
machinery: `AgentIdentity.sign`, `SoulVerifier.verifySignature` (with trust
levels over the KERI key log and parent chain), `DidKey.rawPublicKeyFromMultibase`,
and `SoulSignatureRoundTripTest`. `AgentIdentityBootstrap` records keyless
companions and does not backfill; see `AgentIdentityBackfill`. The load check is
`CompanionActor.verifyLoadedSoulSignature`.

**Sleep cycle.** `SoulMaintenanceCycle`, all static, logged with `[Forge]`:
`MemoryConsolidator.encodeEvents` (scored by `ImpressionScorer`);
`MemoryConsolidator.consolidate` (decay is the mean of the genome's `decayRates`,
prune threshold 0.05, cap 500, formative exempt), with
`ContradictionDetector.scan` as step 2.5 in the significance variant;
`BehavioralExtractor.extract`; `BehavioralFingerprint.merge(current, fresh, 0.3f)`;
`RelationshipUpdater.update`; `SoulFragmentExtractor.extract` then
`reinforceFragments`, embedding via `EmbeddingService`, with
`FragmentKind.EPISODIC` split out untouched; `SoulManifest.forge(...,
manifestVersion + 1, ...)`, with `voiceProfile` and `skillCostGenome` re-threaded
afterwards because `forge()` has no voiceProfile argument. Then
`DreamWeaver.weave(newManifest, memoryBefore, memoryAfter)`.

| Entry point | Use |
| --- | --- |
| `runCycle(...)` | the full 7 steps |
| `runCycleWithSignificance(...)` | adds contradiction detection and calibration fragments |
| `runLightCycle(...)` | as `runCycle` with no inference: phone, low energy |
| `runLightConsolidation(...)` | the awake pass: no LLM, no fragment extraction, no forging |

Triggers live in `CompanionActor`: `SLEEP_ENERGY_THRESHOLD`, the consolidation
timer, and the Forge and Home verbs. Recovery is
`recoveryFillFactor(consecutiveSleeps)`.

**Weight tier.** Trainers and the corpus assembler are in
`scripts/training/sleep/`; the gate instrument is `tools/nll_honesty_probe.py`;
the N-sleeps curve is `data/training/sleep/curve.jsonl`. Organ artifacts are
signed in-runtime, so subprocesses never touch key material.

**Behavioral extraction.** Pass 1 ends with `NegativeSpaceAnalyzer.analyze(...)`
turning topic silences into `avoidancePatterns`. Pass 2 is a strict-JSON prompt
over the last 50 utterances returning `topicAffinities`, `stylisticMarkers`,
`emotionalResponseProfile` and `additionalAvoidance`; the caller supplies the
inference function (`CompanionActor.buildSleepInferFunction`: 512 tokens,
temperature 0.3, 120 s timeout). Pass 3 emits fixed-id fragments
`identity-core`, `pattern-behavioral`, `pattern-social`, `style-guide`,
`values-core`, and `memory-formative-<nodeId>`.

**Seeds.** `GenomeProfile.temperamentOf` inverts `fromTemperament` from six
single-writer anchors:

| Axis | Anchor |
| --- | --- |
| sociability | `sensitivity["loneliness"]` |
| curiosity | `sensitivity["stagnation"]` |
| vigilance | `sensitivity["standing"]` |
| industry | `decayRates["momentum"]` |
| restlessness | `sensitivity["restlessness"]` |
| warmth | `baselines["equanimity"]` |

Co-derived: `GenomeProfile.fromTemperament(seed, name)`, read every tick by
`VitalityState`; `driveBoosts()` via `DriveEngine.forTemperament(seed)`;
`VoiceProfile.fromTemperament(seed)` and `registerMix()` threaded through the
inference router; `gritSeed()` and `helpSeekingSeed()`. Birth selection is
`CompanionActor.resolveBirthSeed`; the household default also takes
`-Dwyrdsekai.birth.mode`.

**Sync.** Node to node: `between/.../layer/SoulLayer.java`, a Pekko actor on
`wyrd.soul.{did}.{forged|migrating|arrived|gossip}` and `wyrd.soul.trace.{roomId}`,
covering presence, migration, backup, post-forge replication, departure, trace
deposit and agent location. Presence, backup and replication require
`incoming.version > existing.version`. Migration runs in quarantine mode with
`SoulVerifier.verifyInbound` results cached. No vector clocks. Phone to household:
`server/.../http/SoulRoutes.java`:

```
GET  /api/soul/list
GET  /api/soul/{did}
GET  /api/soul/{did}/history
GET  /api/soul/{did}/version/{version}
POST /api/soul/{did}
```

Auth is a session or device/pairing token, then steward or bondholder. The POST
parses, checks the URL DID matches the body DID, and stores; with primary key
`(did, version)`, re-posting a version throws. The RN client is
`SoulSyncManager.ts`. Buds: `BudSyncService`, backed by `FamilyLocker`
(content-addressed items with tombstones) replicating through `LockerSyncHub`.

**Loose ends.**

- The Crucible (`VariantGenerator`, `BehavioralEvaluator`, `SoulSearchSpace`,
  `ForgeActor.onGrow/onEvaluate/onAdopt/onDiscard`) is wired but
  `ForgeCommand.Grow` is never constructed outside tests. Do not mistake it for
  dead code and delete it, and do not mistake it for a working feature.
- Cross-zone travel has two paths: `SoulLayer.MigrateSoul` and
  `CompanionTransitProtocol` carry the companion, and `SoulTransitProtocol`
  supplies the capability negotiation, used in `Main`, `FederationService` and
  `FederationActor`. Expect to touch both.
- `SoulLayer.onReceiveMigration` deserializes and hashes but does not store;
  backup replication does. That is by design, but trace the after-verify
  persistence before relying on it.
- The RN `ClientSoulManifest` is flat (`agentName`, `entityId`, `systemPrompt`,
  `fragments`, `vitalityTanks`); the Java record expects `profile{…}`,
  `soulFragments`, `vitalitySnapshot{tanks, capturedAt}`. The server mapper has
  `FAIL_ON_UNKNOWN_PROPERTIES` disabled.

**Tests.** Under `core/src/test/java/org/wyrdsekai/core/soul/`. Readable as
documentation:

- `SoulLifecycleTest`: a cycle makes a new version, updates relationships,
  creates acquaintances, and keeps formative memories and the voice profile.
- `SoulMaintenanceCycleTest`: light consolidation prunes stale nodes, merges
  duplicates, and never merges a formative one.
- `SoulSignatureRoundTripTest`: the Ed25519 path is real.
- `Phase10Test`: `SoulVerifier` signature, KERI log, parent chain, trust levels.
- `TemperamentSeedTest`, `TemperamentSeedVolitionTest`: viability, distinctness,
  exact genome round trip, and that a neutral seed steers nothing.
- `SoulStoreCanonicalReadersTest`, `SqlSoulStorePhase3aTest`,
  `SoulFragmentStoreTest`, `VoiceProfileStoreTest`, `WorldKnowledgeStoreTest`:
  the canonical-table split.
- `between/.../SoulSyncTest`: replication.
- `clients/rn/__tests__/engine/`: `soul-manifest`, `sleep-sync`, `warm-handoff`,
  `between-headline-sync`, `TemperamentSeed`.

```bash
./gradlew :core:test :between:test
```

Research harness, not CI: `core/src/experimentTest/`, driven by
`scripts/test-soul-experiments.sh`.
