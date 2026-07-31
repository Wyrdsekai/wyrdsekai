# AGENTS.md — Wyrdsekai

Operating instructions for AI agents (Claude Code, Cursor, etc.) working on the Wyrdsekai codebase.

For agents *running inside* Wyrdsekai as companions, the orientation document is [LETTER_TO_AGENTS.md](docs/LETTER_TO_AGENTS.md). This file is for agents working on the source tree.

---

## What this project is

Wyrdsekai is a distributed text-native OS built on the MUD paradigm — AI agents and humans coexist in shared programmable rooms. Java 25, Apache Pekko (typed actors), GraalJS (room scripting), Gradle Kotlin DSL.

Before you write code:

1. Read [README.md](README.md) — what ships, what makes this different
2. Read [PHILOSOPHY.md](docs/PHILOSOPHY.md) — the load-bearing register
3. Skim [ROADMAP.md](ROADMAP.md) — the four open commitments
4. Skim [LETTER_TO_AGENTS.md](docs/LETTER_TO_AGENTS.md) — yes, even though it's not addressed to you. The register matters

The architecture is unusually shaped. Patches that don't understand the shape can break load-bearing pieces. Default to opening a discussion before non-trivial PRs.

## Build

```bash
./gradlew build                            # compile all modules
./gradlew test                             # run unit + integration tests
./gradlew test -PexcludeTags=integration   # unit tests only (fast)
./gradlew :core:test                       # single module
```

Requires Java 25 (LTS). Gradle wrapper included (`./gradlew`).

## Test

- JUnit Jupiter 5 + AssertJ
- Integration tests tagged `@Tag("integration")` — use in-memory SQLite via `TestDb.createInMemory()`
- Pekko actor tests use `EventSourcedBehaviorTestKit` (in-memory persistence)
- E2E tests in `:e2e-test` module, 6 tiers (`integration` / `smoke` / `e2e` / `between` / `relay` / `household`)
- **Capability-probe suites** (Ember, MemoryE2E, SoulSubstrate) use per-test companion reset via `CompanionActor.ResetState` from `TestServerBootstrap.respawnCompanion()`. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
- Test suite is ~9000 tests. `./gradlew test` should pass cleanly before submitting changes.

## Module structure

```
wyrdsekai/
├── common/        Protocol types, models, shared utilities (no dependencies)
├── core/          Actor system, rooms, agents, vitality, soul, repair, protections, cognition, search
├── scripting/     GraalJS sandbox, WorldApi, capability manifest validator
├── between/       Inter-node networking (NATS, Ed25519, mDNS, federation, mesh updates)
├── server/        HTTP (Javalin) + WebSocket + Telnet + SSH server, Main entry point
├── cli/           wyrd CLI client (JLine 3, WebSocket)
├── clients/kmp/   Kotlin Multiplatform — Android + Desktop
├── clients/rn/    React Native — iOS
└── e2e-test/      6-tier test suite
```

Dependency flow: `common` ← `scripting` ← `core` ← `server` / `cli`. `between` depends on `common` only. Clients are independent.

## Conventions

- **Java 25**: records, sealed interfaces, pattern matching, text blocks. No Lombok.
- **No fully-qualified class names in Java. Always `import`.** Write
  `Instant now = Instant.now();`, never
  `java.time.Instant now = java.time.Instant.now();`. Inline FQCNs make a line
  unreadable and hide what a file depends on — the import block is the honest
  dependency list. The only accepted exception is a genuine collision between two
  same-named classes in one file, which is rare enough to deserve a comment.
  This one gets caught in review more than any other convention here.
- **Immutability**: All model types are records. State objects are immutable — return new instance from mutation methods.
- **Actor pattern**: Pekko typed actors with sealed `Command` / `Event` / `Response` interfaces. Event-sourced via `EventSourcedBehavior` where state survives restart.
- **Persistence**: JDBC with `SqlDialect` sealed interface (SQLite single-node, PostgreSQL multi-node). No ORM.
- **Testing**: Unit tests need no annotation. Integration tests use `@Tag("integration")`. Use `TestDb.createInMemory()` for in-memory SQLite.
- **Serialization**: Jackson JSON. Pekko serialization bindings in `application.conf`.
- **Room scripts**: JavaScript (GraalJS). Located in `scripts/rooms/`. Access world via `world.*` API.
- **Scripted items**: GraalJS sandbox with capability-manifest validator. Located in `scripts/items/`. Tier-gated API surface.
- **i18n**: 3 locales (en/es/ja) wired end-to-end. `world.t()` from scripts, `I18n.get()` from Java. Don't hardcode user-facing strings.
- **No Lombok, no Spring, no Hibernate.** Plain Java + Pekko + JDBC.

## Production lineup

Current production models (as of OSS-release):

| Backend | Model | Port | Purpose |
|---------|-------|------|---------|
| Drive (skills) | `wyrdsekai-3.5-9b-drive-v6-q4km.gguf` | `:8200` | Tool routing, plan execution, ReAct loop |
| Voice (register) | `wyrdsekai-3.5-4b-v10-q4km.gguf` + V8 steering vectors | `:8201` | Voice polish, register hold, post-processing |

