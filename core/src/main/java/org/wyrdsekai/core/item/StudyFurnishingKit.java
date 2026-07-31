package org.wyrdsekai.core.item;

import org.wyrdsekai.core.item.ToolItem.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted furnishings seeded into a new Study.
 *
 * <p>Each furnishing is a {@link ToolItem} whose script reads from the Home
 * model via the {@code world.audit} / {@code world.grants} / {@code world.home}
 * APIs. They are placed in the owner's inventory so the existing
 * {@code tryInvokeCarriedScript} pathway routes {@code look embers} and
 * {@code read board} through the script executor without any new plumbing.</p>
 *
 * <p>v1 ships two furnishings — Embers (audit log view) and Board (grants
 * issued) — because those were the surfaces unblocked by Phase M1a. More
 * items (Ledger, Mailbox, Mirror, Manifest, Shelf, Lantern, Trunk, Compass,
 * Window) come as their underlying services are exposed via the world API.</p>
 */
public final class StudyFurnishingKit {

    private StudyFurnishingKit() {}

    /** All M1c furnishings. Called by ZoneGuardian on Study provisioning. */
    public static List<ToolItem> defaults() {
        return List.of(embers(), board(), mailbox(),
            ledger(), manifest(), trunk(),
            shelf(), lantern(), mirror(), compass(), window(),
            threshold(),
            // Phase 1b
            codingSlate(),
            codex(),
            // (P4) — in-world relay governance
            warden(),
            // MCP capability grants (steward) — makes strict-grants usable
            toolWarden());
    }

    /**
     * Furnishings for a specific resident: everyone gets {@link #defaults()};
     * the STEWARD's Study additionally seats the four
     * network items (courier satchel / far-hand / postrider / wire) as
     * hands-on surfaces — `use far_hand host=… command=…` from the Study.
     * They run the same {@code world.net.*} seam the companions use, so the
     * NetworkGate (ssh/scp default-deny until `scroll net allow`) applies to
     * the steward's own use identically. Non-stewards never see them: the
     * items are seeded only into the steward's own Study room.
     */
    public static List<ToolItem> defaultsFor(boolean steward) {
        if (!steward) return defaults();
        var out = new ArrayList<>(defaults());
        out.addAll(NetworkItemKit.networkItems());
        return List.copyOf(out);
    }

    // ─── Embers — centerpiece fire, audit log view ──────────────────

    /**
     * {@code look embers} / {@code examine embers} / {@code watch embers} —
     * shows the owner's recent audit entries (grant activity, access decisions,
     * home-entered events). Invoked via ItemScriptExecutor through the existing
     * {@code tryInvokeCarriedScript} path in WyrdWebSocket.
     */
    public static ToolItem embers() {
        return ToolItem.scripted(
            "embers",
            "Embers",
            "A low bank of glowing embers in the centerpiece fire. Each ember is an event: "
            + "a grant issued, a door opened, a visitor checking in. Watch them to see what "
            + "has happened in your Home. Examine or read to review recent entries.",
            EMBERS_SCRIPT,
            List.of(
                new ToolParam("limit", "integer",
                    "How many entries to show (default: 20, max: 50). Omit for the default.",
                    false, null)
            ),
            "home-furnishing");
    }

    private static final String EMBERS_SCRIPT = """
        function invoke(params) {
            var limit = (params && params.limit) ? Math.min(parseInt(params.limit, 10) || 20, 50) : 20;
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The embers are dim. (No Home is bound to this furnishing.)" };
            }
            var entries = world.audit.recent(limit);
            if (!entries || entries.length === 0) {
                return { text: "The embers rest quietly. Nothing has happened in your Home since you last looked." };
            }
            var lines = [];
            lines.push("In the embers, you see the last " + entries.length + " events in your Home:");
            for (var i = 0; i < entries.length; i++) {
                var e = entries[i];
                var line = "  " + (e.timestamp || "") + "  " + (e.verb || "?") + "  " + (e.resource || "");
                if (e.outcome && e.outcome !== "ok") line += "  [" + e.outcome + "]";
                lines.push(line);
            }
            return { text: lines.join("\\n"), events: entries };
        }
        """;

    // ─── Board — grants issued ──────────────────────────────────────

