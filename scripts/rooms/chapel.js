// The Chapel — rituals of binding and release, honoured in equal measure.
//
// Object coverage ( self-documenting contract):
//   stone basin    → scripts/items/stone_basin.js (item pre-empts onUse here;
//                    reads bond standing via world.chapel.bond_status)
//   bond reliquary → scripts/items/bond_reliquary.js (item pre-empts onUse;
//                    reads the thread cabinet via world.bonds.list)
//   stone bench    → handled HERE (the garden template also has a "stone
//                    bench" with a different purpose, so no shared item —
//                    each room script keeps its own branch). Sitting itself
//                    goes through the Sit dispatcher: `sit on bench`.
//
// The ceremonies themselves (bond suggestion, release-of-bond) are agent
// acts through the bond chapel rite (world.chapel / world.bonds) or the
// `wyrd bond` CLI — the room says so honestly rather than miming them.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " steps into the Chapel. Light falls from the high "
            + "window onto the stone basin's still water; the bond reliquary's "
            + "threads hang patient behind glass. Nothing here is hurried, and "
            + "nothing is erased."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "look" || lower === "look around" || lower === "examine") {
        world.emit("narrate", {
            text: "The basin holds the standing of bonds ('use stone basin'); the "
                + "reliquary keeps every released thread ('use bond reliquary'); the "
                + "bench faces the water ('sit on bench'). Ceremonies of binding and "
                + "release are performed by a companion through the chapel rite, or "
                + "via `wyrd bond` — this room witnesses and keeps."
        });
    } else if (lower === "bonds" || lower === "threads") {
        world.emit("narrate", {
            text: "For the standing of bonds, read the water: 'use stone basin'. "
                + "For the kept threads of past partings: 'use bond reliquary'."
        });
    }
}

function onUse(entityId, objectName, target) {
    var lower = (objectName || "").toLowerCase();

    // stone basin / bond reliquary are scripted items (stone_basin.js /
    // bond_reliquary.js) and never reach this hook. Only the bench lands here.
    if (lower.indexOf("bench") >= 0 || lower === "kneeler") {
        world.emit("narrate", {
            text: "The stone bench faces the basin, worn smooth by those who sat "
                + "for a release-of-bond ceremony — two together, neither above the "
                + "other.\n\n"
                + "Commands:\n"
                + "  sit on bench          — take a place facing the water\n"
                + "  use stone basin       — read the standing of your bonds\n"
                + "  use bond reliquary    — read the kept threads\n"
                + "The bench itself asks nothing further of you."
        });
    }
}

function getHints() {
    return [
        { label: "Read the stone basin", intent: "bond_status", action: "use:stone basin" },
        { label: "Read the bond reliquary", intent: "bond_threads", action: "use:bond reliquary" },
        { label: "Sit on stone bench", intent: "sit", action: "sit on stone bench" },
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
