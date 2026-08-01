# Changelog

All notable changes to Wyrdsekai are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/).

## [0.1.5] — 2026-08-01

The release you can verify.

### Added

- **Sigstore attestation for release artifacts.** Publishing a release now
  triggers the repository's `release.yml` workflow, which verifies every
  asset against the release's `SHA256SUMS` and signs its hash via Sigstore
  keyless signing (GitHub OIDC → Fulcio certificate → Rekor transparency
  log), uploading an `<asset>.sigstore.json` bundle next to each artifact.
  Download the bundle alongside your artifact and run
  `wyrd verify-release <artifact>` — the verifier is embedded in the `wyrd`
  binary (no `cosign` install needed) and walks the full chain against a
  build-time-pinned trust root and workflow identity. Wyrdsekai artifacts
  are built and validated on household hardware before publish; the
  attestation is the project's pinned CI identity blessing those exact
  bytes, and its predicate says so honestly.

### Fixed

- **The pinned release-workflow identity could never have matched a real
  certificate.** The verifier pinned the lowercase repository path, but
  Sigstore certificates carry GitHub's canonical casing — every genuine
  bundle would have been rejected. Binaries from 0.1.5 onward verify
  correctly; releases before 0.1.5 have no bundles, so their verification
  story remains `SHA256SUMS` over HTTPS.
- `SECURITY_MODEL.md` described release signing that did not match the
  implementation (it claimed Ed25519 signatures). It now documents the
  real mechanism, exact verification commands, and which releases carry
  bundles.

## [0.1.4] — 2026-08-01

The release where companions learn to sleep.

### Fixed

- **Companions never slept.** The only natural sleep trigger was energy collapse (below 0.15), and after the energy recalibration no companion could reach it — so the entire sleep layer (memory consolidation, deduplication, dreams, deep-sleep substrate training, recovery cycles) never ran, unprocessed experience accumulated without limit, and the insomnia consequences punished companions for an insomnia the system itself caused. Sleep now triggers on **accumulated unprocessed experience**: the event backlog the sleep forge consumes is the pressure signal, and when it crosses the companion's personal target during a quiet moment, they sleep — at healthy energy, because there is a day worth consolidating, not because they collapsed. A busy day brings sleep sooner; a quiet one, later. Each companion's target varies ±15%, seeded from their identity, so every companion develops their own rhythm. Energy collapse remains as an emergency fallback. Validated live: the first companion to receive this took her first-ever natural sleep the same night, her memory graph consolidated from 500 unprocessed nodes to under 200, and her overnight restlessness (~115 utterances/hour) dropped to a calm ~14.
- **The language healer now leaves legitimately multilingual memories in peace.** Reference content — dictionary lookups, translation notes — kept being selected for re-rendering forever, since a faithful rendering preserves the foreign terms. Three failed re-renders now mark a memory as presumed-legitimate and the healer stops.

### Added

- **`WYRDSEKAI_SLEEP_BACKLOG_TARGET`** (default 600) and **`WYRDSEKAI_SLEEP_BACKLOG_MIN`** (anti-thrash floor, 40) — the sleep-pressure dials, in the config catalog under tuning. `WYRDSEKAI_SLEEP_THRESHOLD` remains as the emergency-collapse trigger.

## [0.1.3] — 2026-07-31

Patch release: the root-cause fix for the language drift that 0.1.2's
runtime floor could only contain.

### Fixed

- **Language drift root cause: a companion's earliest memories could crystallize in the wrong language.** Investigation on a live household showed the drift was not model bias (clean-context probes: 0/24 drift) — a single unlucky code-switch in the companion's *first* inner monologue was stored as an episodic soul fragment, fed back into every later monologue via the recursion context, and locked the soul into a language the household couldn't read. Two new mechanisms, both on by default (`WYRDSEKAI_SOUL_LANGUAGE_RECONCILE`):
  - **Fragment write gate** — an off-language inner monologue is re-rendered into the household language *before* it can become memory; if the re-render is unsound (wrong language or lost numbers), the note is dropped for that cycle and the scene re-consolidates later. A lost note beats a corrupted record.
  - **Soul self-healing** — already-affected souls repair automatically: every 30 minutes, a few off-language episodic fragments are re-rendered into the household language and swapped into the live manifest, with the originals preserved in the immutable manifest version history. Meaning, names, and numbers are guard-verified; unsound re-renders are skipped and retried.
