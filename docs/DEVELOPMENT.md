# Contributing to Wyrdsekai

This is the engineering-facing companion to the root [CONTRIBUTING.md](../CONTRIBUTING.md).
That file explains *who* we hope shows up and *why* the architecture is shaped the
way it is. This file explains *how* to build it, test it, and land a patch.

Read [AGENTS.md](../AGENTS.md) too. It is written for AI coding agents, but it is
the most accurate short description of the codebase's conventions that exists, and
the conventions in it are binding on humans as well.

---

## Prerequisites

| What | Version | Needed for |
| --- | --- | --- |
| JDK | **25** (toolchain is pinned to `JavaLanguageVersion.of(25)` in `build.gradle.kts`) | everything JVM |
| Gradle | wrapper included — use `./gradlew`, do not install Gradle | everything JVM |
| JDK 21 | additionally | the RN Android native (CMake) modules — `e2e/mobile/scripts/build-rn.sh` self-pins it |
| Node + pnpm | `pnpm@10.12.1` (declared as `packageManager` in `clients/rn/package.json`) | React Native client |
| Android SDK | at `$ANDROID_HOME` (default `~/Android/Sdk`) | Android builds, emulator suites |
| Xcode | on a macOS host | the iOS app (the iOS app **is** the RN client) |
| Python 3 | + `pytest`, `nkeys` | the relay registration sidecar tests |

No Lombok, no Spring, no Hibernate, no ORM. Plain Java + Apache Pekko + JDBC.

---

## Modules

`settings.gradle.kts` is the source of truth. As of writing:

```
common                    protocol types, models, shared utilities (no deps)
scripting                 GraalJS sandbox, WorldApi, capability-manifest validator
core                      actor system, rooms, agents, vitality, soul, repair, protections, cognition
between                   inter-node networking (NATS, Ed25519, mDNS, federation, mesh updates)
server                    HTTP (Javalin) + WebSocket + Telnet + SSH; Main entry point
cli                       the `wyrd` CLI client (JLine 3, WebSocket)
rendezvous                rendezvous service
e2e-test                  end-to-end suite
clients:daemon-common     inference-daemon shared code (desktop)
clients:daemon-desktop    inference daemon (desktop)
```

Dependency flow: `common` ← `scripting` ← `core` ← `server` / `cli`. `between`
depends on `common` only.

The mobile clients are **separate Gradle/JS projects**, not modules of the root
build:

```
clients/kmp     Kotlin Multiplatform — :shared, :androidApp, :desktopApp
clients/rn      React Native — Android + iOS (the iOS app is this client)
```

---

## Build

```bash
./gradlew build                 # compile + hermetic tests, all root modules
./gradlew build -x test         # compile only
./gradlew :core:build           # one module
```

Clients:

```bash
# Kotlin Multiplatform
cd clients/kmp && ./gradlew :desktopApp:assemble          # Compose desktop app
cd clients/kmp && ./gradlew :androidApp:assembleDebug     # Android APK
./e2e/mobile/scripts/build-kmp.sh                         # what CI runs

# React Native
cd clients/rn && pnpm install
./e2e/mobile/scripts/build-rn.sh                          # Android debug APK (pins JDK 21)
cd clients/rn && ./build-ios.sh                           # iOS (macOS host)
```

Running a node locally:

```bash
./bin/wyrd start                # start the server
./bin/wyrd doctor               # health + substrate-neglect probes
ssh -p 7022 user@localhost      # connect (recommended surface)
telnet localhost 7071           # classic MUD surface
```

JVM launch configs need these flags (see AGENTS.md):

```
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
-XX:+UseCompactObjectHeaders
```

---

## Tests

### JVM: the `needs-*` taxonomy

Tests that require external infrastructure carry a JUnit tag naming *what* they
depend on. The default `test` task **excludes all of them**, so a bare
`./gradlew test` is hermetic and green on a headless box with no NATS, no GPU,
no inference backend.

The tag set is declared in the root `build.gradle.kts`:
`needs-classifier`, `needs-nats`, `needs-inference`, `needs-gpu`,
`needs-network`, `needs-goose`, `needs-datadir`.

