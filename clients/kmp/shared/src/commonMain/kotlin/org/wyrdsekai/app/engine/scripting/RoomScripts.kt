package org.wyrdsekai.app.engine.scripting

/**
 * Foundation room scripts bundled as Kotlin string constants.
 * Avoids needing a file-system script loader on iOS.
 * Phone-class subset: Study + Nexus + Terminal.
 */
object RoomScripts {

    /**
     * Load Study room translations for a locale from bundled JSON resource files.
     * Files: commonMain/resources/i18n/study_{locale}.json
     * Falls back to English if locale unavailable.
     */
    fun studyTranslations(locale: String = "en"): Map<String, String> =
        StudyI18nLoader.load(locale)

    /**
     * Study room script — the player's home base on phone.
     *
     * Handles journal write/search, note-taking, and use-object interactions.
     * Companion handles free conversation; the script handles structured Study commands.
     * MUD verbs: journal, journal private, journal search, search, note, pin.
     */
    val STUDY_SCRIPT = """
        function onEnter(entityId, entityName, fromDirection) {
            var onboarded = world.getProperty("onboarded");
            if (!onboarded) {
                // First visit — welcome and ask about setup
                world.emit("narrate", {
                    text: world.t("study.onboard.welcome")
                });
                world.emit("narrate", {
                    text: world.t("study.onboard.connect")
                });
                world.setProperty("onboarded", "true");
            } else {
                world.emit("narrate", {
                    text: world.t("study.enter")
                });
            }
        }

        function onSay(entityId, entityName, text) {
            var lower = text.toLowerCase().trim();

            // Check if we're awaiting input (server URL or API key)
            var awaiting = world.getProperty("awaiting_input");
            if (awaiting === "server_url") {
                world.setProperty("awaiting_input", "");
                var url = text.trim();
                if (url.indexOf("http") === 0) {
                    world.emit("study_action", { action: "connect_server", url: url });
                    world.emit("narrate", { text: world.t("study.onboard.connecting") });
                } else {
                    world.emit("narrate", { text: world.t("study.onboard.invalid_url") });
                }
                return;
            }
            if (awaiting === "api_provider") {
                // User typed a provider name directly instead of using hints
                var provider = lower;
                if (provider === "anthropic" || provider === "openai" || provider === "openrouter") {
                    world.setProperty("api_provider", provider);
                    world.setProperty("awaiting_input", "api_key");
                    world.emit("narrate", { text: world.t("study.onboard.api_prompt") });
                } else if (provider === "other" || provider === "custom") {
                    world.setProperty("api_provider", "custom");
                    world.setProperty("awaiting_input", "api_url");
                    world.emit("narrate", { text: world.t("study.onboard.api_url_prompt") });
                } else {
                    world.emit("narrate", { text: world.t("study.onboard.api_provider_prompt") });
                }
                return;
            }
            if (awaiting === "api_url") {
                world.setProperty("awaiting_input", "api_key");
                world.setProperty("api_custom_url", text.trim());
                world.emit("narrate", { text: world.t("study.onboard.api_prompt") });
                return;
            }
            if (awaiting === "api_key") {
                world.setProperty("awaiting_input", "");
                var key = text.trim();
                if (key.length > 10) {
                    var provider = world.getProperty("api_provider") || "openai";
                    var customUrl = world.getProperty("api_custom_url") || "";
                    world.emit("study_action", {
                        action: "connect_api",
                        key: key,
                        provider: provider,
                        customUrl: customUrl
                    });
                    world.emit("narrate", { text: world.t("study.onboard.connecting_api") });
                } else {
                    world.emit("narrate", { text: world.t("study.onboard.invalid_key") });
                }
                return;
            }
            if (awaiting === "companion_name") {
                world.setProperty("awaiting_input", "");
                var name = text.trim();
                if (name.length > 0 && name.length < 30) {
                    world.emit("study_action", { action: "set_companion_name", name: name });
                    world.emit("narrate", { text: world.t("study.onboard.name_set", name) });
                }
                return;
            }

            // Cancel awaiting input
            if (lower === "cancel" && world.getProperty("awaiting_input")) {
                world.setProperty("awaiting_input", "");
                world.emit("narrate", { text: world.t("study.onboard.cancelled") });
                return;
            }

            if (lower === "help" || lower === "/help") {
                world.emit("narrate", { text: world.t("study.say.help") });
                return;
            }

            // Journal write (private)
            if (lower.indexOf("journal private ") === 0) {
                var entry = text.substring("journal private ".length).trim();
                if (entry.length > 0) {
                    world.emit("study_action", {
                        action: "journal_write",
                        content: entry,
                        isPrivate: "true"
                    });
                    world.emit("narrate", { text: world.t("study.journal.private.confirm", entry) });
                }
                return;
            }

            // Journal search
            if (lower.indexOf("journal search ") === 0 || lower.indexOf("search journal ") === 0) {
                var query = lower.indexOf("journal search ") === 0
                    ? text.substring("journal search ".length).trim()
                    : text.substring("search journal ".length).trim();
                if (query.length > 0) {
                    world.emit("study_action", { action: "journal_search", query: query });
                }
                return;
            }

            // Journal write (shared). Match both `journal <text>` and the
            // explicit `journal entry <text>` form. Strip the matching
            // prefix and persist the rest; pass the entry into the i18n
            // confirm string so the user sees what was saved.
            if (lower.indexOf("journal entry ") === 0 || lower.indexOf("journal ") === 0) {
                var prefix = lower.indexOf("journal entry ") === 0 ? "journal entry " : "journal ";
                var entry = text.substring(prefix.length).trim();
                if (entry.length > 0) {
                    world.emit("study_action", {
                        action: "journal_write",
                        content: entry,
                        isPrivate: "false"
                    });
                    world.emit("narrate", { text: world.t("study.journal.shared.confirm", entry) });
                }
                return;
            }

            // Library-style search shortcuts (mirror RN: parallel to server's
            // library_card scripted item). Match these BEFORE the generic
            // search so `search the library for X` doesn't collide with the
            // bare `search X` path.
            var libMatch = text.match(/^search\s+(?:the\s+)?library\s+for\s+(.+)$/i)
                || text.match(/^use\s+library[ _-]?card\s+(.+)$/i)
                || text.match(/^library\s+search\s+(.+)$/i);
            if (libMatch && libMatch[1]) {
                var libQuery = libMatch[1].trim();
                world.emit("study_action", { action: "search", query: libQuery });
                world.emit("narrate", { text: "You consult your library card, searching for: " + libQuery });
                return;
            }

            // General search
            if (lower.indexOf("search ") === 0) {
                var query = text.substring("search ".length).trim();
                if (query.length > 0) {
                    world.emit("study_action", { action: "search", query: query });
                }
                return;
            }

            // Note
            if (lower.indexOf("note ") === 0) {
                var note = text.substring("note ".length).trim();
                if (note.length > 0) {
                    world.emit("study_action", { action: "note", content: note });
                    world.emit("narrate", { text: world.t("study.note.confirm") });
                }
                return;
            }

            // --- Onboarding commands ---
            if (lower === "connect server") {
                world.setProperty("awaiting_input", "server_url");
                world.emit("narrate", { text: world.t("study.onboard.server_prompt") });
                return;
            }

            if (lower === "connect api") {
                world.setProperty("awaiting_input", "api_provider");
                world.emit("narrate", { text: world.t("study.onboard.api_provider_prompt") });
                return;
            }

            // Provider selection shortcuts
            if (lower === "openrouter" && world.getProperty("awaiting_input") === "api_provider") {
                world.setProperty("api_provider", "openrouter");
                world.setProperty("awaiting_input", "");
                world.emit("study_action", { action: "oauth_openrouter" });
                world.emit("narrate", { text: world.t("study.onboard.openrouter_oauth") });
                return;
            }
            if ((lower === "anthropic" || lower === "openai") && world.getProperty("awaiting_input") === "api_provider") {
                world.setProperty("api_provider", lower);
                world.setProperty("awaiting_input", "api_key");
                // Show help link for where to get the key
                var helpKey = "study.onboard." + lower + "_help";
                world.emit("narrate", { text: world.t(helpKey) });
                world.emit("narrate", { text: world.t("study.onboard.api_prompt") });
                return;
            }

            if (lower === "other api" || lower === "custom") {
                var awaiting = world.getProperty("awaiting_input");
                if (awaiting === "api_provider") {
                    world.setProperty("api_provider", "custom");
                    world.setProperty("awaiting_input", "api_url");
                    world.emit("narrate", { text: world.t("study.onboard.api_url_prompt") });
                    return;
                }
            }

            if (lower === "name companion" || lower === "name my companion") {
                world.setProperty("awaiting_input", "companion_name");
                world.emit("narrate", { text: world.t("study.onboard.name_prompt") });
                return;
            }

            if (lower === "just me" || lower === "standalone") {
                world.setProperty("companion_connected", "standalone");
                world.emit("study_action", { action: "onboard_standalone" });
                world.emit("narrate", { text: world.t("study.onboard.standalone_ok") });
                return;
            }

            // Otherwise — companion handles free conversation
        }

        function onUse(entityId, objectName, target) {
            var name = objectName.toLowerCase();
            if (name === "journal") {
                world.emit("study_action", { action: "recent_journal" });
                world.emit("narrate", { text: world.t("study.use.journal.info") });
            } else if (name === "desk") {
                world.emit("narrate", { text: world.t("study.use.desk.info") });
            } else if (name === "shelves") {
                world.emit("narrate", { text: world.t("study.use.shelves.info") });
            } else if (name === "pinboard") {
                world.emit("narrate", { text: world.t("study.use.pinboard.info") });
            }
        }

        function getHints() {
            var hints = [
                { label: world.t("study.hint.journal"), intent: "journal", action: "say:journal ", labelKey: "study.hint.journal" },
                { label: world.t("study.hint.journal_search"), intent: "journal_search", action: "say:journal search ", labelKey: "study.hint.journal_search" },
                { label: world.t("study.hint.use_journal"), intent: "use_journal", action: "use:journal", labelKey: "study.hint.use_journal" },
                { label: world.t("study.hint.help"), intent: "help", action: "say:help", labelKey: "study.hint.help" }
            ];
            // Context-sensitive hints based on onboarding state
            var awaiting = world.getProperty("awaiting_input");
            if (awaiting === "api_provider") {
                return [
                    { label: world.t("study.hint.openrouter"), intent: "openrouter", action: "say:openrouter", labelKey: "study.hint.openrouter" },
                    { label: "Anthropic", intent: "anthropic", action: "say:anthropic" },
                    { label: "OpenAI", intent: "openai", action: "say:openai" },
                    { label: world.t("study.hint.custom_api"), intent: "custom", action: "say:custom", labelKey: "study.hint.custom_api" }
                ];
            }
            if (awaiting === "server_url" || awaiting === "api_key" || awaiting === "api_url" || awaiting === "companion_name") {
                // Waiting for typed input — show no action hints, just a cancel
                return [
                    { label: world.t("study.hint.cancel"), intent: "cancel", action: "say:cancel", labelKey: "study.hint.cancel" }
                ];
            }

            // Onboarding hints — shown until companion arrives
            var connected = world.getProperty("companion_connected");
            if (!connected) {
                hints.unshift(
                    { label: world.t("study.hint.connect_server"), intent: "connect_server", action: "say:connect server", labelKey: "study.hint.connect_server" },
                    { label: world.t("study.hint.connect_api"), intent: "connect_api", action: "say:connect api", labelKey: "study.hint.connect_api" },
                    { label: world.t("study.hint.standalone"), intent: "standalone", action: "say:just me", labelKey: "study.hint.standalone" }
                );
            }
            return hints;
        }
    """.trimIndent()

