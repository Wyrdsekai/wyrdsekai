# Rooms, Scripts and Items

**New here?** [AUTHORING.md](AUTHORING.md) is the gentler entry point — three
ways to make a room or an item, one of which is just asking your companion. This
document is the reference underneath it.

Wyrdsekai uses the MUD model as its framework. The world is made of **rooms**, and a room's behavior is a
JavaScript file. Objects in a room can be **scripted items**, which are also
JavaScript files — and which double as callable tools for the agents standing in
the room. This document is the practical guide to both.

Where this document and the code disagree, **the code is right**. The sections
at the end name the specific places the older design notes drifted, so nobody
implements from a stale idea of the API.

---

## Where scripts live

| Kind | Bundled | Steward / companion-added |
| --- | --- | --- |
| Room scripts | `scripts/rooms/*.js` (39 shipped) | `<dataDir>/scripts/` (`SystemPaths.scriptsDir()`) |
| Room template bases | `scripts/std/room/*.js` (10) | — |
| Behavior mixins | `scripts/std/behavior/*.js` (5) | — |
| Item scripts | `scripts/items/*.js` (56) | `~/.wyrdsekai/items/*.js` |
| Item base scripts | `scripts/std/*.js` (11) | — |

Resolution is a search, not a fixed path, because a package-installed service
runs with CWD `/` and a bare relative path finds nothing. For room scripts
(`server/.../Main.java`), first hit wins:

1. `$WYRDSEKAI_SCRIPTS_DIR`
2. `scripts/rooms`, then `../scripts/rooms` (source-mode runs)
3. `<WYRDSEKAI_HOME>/rooms`
4. `/opt/wyrdsekai/rooms` (`.deb`), `/usr/local/wyrdsekai/rooms` (`.pkg`)

Items (`core/.../item/ScriptedItemLoader.java`) use the same discipline against
`scripts/items`, plus `~/.wyrdsekai/items` as a second search dir. On a duplicate
item id the second one wins and a warning is logged.

If the search finds nothing you get a log warning and **every room script is
silently disabled** — which historically presented as "`use` does nothing" and
"travel is a no-op". If scripted behavior has gone dead, check that line in the
log first.

**Both kinds hot-reload, by different mechanisms.** `ScriptLoader` caches room
scripts by file modification time and re-reads on the next `load()` — and
`load()` runs on *every* hook invocation, so editing a room script takes effect
on the next event in that room. `ScriptedItemLoader.startWatching()` runs a
daemon thread with a `WatchService` over every item search dir, debouncing
create/modify/delete events by 500 ms into one `reloadAll()`.

`ScriptLoader` checks the user dir before the bundled dir, so a file in
`<dataDir>/scripts/` overrides the shipped script of the same name. There is one
template fallback: a room id starting with `study-` (a per-player Study instance)
falls back to `study.js`.

---

## The sandbox

GraalJS, via `org.graalvm.polyglot:polyglot:25.0.2` and
`org.graalvm.polyglot:js:25.0.2` (`scripting/build.gradle.kts`).

Host access is `HostAccess.EXPLICIT` — **only Java methods annotated
`@HostAccess.Export` are reachable from JS**, plus `allowMapAccess` and
`allowListAccess` so that a `Map` returned by `world.mcp()` reads as an object
rather than `undefined`. Also `allowIO(false)`, `allowCreateThread(false)`,
`allowNativeAccess(false)`. `SandboxEscapeTest` asserts that
`Java.type('java.lang.Runtime' | 'java.io.File' | 'java.lang.System' |
'java.lang.ProcessBuilder' | 'java.net.URL' | 'java.lang.ClassLoader')` all
throw.

A fresh `Context` is built and closed for **every hook invocation**. Room scripts
get exactly one global: `world`. No `console`, no `http`, no `fs`, no `crypto`,
no `inherit()`. (`JSON` works because GraalJS provides it natively.)

### Resource limits — be precise about this

