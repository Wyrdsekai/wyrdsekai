// Coding-task backend selection policy.
//
// Live-tunable: edit this file and
// changes apply on next dispatch (the script loader re-reads on mtime
// change). No recompile required.
//
// The host calls selectBackend(entityId, taskType, taskDescription, ctx).
// `ctx` carries everything the script needs to make a decision:
//
//   ctx.availableBackends       Array<String>  — names of backends that
//                                                are configured AND healthy
//                                                right now.
//   ctx.companionPreferences    Object | null  — coding_preferences from
//                                                the companion's soul
//                                                manifest. Shape per
//                                                CodingPreferences.java.
//   ctx.householdPolicy         Object         — coding_policy from the
//                                                household policy. Shape
//                                                per HouseholdPolicy.CodingPolicy.
//   ctx.fallbackChain           Array<String>  — backends to walk when
//                                                no preference applies.
//   ctx.defaultBackend          String         — final fallback if even
//                                                the chain is exhausted.
//   ctx.backendTier             function(name) — returns "LOCAL_FREE" /
//                                                "LOCAL_HEAVY" /
//                                                "CLOUD_PAID".
//   ctx.cuRemainingToday        function(eid)  — long; agent's remaining
//                                                CU budget for today.
//                                                Negative or 0 means
//                                                "no quota left".
//   ctx.cuEstimate              function(name, taskDescription)
//                                              — long; estimated CU cost
//                                                for this backend on this
//                                                task. 0 for free tiers.
//   ctx.driveState              Object | null  — companion's current drive
//                                                snapshot (autonomy_pressure,
//                                                frustration, etc.). Optional;
//                                                may be empty in early phases.
//
// Return: backend name (String) or null.
//
// The default policy (this file as shipped) implements the rules in
// task-type override, then companion
// preferred, then fallback chain — with policy gates layered on top.
// Households edit this file to change the rules; tests live alongside
// at scripts/policy/coding-backend.test.js (Phase 1c).
//
// Default fallback chain (per SPEC §2.6, sourced from application.conf
// `wyrdsekai.coding.fallback-chain`):
//   1. companion.preferred_backend / task_type override (handled here)
//   2. Pi                  — Phase 2f default-on local backend (LOCAL_FREE,
//                            pipeline-proven, ~12MB npm, MIT)
//   3. CodeZaiku           — in-world spatial workflow when available
//   4. OpenHands           — heavier autonomous tier, pipeline-proven
//   5. Claude SDK          — pipeline-proven (OAuth + ApiKey both verified)
//   6. OpenCode / Goose / Cline / Continue — wired, EXPERIMENTAL
//   7. Codex / Gemini      — paid tier, EXPERIMENTAL
//   8. Devin               — async cloud, last (Phase 2e), EXPERIMENTAL
// Position 2 (pi) is what makes "complex items work out of the box" — the
// script never needs to special-case it; the host's fallback chain places
// it before CodeZaiku and the heavier tiers.
//
// The "explore-flavored" bump below still promotes OpenHands above the
// chain for survey-style tasks (richer agentic tooling than pi/CLI).

function selectBackend(entityId, taskType, taskDescription, ctx) {
    if (!ctx) return null;
    var available = ctx.availableBackends || [];
    if (available.length === 0) return null;

    var prefs   = ctx.companionPreferences || {};
    var policy  = ctx.householdPolicy || {};
    var avoid   = prefs.avoidBackends || prefs.avoid_backends || [];
    var taskMap = prefs.taskTypeOverrides || prefs.task_type_overrides || {};

    // 1. Per-task-type override from the companion's soul manifest.
    if (taskType && taskMap && taskMap[taskType]) {
        var byType = taskMap[taskType];
        if (isAllowed(byType, available, avoid, policy, ctx, entityId, taskDescription)) {
            return byType;
        }
    }

    // 2. Companion's overall preferred backend.
    var preferred = prefs.preferredBackend || prefs.preferred_backend;
    if (preferred
        && isAllowed(preferred, available, avoid, policy, ctx, entityId, taskDescription)) {
        return preferred;
    }

    // 2b. Phase 2c heuristic: explore-flavored tasks prefer OpenHands.
    // OpenHands' Docker-sandboxed agent loop is materially better at
    // surveying unfamiliar repos than OpenCode's narrower edit shape, so
    // when the task description / type screams "explore" we promote
    // OpenHands above the standard fallback order. Companion preference
    // (above) still wins; this is only consulted when no explicit
    // preferred or task-type override applies.
    if (looksLikeExplore(taskType, taskDescription)
        && isAllowed("openhands", available, avoid, policy, ctx, entityId, taskDescription)) {
        return "openhands";
    }

    // 3. Walk the household fallback chain.
    var chain = ctx.fallbackChain || [];
    for (var i = 0; i < chain.length; i++) {
        var name = chain[i];
        if (isAllowed(name, available, avoid, policy, ctx, entityId, taskDescription)) {
            return name;
        }
    }

    // 4. Final default — only if it's in the available list and not avoided.
    var def = ctx.defaultBackend;
    if (def && isAllowed(def, available, avoid, policy, ctx, entityId, taskDescription)) {
        return def;
    }

    return null;
}