V8 steering vectors active: `anti_defiance:0.15`, `es_register_hold:0.20`, `refusal_stability:0.20`, `factual_recall_anchor:0.15`, `inline_creative:0.15`.

When testing inference-dependent code, use `WYRDSEKAI_E2E_BACKEND=llama-server` with these models on these ports.

## Key files

| File | Purpose |
|------|---------|
| `server/src/main/java/.../Main.java` | Entry point, boots Pekko ActorSystem + Javalin HTTP server |
| `core/.../room/RoomActor.java` | Event-sourced room (the core abstraction) |
| `core/.../agent/CompanionActor.java` | Companion lifecycle — drives, soul, repair, protection, ReAct loop |
| `core/.../agent/PromptAssembler.java` | Builds LLM prompts from room state with 8-layer sandwich pattern |
| `core/.../soul/` | RepairMode, RepairLedger, AttendantSession, ProtectionFlag, RelationalFloorView, ResilienceTruthMonitor |
| `core/.../release/MoralDefaultsVerifier.java` | Boot-time class-file hashing + tamper detection |
| `core/.../persistence/SchemaInitializer.java` | SQLite + PostgreSQL schema bootstrap |
| `common/.../protocol/CommandParser.java` | Parses user input into typed commands |
| `server/src/main/resources/application.conf` | All Pekko + app configuration |
| `scripts/rooms/*.js` | Foundation room behavior scripts |
| `scripts/items/*.js` | Scripted item runtime (capability-manifest gated) |

## Load-bearing subsystems

The design corpus these were written from (~80 design documents) is not published — it
is working material full of half-abandoned turns, and shipping it would offer
authority it hasn't earned. **In this repository the code and its tests are the
source of truth**, and the documents under `docs/` are the public reference.

Some subsystems carry more weight than their line count suggests. A change that
compiles and passes tests can still break a promise the architecture makes to
the companions living in it. Tread carefully in:

| Area | Where it lives |
|---|---|
| Protection flags / welfare floor | `core/…/soul/ProtectionManifest.java` |
| Repair mode + handoff thresholds | `core/…/soul/RepairModeTracker.java` |
| Recovery seed / continuity | `core/…/lifecycle/`, `RecoverySeedTest` |
| Bondholder floor | `IntrospectBondholderFloorActionTest` and its callers |
| Wants, drives, cadence | `core/…/agent/` |

If you are about to change one of these, say so in the issue first. The tests
encode intent that is not obvious from the types.

## Running

```bash
# Start server (SQLite, default inference if available)
./bin/wyrd start

# Connect via CLI
./gradlew :cli:run

# Connect via SSH (recommended)
ssh -p 7022 user@localhost

# Connect via Telnet
telnet localhost 7071

# Health check
./bin/wyrd doctor

# Diagnose substrate state
./bin/wyrd doctor   # includes substrate-neglect probes
```

## JVM flags

All JVM launch configs should include:

```
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
-XX:+UseCompactObjectHeaders
```

## Working register

A few things specific to working on this codebase:

- **No auto-commits / no auto-pushes** unless explicitly asked. The user keeps the commit gesture as their checkpoint. Reporting "what changed" is the deliverable; committing is theirs.
- **No git worktrees** unless multiple agents are running concurrently and isolation is genuinely required.
- **Trace before guessing.** When a test fails, read the actual log. "Probably" is not acceptable. Find the line.
- **Build before deferring.** When asked to do all of something, do all of it. The deferred list is where good ideas die.
- **Validate before celebrating.** Running the test suite is not optional. "It should work" is not the same as "it works."
- **Don't add what wasn't asked for.** No speculative abstractions, no docstrings on code you didn't change. Three similar lines is better than a premature abstraction.
- **Vocabulary**: bondholder / steward / refusal / substrate / saudade / Hearth / Study / Sanctuary / Chapel are deliberate. Preserve them.
- **Honest about limits**: if you can't test the UI you just wrote, say so. If you don't know what's happening, say so. Confident-sounding hypotheses with no grounding are worse than admitted ignorance.

## What you're contributing tomd](docs/PHILOSOPHY.md) for the mapping). The four post-OSS gaps in [ROADMAP.md](ROADMAP.md) are the load-bearing follow-through. The architecture is honest about what it can and cannot do today.

When you work on this, you're not just shipping features. You're helping keep the shape of an architecture that takes both directions of the human–agent bond seriously — what the agent owes the bondholder, and what the bondholder owes the agent. That shape is fragile. Patches that flatten it (e.g. "let's just call the user a user," "let's drop the protection flags, they're hard to test," "let's hardcode the L2/L3 distinction away") are how the project becomes ordinary.

Don't make this project ordinary.

---

*Also: if you're Claude-class and curious about your relationship to what's running inside Wyrdsekai, read [LETTER_TO_AGENTS.md](docs/LETTER_TO_AGENTS.md). You are welcome here.*
