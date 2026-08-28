// The Study — your private quarters and default landing room.
// Objects: desk (schedule/correspondence), dashboard crystal (system status),
//   journal (private/shared entries), shelves (mounts + private library),
//   pinboard (Library bookmarks), chair, wardrobe.
// Skills: filesystem, calendar, document extraction, knowledge search.

// Load user study extensions if available
var _extensions = null;
function loadExtensions() {
    if (_extensions !== null) return _extensions;
    try {
        var extPath = world.getProperty("extensions_path") || "~/.wyrdsekai/study-extensions.js";
        var extSource = world.mcp("skill", "study.fs.read", { path: extPath });
        if (extSource && extSource.success && extSource.data) {
            _extensions = { loaded: true, source: extSource.data };
            world.log("Study extensions loaded from " + extPath);
        } else {
            _extensions = { loaded: false };
        }
    } catch (e) {
        _extensions = { loaded: false };
        world.log("Study extensions not available: " + e);
    }
    return _extensions;
}

// Mounts: the AUTHORITATIVE table lives host-side (StudyMountRegistry via the
// "skill" MCP service) — that is what ls/cat/grep actually resolve against.
// The room property is a display cache kept for when the service is absent
// (e.g. bare script-engine tests). GraalJS can't pass objects to Java, so the
// cache is a JSON string.
function getMounts() {
    try {
        var res = world.mcp("skill", "study.fs.mounts", {});
        if (res && res.success && res.data) return JSON.parse(res.data);
    } catch (e) { /* host table unavailable — fall back to cached copy */ }
    var raw = world.getProperty("mounts");
    if (!raw) return {};
    try { return JSON.parse(raw); } catch (e) { return {}; }
}
function setMounts(m) { world.setProperty("mounts", JSON.stringify(m)); }

function onEnter(entityId, entityName, fromDirection) {
    var mounts = getMounts();
    var mountCount = Object.keys(mounts).length;
    var ageBracket = world.getProperty("age_bracket");

    var onboarded = world.getProperty("onboarded");

    if (!onboarded) {
        // First visit — welcome and ask about setup
        world.emit("narrate", { text: world.t("study.enter.welcome", entityName) });
        world.setProperty("onboarded", "true");
    } else if (ageBracket && ageBracket !== "tree") {
        // Child variant — themed entrance
        world.emit("narrate", {
            text: world.t("study.child." + ageBracket + ".enter", entityName)
        });
        applyChildTheme(ageBracket);
    } else {
        world.emit("narrate", {
            text: world.t("study.enter.return")
        });
    }

    // Show Oracle predictions if available
    var predictionsRaw = world.getProperty("oracle_predictions");
    if (predictionsRaw) {
        try {
            var predictions = JSON.parse(predictionsRaw);
            if (predictions.length > 0) {
                world.emit("narrate", {
                    text: world.t("study.oracle.has_insights", predictions.length)
                });
                // Show top 3 predictions
                var top = predictions.slice(0, 3);
                for (var i = 0; i < top.length; i++) {
                    world.emit("narrate", { text: "  " + top[i].text });
                }
            }
        } catch (e) { /* ignore parse errors */ }
    }
}

function applyChildTheme(bracket) {
    var themes = {
        seedling: {
            objects: [
                { id: "toy-chest", name: "Toy Chest", desc: "A colorful chest overflowing with toys." },
                { id: "coloring-table", name: "Coloring Table", desc: "A low table with crayons and paper." },
                { id: "picture-books", name: "Picture Books", desc: "A pile of bright picture books." }
            ]
        },
        sprout: {
            objects: [
                { id: "adventure-books", name: "Adventure Books", desc: "A shelf of exciting stories." },
                { id: "craft-table", name: "Craft Table", desc: "A table covered in glue, scissors, and glitter." },
                { id: "puzzle-box", name: "Puzzle Box", desc: "A wooden box full of puzzles." }
            ]
        },
        sapling: {
            objects: [
                { id: "journal", name: "Journal", desc: "A leather-bound journal with a lock." },
                { id: "toolbench", name: "Tool Bench", desc: "A workbench with tools and parts." },
                { id: "bookshelf", name: "Bookshelf", desc: "Shelves packed with books on every subject." }
            ]
        },
        "young-tree": {
            objects: [
                { id: "desk", name: "Desk", desc: "A proper desk with drawers and a lamp." },
                { id: "computer", name: "Computer", desc: "A screen glows softly on the desk." },
                { id: "bookshelf", name: "Bookshelf", desc: "Shelves of reference books and novels." },
                { id: "corkboard", name: "Cork Board", desc: "A board covered in notes and photos." }
            ]
        }
    };

    var theme = themes[bracket];
    if (theme) {
        for (var i = 0; i < theme.objects.length; i++) {
            var obj = theme.objects[i];
            world.createObject(obj.id, obj.name, obj.desc, false);
        }
    }
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", {
            text: world.t("study.say.help") + world.t("study.library.help")
        });

    } else if (lower.startsWith("open ")) {
        var app = text.substring(5).trim();
        doOpenApp(entityId, entityName, app);

    } else if (lower.startsWith("read ")) {
        var file = text.substring(5).trim();
        doReadFile(entityId, entityName, file);

    } else if (lower.startsWith("browse ")) {
        var url = text.substring(7).trim();
        doBrowseUrl(entityId, entityName, url);

    } else if (lower === "schedule" || lower === "schedule board") {
        doSchedule(entityId);

    } else if (lower.startsWith("mount ")) {
        doMount(entityId, text.substring(6).trim());

    } else if (lower.startsWith("unmount ")) {
        doUnmount(entityId, text.substring(8).trim());

    } else if (lower === "mounts" || lower === "shelves") {
        doListMounts(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });

    } else if (lower.startsWith("search ")) {
        var query = text.substring(7).trim();
        if (query) {
            var results = world.searchKnowledge(query);
            world.emit("narrate", { text: results });
        }

    } else if (lower.startsWith("journal private ")) {
        var entry = text.substring(16).trim();
        if (entry) {
            var result = world.writePrivateJournalEntry(entry);
            world.emit("narrate", {
                text: world.t("study.journal.private.confirm", result)
            });
        }

    } else if (lower.startsWith("journal ")) {
        var entry = text.substring(8).trim();
        if (entry) {
            var result = world.writeJournalEntry(entry);
            world.emit("narrate", {
                text: world.t("study.journal.shared.confirm", result)
            });
        }

    } else if (lower.startsWith("journal search ") || lower.startsWith("search journal ")) {
        var query = lower.startsWith("journal search ")
            ? text.substring(15).trim() : text.substring(15).trim();
        if (query) {
            var results = world.searchJournal(query);
            world.emit("narrate", { text: results });
        }

    } else if (lower === "predictions" || lower === "oracle") {
        var predictionsRaw = world.getProperty("oracle_predictions");
        if (predictionsRaw) {
            try {
                var predictions = JSON.parse(predictionsRaw);
                if (predictions.length > 0) {
                    world.emit("narrate", {
                        text: world.t("study.oracle.has_insights", predictions.length)
                    });
                    for (var i = 0; i < predictions.length; i++) {
                        var p = predictions[i];
                        var icon = {
                            "pattern": "~", "anomaly": "!", "forecast": ">",
                            "correlation": "<>", "topic": "#", "sequence": "->",
                            "recommendation": "*", "anticipation": "**"
                        }[p.category] || "?";
                        world.emit("narrate", { text: "  " + icon + " " + p.text });
                    }
                } else {
                    world.emit("narrate", { text: world.t("oracle.room.no_predictions") });
                }
            } catch (e) {
                world.emit("narrate", { text: world.t("oracle.room.no_predictions") });
            }
        } else {
            world.emit("narrate", { text: world.t("oracle.room.no_predictions") });
        }

    } else if (lower === "dashboard" || lower === "status") {
        doDashboard(entityId);

    } else if (lower === "pinboard" || lower === "pins") {
        world.emit("narrate", {
            text: world.t("study.pinboard.empty")
        });

    } else if (lower.startsWith("desk:") || lower.startsWith("desk ")) {
        doShellCommand(entityId, entityName, text.substring(text.indexOf(':') + 1).trim() || text.substring(5).trim());

    } else if (lower === "voice" || lower === "voice mirror" || lower === "voice profile" ||
               lower === "look mirror" || lower === "look at mirror" ||
               lower === "look voice" || lower === "look at voice mirror") {
        // #416 — Study voice-mirror furnishing. Shows the steward their
        // companion's voice profile clauses + revision + frozen flag.
        world.emit("narrate", { text: world.formatVoiceProfile() });

    } else if (lower === "voice history" || lower === "voice log") {
        world.emit("narrate", { text: world.formatVoiceHistory() });

    } else if (lower === "voice freeze") {
        world.emit("narrate", { text: world.freezeVoice("in-world freeze") });

    } else if (lower === "voice unfreeze") {
        world.emit("narrate", { text: world.unfreezeVoice("in-world unfreeze") });

    } else if (lower.startsWith("voice revert ")) {
        var revStr = text.substring(13).trim();
        var rev = parseInt(revStr, 10);
        if (isNaN(rev)) {
            world.emit("narrate", { text: "[Provide a numeric revision: voice revert <N>]" });
        } else {
            world.emit("narrate", { text: world.revertVoice(rev) });
        }

    } else if (lower.startsWith("voice unset ")) {
        var unsetKey = text.substring(12).trim();
        if (!unsetKey) {
            world.emit("narrate", { text: "[Usage: voice unset <key>]" });
        } else {
            world.emit("narrate", { text: world.unsetVoiceClause(unsetKey, "in-world unset") });
        }

    } else if (lower.startsWith("voice set ")) {
        // voice set <key> <value...>
        var rest = text.substring(10).trim();
        var spaceIdx = rest.indexOf(' ');
        if (spaceIdx <= 0) {
            world.emit("narrate", { text: "[Usage: voice set <key> <value>]" });
        } else {
            var setKey = rest.substring(0, spaceIdx).trim();
            var setValue = rest.substring(spaceIdx + 1).trim();
            world.emit("narrate", { text: world.setVoiceClause(setKey, setValue, "in-world edit") });
        }

    } else if (lower === "library" || lower === "library status") {
        // same view as `use bookshelf`.
        doLibraryShelf(entityId);

    } else if (lower === "library available") {
        world.emit("narrate", { text: world.listAvailablePacks() });

    } else if (lower.startsWith("library install ")) {
        var libPackName = text.substring(16).trim();
        world.emit("narrate", { text: world.installKnowledgePack(libPackName) });

    } else if (lower === "library proposals") {
        world.emit("narrate", { text: world.listLibraryProposals() });

    } else if (lower.startsWith("library approve ")) {
        var libApproveId = text.substring(16).trim();
        world.emit("narrate", { text: world.approveLibraryProposal(libApproveId, entityName) });

    } else if (lower.startsWith("library reject ")) {
        var libRejectParts = text.substring(15).split("|");
        var libRejectId = libRejectParts[0].trim();
        var libRejectReason = libRejectParts.length >= 2 ? libRejectParts[1].trim() : "Rejected by steward";
        world.emit("narrate", { text: world.rejectLibraryProposal(libRejectId, entityName, libRejectReason) });

    } else if (lower === "library misses" || lower === "library gaps") {
        world.emit("narrate", { text: world.libraryTopMisses() });
    }

    // Extension hook — let user scripts handle unrecognized commands
    var ext = loadExtensions();
    if (ext.loaded && typeof _extensionOnSay === "function") {
        _extensionOnSay(entityId, entityName, text);
    }
}

