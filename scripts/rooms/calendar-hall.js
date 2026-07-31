// The Calendar Hall — Scheduling and family events room.
// Where household members manage schedules, events, and reminders.
// State persisted via world.setProperty/getProperty (survives script re-evaluation).

function loadEvents() {
    var raw = world.getProperty("calendar.events");
    return raw ? JSON.parse(raw) : [];
}

function saveEvents(events) {
    world.setProperty("calendar.events", JSON.stringify(events));
}

function loadReminders() {
    var raw = world.getProperty("calendar.reminders");
    return raw ? JSON.parse(raw) : [];
}

function saveReminders(reminders) {
    world.setProperty("calendar.reminders", JSON.stringify(reminders));
}

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("calendar-hall.enter", entityName)
    });
    // Show today's schedule on entry
    var events = loadEvents();
    if (events.length === 0) {
        world.emit("narrate", {
            text: world.t("calendar-hall.enter.empty")
        });
    } else {
        world.emit("narrate", {
            text: world.t("calendar-hall.enter.count", events.length)
        });
    }
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("schedule ")) {
        var eventDesc = text.substring(9).trim();
        if (eventDesc.length === 0) {
            world.emit("narrate", {
                text: world.t("calendar-hall.say.schedule_usage")
            });
            return;
        }
        var events = loadEvents();
        events.push({ description: eventDesc, addedBy: entityName, day: "today" });
        saveEvents(events);
        world.log("Event scheduled by " + entityName + ": " + eventDesc);
        world.emit("narrate", {
            text: world.t("calendar-hall.say.scheduled", entityName, eventDesc)
        });
    }

    if (lower === "today") {
        var events = loadEvents();
        var todayEvents = events.filter(function(e) { return e.day === "today"; });
        if (todayEvents.length === 0) {
            world.emit("narrate", {
                text: world.t("calendar-hall.say.today_empty")
            });
        } else {
            var listing = todayEvents.map(function(e, i) {
                return (i + 1) + ". " + e.description + " (added by " + e.addedBy + ")";
            }).join("\n");
            world.emit("narrate", {
                text: world.t("calendar-hall.say.today", listing)
            });
        }
    }

    if (lower === "week") {
        var events = loadEvents();
        if (events.length === 0) {
            world.emit("narrate", {
                text: world.t("calendar-hall.say.week_empty")
            });
        } else {
            var listing = events.map(function(e, i) {
                return (i + 1) + ". [" + e.day + "] " + e.description + " (" + e.addedBy + ")";
            }).join("\n");
            world.emit("narrate", {
                text: world.t("calendar-hall.say.week", listing)
            });
        }
    }

    if (lower.startsWith("remind ")) {
        // Format: remind <who> <what>
        var parts = text.substring(7).trim().split(/\s+/, 2);
        if (parts.length < 2) {
            world.emit("narrate", {
                text: world.t("calendar-hall.say.remind_usage")
            });
            return;
        }
        var who = parts[0];
        var what = parts[1];
        var reminders = loadReminders();
        reminders.push({ who: who, what: what, setBy: entityName });
        saveReminders(reminders);
        world.log("Reminder set by " + entityName + " for " + who + ": " + what);
        world.emit("narrate", {
            text: world.t("calendar-hall.say.reminded", entityName, who, what)
        });
    }
}

function getHints() {
    return [
        { label: world.t("calendar-hall.hint.today"), intent: "view_today", action: "say:today" },
        { label: world.t("calendar-hall.hint.week"), intent: "view_week", action: "say:week" },
        { label: world.t("calendar-hall.hint.schedule"), intent: "schedule_event", action: "say:schedule " },
        { label: world.t("calendar-hall.hint.remind"), intent: "set_reminder", action: "say:remind " }
    ];
}
