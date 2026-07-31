# Extending Wyrdsekai

Four ways to give a companion new reach, roughly in order of how much you have to
know to use them:

1. **Skills** — declare a service URL, get an executor
2. **`SKILL.md` files** — write a skill in markdown, no Java
3. **MCP servers** — the standard protocol, in both directions
4. **Room and item scripts** — JavaScript inside the world

All four pass the same gates. Nothing here is a bypass: a new capability is still
subject to grants, budgets, and the egress gate.

---

## 1. Skills

A skill is a service the companion can reach. The registration model is
deliberately blunt: **a key present and non-blank lights the matching executor at
boot; an absent key leaves it unregistered.** No plugin discovery, no scanning —
if you did not configure it, it does not exist.

Set them in `wyrdsekai.skills` (or the matching `WYRDSEKAI_SKILLS_*` variable):

| Key | What it reaches |
|---|---|
| `openclaw.url` | The OpenClaw skill gateway — a container exposing a catalogue over WebSocket |
| `kiwix.url` | Offline Wikipedia / knowledge corpora |
| `ha.url` | Home Assistant |
| `caldav.url` | Calendar |
| `whisper.url` | Speech-to-text |
| `obsidian.vault_path` | An Obsidian vault |
| `signal.phone` | Signal messaging |
| `emergency.contacts` | `"Name:phone[:relation],…"` for `herald.call.emergency` |

Boolean enables (`gcal.enabled`, `gmail.enabled`, `stripe.enabled`, `rss.enabled`,
and friends) and path keys (`fs.mounts`, `filesearch.roots`, `docs.path`) work the
same way.

**Credentials do not go here.** Telephony, mail and the rest read from the
credential chain — The Safe slots (`twilio.account_sid`, `twilio.auth_token`, …)
or `WYRDSEKAI_CRED_*`. The URL says *where*; The Safe holds *the secret*, so the
agent can use a credential it cannot read. See [CONFIGURATION.md](CONFIGURATION.md).

### OpenClaw

```bash
wyrd openclaw setup
```

Stands up the containerized gateway and writes `openclaw.url`. The executor loads
the catalogue on connect and reconnects with backoff, so a gateway restart does
not require a node restart. The bundled catalogue under
`core/src/main/resources/openclaw-skills/` includes notes, reminders, blog
watching and similar — read one to see the shape.

---

## 2. Writing a skill in markdown

`SkillMdImporter` reads `SKILL.md` files: frontmatter declares the skill,
the body *is* the instructions. This is the lowest-friction path — no module, no
build step, no Java. If your extension is "the companion should know how to do
this thing with a tool it already has," write it here rather than in code.

These files are functional data, not documentation. The build ships them because
the runtime parses them.

---

## 3. MCP servers

Wyrdsekai both calls MCP servers and exposes itself as one. Four transports
(stdio, HTTP, SSE, WebSocket), grants per agent-and-resource, a hard daily spend
cap, a circuit breaker, and structural quarantine on everything inbound.

[MCP.md](MCP.md) is the whole story, including why tool output is held until the
next sleep-Forge cycle instead of becoming memory immediately.

---

## 4. Coding backends

A companion can summon a coding agent. Which ones are available is configuration,
not code — each gets a `WYRDSEKAI_CODING_<BACKEND>_*` group:

`goose` (the default), `codex`, `opencode`, `openhands`, `aider`, `gemini-cli`,
`claude-sdk`, `codeplane`, `devin`.

```bash
WYRDSEKAI_CODING_DEFAULT_BACKEND=goose
WYRDSEKAI_CODING_GOOSE_ENABLED=true
WYRDSEKAI_CODING_GOOSE_MODEL=…
```

Container-shaped backends take resource ceilings —
`WYRDSEKAI_CODING_OPENHANDS_MAX_RAM_GB`, `_MAX_DISK_GB`, `_MAX_WALLCLOCK_MIN` —
because an agent that can summon a coding agent can otherwise summon an
unbounded one.

### The egress gate

`WYRDSEKAI_CODING_EGRESS_GATE` is **on by default**. Every subprocess a coding
backend spawns has its network egress gated. Turning it off means a coding agent
running on your machine can reach anything your machine can reach. There are
legitimate reasons to do that; do it knowingly.

---

## 5. Room and item scripts

Rooms and objects are JavaScript, executed in a GraalJS sandbox with a
capability-manifest validator that gates which API tiers a script may touch.
Scripts live in `scripts/rooms/` and `scripts/items/` and reach the world through
the `world.*` API.

This is the most *world-shaped* extension point: it does not add a tool the
companion calls, it changes what is true in the place they live.

**If you just want to make a room or an object** — including by asking your
companion to make it for you — start with [AUTHORING.md](AUTHORING.md).
[ROOMS.md](ROOMS.md) is the full API surface underneath it.

---

## Adding a new backend properly

If you are adding a genuinely new integration rather than configuring an existing
one:

1. Put it behind the same gates. `McpGatewayService` for tools, the egress gate
   for subprocesses, The Safe for credentials.
2. Make absence the default. An unconfigured integration should not register.
3. Write the failure path first. A backend that is down should degrade the
   companion's options, not break their turn.
4. Open a discussion before the PR if it touches the gates themselves — see
   [CONTRIBUTING.md](../CONTRIBUTING.md).
