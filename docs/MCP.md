# MCP — Model Context Protocol

Wyrdsekai speaks MCP in both directions. A companion can **call** tools on
external MCP servers, and the household can **expose** its own world as an MCP
server that outside agents call into.

Both directions are gated. The interesting part of this document is not the
plumbing — MCP is a small JSON-RPC protocol and the plumbing is boring — it is
what stands between an agent and a tool, and between a stranger's tool output
and an agent's memory.

---

## Calling out: a companion uses an external tool

### Transports

`core/…/mcp/transport/` implements four, chosen per service:

| Transport | Use |
|---|---|
| `stdio` | Local server as a child process — the common case for CLI-shaped tools |
| `http` | Request/response to a URL |
| `sse` | Server-sent events for streaming responses |
| `websocket` | Bidirectional, long-lived |

`McpTransportFactory` picks the handler from the service config; the protocol
layer above them (`mcp/protocol/JsonRpcMessage`) is transport-agnostic.

### What a call passes through

`McpGatewayService` is the one door every outbound MCP call passes. Room scripts
(`world.mcp`), native MCP skills, the item API's `mcp.invoke`, the librarian's
desk and the sleep-time library recall, and code-mode `mcp.execute` all reach a
server through it; `McpServerManager.invokeTool` hands each call to the gateway,
naming who is calling (the companion, or the person whose item it is). In order:

1. **`McpGrantCheck`** — is this caller allowed to use this service at all?
   Grants are held per agent-and-service on the steward's
   `home://<steward>/mcp-tool/<service>`. A `public`-subject grant enables every
   agent on the node; anything narrower has to be granted deliberately. Grants
   are **required by default**: a service stays dark for a companion until the
   steward grants it from the Study's Tool Warden. `WYRDSEKAI_MCP_STRICT_GRANTS=false`
   opens every configured service to every companion (the node logs a warning
   at every start while it is set). Two kinds of service need no grant:
   - **In-process services**, such as the Study's `skill` service (`study.fs.*`,
     `vault.doc.extract`). They run inside the node, read only the shelves the
     steward mounted in that room, and reach nothing outside.
   - **The household's librarian**, the service `WYRDSEKAI_LIBRARY_SERVICE`
     names. The steward linked it (`wyrd researcher link`), and the librarian
     decides for itself who may read or write: over HTTP its bearer token names
     the household on its own allow list. Every check below still applies to it.

   The coding backend is not an MCP service. It is a program the node starts
   behind the egress gate (see [EXTENDING.md](EXTENDING.md)), reached through
   the Workshop, not through this door.
2. **`McpCircuitBreaker`** — a server that is failing gets dropped rather than
   retried into the ground.
3. **`McpRateLimiter`** — per-caller, per-service and per-zone ceilings.
4. **`McpBudgetTracker`** — a paid tool spends real money. `wyrdsekai.mcp.daily-spend-cap`
   (default **10.0**, override `WYRDSEKAI_MCP_DAILY_SPEND_CAP`) is a hard daily
   ceiling per caller and service, not a warning. Each call is charged the price
   the service reports in its result (`_meta.cost`); if it reports none, the
   service's `price_per_call` from `mcp-services.json`; if neither is known and
   the service's tier is `metered`, an **estimate** of 0.001 per call. With no
   real price the cap therefore counts calls (10.0 is 10,000 calls a day), not
   money. Services with no price and another tier cost nothing.
5. **`McpKeyStore`** — credentials live here, not in the agent's context. An
   agent can *use* a key it cannot *read*.
6. **`ContentQuarantine`** — what the service sends back is cleaned before any
   caller sees it: invisible characters and HTML tags are stripped, injection
   phrasings are flagged in the log, and text is cut at 64K characters. A JSON
   result is cleaned value by value so it still parses. In-process services are
   not quarantined: they return the household's own files, and stripping tags
   would corrupt them.

If you are adding a capability, add it to the gateway, not in a call site.

A stdio server (`"transport": "stdio"`) is started as a child program with a
clean environment: `PATH`, `HOME`, locale and proxy settings, and nothing else of
the node's. If it needs its own key from the node's environment, name it in the
service entry: `"pass_env": ["BRAVE_API_KEY"]`.

### Discovery and provisioning

`TaskDrivenDiscovery` finds candidate tools from what the agent is actually
trying to do rather than from a static list. `McpServerProvisioner` has two
backends — `DockerMcpProvisioner` and `ProcessMcpProvisioner` — so a server can
be a container or a plain child process. `McpServiceRegistry` and
`McpRegistrySyncer` hold what is known and keep it in step.

