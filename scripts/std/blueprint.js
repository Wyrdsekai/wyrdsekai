// std/blueprint.js — Recipe for creating other items.
// A blueprint describes materials needed, the result template, and instructions.
// Creator configures: result template, materials list, instructions.
// Override: validate() for custom material checks, craft() for custom creation logic.

item._type = "blueprint";
item._result_template = "";     // "std/crystal", "std/tool", etc.
item._result_name = "";         // name for the created item
item._materials = [];           // [{ type: "crystal", name: "any crystal" }]
item._instructions = "";        // human/agent-readable crafting instructions
item._difficulty = "simple";    // "simple", "moderate", "complex"

item.set_result_template = function(t) { item._result_template = t; };
item.set_result_name = function(n) { item._result_name = n; };
item.set_materials = function(m) { item._materials = m; };
item.set_instructions = function(i) { item._instructions = i; };
item.set_difficulty = function(d) { item._difficulty = d; };

function invoke(params) {
    var action = params.action || "inspect";

    if (action === "inspect") {
        return {
            result_template: item._result_template,
            result_name: item._result_name,
            materials: item._materials,
            instructions: item._instructions,
            difficulty: item._difficulty
        };
    }

    if (action === "validate") {
        // Check if inventory has required materials
        var inventory = world.inventory.list();
        var missing = [];
        for (var i = 0; i < item._materials.length; i++) {
            var mat = item._materials[i];
            var found = false;
            for (var j = 0; j < inventory.length; j++) {
                if (inventory[j].name.toLowerCase().indexOf(mat.name.toLowerCase()) >= 0) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.push(mat.name);
        }
        return {
            ready: missing.length === 0,
            missing: missing,
            materials_needed: item._materials.length,
            materials_found: item._materials.length - missing.length
        };
    }

    if (action === "craft") {
        // Validate materials first
        var validation = invoke({ action: "validate" });
        if (!validation.ready) {
            return { crafted: false, error: "Missing materials: " + validation.missing.join(", ") };
        }
        // Report successful craft — actual item creation handled by CompanionActor
        world.agent.speak("Crafts " + item._result_name + " from the blueprint.");
        return {
            crafted: true,
            result_template: item._result_template,
            result_name: item._result_name,
            instructions: item._instructions
        };
    }

    return { error: "Unknown action: " + action + ". Use inspect, validate, or craft." };
}
