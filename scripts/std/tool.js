// std/tool.js — Executable tool base type.
// A tool performs actions via world.* API calls. Equippable, budget-managed.
// Creator configures: name, description, and overrides invoke() with custom behavior.
// This base provides a no-op invoke that returns usage instructions.
// Override: invoke() — this is the ONLY method most tool creators need to override.

item._type = "tool";
item._name = "tool";
item._description = "A tool that does something";
item._usage = "";

item.set_name = function(n) { item._name = n; };
item.set_description = function(d) { item._description = d; };
item.set_usage = function(u) { item._usage = u; };

function invoke(params) {
    // Base tool does nothing — creators MUST override invoke()
    return {
        error: "This tool has no custom behavior defined. Override invoke() in your script.",
        name: item._name,
        description: item._description,
        usage: item._usage
    };
}