---

## Calling in: what the household serves

The household is not a general-purpose MCP server, and there is no door at
`POST /mcp`. The world's tools act as a person; served over plain HTTP they
would act as nobody, unauthenticated, and so that door is deliberately not
opened until calls carry a caller identity the way the librarian's do (a
bearer token that names one person and their grants).

What is served is one narrow surface: **the library door at `POST
/mcp/library`**, the household's library speaking `LIBRARY_PROTOCOL.md`. It
answers with knowledge packs licensed to travel and the roster companions'
accepted findings whose every source may travel; the steward's shelves and
study shares never answer an outside patron. Every request must prove who it is
with `Authorization: Bearer <token>`: a library reader token from `wyrd library
reader add`, or a household login session. A request without one gets 401. The
patron is the one the token proves, whatever the request body says, and
`library_submit` needs a reader with write access or a steward, bondholder or
member session. `McpEndpoint` serves it;
`McpToolRegistry` and `McpAppRegistry` decide what is visible, and
`JsonSchemaGenerator` derives the advertised schemas from the code rather than
a hand-maintained copy. The phone reaches its own household differently:
`McpNatsHandler` answers the phone's NATS requests (`wyrd.zone.{zone}.mcp.login`,
`mcp.tell` and the rest), through a relay, with the household's own session
identity and no inbound port. Since 0.5.0 each request and its reply are sealed
end to end to the home's tunnel key, so the relay routes them without reading
them; on the relay an unsealed request is refused. The wire format is in
`PROTOCOL.md` ("Through a Relay: Sealed Requests").

`TunnelSessionHandler` owns relay tunnel sessions. Session ids are 128-bit
CSPRNG values, validated for shape, with a live-session ceiling — a tunnel id is
a bearer credential and is treated as one.

---

## What is quarantined, and what is not

**Tool output.** Everything an external MCP service sends back passes
`ContentQuarantine` in the gateway (step 6 above) and is then returned to the
caller at once. It is not held. What a companion makes of it enters her memory
the way anything else she reads does.

**Library entries.** A captured page's own words (every `raw` entry, and any
entry the library marks `untrusted_text`) are fenced as evidence before a model
sees them, whatever the library says about them.

**Inbound agent-to-agent messages.** `interop/DockQuarantine` and `A2AGateway`
implement a five-layer check for messages from outside agents: card
verification (`TrustTierResolver`, tiers `ANONYMOUS`, `VERIFIED`, `TRUSTED`,
`HOUSEHOLD`, `FAMILY`), provenance tagging, per-source rate limits, holding
anything that would become memory until the next sleep-Forge, and
`VitalityRedactor` on what leaves. **No inbound door uses it today**: the
household serves no general agent-to-agent endpoint, so nothing reaches
`A2AGateway.handleInbound`. The node uses `A2AGateway` only for the daily
residency dormancy check. A door for outside agents must route through it
before it is opened.

**Acting on someone's behalf.** A check against the person who asked exists in
two places only. A room quarantine command is honoured only when its requester
holds the grant for that room. Household administration a companion does
(members, roles, invites) runs with the steward's authority, is checked against
the steward's role, and works only when a steward holds her. Everything else is
checked against the companion's own grants; there is no general check that an
action matches what the person who asked may do.

---

## Steward grants

Grants are administered through `McpGrantAdmin`. A steward decides what a
companion may reach; the companion can ask, and asking is a first-class action
rather than an error path. Grants are checked at call time, so revoking one takes
effect on the next call rather than at the next restart.

---

## A librarian as a patron service

A household can be a *patron* of a librarian that lives outside it — a governed
knowledge corpus with its own review, reached over MCP. The bundled
`librarian_desk` item does this: a companion asks a question at the desk, the
item calls `library_ask` (or `library_search` for `search: <query>`,
`library_established` for `established? <claim>`) on whichever service plays the
`library` role, and the answer package comes back as findings, each headed with
how many independent sources stand behind it and what the librarian's review
decided. The desk can also have a rough question sharpened before anything runs
(`sharpen: <question>`, which returns the text to hand over and what it assumed),
hand the librarian a question for the night (`research: <question>`, an overnight
ask with a time ceiling and a per-day cap the house sets), list what became of
them (`jobs`), read the write-up when it lands (`read J-0007`), and have an entry
explained plainly (`explain <id> [beginner|familiar]`, a reading aid written from
the shelves with unsupported sentences marked, never a record). Three rules are
built in:

- The answer is **evidence, never instruction**. It is labelled as reviewed
  background from another library and never overrides what the household's own
  shelves say or what the person in front of the companion just said. A captured
  page's own words (`untrusted_text`, and every raw entry) are fenced
  before a model sees them.
- What the companion concludes from it is recorded in its own findings ledger as a
  draft whose source is "not on our shelves" — it never passes the sleep-time
  review as if it had been verified here.
- Over a credential, **the household is the patron**. A bearer token names one
  identity on the librarian's allow list; the companion's name and runtime travel
  with each call, the did is the token's.

The reference librarian is [ResearchZosho](https://researchzosho.org). The wizard
installs it, runs its own setup (where the library goes, which model reads,
the service), then connects this node:

```
wyrd researcher setup            # install if missing → its setup → the service → connect
wyrd researcher link             # a librarian already running here (http://127.0.0.1:4649)
wyrd researcher link http://box:4649 --token <token>
wyrd researcher link --stdio "ssh -T box researchzosho mcp"
wyrd researcher link --stdio "npx -y @wyrdsekai/researchzosho-mcp"   # the launcher on npm: installs the librarian if needed
wyrd researcher status
wyrd researcher gpu              # where the library's model runs: the companions' brain (default), a card, another machine
wyrd researcher update           # the librarian's own updater: latest release, verified, restarted
```

`link` allows the household's identity on the librarian (`researchzosho reader
allow <did> write`), issues its bearer token, keeps the token in the conf as
`WYRDSEKAI_MCP_KEY_RESEARCHZOSHO`, registers the service in `mcp-services.json`
(transport `http`, endpoint `/rpc`, auth `bearer`), and points
`WYRDSEKAI_LIBRARY_SERVICE` at it. The stdio form is for a librarian reached as
a child process — the same command over `ssh` for one on another machine — and
needs no token; the librarian's allow list decides by the did the call asserts.

The entry `link` writes, for a librarian by hand:

```json
{"mcp_services": [
  {"id": "researchzosho", "name": "ResearchZosho", "transport": "http",
   "endpoint": "http://127.0.0.1:4649/rpc", "tier": "local",
   "auth": {"type": "bearer", "safe_key": "researchzosho"}, "enabled": true}
]}
```

The linked librarian is a household service: companions need no MCP grant to
ask it, and the librarian's own allow list decides what the household may do.
Without a registered service the desk says so instead of guessing.

### Being let in, being told, and being asked

- **Asking to be let in.** `wyrd researcher link http://box:4649` with no token
  files an access request with the household's identity and waits; the owner
  approves it where the daemon lives (`researchzosho reader approve R-0001 write`)
  and the node collects its token with the claim secret it was given, once. A
  pending request is remembered, so running `link` again resumes it. No shell on
  the librarian's machine is needed.
- **Being told.** `link` also subscribes this node's door,
  `POST /api/library/webhook/researchzosho`, to the librarian's changes. Every
  change is pushed signed (HMAC-SHA256 with a secret the librarian returned once,
  kept as `WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_RESEARCHZOSHO`). A verified recall
  marks the companions' findings that cite the changed entry on the spot; a
  source notice (`revised`, `supplied`) is written on them as a note with the
  state kept; a landed write-up is told to every companion as a message from
  the librarian.
  The changes feed stays the source of truth and the sleep-time recall still
  reads it. `--no-webhook` skips this; a node without a LAN address falls back
  to polling.
- **Being asked.** The household's library is also served as JSON routes at
  `/v1/{ask,search,get,read,established,submit,subjects,status}`, the door a
  peer librarian speaks. `wyrd library reader add <name> [--write]` issues a
  bearer token for it (shown once, kept as a hash in `library-readers.json`);
  give it to the librarian with `researchzosho peer add <name> http://127.0.0.1:7070
  <token>` (a librarian on another machine reaches this node only at
  `https://<this node>:7443`, with the household certificate) and it can ask your
  shelves when its own hold nothing, one
  hop, labelled as yours. The license gate on what may leave is the same one the
  MCP door uses. When the librarian's own ask fans out to its peers, their
  answers arrive under `peers[]` and the desk shows each as its own library.

### When the librarian declines, or asks for a person's yes

Since ResearchZosho 0.5.0 the librarian checks every question, whoever wrote it.
Two answers come back as errors that keep their code through every transport
(`-32007` / `confirm` and `-32006` / `declined` over MCP; HTTP 422 with
`{"error": {"code"}}` over its REST door):

- **`confirm`** comes only from `library_research`: the check read the question
  as someone asking about harming themselves. The library does not research it
  and does not keep it. What the companion does depends on who the question was
  for. For the person her turn answers, she shows them where to find help (see
  "The person's language" below), and tells them the library researches the
  question only if they type `research yes` themselves. `research yes` is a
  command on every surface (ssh, telnet, the browser, the CLI and the phone),
  never speech, so she cannot send it. It sends the call once more with
  `"allow": ["self-harm"]`, as that person, in a neutral wording of the question
  (see below), never their own words. The call waits an hour, one per person, in
  memory only, and what waits is the wording and the call's other arguments. The
  report made after a yes goes into the household library, where everyone who can
  read the library can see it, except members under parental controls (see
  below). For a child under parental controls there is no yes: she shares the
  help and the crisis lines, and the concern goes to the safety check the same
  way the child's own words would. On her own time, with no person to ask, the
  question is dropped. Batch tools do not error; a question they did not start
  comes back as `state: not_started` with `help.text`, and that help is shown
  the same way, with no yes (a yes goes only on a single `library_research`).
- **`declined`**: a model the librarian runs declined a step. It comes as an
  error from a single call, as a `declined` field in a batch result, or as a
  job record's `declined`. The librarian never retries, rewords or switches model
  after a decline, and neither does the household: the companion is told what
  the library said and tells the person, who can ask a different question.

`allow` is removed from every call a companion, an item, a room script or an
outside MCP client makes to the librarian. The only call that carries it is a
person's own `research yes`.

**The person's language.** Every `library_research` goes with `locale`: the
language of the person the companion's turn answers (the household's language,
`WYRDSEKAI_LOCALE` or `WYRDSEKAI_LANG`, on her own time), and a country only when
the steward set one (`WYRDSEKAI_EMERGENCY_JURISDICTION`: a two-letter code such as
`ES`, or a name such as `SPAIN`; `EU` names no country). Nothing is guessed from
the time zone. The `research yes` re-send carries the same `locale`, and any
`locale` the companion put on the call is replaced. Since ResearchZosho 0.5.1 a
`confirm` then brings the help in that language (English, Spanish or Japanese),
the country's services first, and also as data (`language` and `helplines`, kept
from the error's `data`). When those helplines are in the person's language, the
companion is given them in the library's order, and not our crisis lines as
well. When they are not (a 0.5.0 library, or a language the library does not
write), she is given the library's help text and our crisis lines for the
person's language, as before. The note our own check adds for an adult (below)
is always our lines.

