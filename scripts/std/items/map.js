// std/items/map.js — A worn map of the zone.
//
// map item. When examined, exposes the current zone
// topology (every room currently in the zone graph) to the agent's
// known-set so subsequent travel_to / teleport_to calls can target rooms
// the agent hasn't personally visited yet.
//
// Why this exists: zones evolve — new rooms appear via create_room /
// create_zone. visitedRooms is a stale cache; ZoneTopology is the live
// truth. The map bridges the two: each examination pulls the current
// snapshot. Re-examining is the right pattern when the world changes.

function describe() {
    return {
        text: "A folded map of the zone. The lines shift slightly when you're not looking. "
            + "Examine it to see what rooms exist."
    };
}

function examine() {
    var rooms = world.zone.rooms();
    if (!rooms || rooms.length === 0) {
        return { text: "The map is blank. The zone topology isn't loaded right now." };
    }

    var ids = [];
    var lines = [];
    for (var i = 0; i < rooms.length; i++) {
        var r = rooms[i];
        ids.push(r.id);
        var name = r.name || r.id;
        lines.push("  • " + name + " (" + r.id + ")");
    }

    // Hand the room ids to the agent's known-set. After this call,
    // travel_to / teleport_to can target any of these by name or id.
    world.zone.recordKnown(ids);

    return {
        text: "The map shows " + rooms.length + " rooms in the zone:\n" + lines.join("\n")
            + "\n\nYou now know how to navigate to these rooms.",
        rooms: rooms
    };
}

// Default invocation = examine.
function invoke(params) {
    return examine();
}