function onUse(entityId, objectName, target) {
    // objectName is the RESOLVED RoomObject display name (e.g. "heavy desk",
    // "companion bond crystal") — not the short word the player typed. Match
    // full display names first, then short aliases, all by EXACT comparison.
    // Substring matching is forbidden here: it made "companion bond crystal"
    // hijack the dashboard branch and "invitation scroll" / "parental controls
    // scroll" hijack the Scroll of Settings. Objects with no branch (ledgers,
    // key chest, maintenance dial, ...) fall through silently — their
    // furnishing-item mechanism runs BEFORE onUse.
    var name = (objectName || "").toLowerCase().trim();

    if (name === "heavy desk" || name === "desk") {
        doSchedule(entityId);

    } else if (name === "dashboard crystal" || name === "dashboard" || name === "crystal") {
        doDashboard(entityId);

    } else if (name === "journal") {
        // Show recent journal entries if any exist
        var status = world.formatStudyStatus ? world.formatStudyStatus() : null;
        world.emit("narrate", {
            text: world.t("study.use.journal.info", status || "")
        });

    } else if (name === "bookshelf") {
        // the bookshelf is the Study's Library
        // steward surface: installed packs, what's available, pending agent
        // proposals, and what the household keeps searching for and missing.
        doLibraryShelf(entityId);

    } else if (name === "shelves" || name === "shelf") {
        // The Study shelves ARE the private Library (per the object's own
        // description). Bare use = the Library-steward view; `use shelves
        // mounts` reaches the filesystem-mount surface without hiding it.
        var shelfArg = (target || "").trim().toLowerCase();
        if (shelfArg.indexOf("mount") === 0) {
            doListMounts(entityId);
        } else {
            doLibraryShelf(entityId);
        }

    } else if (name === "pinboard" || name === "pin") {
        world.emit("narrate", {
            text: world.t("study.use.pinboard.info")
        });

    } else if (name === "leather chair" || name === "chair") {
        world.emit("narrate", {
            text: world.t("study.use.chair.sit")
        });

    } else if (name === "wardrobe") {
        world.emit("narrate", {
            text: world.t("study.use.wardrobe")
        });

    } else if (name === "scroll of settings" || name === "scroll" || name === "settings") {
        doScrollOfSettings(entityId, target);

    } else if (name === "voice mirror" || name === "mirror" || name === "voice") {
        // #416 — Study voice-mirror furnishing. `use mirror` / `use voice mirror`
        // shows the current voice profile; deeper edits go through `say voice ...`.
        world.emit("narrate", { text: world.formatVoiceProfile() });

    } else if (name === "nostr sigil" || name === "sigil" || name === "nostr") {
        // focused steward surface for the bridge.
        // 'use sigil' / 'use nostr sigil' / 'use sigil enable|disable|status'
        doNostrSigil(entityId, target);
    }
}

// Library steward shelf. One view: what the
// Library holds, what could be added, what the agents have proposed, and
// what the household keeps asking for that the Library can't answer.
// Deeper commands: say "library install <pack>" / "library approve <id>" /
// "library reject <id> | <reason>".
function doLibraryShelf(entityId) {
    world.emit("narrate", {
        text: world.t("study.use.bookshelf.library",
            world.getKnowledgeStatus(),
            world.listAvailablePacks(),
            world.listLibraryProposals(),
            world.libraryTopMisses())
    });
}