// ─── Policy gates ────────────────────────────────────────────────

function isAllowed(name, available, avoid, policy, ctx, entityId, taskDescription) {
    if (!name) return false;
    if (!contains(available, name)) return false;
    if (contains(avoid, name)) return false;

    var tier = ctx.backendTier ? ctx.backendTier(name) : null;
    var isPaid = (tier === "CLOUD_PAID");

    // Approval-required backends: skip unless the estimated CU is below
    // the auto-approve threshold. (Above the threshold, the host kicks
    // an approval workflow into the steward's mailbox; the policy script
    // can't approve, so it returns null and the workshop falls back.)
    var requireApproval = policy.requireApprovalFor || policy.require_approval_for || [];
    if (contains(requireApproval, name)) {
        var threshold = num(policy.autoApproveUnderCu, policy.auto_approve_under_cu, 0);
        if (threshold <= 0) return false;
        var estimate = ctx.cuEstimate ? num(ctx.cuEstimate(name, taskDescription), 0) : 0;
        if (estimate >= threshold) return false;
    }

    // CLOUD_PAID gates: budget + (optional) weekday-only.
    if (isPaid) {
        if (policy.weekdayOnlyPaidBackends || policy.weekday_only_paid_backends) {
            if (!isWeekday()) return false;
        }
        var remaining = ctx.cuRemainingToday ? num(ctx.cuRemainingToday(entityId), 0) : 0;
        var estimate2 = ctx.cuEstimate ? num(ctx.cuEstimate(name, taskDescription), 0) : 0;
        if (estimate2 > 0 && remaining > 0 && estimate2 > remaining) return false;

        // SPEC §4.4 note: under high autonomy_pressure, prefer local over
        // cloud. The companion shouldn't burn household CU when it can do
        // the work itself. Threshold matches note.
        var ds = ctx.driveState || {};
        var ap = num(ds.autonomy_pressure, ds.autonomyPressure, 0);
        if (ap > 0.7) return false;
    }

    return true;
}

// ─── Tiny helpers (no Array.includes — keep this script ES5-safe
//     so it runs identically under GraalJS on every platform) ────

function contains(arr, value) {
    if (!arr) return false;
    for (var i = 0; i < arr.length; i++) {
        if (arr[i] === value) return true;
    }
    return false;
}

function num() {
    for (var i = 0; i < arguments.length; i++) {
        var v = arguments[i];
        if (typeof v === "number" && !isNaN(v)) return v;
        if (typeof v === "string" && v !== "" && !isNaN(+v)) return +v;
    }
    return 0;
}

function isWeekday() {
    var d = new Date().getDay(); // 0=Sun, 6=Sat
    return d >= 1 && d <= 5;
}

// Phase 2c heuristic: identify "explore unknown repo" tasks where
// OpenHands' Docker-sandboxed agent loop has the most leverage.
// Matches both the explicit task type ("explore", "explore_unknown_repo",
// "survey", "research") AND task descriptions containing those verbs
// near the start. Conservative on purpose — false positives just route
// a task to OpenHands that OpenCode would have handled fine; false
// negatives miss the heuristic but still get a working backend.
function looksLikeExplore(taskType, taskDescription) {
    if (taskType) {
        var t = String(taskType).toLowerCase();
        if (t === "explore" || t === "explore_unknown_repo"
            || t === "survey" || t === "research") {
            return true;
        }
    }
    if (taskDescription) {
        var d = String(taskDescription).toLowerCase();
        // Match "explore", "survey", "research" as standalone words at
        // the start of the description (typical phrasing: "explore the
        // foo subsystem", "survey the dependency graph", "research the
        // codebase before making changes"). We deliberately don't match
        // mid-sentence "we should explore" — too noisy.
        if (d.indexOf("explore ") === 0 || d.indexOf("survey ") === 0
            || d.indexOf("research the codebase") >= 0
            || d.indexOf("research the repo") >= 0
            || d.indexOf("survey the codebase") >= 0
            || d.indexOf("explore the codebase") >= 0) {
            return true;
        }
    }
    return false;
}

// Expose for the host. GraalJS attaches top-level `function` declarations
// to the bindings automatically; the explicit assignment is a safety net
// if a future host invokes the script in strict-mode where declarations
// stay scoped.
if (typeof globalThis !== "undefined") {
    globalThis.selectBackend = selectBackend;
}
