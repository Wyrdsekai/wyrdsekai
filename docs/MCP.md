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

## Adding an MCP server

The short version: register the service (transport + endpoint + credentials into
`McpKeyStore`), grant the agents that should reach it, and let discovery surface
it. See [EXTENDING.md](EXTENDING.md) for worked steps and
[SECURITY_MODEL.md](SECURITY_MODEL.md) for the trust boundary this all sits
inside.

Tests worth reading before you change any of it — they encode intent the types
do not: `McpGrantFlowIntegrationTest`, `McpToolOutputQuarantineTest`,
`McpSpendCapTest`, `McpTransportTest`.
