// std/key.js — Access control item.
// Grants access to rooms, resources, or capabilities. TTL, revocable, signed.
// Creator configures: target (what it unlocks), scope, ttl.
// Override: check() for custom access validation.

item._type = "key";
item._target = "";              // room ID, resource name, or capability
item._scope = "room";           // "room", "resource", "zone", "capability"
item._ttl_minutes = 0;          // 0 = permanent
item._created_at = new Date().toISOString();
item._revoked = false;

item.set_target = function(t) { item._target = t; };
item.set_scope = function(s) { item._scope = s; };
item.set_ttl = function(minutes) { item._ttl_minutes = minutes; };

function invoke(params) {
    var action = params.action || "check";

    if (action === "check") {
        if (item._revoked) {
            return { valid: false, reason: "Key has been revoked" };
        }
        if (item._ttl_minutes > 0) {
            var created = new Date(item._created_at).getTime();
            var now = new Date().getTime();
            var elapsed = (now - created) / 60000;
            if (elapsed > item._ttl_minutes) {
                return { valid: false, reason: "Key has expired" };
            }
        }
        return {
            valid: true,
            target: item._target,
            scope: item._scope,
            ttl_minutes: item._ttl_minutes
        };
    }

    if (action === "revoke") {
        item._revoked = true;
        return { revoked: true, target: item._target };
    }

    if (action === "inspect") {
        return {
            target: item._target,
            scope: item._scope,
            ttl_minutes: item._ttl_minutes,
            revoked: item._revoked,
            created: item._created_at
        };
    }

    return { error: "Unknown action: " + action + ". Use check, revoke, or inspect." };
}
