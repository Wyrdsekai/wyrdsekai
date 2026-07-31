// std/room/hall.js — Governance and council template.
// Proposals, voting, council records.
// Creator configures: name, description, theme, governance style.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "hall";
room._name = "Council Hall";
room._description = "A chamber of deliberation. Seats arc around a central speaker's platform.";
room._theme = "";
room._governance = "council"; // "council", "consensus", "archmage", "corporate"

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_governance = function(g) { room._governance = g; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description
    });
}

// NOTE: "speaker platform" and "agenda board" are scripted items
// (scripts/items/speaker_platform.js, scripts/items/agenda_board.js)
// backed by the real council ledger (world.council.*). RoomActor resolves
// them by normalized display name, so their invoke() handles `use`
// directly. (The room-script governance surface — world.submitProposal
// et al. — is gated to the foundation Council Chamber, so template halls
// go through the items.) The say-handlers below route to those real
// surfaces instead of miming a recorded vote.
function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("propose ")) {
        var proposal = text.substring(8).trim();
        world.emit("narrate", {
            text: entityName + " has words for the council. To enter '" + proposal +
                  "' into the real docket, take the platform:\n" +
                  "  use speaker platform propose " + proposal + " -- <description>"
        });
    } else if (lower.startsWith("vote ")) {
        world.emit("narrate", {
            text: "Votes are cast from the platform, onto the council ledger:\n" +
                  "  use speaker platform vote <proposalId> yes|no\n" +
                  "(Find proposal ids on the agenda board: 'use agenda board'.)"
        });
    } else if (lower === "proposals" || lower === "agenda") {
        world.emit("narrate", {
            text: "The docket is read from the board itself: 'use agenda board' " +
                  "(or 'use agenda board tally <proposalId>' for one count)."
        });
    }
}

function getHints() {
    return [
        { label: "Read the agenda board", intent: "view_agenda", action: "use:agenda board" },
        { label: "Take the speaker platform", intent: "propose", action: "use:speaker platform" },
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
