// std/consumable.js — Single-use effect item.
// Consuming applies a vitality shift and destroys the item. Stackable.
// Creator configures: effect description, vitality shifts, stack count.
// Override: consume() for custom effects beyond vitality shifts.

item._type = "consumable";
item._effect = "";
item._vitality_shifts = {};   // { "curiosity": 0.2, "energy": 0.1 }
item._duration_minutes = 30;
item._stack = 1;

item.set_effect = function(e) { item._effect = e; };
item.set_vitality_shifts = function(shifts) { item._vitality_shifts = shifts; };
item.set_duration = function(minutes) { item._duration_minutes = minutes; };
item.set_stack = function(count) { item._stack = count; };

function invoke(params) {
    var action = params.action || "consume";

    if (action === "inspect") {
        return {
            effect: item._effect,
            vitality_shifts: item._vitality_shifts,
            duration_minutes: item._duration_minutes,
            stack: item._stack
        };
    }

    if (action === "consume") {
        if (item._stack <= 0) {
            return { error: "No more uses remaining" };
        }
        item._stack--;

        // Report the effect — actual vitality application happens in CompanionActor
        world.agent.speak("Uses " + item._effect + ".");
        return {
            consumed: true,
            effect: item._effect,
            vitality_shifts: item._vitality_shifts,
            duration_minutes: item._duration_minutes,
            remaining: item._stack,
            destroyed: item._stack <= 0
        };
    }

    return { error: "Unknown action: " + action + ". Use consume or inspect." };
}