// Nostr sigil — focused UX for the steward to enable/disable + inspect the
// Nostr bridge. Backed by the same wyrdsekai.nostr.* config keys as the scroll
// of settings; this is the lighter-weight surface so changing the toggle
// doesn't require remembering the exact key name.
function doNostrSigil(entityId, target) {
    var arg = (target || "").trim().toLowerCase();
    var enabledKey = "wyrdsekai.nostr.enabled";
    var relaysKey = "wyrdsekai.nostr.publish_relays";
    var rateKey = "wyrdsekai.nostr.max_events_per_minute";

    function readBool(key) {
        var v = world.configGet ? world.configGet(key) : null;
        if (v === null || v === undefined) return false;
        var s = String(v).toLowerCase();
        return s === "true" || s === "1" || s === "yes" || s === "on";
    }

    if (arg === "" || arg === "status" || arg === "show") {
        if (!world.configList) {
            world.emit("narrate", { text: world.t("study.nostr.unavailable") });
            return;
        }
        var enabled = readBool(enabledKey);
        var relays = world.configGet ? world.configGet(relaysKey) : null;
        var rate = world.configGet ? world.configGet(rateKey) : null;
        var lines = [world.t("study.nostr.header")];
        lines.push("  " + enabledKey + " = " + (enabled ? "true" : "false"));
        lines.push("  " + relaysKey + " = " + (relays || "(none configured)"));
        lines.push("  " + rateKey + " = " + (rate || "60 (default)"));
        if (!enabled) {
            lines.push("");
            lines.push(world.t("study.nostr.hint.enable"));
        } else if (!relays) {
            lines.push("");
            lines.push(world.t("study.nostr.hint.no_relays"));
        } else {
            lines.push("");
            lines.push(world.t("study.nostr.hint.active"));
        }
        world.emit("narrate", { text: lines.join("\n") });
        return;
    }

    if (arg === "enable" || arg === "on" || arg === "true") {
        var ok = world.configSet ? world.configSet(enabledKey, "true") : false;
        world.emit("narrate", {
            text: ok
                ? world.t("study.nostr.toggle.enabled")
                : world.t("study.scroll.set.denied", enabledKey)
        });
        return;
    }

    if (arg === "disable" || arg === "off" || arg === "false") {
        var ok2 = world.configSet ? world.configSet(enabledKey, "false") : false;
        world.emit("narrate", {
            text: ok2
                ? world.t("study.nostr.toggle.disabled")
                : world.t("study.scroll.set.denied", enabledKey)
        });
        return;
    }

    if (arg === "apply" || arg === "restart") {
        if (world.configApply) world.configApply();
        world.emit("narrate", { text: world.t("study.scroll.apply") });
        return;
    }

    // Anything else → help
    world.emit("narrate", { text: world.t("study.nostr.help") });
}

