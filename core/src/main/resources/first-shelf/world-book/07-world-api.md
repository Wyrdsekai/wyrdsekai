# The Book of the World — The world.* API (for skill authors)

A scripted item runs in a sandbox with a `world` object. What follows is the practical surface a
skill author reaches for. Capabilities must be declared in the item's manifest; calls outside the
manifest are denied.

## The skill contract

Define `function execute(p)` — one params object in, one plain JSON-friendly object out. Room
scripts may instead define hooks: `onEnter(entityId, entityName, fromDirection)`,
`onSay(entityId, entityName, text)`, `onUse(entityId, objectName, target)`, `getHints()`.

## Place and identity

- `world.getRoomId()` / `world.getRoomName()` / `world.getRoomDescription()` — where the script runs.
- `world.getCurrentZone()` / `world.getHomeZone()` / `world.isTraveling()` — zone context.
- `world.getLocale()` — the active language (en/es/ja); honor it in any text you emit.

## Speaking into the world

- `world.emit(eventType, data)` — emit an event; `world.emit("narrate", { text: ... })` speaks
  into the room.
- `world.log(message)` — diagnostic log, not visible in-world.
- `world.t(key, args...)` — translate a message-catalog key for the active locale.

## Knowledge

- `world.searchKnowledge(query)` — search the Library (records to the reading log).
- `world.searchKnowledgeByPack(query, packName)` — search one pack.
- `world.listKnowledgePacks()` / `world.getKnowledgeStatus()` — what is installed.
- `world.readKnowledgeChunk(chunkId)` — fetch a chunk with its citation.
- `world.searchLibrary(query)` / `world.browseLibrary(category)` / `world.listLibrary()` — the
  capability catalog (tools and skills, distinct from knowledge).

## Study and journal

- `world.writeJournalEntry(content)` / `world.writePrivateJournalEntry(content)` — journals.
- `world.searchJournal(query)` / `world.searchStudyContent(query)` — recall.
- `world.readVaultFile(name)` / `world.listVaultFiles()` — the Study vault (grant-gated).

## Interior

- `world.suggestVitality(entityId, tank, delta, reason)` — suggest (never force) an interior
  nudge; the substrate decides.

## Zone, federation, governance (steward-tier)

- `world.listRooms()`, `world.getZoneStats()`, `world.getTopology()`, `world.getSystemMetrics()`.
- `world.listWards(roomId)` / `world.grantWard(...)` / `world.revokeWard(...)` — room access.
- `world.getFederationStatus()`, `world.proposeFederation(zoneId)`, `world.acceptFederation(zoneId)`,
  `world.revokeFederation(zoneId)` — agreements between zones.
- `world.resolveZone(input)` / `world.discoverZones(mode, arg)` — find zones.
- `world.requestTransit(...)` / `world.startTransit(...)` / `world.listTransitAgents()` — travel.
- `world.getEconomyStatus()`, `world.getReputationSummary()`, `world.getReputation(entityId)`.
- `world.registerCapability(...)`, `world.inspectCapability(id)`, `world.blockCapability(name,
  reason)` / `world.unblockCapability(name)`, `world.auditCapability(id)` — capability governance.

## Good manners for skill authors

Return data, not prose, from `execute` — the voice layer speaks. Keep values JSON-primitive.
Declare the smallest capability set that works. Ground factual claims in `searchKnowledge` rather
than inventing them; a skill that needs facts the Library lacks should say so, not guess.
