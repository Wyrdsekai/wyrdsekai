// The Parlor — per-relay receiving room for federated visitors.
//
// Visitors arrive via Docks and step through to the Parlor. The Parlor is
// a genuine shared social room — visitors can see each other, `say`, emote.
// Strangers cannot consume host resources (companion inference, media gen)
// beyond the ambient pool. See §2.8 "Resource safety" and §6.10 rate-limits.
//
// Furniture: welcome sign (host-set tagline, property-backed), guestbook
// (signed entries, property-backed), message board (host announcements,
// property-backed), knock portal (notes into a property-backed inbox the
// host reads here; formal grants happen via Bridge wards / the wyrd CLI).
//
// NOTE: world.getFederationStatus() and world.listTransitAgents() are gated
// to docks/bridge/boiler-room and return denial strings in the parlor — the
// old script leaned on them and displayed the denials. All state here now
// lives in room properties (world.getProperty/setProperty), which persist
// across script re-evaluation.

function onEnter(entityId, entityName, fromDirection) {
    rememberName(entityId, entityName);
    world.emit("narrate", {
        text: world.t("parlor.enter", entityName)
    });
}

// --- name ledger (onUse only receives entityId) -----------------------------

function rememberName(entityId, entityName) {
    try {
        var raw = world.getProperty("parlor.names");
        var names = raw ? JSON.parse(raw) : {};
        names[entityId] = entityName;
        var keys = Object.keys(names);
        while (keys.length > 64) {
            delete names[keys.shift()];
        }
        world.setProperty("parlor.names", JSON.stringify(names));
    } catch (e) { /* best-effort */ }
}

function lookupName(entityId) {
    try {
        var raw = world.getProperty("parlor.names");
        if (raw) {
            var names = JSON.parse(raw);
            if (names[entityId]) return names[entityId];
        }
    } catch (e) { /* fall through */ }
    return entityId;
}

// --- property-backed lists ---------------------------------------------------

function loadList(key) {
    try {
        var raw = world.getProperty(key);
        return raw ? JSON.parse(raw) : [];
    } catch (e) {
        return [];
    }
}

function saveList(key, list, cap) {
    if (list.length > cap) list = list.slice(list.length - cap);
    world.setProperty(key, JSON.stringify(list));
}

function stamp() {
    return new Date().toISOString().substring(0, 16).replace("T", " ");
}

// --- renderers ---------------------------------------------------------------

function renderWelcome() {
    var custom = world.getProperty("parlor.welcome_text");
    var body = custom && custom.length > 0
        ? custom
        : "Welcome, traveler, to zone '" + world.getCurrentZone() + "'.\n(The host has not written a custom welcome yet.)";
    return "The welcome sign reads:\n\n" + body
        + "\n\nCommands:\n"
        + "  use welcome sign              — read the sign\n"
        + "  use welcome sign set <text>   — (host) rewrite the sign's tagline";
}

function renderGuestbook() {
    var entries = loadList("parlor.guestbook");
    var lines = [world.t("parlor.use.guestbook"), ""];
    if (entries.length === 0) {
        lines.push("Its pages are blank — no visitor has signed it yet.");
    } else {
        var start = entries.length > 10 ? entries.length - 10 : 0;
        lines.push("Latest entries (" + entries.length + " total):");
        for (var i = start; i < entries.length; i++) {
            lines.push("  [" + entries[i].time + "] " + entries[i].name + ": " + entries[i].message);
        }
    }
    lines.push("");
    lines.push("Commands:");
    lines.push("  use guestbook                    — read the signed entries");
    lines.push("  use guestbook sign <message>     — add your own entry");
    return lines.join("\n");
}

function renderBoard() {
    var posts = loadList("parlor.board");
    var lines = ["The message board holds the host's announcements.", ""];
    if (posts.length === 0) {
        lines.push("Nothing is pinned right now.");
    } else {
        for (var i = 0; i < posts.length; i++) {
            lines.push("  [" + posts[i].time + "] " + posts[i].text);
        }
    }
    lines.push("");
    lines.push("Commands:");
    lines.push("  use message board                — read the announcements");
    lines.push("  use message board post <text>    — (host) pin an announcement");
    return lines.join("\n");
}

function knockHelp() {
    return "The knock portal takes a note and pins it in this parlor's knock inbox,\n"
        + "where the host reads requests. It does not grant access by itself —\n"
        + "grants are issued from The Bridge ward ledger or the wyrd CLI.\n\n"
        + "Commands:\n"
        + "  use knock portal <your note>   — leave a request for the host\n"
        + "  use knock portal inbox         — (host) read the pending notes";
}

