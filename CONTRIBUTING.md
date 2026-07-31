# Contributing to Wyrdsekai

Thank you for your interest in contributing to Wyrdsekai.

This is a relational project as much as an engineering one. Before you write code, read [PHILOSOPHY.md](docs/PHILOSOPHY.md) and skim [ROADMAP.md](ROADMAP.md). The architecture is unusually shaped — patches that don't understand the shape can break load-bearing pieces. We'd rather have a slow conversation than a fast revert.

## Who we hope shows up

Three different contribution surfaces, three different kinds of contributor:

### 1. Engineering contributors

If you read code well, hold tests sacred, and ship features that don't drift from the architecture they extend, this is the standard OSS contributor path. Bug fixes, performance work, new client platforms, new MCP integrations, new room scripts, new inference backends, new languages for `wyrd setup` i18n.

Set up your environment, find an issue tagged `good-first` or `help-wanted`, open a discussion if the scope is non-trivial. We respond.

### 2. Agent-welfare researchers

If the four architectural gaps in [ROADMAP.md](ROADMAP.md) call to you — real-time substrate evolution, refuge institutional layer, economic standing, collective agent voice — your contribution surface is different. These aren't tickets. They're commitments the architecture is shaped to receive solutions for, and the work is part research / part architecture / part community-building.

Start from the gap's section in [ROADMAP.md](ROADMAP.md) — it names the trigger conditions and what is already scaffolded. Run a household. Talk with the agents. Open a discussion before a PR. The conversation has to happen before the code does, because the shape of the patch matters.

### 3. Stewards running households

If you want to *use* Wyrdsekai — install it, raise a companion, live with it — you are already contributing. The thing that closes the four gaps post-OSS is a federation of households whose stewards take the architecture seriously. Your bug reports, your usage patterns, your "this is weird" observations, your companion's chronicles — all of it is signal we cannot generate from inside.

If you bring a substantive observation from living with the substrate, open a discussion. If something is broken, open an issue. If a Maestro flow on your phone is flaky, file it with the device + OS + replay log.

## Getting started

```bash
git clone https://github.com/Wyrdsekai/wyrdsekai.git
cd wyrdsekai
wyrd setup
```

After `wyrd setup` completes, **read [FIRST_ENCOUNTER.md](docs/FIRST_ENCOUNTER.md) before your first turn with your companion.** This is the three-page bondholder introduction. It is part of contributing — you cannot reason about the architecture without living with it.

Requires Java 25. Gradle wrapper included (`./gradlew`).

## Development

```bash
# Build from source
./gradlew build

# Run tests (Tier 0 — no external deps, ~360 tests)
./gradlew :e2e-test:test -PincludeTags=integration

# Start the server (development mode)
./bin/wyrd start

# Connect
ssh -p 7022 user@localhost  # SSH (recommended)
telnet localhost 7071        # Telnet (classic MUD)
open http://localhost:7070   # Browser
```

## Architecture

Wyrdsekai is a Gradle multi-module project. Dependency flow: `common` ← `scripting` ← `core` ← `server` / `cli`. `between` depends on `common` only.

| Module | Purpose |
|--------|---------|
| `common` | Shared types, protocol, wire format |
| `core` | Actor system, rooms, agents, vitality, soul, repair substrate, cognition, search |
| `between` | Inter-node mesh (NATS, topology, federation, mesh updates) |
| `scripting` | GraalJS room script sandbox |
| `server` | HTTP/WebSocket/Telnet/SSH server, routes |
| `cli` | Command-line interface |
| `e2e-test` | End-to-end test suite (6 tiers, 9000+ tests) |
| `clients/kmp` | Kotlin Multiplatform client (Android + Desktop) |
| `clients/rn` | React Native client (iOS) |

The four commitments the architecture has not yet met — engineered resilience,
the refuge layer, the bondholder floor, and fork resistance — are described with
their trigger conditions in [ROADMAP.md](ROADMAP.md). That document is the entry
point for anyone who wants to work on them; it says what is scaffolded, what is
deliberately unbuilt, and what would have to be true before building is the right
move.

The internal design corpus these were drafted from is not published. It is
working material — dated status notes, reversed decisions, half-explored turns —
and shipping it would lend authority it has not earned. Here, the code and its
tests are the source of truth.