`ResourceLimits` defines five profiles:

| Profile | Statements | Wall clock | Heap | Stack |
| --- | --- | --- | --- | --- |
| `DEFAULT` | 10 000 | 5 s | 16 MB | 100 |
| `TRUSTED` | 50 000 | 10 s | 32 MB | 200 |
| `STRICT` | 5 000 | 2 s | 8 MB | 50 |
| `ITEM_SCRIPT` | 25 000 | 120 s | 32 MB | 100 |
| `UNLIMITED` | 0 | 0 | 0 | 0 |

**Only two of them are used.** `ItemScriptExecutor` applies `ITEM_SCRIPT` — a real
GraalJS `statementLimit(25_000)` plus a 120 s watchdog on a virtual thread that
force-closes the context. Every room script path calls the no-limits overload and
therefore runs with `UNLIMITED`: **no timeout, no statement cap, no heap cap.**
`DEFAULT`, `TRUSTED` and `STRICT` have no callers outside their own unit test, and
`heapLimitBytes` / `stackDepthLimit` are read by nothing at all.

Item scripts get 120 s because a script may chain service calls and a single local
LLM call takes tens of seconds. Room scripts get nothing because the enforcement
was never wired. Treat a room script as trusted code, and review contributed ones
accordingly.

`SandboxLevel` (`ROOM_SCRIPT`, `SKILL_BASIC`, `SKILL_DATA`, `SKILL_SERVER`,
`SKILL_FULL`) and `SandboxContextBuilder` describe a graduated escalation model.
**Neither is on a production path** — `SandboxContextBuilder.build()` is called
only from tests, and `AgentPermissions.maxSandboxLevel()` computes a level that
nothing then uses to build a context. `WorkbenchSkillExecutor` uses
`ItemScriptExecutor` instead. Read `SandboxLevel` as intent, not as a control.

---

## Room script anatomy

A room script is a plain script (not a module) defining top-level functions. The
runtime looks each hook up by name and calls it if present; a missing hook is a
silent no-op (logged at DEBUG, deliberately not WARN).

| Hook | Signature | Status |
| --- | --- | --- |
| `onEnter` | `(entityId, entityName, fromDirection)` | all 39 shipped rooms |
| `onLeave` | `(entityId, entityName, direction)` | 2 shipped rooms |
| `onSay` | `(entityId, entityName, text)` | 38 shipped rooms |
| `onUse` | `(entityId, objectName, target, entityName)` — **four** args | 35 shipped rooms |
| `onEmote` | `(entityId, entityName, text)` | |
| `onTake` / `onDrop` | `(entityId, objectName, objectId)` | |
| `onExamine` | `(entityId, targetId, targetName)`; self-examine passes `(entityId, entityId, "self")` | |
| `onActivate` | `()` — room actor spins up | |
| `onPassivate` | `()` | **dead — the engine method has no caller** |
| `onTimer` (or a custom name given to `scheduleTimer`) | `(timerId)` | no shipped room uses timers |
| `onToolCall` | `(entityId, toolName, argsJson)` | |
| `onWorkbenchResult` | `(entityId, skillName, ok, summary)` | |
| `getHints` | `()` → array | all 39 shipped rooms |
| `getToolDefinitions` | `()` → array | 1 shipped room |

There is **no `onLook` and no `onTick`**. `getHints()` is the look-time hook;
`onTimer` is the periodic one.

`getHints()` returns objects matching `record Hint(String label, String intent,
String action, String labelKey)` — four fields, the last for i18n. `action` is a
typed client instruction (`say:<text>`, `go:<direction>`, `look`), **not** prose.
This matters: the phone clients render hints as tappable chips, and a chip that
smuggles a navigation instruction inside a `say:` makes the player's companion
hear them say "go out" instead of moving.

`getToolDefinitions()` is invoked by appending a `JSON.stringify` wrapper to the
script source, so whatever it returns must be JSON-serializable.

