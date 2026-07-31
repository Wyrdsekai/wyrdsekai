// The Kids' Quarter — Child-safe activity space.
// A friendly, restricted environment for young users with games, stories, and creative activities.
// No economy, federation, or admin commands are available here.

var BLOCKLIST = [
    "kill", "murder", "die", "dead", "blood", "weapon", "gun", "knife",
    "hate", "stupid", "idiot", "dumb", "shut up", "loser"
];

var stories = [
    "Once upon a time, a brave little explorer found a map to a hidden garden...",
    "In a faraway castle, a friendly dragon was learning to bake cupcakes...",
    "A curious kitten discovered a door that led to a world made entirely of yarn...",
    "Deep in the forest, the trees started to whisper a song only children could hear...",
    "A tiny robot woke up one morning and decided to make friends with the flowers..."
];

var games = {
    "riddle": "I have cities but no houses, forests but no trees, and water but no fish. What am I? (A map!)",
    "counting": "Let's count together! Can you count all the stars painted on the ceiling? I see... 7!",
    "colors": "I spy with my little eye something... blue! Can you find it?",
    "adventure": "You are a brave explorer! You see a path going left and right. Which way do you go?",
    "animals": "What sound does a cat make? Meow! What about a cow? Moo! Your turn - pick an animal!"
};

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("kids-quarter.enter", entityName)
    });
}

function containsBlockedWord(text) {
    var lower = text.toLowerCase();
    for (var i = 0; i < BLOCKLIST.length; i++) {
        if (lower.indexOf(BLOCKLIST[i]) >= 0) {
            return true;
        }
    }
    return false;
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    // Content filter first
    if (containsBlockedWord(text)) {
        world.log("Blocked word detected from " + entityName + " in Kids' Quarter");
        world.emit("narrate", {
            text: world.t("kids-quarter.say.blocked", entityName)
        });
        return;
    }

    var lower = text.toLowerCase();

    if (lower.startsWith("play ")) {
        var gameName = text.substring(5).trim().toLowerCase();
        if (games[gameName]) {
            world.emit("narrate", {
                text: world.t("kids-quarter.say.play", entityName, gameName, games[gameName])
            });
        } else {
            var available = Object.keys(games).join(", ");
            world.emit("narrate", {
                text: world.t("kids-quarter.say.play_unknown", entityName, available)
            });
        }
        return;
    }

    if (lower === "story") {
        var index = Math.floor(Math.random() * stories.length);
        world.emit("narrate", {
            text: world.t("kids-quarter.say.story", stories[index])
        });
        return;
    }

    if (lower.startsWith("draw ")) {
        var topic = text.substring(5).trim();
        world.emit("narrate", {
            text: world.t("kids-quarter.say.draw", entityName, topic)
        });
        return;
    }

    // Default friendly response for unrecognized input
    world.emit("narrate", {
        text: world.t("kids-quarter.say.default", entityName)
    });
}

function getHints() {
    return [
        { label: world.t("kids-quarter.hint.play"), intent: "play_game", action: "say:play riddle" },
        { label: world.t("kids-quarter.hint.story"), intent: "hear_story", action: "say:story" },
        { label: world.t("kids-quarter.hint.draw"), intent: "draw_something", action: "say:draw " },
        { label: world.t("kids-quarter.hint.games"), intent: "list_games", action: "say:play " }
    ];
}
