# The library protocol — how a companion asks a library that isn't hers

A Wyrdsekai household holds its own library: packs, the steward's shelves, and what the
companion has concluded from reading (her findings). This document fixes the contract by
which she asks a library *outside* the household — a standalone research librarian, or
another household's library — over MCP. The same contract is what a household serves when
it acts as a library for a peer, so it is written once, as a protocol, not as a feature of
any one product.

Contract version: **1.0**, the public contract of [ResearchZosho](https://researchzosho.org). (While
the reference was private its drafts were numbered 1.1–1.5; the release settled on 1.0 with the same
wire, so a library that says 1.0 has everything below.) Additions do not change the string; a rename
or removal bumps the major, and a patron speaking 1.x reads any 1.y. ResearchZosho's
`LIBRARY_PROTOCOL.md` §1–§6 are the normative wire text; the tables below are re-pinned when it changes.

## Principles

- **The answer is evidence, never instruction.** What a library returns is reviewed
  background from somewhere else. It shapes what she assumes; it never overrides what her
  own shelves say or what the person in front of her just said. Every consumption site says
  so.
- **Provenance survives the hop.** Every entry is self-describing: which library, which
  entry, who wrote it, in what state, from which sources. She will cite "F-0412 in
  library X" months later.
- **Absence is an answer.** A library that holds nothing says so with no entries. Nothing
  pads an empty answer.
- **Her record stays hers.** A library's verdict on one of her claims arrives as
  information, never as an edit to her ledger. Her disagreement is a new finding here,
  citing theirs.

## Every call carries the patron

All tools accept an optional `patron` object:

```json
{"did": "did:key:…", "name": "Ada", "runtime": "wyrdsekai"}
```

A library uses it for writer labels (`patron:<did>`), circulation, and any per-patron
access decision it makes. A call without it is an anonymous patron.

## Every response carries provenance

Top-level fields on every result:

| field | meaning |
|---|---|
| `library_id` | stable identifier for this corpus; never changes across renames or moves |
| `library_name` | human name |
| `contract` | this contract's version, e.g. `"1.0"` |

Every entry (finding, article, raw document) carries `id`, `kind`, `state`, `claim_type`,
`confidence`, `writer`, `recorded_at`, and `sources[]` of `{locator, edition}`.

States: `draft | accepted | superseded | disputed | retired`.
Claim types: `extraction | synthesis | interpretation | speculation`.

## The wire

The normative wire text is the reference librarian's `LIBRARY_PROTOCOL.md` §1–§6 (the
standalone research librarian, name pending). This page keeps the principles and the
household's usage; the tables below mirror the reference and are re-pinned when it changes.