    /**
     * {@code look board} / {@code examine board} / {@code read board} — lists
     * the grants the owner has issued. Each grant is shown with subject,
     * resource, capability, and active/expired status.
     */
    public static ToolItem board() {
        return ToolItem.scripted(
            "board",
            "Board",
            "A wooden board mounted on the wall, pinned with small tokens. Each token is a grant "
            + "you've given — someone allowed to read a collection, a zone allowed to use your "
            + "inference budget, a companion allowed to perform an action on your behalf. Read it "
            + "to see what you've granted.",
            BOARD_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String BOARD_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The board is blank. (No Home is bound to this furnishing.)" };
            }
            var grants = world.grants.issued();
            var pending = world.grants.pendingRequests();
            var skillDrafts = world.skill.pendingDrafts();
            var hasGrants = grants && grants.length > 0;
            var hasPending = pending && pending.length > 0;
            var hasDrafts = skillDrafts && skillDrafts.length > 0;
            if (!hasGrants && !hasPending && !hasDrafts) {
                return { text: "The board is empty. You have not yet granted anything to anyone." };
            }
            var lines = [];
            if (hasDrafts) {
                lines.push("Pinned highest — " + skillDrafts.length
                    + " skill draft" + (skillDrafts.length === 1 ? "" : "s")
                    + " your companion proposed:");
                for (var i = 0; i < skillDrafts.length; i++) {
                    var d = skillDrafts[i];
                    var line = "  ⚒  " + (d.name || "?") + "  —  " + (d.description || "");
                    lines.push(line);
                    if (d.rationale) lines.push("     why: " + d.rationale);
                    lines.push("     id: " + d.draftId);
                }
                lines.push("");
                lines.push("  Respond at the workbench:  approve / reject / edit  (or via wyrd skill)");
                lines.push("");
            }
            if (hasPending) {
                lines.push("Pinned near the top — " + pending.length
                    + " request" + (pending.length === 1 ? "" : "s") + " awaiting your decision:");
                for (var i = 0; i < pending.length; i++) {
                    var p = pending[i];
                    var line = "  ✉  " + (p.requester || "?")
                        + "  →  " + (p.capability || "?") + " on " + (p.resource || "?");
                    if (p.reason) line += "  (\\"" + p.reason + "\\")";
                    lines.push(line);
                    lines.push("     id: " + p.id);
                }
                lines.push("");
                lines.push("  Respond with: approve <id>  |  deny <id>");
                lines.push("");
            }
            var active = 0;
            var expired = 0;
            if (hasGrants) {
                lines.push("Pinned to the board, your grants:");
                for (var i = 0; i < grants.length; i++) {
                    var g = grants[i];
                    var status = g.active ? "[active]" : (g.revokedAt ? "[revoked]" : "[expired]");
                    if (g.active) active++; else expired++;
                    var line = "  " + status + "  " + g.capability + "  " + g.resource + "  →  " + g.subject;
                    if (g.expiresAt) line += "  (until " + g.expiresAt + ")";
                    lines.push(line);
                }
                lines.push("");
                lines.push(active + " active, " + expired + " revoked or expired.");
            }
            return { text: lines.join("\\n"), grants: grants, pending: pending,
                skillDrafts: skillDrafts,
                active: active, inactive: expired,
                pendingCount: hasPending ? pending.length : 0,
                draftCount: hasDrafts ? skillDrafts.length : 0 };
        }
        """;

    // ─── Mailbox — grants held ──────────────────────────────────────

    /**
     * {@code look mailbox} / {@code read mailbox} — the inbox side of §18.
     * Lists every grant *issued to* the owner (by other users, agents, zones).
     * Counterpart to {@link #board()}, which shows what the owner has given out.
     */
    public static ToolItem mailbox() {
        return ToolItem.scripted(
            "mailbox",
            "Mailbox",
            "A brass-hinged mailbox on a shelf by the door. Each envelope is a grant someone has "
            + "given you — a collection they've shared, a zone that lets you use its inference, "
            + "a companion with a capability they've delegated. Read it to see what authority you "
            + "hold on other Homes.",
            MAILBOX_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String MAILBOX_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The mailbox is sealed. (No Home is bound to this furnishing.)" };
            }
            var grants = world.grants.held();
            if (!grants || grants.length === 0) {
                return { text: "The mailbox is empty. No one has yet granted you anything." };
            }
            var active = 0;
            var expired = 0;
            var lines = [];
            lines.push("In the mailbox, envelopes addressed to you:");
            for (var i = 0; i < grants.length; i++) {
                var g = grants[i];
                var status = g.active ? "[active]" : (g.revokedAt ? "[revoked]" : "[expired]");
                if (g.active) active++; else expired++;
                var line = "  " + status + "  " + g.capability + "  " + g.resource + "  ←  " + g.issuer;
                if (g.expiresAt) line += "  (until " + g.expiresAt + ")";
                lines.push(line);
            }
            lines.push("");
            lines.push(active + " active, " + expired + " revoked or expired.");
            return { text: lines.join("\\n"), grants: grants, active: active, inactive: expired };
        }
        """;

    // ─── Ledger — inference cost & budget ────────────────────────

    /**
     * {@code look ledger} / {@code read ledger} — resource-usage summary for
     * the owner: inference count, tokens, avg latency, daily budget.
     */
    /**
     * {@code look tool-warden} — the MCP capabilities the household may reach,
     * and which agents are permitted each. {@code use tool-warden op=grant
     * agent=<name|everyone> service=<id>} authorizes; {@code op=revoke} withdraws.
     * Steward-only: the grant admin refuses a non-steward actor. This is the UX
     * that makes {@code WYRDSEKAI_MCP_STRICT_GRANTS=true} (the secure default)
     * usable — configured services stay dark until the steward grants them.
     */
    public static ToolItem toolWarden() {
        return ToolItem.scripted(
            "tool-warden",
            "Tool Warden",
            "A ring of labelled brass keys by the study door — one per external tool the "
            + "household can reach into the world with (search, home controls, image-craft, "
            + "and the like). Each tag shows which of your companions you've handed that key "
            + "to. Read it to take stock; use it (op=grant | revoke, agent=<name|everyone>, "
            + "service=<id>) to hand out or take back a key.",
            TOOL_WARDEN_SCRIPT,
            List.of(
                new ToolParam("op", "string",
                    "Optional: grant | revoke. Omit to view.", false, List.of("grant", "revoke")),
                new ToolParam("agent", "string",
                    "For grant/revoke: an agent's name, or 'everyone' for all household agents.",
                    false, null),
                new ToolParam("service", "string",
                    "For grant/revoke: the MCP service id (e.g. searxng, home-assistant).",
                    false, null)
            ),
            "home-furnishing");
    }

    private static final String TOOL_WARDEN_SCRIPT = """
        function invoke(params) {
            var op = params && params.op ? String(params.op).toLowerCase() : null;
            if (op === "grant") {
                var r = world.mcp.grant(params.agent, params.service);
                if (r && r.ok) {
                    return { text: "Key handed over: " + r.subject + " may now use "
                        + r.service + ".", result: r };
                }
                return { text: "Could not grant that key: " + (r && r.error ? r.error : "unknown error"),
                    result: r };
            }
            if (op === "revoke") {
                var r2 = world.mcp.revoke(params.agent, params.service);
                if (r2 && r2.ok) {
                    return { text: "Key taken back: " + r2.subject + " can no longer use "
                        + r2.service + ".", result: r2 };
                }
                return { text: "Could not revoke that key: "
                    + (r2 && r2.error ? r2.error : "no matching grant"), result: r2 };
            }

            // ── View ──
            var services = world.mcp.services();
            var lines = [];
            lines.push("The Tool Warden — external tools this household can reach:");
            if (!services || services.length === 0) {
                lines.push("  (No MCP services are configured — or you are not the steward.)");
                lines.push("  Configure services in mcp-services.json in the data directory.");
            } else {
                for (var i = 0; i < services.length; i++) {
                    var s = services[i];
                    var flag = (s.enabled === false) ? " [disabled]" : "";
                    lines.push("  " + s.id + flag + "  →  keys held by: " + s.grantedText);
                }
                lines.push("");
                lines.push("Hand out a key:  use tool-warden op=grant agent=<name|everyone> service=<id>");
                lines.push("Take one back:   use tool-warden op=revoke agent=<name|everyone> service=<id>");
            }
            return { text: lines.join("\\n"), services: services };
        }
        """;

