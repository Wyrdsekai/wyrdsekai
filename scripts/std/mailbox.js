// std/mailbox.js — The household mailbox: read what has arrived, write to someone by name.
//
// Until 2026-09-15 the "mailbox" template inherited std/container: a box that held ITEMS,
// with put/take/list, while a real message store sat behind world.mailbox.* unused. A
// companion who asked for a mailbox got a crate and was told it was ready to use. This is
// the mailbox.
//
// Addresses are what a person types: a name for someone here ("mia"), name@zone for a
// federated household ("mia@alpha"), and an outside address goes out through the household's
// mail account once that is configured. Naming your own zone is the same as the bare name.

item._type = "mailbox";
item._label = "mailbox";

item.set_label = function (l) { item._label = l; };

function summarise(list) {
    var lines = [];
    for (var i = 0; i < list.length; i++) {
        var m = list[i];
        var when = m.ts ? new Date(m.ts).toISOString().replace("T", " ").substring(0, 16) : "";
        lines.push("  " + (i + 1) + ". " + (m.read ? "  " : "• ")
            + (m.fromAddress || m.from)
            + (m.subject ? " — " + m.subject : "")
            + (when ? "   (" + when + ")" : ""));
    }
    return lines;
}

function pick(list, which) {
    if (!which) return null;
    var n = parseInt(which, 10);
    if (!isNaN(n) && n >= 1 && n <= list.length) return list[n - 1];
    for (var i = 0; i < list.length; i++) {
        if (list[i].id === which) return list[i];
    }
    return null;
}

function invoke(params) {
    params = params || {};
    var argStr = params.args == null ? "" : String(params.args);
    var words = argStr.trim().split(/\s+/).filter(function (w) { return w.length > 0; });
    var action = params.action ? String(params.action).toLowerCase()
        : (words.length ? String(words[0]).toLowerCase() : "list");
    if (!params.action && words.length) words.shift();

    if (action === "help") {
        return { text: [
            "The " + item._label + ":",
            "  use mailbox                      — what has arrived",
            "  use mailbox read <n>             — read one, and mark it read",
            "  use mailbox archive <n>          — put one away",
            "  use mailbox send <who> <subject> | <body>",
            "Who is a name here (mia), or name@zone for another household (mia@alpha).",
            "A name with a space in it goes in quotes: send \"ada lovelace\" hello | ..."
        ].join("\n") };
    }

    if (action === "send" || action === "write" || action === "mail") {
        if (!words.length) return { error: "Send to whom? — use mailbox send <who> <subject> | <body>" };
        // A name with a space in it is written in quotes: send "ada lovelace" the garden | ...
        var to = words.shift();
        if (to.charAt(0) === '"') {
            var parts = [to];
            while (words.length && parts[parts.length - 1].slice(-1) !== '"') parts.push(words.shift());
            to = parts.join(" ");
        }
        var rest = words.join(" ");
        var body = "";
        var subject = rest;
        var bar = rest.indexOf("|");
        if (bar >= 0) {
            subject = rest.substring(0, bar).trim();
            body = rest.substring(bar + 1).trim();
        }
        if (!body) { body = subject; subject = ""; }
        if (!body) return { error: "Nothing to say. — use mailbox send <who> <subject> | <body>" };
        var sent = world.mailbox.send(to, subject, body);
        if (!sent || !sent.ok) {
            var why = sent && sent.error ? sent.error : "the letter would not go";
            if (why === "unknown_recipient") {
                var known = (sent.known || []).join(", ");
                return { error: "Nobody here is called '" + to + "'."
                    + (known ? " Here: " + known + "." : "") };
            }
            if (why === "federated_mail_not_yet") {
                return { error: "Mail to another household isn't carried yet — that comes with federation." };
            }
            if (why === "external_mail_not_configured") {
                return { error: "No mail account is configured for letters leaving the household." };
            }
            return { error: String(why) };
        }
        return { text: "Sent to " + (sent.to || to) + ".", id: sent.id, to: sent.to };
    }

    var inbox = world.mailbox.inbox() || [];

    if (action === "read" || action === "open") {
        var target = pick(inbox, words.length ? words[0] : null);
        if (!target) return { error: "Which one? — use mailbox read <n>" };
        var full = world.mailbox.read(target.id);
        if (!full || full.error) return { error: "That one isn't here any more." };
        world.mailbox.mark_read(target.id);
        return { text: [
            "From:    " + (full.fromAddress || full.from),
            "Subject: " + (full.subject || "(none)"),
            "",
            full.body || full.content || ""
        ].join("\n"), message: full };
    }

    if (action === "archive" || action === "put_away") {
        var away = pick(inbox, words.length ? words[0] : null);
        if (!away) return { error: "Which one? — use mailbox archive <n>" };
        var done = world.mailbox.archive(away.id);
        if (!done || !done.ok) return { error: "That one isn't here any more." };
        return { text: "Put away." };
    }

    if (inbox.length === 0) {
        return { text: "The " + item._label + " is empty.", count: 0, unread: 0 };
    }
    var unread = 0;
    for (var i = 0; i < inbox.length; i++) if (!inbox[i].read) unread++;
    var head = inbox.length + " in the " + item._label
        + (unread ? ", " + unread + " unread:" : ", all read:");
    return {
        text: [head].concat(summarise(inbox)).join("\n"),
        count: inbox.length,
        unread: unread,
        messages: inbox
    };
}