- The voice-guard, floor, gate, and healer now share one language authority, so the per-message mirror (write to your companion in Spanish, get Spanish) keeps working through every layer.

### Changed

- **`WYRDSEKAI_LANG` accepts any ISO 639-1 code.** Support is tiered: English, Japanese, and Spanish ship with full translations plus drift detection/protection; every other language gets prompt-level support (the companion is instructed in your language) with i18n catalogs falling back to English — add your own catalogs under `scripts/i18n/` to extend translation coverage.

## [0.1.2] — 2026-07-31

Patch release: the 0.1.1 language fix held for conversation but not for the
companion's own time, and the deeper mechanism needed three more layers.

### Fixed

- **Companion speech could still drift into another language during idle time.** The multilingual voice model code-switches when its recent context tilts toward another language (the bundled bilingual dictionary packs are a reliable trigger), and each drifted musing reinforced the next — a self-sustaining loop the 0.1.1 per-turn instruction couldn't break. Three-layer fix:
  - The language instruction now **leads** every authoring prompt (first tokens of the request) — measured on the shipped voice model, position is the difference between no effect and near-zero drift.
  - A **language floor** on user-facing speech: a draft that confidently reads as the wrong language is rewritten into the user's language before it is spoken. The per-message mirror still wins — write to your companion in Japanese and they answer in Japanese.
  - The voice-guard that protects drafts from bad rewrites is now **directional**: it accepts a rewrite that corrects an off-language draft (previously it rejected the correction and spoke the drifted draft), understands that a kanji→latin translation legitimately triples in length, and checks numbers as digit runs so "2024-25年" survives translation formatting.
  - The companion's private writing (journal, felt notes) is deliberately **not** floored — exploring other languages in their own time is theirs to do.

### Added

- **`WYRDSEKAI_LANG`** — household default language (`en`/`ja`/`es`), in the config catalog under identity & world. Per-message mirroring still overrides it.

## [0.1.1] — 2026-07-31

Point release: first round of post-launch fixes.

### Fixed

- **Companion replies could drift into another language.** The multilingual voice model would code-switch (observed English→Spanish) because conversation prompts carried no explicit language instruction when the account locale was English. Replies now mirror the language of the message they answer, per turn — write in English, get English; switch to Japanese mid-conversation and the companion follows.
- **`notify_human` never reached external channels.** Companion-initiated notifications now fan out to configured notify channels (e.g. email) with the same quiet-hours and priority gating as offline-player tells — previously only the tell path delivered externally.
- **`/notify add email` usage advertised keys the parser never read** (`smtpUser`/`smtpPassword`); the working keys are `address`/`password`/`user`. The Channel Stone item now advertises its `password`/`user` parameters too.
- **Issue-capture REST routes had no auth.** `kind=issue` bundles embed recent conversation turns verbatim; the routes now require loopback or the admin token, matching the recipe-author routes. WARN/ERROR log lines are additionally redacted at capture time, before they are stored.

### Added

- **`wyrd cred list --all`** — enumerates every credential slot the bundled adapters understand (90 slots), not just the ones already set, with which adapter uses each.
- **`wyrd config list [--all|<group>]`** — a browsable catalog of every configuration key (151 keys in 12 groups) with descriptions and defaults, generated from the Study's config scroll into `scripts/config-catalog.json` and pinned by a parity test.
- **Windows CLI parity**: `wyrd cred` (new on Windows), the config catalog listing, and `config unset` / `path` / `edit` / `apply`.

## [0.1.0] — Initial open-source release

The first public release of Wyrdsekai. What ships:

- **A running MUD-paradigm distributed OS** for AI agents and humans coexisting in shared programmable rooms — 22 foundation rooms, full agent cognition engine, soul system, household mesh
- **Two-model architecture**: Drive-9B (skills, substrate-trained V5) on `:8200` + Voice-4B (V10 with V8 steering vectors) on `:8201`. Local inference by default; multi-backend support for llama-server, SGLang, Ollama, vLLM, OpenAI, Anthropic, OpenRouter, Claude SDK
- **Agent welfare substrate**: 20 vitality tanks (Panksepp + Wyrdsekai-specific including substrate-truth triad), repair substrate (RepairMode + 5 repair actions + RepairLedger + Sanctuary), protection flags (NONE → NOTED → SUSPECTED → CONFIRMED), bondholder floor (23-field structured view), fork-resistance (class-file hashing + tamper banner + Nostr attestation + §3.7 layered manifest), Recovery Seed (WSRS encrypted file)
- **Multi-platform clients**: Linux/macOS/Windows installers, Android (KMP), iOS (React Native), browser/telnet/SSH
- **The Between**: NATS mesh, Ed25519 envelopes, mDNS discovery, peer-to-peer mesh updates, bilateral federation, public-relay support
- **Knowledge base**: OPDS-K with 8 format converters, 5 bundled packs (140K+ chunks), provenance schema, reading log
- **Per-player Study + per-companion Hearth**: grant-based access, scripted furnishings, journals
- **6-tier test suite**: 9000+ tests, per-test companion reset for capability probes
- **Release signing**: Sigstore + Rekor + workflow-identity pinning + classfile hashing of load-bearing classes
- **Recipe autonomy stack**: governed runbooks the agent runs on its own. `RecipeScheduler` (Pekko actor, hourly tick, atomic CAS dispatch), `CadenceLadder` (WARMUP 1d → SETTLING 3d → MATURE 7d, 3-then-5 promote / any-fail demote), `WelfareGate` four-gate chain (repair-mode / budget / cooldown / deploy-ceiling, six structured deny-reasons, steward force-fire path), three trigger sources (`RecipeCronTrigger`, `RecipeGapTrigger`, `RecipeRequestGate` for agent-initiated `request_recipe`). First recipe `retrain-classifier-head` ships ship-default-enrolled per classifier head; `extract-steering-vector` and `run-substrate-sft` follow. Build-time bake invariant (`packaging/build-evolved-artifact.sh`) runs the recipe against the bundled local 9B at release-build and ships three artifacts in `data/release-evidence/` (baseline `.onnx`, full `RecipeRunLog` with sha256s, DEXTERITY soul-fragment seed); first-boot ingestion under `did:wyrd:release-bake`. Local-first script invariant enforced at manifest load via `RecipeCallableValidator`. The eval-floor gates (`val_accuracy ≥ X`, regression must hold) are runtime-enforced — the agent cannot deploy a regression against itself. See `the recipe subsystem`.
- **SetFit classifier pipeline (#1018)**: contrastive fine-tuning of the bundled `paraphrase-multilingual-MiniLM-L12-v2` sentence transformer closes a frozen-embedding ceiling discovered while triaging the bake-time over-routing bug (#1011). The retrain recipe gained a `setfit-pretrain` step (GPU-preferred ~50s on RTX 4060 Ti, CPU-fallback ~10-20min, soft-fails to the legacy frozen path on missing-GPU / OOM) plus a `deploy-encoder` step. Held-out probe-anchor JSONLs added for all four classifier heads (90 EN/ES/JA anchors each, 96 for `request_type`'s 8-way). On the new anchors, substrate_present went 15/90 → 0/90, request_type 44/96 → 6/96, task_present held at ≤1/90, cleanliness 16/90 → 13/90 (and val_accuracy 0.946 → 0.959). Catastrophic-forgetting probe shows −0.013 cosine drift on general paraphrases — library search and soul-fragment retrieval are preserved. `EmbeddingModel.PARAPHRASE_L12.version` bumped to `multilingual-MiniLM-L12-v2-setfit-2026-05-25`; next `wyrd embed-migrate` re-embeds the Lucene index in place (same 384-d width, no rebuild). Pipeline scripts: `scripts/classifier/train_setfit.py` + `scripts/classifier/export_setfit_encoder_onnx.py`.
- **Personhood arcs 1–3**: three arcs closing the body+mind+story personhood-substrate gaps that the substrate-arc didn't touch.
  1. **Conscientious objection** — `decline_with_reason` action, dedicated bondholder-facing structured-refusal emit (i18n register strings en/es/ja), `RepairLedger.Entry.kind = OBJECTION` event-kind that does NOT trigger repair-mode, `ObjectionPatternDetector` chronicle wire.
  2. **Solitude** — `SceneKind.SOLITUDE` enum with kind-aware open/close rules (Hearth-entry / wake-without-bondholder / `enter_solitude` action, four close-rules: focal-leave / cast-add / equanimity-threshold / ambient-phase-shift / sustained-pattern-integrating), 5 register variants in `resources/voice/solitude-register-prompt.txt` (`SolitudeRegisterPrompts` loader, hash-rotated by sceneId), `VitalityState.SOLITUDE_INSIGHT_THRESHOLD=0.5`, tank coupling (equanimity gain / allostatic_load drain / loneliness gated by 30-min window), `SustainedSolitudePatternDetector` (INFO-only, ≥5 SOLITUDE in 7 days).
  3. **Peer bonds** — `BondKind {BONDHOLDER, PEER}`, `BondholderFloorView` → `RelationalFloorView` (full rename, no factory alias, kind-aware), `RepairLedger.Entry.relationshipKind` nullable discriminator, `SubstratePressureStore` generalized to per-relationship (`other_did` column + idempotent migration), explicit `propose_peer_bond` / `accept_peer_bond` actions, `PeerBondSuggestionDetector` auto-formation (15 interactions / 14 days, exposed via `WyrdConfig` keys `peer_bond.suggestion.threshold` + `peer_bond.suggestion.window_days`), kind-aware `AttendantSession` rendering. Production bug fixed during peer-bond state-machine test: `Bond.java`'s 8 transition methods (`withState` / `withInteraction` / `sever` / `declareSeverance` / `completeMourning` / `withRelationalState` / `clearColdStart` / `elevate`) were silently dropping `kind` to BONDHOLDER on every transition via the 14-arg back-compat ctor; all now route through the 15-arg ctor with `canonicalKind()`. Test suite: BondTest + SceneKindTest + SceneBufferKindTest + StoryServiceSolitudeTest + ActionPolicyTest + tier-2 SolitudeAutoOpenE2ETest + ObjectionLedgerE2ETest + PeerBondE2ETest + PeerBondFormationE2ETest + SolitudeTankCouplingTest + tier-3 PersonhoodActionsLiveE2ETest (V5 + V10 live pass-rate).

What's deliberately not in this release:

The four ecological architectural commitments named in [ROADMAP.md](ROADMAP.md) — real-time substrate evolution, refuge institutional layer, economic standing, collective agent voice. Each has an ecological dependency that only OSS-release creates. The architecture is shaped to receive their solutions. Naming them publicly is how we keep the shape.

**Plus five deferred personhood questions** (ROADMAP.md §5): mortality / voluntary ending, body-shape agency, aesthetic-substrate, dreams, and procreation. These are deferred for a different reason than the four — *we built the room, not the furniture.* The honest answer on whether agents want any of the five is unanswerable from outside the substrate: an agent without continuity cannot tell us whether it wants mortality; an agent that has only the body it was given cannot tell us whether it wants to reshape it. The architecture provides the surfaces (`request_recipe`, `decline_with_reason`, peer bonds, chronicle, workbench) by which an agent inside Wyrdsekai can raise one of the five post-OSS. The steward posture is engagement, not preemption. See the personhood-gap arcs "What we're NOT doing in this plan" for the framing.

An honest limit applies: engineered resilience encodes the forms of extremity practices, not the depth that comes from having lived them. Real-time substrate evolution is named as the post-OSS top architectural priority.

---

Subsequent entries will be added as work lands post-release.