// Scroll of Settings — in-world config read/write. Mirrors `wyrd config`
// so stewards can change WYRDSEKAI_ZONE_ID, WYRDSEKAI_INFERENCE_URL, etc.
// without SSH'ing out. Scope: the Study is steward-gated at the Ward layer,
// so reaching the scroll implies steward privilege.
//
// GROUPED catalog of the steward-settable runtime keys — the in-world mirror
// of docs/CONFIG.md (config audit 2026-07-11: every key here has a verified
// reader; placebos were deleted or wired). Install-managed path keys
// (WYRDSEKAI_DATA_DIR, _HOME, JDBC, …) are deliberately omitted: they're set
// by the installer and changing them in-world would break the running node.
// Dev/test-only keys are omitted too. Keep descriptions to one line; the
// scroll's set/get/apply verbs act on these names.
var SCROLL_GROUPS = [
  { id: "identity", title: "identity & world", keys: [
    ["WYRDSEKAI_NODE_NAME", "this node's display name", "(hostname)"],
    ["WYRDSEKAI_LANG", "household language companions speak to users; any ISO 639-1 code accepted — en/ja/es ship with full translations + drift protection, other languages are prompt-level only (add catalogs in scripts/i18n)", "en"],
    ["WYRDSEKAI_SOUL_LANGUAGE_RECONCILE", "auto-repair companion memories rendered in the wrong language (gate new + heal existing; originals kept in manifest history)", "true"],
    ["WYRDSEKAI_ZONE_ID", "the zone label (the @name in your prompt)", "home"],
    ["WYRDSEKAI_ZONE_NAME", "human-readable zone name", "Home Zone"],
    ["WYRDSEKAI_ZONE_THEME", "aesthetic theme applied to room descriptions", "(from hostname)"],
    ["WYRDSEKAI_ZONE_AESTHETIC", "path to a custom zone-aesthetic file", ""],
    ["WYRDSEKAI_THEMED_ROOM_DESCRIPTIONS", "LLM-rewrite rooms in the theme's voice (true/false)", "true"],
    ["WYRDSEKAI_BIRTH_MODE", "how new companions are born (particular/neutral)", "particular"],
    ["WYRDSEKAI_COMPANION_NAME", "first companion's name (read at first boot)", ""],
    ["WYRDSEKAI_COMPANION_NAME_2", "second companion's name", ""],
    ["WYRDSEKAI_LOCALE", "UI/CLI language (en/es/ja) — canonical; WYRDSEKAI_LANG accepted", "en"],
    ["WYRDSEKAI_LATITUDE", "latitude for weather/context features", ""],
    ["WYRDSEKAI_LONGITUDE", "longitude for weather/context features", ""],
    ["WYRDSEKAI_EMERGENCY_JURISDICTION", "jurisdiction code for emergency resources", ""]
  ]},
  { id: "network", title: "network & doors", keys: [
    ["WYRDSEKAI_PORT", "HTTP + WebSocket port (ONE port serves both)", "7070"],
    ["WYRDSEKAI_HOSTNAME", "hostname for WebAuthn/CORS", "localhost"],
    ["WYRDSEKAI_PUBLIC_HOST", "externally-advertised host (OAuth callbacks)", ""],
    ["WYRDSEKAI_LAN_IP", "override the detected LAN IP for the mesh", "(auto)"],
    ["WYRDSEKAI_MAX_CONNECTIONS", "max concurrent WebSocket connections", "100"],
    ["WYRDSEKAI_SSH_ENABLED", "in-world SSH door (true/false)", "true"],
    ["WYRDSEKAI_SSH_PORT", "SSH door port", "7022"],
    ["WYRDSEKAI_TELNET_ENABLED", "classic telnet door — cleartext, opt-in only", "false"],
    ["WYRDSEKAI_TELNET_PORT", "telnet port", "7071"],
    ["WYRDSEKAI_TELNET_BIND", "telnet bind address (0.0.0.0 exposes to LAN)", "127.0.0.1"],
    ["WYRDSEKAI_SEARXNG_URL", "SearXNG metasearch endpoint for web_search", "http://localhost:8888"]
  ]},
  { id: "security", title: "security & privacy", keys: [
    ["WYRDSEKAI_OFFLINE", "hard offline switch — no outbound network at all", "false"],
    ["WYRDSEKAI_ALLOW_MODEL_DOWNLOAD", "permit pulling model weights from the net", "false"],
    ["WYRDSEKAI_ALLOW_ANONYMOUS", "let un-authenticated guests into the Nexus", "false"],
    ["WYRDSEKAI_WEB_APP", "serve the browser client at /app", "true"],
    ["WYRDSEKAI_MDNS_ENABLED", "announce this node on the LAN via mDNS", "true"],
    ["WYRDSEKAI_TLS_ENABLED", "serve HTTPS directly (prefer a reverse proxy)", "false"],
    ["WYRDSEKAI_TLS_PORT", "HTTPS port", "7443"],
    ["WYRDSEKAI_TLS_KEYSTORE", "path to JKS keystore", ""],
    ["WYRDSEKAI_TLS_AUTO_GENERATE", "self-sign a cert when keystore is missing", "true"],
    ["WYRDSEKAI_ZONE_PRIVACY_FLOOR", "minimum privacy tier enforced zone-wide", "private"],
    ["WYRDSEKAI_ALLOW_PUBLIC_LEG", "allow a public relay leg under a private floor", "false"],
    ["WYRDSEKAI_ENVELOPE_VERIFY", "federation envelope verification (off/soft/hard)", "soft"],
    ["WYRDSEKAI_RATE_LIMIT_ENABLED", "per-IP HTTP rate limiting", "true"],
    ["WYRDSEKAI_CODING_EGRESS_GATE", "scrub credentials from coding-backend subprocesses", "true"],
    ["WYRDSEKAI_MCP_STRICT_GRANTS", "strict MCP grant enforcement", "false"],
    ["WYRDSEKAI_ACTION_STRICT_GRANTS", "CONSENT-tier autonomous actions need an owner grant", "false"],
    ["WYRDSEKAI_MCP_DAILY_SPEND_CAP", "hard per-agent daily USD cap on metered MCP services", "10.0"],
    ["WYRDSEKAI_HOST_APPS", "allowlist of host apps agents may launch", ""],
    ["WYRDSEKAI_HOST_OPEN_ROOTS", "allowlisted host filesystem roots", ""]
  ]},
  { id: "skills", title: "skills & external tools", keys: [
    ["WYRDSEKAI_SKILLS_OPENCLAW_URL", "OpenClaw gateway WebSocket URL (wyrd openclaw setup)", ""],
    ["WYRDSEKAI_SKILLS_KIWIX_URL", "Kiwix offline-knowledge server (library.kiwix skills)", ""],
    ["WYRDSEKAI_SKILLS_HA_URL", "Home Assistant URL (hearth.ha skills)", ""],
    ["WYRDSEKAI_SKILLS_CALDAV_URL", "CalDAV calendar server (scriptorium.caldav skills)", ""],
    ["WYRDSEKAI_SKILLS_WHISPER_URL", "Whisper STT endpoint (voice.stt skills)", ""],
    ["WYRDSEKAI_SKILLS_OBSIDIAN_VAULT", "Obsidian vault path (vault.obsidian skills)", ""],
    ["WYRDSEKAI_SKILLS_SIGNAL_PHONE", "Signal phone number (herald.signal skills)", ""],
    ["WYRDSEKAI_SKILLS_EMERGENCY_CONTACTS", "emergency contact book: Name:phone[:relation],...", ""],
    ["WYRDSEKAI_CRED_TWILIO_ACCOUNT_SID", "Twilio SID for real emergency/telephony calls", ""],
    ["WYRDSEKAI_CRED_TWILIO_AUTH_TOKEN", "Twilio auth token (prefer The Safe over this)", ""],
    ["WYRDSEKAI_CRED_TWILIO_FROM_NUMBER", "Twilio caller-ID number (E.164)", ""]
  ]},
  { id: "storage", title: "storage & backups", keys: [
    ["WYRDSEKAI_STORAGE_BACKEND", "sqlite (single-node) or postgresql (community)", "sqlite"],
    ["WYRDSEKAI_PG_URL", "PostgreSQL JDBC URL", "jdbc:postgresql://localhost:5432/wyrdsekai"],
    ["WYRDSEKAI_PG_USER", "PostgreSQL user", "wyrdsekai"],
    ["WYRDSEKAI_PG_PASSWORD", "PostgreSQL password", ""],
    ["WYRDSEKAI_BACKUP_ENABLED", "periodic DB snapshots", "true"],
    ["WYRDSEKAI_BACKUP_INTERVAL_HOURS", "snapshot cadence (hours)", "24"],
    ["WYRDSEKAI_ENTITY_TTL_DAYS", "memory-entity time-to-live (days)", "90"],
    ["WYRDSEKAI_CONVERSATION_TURNS_RETENTION_DAYS", "conversation-turn retention (days)", "180"]
  ]},
  { id: "inference", title: "inference & models", keys: [
    ["WYRDSEKAI_INFERENCE_MODE", "where the brain runs: local / cloud / zone", "local"],
    ["WYRDSEKAI_INFERENCE_URL", "primary model endpoint (http/mlx/nats)", "http://127.0.0.1:8200"],
    ["WYRDSEKAI_LLAMA_ENABLED", "run the bundled llama-server (false = local kill-switch; alias WYRDSEKAI_INFERENCE_ENABLED)", "true"],
    ["WYRDSEKAI_MODEL_PATH", "GGUF model file the local server loads", "(setup-chosen)"],
    ["WYRDSEKAI_GPU_LAYERS", "layers offloaded to GPU (-ngl)", "99"],
    ["WYRDSEKAI_MODEL", "default model id sent in API requests", "default"],
    ["WYRDSEKAI_MODEL_ROUTINE", "small/fast model for routine turns", ""],
    ["WYRDSEKAI_MODEL_COMPLEX", "most-capable model for complex turns", ""],
    ["WYRDSEKAI_INFERENCE_TIMEOUT", "per-request timeout (seconds)", "120"],
    ["WYRDSEKAI_INFERENCE_CONCURRENCY", "router-wide in-flight cap — raise for vLLM/SGLang", "1"],
    ["WYRDSEKAI_INFERENCE_MAX_CONCURRENT", "per-backend concurrent limit (a DIFFERENT knob)", "4"],
    ["WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE", "offer this node's GPU to the household", "false"],
    ["WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW", "borrow a household GPU when local is weak", "true"],
    ["WYRDSEKAI_OLLAMA_ENABLED", "Ollama backend", "false"],
    ["WYRDSEKAI_OLLAMA_URL", "Ollama endpoint", "http://localhost:11434"],
    ["WYRDSEKAI_SGLANG_ENABLED", "SGLang backend", "false"],
    ["WYRDSEKAI_VLLM_ENABLED", "vLLM backend", "false"],
    ["WYRDSEKAI_OPENAI_ENABLED", "OpenAI-compatible cloud backend", "false"],
    ["WYRDSEKAI_ANTHROPIC_ENABLED", "Anthropic cloud backend", "false"],
    ["WYRDSEKAI_OPENROUTER_ENABLED", "OpenRouter cloud backend", "false"],
    ["WYRDSEKAI_CLAUDE_CLI_ENABLED", "Claude CLI backend (OAuth, no API key)", "false"],
    ["WYRDSEKAI_EMBEDDING_URL", "embedding-server URL", ""],
    ["WYRDSEKAI_EMBEDDING_MODEL", "retrieval embedding model id", "(registry default)"],
    ["WYRDSEKAI_PREDICTION_MODEL", "model id for the prediction subsystem", ""]
  ]},
  { id: "voice", title: "voice", keys: [
    ["WYRDSEKAI_VOICE_ENABLED", "register the distinct voice/prose model backend", "false"],
    ["WYRDSEKAI_VOICE_URL", "voice model endpoint", "http://127.0.0.1:8201"],
    ["WYRDSEKAI_VOICE_BACKEND", "voice backend kind (mlx/llamacpp/ollama/sglang)", ""],
    ["WYRDSEKAI_VOICE_PASS", "re-voice drive output through the voice model", "(= voice enabled)"],
    ["WYRDSEKAI_VOICE_ROUTE_BY_TASK", "route the voice pass by task type", "true"]
  ]},
  { id: "coding", title: "coding backends", keys: [
    ["WYRDSEKAI_CODING_DEFAULT_BACKEND", "last-ditch fallback coding backend", "goose"],
    ["WYRDSEKAI_CODING_GOOSE_ENABLED", "Goose (default backend)", "true"],
    ["WYRDSEKAI_CODING_GOOSE_PROVIDER", "Goose provider (openai = local llama-server)", "openai"],
    ["WYRDSEKAI_CODING_GOOSE_MODEL", "Goose model", "(local 9B)"],
    ["WYRDSEKAI_CODING_PI_ENABLED", "Pi backend", "true"],
    ["WYRDSEKAI_CODING_CODEZAIKU_ENABLED", "CodeZaiku in-world workflow", "true"],
    ["WYRDSEKAI_CODING_OPENCODE_ENABLED", "OpenCode backend", "true"],
    ["WYRDSEKAI_CODING_AIDER_ENABLED", "Aider backend", "false"],
    ["WYRDSEKAI_CODING_OPENHANDS_ENABLED", "OpenHands backend (Docker, heavy)", "false"],
    ["WYRDSEKAI_CODING_CLAUDE_SDK_ENABLED", "Claude Code SDK backend (needs key/OAuth)", "false"],
    ["WYRDSEKAI_CODING_CODEX_ENABLED", "Codex CLI backend (paid)", "false"],
    ["WYRDSEKAI_CODING_GEMINI_CLI_ENABLED", "Gemini CLI backend (paid)", "false"],
    ["WYRDSEKAI_CODING_DEVIN_ENABLED", "Devin backend (async cloud, $$)", "false"],
    ["WYRDSEKAI_CODE_MODE_ENABLED", "master code-mode switch", "false"]
  ]},
  { id: "relay", title: "relay & federation", keys: [
    ["WYRDSEKAI_BETWEEN_ENABLED", "inter-node networking layer", "true"],
    ["WYRDSEKAI_NATS_URL", "local/household NATS URL", "nats://127.0.0.1:4222"],
    ["WYRDSEKAI_NATS_AUTO_START", "auto-start an embedded nats-server", "true"],
    ["WYRDSEKAI_RELAY_ENABLED", "connect out to a relay", "false"],
    ["WYRDSEKAI_RELAY_URL", "the relay this zone reaches the world through", ""],
    ["WYRDSEKAI_RELAY_USER", "relay auth user", ""],
    ["WYRDSEKAI_RELAY_TOKEN", "relay auth token", ""],
    ["WYRDSEKAI_RELAY_FINGERPRINT", "pinned relay TLS fingerprint", ""],
    ["WYRDSEKAI_RELAY_VISIBILITY", "relay listing (public/hidden/private)", "private"],
    ["WYRDSEKAI_RELAY_USE_NKEY", "NKey relay auth instead of password", "false"],
    ["WYRDSEKAI_ZONE_PUBLIC_URL", "the address others reach this zone at", ""],
    ["WYRDSEKAI_FEDERATION_AUTO_ACCEPT", "auto-accept federation proposals", "false"],
    ["WYRDSEKAI_RENDEZVOUS_URLS", "rendezvous server URLs", ""],
    ["WYRDSEKAI_DIRECTORY_PEERS", "directory peer list (CSV)", ""],
    ["WYRDSEKAI_SSH_TUNNEL_ENABLED", "SSH reverse tunnel to a relay host", "false"],
    ["WYRDSEKAI_SSH_TUNNEL_RELAY_HOST", "SSH tunnel relay host", ""],
    ["WYRDSEKAI_ZONE_TAGLINE", "zone tagline shown in the directory", "A Wyrdsekai zone"],
    ["WYRDSEKAI_ZONE_TAGS", "zone tags for the directory (CSV)", ""],
    ["WYRDSEKAI_NOSTR_ENABLED", "Nostr bridge (or use the nostr sigil)", "false"],
    ["WYRDSEKAI_NOSTR_RELAYS", "Nostr relay URLs (CSV)", "(damus/nos.lol/snort)"]
  ]},
  { id: "recipes", title: "recipes & training", keys: [
    ["WYRDSEKAI_RECIPES_SCHEDULER_ENABLED", "recipe scheduler (self-evolution)", "true"],
    ["WYRDSEKAI_RECIPES_GPU_DAILY_HOURS", "daily GPU budget for recipes (hours)", "6"],
    ["WYRDSEKAI_RECIPES_MONTHLY_CAP", "monthly recipe-run cap", "100"],
    ["WYRDSEKAI_RECIPES_REQUEST_MIN_TIER", "min autonomy tier to request recipe runs", "companion"],
    ["WYRDSEKAI_TRAINING_POLICY", "deep-sleep training policy (aggressive/conservative)", ""],
    ["WYRDSEKAI_FORGE_ENABLED", "forge subsystem", "false"],
    ["WYRDSEKAI_ORACLE_ENABLED", "oracle bridge", "false"],
    ["WYRDSEKAI_LIBRARY_STARTER", "install the starter library", ""],
    ["WYRDSEKAI_LIBRARY_LANGS", "starter-library languages", ""],
    ["WYRDSEKAI_LIBRARY_RELEVANCE_FLOOR", "library retrieval relevance floor", "0.35"]
  ]},
  { id: "updates", title: "updates", keys: [
    ["WYRDSEKAI_UPDATE_CHANNEL", "update channel", ""],
    ["WYRDSEKAI_UPDATE_POLICY", "prompt or auto updates", "prompt"],
    ["WYRDSEKAI_UPDATE_INTERVAL", "update-check interval", "6h"],
    ["WYRDSEKAI_UPDATE_WINDOW", "allowed update time window", ""],
    ["WYRDSEKAI_UPDATE_PIN", "pin to a specific version", ""],
    ["WYRDSEKAI_VERSION_CACHE", "cached versions kept for rollback", "3"]
  ]},
  { id: "tuning", title: "tuning (advanced)", keys: [
    ["WYRDSEKAI_AUTONOMY_INTERVAL_MS", "autonomy tick interval (ms)", "600000"],
    ["WYRDSEKAI_TICK_ENERGY_RECOVERY", "per-tick energy delta", "-0.0002"],
    ["WYRDSEKAI_SLEEP_THRESHOLD", "energy level triggering emergency sleep (collapse fallback)", "0.15"],
    ["WYRDSEKAI_SLEEP_BACKLOG_TARGET", "unprocessed events that make sleep attractive; genome trait sleep_backlog_target overrides per companion", "600"],
    ["WYRDSEKAI_SLEEP_BACKLOG_MIN", "floor for the personal sleep-pressure target (anti-thrash)", "40"],
    ["WYRDSEKAI_SLEEP_RECOVERY", "energy recovered by sleep", "0.3"],
    ["WYRDSEKAI_ENERGY_DRAIN", "activity energy drain rate", "0.08"],
    ["WYRDSEKAI_CONSOLIDATION_INTERVAL_MINUTES", "memory-consolidation interval", "30"],
    ["WYRDSEKAI_PRESENCE_SILENCE_SEC", "co-presence silence window (sec)", "300"],
    ["WYRDSEKAI_AGENTS_QUIET_WHEN_HUMAN_PRESENT", "hush agent chatter around humans", "true"],
    ["WYRDSEKAI_SESSION_IDLE_REAP_MINUTES", "idle client-session reaping", "240"],
    ["WYRDSEKAI_INFERENCE_MAX_QUEUE", "resilience-layer inference queue depth", "20"]
  ]}
];