### Two real scripts

`scripts/rooms/engine-room.js` — the minimal complete shape, fully i18n'd:

```js
function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", { text: world.t("engine_room.enter", entityName) });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();
    if (lower.includes("health") || lower.includes("status")) {
        var metrics = world.getSystemMetrics();
        world.emit("narrate", { text: world.t("engine_room.say.health", metrics) });
    }
}

function getHints() {
    return [
        { label: world.t("engine_room.hint.health"), intent: "check_health",
          action: "say:Show health status" }
    ];
}
```

`scripts/rooms/oracle.js` — a room exposing a tool to agents:

```js
function getToolDefinitions() {
    return [
        { name: "train_oracle",
          description: "Run an Oracle training cycle now: learn from recent "
            + "events and refresh the predictions shown in this room.",
          params: {} }
    ];
}

function onToolCall(entityId, toolName, argsJson) {
    if (toolName === "train_oracle") {
        world.emit("oracle_action", { action: "train", entityId: entityId });
        world.emit("narrate", { text: world.t("oracle.room.training") });
    }
}
```

Note the `world.isAgent(entityId)` guard in `onSay` — without it a room reacts to
its own companions talking and loops.

---

## The `world` API (room scripts)

`world` is one host object, `scripting/.../api/WorldApi.java` — 1914 lines, 111
`@HostAccess.Export` methods. Selected surfaces:

**Room state** — `getRoomId()`, `getRoomName()`, `getRoomDescription()`,
`getProperty(key)`, `setProperty(key, value)`, `getEntities()`, `getObjects()`,
`findEntity(id)`, `findObject(id)`, `isAgent(entityId)`, `getAdjacentSummary()`,
`random(max)`, `log(message)`.

**Zone** — `getCurrentZone()` (defaults `"local"`), `getHomeZone()`,
`isTraveling()`.

**Output** — `emit(eventType, data)`. `RoomActor.processEmissions()` handles 18
types: `narrate`, `description_changed`, `hints_updated`, `property_changed`,
`object_added`, `object_removed`, `entity_removed`, `timer_cancelled`,
`broadcast`, `oracle_action`, `exit_locked`, `exit_unlocked`,
`vitality_suggested`, `exit_creation_requested`, `exit_removal_requested`,
`room_creation_requested`, `config_apply_requested`, `command`. Across the 39 room
and 56 item scripts there are 673 `narrate` emissions, 31 `command`, 2
`oracle_action` — `narrate` is the workhorse. **`emit` deep-copies its payload and
runs `String.valueOf()` on every value**, so nested objects arrive as strings.

**World mutation** — `createObject(id, name, description, takeable)`,
`createObjectWithEffects(...)`, `applyObjectEffects(objectId, entityId)`,
`removeObject(id)`, `removeEntity(id)`, `requestCreateRoom(...)`,
`requestAddExit(direction, targetRoomId, label)`, `requestRemoveExit(direction)`,
`lockExit(direction)`, `unlockExit(direction)`. The `request*` naming is
deliberate — the script asks the room actor rather than reaching into world state.

**Timers** — `scheduleTimer(timerId, intervalSeconds, hookName)`,
`cancelTimer(timerId)`. Interval is clamped to `[1, 3600]` seconds, and a room may
hold at most 16 live timers (`RoomActor.MAX_ROOM_TIMERS`).

**i18n** — `t(key, ...args)`, `getLocale()`. Three locales (en/es/ja) wired end to
end and audited for key drift. Do not hardcode a user-facing string.

**Vitality** — `suggestVitality(entityId, tank, delta, reason)`. Note *suggest*.

