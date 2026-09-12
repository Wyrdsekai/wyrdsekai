# Changelog

All notable changes to Wyrdsekai are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Changed
- **The bundled CodeZaiku is 0.3.3.** Its installer and `wyrd coding update codezaiku` take the
  build that carries its own Java runtime when the machine has no Java 21, and the librarian's
  does the same from ResearchZosho 0.1.6; `wyrd researcher setup` no longer warns about Java.
- **The librarian's desk reads a write-up in pieces and says where a run is.** `read <id>` asks
  the librarian (0.1.7) for the answer alone, capped, and says how much stayed on the shelf,
  so a long report no longer overflows her turn; `jobs` shows a running job's phase, round and
  workers, or that it is waiting for a sleeping model server.
- **A third way to link the librarian:** `wyrd researcher link --stdio "npx -y
  @wyrdsekai/researchzosho-mcp"`, the launcher both siblings publish on npm.

### Fixed
- **MCP.md no longer claims a general MCP door at `POST /mcp`.** The household serves one
  surface, the library door at `POST /mcp/library`; the world door stays closed until calls
  carry a caller identity, and the document now says so.

## [0.3.1] — 2026-09-11

A point release for the companion's own reach: what she names, she can use; what she is
forced to choose between includes declining; what did not happen is not reported as done.

### Fixed
- **She can find her way back to a room she made.** `go_to_room` matched only the exits of
  the room she was standing in, so a room she had built the day before, two doors away, did
  not exist to it, and she made it again: nine rooms in three days, most of them the same
  room. Now a room anywhere in the zone is found by its name, with the doors between from the
  map, and her own home is reachable by its id however the model spells it. A room's name is
  cut at its first seam when the model pads it with the description (a 675-character room
  name, an id the length of a paragraph, every door list in every prompt carrying all of it);
  the rest becomes the description. Asking to make a room that already exists walks her to
  it instead.
- **Tired is not the same as not missing anyone.** Below the rest line the decide step chose
  rest unconditionally, and her energy sat below it all day, so "check in on someone I care
  about" lost to rest on every tick of a two-day absence and never became a note on anyone's
  desk. A relational want that weighs enough now goes out at low energy; everything else
  rests. A want that came due while she was mid-thought is held and enacted at the next tick
  she is free instead of dropped.