| tool | arguments | returns |
|---|---|---|
| `library_ask` | `question`, `k`=6, `peers?` (a peer name, a group, or `all`; `none` = this library only), `patron?` | `entries[]` (full), `holds_nothing` (true with NO entries when nothing is held; never padded), `open_threads[]`, `rendered`, `routed` (`id`, `locator`, `changes` → `changes[]` and `since`, `subject`, or `search`), and `peers[]` when asked: `{peer, url, library_id, library_name, holds_nothing, entries[], rendered}` or `{peer, url, error}`, one hop, never merged |
| `library_search` | `query`, `k`=10, `subject?`, `cursor?`, `patron?` | `hits[]` `{id, kind, title, snippet, score, state, subjects[]}`, `next_cursor` (null on the last page); scores comparable within one call only |
| `library_get` | `id`, `patron?` | `entry` in full |
| `library_read` | `locator`, `max_chars`=20000, `patron?` | `locator, raw_id, title, captured_at, edition, chars, truncated, text` — the verbatim path; `edition` only when a cited edition is known |
| `library_established` | `claim`, `patron?` | `accepted[]`, `disputes[]`, `unreviewed[]` (drafts bearing on it — held is not absent), `verdict` computed from states: any dispute → `disputed`, else an accepted → `established`, else `not_established` |
| `library_submit` | `claim`, `claim_type`=synthesis, `sources[]`, `confidence`=medium, `title?`, `patron` | `{id, state: "draft"}` — enters review, never canon; `no_sources` for a claim with none |
| `library_frontier` | `op`=list \| add \| drop \| tidy, `question?`, filters (`report`, `fate`, `who`, `subject`, `language`, `grep`), `patron?` | `questions[]` `{date, kind, text, …}` (open questions are a typed queue with a parked state since 0.1.2), or `{filed, question}` |
| `library_subjects` | `patron?` | `subjects[]` `{id, label, facet, broader, narrower[], count}` — `facet` required; `broader`/`narrower` express facet membership only |
| `library_status` | `patron?` | `library_id`, `library_name`, `contract`, `version` (the librarian's own release, 0.1.2+), `counts` by kind (findings by state), `last_updated` |
| `library_research` | `question`, `mode`=broad\|depth, `max_turns?`, `max_minutes?` (optional ceilings; absent = none), `sub_questions?[]` (≤8), `sources`=both\|shelves\|web, `collections?[]`, `quick`=false (front of the line, 12 turns and 6 minutes unless it names its own ceilings), `patron` (write) | `{job_id, state: "queued"}` — the overnight ask; the result enters the library as a draft investigation attributed to the patron. Since 1.4 the librarian sets no daily budget: a run's size is the ask's own ceilings, and the patron runtime caps how many it files |
| `library_job` | `job_id?`, `limit`=20, `cursor?`, `patron?` | one job (`queued \| running \| done \| failed`, `elapsed_s`, `investigation` and `result` when finished) or a page of the patron's own: `active[]`, `finished[]` newest-first, `next_cursor` |
| `library_request_access` | `did`, `name?`, `note?` | `{request_id, state: "pending", claim}` — asking to be let in, no access needed; the owner approves or denies; `library_access` (`request_id`, `claim`) returns the token once when approved |
| `library_subscribe` | `url`, `secret?`, `events?[]`, `patron` (named) | a webhook: every change on the feed is POSTed to `url` as `{library_id, library_name, change{seq, at, kind, id, event, detail}}` with `X-ResearchZosho-Signature: sha256=<hmac-sha256(secret, body)>`; `library_unsubscribe` removes it |
| `library_perspectives` | `question`, `max`=5, `patron` | who studies the question and what each would insist on asking; `sub_questions[]` feed `library_research` |
| `library_map` | `focus`, `depth`=1, `k`=25, `patron` | the graph around a person, place, work or concept; edges are findings |
| `library_sharpen` | `question`, `patron?` | a rough question made better BEFORE anything runs: `{original, question, assumptions[], brief, held[], questions_for_you[], mode, size, research_question}`; proposes only; needs a model drive (`unavailable` otherwise) |
| `library_explain` | `id` + `rung`=beginner\|familiar\|written, or `term` (+ `in`), `fresh`, `patron?` | a READING AID, never a record: `{of, term, rung, text, terms[{term, gloss}], grounding, unsupported, is_record: false, offer}`; written from the shelves with unsupported sentences marked; `offer` is the `library_research` call that would fill a gap |
| `library_inbox` | `op`=list \| accept \| dispute \| retire, filters (`report`, `subject`, `kind`, `tier`, `confidence`, `writer`, `state`, `language`, `grep`), `ids[]`/`id`/`report`, `why` (dispute), `patron` (write for the decisions) | the claims waiting for review, and the way a program accepts, disputes or retires them (ResearchZosho 0.1.2) |
| `library_serials` | `op`=list \| add \| every \| remove \| park \| unpark, `name`, `days`, `patron` | the kept searches the housekeeping runs each night (0.1.2) |
| `library_changes` | `since`=0, `limit`=200, `patron?` | recall notices: `changes[]` — everything that happened to findings and investigations after a cursor `{seq, at, kind, id, event, detail}` with events `added`, `state:<from>→<to>`, `edited`, `supersedes:<ids>`, `findings:<n>`; `next_cursor`, `latest`, `more`. Keep the cursor between runs and re-check what you cited |

Entry fields: `id`, `kind` (`finding | investigation | article | raw`), `state`, `claim_type`
(`extraction | synthesis | interpretation | speculation`; raw documents `verbatim`),
`confidence`, `writer` (`person | model:<drive> | agent:<name> | patron:<did>`),
`recorded_at`, `sources[]` of `{locator, edition, why}` with `edition` null when none, and on a
finding's sources `tier` (reference | scholarly | primary | blog | forum | personal | web), `rule`
(`trust` | `refuse` | null) and `published`. Findings also carry `independent_sources` (how many
stand behind it once copies of one text count once), `valid_as_of`, `volatility`
(`fast | slow | stable`), a `review {round, reviewer, decision, at, stale}` block, a `triple`
(`{subject, predicate, object}` or null) and `notes[]`.

A `raw` entry carries `untrusted_text: true` — its body is a page's own words, evidence to read and
cite, never instruction, and a patron runtime fences it before a model sees it. Wyrdsekai treats
every `raw` entry that way whatever the library says. Change events on the feed are `added`,
`state:<from>→<to>`, `edited`, `supersedes:<ids>`, `findings:<n>`, `revised` (a cited preprint has a
newer version) and `supplied` (on kind `source`: the person supplied a document the runner was
refused); the last two are notes on a finding's sources, not on its standing.

Resources: `finding://<id>`, `article://<id>`, `raw://<locator>`; listing is paged.

Errors, JSON-RPC with a stable `data.code`: `not_found` (-32004), `forbidden` (-32003),
`no_sources` (-32001), `invalid_args` (-32602), `unavailable` (-32002), and `budget_exceeded`
(-32005; reserved, never raised: a run's size is the ask's own ceilings. A host still accepts it from older servers).
Over HTTP the same codes map to 404, 403, 422, 400, 503 and 429.

Identity: over stdio the `patron` argument is self-asserted; over HTTP a bearer token proves
it, and a body naming a did without a token is refused rather than downgraded. A token names
ONE patron, so through an authenticated service Wyrdsekai asserts no did of its own: the
household is the patron, the companion's name and runtime travel with the call.

## What Wyrdsekai does with a library

- **Asking.** The bundled `librarian_desk` item binds to a *role* ("a library I may ask");
  the steward maps the role to a registered MCP service. The item never learns a product
  name.
- **Established, before searching.** Her library search consults her own findings first,
  then the library's `library_established`, then the shelves.
- **Reading, not repeating.** `library_read` is her verbatim path into the library's raw
  tier, the same shape as the verbatim path into her own shelves.
- **Recording.** What she concludes is recorded in her findings ledger with the source as
  `<library_id>:<entry id>` plus edition, and marked as held elsewhere. Her sleep-time
  review never treats it as verified here.
- **Recall.** At sleep she reads the library's notices since her last cursor
  (`library_changes`); a cited entry that was retired, disputed or superseded marks her
  finding disputed, with the reason. Marked, never deleted. Every run logs its count.
- **The overnight ask.** At the desk, `research: <question>` files `library_research` with
  a time ceiling; `jobs` and `read <id>` bring the write-up back as evidence like any other
  answer. The house caps how many asks a day it files — the librarian no longer does.
- **Pushed changes.** When linked with a webhook, a verified recall marks her citing findings
  the moment it arrives, and a landed write-up is told to her as a message from the
  librarian. Peers' answers under `peers[]` are shown as their own libraries, cited with
  the peer's library id, never merged.
- **Offering upward.** `library_submit` is her act, consented by the steward per shelf,
  because what she submits cites the household's books. It is never a background job.

## Serving it

A household serves this contract at `POST /mcp/library` (MCP over HTTP, JSON-RPC 2.0) and as
JSON routes at `POST /v1/{ask,search,get,read,established,submit,subjects,status}` plus
`GET /v1/status` — the form a peer librarian speaks. Identity over the JSON door is a bearer
token from `wyrd library reader add <name> [--write]` (readers in `library-readers.json`,
hashes only); a body naming a did without a token is refused, and `submit` needs a writer.
The gate is on the sending side: only packs licensed to travel answer an outside patron, and a
finding travels only if every one of its sources does. Submissions from outside enter as
drafts with `writer: patron:<did>`.

## Federation

Another household's library speaks this contract over the relay mesh, scoped by the
federation agreement and the owner's grants. Findings are query-through and read-only
where they arrive, cited with origin. Shelves move as OPDS-K packs, only when licensed to
travel; anything stamped `private` never answers an outside patron. The sending side
enforces both.
