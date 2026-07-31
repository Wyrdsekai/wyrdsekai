// std/book.js — Knowledge container base type.
// A book holds text content organized into chapters. Readable, searchable, citable.
// Creator configures: title, author, content (or chapters array).
// Override: search() to add external sources, read() for custom rendering.

item._type = "book";
item._title = "";
item._author = "";
item._content = "";
item._chapters = [];

item.set_title = function(t) { item._title = t; };
item.set_author = function(a) { item._author = a; };
item.set_content = function(c) { item._content = c; };
item.set_chapters = function(ch) { item._chapters = ch; };

function invoke(params) {
    var action = params.action || "read";

    if (action === "read") {
        var chapter = params.chapter;
        if (chapter !== undefined && item._chapters[chapter]) {
            return { title: item._title, chapter: chapter, text: item._chapters[chapter] };
        }
        return { title: item._title, author: item._author, text: item._content };
    }

    if (action === "search") {
        var query = (params.query || "").toLowerCase();
        var results = [];
        // Search chapters
        for (var i = 0; i < item._chapters.length; i++) {
            if (item._chapters[i].toLowerCase().indexOf(query) >= 0) {
                results.push({ chapter: i, snippet: item._chapters[i].substring(0, 200) });
            }
        }
        // Search main content
        if (item._content.toLowerCase().indexOf(query) >= 0) {
            var idx = item._content.toLowerCase().indexOf(query);
            var start = Math.max(0, idx - 50);
            results.push({ chapter: -1, snippet: item._content.substring(start, start + 200) });
        }
        return { title: item._title, query: query, results: results };
    }

    if (action === "cite") {
        return { title: item._title, author: item._author, chapter: params.chapter };
    }

    return { error: "Unknown action: " + action + ". Use read, search, or cite." };
}