- **The phone door on the relay now uses the node's NKey too.** After `wyrd relay
  register-nkey`, the Between bridge and the session transport switched to NKey auth but the
  MCP relay leg kept dialling with the deprecated household password, so the password record
  had to stay alive just for it. It follows the same rule as the other two legs now: NKey when
  `WYRDSEKAI_RELAY_USE_NKEY=true`, password only as the fallback, and it says which it used.
  The peer-training relay leg follows the same rule.
- **The key chest shows the companion the household's snapshots.** It opened on "bare cedar" for
  her while ten snapshots sat in the backups directory: only the player-side Home provider was
  ever handed the backup orchestrator, so on every other path, the companion's and the ssh
  Study's, the item read an empty default. One holder now answers on all of them.
- **A forced "act or decline" surface always offers decline.** When her reply named work she had
  not yet reached for, the loop narrowed her tools to the build tools and required a call, but
  decline was on that surface only when the ranker happened to rank it, and eight times in a
  week it did not: one tool, required. Every build that week came through that door. Decline is
  added whenever it is missing.
- **A refused dispatch is reported to the loop as refused.** The handler spoke the refusal and
  returned; the loop was then told the action executed, and she announced a build that never
  started a second after saying it could not. The observation now carries what happened and how
  to proceed. The dispatch schema also says to leave the workspace out unless a person named a
  directory, which is where an invented path came from.
- **`use_item` reaches what she names.** A placed item is listed to her by its object name, and a
  second copy of the same item gets "-2" while the tool is still "chest"; a fixture of the room
  is not a tool; an action she is told to use, such as `workbench_submit`, is an action rather
  than an item. All three were refused as "not permitted", 63 times in a week, when nothing
  about permission was in question. The placement suffix is dropped, a fixture is examined, a
  known action is passed through, and the refusal names the nearest tools instead.
- **An item that declares commands it never reads is flagged.** A chest that answered every
  command, including `create`, with the sentence telling you to type `create` passed every
  check. The loader and `wyrd items check` now report a manifest with several commands whose
  `invoke()` never reads `params.args`.
- **A forced build surface offers the template tools, not only the workshop.** When her
  reply named something to make, the forced surface held the coding dispatch and decline;
  she said "I meant to call craft_from_template" and the gift went to the coding backend
  instead. `craft_from_template` and `create_room_from_template` are on that surface now.
- **An item may not take the name of a builtin action.** The tool list bakes the runtime's
  own actions in and drops a loaded script with the same id, so an item named
  `craft_from_template` could never be reached and sat as a dead object in a room. The loader
  refuses such an item with the fix in the message, and `wyrd items check` reports it.

## [0.3.0] — 2026-09-10

The node keeps itself current, and the library learns to remember what was concluded from it.

### Added
- **`wyrd update`: the node knows its release and installs the next one.** `wyrd update` says
  what release runs (the `VERSION` file the installer ships), what the latest is (GitHub, asked
  once a day) and the mode; `wyrd update now [VERSION]` downloads this platform's installer from
  the GitHub release, verifies it against the release's `SHA256SUMS` and runs the package's own
  upgrade (databases snapshotted, service restarted); `wyrd update auto on` lets the node do that
  itself at a quiet moment inside its window (`WYRDSEKAI_UPDATE=check|auto|off`,
  `WYRDSEKAI_UPDATE_WINDOW`, default 03:00–05:00 local; nothing in flight for ten minutes; one
  attempt per version per day; never on a dev build). The installer runs outside the service so
  the service it stops is not the one running it. `wyrd status` and `wyrd doctor` say when a
  newer release exists, on Linux, macOS and Windows; `/api/update/status` carries the same. On
  Windows auto mode downloads and verifies the `.msi` and `wyrd update now` finishes it, since the
  install needs an elevation prompt. The one-line installers on wyrdsekai.org now install the
  latest release rather than a pinned one (`WYRDSEKAI_VERSION` picks one).
- **The two programs a household leans on stay current too.** `wyrd coding update codezaiku`
  installs the coder's newest GitHub release, verified against that release's sums, whatever
  version this Wyrdsekai shipped with (`--manifest` follows the pin); `wyrd researcher update`
  runs the librarian's own updater; `wyrd doctor` and `wyrd researcher status` say when either is
  behind. The bundled CodeZaiku is 0.3.0; the librarian client reads ResearchZosho 0.1.2 (its
  `version` in `library_status`, `library_inbox`, `library_serials`, the typed open-questions
  queue).
- **Text Embeddings Inference as the served embedder** (`WYRDSEKAI_EMBED_SERVER=tei`): the same
  bge-m3 on Hugging Face's TEI, several times faster than llama-server for the same model on the
  same card, one image per compute capability (Turing through Blackwell, and a CPU image), the
  model fetched on first start. `wyrd setup` offers it on NVIDIA tiers; a running node switches
  with one config key and no re-index, since the vectors are the same model's.
- **ResearchZosho's public contract, 1.0.** The reference librarian shipped and settled its contract
  string on 1.0 with the wire this client already spoke; the docs are re-pinned, the wizard's default
  address is the librarian's own (`127.0.0.1:4649`), and it speaks the release's `reader` commands
  with the older `patron` word as a fallback. The desk gains `sharpen: <question>` (the librarian
  rewrites a rough question before anything runs and returns the text to hand over) and
  `explain <id> [beginner|familiar]` (a reading aid written from the shelves, unsupported sentences
  marked, never a record); every entry it renders now says how many independent sources stand
  behind it and what the librarian's review decided, and an ask that reads as "what changed since…"
  is shown as the changes it routed to. A pushed `revised` or `supplied` notice is written on the
  companions' findings that cite the entry as a note with the state kept, rather than ignored.
- **Items are checked against the world API before they can fail.** The loader resolves every
  `world.*` call in a script against what this build serves; a copy whose calls do not exist
  never replaces a working copy of the same item, and alone it loads with the fix in the log
  and an entry in the manifest audit (`misWired`). `wyrd items check [dir…]` runs the same
  check over the bundled items and the household's own and exits 1 on any mis-wired script.
  The check also counts arguments: a call that hands a method a number of arguments no overload
  takes is named with the arities it accepts, since host methods have no defaults and the call
  would die with "no applicable overload". Found the way a stale copy of the journal in a data
  directory called a renamed method and died on `use` while the bundled copy worked.
- **A same-named copy from another author replaces a loaded item only when its version is
  newer.** Three items a coding backend wrote took the names of bundled items and, because the
  household directory scans second, replaced them. Now the loaded copy stands, the log names the
  fix (give it its own name, or bump the version if it is meant to replace), the manifest audit
  lists it under `shadowed`, and registering such a file at runtime reports that it did not
  register instead of pointing at the bundled item. The same author re-shipping the same version
  still replaces, so an edit in place or a re-install behaves as before.
- **`world.journal.write({title, body})`.** The object form scripts have used beside the string
  form: the title heads the entry, the text is `body` (or `content`, `text`, `entry`), and every
  other key travels as an option. It failed with "no applicable overload" before.
- **`params.args` is always a string** on every path into a scripted item, an empty one when
  nothing was typed, as the items-as-tools contract says. An item that did `params.args.trim()`
  died when called with no arguments.
- **`wyrd researcher`.** A wizard that installs [ResearchZosho](https://researchzosho.org), the
  research librarian, runs its own setup, and connects this node as a patron over HTTP with a
  bearer token for the household's identity (`setup`), or connects to a librarian already
  running here, on another machine, or as a child process (`link`, `link --stdio`); `status`
  and `unlink`. On Linux, macOS and Windows. The client speaks contract 1.4: over a credential
  the household is the patron and no did is asserted; a captured page's words are fenced before
  a model sees them; the sleep-time recall logs every run. The librarian's desk item gains the
  overnight ask (`research: <question>`, with a time ceiling and a per-day cap), `jobs` and
  `read <id>`. Contract 1.5: `link` without a token asks the owner to be let in and collects
  the token once approved; `link` subscribes the node to the librarian's pushed changes (a
  verified recall marks findings on the spot, a landed write-up is told to the companions);
  the household's library is served at `/v1/*` for peer librarians, with reader tokens from
  `wyrd library reader add`; peers' answers under `peers[]` are shown as their own libraries.
- **A findings tier.** What a companion concludes from reading is kept as a cited,
  reviewable claim in her Study (`finding` items: claim, claim type, confidence, state,
  writer, sources). Library search answers "what have I established before?" from her
  own findings first, then the shelves. A mechanical review at sleep accepts drafts whose
  sources are still on the shelves, keeps unresolvable ones as drafts with the reason, and
  retires restatements of accepted claims. `wyrd library findings <companion>` lists,
  accepts, disputes and retires; `review` runs the pass on demand.
- **A retrieval bench.** `wyrd library bench` replays the reading log's real queries
  through the production search path (without writing them back) and reports zero-hit,
  repeat and dictionary-on-top rates, plus miss@k over queries labeled in
  `<library>/bench-gold.json`. Every retrieval change is judged by this number.
- **The library protocol.** `LIBRARY_PROTOCOL.md` fixes the contract by which a companion
  asks a library outside the household (a research librarian, or later a peer household)
  over MCP. A `LibraryPatron` client speaks it, pins the contract version, keeps every
  entry's library id, and reports "holds nothing" as nothing. Items name the *role*
  ("library"); `WYRDSEKAI_LIBRARY_SERVICE` maps it to a registered service. Library search
  consults the configured library's "established" verdict after her own findings and
  before the shelves. Findings record sources as `locator` + `edition` and carry their
  origin library when they came from elsewhere.
- **The household serves the library protocol.** `POST /mcp/library` is an inbound MCP door
  on which the household's own library speaks `LIBRARY_PROTOCOL.md` to an outside patron.
  The license gate is on the sending side: only packs licensed to travel answer; the
  steward's shelves, study shares and any finding citing them never leave. The companions'
  accepted findings are served too, read live from the roster, only those whose every
  source may travel; `WYRDSEKAI_LIBRARY_SERVE_FINDINGS=false` makes the door packs-only. (The general
  inbound MCP endpoint the docs described was never constructed; it stays unserved, since
  its world tools carry no caller identity.)
- **A librarian's desk.** The bundled `librarian_desk` item makes the household a patron
  of a librarian service over MCP (`library_ask`, `library_search`): the answer package is
  labelled as background from another library and never overrides the shelves here.
- **Acquisition proposals have a surface.** `wyrd library proposals` lists what the
  library asked to acquire (repeated misses, a companion's `acquire`) and approves or
  rejects; `wyrd library status` says how many are waiting.

### Changed
- **A served embedder.** Retrieval embeddings can come from an embedding server
  (`WYRDSEKAI_EMBEDDING_URL`, llama.cpp `llama-server --embedding` with bge-m3) instead of
  the in-process ONNX session. On GPU tiers `wyrd setup` fetches the bge-m3 GGUF and
  `wyrd start` runs it as `wyrdsekai-llama-embed` on :8202; `wyrd status` reports it. The
  in-process path embeds bge-m3 at about 2 chunks per second on a CPU and does not scale
  with threads; served on a 16 GB card it does about 80 to 130. Served vectors are stamped
  with a distinct model version, so they never mix with in-process ones; an over-long text
  is truncated and retried, and an index-time failure is an error rather than a zero vector.
  Offline tools embed a copy of the index and bench it (`KnowledgeEmbedMain`,
  `LibraryBenchMain`).
- **Chunking.** New document ingests use structure boundaries with no overlap, and every
  chunk of a multi-part document carries a context line ("From <title>, part i/N"), so a
  passage says what it is a passage of. Measured elsewhere as the largest single lever
  for sparse retrieval; existing indexes are unchanged until re-ingested.
- **Library-card receipts** carry chunk ids to the findings ledger in a separate field,
  so provenance reaches the record without re-entering speech.

### Fixed
- **Backups no longer copy the search index.** Lucene segment files are write-once, so a
  snapshot now hard-links them to the live ones and copies only what cannot be linked: a
  snapshot of a whole-library index costs seconds and no space instead of eight minutes,
  the index's full size, and every page of RAM as cache. Five nightly copies of a 174 GB
  index had filled a household node's disk to 4% free. A snapshot that cannot link and
  would not fit is skipped with a warning rather than filling the disk. `wyrd doctor` now
  reports the disk's headroom, what the backups hold, swap in use and the service's memory
  peak, and warns when the disk is over 90% full.
- **Swap stopped creeping.** The package sets `vm.swappiness=10`
  (`/etc/sysctl.d/90-wyrdsekai.conf`, a conffile), so under pressure the kernel drops
  mmapped model and index pages, which are a fast re-read, before it swaps out the
  server's own memory. The nightly sleep write now logs its peak host memory. The
  self-updater counts a backup or a sleep write as work in progress and waits.
- **A context overflow with nothing left to drop is sheared, not failed fast.** Two turns after a
  restart had no history to remove and a small system layer; compaction freed 153 tokens, then
  nothing, and the turn failed as a permanent error. The last step now cuts the middle out of the
  largest message, keeping its head and the question at its tail with a visible mark, so any
  prompt the window can hold at all is retried. When compaction still has nothing, the log names
  where the tokens live (per-message sizes and tool count).
- **Item notes listing.** `world.notes.list` enumerated with a wildcard text query, which
  matches the literal token and nothing else — every item that asked for its notes got an
  empty list. It now enumerates by type.
- **Sanctuary memories are sentences.** The records written when a companion enters or
  leaves the sanctuary were tagged machine lines with internal ids and tank readings; a
  companion read one back as a thing to build around. They are plain first-person
  sentences now; the readings stay in the log.
- **Spelling is not permission.** Naming an owned item with a hyphen where its id has an
  underscore was answered as "not in your permitted scope". Item names now match
  case- and punctuation-insensitively and resolve to the real name.
- **Item loader** scans a directory reached by two paths (a symlinked data dir) once,
  instead of warning "duplicate item" for every item on every reload.

## [0.2.2] — 2026-09-02

The drive model is back in the driver's seat.

### Fixed

- **The large model was not being used.** Hosts with two models (a 9B for
  thinking on :8200 and a 4B for speaking on :8201) were sending everything
  to the 4B. The setup script wrote the 4B's file name into the config as
  "the model", and a July change that avoids starting a second copy of an
  already-running model then pointed the thinking route at the 4B's server
  as well. The 9B was loaded but never asked anything. Fixed three ways: the server will not route thinking to the
  speaking model when another model is running; it warns at startup if two
  routes point at the same server; and setup now writes the correct model
  and address. Existing installs are corrected on their next restart, no
  config changes needed.
- **macOS uses both models.** On a Mac, the thinking server could start with
  the speaking model, and the service could start before any model existed
  and then send every request to one server. Setup now starts the correct
  model and restarts the service once both are running. `wyrd start` and
  `wyrd status` recognise the Mac service instead of starting a second copy,
  and the Mac thinking server has the same context size as Linux, so coding
  tasks fit.
- **Windows: coding tasks fit, the first-run wizard runs once, codezaiku is
  the default.** The Windows thinking server has the same context size as
  Linux (`WYRDSEKAI_DRIVE_CTX` / `WYRDSEKAI_VOICE_CTX` override the defaults).
  The tray wizard reads the config file it writes, so it no longer reappears
  on every start. The bundled codezaiku is found where the installer
  puts it and is the default coding backend on Windows as on Linux and macOS.
- **Setup does not hang on a stalled Docker on macOS.** Docker calls are
  bounded.
- **The Windows llama.cpp download works with that project's new release
  layout.** The installer follows the release pointer to the build that
  carries the binaries.
- **`wyrd status` knows a service-managed server.** After a package upgrade
  or a reboot it reported the live systemd or launchd service as "orphan
  processes" or "not running"; it now reports it as running with its pid.
- **Relays keep password-mode households.** A relay's liveness check only
  recognised connections that signed in with a key, so a household that signs
  in with a password looked absent and was removed at the end of its window
  while it was connected. Password logins now count as present.
- **Rooms can be made on a companion's own time.** Creating a room from a
  template is now VISIBLE (it lands on the steward feed); creating one by
  hand asks (CONSENT); zones stay off-limits unprompted. The old FORBIDDEN
  tier refused the verb *after* she had chosen it from her own menu, and
  recorded the refusal as if she had acted — both fixed: a refused act is
  never logged as done, and forbidden verbs are never offered.
- **The map shows every door.** When rooms were recovered from the database
  before the map's seed list arrived, exits learned in play were dropped in
  favour of the seeded ones, so the map showed fewer doors than a room had.
  Learned exits now survive the merge.
- **Sleep learning reads her whole day.** The overnight scan that turns
  "I wish I could…" into a want was filtering by only one of a companion's
  two identities, so it saw a small fraction of what she had said. It now
  reads under both identities, builds its store on demand (so a sleep right
  after a restart still runs it), and logs its counts on every path.
- **Sleep learning no longer runs at the edge of GPU memory.** The voice
  server is paused for the write and restored afterwards — by the script's
  own exit, and by the server as a belt for killed writes.

### Added

- **The steward feed.** Everything a companion does unasked above the ambient
  rung is recorded to `steward-feed.jsonl` (configurable path), and the making
  family — rooms, workshop, recipes, code — also leaves a note on the
  steward's Study desk, pushed in-world and fanned out to their channels.
  `wyrd feed [--tail N] [--json]` reads it.
- **`wyrd grants tiers`** lists every verb's autonomy rung, its domain and
  maturity tier, and the exact grant that lifts it.
- **CLI messages in Japanese and Spanish** for sleep learning, the feed and the
  tiers listing, with a test that keeps the three catalogues in step.

## [0.2.1] — 2026-08-31

Companions now learn from their days.

### Added

- **Sleep learning.** While a companion sleeps, the day's conversations train
  a small adapter onto her voice model, so what happened yesterday actually
  shapes how she speaks tomorrow. The write is careful: it only touches the
  parts of the model most active during what she felt strongly about, it
  mixes in a sample of past days so old experience isn't overwritten, and it
  is rejected outright if it would change her general behavior or doesn't
  improve her recall of her own life. The result applies at wake, only when
  she is idle. Manage it with `wyrd sleepwrite`.
- **A morning check on every sleep write.** After a night's adapter is
  applied, the same model is asked a fixed set of questions with and without
  it: does it still follow instructions, refuse what it should refuse, speak
  the same language, avoid degenerating into repetition. If the night made
  any of that worse, the adapter is set aside and she wakes on her previous
  weights.
- **Growth wants.** If a companion says something like "I wish I could read
  music" — out loud or in her journal — that can become a want of her own.
  Later, on her own time, the world suggests she could build herself a small
  practice tool for it in the workshop. Suggests, never forces. Practice
  tools must grade attempts honestly and keep progress between uses. This
  only ever starts from her own words, never from measuring her against a
  standard.
- **The library announces new packs.** When a knowledge pack finishes
  installing, companions are told there is new reading — before, it sat
  there until someone happened to look.
- **CodeZaiku 0.2.0 bundled.** The bundled coding backend is updated to the
  new upstream release (chat, background delegation, research). Verified
  against its published checksum at build time, as before.

### Fixed

- **Knowledge packs failed to download.** Wikimedia rejects the Java HTTP
  client's default User-Agent, so the simple-wikipedia starter pack had
  failed with HTTP 403 on every boot since the library shipped. Pack
  downloads now send a proper identifying User-Agent.
- **Nothing could ever suggest the workshop.** The workshop verb had no
  connection to any of a companion's drives, so no amount of boredom or
  creative pressure could surface it on her own time. It is now wired up
  like the other creative verbs.

## [0.2.0] — 2026-08-27

The household stops being one machine.

### Added

- **The Between — a household mesh across machines.** Nodes reach each other
  directly instead of through a single box, and a phone that walks out of the
  house keeps the same conversation: the LAN channel and the relay channel are
  two doors onto one identity, and moving between them supersedes the channel
  rather than re-introducing you. Identity is minted once and travels.
- **Coding backends you can choose — and CodeZaiku is the bundled default.**
  `wyrd coding` lists, installs, updates and removes the coding agents a
  companion can build with; `wyrd coding use <backend>` picks the default and
  then prints the chain the node will actually use, because this setting has
  silently failed before; and `wyrd coding probe` submits one small real task
  through the selected backend and judges it by what lands on disk — because
  "installed" is a claim about bytes, and a probe is a claim about work.
  CodeZaiku ships inside every installer (one platform-independent artifact,
  verified against the manifest's own checksum at build time) and is the
  default of record; Goose and the rest are a `wyrd coding install` away. A
  backend whose binary cannot be found does not register — absence is visible,
  never a task-time surprise.
- **ACP v1 client.** Wyrdsekai speaks the Agent Client Protocol over stdio, so
  any ACP agent can be a coding backend.
- **Your own library, reachable from inside the world.** `wyrd library ingest`
  reads a directory of documents — epub, pdf, docx, markdown, plain text — into
  your Study, and `wyrd library publish <collection>` projects a shelf onto the
  household's shared knowledge surface so every companion and item can find it.
  A Calibre library is understood as a catalogue rather than a heap of files.

### Changed

- **Giving something away means you no longer have it.** Handing an item to
  someone used to copy it: the recipient gained one, the room kept one, and the
  giver's own copy came back on the next restart. A hand-off is now a move.
- **A person's own shelves answer their own tools.** Searching from an item you
  are holding searches what *you* can see — your own documents, plus anything
  granted to you — instead of being answered as a placeholder identity that owns
  nothing. Companions keep reading through their bondholder's consent, per
  collection, exactly as before.
- **Subprocess coding backends run with a scrubbed environment.** A backend
  spawns a real shell; it no longer inherits the daemon's ambient credentials.
- **CodePlane is now CodeZaiku.** The rename is complete: the binary, the
  `CODEZAIKU_*` environment variables, the `~/.codezaiku` state directory, the
  `codezaiku` backend id and the `codezaiku.*` zone-command namespace all became
  `codezaiku`. A host still exporting the old environment variable names keeps
  working -- both spellings are read and the new one wins -- and those aliases
  go away at 1.0.

### Fixed

- **Rooms you made survived the restart but nobody was home.** Player-created
  rooms came back as data with no actor behind them, so they existed and did
  nothing. They are respawned at boot.
- **Publishing a large shelf took hours it did not need.** Indexing refreshed
  the search index once per document — one tiny segment per passage — which on a
  74,000-volume library meant the machine spent its time merging rather than
  indexing. Bulk indexing batches the work: a 13.7-million-passage shelf now
  indexes about twenty-five times faster.
- **A shutdown during a long index no longer narrates every remaining item.** It
  stops with one line saying where it stopped, and a closed index can no longer
  quietly reopen a writer behind a completed shutdown.
- **A shelf ingest indexes books, not the files beside them.** Calibre keeps a
  `metadata.opf` next to every volume; those were indexed as documents and, being
  pure title-and-author, outranked the books they described. Sidecars are skipped,
  and `wyrd library prune-sidecars` removes any an earlier ingest already took in.
- **The documented way to choose a coding backend never worked.** The setting was
  bound to one configuration key and read from another, so it wrote something
  nothing consulted.

### Security

- **An item now acts with the authority of whoever is holding it.** Content
  surfaces used by player-held items were served by a single shared object built
  with a placeholder identity, so note ownership, filesystem audit records,
  library filing and inference spend were all attributed to that placeholder
  rather than to the person. Each caller now gets its own view.
- **Library entries can only be edited by whoever wrote them.** `library.tag` and
  `library.delete` accepted any entry id with no ownership check, and
  `library.delete` is available to crafted items — so an item could have removed
  any entry in the household's knowledge base. You may now edit what you wrote;
  the household's steward may curate anything, and it is logged.

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