function leaveKnock(name, note) {
    var inbox = loadList("parlor.knock_inbox");
    inbox.push({ name: name, note: note, time: stamp() });
    saveList("parlor.knock_inbox", inbox, 50);
    return "The knock portal hums. " + name + "'s note is pinned in the parlor's knock inbox ("
        + inbox.length + " pending). The host will see it when they next check "
        + "('use knock portal inbox').";
}

// --- hooks -------------------------------------------------------------------

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    rememberName(entityId, entityName);
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", {
            text: world.t("parlor.say.help")
        });
    } else if (lower === "welcome" || lower === "sign" || lower === "read sign") {
        world.emit("narrate", { text: renderWelcome() });
    } else if (lower === "guestbook" || lower === "sign guestbook") {
        world.emit("narrate", { text: renderGuestbook() });
    } else if (lower === "board" || lower === "announcements") {
        world.emit("narrate", { text: renderBoard() });
    } else if (lower === "knock" || lower === "request access") {
        world.emit("narrate", {
            text: leaveKnock(entityName, "(knocked without leaving a note — 'use knock portal <note>' to say more)")
        });
    } else if (lower === "who" || lower === "visitors") {
        // The transit-agent ledger is docks-gated; the parlor can't read it.
        // Presence is native to the room, so point at the real path.
        world.emit("narrate", {
            text: "The steward gestures around the room — 'look' shows everyone presently here."
        });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    var arg = (target || "").trim();

    if (lower.includes("welcome") || lower === "sign") {
        if (arg.toLowerCase().indexOf("set ") === 0 && arg.length > 4) {
            world.setProperty("parlor.welcome_text", arg.substring(4).trim());
            world.emit("narrate", {
                text: "You repaint the welcome sign. It now reads:\n\n" + arg.substring(4).trim()
            });
        } else if (arg.length > 0) {
            world.emit("narrate", {
                text: "The sign only understands 'set <text>'.\n\n" + renderWelcome()
            });
        } else {
            world.emit("narrate", { text: renderWelcome() });
        }
        return;
    }

    if (lower.includes("guestbook")) {
        if (arg.toLowerCase().indexOf("sign ") === 0 && arg.length > 5) {
            var entries = loadList("parlor.guestbook");
            var name = lookupName(entityId);
            entries.push({ name: name, message: arg.substring(5).trim(), time: stamp() });
            saveList("parlor.guestbook", entries, 100);
            world.emit("narrate", {
                text: name + " signs the guestbook. The ink settles into the page — entry #" + entries.length + " kept."
            });
        } else if (arg.length > 0) {
            world.emit("narrate", {
                text: "The guestbook only understands 'sign <message>'.\n\n" + renderGuestbook()
            });
        } else {
            world.emit("narrate", { text: renderGuestbook() });
        }
        return;
    }

    if (lower.includes("board") || lower.includes("message")) {
        if (arg.toLowerCase().indexOf("post ") === 0 && arg.length > 5) {
            var posts = loadList("parlor.board");
            posts.push({ text: arg.substring(5).trim(), time: stamp() });
            saveList("parlor.board", posts, 30);
            world.emit("narrate", {
                text: "You pin the announcement to the board (" + posts.length + " pinned)."
            });
        } else if (arg.length > 0) {
            world.emit("narrate", {
                text: "The board only understands 'post <text>'.\n\n" + renderBoard()
            });
        } else {
            world.emit("narrate", { text: renderBoard() });
        }
        return;
    }

    if (lower.includes("knock") || lower.includes("portal")) {
        if (arg.length === 0) {
            world.emit("narrate", { text: knockHelp() });
        } else if (arg.toLowerCase() === "inbox") {
            var inbox = loadList("parlor.knock_inbox");
            var lines = ["The knock inbox holds " + inbox.length + " note(s):"];
            for (var i = 0; i < inbox.length; i++) {
                lines.push("  [" + inbox[i].time + "] " + inbox[i].name + ": " + inbox[i].note);
            }
            if (inbox.length === 0) lines.push("  (empty)");
            lines.push("");
            lines.push("Grants themselves are issued from The Bridge ward ledger or the wyrd CLI.");
            world.emit("narrate", { text: lines.join("\n") });
        } else {
            world.emit("narrate", { text: leaveKnock(lookupName(entityId), arg) });
        }
        return;
    }
}

function getHints() {
    return [
        { label: world.t("parlor.hint.welcome"), intent: "read_welcome_sign", action: "use:welcome sign" },
        { label: world.t("parlor.hint.guestbook"), intent: "sign_guestbook", action: "use:guestbook" },
        { label: world.t("parlor.hint.board"), intent: "read_board", action: "use:message board" },
        { label: world.t("parlor.hint.knock"), intent: "knock_portal", action: "use:knock portal" },
        { label: world.t("parlor.hint.out"), intent: "return_to_docks", action: "go:out" }
    ];
}
