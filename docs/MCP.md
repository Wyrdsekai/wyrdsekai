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

A tool call is not a function call. In order:

1. **`McpGrantCheck`** — is this agent allowed to use this resource at all? Grants
   are held per agent-and-resource. A `public`-subject grant enables every agent
   on the node; anything narrower has to be granted deliberately.
2. **`McpRateLimiter`** — per-source, per-method ceilings.
3. **`McpBudgetTracker`** — a paid tool spends real money. `wyrdsekai.mcp.daily-spend-cap`
   (default **10.0**, override `WYRDSEKAI_MCP_DAILY_SPEND_CAP`) is a hard daily
   ceiling, not a warning.
4. **`McpCircuitBreaker`** — a server that is failing gets dropped rather than
   retried into the ground.
5. **`McpKeyStore`** — credentials live here, not in the agent's context. An
   agent can *use* a key it cannot *read*.

`McpGatewayService` is the single door all of this sits behind. If you are adding
a capability, add it there — not in a call site.

### Discovery and provisioning

`TaskDrivenDiscovery` finds candidate tools from what the agent is actually
trying to do rather than from a static list. `McpServerProvisioner` has two
backends — `DockerMcpProvisioner` and `ProcessMcpProvisioner` — so a server can
be a container or a plain child process. `McpServiceRegistry` and
`McpRegistrySyncer` hold what is known and keep it in step.

---

## Calling in: the household as an MCP server

`server/…/mcp/` exposes the world. `McpToolRegistry` and `McpAppRegistry` decide
what is visible; `JsonSchemaGenerator` derives the schemas advertised to callers
so the wire contract is generated from the code rather than hand-maintained
beside it. `McpEndpoint` serves HTTP; `McpNatsHandler` serves the same surface
over the Between, which is how a phone reaches its household through a relay
without an inbound port.

`TunnelSessionHandler` owns relay tunnel sessions. Session ids are 128-bit
CSPRNG values, validated for shape, with a live-session ceiling — a tunnel id is
a bearer credential and is treated as one.

---

## Inbound quarantine

**Every inbound agent-to-agent interaction passes through quarantine. This is
structural, not policy** — there is no configuration flag that turns it off,
because a boundary you can switch off is not a boundary.

`interop/DockQuarantine` applies five layers:

1. **Card verification** — who is this, cryptographically (`TrustTierResolver`)
2. **Message sanitization** — provenance tagging, so an agent can always tell
   what came from outside
3. **Rate limiting** — per-source, per-method
4. **Soul item quarantine** — anything that would become memory is *held* until
   the agent's next sleep-Forge cycle, and reviewed there
5. **Information redaction** — `VitalityRedactor` strips internal state from
   what leaves

Trust is tiered: `ANONYMOUS`, `VERIFIED`, `TRUSTED`, `HOUSEHOLD`, `FAMILY`.
The tier a caller resolves to determines what the layers above let through.

The fourth layer is the one worth understanding. A prompt-injection attempt in a
tool result does not get to become a memory the agent later acts on as if it
were its own — it is held, tagged with where it came from, and passed through
the same consolidation the agent applies to everything else. The confused-deputy
shape (tool output steering an agent that has authority the tool does not) is
closed by requester-gating: an action taken on someone's behalf is checked
against *their* grants, not the agent's.

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
- What the companion concludes from it is recorded in her findings ledger as a
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
wyrd researcher status
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

With strict MCP grants on (`WYRDSEKAI_MCP_STRICT_GRANTS=true`), grant the
companions that should reach it from the steward's Study. Without a grant the
desk says so instead of guessing; without a registered service it says that
instead.

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
  give it to the librarian with `researchzosho peer add <name> http://<this
  node>:7070 <token>` and it can ask your shelves when its own hold nothing, one
  hop, labelled as yours. The license gate on what may leave is the same one the
  MCP door uses. When the librarian's own ask fans out to its peers, their
  answers arrive under `peers[]` and the desk shows each as its own library.

## Adding an MCP server

The short version: register the service (transport + endpoint + credentials into
`McpKeyStore`), grant the agents that should reach it, and let discovery surface
it. See [EXTENDING.md](EXTENDING.md) for worked steps and
[SECURITY_MODEL.md](SECURITY_MODEL.md) for the trust boundary this all sits
inside.

Tests worth reading before you change any of it — they encode intent the types
do not: `McpGrantFlowIntegrationTest`, `McpToolOutputQuarantineTest`,
`McpSpendCapTest`, `McpTransportTest`.