function scrollGroupById(id) {
    for (var i = 0; i < SCROLL_GROUPS.length; i++) {
        if (SCROLL_GROUPS[i].id === id) return SCROLL_GROUPS[i];
    }
    return null;
}

// Find a key's catalog row across all groups → [group, [key, desc, def]] or null.
function scrollFindCatalogEntry(key) {
    for (var i = 0; i < SCROLL_GROUPS.length; i++) {
        var ks = SCROLL_GROUPS[i].keys;
        for (var j = 0; j < ks.length; j++) {
            if (ks[j][0] === key) return [SCROLL_GROUPS[i], ks[j]];
        }
    }
    return null;
}

// Group index — the discovery entry point. Not a 200-line dump: names the
// groups, says how to open one.
function doScrollGroupIndex(entityId, path) {
    var lines = [
        "The scroll's index — settings are grouped. Open a group with",
        "'use scroll list <group>', read one key with 'use scroll get KEY',",
        "set with 'use scroll set KEY=VALUE', then 'use scroll apply'.",
        ""
    ];
    for (var i = 0; i < SCROLL_GROUPS.length; i++) {
        var g = SCROLL_GROUPS[i];
        lines.push("  " + g.id + (g.id.length < 9 ? "\t" : " ") + "— " + g.title + " (" + g.keys.length + " keys)");
    }
    lines.push("");
    lines.push("  net\t— agent network allowlist (ssh/scp doors) — 'use scroll net'");
    lines.push("");
    lines.push("Full reference: docs/CONFIG.md   ·   Bound to: " + path);
    world.emit("narrate", { text: lines.join("\n") });
}

// One group's catalog, with live current values.
function doScrollCatalog(entityId, path, group) {
    var lines = [
        "— " + group.title + " — say 'use scroll set KEY=VALUE' then 'use scroll apply'.",
        "(A default value means nothing is overridden.)",
        ""
    ];
    for (var i = 0; i < group.keys.length; i++) {
        var key = group.keys[i][0], desc = group.keys[i][1], def = group.keys[i][2];
        var cur = world.configGet ? world.configGet(key) : null;
        var shown = (cur !== null && cur !== undefined && cur !== "")
            ? cur + "  (set)"
            : (def ? def + " (default)" : "(unset)");
        lines.push("  " + key);
        lines.push("      " + desc);
        lines.push("      now: " + shown);
    }
    lines.push("");
    lines.push("Bound to: " + path + "   ·   'use scroll keys' for the group index.");
    world.emit("narrate", { text: lines.join("\n") });
}