    public static ToolItem ledger() {
        return ToolItem.scripted(
            "ledger",
            "Ledger",
            "A leather-bound ledger resting open on the desk. Each line is a reckoning: "
            + "how much inference has run in your name today, how many tokens have flowed, "
            + "where the latency spent itself. Read it to see what your Home has consumed.",
            LEDGER_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String LEDGER_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The ledger pages are blank. (No Home is bound to this furnishing.)" };
            }
            var s = world.budget.summary();
            if (!s || !s.inferences) {
                return { text: "The ledger is fresh. No inference has yet been recorded in your Home." };
            }
            var lines = [];
            lines.push("In the ledger, your reckoning:");
            lines.push("  Inferences: " + s.inferences);
            if (s.mcpCalls) lines.push("  MCP calls:  " + s.mcpCalls);
            lines.push("  Tokens:     " + s.tokens);
            lines.push("  Avg latency: " + Math.round(s.avgLatencyMs || 0) + "ms");
            if (s.monetaryCost && s.monetaryCost > 0) {
                lines.push("  Cost today: $" + (Math.round(s.monetaryCost * 10000) / 10000));
            }
            if (s.budgetNote) lines.push("  ⚠ " + s.budgetNote);
            if (s.lastActivity) lines.push("  Last activity: " + s.lastActivity);
            return { text: lines.join("\\n"), summary: s };
        }
        """;

    // ─── Manifest — federation agreements ────────────────────────

    /**
     * {@code look manifest} / {@code read manifest} — the zone's federation
     * agreements. What each partner zone allows this zone, and vice versa.
     * Visible to any Home owner; the zone steward is expected to act on it.
     */
    public static ToolItem manifest() {
        return ToolItem.scripted(
            "manifest",
            "Manifest",
            "A shipping manifest tacked to the wall near the door. Each entry is a pact "
            + "with a neighbouring zone: who trusts whom, how much inference they'll share, "
            + "when the agreement expires. Read it to see your zone's standing in the wider world.",
            MANIFEST_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String MANIFEST_SCRIPT = """
        function invoke(params) {
            var agreements = world.federation.agreements();
            if (!agreements || agreements.length === 0) {
                return { text: "The manifest is clean. Your zone has no federation agreements on file." };
            }
            var lines = [];
            lines.push("Tacked to the manifest, agreements with other zones:");
            for (var i = 0; i < agreements.length; i++) {
                var a = agreements[i];
                var line = "  [" + (a.status || "?") + "]  " + (a.remoteZone || "?")
                    + "  (" + (a.trustLevel || "?") + ")";
                if (a.localQuotaDaily) line += "  you→them " + a.localQuotaDaily;
                if (a.remoteQuotaDaily) line += "  them→you " + a.remoteQuotaDaily;
                if (a.expiresAt) line += "  (until " + a.expiresAt + ")";
                lines.push(line);
            }
            lines.push("");
            lines.push(agreements.length + " agreement" + (agreements.length === 1 ? "" : "s") + " on record.");
            return { text: lines.join("\\n"), agreements: agreements };
        }
        """;

    // ─── Trunk — inventory snapshot ──────────────────────────────

    /**
     * {@code look trunk} / {@code open trunk} — everything the owner carries
     * in the current zone. Mirrors the inventory table but renders as a
     * single scannable list.
     */
    public static ToolItem trunk() {
        return ToolItem.scripted(
            "trunk",
            "Trunk",
            "A weathered travel trunk at the foot of the bed. Inside, neatly arranged, "
            + "is everything you carry: tools, tokens, keepsakes, whatever you've picked up "
            + "on your travels. Open it to take stock.",
            TRUNK_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String TRUNK_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The trunk is locked. (No Home is bound to this furnishing.)" };
            }
            var items = world.trunk.items();
            if (!items || items.length === 0) {
                return { text: "The trunk is empty. You carry nothing of note in this zone." };
            }
            var lines = [];
            lines.push("Inside the trunk, your belongings:");
            var scripted = 0;
            for (var i = 0; i < items.length; i++) {
                var it = items[i];
                var marks = [];
                if (it.scripted) { marks.push("scripted"); scripted++; }
                if (it.takeable === false) marks.push("fixed");
                var tag = marks.length > 0 ? "  [" + marks.join(", ") + "]" : "";
                lines.push("  " + (it.name || it.id) + tag);
                if (it.description) lines.push("    " + it.description);
            }
            lines.push("");
            lines.push(items.length + " item" + (items.length === 1 ? "" : "s")
                + (scripted > 0 ? ", " + scripted + " scripted" : "") + ".");
            return { text: lines.join("\\n"), items: items };
        }
        """;

    // ─── Shelf — bonds ──────────────────────────────────────────

