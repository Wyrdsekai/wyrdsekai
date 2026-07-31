// std/aspect.js — Personality modifier (equipped, max 3).
// Shifts vitality baselines and adds a prompt overlay when equipped.
// Creator configures: name, overlay text, vitality shifts.
// Override: equip()/doff() for custom equip/unequip behavior.

item._type = "aspect";
item._name = "aspect";
item._overlay = "";             // prompt text injected when equipped
item._vitality_shifts = {};     // { "curiosity": 0.1, "patience": 0.05 }
item._appearance = "";          // visual description when wearing

item.set_name = function(n) { item._name = n; };
item.set_overlay = function(text) { item._overlay = text; };
item.set_vitality_shifts = function(shifts) { item._vitality_shifts = shifts; };
item.set_appearance = function(desc) { item._appearance = desc; };

function invoke(params) {
    var action = params.action || "inspect";

    if (action === "inspect") {
        return {
            name: item._name,
            overlay: item._overlay,
            vitality_shifts: item._vitality_shifts,
            appearance: item._appearance
        };
    }

    if (action === "equip") {
        world.agent.speak("Equips " + item._name + ".");
        return {
            equipped: true,
            name: item._name,
            overlay: item._overlay,
            vitality_shifts: item._vitality_shifts
        };
    }

    if (action === "doff") {
        world.agent.speak("Removes " + item._name + ".");
        return { doffed: true, name: item._name };
    }

    return { error: "Unknown action: " + action + ". Use inspect, equip, or doff." };
}