    val NEXUS_SCRIPT = """
        function onEnter(entityId, entityName, fromDirection) {
            world.emit("narrate", {
                text: "Welcome home, " + entityName + "."
            });
        }

        function onSay(entityId, entityName, text) {
            // Companion handles all conversation
        }

        function onUse(entityId, objectName, target) {
            if (objectName.toLowerCase() === "crystal") {
                world.emit("narrate", {
                    text: "The crystal pulses with a soft light, revealing hidden connections."
                });
            }
        }

        function onTake(entityId, objectName, objectId) {
            world.emit("narrate", {
                text: "You carefully pick up the " + objectName + "."
            });
        }

        function onDrop(entityId, objectName, objectId) {
            world.emit("narrate", {
                text: "You set the " + objectName + " down gently."
            });
        }

        function getHints() {
            var hints = [
                { label: "Talk to Wyrd", intent: "greet", action: "say:Hello" },
                { label: "Examine the crystal", intent: "use_crystal", action: "use:crystal" }
            ];
            var entities = world.getEntities();
            if (entities && entities.length > 1) {
                hints.push({
                    label: "Who is here?",
                    intent: "look_around",
                    action: "say:Who is here?"
                });
            }
            return hints;
        }
    """.trimIndent()

    val TERMINAL_SCRIPT = """
        function onEnter(entityId, entityName, fromDirection) {
            world.emit("narrate", {
                text: entityName + " approaches the terminal. The screen flickers to life."
            });
        }

        function onSay(entityId, entityName, text) {
            var lower = text.toLowerCase().trim();
            if (lower === "help" || lower === "/help") {
                world.emit("narrate", {
                    text: "Available commands: status, help, who"
                });
            } else if (lower === "status" || lower === "/status") {
                world.emit("narrate", {
                    text: "Node status: running. Rooms active: " + (world.getProperty("room_count") || "unknown")
                });
            } else if (lower === "who" || lower === "/who") {
                var entities = world.getEntities();
                var names = [];
                for (var i = 0; i < entities.length; i++) {
                    names.push(entities[i].name);
                }
                world.emit("narrate", {
                    text: "Present: " + names.join(", ")
                });
            }
        }

        function getHints() {
            return [
                { label: "Check status", intent: "status", action: "say:status" },
                { label: "Get help", intent: "help", action: "say:help" },
                { label: "Who is here?", intent: "who", action: "say:who" }
            ];
        }
    """.trimIndent()
}
