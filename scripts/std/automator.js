// std/automator.js — Trigger-based event watcher.
// Watches for conditions and acts when triggered. Event-driven automation.
// Creator configures: condition (keyword, schedule, pattern), action to take.
// Override: evaluate() for custom condition checking, act() for custom actions.

item._type = "automator";
item._condition = "";           // what triggers this automator
item._condition_type = "keyword"; // "keyword", "schedule", "pattern"
item._action = "notify";        // "notify", "speak", "tell", "custom"
item._action_target = "";       // who/what to notify
item._action_message = "";      // what to say/send
item._enabled = true;
item._trigger_count = 0;

item.set_condition = function(c) { item._condition = c; };
item.set_condition_type = function(t) { item._condition_type = t; };
item.set_action = function(a) { item._action = a; };
item.set_action_target = function(t) { item._action_target = t; };
item.set_action_message = function(m) { item._action_message = m; };

function invoke(params) {
    var action = params.action || "status";

    if (action === "status") {
        return {
            condition: item._condition,
            condition_type: item._condition_type,
            action: item._action,
            target: item._action_target,
            enabled: item._enabled,
            trigger_count: item._trigger_count
        };
    }

    if (action === "enable") {
        item._enabled = true;
        return { enabled: true };
    }

    if (action === "disable") {
        item._enabled = false;
        return { enabled: false };
    }

    if (action === "evaluate") {
        // Check if condition is met given input text
        if (!item._enabled) return { triggered: false, reason: "disabled" };
        var text = (params.text || "").toLowerCase();
        var condition = item._condition.toLowerCase();

        if (item._condition_type === "keyword") {
            if (text.indexOf(condition) >= 0) {
                item._trigger_count++;
                return _doAction(text);
            }
            return { triggered: false };
        }

        if (item._condition_type === "pattern") {
            // Use LLM to evaluate if text matches pattern
            var match = world.llm.analyze(text,
                "Does this text match the pattern: '" + item._condition +
                "'? Answer only 'yes' or 'no'.");
            if (match && match.toLowerCase().indexOf("yes") >= 0) {
                item._trigger_count++;
                return _doAction(text);
            }
            return { triggered: false };
        }

        return { triggered: false, reason: "Unknown condition type" };
    }

    if (action === "test") {
        // Test the action without a real trigger
        return _doAction("test trigger");
    }

    return { error: "Unknown action: " + action + ". Use status, enable, disable, evaluate, or test." };
}

function _doAction(triggerText) {
    var message = item._action_message || ("Triggered by: " + triggerText);

    if (item._action === "speak") {
        world.agent.speak(message);
        return { triggered: true, action: "speak", message: message };
    }

    if (item._action === "tell") {
        world.agent.tell(item._action_target, message);
        return { triggered: true, action: "tell", target: item._action_target, message: message };
    }

    if (item._action === "notify") {
        world.agent.speak("[Alert] " + message);
        return { triggered: true, action: "notify", message: message };
    }

    // Custom action — report the trigger for CompanionActor to handle
    return { triggered: true, action: "custom", message: message, trigger: triggerText };
}