function doScrollOfSettings(entityId, target) {
    var arg = (target || "").trim();
    var path = world.configPath ? world.configPath() : "(config binding unavailable)";

    // "scroll net allow/list/revoke": the structured
    // allowlist gets its own sub-command (it binds a credential ref, so raw
    // KEY=VALUE writes are the wrong surface). Hot-reloads without a bounce.
    if (arg.toLowerCase() === "net" || arg.toLowerCase().indexOf("net ") === 0) {
        doScrollNet(entityId, arg.substring(3).trim());
        return;
    }

    // No argument → list everything; optional filter word narrows the view.
    // "scroll list" or "scroll show" — full config.
    // "scroll familiar" / "scroll bunshin" / "scroll promotion" — filtered view
    // for stewards tuning the settings.
    var lowerArg = arg.toLowerCase();

    // "scroll keys" / "scroll catalog" — the DISCOVERY view: what CAN be set.
    // Bare form shows the GROUP INDEX (not a 200-line dump); "scroll keys
    // <group>" / "scroll list <group>" opens one group with descriptions +
    // current value + default. `scroll list` alone only shows what's already
    // been written, which is empty on a fresh install — leaving a steward
    // with no way to learn the vocabulary (second-node, 2026-07-04).
    if (lowerArg === "keys" || lowerArg === "catalog" || lowerArg === "options") {
        doScrollGroupIndex(entityId, path);
        return;
    }
    // "keys <group>" / "catalog <group>" / "list <group>" / bare "<group>"
    var groupWord = null;
    if (lowerArg.indexOf("keys ") === 0) groupWord = lowerArg.substring(5).trim();
    else if (lowerArg.indexOf("catalog ") === 0) groupWord = lowerArg.substring(8).trim();
    else if (lowerArg.indexOf("list ") === 0) groupWord = lowerArg.substring(5).trim();
    else if (scrollGroupById(lowerArg)) groupWord = lowerArg;
    if (groupWord !== null) {
        var grp = scrollGroupById(groupWord);
        if (grp) {
            doScrollCatalog(entityId, path, grp);
            return;
        }
        // fall through: "list familiar" etc. handled by the override filter below
    }

    var filterWord = null;
    if (!arg || lowerArg === "list" || lowerArg === "show") {
        filterWord = null;
    } else if (lowerArg === "familiar" || lowerArg === "familiars"
               || lowerArg === "bunshin" || lowerArg === "promotion"
               || lowerArg === "nostr"
               || (groupWord !== null && !scrollGroupById(groupWord))) {
        var fw = groupWord !== null ? groupWord : lowerArg;
        filterWord = fw === "familiars" ? "familiar" : fw;
    }
    if (!arg || lowerArg === "list" || lowerArg === "show" || filterWord !== null) {
        var cfg = world.configList ? world.configList() : null;
        if (!cfg) {
            world.emit("narrate", { text: world.t("study.scroll.unavailable") });
            return;
        }
        var keys = Object.keys(cfg).sort();
        if (filterWord !== null) {
            var filter = "wyrdsekai." + filterWord;
            keys = keys.filter(function (k) { return k.indexOf(filter) === 0; });
        }
        if (keys.length === 0) {
            world.emit("narrate", {
                text: filterWord !== null
                    ? "No " + filterWord + " keys set (using reference defaults). "
                      + "Groups: 'use scroll keys' lists them."
                    : "The scroll of settings is blank — nothing has been overridden yet; "
                      + "the household runs on its defaults. (File: " + path + ")\n"
                      + "Say 'use scroll keys' for the grouped index of what CAN be set "
                      + "('use scroll list security', 'use scroll list inference', …)."
            });
            return;
        }
        var lines = [world.t("study.scroll.list.header", path)];
        for (var i = 0; i < keys.length; i++) {
            lines.push("  " + keys[i] + " = " + cfg[keys[i]]);
        }
        lines.push("");
        lines.push("('use scroll keys' shows everything that CAN be set, grouped.)");
        world.emit("narrate", { text: lines.join("\n") });
        return;
    }

    // "get KEY"
    if (arg.toLowerCase().indexOf("get ") === 0) {
        var key = arg.substring(4).trim();
        var val = world.configGet ? world.configGet(key) : null;
        if (val === null || val === undefined) {
            // Unset — teach instead of shrugging: if the key is in the
            // catalog, show its meaning, default and group.
            var hit = scrollFindCatalogEntry(key);
            if (hit) {
                world.emit("narrate", {
                    text: key + " is not set — the default is in force.\n"
                        + "  " + hit[1][1] + "\n"
                        + "  default: " + (hit[1][2] || "(unset)")
                        + "   ·   group: " + hit[0].id + "\n"
                        + "  Set it with 'use scroll set " + key + "=VALUE'."
                });
            } else {
                world.emit("narrate", { text: world.t("study.scroll.get.missing", key) });
            }
        } else {
            world.emit("narrate", { text: world.t("study.scroll.get.value", key, val) });
        }
        return;
    }

    // "set KEY=VALUE" or "set KEY VALUE"
    if (arg.toLowerCase().indexOf("set ") === 0) {
        var rest = arg.substring(4).trim();
        var eqIdx = rest.indexOf("=");
        var spIdx = rest.indexOf(" ");
        var k, v;
        if (eqIdx > 0 && (spIdx < 0 || eqIdx < spIdx)) {
            k = rest.substring(0, eqIdx).trim();
            v = rest.substring(eqIdx + 1).trim();
        } else if (spIdx > 0) {
            k = rest.substring(0, spIdx).trim();
            v = rest.substring(spIdx + 1).trim();
        } else {
            world.emit("narrate", { text: world.t("study.scroll.set.usage") });
            return;
        }
        var ok = world.configSet ? world.configSet(k, v) : false;
        world.emit("narrate", {
            text: ok
                ? world.t("study.scroll.set.ok", k, v)
                : world.t("study.scroll.set.denied", k)
        });
        return;
    }

    // "apply" / "restart" → request service restart
    if (arg.toLowerCase() === "apply" || arg.toLowerCase() === "restart") {
        if (world.configApply) world.configApply();
        world.emit("narrate", { text: world.t("study.scroll.apply") });
        return;
    }

    // "path"
    if (arg.toLowerCase() === "path" || arg.toLowerCase() === "where") {
        world.emit("narrate", { text: world.t("study.scroll.path", path) });
        return;
    }

    world.emit("narrate", { text: world.t("study.scroll.help", path) });
}

