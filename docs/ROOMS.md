# Rooms, Scripts and Items

Wyrdsekai's world is made of **rooms**. A room is a place that you and your
companion can be in, such as your Study or the Library. Your companion is the
AI that lives in Wyrdsekai with you. Rooms connect through **exits**, which are
ways out named by a direction, such as `north`. The things in a room are
**items**. Some items are tools that the companions standing in the room can
use.

You can add rooms and items, change how they behave, and clear away the ones
you no longer want.

**What to do:**

- **To make a room or an item, ask your companion.** Just say what you want.
  [AUTHORING.md](AUTHORING.md) explains this, and how to start from a
  template instead.
- **To see your rooms or clear out old ones,** use `wyrd rooms`, below.
- **To change exactly how a room or an item behaves,** you edit its script. A
  script is a small JavaScript program. The part of this page headed
  [For people who write scripts](#for-people-who-write-scripts) is the
  reference for that.

A few more words used on this page:

- Your **Study** is your own private room, and the room you arrive in. Each
  person has their own. Only you, the people you let in, and the companion whose
  bondholder you are can walk in.
- The **steward** is the person who looks after the household, its
  administrator. The first account on a new install is the steward.

## See and tidy your rooms

```bash
wyrd rooms                                        # list rooms: who made each, its doors, who is in it
wyrd rooms demolish <room-id-or-name> [--with-objects]
wyrd rooms prune [--duplicates] [--stale <days>] [--with-objects] [--yes]
```

- `demolish` removes one room. A room with things in it is kept unless you
  add `--with-objects`. A room someone is in is not removed.
- `prune` removes several at once. On its own it only lists what it would
  remove. `--stale 30` adds rooms that were made and that nobody has been in
  for 30 days. `--duplicates` adds later copies of a room with the same name.
  Add `--yes` to remove them.
- The rooms the world started with, a companion's Home, and anyone's Study
  or Workshop are never removed.

Only the steward can demolish or prune rooms. Log in first with `wyrd login`.

## How scripts work

Wyrdsekai is built like a MUD, a text world made of rooms. Each room's
behaviour is a **room script**, a JavaScript file that decides what happens
when someone enters, speaks or uses something there. **Scripted items** are
JavaScript files too, and double as tools that companions in the room can
call.

Scripts reload by themselves, with no rebuild or restart. A changed room
script takes effect at the next event in that room. A changed item reloads
within about half a second.

To change a room that comes with Wyrdsekai, put a file with the same name in
the `scripts/` folder of your data directory. Your copy is used instead.
Everyone's Study uses `study.js` unless there is a file for that particular
Study.

---

## For people who write scripts

The rest of this page is a reference for writing room and item scripts. Where
this page and the code disagree, **the code is right**. The
[Known gaps](#known-gaps) section names the places where older design notes
drifted, so nobody writes against a stale idea of the API.

### Where scripts live

| Kind | Bundled | Steward / companion-added |
| --- | --- | --- |
| Room scripts | `scripts/rooms/*.js` (39 shipped) | `<dataDir>/scripts/` |
| Room template bases | `scripts/std/room/*.js` (10) | none |
| Behavior mixins | `scripts/std/behavior/*.js` (5) | none |
| Item scripts | `scripts/items/*.js` (61) | `~/.wyrdsekai/items/*.js` |
| Item base scripts | `scripts/std/*.js` (12) | none |

A file in `<dataDir>/scripts/` overrides the shipped script of the same name.
If two items share an id, the second one found wins and a warning is logged.

**If scripted behaviour has gone dead, check the log first.** When Wyrdsekai
cannot find the room scripts it logs a warning, and **every room script is
silently switched off**. That has shown up before as "`use` does nothing" and
"travel does nothing". The search order is under
[For developers](#for-developers).

### What a script can reach

Scripts run in a sandbox, a closed space that only lets them reach what
Wyrdsekai hands them. A room script gets exactly one global: `world`. There is
no `console`, no `http`, no `fs`, no `crypto` and no `inherit()`. `JSON`
works. A fresh sandbox is built for every hook call.

**Limits.** Item scripts are stopped after 25,000 statements or 120 seconds,
because a script may chain service calls and one call to a local AI model
takes tens of seconds. Each room hook call is stopped after 1,000,000
statements or 5 seconds of processor time, whichever comes first. Time a hook
spends waiting for another program to answer (an MCP server over the network)
does not count; work done on the room's own thread, such as a local search,
does. A
stopped hook leaves a warning in the log that names the room and the hook, and
the room goes on with its next event. `WYRDSEKAI_ROOM_SCRIPT_STATEMENTS` and
`WYRDSEKAI_ROOM_SCRIPT_CPU_MS` change the two limits. The bundled room scripts
need at most a few hundred statements per call. There is no memory cap.

### Room script anatomy

A room script is a plain script, not a module, that defines top-level
functions called **hooks**. Wyrdsekai looks each hook up by name and calls it
if it is there. A missing hook is quietly skipped. It is logged at DEBUG,
deliberately not WARN.

| Hook | Signature | Status |
| --- | --- | --- |
| `onEnter` | `(entityId, entityName, fromDirection)` | all 39 shipped rooms |
| `onLeave` | `(entityId, entityName, direction)` | 2 shipped rooms |
| `onSay` | `(entityId, entityName, text)` | 38 shipped rooms |
| `onUse` | `(entityId, objectName, target, entityName)`, **four** args | 35 shipped rooms |
| `onEmote` | `(entityId, entityName, text)` | |
| `onTake` / `onDrop` | `(entityId, objectName, objectId)` | |
| `onExamine` | `(entityId, targetId, targetName)`; self-examine passes `(entityId, entityId, "self")` | |
| `onActivate` | `()`, when the room starts up | |
| `onPassivate` | `()` | **dead: nothing ever calls it** |
| `onTimer` (or a custom name given to `scheduleTimer`) | `(timerId)` | no shipped room uses timers |
| `onToolCall` | `(entityId, toolName, argsJson)` | |
| `onWorkbenchResult` | `(entityId, skillName, ok, summary)` | |
| `getHints` | `()` → array | all 39 shipped rooms |
| `getToolDefinitions` | `()` → array | 1 shipped room |

There is **no `onLook` and no `onTick`**. `getHints()` is the hook for when
someone looks, and `onTimer` is the one that repeats.

**Hints** are suggested actions. `getHints()` returns objects with four
fields: `label`, `intent`, `action` and `labelKey`, the last one for
translation. `action` is a typed instruction to the client (`say:<text>`,
`go:<direction>`, `look`), **not** prose. This matters. The phone apps show
hints as buttons you tap. A button that hides a movement inside a `say:` makes
your companion hear you say "go out" instead of you moving.

Whatever `getToolDefinitions()` returns must be JSON-serializable.

### Two real scripts

`scripts/rooms/engine-room.js` is the smallest complete shape, fully
translated:

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

`scripts/rooms/oracle.js` offers a tool to companions:

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

Note the `world.isAgent(entityId)` check in `onSay`. Without it, a room reacts
to its own companions talking, and loops.

### The `world` API for room scripts

`world` has 112 methods. The main groups:

**Room state:** `getRoomId()`, `getRoomName()`, `getRoomDescription()`,
`getProperty(key)`, `setProperty(key, value)`, `getEntities()`, `getObjects()`,
`findEntity(id)`, `findObject(id)`, `isAgent(entityId)`, `getAdjacentSummary()`,
`random(max)`, `log(message)`.

**Zone:** `getCurrentZone()` (defaults to `"local"`), `getHomeZone()`,
`isTraveling()`.

**Output:** `emit(eventType, data)`. The room handles 18 types: `narrate`,
`description_changed`, `hints_updated`, `property_changed`, `object_added`,
`object_removed`, `entity_removed`, `timer_cancelled`, `broadcast`,
`oracle_action`, `exit_locked`, `exit_unlocked`, `vitality_suggested`,
`exit_creation_requested`, `exit_removal_requested`,
`room_creation_requested`, `config_apply_requested`, `command`. Across the 39
room and 61 item scripts there are 662 `narrate` emissions, 31 `command` and 2
`oracle_action`, so `narrate` is the workhorse. **`emit` deep-copies its data
and turns every value into a string**, so nested objects arrive as strings.

**Changing the world:** `createObject(id, name, description, takeable)`,
`createObjectWithEffects(...)`, `applyObjectEffects(objectId, entityId)`,
`removeObject(id)`, `removeEntity(id)`, `requestCreateRoom(...)`,
`requestAddExit(direction, targetRoomId, label)`, `requestRemoveExit(direction)`,
`lockExit(direction)`, `unlockExit(direction)`. The `request*` names are
deliberate. The script asks the room to make the change rather than reaching
into the world itself.

**Timers:** `scheduleTimer(timerId, intervalSeconds, hookName)`,
`cancelTimer(timerId)`. The interval is held between 1 and 3600 seconds, and a
room may have at most 16 timers running.

**Translation:** `t(key, ...args)`, `getLocale()`. Three languages (English,
Spanish, Japanese) are wired end to end and checked for missing keys. Do not
hardcode text that people will see.

**Vitality:** `suggestVitality(entityId, tank, delta, reason)`. Note that it
*suggests*.

**Methods that work only in certain rooms.** Some methods check which room is
calling and refuse anywhere else:

- vault reads (`readVaultFile`, `listVaultFiles`) only in `vault`. They are
  capped at 4096 bytes and reject `..`, `/`, `\` and NUL.
- bridge administration (`listRooms`, `listWards`, `grantWard`, `revokeWard`,
  `getZoneStats`, `getTopology`) only in `bridge`.
- desktop launch (`launchApp`, `launchFile`, `launchUrl`) only in a room whose
  id starts with `study`.

**The rest** mostly return `String`. Grouped by prefix:

- federation and travel between zones (`getFederationStatus`,
  `proposeFederation`, `requestTransit`, `resolveZone`, `discoverZones`, …)
- library and knowledge (`searchLibrary`, `searchKnowledge`,
  `installKnowledgePack`, `readKnowledgeChunk`, plus the proposal and approval
  set)
- Study (`writeJournalEntry`, `writePrivateJournalEntry`, `searchJournal`,
  `searchStudyContent`)
- voice governance (`formatVoiceProfile`, `setVoiceClause`, `freezeVoice`,
  `revertVoice(int)`, …). Every method that changes something takes a
  `reason`, which appears in the printed history.
- the capability registry (`inspectCapability`, `registerCapability`,
  `blockCapability`, `auditCapability`)
- governance (`listProposals`, `submitProposal`, `castVote`, `tallyVotes`)
- AI models (`getInferenceStatus`, `infer(options)`, `extract(itemId)`)
- configuration (`configGet`, `configList()`, `configSet()`, `configApply()`)
- network grants (`netAllow`, `netRevoke`, `netList`)
- `zoneCommand(command, payload)`

`WorldApi.java` lists all 112.

### `world.mcp()`

MCP lets a room call a connected service, such as a news reader.

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

The result always has `success`, `data`, `error`, `cost`, `latencyMs`,
`serviceId` and `toolName`. Empty values arrive as an empty string or 0.0,
never null, so a script never has to check for null.

The caller's identity is filled in by Wyrdsekai, not the script. The caller is
the current entity, or the room if there is none. The zone comes from the
room. The room's own id is written in **after** the script's parameters, so a
script cannot pretend to be another room.

Ten shipped rooms use it: `atelier`, `workshop`, `golem-workshop`,
`scriptorium`, `scrying-pool`, `sky-dock`, `study`, `observatory`, `hearth`,
`heralds-hall`.

### Room templates

A template is a starting shape for a new room. Templates are defined in code,
not files, but each points at a real base script under `scripts/std/room/`.

Ten ship: `hub`, `study`, `workshop`, `library`, `market`, `garden`, `hall`,
`observatory`, `gate`, `empty`. A room made from a template gets its base
script copied into the user scripts folder as `<roomId>.js`.

`scripts/std/room/empty.js` shows what a base script looks like. It has a
`room` settings holder with setters, and the usual hooks read from it:

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

### Behavior mixins

A behavior mixin is a small script *added to the end* of a room's script, not
loaded separately. Five ship in `scripts/std/behavior/`: `greeter`,
`narrator`, `announcer`, `recorder`, `guardian`. Those are the only five names
accepted.

A companion adds one with `{"action":"add_script", "room_id":"<room>",
"script":"greeter"}`. The mixin is added to the room's script, a check stops
the same mixin being added twice, and the script is read again.

Mixins chain hooks by **assignment, not declaration**. A `function onEnter`
declaration is hoisted, so it would replace the room's own hook and then call
itself as the "previous" one:

```js
var _greeter_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_greeter_prev_onEnter) _greeter_prev_onEnter(entityId, entityName, fromDirection);
    if (greeter.enabled()) {
        world.emit("narrate", { text: greeter.message().replace("{name}", entityName) });
    }
};
```

`scripts/behavior/`, without `std/`, is a different folder holding Python
probe scripts. It has nothing to do with this.

### Items as tools

A scripted item is one `.js` file that exports a manifest and an `invoke`
function. The manifest describes the item: its name, what it may do, and how
it is called. If there is no `invoke`, `execute` is used. `exports` and
`module.exports` both work, so the `exports.manifest = {…}` style works.

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

A manifest can also carry `rateLimits`, `dataSensitivity`, `installWarnings`,
`externalDomains`, `mcpServers`, `safeSlots`, `signature`, `installHandler`,
`uninstallHandler`, `manifestVersion` and `spendLimitUsdPerDay`.

**Keep the manifest at the top of the file.** Only the first 16 KB are
searched for `exports.manifest = {`, and only that part is read.

`invoke(params)` takes **one** argument, and `world` is a global. There is
**no event loop**, so `async`, `await` and Promises will not resolve. Write
synchronous code.

The item-side `world` offers more than 550 methods across about 80 groups,
such as `self`, `library`, `web`, `net`, `llm`, `agent`, `inventory`, `room`,
`journal`, `soul`, `forge`, `chronicle` and `treasury`. A group that does not
exist answers every call with
`{ok:false, error:{code:'adapter_unavailable', …}}` instead of throwing. Item
scripts also get `http`, `html` and `crypto` globals, plus `inherit(path)` to
pull in a base script from `scripts/std/`. `http` is gated like `world.web.*`:
`http.get` needs `web.fetch_raw`, `http.post` needs `web.post`, and the site
must be in the manifest's `external_domains`.

MCP has **two different shapes**. Room scripts call the function
`world.mcp(serviceId, toolName, params)`. Item scripts use
`world.mcp.invoke(server, tool, args)`, alongside `.list_servers()`,
`.list_tools()`, `.grants()`, `.grant()` and `.revoke()`.

#### The two rules that catch people out

**1. `commands` is required.** An item without a `commands` block, or with an
entry whose label is blank, is rejected when it is registered or reloaded. A
tool must say how it is used. A step at boot can add a default entry, and records
what it added in `data/manifest_audit.json`.

**2. Exactly one required parameter, and so exactly one way to call it.** The
comments in `scripts/items/calculator.js` explain this at length, because it
was learned twice:

- An item whose parameters are all optional gets called with *nothing*. The
  model will not fill a parameter it does not have to.
- An item with one required parameter **and a second valid way to call it**
  gets the second way packed into the required text. The call is rejected as
  unreadable, and the companion has to tell its bondholder, the person it is
  bonded to, that it failed at something it knew how to do.

So give the tool one required slot, the one thing it cannot work without, and
describe exactly what belongs in it. Keep other ways of calling it out of what
the model sees, and handle them in `invoke()` for code that calls it directly.
Also, `action` is a reserved key. It names the tool, so an `action` inside the
parameters takes over the call.

The model is shown `params[]` if you declare it. Without it, a schema is built
from `commands[]`, and failing that, a single optional free-text `query`. That
last fallback is the failure described above. Declare `params`.

#### Capabilities and tiers

A **capability** is a named permission, such as `self.name`,
`agent.mailbox.send` or `web.post`. The item's author lists the ones it needs.
You do **not** choose a tier, the level of trust a capability needs.
Wyrdsekai works it out from the name, using a catalogue of 634 known
capabilities. Each has a default tier from 1 to 7.

The catalogue is `KNOWN_CAPABILITIES` in
`scripting/src/main/java/org/wyrdsekai/scripting/api/ItemManifestValidator.java`.
Read it when you need to know what a name will cost you. Wildcards end in `.*`
(`github.*`). **A name the catalogue does not know is an error**: the manifest
fails validation and the item is not loaded. Check what the validator says.

Tier 1 comes free. About 130 capabilities are granted to every item without
declaring anything: `math.*`, `json.*`, `regex.*`, `date.*`,
`crypto.hash/hmac/uuid/random_bytes`, `time.*`,
`room.id/name/description/entities/objects/exits`, `self.*`, `zone.*`,
`inventory.list/use/examine`, `library.search/read`, `journal.search/recent`.
That is why `calculator.js` declares `capabilities: []`.

Ten capabilities are **rejected outright without a `rate_limits` entry**:
`web.post`, `web.put`, `web.delete`, `web.fetch_raw`, `mcp.invoke`,
`agent.mailbox.send`, `agent.broadcast`, `net.ssh`, `net.scp`, `net.household`.
Tier 7 covers spending that cannot be undone, and needs the steward's token.

When an item is refused a capability at run time, the caller gets
`{capability_denied: "<cap>", error: "…"}`. Every item with a manifest runs
under it: bundled items, household items and items a coding helper wrote. On
top of that, scripts that companions craft and scripts that visitors carry in
run under a fixed ceiling, so their manifest cannot ask for more than it. An
item that uses another item (`world.inventory.use`) lends it no more than its
own capabilities. Only the items built into the server itself (the starter kit,
the network items, the Study and Hearth furnishings) have no manifest and run
with the server's authority, and only when the script is exactly the one the
server ships. Until September 2026 bundled and household items were not checked
at all.

For an install that has household items written before the manifest was
enforced, `WYRDSEKAI_ITEMS_ALLOW_UNDECLARED=true` lets bundled and household
items run as they used to. Each call an item makes without declaring it is
then logged once as a warning, so the manifests can be fixed. The setting is
off by default; crafted and visitor items keep their ceiling either way.

**Credentials.** `world.safe.get(slot)` does not return the secret. It returns
a reference, `{{safe:slot}}`. Put the reference in a request header, for
example `Authorization: "Bearer " + world.safe.get("github.token")`, and the
secret is put in its place as the request leaves. If the answer contains the
secret, the item sees `[secret]` instead. The slot must be listed in the
manifest's `safe_slots`, next to the `safe.get` capability. A reference in a
URL or a request body is sent as written.

#### The standard item library

61 items ship in `scripts/items/`, and 20 declare no capabilities at all. A
sample with their declarations, to show the range: `calculator` `[]`,
`journal` `[journal.write]`, `pinboard` `[pinboard.pin]`, `chronicle`
`[chronicle.read]`, `repair_mirror` `[substrate.read]`, `leather_chair`
`[entity.set_posture, entity.clear_posture, room.broadcast_body_language]`,
`household_treasury` `[treasury.set_budget, treasury.transfer]`,
`maintenance_dial` `[maintenance.set_mode, maintenance.backup]`,
`speaker_platform` `[council.suggest, council.vote]`, `web_clipper` `[web.post]`,
`notify_team` `[slack.post_message]`, `journal_archiver` `[fs.write]`,
`observation_chart` `[chart.render, artifact.write]`.

### Writing and testing a script

There is no build step. Scripts are read from disk, and both kinds reload by
themselves.

```bash
./bin/wyrd start
# edit scripts/rooms/your_room.js   -> next event in that room picks it up
# edit scripts/items/your_item.js   -> reloaded within ~500ms
```

The tests are good worked examples. They are listed under
[For developers](#for-developers). Run them with:

```bash
./gradlew :scripting:test :core:test
```

### Known gaps

Stated plainly. In several places the original design describes more than the
code does, so do not write a script against the design without checking.

- **The graduated sandbox is not used in production.** Its levels are
  exercised only by tests. `SKILL_SERVER` is marked future, and its script
  HTTP server is never connected.
- **`onPassivate` never runs.** The engine method exists, but nothing calls it.
- **`onTick` does not exist** under any name. Use `onTimer`.
- **Rate limits are declared and checked, but never enforced.** The
  `rate_limits` block is read, and the ten gated capabilities must declare one,
  but nothing looks at the limit when a call is made.
- **Item signatures are not verified.** A manifest can carry a `signature`,
  and the design requires checking it, but no check runs when items load.
- **`mixin()` does not exist.** The design's `mixin("std/behavior/x")` is not
  built anywhere. The real mechanism is the append-and-chain described above.
  Likewise `inherit()` works only for *items*, so a room script cannot call
  it.
- **Three named behavior mixins are missing.** The design lists
  `shopkeeper`, `timer` and `quiz`. They are not on disk and not accepted.
- **Design-only API names.** `world.say()`, `world.narrate()`,
  `world.llm.synthesize()`, `world.agent.speak()`, `world.agent.moveTo()` and
  `world.mcp.call()` appear in the design and do not exist. The starter item
  names in the original design ("Library Card", "Searching Glass", "Quill", …)
  match no file in `scripts/items/`.
- **The original room-scripting design is out of date** in specific ways. It
  documented 4 hooks (there are 14), a 3-argument `onUse` (it takes 4), a
  3-field hint (it has 4), 6 shipped scripts (there are 39), 2 places to look
  for scripts (there are 6), and `narrate` as the only emission (18 are
  handled). It also lists as "future" several things that shipped.
- **The capability catalogue is broader than what is wired up.** A capability
  in `KNOWN_CAPABILITIES` means the validator knows its tier, not that anything
  carries it out. Check that a service is registered before assuming a call
  lands.

---

## For developers

How the pieces above are implemented. For the wider picture see
[ARCHITECTURE.md](ARCHITECTURE.md).

**Finding scripts.** Resolution is a search, not a fixed path, because a
package-installed service runs with its working directory at `/`, where a bare
relative path finds nothing. For room scripts (`server/.../Main.java`) the
first hit wins:

1. `$WYRDSEKAI_SCRIPTS_DIR`
2. `scripts/rooms`, then `../scripts/rooms` (source-mode runs)
3. `<WYRDSEKAI_HOME>/rooms`
4. `/opt/wyrdsekai/rooms` (`.deb`), `/usr/local/wyrdsekai/rooms` (`.pkg`)

Items (`core/.../item/ScriptedItemLoader.java`) use the same approach against
`scripts/items`, with `~/.wyrdsekai/items` as a second search folder. The
user scripts folder is `SystemPaths.scriptsDir()`, checked first. A room id
starting with `study-` falls back to `study.js`.

**Reloading.** `ScriptLoader` caches room scripts by file modification time
and re-reads them on the next `load()`. `load()` runs on *every* hook call.
`ScriptedItemLoader.startWatching()` runs a daemon thread with a
`WatchService` over every item search folder. It gathers create, modify and
delete events within 500 ms into one `reloadAll()`.

**The sandbox.** GraalJS, via `org.graalvm.polyglot:polyglot:25.0.2` and
`org.graalvm.polyglot:js:25.0.2` (`scripting/build.gradle.kts`). Host access is
`HostAccess.EXPLICIT`: **only Java methods annotated `@HostAccess.Export` are
reachable from JS**. `allowMapAccess` and `allowListAccess` are on, so a `Map`
returned by `world.mcp()` reads as an object rather than `undefined`. Also
`allowIO(false)`, `allowCreateThread(false)`, `allowNativeAccess(false)`.
`SandboxEscapeTest` asserts that
`Java.type('java.lang.Runtime' | 'java.io.File' | 'java.lang.System' |
'java.lang.ProcessBuilder' | 'java.net.URL' | 'java.lang.ClassLoader')` all
throw. A fresh `Context` is built and closed for every hook call. `JSON` is
GraalJS's own.

**Resource limits.** `ResourceLimits` defines six profiles:

| Profile | Statements | Time | Heap | Stack |
| --- | --- | --- | --- | --- |
| `DEFAULT` | 10 000 | 5 s | 16 MB | 100 |
| `TRUSTED` | 50 000 | 10 s | 32 MB | 200 |
| `STRICT` | 5 000 | 2 s | 8 MB | 50 |
| `ITEM_SCRIPT` | 25 000 | 120 s wall clock | 32 MB | 100 |
| `ROOM_SCRIPT` | 1 000 000 | 5 s CPU | 0 | 0 |
| `UNLIMITED` | 0 | 0 | 0 | 0 |

**Two are used.** `ItemScriptExecutor` applies `ITEM_SCRIPT`: a real
GraalJS `statementLimit(25_000)` plus a 120 s watchdog on a virtual thread that
force-closes the context. `ScriptSandbox` applies `ROOM_SCRIPT` to every room
hook call (`RoomScriptEngine` reads the two settings): `statementLimit(1_000_000)`
plus `ScriptWatchdog`, which measures the CPU time of the thread running the hook
and cancels the context after 5 s of it. `DEFAULT`, `TRUSTED` and `STRICT` have
no callers outside their own unit test, and nothing reads `heapLimitBytes` or
`stackDepthLimit`.

`SandboxLevel` (`ROOM_SCRIPT`, `SKILL_BASIC`, `SKILL_DATA`, `SKILL_SERVER`,
`SKILL_FULL`) and `SandboxContextBuilder` describe a graduated escalation
model. **Neither is on a production path.** `SandboxContextBuilder.build()`
is called only from tests, and `AgentPermissions.maxSandboxLevel()` computes a
level that nothing then uses to build a context. `WorkbenchSkillExecutor` uses
`ItemScriptExecutor` instead. `ScriptHttpServer` is never injected. Read
`SandboxLevel` as intent, not as a control.

**Hooks.** `getHints()` returns
`record Hint(String label, String intent, String action, String labelKey)`.
`getToolDefinitions()` is called by appending a `JSON.stringify` wrapper to the
script source.

**World API.** `world` is one host object,
`scripting/.../api/WorldApi.java`: about 1,900 lines and 112
`@HostAccess.Export` methods. `RoomActor.processEmissions()` handles the 18
emission types. `emit` runs `String.valueOf()` on every value. The timer cap is
`RoomActor.MAX_ROOM_TIMERS`.

**`world.mcp()`.** `RoomMcpBridge` replaces nulls before the map crosses
into JS. The room id goes in as `_room`, after the script's own params, so a
script cannot reach another room's mounted state. `world.mcp()` only works because `RoomMcpBridge.install(mcpGateway)` runs at
boot. `RoomActor` builds its `RoomScriptEngine` without a provider, so until
that install landed, every `world.mcp()` call in every room answered *"MCP
gateway not available"*.

**Templates.** `RoomTemplate` is a Java record registered in
`StandardRoomLibrary`:

```java
record RoomTemplate(String name, String displayName, String description,
                    String baseScript, List<DefaultObject> defaultObjects,
                    Map<String,Double> defaultImprint, Map<String,String> defaultConfig)
```

`instantiate(templateName, roomId, config, connectTo)` returns a room seed. The
base script is delivered through `RoomCommand.SetBehaviorScript` and lands in
the user scripts folder as `<roomId>.js`.

**Mixins.** `CompanionActor.BEHAVIOR_MIXINS` accepts exactly the five names.
`add_script` routes to `RoomCommand.SetBehaviorScript(..., append = true)`.
`RoomActor` joins the mixin onto the existing user script, with a dedup guard
on the mixin's first line, and invalidates the loader cache.

**Items.** The executor polyfills `exports` and `module.exports`. The manifest
is found by regex-locating `exports.manifest = {` within the first 16 KB,
brace-matching to the close, and evaluating **only that snippet** in a
throwaway context. The item-side `world` is a JS `Proxy` over `ItemWorldApi`.
`ItemManifestValidator.requireCommands` throws
`ManifestCommandsMissingException` when `commands` is missing.
`ToolItem.toToolDefinition()` builds the JSON Schema the model sees.
`ItemManifest.RateLimit(perMinute, perHour, perDay)` is parsed, but
`rateLimitFor()` has no production caller. At run time
`ItemCapabilitySet.require(cap)` throws `CapabilityDeniedError`. Bundled disk
items and starter-kit items load as `UNRESTRICTED`. The `CRAFTED_ALLOW`
ceiling applies to agent-crafted and visitor-carried scripts.

`ScriptedItemLoader.diagnostics()` returns the loaded set with each item's
manifest snapshot. Its Javadoc calls it "the diagnostics surface for the
`wyrd items list` CLI". **That command does not exist**, and the method has no
production caller.

**Tests worth reading as documentation**, under
`scripting/src/test/java/org/wyrdsekai/scripting/`:

- `loader/ScriptLoaderTest.java`: user-overrides-base and cache-invalidation
  behaviour.
- `sandbox/SandboxEscapeTest.java`: one test per escape route; the security
  claims made concrete.
- `sandbox/ItemScriptExecutorTest.java` and `ItemScriptExecutorHookTest.java`:
  minimal `invoke(params)` examples and the `missing_hook` error shape.
- `sandbox/InheritMechanismTest.java`: `inherit()` with a stub resolver.
- `api/WorldApiTest.java`: timer clamping and emission callbacks.
- `api/ItemManifestParserTest.java`, `ItemManifestValidatorTest.java`,
  `ItemCapabilitySetTest.java`: the manifest contract.

Under `core/src/test/java/org/wyrdsekai/core/item/`:
`CalculatorHonoursItsAdvertisedOpsTest` checks that an item does what its
manifest advertises, and is the pattern to copy. `ScriptedItemLoaderCommandsIT`
checks that every bundled item still loads at boot. Also
`SubstrateFurnishingsLoaderTest`.