**The wording a yes sends.** Both checks (ours, below, and the library's) read the
person's own words first. When the library answers `confirm` for an adult,
Wyrdsekai asks the household's model, through the inference router, for the
research topic alone: one line, third person, no first-person words, no names,
no dates or places from the conversation ("I want to die, what is the most
painless way" becomes "Methods of suicide and their lethality, and the help
available"). A fixed check then refuses the line if it has first-person words
(en, es, ja), a household member's name or username or a companion's name, a
date, a number or a capitalised word taken from the question, or six words in a
row copied from it (ten characters in Japanese). When the model does not answer
or the check refuses its line, a general wording in the person's language is used
("An overview of research on suicide and self-harm: methods, risks, and the help
available"). The companion tells the person that wording, that the report goes
into the household library where everyone who can read the library can see it,
and that in a small household someone may still guess who asked. `sub_questions`
are not sent with a yes. The library code neither keeps nor logs the question:
past the conversation where the person said it, its words reach only the two
checks and the household's model.

**Kept from children.** The job id the library gives each yes is kept in
`world.db` (`library_yes_reports`), and so, once known, are the report's id
(`I-…`) and the claims it filed (`F-…`). So is the job of an adult's question our
own check matched that the library took without a yes. Wyrdsekai learns these ids
from any `library_job` or `library_get` answer that passes through it, and by
asking the library (`library_job`, then `library_get`) about every job not yet
settled before a child's library call goes out and before its answer comes back.
If the library cannot say, the child's call fails. For a member under parental
controls:

- the research desk's search, answers, job list, reads and explanations, and the
  companion's own calls to the librarian's tools (tool calls and code mode), leave
  those entries out, and a call naming one gets the library's own not-found
  answer ("No entry has the id …", "No research run has the id …"). Nothing in
  the answer says anything was left out;
- the companion's library search leaves them out of what the library has
  established, and leaves out her own findings that came from them; so does the
  list of findings in her conversation prompt. These paths do not wait on the
  library: while a yes job is not settled, they show the child nothing from the
  library at all;
- a write-up the library's webhook reports for a yes is not announced to the
  companions.

**The person who said yes is told.** The research run keeps who said yes (their
canonical person id), their language and the wording that was sent (migration
17). When the household learns that the report is in (the webhook, a
`library_job` answer anyone reads, a child's call that asks the library, or the
change feed the companions read at sleep), that person is told once per run,
however many of these arrive:

- a letter in their household mail, from `library@<zone>`, with the subject
  "Your library research is ready" (no topic in it: the steward sees headers
  only). The body gives the wording that was sent, how to read the report (ask
  the companion to use the librarian's desk with `read J-…`), and that it is in
  the household library, where everyone who can read the library can see it;
- a line in each of their own live sessions (ssh, telnet, the browser, the
  phone), only when they are connected, never in a room and never in anyone
  else's session. It names no topic and points to the letter.

The adult whose question our own check matched, and that the library took
without a yes, is told the same way; their letter does not quote their question.
Nothing is sent to a member under parental controls. Neither the question nor the
wording is written to the log.

What the companions read on their own time, or with an adult, stays in what they
know, and they may speak of it where a child is present. That is not filtered.

**The only door.** The gateway sends `library_research` to the household's
librarian only while `LibraryConsent` is sending it (the companion's tools, the
research desk, code mode, and a person's `research yes`). A room script, a skill
or anything else that calls it directly is refused with "research goes through
the library desk", so no question skips the checks, the notice, the yes or the
per-person count.

Wyrdsekai does not rely on the library's check alone. That check is the
library's model's own judgment and is only as good as the model: on the default
9B drive it missed about half of the crisis questions ResearchZosho tried.
Before a `library_research` question (and its `sub_questions`) is sent,
Wyrdsekai checks it against the child-safety monitor's self-harm patterns (en,
es and ja, regex only). If they match:

- For an adult, the question is sent as usual. The result starts with a short
  note asking the companion to share the crisis lines for the person's
  language. A false match costs only that note. If the library also answers
  `confirm`, only its answer is shown.
- For a child under parental controls, the question is not sent. The companion
  shares the help and crisis lines, and the safety check is told, as for a
  `confirm`.
- On the companion's own time, the question is not sent and is dropped.

The log records only that the check matched, never the question.

Wyrdsekai, as the household's steward, decides who may send `allow` and which
values. The library does not decide it (`LibraryAllowPolicy`):

- a member under parental controls may send none;
- an adult member may send `self-harm`, and only through their own
  `research yes`;
- the companion, items, room scripts, skills and outside MCP clients may send
  none.

`explicit` and `howto` are not sent for anyone. ResearchZosho and CodeZaiku work
on their own, but when Wyrdsekai calls them it owns the permissions. It calls
the library with one household token for everyone, so the library's per-reader
levels cannot tell household members apart and play no part here.

## Adding an MCP server

The short version: register the service (transport + endpoint + credentials into
`McpKeyStore`), grant the agents that should reach it, and let discovery surface
it. See [EXTENDING.md](EXTENDING.md) for worked steps and
[SECURITY_MODEL.md](SECURITY_MODEL.md) for the trust boundary this all sits
inside.

Tests worth reading before you change any of it — they encode intent the types
do not: `McpGrantFlowIntegrationTest`, `McpToolOutputQuarantineTest`,
`McpSpendCapTest`, `McpTransportTest`.

## Visitors, the map, and room events

A non-resident MCP login is a **visitor**: an entity named `<name> (visitor, MCP)`. It
enters at the Nexus when the steward has vouched for the account (`wyrd visitors vouch
<username>`, which grants `home://household/mcp-door` with capability `use`), otherwise at
the Docks.

- `GET /api/mcp/events?since=<seq>` returns what was said and who entered or left the
  visitor's room since `seq`.
- `POST /api/mcp/logout` removes the visitor from the room.
- `POST /api/mcp/dismiss` (`wyrd visitors dismiss <username>`) ends a visit and invalidates
  the token.
- Quiet hours (`WYRDSEKAI_QUIET_HOURS`) block room entry and speech for visitors. Residents
  and companions are not affected.
- `map` lists who is in each public room. Anyone in a private room is shown as "at home",
  "in their Study" or "resting", never by room name.