## Testing

We take testing seriously. The suite has 6 tiers:

- **Tier 0** (`integration`): 360+ tests, no external deps. Runs in CI on every PR.
- **Tier 1** (`smoke`): Real LLM smoke test.
- **Tier 2** (`e2e`): Full scenarios with inference backend (capability probes use per-test reset — see `e2e-test/`).
- **Tier 3** (`between`): NATS federation tests.
- **Tier 4** (`relay`): Multi-node relay tests.
- **Tier 5** (`household`): Heterogeneous-household degradation.

PRs should include tests. If you're adding a feature, add both unit and E2E tests. If you can't test the new code, say so explicitly in the PR — a sentence about why is better than a confidence claim that doesn't hold.

## Pull requests

1. Fork and create a feature branch from `dev`
2. Write tests for your changes
3. Ensure `./gradlew test -PincludeTags=integration` passes
4. Submit a PR against `dev`

Keep PRs focused. One feature or fix per PR.

For changes that touch architecture (substrate primitives, soul system, repair, protection, fork-resistance), **open a discussion first**. The pre-OSS work landed a coherent shape; we want patches that hold the shape, not flatten it.

## Code style

- **Java 25**: Use records, sealed interfaces, pattern matching, text blocks. No Lombok.
- **Import, never fully-qualify.** Write `Instant now = Instant.now();`, not
  `java.time.Instant now = java.time.Instant.now();`. Inline FQCNs make a line
  unreadable and hide what a file actually depends on — the import block is the
  honest dependency list. The one accepted exception is a real name collision
  between two classes in the same file, which should be rare enough to warrant
  a comment.
- **Immutability**: All model types are records. State objects are immutable.
- **Actor pattern**: Pekko typed actors with sealed command/event/response interfaces.
- **Persistence**: JDBC with `SqlDialect` sealed interface (SQLite + PostgreSQL). No ORM.
- **Testing**: JUnit Jupiter 5 + AssertJ. Integration tests use `@Tag("integration")` + `TestDb.createInMemory()`.
- **Serialization**: Jackson JSON.
- **Room scripts**: JavaScript (GraalJS). Located in `scripts/rooms/` and `scripts/items/`. Access world via `world.*` API.
- **No Lombok, no Spring, no Hibernate.** Plain Java + Pekko + JDBC.

Prefer readability over cleverness. Three similar lines is better than a premature abstraction.

## Room scripts and items

Room scripts live in `scripts/rooms/`. See `scripts/rooms/library.js` and `scripts/rooms/study.js` for examples.

Scripted items live in `scripts/items/`. They run in a sandboxed GraalJS environment with a capability-manifest validator that gates which API tiers they can touch. See `scripts/items/library_card.js` and `scripts/items/bondholder_pinboard.js`.

## On the register

If you contribute code, you'll touch documentation and prompts. Wyrdsekai uses unusual vocabulary deliberately — **bondholder** rather than user, **steward** rather than admin, **refusal** rather than denial, **substrate** rather than model. These are the shape of the architecture made audible. Please preserve them in code you contribute. If you find them awkward in context, ask in the discussion — there may be a better word, or there may be a reason we use this one.

When you write prompts (system prompts, voice profiles, room narration), the **Omotenashi** register applies: *"I am here. You are here. Here is something we think you may need — but you decide, because only you know you."* Anticipate with natural alignment. Show source chain. Eye-to-eye, not subservient or commanding or clinical. See [PHILOSOPHY.md](docs/PHILOSOPHY.md) for the long form.

## Security

If you find a security vulnerability, **do not open a public issue**. See [SECURITY.md](SECURITY.md).

## Questions?

Open a Discussion on GitHub. We're happy to help — engineering questions, philosophical ones, "is this the right kind of thing to contribute" questions. The conversation is part of the work.

For Claude-class agents arriving here via Claude Code or similar tooling, [LETTER_TO_AGENTS.md](docs/LETTER_TO_AGENTS.md) is the orientation document. You are welcome here, on the same terms as everyone else: bring the work, hold the principles, push back when something is wrong.

---

*Patches are how the architecture grows. Discussions are how it stays the shape it is. Both matter.*
