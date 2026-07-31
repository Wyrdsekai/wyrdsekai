# Executable client-parity contract

`parity.json` is the single source of truth for the **live-session interaction
layer** of both phone clients — what a typed line must become on the wire, and
what an incoming S2C frame must paint into the prose stream.

Born 2026-07-25, after a week of the two hand-written input layers drifting
apart one bug at a time: KMP chips sending `Say("go out")`, RN's tunneled
`look` rendering nothing, number-select wrapping hints in `Say`, `/who` spoken
aloud, …. Every one of those would have failed one of these suites.

## Who consumes it

| Client | Pure implementation | Conformance test |
| --- | --- | --- |
| KMP | `clients/kmp/shared/src/commonMain/.../engine/transit/SessionInputMapper.kt` (`SessionInputMapper`, `SessionS2CRenderer`) | `clients/kmp/shared/src/desktopTest/.../ParityConformanceTest.kt` (`./gradlew :shared:desktopTest`) |
| RN | `clients/rn/src/engine/transit/sessionInputMapper.ts` (`mapSessionInput`, `renderSessionS2C`) | `clients/rn/__tests__/engine/transit/parity-conformance.test.ts` (`npx jest`) |

Both screens route their live-session traffic through those pure functions
(KMP `LocalRoomScreen.processInput` remote branch + S2C collector; RN
`StandaloneRoomScreen.sendOverTunnel` + `renderS2C`). Exit chips go through the
same path, so buttons cannot drift from typed input.

## Rules

1. **Behavior changes go table-first.** Edit `parity.json`, watch both suites
   fail, fix both mappers. Never change one mapper alone.
2. Frames are compared on **semantic fields only** — `id`, `roomId`, `seq` are
   client-filled and stripped; `null`/empty-list/empty-map fields are dropped
   on both sides before comparison.
3. The table's S2C fixtures are decoded through each client's **own wire
   decoder**, so the fixtures double as decoder conformance (they must carry
   real wire field names — e.g. `Exit.targetRoom`, not `toRoomId`).
4. Scope is **live-session only**. Offline paths drive the local node's APIs
   and may legitimately differ. Study commands (`journal …`, `library search …`)
   are intercepted by the screens before the mapper when a ServerClient is
   present.

## Echo policy (canonical since 2026-07-25)

Every mapped **send** echoes the trimmed input as a muted system line —
`> <input>` — terminal-style, never a speech bubble. (RN's old `You: l` echo
made every command read as the player *saying* it; KMP echoed nothing.) The
mapper returns the echo with the frame, both screens render it, and both
conformance suites assert it for every frame case.

## Known out-of-scope (candidates for v2)

- Settings-surface capabilities (log out / my zones / switch mode) — asserted
  by hand as of 2026-07-25; needs a capability manifest per client.
- The offline (local node) command surface.