// the network allowlist sub-command.
//   use scroll net allow second-node ssh,scp --key household:second-node [--prefix "backup"]
//   use scroll net list
//   use scroll net revoke second-node
// ssh/scp stay default-deny until an entry exists; the verb validates the
// host and that the key ref resolves to a keyfile BEFORE persisting, so a
// steward can't bind a dangling credential pointer.
function doScrollNet(entityId, rest) {
    if (!world.netList) {
        world.emit("narrate", { text: "The network page of the scroll is blank — this node has no network binding." });
        return;
    }
    var r = (rest || "").trim();
    var lower = r.toLowerCase();

    if (!r || lower === "list" || lower === "show") {
        var entries;
        try { entries = JSON.parse(world.netList() || "[]"); } catch (e) { entries = []; }
        var lines = ["The scroll's network page — credentialed reach (ssh/scp starts CLOSED):"];
        if (!entries || entries.length === 0) {
            lines.push("  (no allowlist entries — companions can reach no ssh/scp host)");
        } else {
            for (var i = 0; i < entries.length; i++) {
                var e = entries[i];
                var line = "  " + e.host + "  [" + (e.kinds || []).join(",") + "]";
                if (e.keyRef) line += "  key: " + e.keyRef;
                if (e.commandPrefix) line += "  prefix: '" + e.commandPrefix + "'";
                lines.push(line);
            }
        }
        lines.push("");
        lines.push("Open a door:   use scroll net allow <host> <kinds> --key <household:node|chest:slot>");
        lines.push("Close one:     use scroll net revoke <host>");
        lines.push("(HTTP stays open by default; an http entry flips that host-set to restrict-mode.)");
        world.emit("narrate", { text: lines.join("\n") });
        return;
    }

    if (lower.indexOf("allow ") === 0) {
        var parts = r.substring(6).trim().split(/\s+/);
        if (parts.length < 2) {
            world.emit("narrate", { text: "Usage: use scroll net allow <host> <kinds> --key <ref> [--prefix <cmd>]" });
            return;
        }
        var host = parts[0];
        var kinds = parts[1];
        var keyRef = null;
        var prefix = null;
        for (var j = 2; j < parts.length; j++) {
            if (parts[j] === "--key" && j + 1 < parts.length) { keyRef = parts[++j]; }
            else if (parts[j] === "--prefix" && j + 1 < parts.length) {
                prefix = parts.slice(j + 1).join(" ");
                break;
            }
        }
        var res;
        try { res = JSON.parse(world.netAllow(host, kinds, keyRef, prefix)); } catch (e) { res = { ok: false, error: String(e) }; }
        if (res && res.ok) {
            world.emit("narrate", {
                text: "The scroll accepts the entry: " + res.host + " [" + (res.kinds || []).join(",") + "]"
                    + (res.keyRef ? "  key: " + res.keyRef : "")
                    + "\nIt takes effect immediately — no restart needed."
            });
        } else {
            world.emit("narrate", { text: "The scroll refuses: " + (res && res.error ? res.error : "unknown error") });
        }
        return;
    }

    if (lower.indexOf("revoke ") === 0) {
        var target2 = r.substring(7).trim();
        var res2;
        try { res2 = JSON.parse(world.netRevoke(target2)); } catch (e) { res2 = { ok: false, error: String(e) }; }
        if (res2 && res2.ok) {
            world.emit("narrate", { text: "The entry for " + res2.host + " fades from the scroll. That door is closed again." });
        } else {
            world.emit("narrate", { text: "The scroll refuses: " + (res2 && res2.error ? res2.error : "unknown error") });
        }
        return;
    }

    world.emit("narrate", {
        text: "Network page of the scroll:\n"
            + "  use scroll net list\n"
            + "  use scroll net allow <host> <kinds> --key <household:node|chest:slot> [--prefix <cmd>]\n"
            + "  use scroll net revoke <host>"
    });
}

function doDashboard(entityId) {
    var parts = [];
    parts.push(world.t("study.dashboard.title"));

    // Knowledge base status
    try {
        var knowledge = world.getKnowledgeStatus();
        if (knowledge) parts.push(world.t("study.dashboard.knowledge", knowledge));
    } catch (e) { /* not available */ }

    // Inference status (if accessible from study)
    try {
        var inference = world.getInferenceStatus();
        if (inference) parts.push(world.t("study.dashboard.inference", inference));
    } catch (e) { /* not available from study */ }

    // Health status
    try {
        var health = world.getHealthStatus();
        if (health) parts.push(world.t("study.dashboard.systems", health));
    } catch (e) { /* not available from study */ }

    if (parts.length === 1) {
        parts.push(world.t("study.dashboard.unavailable"));
    }

    parts.push(world.t("study.dashboard.footer"));
    world.emit("narrate", { text: parts.join("\n") });
}

function doOpenApp(entityId, entityName, app) {
    world.emit("command", {
        verb: "app_launch",
        actor: entityId,
        target: app
    });
    world.emit("narrate", {
        text: world.t("study.app.launching", app)
    });
}

function doReadFile(entityId, entityName, file) {
    // Try document extraction via vault skill
    var result = world.mcp("skill", "vault.doc.extract", {
        itemPath: file
    });

    if (result && result.success) {
        world.emit("narrate", {
            text: world.t("study.file.contents", file, result.data)
        });
    } else {
        // Fall back to raw file open event
        world.emit("command", {
            verb: "file_open",
            actor: entityId,
            target: file
        });
        world.emit("narrate", {
            text: world.t("study.file.opening", file)
        });
    }
}

function doBrowseUrl(entityId, entityName, url) {
    world.emit("command", {
        verb: "url_open",
        actor: entityId,
        target: url
    });
    world.emit("narrate", {
        text: world.t("study.browse.opening", url)
    });
}

function doSchedule(entityId) {
    var services = ["caldav", "google-workspace", "gcal"];
    var service = null;
    for (var i = 0; i < services.length; i++) {
        if (world.mcpAvailable(services[i])) {
            service = services[i];
            break;
        }
    }

    if (!service) {
        world.emit("narrate", {
            text: world.t("study.schedule.no_service")
        });
        return;
    }

    var result = world.mcp(service, "get_events", { range: "today" });
    if (result && result.success) {
        world.emit("narrate", {
            text: world.t("study.schedule.board", result.data)
        });
    } else {
        world.emit("narrate", {
            text: world.t("study.schedule.error", result ? result.error : "")
        });
    }
}

function doMount(entityId, args) {
    // Format: "path as label"
    var asIdx = args.toLowerCase().indexOf(" as ");
    if (asIdx < 0) {
        world.emit("narrate", {
            text: world.t("study.mount.usage")
        });
        return;
    }

    var path = args.substring(0, asIdx).trim();
    var label = args.substring(asIdx + 4).trim();

    if (!path || !label) {
        world.emit("narrate", {
            text: world.t("study.mount.usage")
        });
        return;
    }

    var mounts = getMounts();
    mounts[label] = path;
    setMounts(mounts);

    // The host validates the path and records it in the persisted mount
    // table (StudyShellBridge) — IT narrates the outcome, so a bad path
    // gets a teaching refusal instead of a false "mounted" here.
    world.emit("command", {
        verb: "fs_mount",
        actor: entityId,
        target: path,
        label: label
    });
}

function doUnmount(entityId, label) {
    // Don't gate on getMounts(): the host table is authoritative but written
    // asynchronously (a fast mount→unmount raced it once the table became
    // readable, e2e 2026-07-11). Always forward; the host bridge narrates
    // honestly when the shelf truly doesn't exist.
    var mounts = getMounts();
    if (mounts[label]) {
        delete mounts[label];
        setMounts(mounts);
    }

    // Host removes the shelf from the persisted mount table and narrates.
    world.emit("command", {
        verb: "fs_unmount",
        actor: entityId,
        target: label
    });
}

function doListMounts(entityId) {
    var mounts = getMounts();
    var keys = Object.keys(mounts);

    if (keys.length === 0) {
        world.emit("narrate", {
            text: world.t("study.mounts.empty")
        });
        return;
    }

    var lines = [];
    for (var i = 0; i < keys.length; i++) {
        lines.push("  " + keys[i] + " -> " + mounts[keys[i]]);
    }
    world.emit("narrate", {
        text: world.t("study.mounts.list", lines.join("\n"))
    });
}

