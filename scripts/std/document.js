// std/document.js — Agent-written persistent artifact.
// A document has a title, author, content, and format. Readable and editable.
// Creator configures: title, author, format ("note", "letter", "report", "story").
// Override: read() for custom rendering, edit() for validation.

item._type = "document";
item._title = "";
item._author = "";
item._content = "";
item._format = "note";
item._created = new Date().toISOString();

item.set_title = function(t) { item._title = t; };
item.set_author = function(a) { item._author = a; };
item.set_content = function(c) { item._content = c; };
item.set_format = function(f) { item._format = f; };

function invoke(params) {
    var action = params.action || "read";

    if (action === "read") {
        return {
            title: item._title,
            author: item._author,
            format: item._format,
            content: item._content,
            created: item._created
        };
    }

    if (action === "edit") {
        if (params.content) {
            item._content = params.content;
        }
        if (params.append) {
            item._content += "\n" + params.append;
        }
        if (params.title) {
            item._title = params.title;
        }
        return { title: item._title, updated: true, length: item._content.length };
    }

    if (action === "polish") {
        // Use LLM to improve the writing
        var polished = world.llm.analyze(item._content,
            "Improve this " + item._format + " while preserving its meaning and voice.");
        if (polished && polished.indexOf("[error]") !== 0) {
            item._content = polished;
            return { title: item._title, polished: true, content: polished };
        }
        return { title: item._title, polished: false, error: "Could not polish" };
    }

    return { error: "Unknown action: " + action + ". Use read, edit, or polish." };
}
