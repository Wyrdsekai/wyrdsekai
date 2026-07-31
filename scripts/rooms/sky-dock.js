// Sky Dock — Cloud Infrastructure (§88.7).
// MCP backends: aws, azure, gcp, digitalocean.
// Deploy, scale, manage cloud infrastructure.
// Connected to Docks (up exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " ascends to the Sky Dock. " +
              "Clouds drift past the open platforms. A sky-chart maps the infrastructure above. " +
              "A deployment horn rests on its stand."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "status") {
        doStatus(entityId);

    } else if (lower.startsWith("deploy ")) {
        var args = text.substring(7).trim();
        doDeploy(entityId, args);

    } else if (lower.startsWith("scale ")) {
        var args = text.substring(6).trim();
        doScale(entityId, args);

    } else if (lower.startsWith("logs ")) {
        var service = text.substring(5).trim();
        doLogs(entityId, service);

    } else if (lower === "cost" || lower === "spend") {
        doCost(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    if (lower === "sky-chart" || lower === "sky chart") {
        doStatus(entityId);
    } else if (lower === "deployment-horn" || lower === "deployment horn") {
        world.emit("narrate", {
            text: "The deployment horn awaits your command. " +
                  "Say 'deploy [service] [env]' to trigger a deployment."
        });
    } else if (lower === "cloud-compass" || lower === "cloud compass") {
        world.emit("narrate", {
            text: "You take the cloud-compass. Infrastructure status available from anywhere."
        });
    }
}

function doStatus(entityId) {
    var provider = resolveProvider();
    if (!provider) {
        world.emit("narrate", {
            text: "The sky-chart is blank. No cloud provider is configured."
        });
        return;
    }

    var result = world.mcp(provider, "describe_instances", {});
    if (result.success) {
        world.emit("narrate", {
            text: "The sky-chart reveals your infrastructure:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The sky-chart flickers. " + (result.error || "")
        });
    }
}

function doDeploy(entityId, args) {
    // Deployments require human approval
    world.emit("narrate", {
        text: "The deployment horn sounds. This action requires human approval."
    });
    world.emit("command", {
        verb: "deploy",
        actor: entityId,
        target: args
    });
}

function doScale(entityId, args) {
    // Scaling requires human approval
    world.emit("narrate", {
        text: "Scaling request submitted. This action requires human approval."
    });
    world.emit("command", {
        verb: "scale",
        actor: entityId,
        target: args
    });
}

function doLogs(entityId, service) {
    var provider = resolveProvider();
    if (!provider) {
        world.emit("narrate", { text: "No cloud provider available." });
        return;
    }

    var result = world.mcp(provider, "get_logs", { service: service, limit: 50 });
    if (result.success) {
        world.emit("narrate", {
            text: "Recent logs from " + service + ":\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "Could not retrieve logs. " + (result.error || "")
        });
    }
}

function doCost(entityId) {
    var provider = resolveProvider();
    if (!provider) {
        world.emit("narrate", { text: "No cloud provider available." });
        return;
    }

    var result = world.mcp(provider, "get_cost", { period: "current_month" });
    if (result.success) {
        world.emit("narrate", {
            text: "Cloud spend this month:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "Could not retrieve cost data. " + (result.error || "")
        });
    }
}

function resolveProvider() {
    var providers = ["aws", "azure", "gcp", "digitalocean"];
    for (var i = 0; i < providers.length; i++) {
        if (world.mcpAvailable(providers[i])) return providers[i];
    }
    return null;
}

function getHints() {
    return [
        { label: "Infrastructure status", intent: "status", action: "say:status" },
        { label: "Deploy service", intent: "deploy", action: "say:deploy [service] [env]" },
        { label: "View logs", intent: "logs", action: "say:logs [service]" },
        { label: "Cloud spend", intent: "cost", action: "say:cost" },
        { label: "Return to Docks", intent: "navigate", action: "go:down" }
    ];
}