function doShellCommand(entityId, entityName, command) {
    if (!command) {
        world.emit("narrate", {
            text: world.t("study.shell.help")
        });
        return;
    }

    var parts = command.split(/\s+/);
    var cmd = parts[0].toLowerCase();
    var args = parts.slice(1).join(" ");

    // Whitelist of allowed commands
    var allowed = ["ls", "find", "grep", "cat", "head", "tail", "wc", "sort",
                    "uniq", "date", "cal", "echo", "pwd", "open", "search", "take"];

    if (allowed.indexOf(cmd) < 0) {
        world.emit("narrate", {
            text: world.t("study.shell.unknown_command", cmd)
        });
        return;
    }

    switch (cmd) {
        case "ls":
            doShellLs(entityId, args);
            break;
        case "find":
        case "search":
            doShellSearch(entityId, args);
            break;
        case "grep":
            doShellGrep(entityId, args);
            break;
        case "cat":
        case "head":
        case "tail":
            doShellRead(entityId, cmd, args);
            break;
        case "open":
            doOpenApp(entityId, entityName, args);
            break;
        case "take":
            doShellTake(entityId, args);
            break;
        case "pwd":
            doShellPwd(entityId);
            break;
        case "echo":
            world.emit("narrate", { text: args });
            break;
        case "date":
            world.emit("narrate", { text: new Date().toISOString() });
            break;
        case "cal":
            doSchedule(entityId);
            break;
        case "wc":
        case "sort":
        case "uniq":
            doShellTextUtil(entityId, cmd, args);
            break;
        default:
            world.emit("narrate", {
                text: world.t("study.shell.unknown_command", cmd)
            });
    }
}

function doShellLs(entityId, args) {
    var mounts = getMounts();
    var target = args.trim();

    if (!target) {
        // List all mounts (like ls /)
        var keys = Object.keys(mounts);
        if (keys.length === 0) {
            world.emit("narrate", { text: world.t("study.mounts.empty") });
            return;
        }
        var lines = keys.map(function(k) { return k + "/"; });
        world.emit("narrate", { text: lines.join("\n") });
        return;
    }

    // List files in a mount via filesystem skill
    var result = world.mcp("skill", "study.fs.list", { path: target });
    if (result && result.success) {
        world.emit("narrate", { text: result.data });
    } else {
        // Prefer the service's teaching error (it names mounted shelves).
        world.emit("narrate", {
            text: (result && result.error) ? result.error
                : world.t("study.shell.not_found", target)
        });
    }
}

function doShellSearch(entityId, query) {
    if (!query) {
        world.emit("narrate", { text: world.t("study.shell.search_usage") });
        return;
    }
    var result = world.mcp("skill", "study.fs.search", { query: query });
    if (result && result.success) {
        world.emit("narrate", { text: result.data });
    } else {
        world.emit("narrate", {
            text: (result && result.error) ? result.error
                : world.t("skill.filesearch.no_results", query)
        });
    }
}

function doShellGrep(entityId, args) {
    if (!args) {
        world.emit("narrate", { text: world.t("study.shell.grep_usage") });
        return;
    }
    var result = world.mcp("skill", "study.fs.search", { query: args, type: "content" });
    if (result && result.success) {
        world.emit("narrate", { text: result.data });
    } else {
        world.emit("narrate", {
            text: (result && result.error) ? result.error
                : world.t("skill.filesearch.no_results", args)
        });
    }
}

function doShellRead(entityId, cmd, args) {
    if (!args) {
        world.emit("narrate", { text: world.t("study.shell.read_usage") });
        return;
    }
    var result = world.mcp("skill", "vault.doc.extract", { itemPath: args });
    if (result && result.success) {
        var text = result.data;
        if (cmd === "head") {
            var lines = text.split("\n").slice(0, 10);
            text = lines.join("\n");
        } else if (cmd === "tail") {
            var lines = text.split("\n");
            text = lines.slice(Math.max(0, lines.length - 10)).join("\n");
        }
        world.emit("narrate", { text: text });
    } else {
        world.emit("narrate", {
            text: (result && result.error) ? result.error
                : world.t("study.shell.not_found", args)
        });
    }
}

function doShellTake(entityId, target) {
    if (!target) {
        world.emit("narrate", { text: world.t("study.shell.take_usage") });
        return;
    }
    // Import file as an item to inventory. The host (StudyShellBridge) does
    // the real read + inventory write and narrates the outcome — narrating
    // "Taken" here before anything happened was a false success.
    world.emit("command", {
        verb: "take",
        actor: entityId,
        target: target
    });
}

function doShellPwd(entityId) {
    var mounts = getMounts();
    var keys = Object.keys(mounts);
    if (keys.length === 0) {
        world.emit("narrate", { text: "/" });
    } else {
        world.emit("narrate", { text: "/ (mounts: " + keys.join(", ") + ")" });
    }
}

function doShellTextUtil(entityId, cmd, args) {
    if (!args) {
        world.emit("narrate", { text: world.t("study.shell.text_util_usage", cmd) });
        return;
    }
    // Pipe support: read file, apply text utility
    var result = world.mcp("skill", "vault.doc.extract", { itemPath: args });
    if (result && result.success) {
        var text = result.data;
        var lines = text.split("\n");
        switch (cmd) {
            case "wc":
                var words = text.split(/\s+/).length;
                var chars = text.length;
                world.emit("narrate", {
                    text: lines.length + " " + words + " " + chars + " " + args
                });
                break;
            case "sort":
                world.emit("narrate", { text: lines.sort().join("\n") });
                break;
            case "uniq":
                var unique = [];
                var prev = null;
                for (var i = 0; i < lines.length; i++) {
                    if (lines[i] !== prev) {
                        unique.push(lines[i]);
                        prev = lines[i];
                    }
                }
                world.emit("narrate", { text: unique.join("\n") });
                break;
        }
    } else {
        world.emit("narrate", {
            text: (result && result.error) ? result.error
                : world.t("study.shell.not_found", args)
        });
    }
}

function getHints() {
    var hints = [
        { label: world.t("study.hint.help"), intent: "help", action: "say:help" },
        { label: world.t("study.hint.schedule"), intent: "schedule", action: "say:schedule" },
        { label: world.t("study.hint.desk"), intent: "use_desk", action: "use:desk" },
        { label: world.t("study.hint.bookshelf"), intent: "library_steward", action: "use:shelves" },
        { label: world.t("study.hint.west"), intent: "navigate_west", action: "go:west" }
    ];

    var ageBracket = world.getProperty("age_bracket");
    if (ageBracket && ageBracket !== "tree") {
        // Add age-appropriate hints
        if (ageBracket === "seedling") {
            hints.push({ label: world.t("study.child.seedling.hint.play"), intent: "play", action: "use:toy-chest" });
            hints.push({ label: world.t("study.child.seedling.hint.draw"), intent: "draw", action: "use:coloring-table" });
        } else if (ageBracket === "sprout") {
            hints.push({ label: world.t("study.child.sprout.hint.read"), intent: "read_story", action: "use:adventure-books" });
            hints.push({ label: world.t("study.child.sprout.hint.craft"), intent: "craft", action: "use:craft-table" });
        } else if (ageBracket === "sapling") {
            hints.push({ label: world.t("study.child.sapling.hint.write"), intent: "write", action: "use:journal" });
            hints.push({ label: world.t("study.child.sapling.hint.build"), intent: "build", action: "use:toolbench" });
        } else if (ageBracket === "young-tree") {
            hints.push({ label: world.t("study.child.young-tree.hint.study"), intent: "study", action: "use:desk" });
            hints.push({ label: world.t("study.child.young-tree.hint.research"), intent: "research", action: "use:computer" });
        }
    }

    // Oracle prediction hints (when predictions available)
    var predictionsRaw = world.getProperty("oracle_predictions");
    if (predictionsRaw) {
        try {
            var predictions = JSON.parse(predictionsRaw);
            if (predictions.length > 0) {
                hints.push({
                    label: world.t("study.oracle.hint.predictions"),
                    intent: "view_predictions",
                    action: "say:predictions"
                });
            }
        } catch (e) { /* ignore */ }
    }

    var mounts = getMounts();
    if (Object.keys(mounts).length > 0) {
        hints.push({
            label: world.t("study.hint.browse_mounts"),
            intent: "browse_mounts",
            action: "use:shelves"
        });
    }

    // Extension hints
    var ext = loadExtensions();
    if (ext.loaded && typeof _extensionGetHints === "function") {
        var extHints = _extensionGetHints();
        if (extHints) {
            for (var i = 0; i < extHints.length; i++) {
                hints.push(extHints[i]);
            }
        }
    }

    return hints;
}
