// PR notifier — subscribes to a GitHub-style webhook and journals new pull
// requests as they arrive. Demonstrates the Phase T (§4.34) inbound listener
// pattern: a long-lived subscription owned by the item, with delivery via the
// onWebhook hook.
//
// Usage:
//   1. Equip the item.
//   2. Tell it to set up: invoke({action: "subscribe"}).
//      → returns { url, subscriptionId } — paste the URL into the GitHub repo
//        settings → Webhooks → "Push events" / "Pull requests" / etc.
//   3. The runtime will call onWebhook(event) for every payload that hits
//      /api/webhook/{subscriptionId}.

exports.manifest = {
    name: "pr_notifier",
    version: "0.1.0",
    description: "Subscribes to a webhook and journals incoming GitHub PR events.",
    author: "did:wyrd:bundled",
    capabilities: [
        "inbound.webhook",   // Tier 5 — creates a webhook subscription
        "inbound.list",      // Tier 1 — see active subscriptions
        "inbound.cancel",    // Tier 4 — drop a subscription
        "journal.write"      // Tier 2 — write the events into the journal
    ],
    embodiment: {
      silent: true,
      reason: "background GitHub webhook listener, no in-room body"
    },
    rate_limits: {
        "inbound.webhook": "5/hour"
    },
    data_sensitivity: "bonded",
    // Items-as-tools contract — invoke() reads params.action
    // ("subscribe"/"cancel"/default "list"), not the args string; only the
    // no-arg default (list) is reachable from a menu args string.
    commands: [
        { label: "List active webhook subscriptions", args: "" }
    ],
  // Optional: `action` defaults to "list", which is the safe read-only view.
  params: [
    { name: "action", type: "string", required: false,
      description: "What to do: \"list\" (default, show subscriptions), \"subscribe\", or \"unsubscribe\"." },
    { name: "path", type: "string", required: false,
      description: "Repository path to subscribe to." },
    { name: "subscriptionId", type: "string", required: false,
      description: "Which subscription to remove, when unsubscribing." }
  ]
};

function invoke(params) {
    const action = params && params.action ? params.action : "list";
    if (action === "subscribe") {
        const path = params && params.path ? params.path : "/github-prs";
        const result = world.inbound.webhook(path, "onWebhook");
        return {
            ok: true,
            subscriptionId: result.subscriptionId,
            url: result.url,
            note: "POST GitHub webhooks here. Set Content-Type: application/json. " +
                  "Use the returned secret as the webhook secret on GitHub."
        };
    }
    if (action === "cancel") {
        const id = params.subscriptionId;
        return world.inbound.cancel(id);
    }
    return { ok: true, subscriptions: world.inbound.list() };
}

// onWebhook is called by the runtime each time the runtime endpoint receives a
// validated POST. The event shape:
//   { kind: "webhook", source: "/github-prs", payload: { body, headers, ... },
//     timestamp, correlationId }
function onWebhook(event) {
    const body = event && event.payload && event.payload.body || {};
    const action = body.action || "";
    const pr = body.pull_request || {};
    const number = pr.number;
    const title = pr.title || "(no title)";
    const repoFull = (body.repository && body.repository.full_name) || "(unknown repo)";

    if (number) {
        world.journal.write(
            "[PR " + action + "] " + repoFull + " #" + number + " — " + title,
            { tags: ["pr-notifier", action, repoFull] }
        );
        return { ok: true, action: action, number: number };
    }
    // Non-PR event (push, ping, ...) — record minimally.
    world.journal.write(
        "[webhook] " + repoFull + " — non-PR event " + action,
        { tags: ["pr-notifier", "non-pr"] }
    );
    return { ok: true, ignored: true };
}
