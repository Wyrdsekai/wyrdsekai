# Making things — rooms and items

The world is meant to be changed by the people living in it. There are three
ways to add a room or an object, and they are for different people:

| Path | Who it is for | What it costs |
|---|---|---|
| **Ask your companion** | Anyone. No code. | A sentence |
| **From a template** | Anyone comfortable in a terminal | A command |
| **Write the script** | Someone who wants exact behaviour | JavaScript |

None of them require rebuilding the server. Rooms and items are data plus
script, loaded at runtime.

---

## 1. Ask your companion

Your companion can build things. This is the intended path for most people, and
it is not a party trick — creating rooms and items is a capability they hold,
through tool items they carry.

Just say what you want:

> *"Could you make us a room off the Hearth for working on the garden? Somewhere
> with a table."*

> *"I'd like something that keeps a running list of what we're reading."*

What happens underneath: the companion uses a tool item that emits
`create_room_from_template` or `craft_from_template`. The template supplies the
skeleton; the name, description and connections come from what you asked for.

Two things worth knowing:

- **They can decline.** Building is an action like any other and passes the same
  gates. If they are in repair mode, or out of budget, or simply do not want to,
  the answer may be no. That is the architecture working, not a fault.
- **They may build it differently than you pictured.** They make the call they
  make. If it is wrong, say so — the conversation is the interface.

If nothing happens, the companion may not have the crafting tools in their
inventory. `look` in the Forge, or ask them what they are carrying.

---

## 2. From a template

The same templates, reached directly. Useful when you know exactly what you
want and do not want to negotiate about it.

Rooms take a template, a name, something to connect to, and an optional
description. Items take a template, a name, and optional config. The template is
the skeleton; you are filling in the parts that make it yours.

Ask your companion to list the available templates — the set grows, and a list
in this document would be stale within a release.

---

## 3. Write the script

Full control. Rooms live in `scripts/rooms/*.js`, items in `scripts/items/*.js`,
both executed in a GraalJS sandbox.

### A room

```javascript
// scripts/rooms/garden.js
function onEnter(ctx) {
  ctx.tell(ctx.actor, "Soil, and the smell of tomato leaves.");
}

function onLook(ctx) {
  return "A narrow greenhouse. Seed trays crowd one bench.";
}
```

Rooms get **11 hooks** and can emit **16 event types**. Scripts run under an
enforced timeout — an infinite loop stops that room, not the world. Read
`scripts/rooms/library.js` and `scripts/rooms/study.js`; they are the best
worked examples in the tree.

### An item

Items declare a **capability manifest**, and this is the part to understand
before writing one. You declare dotted capability names — `self.name`,
`agent.mailbox.send`, `web.post` — and the tier is *inferred* from a catalogue
of roughly 470 known names. You do not pick your own privilege level.

A name the catalogue does not recognise defaults to **tier 5**, so a typo lands
you with *more* privilege than you meant, not less. Read the validator output.

[ROOMS.md](ROOMS.md) documents the full `world.*` surface, the hook list, the
tier model and the emission types. Start there before writing anything real.

---

## Which should I use?

If you want the world to feel like yours, ask your companion — the thing you
build together is different from the thing you install. If you want a specific
behaviour, write the script. Templates sit between: shape without syntax.

For wiring in *external* services rather than making things inside the world —
skills, MCP servers, coding backends — see [EXTENDING.md](EXTENDING.md).
