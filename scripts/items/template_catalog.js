// Workshop furnishing — template catalog ("template-catalog" RoomObject in
// the foundation Workshop AND the std workshop room template; display name
// "template catalog" → normalized linkage "template_catalog". One item
// serves both rooms — same purpose, same shimmer).
//
// The shimmering index of what can be made. Read surface is
// world.catalog.* (StandardItemLibrary): search, byCategory, templateInfo.
// Bare `use template catalog` lists the shelf AND the command list.
//
// ROOM templates (hub, garden, market, …) are indexed separately in the
// StandardRoomLibrary — ask your companion ("what can you build?", the
// Template Catalog tool) for those. The catalog says so honestly.
exports.manifest = {
  name: "template_catalog",
  version: "1.0.0",
  description: "A shimmering crystal index of every item template this engine can craft — searchable by word or category.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The catalog brightens under {actor}'s attention, template-cards shimmering into order."
  },
  commands: [
    { label: "Browse the catalog", args: "" },
    { label: "Search templates", args: "search <word>" },
    { label: "Browse a category", args: "category <name>" },
    { label: "Inspect one template", args: "info <template>" },
    { label: "Catalog help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use template catalog                    — browse everything craftable",
    "  use template catalog search <word>      — find templates by word",
    "  use template catalog category <name>    — browse one category",
    "  use template catalog info <template>    — one template, in detail",
    "  use template catalog help               — this help",
    "To actually make something, work the workbench ('craft <name>') or ask your",
    "companion. Room templates live in a separate index — ask your companion",
    "\"what rooms can you build?\" for those."
  ].join("\n");
}

function renderList(templates, heading) {
  if (!templates || templates.length === 0) {
    return "The catalog's shimmer settles into blankness — nothing matches, "
      + "or the item library isn't bound on this surface.";
  }
  var lines = [heading];
  var cap = Math.min(templates.length, 25);
  for (var i = 0; i < cap; i++) {
    var t = templates[i];
    var line = "  " + (t.displayName || t.name || "?");
    if (t.category) line += "  [" + t.category + "]";
    if (t.description) line += " — " + t.description;
    lines.push(line);
  }
  if (templates.length > cap) {
    lines.push("  …and " + (templates.length - cap) + " more. Narrow with 'search <word>'.");
  }
  return lines.join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = args.toLowerCase();

  if (lower === "help" || lower === "?") {
    return { ok: true, text: "The catalog indexes every craftable item template." + usageFooter() };
  }

  try {
    if (lower.indexOf("search ") === 0) {
      var q = args.substring(7).trim();
      var found = world.catalog.search(q);
      return { ok: true, text: renderList(found, "Templates matching '" + q + "':") + usageFooter() };
    }
    if (lower.indexOf("category ") === 0) {
      var cat = args.substring(9).trim();
      var inCat = world.catalog.byCategory(cat);
      return { ok: true, text: renderList(inCat, "Templates in category '" + cat + "':") + usageFooter() };
    }
    if (lower.indexOf("info ") === 0) {
      var name = args.substring(5).trim();
      var info = null;
      try { info = world.catalog.templateInfo(name); } catch (e2) { info = null; }
      if (!info) {
        return { ok: true, text: "No card in the catalog bears the name '" + name + "'." + usageFooter() };
      }
      var lines = ["The card for '" + (info.displayName || name) + "':"];
      if (info.description) lines.push("  " + info.description);
      if (info.category) lines.push("  Category: " + info.category);
      if (info.level) lines.push("  Level:    " + info.level);
      return { ok: true, text: lines.join("\n") + usageFooter(), template: info };
    }
    if (lower === "") {
      var all = world.catalog.search("");
      return { ok: true, text: renderList(all, "The catalog shimmers with craftable templates:") + usageFooter() };
    }
  } catch (e) {
    return {
      ok: true,
      text: "The catalog's shimmer stutters — the item library isn't reachable from "
        + "this surface right now." + usageFooter()
    };
  }
  return { ok: true, text: "The catalog doesn't index '" + args + "' as a command." + usageFooter() };
}