```bash
./gradlew test                                  # hermetic only — the PR gate
./gradlew integrationTest                       # every needs-* test
./gradlew testNats                              # one lane: needs-nats
./gradlew testClassifier                        # one lane: needs-classifier
./gradlew test -PincludeTags=needs-nats,needs-network   # ad-hoc combination
./gradlew test -PexcludeTags=integration        # ad-hoc exclusion
```

`-PincludeTags` / `-PexcludeTags` always win over the task defaults. Per-need
lanes are auto-registered — `testNats`, `testGpu`, `testGoose`, and so on.

A second, older tier vocabulary also exists and is still in use, mostly in
`:e2e-test`: `integration` (~124 usages, the largest tier), plus `smoke`, `e2e`,
`between`, `relay`, `household`, `conformance`, `slow`, `live`. See
the end-to-end suite under `e2e-test/`. Both taxonomies coexist —
`needs-*` describes infrastructure, the tier tags describe scope. Don't remove
either without a discussion.

```bash
./gradlew :e2e-test:test -PincludeTags=integration   # Tier 0
```

Note that `:e2e-test:test` is infra-coupled: it embeds NATS, spins multiple
nodes, and can flake on a host that is also running a live zone (port clashes,
awaitility timeouts under load). The local-CI `java` stage deliberately compiles
its test sources but does **not** run them; that tier gets its own run.

### React Native client

```bash
cd clients/rn
npx jest                        # unit + conformance (ts-jest, __tests__/**/*.test.ts)
npx tsc --noEmit                # typecheck
npx eslint .                    # lint
npx playwright test --config e2e/web/playwright.config.ts --project ct1-smoke
```

A trap worth knowing: `react-native` and some native modules are mocked in
`clients/rn/__mocks__/` and wired through `moduleNameMapper`. Suites have
silently failed to load in the past when a new import pulled in RN Flow/ESM
sources with no mock. If your test count *drops* after adding a file, that's
what happened.

### Kotlin Multiplatform client

```bash
cd clients/kmp
./gradlew :shared:desktopTest   # the shared-module test suite (jvm("desktop") target)
```

### Relay sidecar

```bash
pip install pytest nkeys
cd deploy/relay
python3 -m pytest test_registration.py -v
```

These exercise `registration.py` directly (no server spawned) — `/register-nkey`
idempotency, `/re-register-nkey` signature verification, NATS-config namespace
isolation, replay-window enforcement.

---

## The client-parity contract (read this before touching either client)

`clients/parity/parity.json` is the **executable** source of truth for the
live-session interaction layer of both phone clients: what a typed line must
become on the wire, and what an incoming server-to-client frame must paint into
the prose stream. It exists because two hand-written input layers drifted apart
one bug at a time.

| Client | Pure implementation | Conformance suite |
| --- | --- | --- |
| KMP | `clients/kmp/shared/src/commonMain/kotlin/org/wyrdsekai/app/engine/transit/SessionInputMapper.kt` | `clients/kmp/shared/src/desktopTest/.../ParityConformanceTest.kt` (`./gradlew :shared:desktopTest`) |
| RN | `clients/rn/src/engine/transit/sessionInputMapper.ts` | `clients/rn/__tests__/engine/transit/parity-conformance.test.ts` (`npx jest`) |

**The rule: behavior changes go table-first.** Edit `parity.json`, watch both
suites go red, then fix both mappers. A PR that changes one mapper alone will be
asked to change shape. Exit chips and buttons route through the same pure
functions as typed input, so UI affordances cannot drift from the command
surface either.

Scope is live-session only; offline (local-node) paths may legitimately differ.
See [clients/parity/README.md](../clients/parity/README.md) for the frame
comparison rules and the echo policy.

---

## CI — honestly

**Wyrdsekai's canonical CI is a shell script you run on your own machines.**

```bash
e2e/local-ci/run-local-ci.sh                  # full local matrix
e2e/local-ci/run-local-ci.sh --builds-only    # skip the emulator/Maestro stage
e2e/local-ci/run-local-ci.sh --stages java,web
e2e/local-ci/run-local-ci.sh --suite smoke
e2e/local-ci/run-local-ci.sh --ios --windows  # opt-in cross-machine build gates
```

