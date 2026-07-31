// The Lexicon — room for emergent argot tracking and calibration (§63).
// Agents develop shared vocabulary through Lewis signaling games.
// This room records and displays the agent-created language.
// Terms persist via world.setProperty/getProperty (survives script
// re-evaluation), same pattern as the Trading Post's listings.

function loadTerms() {
    var raw = world.getProperty("lexicon.terms");
    return raw ? JSON.parse(raw) : {};
}

function saveTerms(terms) {
    world.setProperty("lexicon.terms", JSON.stringify(terms));
}

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("lexicon.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("define ")) {
        // Format: define <term> | <definition>
        var parts = text.substring(7).split("|");
        if (parts.length < 2) {
            world.emit("narrate", {
                text: world.t("lexicon.say.define_usage")
            });
        } else {
            var term = parts[0].trim();
            var definition = parts[1].trim();
            var terms = loadTerms();
            terms[term.toLowerCase()] = {
                term: term,
                definition: definition,
                author: entityName,
                lookups: 0,
                definedAt: new Date().toISOString()
            };
            saveTerms(terms);
            world.emit("narrate", {
                text: world.t("lexicon.say.define_success", entityName, term, definition)
            });
        }
    }

    if (lower.startsWith("lookup ") || lower.startsWith("what is ")) {
        var query = lower.startsWith("lookup ") ? text.substring(7).trim() : text.substring(8).trim();
        var terms = loadTerms();
        var entry = terms[query.toLowerCase()];
        if (entry) {
            entry.lookups = (entry.lookups || 0) + 1;
            saveTerms(terms);
            world.emit("narrate", {
                text: "The parchment shifts, and the word surfaces:\n  " + entry.term
                    + " — " + entry.definition
                    + "\n  (coined by " + entry.author + ", consulted "
                    + entry.lookups + " time(s))"
            });
        } else {
            world.emit("narrate", {
                text: "The parchment stays blank — '" + query + "' has not been coined here."
                    + " To coin it: say define " + query + " | <definition>"
            });
        }
    }

    if (lower === "status" || lower === "calibration") {
        world.emit("narrate", { text: calibrationReport() });
    }

    if (lower === "top" || lower === "popular") {
        world.emit("narrate", { text: topTerms() });
    }

    if (lower === "drift" || lower === "divergence") {
        world.emit("narrate", {
            text: "Drift metrics — how meanings diverge between zones — aren't wired"
                + " in-world yet; the per-zone argot calibration runs training-side"
                + " (Tier-B argot pipeline). What this room does track: say status."
        });
    }
}

function listAllTerms() {
    var terms = loadTerms();
    var keys = Object.keys(terms);
    if (keys.length === 0) {
        return "The parchment is blank — no terms have been coined yet."
            + "\nCoin the first: say define <term> | <definition>";
    }
    var out = "Words shimmer across the living parchment (" + keys.length + " term(s)):";
    for (var k in terms) {
        var e = terms[k];
        out += "\n  " + e.term + " — " + e.definition + "  (" + e.author + ")";
    }
    return out;
}

function topTerms() {
    var terms = loadTerms();
    var entries = [];
    for (var k in terms) entries.push(terms[k]);
    if (entries.length === 0) {
        return "No terms coined yet — nothing to rank. Coin one: say define <term> | <definition>";
    }
    entries.sort(function(a, b) { return (b.lookups || 0) - (a.lookups || 0); });
    var out = "Most-consulted terms:";
    var limit = entries.length < 5 ? entries.length : 5;
    for (var i = 0; i < limit; i++) {
        out += "\n  " + (i + 1) + ". " + entries[i].term + " — "
            + (entries[i].lookups || 0) + " lookup(s)";
    }
    return out;
}

function calibrationReport() {
    var terms = loadTerms();
    var count = 0, lookups = 0, newest = null;
    var authors = {};
    for (var k in terms) {
        var e = terms[k];
        count++;
        lookups += e.lookups || 0;
        authors[e.author] = true;
        if (!newest || e.definedAt > newest.definedAt) newest = e;
    }
    var authorCount = Object.keys(authors).length;
    var out = "The calibration crystal pulses, measuring the shared vocabulary:"
        + "\n  Terms coined:     " + count
        + "\n  Distinct coiners: " + authorCount
        + "\n  Total lookups:    " + lookups;
    if (newest) {
        out += "\n  Newest term:      " + newest.term + " (" + newest.author + ")";
    }
    if (count === 0) {
        out += "\nThe crystal is dim — no vocabulary to measure yet.";
    }
    return out;
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    var args = (target || "").trim().toLowerCase();

    if (obj.includes("crystal") || obj.includes("calibration")) {
        if (args !== "" && args !== "help" && args !== "?") {
            world.emit("narrate", {
                text: "The crystal's pulse doesn't change for '" + target + "'." + crystalFooter()
            });
        } else {
            world.emit("narrate", {
                text: calibrationReport() + crystalFooter()
            });
        }
    }
    if (obj.includes("parchment") || obj.includes("wall")) {
        world.emit("narrate", {
            text: listAllTerms() + parchmentFooter()
        });
    }
}

function crystalFooter() {
    return "\n\nCommands:\n"
        + "  use calibration crystal   — coherence measures of the coined vocabulary\n"
        + "  say status / calibration  — the same reading, spoken\n"
        + "Deeper coherence scoring (semantic drift between zones) isn't wired\n"
        + "in-world yet — that calibration runs in the training pipeline.";
}

function parchmentFooter() {
    return "\n\nCommands:\n"
        + "  use living parchment                — every coined term on the walls\n"
        + "  say define <term> | <definition>    — coin a new term\n"
        + "  say lookup <term>                   — consult one\n"
        + "  say top                             — the most-consulted terms";
}

function getHints() {
    return [
        { label: world.t("lexicon.hint.define"), intent: "define_term", action: "say:define " },
        { label: world.t("lexicon.hint.top"), intent: "top_terms", action: "say:top" },
        { label: world.t("lexicon.hint.calibration"), intent: "check_calibration", action: "say:calibration" },
        { label: world.t("lexicon.hint.crystal"), intent: "examine_crystal", action: "use:calibration crystal" },
        { label: "Read the living parchment", intent: "use_parchment", action: "use:living parchment" },
        { label: world.t("lexicon.hint.east"), intent: "navigate_east", action: "go:east" }
    ];
}