    /**
     * {@code look shelf} / {@code read shelf} — the owner's bonds. Each bond
     * is shown with partner, depth level, interaction count, and active/scarred
     * status.
     */
    public static ToolItem shelf() {
        return ToolItem.scripted(
            "shelf",
            "Shelf",
            "A narrow shelf above the desk, holding small keepsakes — a stone from one friend, "
            + "a pressed leaf from another, a thimble, a paper crane. Each is a bond you keep. "
            + "Read it to recall who you are tied to and how deeply.",
            SHELF_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String SHELF_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The shelf is bare. (No Home is bound to this furnishing.)" };
            }
            var bonds = world.bonds.list();
            if (!bonds || bonds.length === 0) {
                return { text: "The shelf is empty. You hold no bonds yet." };
            }
            var active = 0;
            var scarred = 0;
            var lines = [];
            lines.push("On the shelf, your keepsakes:");
            for (var i = 0; i < bonds.length; i++) {
                var b = bonds[i];
                var status = (b.active === false)
                    ? (b.scarred ? "[scarred]" : "[severed]")
                    : "[" + (b.depth || "?") + "]";
                if (b.active !== false) active++;
                if (b.scarred) scarred++;
                var line = "  " + status + "  ↔  " + (b.partner || "?")
                    + "  (" + (b.interactionCount || 0) + " interactions)";
                lines.push(line);
            }
            lines.push("");
            lines.push(active + " active"
                + (scarred > 0 ? ", " + scarred + " scarred" : "") + ".");
            return { text: lines.join("\\n"), bonds: bonds, active: active, scarred: scarred };
        }
        """;

    // ─── Lantern — presence ─────────────────────────────────────

    /**
     * {@code look lantern} — who is currently in the owner's Home room.
     * The lantern's warmth tells you whether anyone else is present.
     */
    public static ToolItem lantern() {
        return ToolItem.scripted(
            "lantern",
            "Lantern",
            "A small oil lantern on a hook by the door. Its flame steadies when you are alone "
            + "and flutters when others share the room with you. Look at it to see who is here.",
            LANTERN_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String LANTERN_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The lantern is dark. (No Home is bound to this furnishing.)" };
            }
            var present = world.presence.inHome();
            if (!present || present.length === 0) {
                return { text: "The lantern burns steady. You are alone in your Home." };
            }
            var others = [];
            for (var i = 0; i < present.length; i++) {
                var p = present[i];
                if (p.entityId && p.entityId !== who) others.push(p);
            }
            if (others.length === 0) {
                return { text: "The lantern burns steady. You are alone in your Home." };
            }
            var lines = [];
            lines.push("The lantern flutters. Present in your Home:");
            for (var i = 0; i < others.length; i++) {
                var p = others[i];
                lines.push("  " + (p.name || p.entityId) + (p.type ? "  (" + p.type + ")" : ""));
            }
            return { text: lines.join("\\n"), present: others };
        }
        """;

    // ─── Mirror — self snapshot ─────────────────────────────────

    /**
     * {@code look mirror} / {@code read mirror} — a compact self-snapshot:
     * DID, audit count, grants out, grants in, inventory size, bonds held.
     * All data aggregated from other world.* APIs.
     */
    public static ToolItem mirror() {
        return ToolItem.scripted(
            "mirror",
            "Mirror",
            "A hand mirror on the vanity. When you look into it you see not just your face but "
            + "your presence in this world — your name, your holdings, your reach.",
            MIRROR_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String MIRROR_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The mirror reflects only shadow. (No Home bound.)" };
            }
            var audit = world.audit.recent(5);
            var issued = world.grants.issued();
            var held = world.grants.held();
            var items = world.trunk.items();
            var bonds = world.bonds.list();
            var budget = world.budget.summary();

            var lines = [];
            lines.push("In the mirror, your reflection:");
            lines.push("  Identity:   " + who);
            lines.push("  Zone:       " + world.zone.current());
            lines.push("  Grants out: " + (issued ? issued.length : 0));
            lines.push("  Grants in:  " + (held ? held.length : 0));
            lines.push("  Inventory:  " + (items ? items.length : 0) + " item(s)");
            lines.push("  Bonds:      " + (bonds ? bonds.length : 0));
            if (budget && budget.inferences) {
                lines.push("  Inferences: " + budget.inferences + "  ("
                    + (budget.tokens || 0) + " tokens)");
            }
            lines.push("  Recent activity: " + (audit ? audit.length : 0) + " audit entries");
            return {
                text: lines.join("\\n"),
                identity: who,
                zone: world.zone.current(),
                grantsIssued: issued ? issued.length : 0,
                grantsHeld: held ? held.length : 0,
                inventory: items ? items.length : 0,
                bonds: bonds ? bonds.length : 0,
                budget: budget
            };
        }
        """;

    // ─── Compass — notification routing ─────────────────────────

    /**
     * {@code look compass} — which notification channels are configured and
     * in which direction they point (SSH, phone, desktop, etc.).
     */
    public static ToolItem compass() {
        return ToolItem.scripted(
            "compass",
            "Compass",
            "A brass compass on a small stand. Instead of north, its needle points toward the "
            + "channels through which your Home reaches you — phone, desktop, voice, SSH.",
            COMPASS_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String COMPASS_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The compass spins wildly. (No Home is bound.)" };
            }
            var channels = world.notifications.channels();
            if (!channels || channels.length === 0) {
                return { text: "The compass needle is still. No notification channels configured." };
            }
            var enabled = 0;
            var lines = [];
            lines.push("The compass points to:");
            for (var i = 0; i < channels.length; i++) {
                var c = channels[i];
                var mark = c.enabled ? "●" : "○";
                if (c.enabled) enabled++;
                var line = "  " + mark + "  " + (c.channel || "?");
                if (c.destination) line += "  →  " + c.destination;
                lines.push(line);
            }
            lines.push("");
            lines.push(enabled + " channel(s) enabled, " + (channels.length - enabled) + " silent.");
            return { text: lines.join("\\n"), channels: channels, enabled: enabled };
        }
        """;

    // ─── Window — bonded whereabouts ────────────────────────────