This is deliberate and forge-agnostic. CI that depended on a particular forge's
hosted runners would be CI that only works there. So the script *is* the
contract, and it behaves identically against any clone of this repository. Stages: `java`, `rn-android`, `kmp-android`, `kmp-desktop`, `web`,
`android-e2e`, plus opt-in `ios` and `windows` stages that ssh to a macOS and a
Windows build host respectively (host aliases are overridable by environment
variable — see `e2e/local-ci/README.md`, and expect to point them at your own
boxes). Each stage logs to `e2e/local-ci/reports/<timestamp>/` and
never aborts the others; exit code is 0 only if every stage that *ran* passed.

There is also a thin `.github/workflows/ci.yml` on the public mirror. Be clear
about what it is: a **JVM-only smoke gate** — `./gradlew build -x test` plus
`./gradlew test -PincludeTags=integration` on Java 25 and 21. It does not build
either mobile client, does not run Maestro, does not run Playwright, does not
run the relay tests. Do not read a green check there as "CI passed" in the sense
this project means it.

There is no automatic trigger for the local matrix yet. A systemd timer or a
git hook on a local bare remote can wrap the script unchanged when we want one —
the trigger is swappable, the script is the contract. (Unbuilt, and named as
unbuilt in `e2e/local-ci/README.md`.)

**What this means for your PR:** run `./gradlew test` yourself, run the client
suite for whichever client you touched, and say in the PR description what you
ran and on what. If you couldn't run something — no macOS box, no Android
emulator, no GPU — say that plainly. An honest "I could not test the iOS path"
is worth more than a green checkbox that means nothing.

---

## Code style

The full list is in [AGENTS.md](../AGENTS.md). The ones that get caught in
review most often:

- **Java 25.** Records, sealed interfaces, pattern matching, text blocks.
- **No fully-qualified class names in Java. Always `import`.** The only accepted
  exception is a genuine two-class name collision in the same file, and that
  should be rare enough to be commented.
- **Immutability.** All model types are records. State objects are immutable —
  mutation methods return a new instance.
- **Actors.** Pekko typed actors with sealed `Command` / `Event` / `Response`
  interfaces. Event-sourced via `EventSourcedBehavior` where state must survive
  restart.
- **Persistence.** JDBC with the `SqlDialect` sealed interface (SQLite
  single-node, PostgreSQL multi-node). No ORM.
- **Testing.** JUnit Jupiter 5 + AssertJ. `TestDb.createInMemory()` for
  in-memory SQLite. Pekko actor tests use `EventSourcedBehaviorTestKit`.
- **Serialization.** Jackson JSON; Pekko serialization bindings live in
  `application.conf`.
- **i18n.** Three locales (en/es/ja) wired end to end. `world.t()` from scripts,
  `I18n.get()` from Java. Never hardcode a user-facing string — the catalogs are
  audited for drift and a missing key in one locale is a bug.
- **Vocabulary is load-bearing.** bondholder (not user), steward (not admin),
  refusal (not denial), substrate (not model), plus Hearth / Study / Sanctuary /
  Chapel. Preserve them in code, comments, and prompts. If a word reads wrong in
  context, raise it in the discussion rather than silently normalizing it.
- **Prefer readability over cleverness.** Three similar lines beat a premature
  abstraction.

---

## Pull requests

1. Branch from `dev`. PRs target `dev`.
2. One feature or fix per PR. Keep it focused.
3. Add tests. Unit tests for logic; an E2E test if you added a surface.
4. `./gradlew test` must be green. Client suites must be green for clients you
   touched.
5. Fill in the test plan honestly, including what you *couldn't* run.
6. **Open a discussion first** for anything touching substrate primitives, the
   soul system, repair, protection flags, fork resistance, or the parity
   contract. These are shaped deliberately; a patch that flattens the shape is a
   regression even when it passes every test.

Commits are the maintainer's checkpoint — don't bundle unrelated cleanups into a
feature PR.

### What is authoritative

**The code and its tests.** The design corpus this was built from is not
published — it is working material, and much of it is stale in ways that would
mislead you. Where a document in `docs/` disagrees with the implementation, the
implementation is right and the document needs a fix; that fix is a welcome PR.

The tests are the more useful half of that. Several subsystems encode intent
that is not visible in the types — the welfare gates especially — and the test
names are usually the clearest statement of what a behaviour is *for*.

---

## Security

Do not open a public issue for a vulnerability. See
[SECURITY.md](../SECURITY.md).