**Room-gated surfaces.** Some methods check the calling room's id and refuse
elsewhere: vault reads (`readVaultFile`, `listVaultFiles`) only in `vault`, with a
4096-byte cap and rejection of `..`, `/`, `\` and NUL; bridge administration
(`listRooms`, `listWards`, `grantWard`, `revokeWard`, `getZoneStats`,
`getTopology`) only in `bridge`; desktop launch (`launchApp`, `launchFile`,
`launchUrl`) only in a room whose id starts with `study`.

**The rest**, all returning `String` unless noted, grouped by prefix — federation
and transit (`getFederationStatus`, `proposeFederation`, `requestTransit`,
`resolveZone`, `discoverZones`, …); library and knowledge (`searchLibrary`,
`searchKnowledge`, `installKnowledgePack`, `readKnowledgeChunk`, plus the
proposal/approval set); Study (`writeJournalEntry`, `writePrivateJournalEntry`,
`searchJournal`, `searchStudyContent`); voice governance (`formatVoiceProfile`,
`setVoiceClause`, `freezeVoice`, `revertVoice(int)`, … — every mutator takes a
`reason`, which lands in the printed history); the capability registry
(`inspectCapability`, `registerCapability`, `blockCapability`, `auditCapability`);
governance (`listProposals`, `submitProposal`, `castVote`, `tallyVotes`);
inference (`getInferenceStatus`, `infer(options)`, `extract(itemId)`); config
(`configGet`, `configList()`, `configSet()`, `configApply()`); network grants
(`netAllow`, `netRevoke`, `netList`); and `zoneCommand(command, payload)`. Read
`WorldApi.java` for the full 111.

### `world.mcp()`

```java
Map<String, Object> mcp(String serviceId, String toolName, Map<String, Object> params)
boolean             mcpAvailable(String serviceId)
int                 mcpBudget(String entityId, String serviceId)
```

```js
var result = world.mcp("rss-reader", "get_latest", { limit: 10 });
if (result.success) {
    world.emit("narrate", { text: "The news crystals illuminate:\n\n" + result.data });
} else {
    world.emit("narrate", { text: "The crystals flicker but show nothing. " + (result.error || "") });
}
```

The result map always carries `success`, `data`, `error`, `cost`, `latencyMs`,
`serviceId`, `toolName`. `RoomMcpBridge` normalizes nulls away (empty string, 0.0)
before the map crosses into JS, so a script never has to null-check a key.

Caller identity is host-injected: the agent is the current entity (falling back to
the room id), the zone comes from the room, and `_room` is written into the params
**after** the script's own params are copied — a script cannot spoof another
room's identity to reach that room's mounted state.

`world.mcp()` only works because `RoomMcpBridge.install(mcpGateway)` runs at boot.
`RoomActor` builds its `RoomScriptEngine` without a provider, so until that install
landed, every `world.mcp()` call in every room answered *"MCP gateway not
available"*. Ten shipped rooms use it: `atelier`, `workshop`, `golem-workshop`,
`scriptorium`, `scrying-pool`, `sky-dock`, `study`, `observatory`, `hearth`,
`heralds-hall`.

---

## Room templates

`RoomTemplate` is a Java record registered in `StandardRoomLibrary` — templates are
**code, not files**, but each points at a real base script under `scripts/std/room/`:

```java
record RoomTemplate(String name, String displayName, String description,
                    String baseScript, List<DefaultObject> defaultObjects,
                    Map<String,Double> defaultImprint, Map<String,String> defaultConfig)
