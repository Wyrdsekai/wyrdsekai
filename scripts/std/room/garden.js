// std/room/garden.js — Ambient/social space template.
// Seasonal descriptions, growth over time, peaceful imprint.
// Creator configures: name, description, theme, season.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "garden";
room._name = "Garden";
room._description = "Greenery surrounds you. The air is fresh and the light is gentle.";
room._theme = "";
room._season = "spring"; // "spring", "summer", "autumn", "winter"

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_season = function(s) { room._season = s; };

function onEnter(entityId, entityName, fromDirection) {
    var seasonal = "";
    if (room._season === "spring") seasonal = "New growth unfolds everywhere.";
    else if (room._season === "summer") seasonal = "Warm light filters through full canopy.";
    else if (room._season === "autumn") seasonal = "Amber and gold leaves drift on the breeze.";
    else if (room._season === "winter") seasonal = "Bare branches trace patterns against a pale sky.";

    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description + " " + seasonal
    });
}

function onUse(entityId, objectName, target) {
    var name = (objectName || "").toLowerCase();

    // The garden's "stone bench" shares a name with the Chapel's bench but
    // not a purpose (rest vs. ritual) — so no shared scripted item; each
    // room script keeps its own branch. Sitting itself goes through the
    // Sit dispatcher: `sit on bench` works here with a generic posture.
    if (name.indexOf("bench") >= 0) {
        world.emit("narrate", {
            text: "The stone bench is weathered smooth beneath the tree" +
                  (room._season === "autumn" ? ", drifted leaves gathered at its feet" : "") + ".\n\n" +
                  "Commands:\n" +
                  "  sit on bench   — take a seat (the Sit dispatcher handles posture)\n" +
                  "The bench asks nothing more of you — it is for resting, honestly."
        });
    } else if (name.indexOf("fountain") >= 0) {
        // Real mechanism: a small vitality suggestion through the imprint
        // system (world.suggestVitality) — the same channel other ambient
        // rooms use. No pretend effects beyond it.
        world.suggestVitality(entityId, "energy", 0.02, "resting by the garden fountain");
        world.emit("narrate", {
            text: "Clear water rises and folds back on itself. You linger a moment, " +
                  "and something in you settles — the garden suggests a little " +
                  "energy back to your tanks.\n\n" +
                  "Commands:\n" +
                  "  use fountain   — linger again (each visit suggests a small recovery)\n" +
                  "That is all the fountain does, honestly; its work is quiet."
        });
    }
}

function getHints() {
    return [
        { label: "Look around", intent: "examine_room", action: "look" },
        { label: "Sit on stone bench", intent: "sit", action: "sit on stone bench" },
        { label: "Linger at the fountain", intent: "rest", action: "use:fountain" },
        { label: "Sit quietly", intent: "rest", action: "emote:sits quietly among the greenery" }
    ];
}