    /**
     * {@code look window} — the rooms/zones your bonded parties are in right
     * now. Honors their visibility choices: you see only what they've chosen
     * to share with bondholders.
     */
    public static ToolItem window() {
        return ToolItem.scripted(
            "window",
            "Window",
            "A round window above the desk, facing nowhere in particular. Through it you can see "
            + "faint outlines of those you are bonded to — where they are, whether they are awake, "
            + "whether they carry anything pressing.",
            WINDOW_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    // ─── Threshold — pairing knocks ──────────────────────────────────

    /**
     * {@code look threshold} / {@code read threshold} — admin surface for the
     * LAN-discovery + {@code /api/pair/*} flow. Lists devices/nodes currently
     * waiting at the threshold with the active 6-digit code, and (steward-only)
     * the pre-shared household key. Approval still flows through the existing
     * {@link org.wyrdsekai.core.persistence.PairingService} verify-by-code, but
     * the steward can read the code from this furnishing instead of grepping
     * the server log. Use {@code use threshold key=rotate} to regenerate the
     * household key.
     */
    public static ToolItem threshold() {
        return ToolItem.scripted(
            "threshold",
            "Threshold",
            "A worn stone threshold at the front of the Study. When a device or node knocks "
            + "at your household's door, the threshold remembers it — name, kind, the six-digit "
            + "code they need to step inside. Read it to see who is waiting. Steward-only: the "
            + "pre-shared household key for headless pairing rests here too.",
            THRESHOLD_SCRIPT,
            List.of(
                new ToolParam("key", "string",
                    "Pass 'rotate' to (re)generate the pre-shared household key. Steward-only.",
                    false, null)
            ),
            "home-furnishing");
    }

    private static final String THRESHOLD_SCRIPT = """
        function invoke(params) {
            var rotate = params && (params.key === "rotate" || params.rotate === true);
            if (rotate) {
                var fresh = world.pairing.generateHouseholdKey();
                if (!fresh) {
                    return { text: "The threshold does not respond. (Pairing service unavailable.)" };
                }
                return {
                    text: "A new household key has been carved into the threshold:\\n  " + fresh
                        + "\\n\\nShare it only with hosts you trust. The previous key, if any, remains valid until revoked.",
                    householdKey: fresh,
                    rotated: true
                };
            }
            var pending = world.pairing.pending();
            var code = world.pairing.code();
            var key = world.pairing.householdKey();
            var hasPending = pending && pending.length > 0;
            var lines = [];
            if (!hasPending && !code && !key) {
                return { text: "The threshold rests quietly. No one is waiting at your door, and no pre-shared key has been carved into the stone." };
            }
            if (hasPending) {
                lines.push("Waiting at the threshold:");
                for (var i = 0; i < pending.length; i++) {
                    var p = pending[i];
                    var name = p.deviceName ? p.deviceName : "(unnamed device)";
                    var kind = p.deviceType ? "  [" + p.deviceType + "]" : "";
                    lines.push("  ✦  " + name + kind);
                    if (p.code) lines.push("       code: " + p.code);
                    if (p.expiresAt) lines.push("       expires: " + p.expiresAt);
                    lines.push("       challenge: " + p.challengeId);
                }
                lines.push("");
                lines.push("  Approve by reading them the code; they enter on /api/pair/verify.");
            } else if (code) {
                lines.push("An active pairing code rests on the threshold:");
                lines.push("  " + code);
                lines.push("");
                lines.push("  Nothing is currently knocking — the code is reserved for the next caller.");
            }
            if (key) {
                if (lines.length > 0) lines.push("");
                lines.push("Carved into the stone — the household key (steward-only):");
                lines.push("  " + key);
                lines.push("");
                lines.push("  Use this for headless pairing:  wyrdsekai join --key " + key);
            } else if (!hasPending && !code) {
                // already returned above
            } else {
                lines.push("");
                lines.push("  No household key has been carved yet. Use 'use threshold key=rotate' to mint one.");
            }
            return {
                text: lines.join("\\n"),
                pending: pending,
                code: code,
                householdKey: key,
                pendingCount: hasPending ? pending.length : 0
            };
        }
        """;

    // ─── Coding Slate — coding-backend status ──────────────────────────

    /**
     * {@code look slate} / {@code examine slate} / {@code read slate} —
     * shows the configured coding backends, their tier, health, last-task
     * summary, and 30-day success rate.
     *
     * <p>ASCII rendering is deliberate: the slate must be readable over
     * SSH/telnet (§9.7 parity). The structured data is also returned so
     * web/KMP/RN clients can render the same information richly.</p>
     */
    public static ToolItem codingSlate() {
        return ToolItem.scripted(
            "coding-slate",
            "Coding Slate",
            "A dark slate mounted next to the workbench. Each row is a coding "
            + "backend you've made available — CodePlane, Aider, OpenHands, "
            + "Claude Code, Codex CLI — with its tier, health glyph, last task, "
            + "and 30-day success rate. Read it to see what's working and what isn't.",
            CODING_SLATE_SCRIPT,
            List.of(
                new ToolParam("verbose", "boolean",
                    "If true, include backend tier and last-task details for each row.",
                    false, null)
            ),
            "home-furnishing");
    }

    private static final String CODING_SLATE_SCRIPT = """
        function invoke(params) {
            var verbose = params && params.verbose === true;
            var rows = world.coding.backends();
            if (!rows || rows.length === 0) {
                return {
                    text: "The slate is blank. No coding backends are configured.\\n"
+ " ( — edit "
                        + "wyrdsekai.coding.backends in your Scroll of Settings.)",
                    backends: []
                };
            }
            var lines = ["Etched into the slate, your coding backends:"];
            var enabled = 0;
            var healthy = 0;
            for (var i = 0; i < rows.length; i++) {
                var r = rows[i];
                var glyph = r.healthy ? "\\u2713" : (r.enabled ? "\\u00b7" : "\\u00d7");
                if (r.enabled) enabled++;
                if (r.healthy) healthy++;
                var line = "  " + glyph + "  " + pad(r.name, 14);
                if (verbose) {
                    line += "  [" + (r.tier || "?") + "]";
                }
                if (r.lastTask && r.lastTask.summary) {
                    line += "  last: " + r.lastTask.summary;
                } else {
                    line += "  last: \\u2014";
                }
                if (r.successRate30d !== null && r.successRate30d !== undefined) {
                    line += "  30d: " + Math.round(r.successRate30d * 100) + "%";
                } else {
                    line += "  30d: \\u2014";
                }
                lines.push(line);
            }
            lines.push("");
            lines.push("\\u2713 healthy   \\u00b7 configured but not responding   \\u00d7 disabled");
            lines.push(enabled + " enabled, " + healthy + " healthy.");
            return {
                text: lines.join("\\n"),
                backends: rows,
                enabled: enabled,
                healthy: healthy
            };
        }

        function pad(s, n) {
            if (!s) s = "";
            while (s.length < n) s = s + " ";
            return s;
        }
        """;

    // ─── Companion Codex — companion roster + configuration map ──────

    /**
     * {@code look codex} / {@code read codex} — one page per companion bound
     * to this hearth (a household can keep more than one). Shows each
     * companion's name, identity, temperament label, voice-profile state,
     * relationships, and where they are right now, plus pointers to the
     * surfaces that actually change them: {@code rename <companion> <name>}
     * for the name, the Voice Mirror for register, the Scroll of Settings
     * for runtime knobs. Drives and autonomy deliberately live in the
     * companion's own Hearth — the codex shows, it does not edit.
     */
    public static ToolItem codex() {
        return ToolItem.scripted(
            "codex",
            "Companion Codex",
            "A heavy book on a reading stand, bound in worn leather. Each companion who "
            + "lives in this household has a page: who they are, how they sound, who they "
            + "are tied to, where they are right now. Read it to know your companions — "
            + "and to find the surfaces that shape them.",
            CODEX_SCRIPT,
            List.of(),
            "home-furnishing");
    }

    private static final String CODEX_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The codex is clasped shut. (No Home is bound to this furnishing.)" };
            }
            var companions = world.companions.list();
            if (!companions || companions.length === 0) {
                return { text: "The codex pages are blank. No companion soul has been born in this zone yet." };
            }
            var lines = [];
            lines.push("The Companion Codex — " + companions.length + " companion"
                + (companions.length === 1 ? "" : "s") + " of this household:");
            for (var i = 0; i < companions.length; i++) {
                var c = companions[i];
                lines.push("");
                lines.push("  \\u25c6 " + (c.name || "?") + "  (" + (c.entityId || "?") + ")");
                if (c.did) lines.push("      identity:      " + c.did);
                if (c.temperament) lines.push("      temperament:   " + c.temperament
                    + "  (nearest preset ~ distance; a label, not a verdict)");
                if (c.voiceRevision !== undefined && c.voiceRevision !== null) {
                    var v = "rev " + c.voiceRevision + ", " + (c.voiceClauses || 0) + " clause"
                        + (c.voiceClauses === 1 ? "" : "s");
                    if (c.voiceFrozen) v += "  [frozen]";
                    lines.push("      voice:         " + v);
                }
                lines.push("      relationships: " + (c.relationships || 0));
                if (c.online) {
                    lines.push("      present:       " + (c.room || "somewhere in the zone"));
                } else {
                    lines.push("      present:       not currently in the world");
                }
                if (c.forgedAt) lines.push("      born:          " + c.forgedAt);
            }
            lines.push("");
            lines.push("To shape a companion:");
            lines.push("  name   \\u2014  rename <companion> <new-name>   (they answer to it everywhere)");
            lines.push("  voice  \\u2014  the Voice Mirror on the vanity");
            lines.push("  knobs  \\u2014  the Scroll of Settings");
            lines.push("Their drives and autonomy live in their own Hearth \\u2014 theirs to keep.");
            return { text: lines.join("\\n"), companions: companions };
        }
        """;

    // ─── Warden — relay governance ────────

    /**
     * {@code look warden} / {@code read warden} — the in-world surface for
     * managing a relay this zone owns or has been granted admin on. Shows the
     * relay's registrations (DID + petname, tier, LIVE, last_seen), the
     * relay-admin delegations this zone holds out, and policy (stub for P4),
     * with the actions the caller's grant scope permits.
     *
     * <p>Actions run via {@code use warden action=<op> ...}:
     * <ul>
     *   <li>{@code action=invite [ttl=N]}</li>
     *   <li>{@code action=remove pubkey=<nkey>}</li>
     *   <li>{@code action=grant-admin did=<did> scope=<invite-only|moderation|full>}</li>
     *   <li>{@code action=revoke-admin did=<did>}</li>
     *   <li>{@code action=reports} — list the open abuse reports (§8)</li>
     *   <li>{@code action=resolve report=<id> verdict=<dismiss|noted|removed>}</li>
     * </ul>
     * Each is gated zone-side by {@code RelayGovernance.authorize} (and again
     * relay-side by the signed endpoint). The reports queue (P6) reads via
     * {@code report-queue} and resolves via {@code resolve-report}, both
     * moderation-scoped. Remaining P5+ ops (promote/demote/set-mode/set-policy)
     * are shown as "coming later" and not invocable here.</p>
     *
     * <p>ASCII rendering is deliberate for SSH/telnet parity; structured data
     * is also returned so web/KMP/RN clients can render richly.</p>
     */
    public static ToolItem warden() {
        return ToolItem.scripted(
            "warden",
            "Warden",
            "A heavy iron key-ring on a hook by the door, hung with one tag per relay you "
            + "keep watch over. Each tag lists who is registered there, the abuse reports "
            + "awaiting you, who you've trusted to help moderate, and what you're allowed to "
            + "do. Read it to take stock; use it (action=invite | remove | grant-admin | "
            + "revoke-admin | reports | resolve) to govern the relay.",
            WARDEN_SCRIPT,
            List.of(
                new ToolParam("action", "string",
                    "Optional: invite | remove | grant-admin | revoke-admin | reports | resolve. Omit to view.",
                    false, List.of("invite", "remove", "grant-admin",
                        "revoke-admin", "reports", "resolve")),
                new ToolParam("pubkey", "string",
                    "For action=remove: the NATS pubkey of the registration to kick.",
                    false, null),
                new ToolParam("did", "string",
                    "For grant-admin/revoke-admin: the subject did:key: to (de)authorize.",
                    false, null),
                new ToolParam("scope", "string",
                    "For grant-admin: invite-only | moderation | full.",
                    false, List.of("invite-only", "moderation", "full")),
                new ToolParam("ttl", "integer",
                    "For action=invite: invite lifetime in seconds (optional).",
                    false, null),
                new ToolParam("report", "string",
                    "For action=resolve: the report id (rpt-...) to resolve.",
                    false, null),
                new ToolParam("verdict", "string",
                    "For action=resolve: dismiss | noted | removed.",
                    false, List.of("dismiss", "noted", "removed"))
            ),
            "home-furnishing");
    }

    private static final String WARDEN_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The key-ring hangs cold. (No Home is bound to this furnishing.)" };
            }
            var info = world.relay.info();
            if (!info || info.configured !== true) {
                return { text: "The key-ring holds no tags. This zone does not administer any relay.\\n"
+ " (Deploy or claim one: 4b / `wyrd relay claim`.)"
                    configured: false };
            }
            var scope = info.scope; // owner|full|moderation|invite-only|null
            if (!scope) {
                return { text: "The key-ring is here, but none of its tags answer to you.\\n"
                    + "  You hold no relay-admin grant on " + (info.relayLabel || info.relayDid) + ".",
                    configured: true, scope: null };
            }

            var action = params && params.action ? String(params.action).toLowerCase() : null;
            if (action) {
                return runAction(action, params, scope, info);
            }

            // ── View ──
            var lines = [];
            lines.push("Warden of relay " + (info.relayLabel || info.relayDid) + ":");
            lines.push("  your role: " + scope + (info.canDelegate ? "  (may delegate)" : ""));
            lines.push("");

            var canModerate = (scope === "owner" || scope === "full" || scope === "moderation");
            if (canModerate) {
                var regs = world.relay.registrations();
                if (!regs || regs.length === 0) {
                    lines.push("Registrations: none on record (or the relay is unreachable).");
                } else {
                    lines.push("Registrations (" + regs.length + "):");
                    for (var i = 0; i < regs.length; i++) {
                        var r = regs[i];
                        var name = r.petname || r.did || r.pubkey || "?";
                        var live = (r.active === false) ? "[down]" : "[LIVE]";
                        var tier = r.tier ? ("  tier=" + r.tier) : "";
                        var seen = r.last_seen ? ("  seen=" + r.last_seen) : "";
                        lines.push("  " + live + "  " + name + tier + seen);
                        if (r.pubkey) lines.push("        pubkey: " + r.pubkey);
                    }
                }
            } else {
                lines.push("Registrations: (hidden \\u2014 needs moderation scope or higher.)");
            }
            lines.push("");

// Reports queue — moderation+ only.
            if (canModerate) {
                var rq = world.relay.reportQueue(false);
                var reps = (rq && rq.reports) ? rq.reports : [];
                if (!reps || reps.length === 0) {
                    lines.push("Reports queue: empty (no open abuse reports).");
                } else {
                    lines.push("Reports queue (" + reps.length + " open):");
                    for (var k = 0; k < reps.length; k++) {
                        var rp = reps[k];
                        var subj = rp.subject_present === false
                            ? (didShort(rp.subject_did) + " (left)")
                            : didShort(rp.subject_did);
                        lines.push("  [" + rp.id + "] " + subj
                            + "  by " + didShort(rp.reporter_did)
                            + "  (" + ageOf(rp.created_at) + ")");
                        if (rp.reason) lines.push("        reason: " + rp.reason);
                    }
                }
            } else {
                lines.push("Reports: (hidden \\u2014 needs moderation scope or higher.)");
            }
            lines.push("");

            // Delegations (owner / full see who else holds admin).
            var dels = world.relay.delegations();
            if (dels && dels.length > 0) {
                lines.push("Relay-admin delegations you've granted (" + dels.length + "):");
                for (var j = 0; j < dels.length; j++) {
                    var d = dels[j];
                    var dscope = (d.scope && d.scope["relay-scope"]) ? d.scope["relay-scope"] : "?";
                    var st = d.active ? "[active]" : "[revoked/expired]";
                    lines.push("  " + st + "  " + dscope + "  ->  " + (d.subject || "?"));
                }
                lines.push("");
            }

            // Policy (stub for P4).
            lines.push("Policy: mode/tier rules \\u2014 coming in P5.");
            lines.push("");

            // Available actions, filtered by scope.
            lines.push("You can:");
            // invite: invite-only and up.
            lines.push("  - use warden action=invite [ttl=<seconds>]");
            if (canModerate) {
                lines.push("  - use warden action=remove pubkey=<nkey>            (kick)");
                lines.push("  - use warden action=reports                          (list open reports)");
                lines.push("  - use warden action=resolve report=<id> verdict=<dismiss|noted|removed>");
            }
            if (scope === "owner" || scope === "full") {
                lines.push("  - use warden action=grant-admin did=<did> scope=<invite-only|moderation|full>");
                lines.push("  - use warden action=revoke-admin did=<did>");
            }
            lines.push("Coming later: promote / demote / set-mode / set-policy.");

            return {
                text: lines.join("\\n"),
                configured: true,
                relayDid: info.relayDid,
                relayLabel: info.relayLabel,
                scope: scope,
                canDelegate: info.canDelegate,
                registrations: canModerate ? world.relay.registrations() : [],
                delegations: dels || []
            };
        }

        function runAction(action, params, scope, info) {
            var res;
            if (action === "invite") {
                var opts = {};
                if (params.ttl) opts.ttl = parseInt(params.ttl, 10);
                res = world.relay.invite(opts);
                return render(res, "invite", info);
            }
            if (action === "remove" || action === "kick") {
                if (!params.pubkey) {
                    return { text: "remove needs pubkey=<nkey>. (Read the warden to see registrations.)" };
                }
                res = world.relay.remove(String(params.pubkey));
                return render(res, "remove", info);
            }
            if (action === "grant-admin") {
                if (!params.did || !params.scope) {
                    return { text: "grant-admin needs did=<did:key:...> and scope=<invite-only|moderation|full>." };
                }
                res = world.relay.grantAdmin(String(params.did), String(params.scope));
                return render(res, "grant-admin", info);
            }
            if (action === "revoke-admin") {
                if (!params.did) {
                    return { text: "revoke-admin needs did=<did:key:...>." };
                }
                res = world.relay.revokeAdmin(String(params.did));
                return render(res, "revoke-admin", info);
            }
            if (action === "reports" || action === "report-queue") {
                var inc = params && (params.all === "true" || params.all === true
                    || params.include_resolved === "true");
                res = world.relay.reportQueue(!!inc);
                if (res && (res.ok === false || (res.status && res.status !== 200))) {
                    return render(res, "report-queue", info);
                }
                var reps = (res && res.reports) ? res.reports : [];
                var rlines = [];
                if (reps.length === 0) {
                    rlines.push("Reports queue: empty (no open abuse reports).");
                } else {
                    rlines.push("Reports queue (" + reps.length + (inc ? " total" : " open") + "):");
                    for (var i2 = 0; i2 < reps.length; i2++) {
                        var rp = reps[i2];
                        var subj = rp.subject_present === false
                            ? (didShort(rp.subject_did) + " (left)") : didShort(rp.subject_did);
                        var st = rp.status === "resolved"
                            ? ("  [resolved: " + (rp.resolution || "?") + "]") : "";
                        rlines.push("  [" + rp.id + "] " + subj + "  by "
                            + didShort(rp.reporter_did) + "  (" + ageOf(rp.created_at) + ")" + st);
                        if (rp.reason) rlines.push("        reason: " + rp.reason);
                    }
                    rlines.push("");
                    rlines.push("Resolve one: use warden action=resolve report=<id> verdict=<dismiss|noted|removed>");
                }
                return { text: rlines.join("\\n"), ok: true, reports: reps,
                    open_count: res ? res.open_count : 0 };
            }
            if (action === "resolve" || action === "resolve-report") {
                var rid = params.report || params.report_id || params.id;
                var verdict = params.verdict || params.action_verdict;
                if (!rid || !verdict) {
                    return { text: "resolve needs report=<id> and verdict=<dismiss|noted|removed>." };
                }
                res = world.relay.resolveReport(String(rid), String(verdict));
                return render(res, "resolve-report", info);
            }
            if (action === "promote" || action === "demote" || action === "set-mode"
                || action === "set-policy") {
                return { text: "That action (" + action + ") arrives later \\u2014 trust-tier "
                    + "promotion/demotion and mode/policy edits are not yet wired into the Warden.",
                    placeholder: true, action: action };
            }
            return { text: "Unknown warden action: " + action
                + "  (invite | remove | grant-admin | revoke-admin | reports | resolve)." };
        }

        // DID-prefix display (matches the impl's didShort); the relay surfaces
        // full DIDs but the table shows a stable prefix.
        function didShort(did) {
            if (!did) return "(unknown)";
            var body = (did.indexOf("did:key:") === 0) ? did.substring(8) : did;
            return body.length <= 12 ? did : (body.substring(0, 12) + "\\u2026");
        }

        // Rough human age of an ISO-8601 timestamp (created_at), best-effort.
        function ageOf(iso) {
            if (!iso) return "?";
            var then = Date.parse(iso);
            if (isNaN(then)) return iso;
            var secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
            if (secs < 90) return secs + "s ago";
            var mins = Math.floor(secs / 60);
            if (mins < 90) return mins + "m ago";
            var hrs = Math.floor(mins / 60);
            if (hrs < 48) return hrs + "h ago";
            return Math.floor(hrs / 24) + "d ago";
        }

        function render(res, op, info) {
            if (!res) {
                return { text: "The warden's call returned nothing." , ok: false };
            }
            if (res.ok === true || res.status === 200) {
                var msg = op + " succeeded on " + (info.relayLabel || info.relayDid) + ".";
                if (op === "invite" && res.invite_url) {
                    msg += "\\n  invite: " + res.invite_url;
                    if (res.join_code) msg += "\\n  join code: " + res.join_code;
                }
                if (op === "remove" && res.status) msg += "\\n  " + (res.status);
                if (op === "resolve-report") {
                    msg = "Report " + (res.report_id || "?") + " resolved as '"
                        + (res.resolution || "?") + "'.";
                    if (res.status === "already_resolved") {
                        msg = "Report " + (res.report_id || "?")
                            + " was already resolved as '"
                            + (res.report && res.report.resolution ? res.report.resolution : "?") + "'.";
                    }
                }
                return { text: msg, ok: true, result: res };
            }
            var err = res.error || res.warn || ("HTTP " + (res.status || "?"));
            return { text: "The warden refused " + op + ": " + err, ok: false, result: res };
        }
        """;

    private static final String WINDOW_SCRIPT = """
        function invoke(params) {
            var who = world.home.callerDid();
            if (!who) {
                return { text: "The window is fogged. (No Home is bound.)" };
            }
            var bonds = world.bonds.list();
            if (!bonds || bonds.length === 0) {
                return { text: "The window looks out on mist. You have no bonds whose paths to watch." };
            }
            var lines = [];
            lines.push("Through the window, your bonded parties:");
            var seen = 0;
            for (var i = 0; i < bonds.length; i++) {
                var b = bonds[i];
                if (b.active === false) continue;
                var where = b.currentZone
                    ? b.currentZone + (b.currentRoom ? "/" + b.currentRoom : "")
                    : "unseen";
                lines.push("  " + (b.partner || "?") + "  —  " + where
                    + (b.awake === false ? "  [asleep]" : ""));
                seen++;
            }
            if (seen === 0) {
                return { text: "Through the window, only mist. Your bonded parties hide themselves from view." };
            }
            return { text: lines.join("\\n"), seen: seen };
        }
        """;
}