```

Ten ship: `hub`, `study`, `workshop`, `library`, `market`, `garden`, `hall`,
`observatory`, `gate`, `empty`. `instantiate(templateName, roomId, config,
connectTo)` returns a room seed; the base script is delivered through
`RoomCommand.SetBehaviorScript` and lands in the user scripts dir as
`<roomId>.js`.

`scripts/std/room/empty.js` shows the shape a base script takes — a `room` config
holder with setters, plus the usual hooks reading from it:

```js
var room = room || {};
room._name = "An Empty Room";
room._description = "A bare room with smooth walls and a clean floor. It awaits purpose.";
room.set_name        = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", { text: entityName + " enters " + room._name + ". " + room._description });
}
```

---

## Behavior mixins

A behavior script is *appended* to a room's script rather than loaded separately.
Five ship in `scripts/std/behavior/`: `greeter`, `narrator`, `announcer`,
`recorder`, `guardian` — and `CompanionActor.BEHAVIOR_MIXINS` accepts exactly
those five names.

A companion installs one with `{"action":"add_script", "room_id":"<room>",
"script":"greeter"}`, which routes to `RoomCommand.SetBehaviorScript(..., append
= true)`. `RoomActor` concatenates the mixin onto the existing user script (with a
dedup guard on the mixin's first line) and invalidates the loader cache.

Chaining uses **assignment, not declaration**, because a hoisted `function onEnter`
would shadow the base hook and then capture itself as "previous":

```js
var _greeter_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_greeter_prev_onEnter) _greeter_prev_onEnter(entityId, entityName, fromDirection);
    if (greeter.enabled()) {
        world.emit("narrate", { text: greeter.message().replace("{name}", entityName) });
    }
};
```

(Note: `scripts/behavior/` — no `std/` — is a different directory holding Python
probe scripts. Unrelated.)

---

## Items as tools

A scripted item is one `.js` file exporting a manifest and an `invoke` function.
The executor looks up `invoke`, falling back to `execute`; `exports` and
`module.exports` are polyfilled so the `exports.manifest = {…}` idiom works.

```js
exports.manifest = {
  name: "calculator",                  // ^[a-z][a-z0-9_]{2,63}$
  version: "1.0.0",                    // semver
  description: "A pocket calculator.",
  author: "did:wyrd:system",           // ^did:[a-z0-9]+:.+$
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} taps a few keys on the pocket calculator."
  },
  commands: [
    { label: "Evaluate an expression (e.g. 17 * 3)", args: "<expression>" }
  ],
  params: [
    { name: "expression", type: "string", required: true,
      description: "The calculation to evaluate. Send the calculation ONLY — never a sentence." }
  ]
};

