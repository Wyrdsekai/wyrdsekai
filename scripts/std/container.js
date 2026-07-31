// std/container.js — Holds other items.
// Supports put, take, list operations. Configurable capacity and lock.
// Creator configures: capacity, locked state, label.
// Override: put() for validation, take() for permissions.

item._type = "container";
item._capacity = 10;
item._locked = false;
item._lock_key = "";
item._items = [];
item._label = "container";

item.set_capacity = function(c) { item._capacity = c; };
item.set_locked = function(locked, key) { item._locked = locked; item._lock_key = key || ""; };
item.set_label = function(l) { item._label = l; };

function invoke(params) {
    var action = params.action || "list";

    if (action === "list") {
        return {
            label: item._label,
            count: item._items.length,
            capacity: item._capacity,
            locked: item._locked,
            items: item._items.map(function(i) { return { name: i.name, id: i.id }; })
        };
    }

    if (action === "put") {
        if (item._locked) return { error: "Container is locked" };
        if (item._items.length >= item._capacity) return { error: "Container is full" };
        var entry = { id: params.item_id || ("item-" + item._items.length), name: params.item_name || "unknown" };
        item._items.push(entry);
        return { stored: true, item: entry.name, count: item._items.length };
    }

    if (action === "take") {
        if (item._locked) return { error: "Container is locked" };
        var target = params.item_name || params.item_id;
        for (var i = 0; i < item._items.length; i++) {
            if (item._items[i].name === target || item._items[i].id === target) {
                var taken = item._items.splice(i, 1)[0];
                return { taken: true, item: taken.name, remaining: item._items.length };
            }
        }
        return { error: "Item not found: " + target };
    }

    if (action === "lock") {
        item._locked = true;
        if (params.key) item._lock_key = params.key;
        return { locked: true };
    }

    if (action === "unlock") {
        if (item._lock_key && params.key !== item._lock_key) return { error: "Wrong key" };
        item._locked = false;
        return { unlocked: true };
    }

    return { error: "Unknown action: " + action + ". Use list, put, take, lock, or unlock." };
}
