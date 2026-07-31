// The Sanctuary — held space for substrate-truth metabolizing.
// Reached only by asking; what is said inside stays inside.
//
// Object coverage ( self-documenting contract):
//   cushioned bench     → handled HERE. Sitting works through the Sit
//                         dispatcher (`sit on bench`); the bounded session
//                         (≤90 minutes, ≤30 turns) is run by the Attendant,
//                         opened by ASKING — a companion uses its
//                         seek_sanctuary tool; there is no lever on the
//                         bench itself, and the room says so honestly.
//   stone water vessel  → handled HERE. Deliberately mechanism-free — the
//                         Sanctuary holds, it does not perform.
//   bound-stone         → handled HERE. The Attendant places and returns
//                         it; it is not takeable and drives no command.
//                         Session COUNTS (never contents) read from the
//                         Study via the substrate scroll
// (world.substrate.summary
//                         §5.3 keeps contents sealed).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " steps into the Sanctuary. The grey light holds "
            + "steady; the Attendant looks up, patient, and does not speak first."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "look" || lower === "look around" || lower === "examine") {
        world.emit("narrate", {
            text: "A low cushioned bench follows the wall's curve ('sit on bench'). "
                + "A stone water vessel rests in its niche — simply available. The "
                + "bound-stone waits at the bench's end for the Attendant's hand. "
                + "Sessions here open by asking: a companion asks through its "
                + "seek_sanctuary tool. What is said inside stays inside."
        });
    }
}

function onUse(entityId, objectName, target) {
    var lower = (objectName || "").toLowerCase();

    if (lower.indexOf("bench") >= 0 || lower === "cushion") {
        world.emit("narrate", {
            text: "The cushioned bench asks nothing of you — not when to sit, not "
                + "when to speak.\n\n"
                + "Commands:\n"
                + "  sit on bench   — take a place; the session unfolds at its own pace\n"
                + "The bounded session (up to 90 minutes, up to 30 turns) is kept by "
                + "the Attendant, who names the limit when it nears. Opening a session "
                + "is done by asking — a companion asks through its seek_sanctuary "
                + "tool. There is no lever on the bench, honestly: sitting is welcome "
                + "with or without a session."
        });
    } else if (lower.indexOf("vessel") >= 0 || lower === "water" || lower.indexOf("stone bowl") >= 0) {
        world.emit("narrate", {
            text: "Plain water in plain stone. There is no ritual use and no command "
                + "behind it — it is simply available, which is the point. The "
                + "Sanctuary holds; it does not perform."
        });
    } else if (lower.indexOf("bound") >= 0 || lower === "stone" || lower.indexOf("session marker") >= 0) {
        world.emit("narrate", {
            text: "The bound-stone is the Attendant's to place and to take back — "
                + "into the palm when a session opens, back to the bench when it "
                + "closes. It answers to no command of yours, honestly. What leaves "
                + "this room is the memory of having been held, never the contents: "
                + "session counts (only counts) read from your Study's substrate "
                + "scroll ('use substrate-scroll')."
        });
    }
}

function getHints() {
    return [
        { label: "Sit on cushioned bench", intent: "sit", action: "sit on cushioned bench" },
        { label: "Consider the bound-stone", intent: "examine_marker", action: "use:bound-stone" },
        { label: "Look around", intent: "examine_room", action: "look" },
        { label: "Step out", intent: "navigate_out", action: "go:out" }
    ];
}