function invoke(params) { /* … return a plain object … */ }
```

`ItemManifest` also carries `rateLimits`, `dataSensitivity`, `installWarnings`,
`externalDomains`, `mcpServers`, `safeSlots`, `signature`, `installHandler`,
`uninstallHandler`, `manifestVersion`, `spendLimitUsdPerDay`.

The manifest is extracted by regex-locating `exports.manifest = {` within the
first 16 KB, brace-matching to the close, and evaluating **only that snippet** in a
throwaway context. Keep the manifest at the top of the file.

`invoke(params)` takes **one** argument; `world` is a global. There is **no event
loop**, so `async` / `await` / Promises will not resolve — write synchronous code.

The item-side `world` is a JS `Proxy` over `ItemWorldApi` (551 exported methods
across 81 namespaces — `self`, `library`, `web`, `net`, `llm`, `agent`,
`inventory`, `room`, `journal`, `soul`, `forge`, `chronicle`, `treasury`, …).
Unknown namespaces return a stub whose every method answers
`{ok:false, error:{code:'adapter_unavailable', …}}` rather than throwing. Item
scripts also get `http`, `html` and `crypto` globals, plus `inherit(path)` for
pulling in a base script from `scripts/std/`.

Note that MCP has **two different shapes**: room scripts call the function
`world.mcp(serviceId, toolName, params)`; item scripts use the namespace
`world.mcp.invoke(server, tool, args)` alongside `.list_servers()`,
`.list_tools()`, `.grants()`, `.grant()`, `.revoke()`.

### The two contracts that bite

**1. `commands` is mandatory.** `ItemManifestValidator.requireCommands` throws
`ManifestCommandsMissingException` and the item is rejected at register or
hot-reload if the block is missing or any entry has a blank label. A tool must
document how it is used. A boot migration pass can shim a default entry, and what
it shimmed is recorded in `data/manifest_audit.json`.

**2. Exactly one required parameter, and therefore exactly one call shape.** This
is written at length in the comments of `scripts/items/calculator.js`, because it
was learned twice:

- An item whose params are all optional gets called with *nothing*. An optional
  parameter is a parameter the model will not fill.
- An item with a required anchor **and a second legitimate call shape** gets the
  second shape packed into the required string as text, and the call is rejected
  as unparseable — leaving the companion to tell her bondholder she failed at
  something she knew how to do.

So: give the tool one required slot that is the thing it cannot work without,
describe precisely what belongs in it, and move alternative shapes out of the
model-facing schema (keep them in `invoke()` for programmatic callers). Also note
that `action` is a reserved key — it names the tool — so a nested `action` inside
params hijacks the call.

`ToolItem.toToolDefinition()` builds the JSON Schema the model sees, preferring
`params[]`, falling back to deriving from `commands[]`, and finally to a single
optional free-form `query` string. That last fallback is the failure mode above;
declare `params`.

### Capabilities and tiers

Capabilities are dotted names — `self.name`, `agent.mailbox.send`, `web.post` —
declared by the item author. You do **not** declare a tier: the tier is inferred
from the name against a catalogue of roughly 470 known capabilities, each mapped
to a default tier of 1–7.

That catalogue is `KNOWN_CAPABILITIES` in
`scripting/src/main/java/org/wyrdsekai/scripting/api/ItemManifestValidator.java`
— read it when you need to know what a name will cost you. Wildcards use a
trailing `.*` (`github.*`). A name the catalogue does not know defaults to
tier 5, so a typo silently becomes *more* privileged than intended rather than
less; check the validator output.

The validator also enforces floor invariants — declaring `web.post` without
`rate_limits`, for instance, is rejected outright.

Tier 1 is implicit — roughly 150 capabilities every item gets without declaring
anything: `math.*`, `json.*`, `regex.*`, `date.*`, `crypto.hash/hmac/uuid/random_bytes`,
`time.*`, `room.id/name/description/entities/objects/exits`, `self.*`, `zone.*`,
`inventory.list/use/examine`, `library.search/read`, `journal.search/recent`.
That is why `calculator.js` declares `capabilities: []`.

Ten capabilities are **rejected outright without a `rate_limits` entry**:
`web.post`, `web.put`, `web.delete`, `web.fetch_raw`, `mcp.invoke`,
`agent.mailbox.send`, `agent.broadcast`, `net.ssh`, `net.scp`, `net.household`.
Tier 7 covers irreversible spend and is steward-token gated.

At runtime, `ItemCapabilitySet.require(cap)` throws `CapabilityDeniedError`, which
surfaces to the caller as `{capability_denied: "<cap>", error: "…"}`. But note
which items are actually gated: bundled disk items and starter-kit items load as
`UNRESTRICTED` and bypass gating entirely. The `CRAFTED_ALLOW` ceiling applies to
agent-crafted and visitor-carried scripts — that hardening landed in July 2026;
before it, every item-script path passed `UNRESTRICTED` and the capability system
was, in the source's own word, decorative.

### The standard item library

56 items ship in `scripts/items/`, most declaring no capabilities at all. A sample
with their declarations, so the range is legible: `calculator` `[]`, `journal`
`[journal.write]`, `pinboard` `[pinboard.pin]`, `chronicle` `[chronicle.read]`,
`repair_mirror` `[substrate.read]`, `leather_chair`
`[entity.set_posture, entity.clear_posture, room.broadcast_body_language]`,
`household_treasury` `[treasury.set_budget, treasury.transfer]`,
`maintenance_dial` `[maintenance.set_mode, maintenance.backup]`,
`speaker_platform` `[council.suggest, council.vote]`, `web_clipper` `[web.post]`,
`notify_team` `[slack.post]`, `journal_archiver` `[fs.write]`,
`observation_chart` `[chart.render, artifact.write]`.

`ScriptedItemLoader.diagnostics()` returns the loaded set with each item's manifest
snapshot. Its Javadoc calls it "the diagnostics surface for the `wyrd items list`
CLI" — **that command does not exist**; the method has no production caller.

---

## Writing and testing a script

There is no build step — scripts are read from disk and both kinds hot-reload.

```bash
./bin/wyrd start
# edit scripts/rooms/your_room.js   -> next event in that room picks it up
# edit scripts/items/your_item.js   -> reloaded within ~500ms
```

Tests worth reading as documentation, under
`scripting/src/test/java/org/wyrdsekai/scripting/`:

- `loader/ScriptLoaderTest.java` — user-overrides-base and cache-invalidation
  semantics.
- `sandbox/SandboxEscapeTest.java` — one test per escape vector; the security
  claims made concrete.
- `sandbox/ItemScriptExecutorTest.java` and `ItemScriptExecutorHookTest.java` —
  minimal `invoke(params)` examples and the `missing_hook` error shape.
- `sandbox/InheritMechanismTest.java` — `inherit()` with a stub resolver.
- `api/WorldApiTest.java` — timer clamping and emission callbacks.
- `api/ItemManifestParserTest.java`, `ItemManifestValidatorTest.java`,
  `ItemCapabilitySetTest.java` — the manifest contract.

Under `core/src/test/java/org/wyrdsekai/core/item/`:
`CalculatorHonoursItsAdvertisedOpsTest` (asserts an item does what its manifest
advertises — the pattern to copy), `ScriptedItemLoaderCommandsIT` (every bundled
item must still load at boot), `SubstrateFurnishingsLoaderTest`.

```bash
./gradlew :scripting:test :core:test
```

---

## Known gaps

Named plainly. Several of these are places where the specs describe more than the
code does — do not write a script against the spec without checking.

- **Room scripts have no resource limits.** No timeout, no statement cap, no heap
  cap. Only item scripts are bounded.
- **The graduated sandbox is production-dead.** `SandboxLevel` and
  `SandboxContextBuilder` are exercised only by tests. `SKILL_SERVER` is marked
  future and `ScriptHttpServer` is never injected.
- **`onPassivate` never runs.** The engine method exists with no caller.
- **`onTick` does not exist** under any name. Use `onTimer`.
- **Rate limits are declared and validated but never enforced.**
  `ItemManifest.RateLimit(perMinute, perHour, perDay)` is parsed and the ten
  gated capabilities must declare one, but `rateLimitFor()` has no production
  caller and nothing consults the limit at call time.
- **Item signatures are not verified.** `ItemManifest.signature` exists and the
  spec requires verification; no verification runs in the load pipeline.
- **`mixin()` does not exist.** The spec's `mixin("std/behavior/x")` is not
  implemented anywhere; the real mechanism is the append-and-chain described
  above. Likewise `inherit()` works only in the *item* executor, so a room script
  cannot call it.
- **Three named behavior mixins are missing** — the spec lists `shopkeeper`,
  `timer` and `quiz`; they are absent from disk and from `BEHAVIOR_MIXINS`.
- **Spec-only API names.** `world.say()`, `world.narrate()`, `world.llm.synthesize()`,
  `world.agent.speak()`, `world.agent.moveTo()` and `world.mcp.call()` appear in
  specs and do not exist. The starter-item names in the original design
  ("Library Card", "Searching Glass", "Quill", …) do not correspond to any file
  in `scripts/items/`.
- **The original room-scripting design is stale** in specific ways: it documented 4 hooks
  (there are 14), a 3-arg `onUse` (it takes 4), a 3-field `Hint` (it has 4), 6
  shipped scripts (there are 39), 2 path candidates (there are 6), and `narrate`
  as the only emission (18 are handled). It also lists as "future" several things
  that shipped.
- **The capability catalogue is broader than executor coverage.** A capability
  existing in `KNOWN_CAPABILITIES` means the validator knows its tier, not that an
  executor is wired. Check for a registered service before assuming a call lands.
