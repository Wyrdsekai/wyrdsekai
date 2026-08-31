package org.wyrdsekai.scripting.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * schema + tier validation for {@link ItemManifest}.
 *
 * <p>Validation is split into two passes: structural (required-field shape,
 * regex on name, semver, DID format) and semantic (every declared cap exists
 * in {@link #KNOWN_CAPABILITIES}; Tier 5+ caps without rate_limits get rejected;
 * etc).</p>
 *
 * <p>Tier inference is derived from a static map keyed by capability name —
 * authors don't need to think in tiers. The validator then applies floor
 * invariants (e.g. {@code web.post} declared without {@code rate_limits}
 * rejects).</p>
 */
public final class ItemManifestValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern SEMVER_PATTERN =
        Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?$");
    private static final Pattern DID_PATTERN = Pattern.compile("^did:[a-z0-9]+:.+$");

    /** Capabilities that always require a {@code rate_limits} entry. */
    private static final Set<String> RATE_LIMIT_REQUIRED = Set.of(
        "web.post", "web.put", "web.delete", "web.fetch_raw",
        "mcp.invoke",
        "agent.mailbox.send", "agent.broadcast",
        // credentialed reach is rate-limited by contract.
        "net.ssh", "net.scp", "net.household"
    );

    /**
     * The full known-capability catalogue — derived from §4 of
     * Each entry maps a dotted cap name to its
     * default tier (1-7).
     *
     * <p>Wildcards are encoded via the trailing {@code .*} sentinel: an item
     * declaring {@code github.*} validates against {@code github.*}; runtime
     * gating still uses the wildcard. Concrete cap-by-cap entries are
     * preserved for the most common surfaces so wildcards are an
     * opt-in convenience rather than the default.</p>
     */
    public static final Map<String, Integer> KNOWN_CAPABILITIES = buildCatalogue();

    private ItemManifestValidator() {}

    /** Validation result: list of error messages; empty list means valid. */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean valid() { return errors.isEmpty(); }
        public static ValidationResult ok() { return new ValidationResult(List.of(), List.of()); }
    }

    /** Validate a manifest — never throws; returns the structured result. */
    /**
     * The manifest rules, in the words an author needs, rendered FROM the patterns this
     * class enforces.
     *
     * <h2>Why generated rather than written</h2>
     * The items-as-tools contract never stated any of this. On 2026-08-22 an authoring
     * model named an item {@code web-sight}; the loader rejected it
     * ({@code name must match [a-z][a-z0-9_]{2,63}}) and the tool the steward asked for
     * did not exist. Nothing in what the model was given could have told it. Prose copied
     * out of here would drift the first time a rule changed, so the contract reads these.
     */
    public static List<String> rules() {
        return List.of(
            "name: " + NAME_PATTERN.pattern()
                + " — lowercase letters, digits and UNDERSCORES only. No hyphens, no capitals.",
            "version: " + SEMVER_PATTERN.pattern() + " — semver, e.g. \"1.0.0\".",
            "author: " + DID_PATTERN.pattern() + " — a DID, e.g. \"did:wyrd:openhands\".");
    }

    public static ValidationResult validate(ItemManifest manifest) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        if (manifest == null) {
            errors.add("manifest is null");
            return new ValidationResult(errors, warnings);
        }

        // Required shape
        if (manifest.name() == null || !NAME_PATTERN.matcher(manifest.name()).matches()) {
            errors.add("name must match [a-z][a-z0-9_]{2,63}: '" + manifest.name() + "'");
        }
        if (manifest.version() == null || !SEMVER_PATTERN.matcher(manifest.version()).matches()) {
            errors.add("version must be semver: '" + manifest.version() + "'");
        }
        if (manifest.description() == null || manifest.description().isBlank()
                || manifest.description().length() > 500) {
            errors.add("description must be 1..500 chars");
        }
        if (manifest.author() == null || !DID_PATTERN.matcher(manifest.author()).matches()) {
            errors.add("author must be DID format: '" + manifest.author() + "'");
        }

        // Capabilities
        int maxTier = 1;
        for (var cap : manifest.capabilities()) {
            if (cap == null || cap.isBlank()) {
                errors.add("blank capability entry");
                continue;
            }
            if (!isKnownCapability(cap)) {
                errors.add("unknown capability: '" + cap + "'");
                continue;
            }
            int tier = tierFor(cap);
            if (tier > maxTier) maxTier = tier;

            // Tier 4+ recommends rate_limits; Tier 5 mandates for sensitive verbs
            if (RATE_LIMIT_REQUIRED.contains(cap)
                    && manifest.rateLimitFor(cap) == null) {
                errors.add("capability '" + cap + "' requires a rate_limits entry "
                    + "— a manifest field, not a runtime check in invoke(): add to "
                    + "exports.manifest e.g. rate_limits: { \"" + cap
                    + "\": { per_minute: 10, per_hour: 60, per_day: 200 } }");
            }
        }

        // §5.2 — domain allowlist required for raw web writes
        boolean wantsRawWeb = manifest.capabilities().contains("web.post")
            || manifest.capabilities().contains("web.put")
            || manifest.capabilities().contains("web.delete")
            || manifest.capabilities().contains("web.fetch_raw");
        if (wantsRawWeb && manifest.externalDomains().isEmpty()) {
            errors.add("web.post/put/delete/fetch_raw requires external_domains allowlist "
                + "— a manifest field, not a runtime check in invoke(): add to "
                + "exports.manifest e.g. external_domains: [\"example.com\"] listing "
                + "every domain the item touches");
        }
        if (manifest.capabilities().contains("mcp.invoke") && manifest.mcpServers().isEmpty()) {
            errors.add("mcp.invoke requires mcp_servers allowlist "
                + "— a manifest field: add to exports.manifest e.g. "
                + "mcp_servers: [\"server-id\"] naming each MCP server the item calls");
        }
        boolean wantsSafe = manifest.capabilities().stream().anyMatch(
            c -> c.equals("safe.get") || c.equals("safe.set") || c.equals("safe.delete"));
        if (wantsSafe && manifest.safeSlots().isEmpty()) {
            errors.add("safe.get/set/delete requires safe_slots allowlist "
                + "— a manifest field: add to exports.manifest e.g. "
                + "safe_slots: [\"slot-name\"] naming each credential slot the item reads");
        }

        // Items-as-tools contract — commands structure. Presence is gated
        // separately by {@link #requireCommands} (boot shims, register rejects,
        // mirroring the §18 embodiment rollout), but when a commands list IS
        // declared every entry must carry a non-blank label. args may be empty
        // (a no-arg default invoke).
        int cmdIdx = 0;
        for (var cmd : manifest.commands()) {
            if (cmd == null || cmd.label() == null || cmd.label().isBlank()) {
                errors.add("commands[" + cmdIdx + "] requires a non-blank label");
            }
            cmdIdx++;
        }
        if (manifest.commands().isEmpty()) {
            warnings.add("manifest declares no commands — boot load will shim a "
                + "default 'Use <name>' entry; register/hot-reload will REJECT");
        }

        // Sensitivity warnings (soft only)
        if ("low".equals(manifest.dataSensitivity()) && maxTier >= 5) {
            warnings.add("data_sensitivity=low but item declares Tier 5+ capabilities");
        }

        return new ValidationResult(errors, warnings);
    }

    /**
     * fail-fast embodiment-block check.
     *
     * <p>Every scripted item MUST declare an {@code embodiment} block in its
     * manifest. Silence must be a *declared* choice, not a default. Two valid
     * shapes:
     * <ul>
     *   <li>{@code { "silent": true, "reason": "&lt;justification&gt;" }} — the
     *       item produces no body events (must say why).</li>
     *   <li>{@code { "emits": ["posture_change", ...], "descriptor_template":
     *       "..." }} — the item emits one or more body events.</li>
     * </ul>
     *
     * <p>{@code allowMigration} controls boot-time behaviour: when true (the
     * boot migration pass) a missing block returns a {@link ItemEmbodimentSpec#migrationShim}
     * instead of throwing — the audit list captures the item for later author
     * review. When false (hot-reload at runtime), the parser is the
     * gatekeeper and a missing block raises {@link ManifestEmbodimentMissingException}.
     *
     * @throws ManifestEmbodimentMissingException when {@code spec} is null
     *     AND {@code allowMigration} is false, OR when {@code spec} is
     *     structurally invalid (see {@link ItemEmbodimentSpec#isValid()}).
     * @return the spec as-given (when valid), or a migration shim when
     *     {@code allowMigration} is true and the input is null.
     */
    public static ItemEmbodimentSpec requireEmbodiment(
            ItemEmbodimentSpec spec, boolean allowMigration, String itemName) {
        var displayName = itemName == null ? "<unknown>" : itemName;
        if (spec == null) {
            if (allowMigration) {
                return ItemEmbodimentSpec.migrationShim();
            }
            throw new ManifestEmbodimentMissingException(
                "Item '" + displayName + "' is missing the "
                + "required `embodiment` block in its manifest. "
                + "Declare either "
                + "`embodiment: { silent: true, reason: \"...\" }` or "
                + "`embodiment: { emits: [...], descriptor_template: \"...\" }`. "
                + "Silence in the world must be a declared choice.");
        }
        if (!spec.isValid()) {
            throw new ManifestEmbodimentMissingException(
                "Item '" + displayName + "' declares an "
                + "embodiment block but it's structurally invalid: silent=true "
                + "requires a non-blank reason; non-silent requires a non-empty "
                + "emits list.");
        }
        return spec;
    }

    /**
     * runtime exception thrown when an item's manifest
     * fails the embodiment-block contract. Catchers at the hot-reload boundary
     * surface this to the steward and reject the item; the boot migration pass
     * avoids the throw by calling {@link #requireEmbodiment} with
     * {@code allowMigration=true}.
     */
    public static final class ManifestEmbodimentMissingException extends RuntimeException {
        public ManifestEmbodimentMissingException(String message) {
            super(message);
        }
    }

    /**
     * Items-as-tools contract — fail-fast {@code commands} check.
     *
     * <p>Every scripted item MUST declare at least one entry in
     * {@code manifest.commands = [{label, args}]} so the tool self-documents:
     * each entry surfaces as a discovery hint in the room action menu and its
     * {@code args} string must be understood by the item's {@code invoke()}.
     * {@code args} may be empty for a no-arg default invoke.
     *
     * <p>{@code allowMigration} controls boot-time behaviour, mirroring
     * {@link #requireEmbodiment}: when true (the boot migration pass) a
     * missing/empty list returns a single derived default command
     * {@code {label: "Use <displayName>", args: ""}} — the caller records the
     * item for audit. When false (register / hot-reload of newly authored
     * items), a missing list raises {@link ManifestCommandsMissingException}.
     *
     * <p>A declared-but-structurally-invalid list (any entry with a blank
     * label) throws regardless of {@code allowMigration} — like an invalid
     * embodiment block, garbage is never shimmed.
     *
     * @param manifest     the parsed manifest (may be null → treated as missing).
     * @param allowMigration boot pass when true; register/hot-reload when false.
     * @param displayName  human-readable item name used for the derived
     *                     default command's label (falls back to the manifest
     *                     name when null/blank).
     * @return the declared commands (when valid and non-empty), or the
     *     single-entry migration default when {@code allowMigration} is true
     *     and none were declared.
     * @throws ManifestCommandsMissingException per the rules above.
     */
    public static List<ItemManifest.Command> requireCommands(
            ItemManifest manifest, boolean allowMigration, String displayName) {
        var itemName = manifest != null && manifest.name() != null
            ? manifest.name() : "<unknown>";
        var shown = displayName == null || displayName.isBlank() ? itemName : displayName;
        var commands = manifest == null
            ? List.<ItemManifest.Command>of() : manifest.commands();
        if (commands.isEmpty()) {
            if (allowMigration) {
                return List.of(new ItemManifest.Command("Use " + shown, ""));
            }
            throw new ManifestCommandsMissingException(
                "items-as-tools contract — item '" + itemName + "' is missing the "
                + "required `commands` block in its manifest. Declare at least one "
                + "entry: `commands: [{ label: \"Human-readable action\", args: "
                + "\"verb-args-the-invoke-understands\" }]` (args may be \"\" for a "
                + "no-arg default invoke). A tool must document how it is used.");
        }
        for (var cmd : commands) {
            if (cmd == null || cmd.label() == null || cmd.label().isBlank()) {
                throw new ManifestCommandsMissingException(
                    "items-as-tools contract — item '" + itemName + "' declares a "
                    + "commands block but an entry has a blank label. Every entry "
                    + "must carry a non-blank human-readable label.");
            }
        }
        return commands;
    }

    /**
     * Items-as-tools contract — runtime exception thrown when an item's
     * manifest fails the {@code commands} contract. Catchers at the
     * register/hot-reload boundary surface this to the steward and reject the
     * item; the boot migration pass avoids the throw by calling
     * {@link #requireCommands} with {@code allowMigration=true}.
     */
    public static final class ManifestCommandsMissingException extends RuntimeException {
        public ManifestCommandsMissingException(String message) {
            super(message);
        }
    }

    /** Returns the highest tier across the manifest's capabilities (1 if empty). */
    public static int maxTier(ItemManifest manifest) {
        if (manifest == null) return 1;
        int max = 1;
        for (var cap : manifest.capabilities()) {
            int t = tierFor(cap);
            if (t > max) max = t;
        }
        return max;
    }

    /** Default tier for a capability per §4. Falls back to 5 for unknown writes. */
    public static int tierFor(String capability) {
        if (capability == null) return 1;
        // Wildcard form — derive tier from a representative concrete entry
        if (capability.endsWith(".*")) {
            var prefix = capability.substring(0, capability.length() - 1);
            int max = 1;
            for (var entry : KNOWN_CAPABILITIES.entrySet()) {
                if (entry.getKey().startsWith(prefix) && !entry.getKey().endsWith(".*")
                        && entry.getValue() > max) {
                    max = entry.getValue();
                }
            }
            // If no concrete entries exist, fall through to wildcard tier (5)
            if (max == 1 && KNOWN_CAPABILITIES.containsKey(capability)) {
                return KNOWN_CAPABILITIES.get(capability);
            }
            return max;
        }
        Integer concrete = KNOWN_CAPABILITIES.get(capability);
        if (concrete != null) return concrete;
        // Wildcard fallback for adapter-namespaced caps: github.create_issue → github.*
        var dot = capability.indexOf('.');
        if (dot > 0) {
            var wild = capability.substring(0, dot) + ".*";
            var w = KNOWN_CAPABILITIES.get(wild);
            if (w != null) return w;
        }
        return 5;
    }

    /** Whether the cap (or its wildcard parent) is known. */
    public static boolean isKnownCapability(String capability) {
        if (capability == null || capability.isBlank()) return false;
        if (KNOWN_CAPABILITIES.containsKey(capability)) return true;
        if (capability.endsWith(".*")) {
            var prefix = capability.substring(0, capability.length() - 1);
            for (var key : KNOWN_CAPABILITIES.keySet()) {
                if (key.startsWith(prefix)) return true;
            }
        }
        // adapter-namespaced concrete caps inherit from wildcard
        var dot = capability.indexOf('.');
        if (dot > 0) {
            var wild = capability.substring(0, dot) + ".*";
            return KNOWN_CAPABILITIES.containsKey(wild);
        }
        return false;
    }

    // ─── Catalogue ─────────────────────────────────────────────────

    private static Map<String, Integer> buildCatalogue() {
        var m = new HashMap<String, Integer>();

        // §4.1 Self & state
        addAll(m, 1, "self.did", "self.name", "self.role", "self.history",
            "zone.current", "zone.home", "zone.isTraveling", "locale",
            "drives.snapshot", "drives.history",
            "memory.search",
            "inventory.list", "inventory.use", "inventory.owned", "inventory.examine");
        addAll(m, 2, "memory.add");
        addAll(m, 4, "inventory.equip", "inventory.doff");
        addAll(m, 5, "memory.forget", "drive.mark", "tank.fill", "tank.drain");

        // §4.2 Knowledge
        addAll(m, 1, "library.search", "library.read", "library.list_packs",
            "journal.search", "journal.recent",
            "notes.list",
            "pinboard.list",
            "tags.list", "tags.entries");
        addAll(m, 2, "library.add", "library.tag", "library.ingest",
            "journal.write",
            "notes.add", "notes.delete",
            "pinboard.pin", "pinboard.unpin");
        addAll(m, 5, "library.delete");

        // §4.3 Room
        addAll(m, 1, "room.id", "room.name", "room.description",
            "room.entities", "room.objects", "room.exits", "room.get_property");
        addAll(m, 3, "room.emit", "room.narrate",
            "room.add_object", "room.remove_object",
            "room.set_property", "room.update_description",
            // body-language + ambient broadcasts
            "room.broadcast_body_language", "room.broadcast_ambient",
            "room.look_at");

        // entity posture (sit/stand/lean/etc.).
        // Tier 3: side-effect on the entity's body, mediated by ItemWorldApi.
        addAll(m, 3, "entity.set_posture", "entity.clear_posture", "entity.look_at");

        // §4.4 LLM / embed
        addAll(m, 1, "embed.similarity", "llm.budget_remaining");
        addAll(m, 4, "llm.summarize", "llm.analyze", "llm.classify",
            "llm.extract", "llm.complete", "llm.tools", "embed.encode");

        // §4.5 Time / schedule
        addAll(m, 1, "time.now", "time.iso", "time.parse", "time.elapsed", "time.tz",
            "schedule.list");
        addAll(m, 4, "schedule.in", "schedule.cron", "schedule.cancel");

        // §4.6 Compute / utility
        addAll(m, 1, "math.*", "regex.match", "regex.replace",
            "json.parse", "json.stringify", "json.diff",
            "date.format", "date.add", "date.diff",
            "crypto.hash", "crypto.hmac", "crypto.uuid", "crypto.random_bytes");

        // §4.7 Web
        addAll(m, 1, "web.search", "web.fetch", "web.allowed_domains");
        addAll(m, 4, "web.fetch_raw");
        addAll(m, 5, "web.post", "web.put", "web.delete");

        // credentialed network reach. Tier 5+ (external
        // side-effect with credentials). ssh/scp are zone-allowlist gated inside
        // the provider; net.household rides the household bus. All rate-limited.
        addAll(m, 5, "net.ssh", "net.scp", "net.household");

        // The steward's own directories. Nothing here can leave the roots configured in
        // WYRDSEKAI_HOST_OPEN_ROOTS, and every call is audit-logged — which is why find /
        // mkdir / move sit at the same tier as a raw web fetch rather than higher.
        //
        // 2026-08-22: these were admitted to the crafted ceiling and advertised in the
        // items-as-tools contract on the same day, and NOT added here. So an item that
        // declared exactly what the contract told it to declare was refused by the loader
        // with "unknown capability: 'host.file_find'", the repair loop tried to help and
        // made it worse, and the tool never reached the steward. Three lists describe one
        // capability; a capability added to two of them does not exist.
        addAll(m, 4, "host.file_find", "host.dir_make", "host.file_move");
        // Found by the same sweep, pre-existing: each of these is inside the crafted
        // ceiling — an item is ALLOWED to use it — and declaring it failed validation.
        addAll(m, 3, "oracle.query");
        addAll(m, 5, "host.app_launch", "host.file_open", "host.url_open");

        // §4.8 MCP
        addAll(m, 1, "mcp.budget_remaining", "mcp.available");
        addAll(m, 4, "mcp.list_servers", "mcp.list_tools",
            "mcp.resources.read", "mcp.prompts", "mcp.subscribe");
        addAll(m, 5, "mcp.invoke");

        // §4.9 Cross-agent
        addAll(m, 1, "agent.mailbox.read", "bond.list", "bond.detail");
        addAll(m, 2, "agent.mailbox.archive", "mailbox.mark_read",
            // ITEMS_AS_TOOLS_PREAMBLE teaches world.agent.remember — alias of memory.add
            // (tier 2 memory write). Added 2026-05-24 to close preamble↔validator gap
            // surfaced by GooseLiveInvocationE2ETest #983.
            "agent.remember");
        addAll(m, 3,
            // ITEMS_AS_TOOLS_PREAMBLE teaches world.agent.speak — agent makes a sound
            // observable to the room (semantic peer of room.emit at tier 3). Same
            // origin as agent.remember above.
            "agent.speak");
        addAll(m, 4, "agent.tell");
        addAll(m, 5, "agent.broadcast", "agent.mailbox.send");
        addAll(m, 6, "agent.give_item", "transit.request", "transit.start", "bond.suggest");

        // §4.10 Forge
        addAll(m, 1, "forge.cycle_status", "forge.history", "forge.gap_report");
        addAll(m, 2, "forge.journal");
        addAll(m, 4, "forge.observe");
        addAll(m, 5, "forge.propose_skill");

        // Chronicle read (used by chronicle.js).
        addAll(m, 1, "chronicle.read", "chronicle.warnings");

        // Wave 7 — substrate read surface
        // (bondholder_pinboard / repair_mirror / substrate_scroll furnishings).
        // Tier 1 read-only — never surfaces Sanctuary session contents.
        addAll(m, 1, "substrate.read");

        // §4.11 Workshop / Workbench
        addAll(m, 1, "workshop.backend_for", "workshop.task_status", "workshop.artifacts");
        addAll(m, 4, "workshop.cancel");
        addAll(m, 5, "workshop.dispatch", "workbench.submit_tool");
        addAll(m, 6, "workbench.shape_form", "workbench.revise_form",
            "workbench.retire_form", "workbench.destroy_tool", "workbench.imprint");

        // §4.12 Crucible / Assay
        addAll(m, 1, "crucible.status", "assay.score");
        addAll(m, 4, "crucible.cancel");
        addAll(m, 5, "crucible.run", "assay.test");

        // §4.13 Trading Post
        addAll(m, 1, "market.list_listings", "market.history");
        addAll(m, 4, "market.cancel");
        addAll(m, 5, "market.list_offer", "market.accept");

        // §4.14 Counting House
        addAll(m, 1, "ledger.balance", "ledger.history", "ledger.estimate", "ledger.usage_summary");
        addAll(m, 5, "ledger.charge");
        addAll(m, 6, "ledger.transfer");

        // §4.15 Council
        addAll(m, 1, "council.proposals", "council.history", "council.tally");
        addAll(m, 5, "council.suggest");
        addAll(m, 7, "council.vote");

        // §4.16 Furnishing writes
        addAll(m, 1, "audit.recent",
            "grants.issued", "grants.held", "grants.pending_requests",
            "presence.in_home", "notifications.channels",
            "voice.snapshot", "skill.pending_drafts",
            "pairing.pending", "pairing.code", "pairing.household_key");
        addAll(m, 5, "grants.issue", "grants.revoke", "grants.approve", "grants.deny",
            "voice.set", "voice.unset", "voice.freeze", "voice.unfreeze",
            "presence.dim", "presence.light",
            "notifications.set", "notifications.disable",
            "skill.reject");
        addAll(m, 6, "pairing.approve", "pairing.deny");
        addAll(m, 7, "voice.revert", "skill.accept", "pairing.generate_household_key");

        // Nostr bridge publish (2026-07-03) — nostr_quill.js publishes notes
        // through the relay-mesh via world.nostr.publish. Tier 5: external
        // side-effect with the household's signing identity. Registered so
        // items can DECLARE it (the adapter layer dispatched it unrestricted
        // regardless — this closes the manifest/catalogue gap that silently
        // kept the quill from loading at all).
        addAll(m, 5, "nostr.publish");

        // §4.16b Study control panel (2026-07-03) — steward household
        // administration writes + safe-snapshot read, backing the roster
        // ledger / invitation scroll / ward keyring / treasury / key chest
        // furnishings. Reads (household.members, invite.list, ward.list,
        // nodes.list, treasury.summary, pairing.devices, audit.security)
        // are IMPLICIT in ItemCapabilitySet like audit.recent. The provider
        // additionally steward-gates every write regardless of declared caps.
        addAll(m, 4, "safe.snapshots");
        addAll(m, 6, "invite.create", "invite.revoke",
            "ward.grant", "ward.revoke",
            "hermod.grant.revoke",
            "pairing.revoke_device", "treasury.set_budget",
            // W5 (2026-07-11): Counting House write API — credit transfer from
            // the acting player (provider enforces from == acting player).
            "treasury.transfer");
        addAll(m, 7, "household.set_role", "household.remove_member");

        // §4.16c Parental controls (2026-07-03) — steward writes backing the
        // parental-controls scroll (per-member time limits, room blocks,
        // inference quotas, content filters). Reads (parental.list,
        // parental.get) are IMPLICIT in ItemCapabilitySet; the provider
        // steward-scopes reads and the service steward-gates every write.
        addAll(m, 7, "parental.set", "parental.clear");

        // §4.16d Maintenance (2026-07-03) — steward maintenance writes
        // backing the maintenance dial + key chest (maintenance mode,
        // backup-now, backup schedule, staged restore). The read
        // (maintenance.status) is IMPLICIT in ItemCapabilitySet; the
        // service steward-gates every write regardless of declared caps,
        // and a restore only STAGES a marker applied at next boot.
        addAll(m, 7, "maintenance.set_mode", "maintenance.backup",
            "maintenance.stage_restore");

        // §4.17 Hearth aliases
        addAll(m, 1, "hearth.drives_mirror", "hearth.autonomy",
            "hearth.visits", "hearth.journal_recent", "hearth.steward");

        // §4.18 The Safe
        addAll(m, 4, "safe.list_slots", "safe.has");
        addAll(m, 5, "safe.get", "safe.set", "safe.delete");

        // §4.19 The Bridge
        addAll(m, 4, "bridge.zone_status", "bridge.peers", "bridge.federation_health",
            "bridge.topology", "bridge.system_metrics");
        addAll(m, 5, "bridge.tail_log");

        // §4.20 Federation / directory
        addAll(m, 1, "federation.agreements", "federation.mesh_status",
            "directory.resolve");
        addAll(m, 4, "federation.peers", "federation.zone_info",
            "directory.discover", "directory.locate", "transit.list_visitors");
        addAll(m, 7, "federation.propose", "federation.accept", "federation.revoke");

        // §4.21 Soul / familiar / imprint
        addAll(m, 1, "soul.fragments.list", "soul.imprints.list",
            "familiar.list", "familiar.status",
            "bunshin.list", "bunshin.status", "form.list");
        addAll(m, 4, "soul.fragments.add");
        addAll(m, 6, "soul.imprints.create",
            "familiar.summon", "familiar.give_copy", "familiar.name",
            "bunshin.dispatch", "form.shape");
        addAll(m, 7, "soul.imprints.restore", "soul.imprints.delete", "soul.modify");

        // §4.22 Chapel
        addAll(m, 1, "chapel.bond_status");
        addAll(m, 6, "chapel.suggest_ritual", "chapel.ceremony");
        addAll(m, 7, "chapel.exit_ritual");

        // §4.23 FS
        addAll(m, 1, "fs.list", "fs.exists", "fs.stat");
        addAll(m, 4, "fs.read", "fs.write", "fs.delete", "fs.mkdir");

        // §4.34 Inbound listeners — Phase T
        addAll(m, 1, "inbound.list");
        addAll(m, 4, "inbound.cancel", "inbound.pause", "inbound.resume",
            "inbound.email_watch", "inbound.rss", "inbound.atom",
            "inbound.file_watch", "inbound.calendar_reminder", "inbound.scheduled");
        addAll(m, 5, "inbound.webhook", "inbound.mqtt",
            "inbound.slack_event", "inbound.discord_event",
            "inbound.telegram_event", "inbound.matrix_event",
            "inbound.github_webhook", "inbound.gitlab_webhook",
            "inbound.bitbucket_webhook",
            "inbound.sse", "inbound.websocket",
            "inbound.sms_received", "inbound.voice_received");

        // §4.35 Visualization (charts) — Phase B+
        addAll(m, 1, "chart.ascii", "chart.theme", "chart.list_themes");
        addAll(m, 4, "chart.render");

        // §4.36 Artifacts — Phase B+
        addAll(m, 1, "artifact.read", "artifact.list");
        addAll(m, 4, "artifact.write", "artifact.read.shared");
        addAll(m, 5, "artifact.attach.room");

        // §4.37 Scrolls — Phase B+
        addAll(m, 1, "scroll.read", "scroll.list");
        addAll(m, 4, "scroll.write", "scroll.read.shared");
        addAll(m, 5, "scroll.share");

        // §4.32 financial — Phase S concrete caps. stripe.write is Tier 7
        // (steward-token gated); read paths + non-spend writes default to Tier 5.
        addAll(m, 5, "stripe.read", "plaid.read",
            "wise.read", "coinbase.read");
        addAll(m, 7, "stripe.write", "wise.write");

        // §4.33 telephony — Phase S concrete caps. All Tier 5 because every
        // call places a real outbound communication.
        addAll(m, 5, "twilio.sms", "twilio.whatsapp", "twilio.voice",
            "vonage.sms", "signalwire.sms");

        // §4.42 Travel & transport — Phase V (read-only).
        addAll(m, 4, "amadeus.read", "kayak.read", "google_flights.read",
            "booking.read", "airbnb.read", "uber.read", "lyft.read",
            "transit.read");

        // §4.43 Shopping, commerce, real estate — Phase V.
        addAll(m, 4, "shopify.read", "amazon.read", "etsy.read",
            "zillow.read", "redfin.read", "indeed.read");
        addAll(m, 7, "shopify.write");

        // §4.44 Translation, language, education — Phase W (Tier 4)
        addAll(m, 4,
            "deepl.translate", "deepl.detect_language",
            "translate.translate", "translate.detect_language",
            "lingua.translate",
            "duolingo.read", "duolingo.user_progress", "duolingo.list_courses",
            "coursa.read", "coursa.write",
            "coursa.course_search", "coursa.enroll",
            "khan.read", "khan.topic_search", "khan.video_lookup");
        addAll(m, 5, "deepl.*", "translate.*", "lingua.*",
            "duolingo.*", "coursa.*", "khan.*");

        // §4.45 Photo, asset libraries — Phase W (Tier 1, public reads)
        addAll(m, 1,
            "unsplash.read", "unsplash.search", "unsplash.download_url",
            "pixabay.read", "pixabay.search",
            "pexels.read", "pexels.search",
            "iconify.read", "iconify.search_icons", "iconify.search",
            "fonts.read", "fonts.list", "fonts.font_info");
        addAll(m, 5, "unsplash.*", "pixabay.*", "pexels.*",
            "iconify.*", "fonts.*");

        // §4.46 Books, reading, manga — Phase W (Tier 4, user libraries)
        addAll(m, 4,
            "goodreads.read", "goodreads.search", "goodreads.book_info", "goodreads.list_reviews",
            "openlib.read", "openlib.search", "openlib.work_info", "openlib.edition_info",
            "gbooks.read", "gbooks.search", "gbooks.volume_info",
            "kobo.read", "kobo.library_list", "kobo.recent_purchases",
            "audible.read", "audible.library_list", "audible.listening_history",
            "calibre.read", "calibre.write",
            "calibre.library_list", "calibre.book_info", "calibre.search",
            "mangadex.read", "mangadex.search", "mangadex.chapter_list");
        addAll(m, 5, "goodreads.*", "openlib.*", "gbooks.*",
            "kobo.*", "audible.*", "calibre.*", "mangadex.*");

        // §4.25 + §4.26 — Phase P concrete adapter caps. Reads = Tier 4,
        // writes = Tier 5. Wildcards remain available via the catalogue
        // below for stewards who'd rather declare "github.*" than enumerate.
        // Social — §4.25
        addAll(m, 4, "mastodon.read", "reddit.read", "bluesky.read",
            "x.read", "hn.read");
        addAll(m, 5, "mastodon.post", "mastodon.follow",
            "reddit.post", "reddit.subscribe",
            "bluesky.post",
            "x.post", "x.dm");
        // Code / dev platforms — §4.26
        addAll(m, 4, "github.issue.read", "github.pr.read", "github.code.search",
            "gitlab.issue.read", "gitlab.mr.read",
            "npm.read", "pypi.read");
        addAll(m, 5, "github.issue.write", "github.pr.write",
            "gitlab.issue.write");

        // §4.24-§4.34 — adapter wildcards. Declaring "<ns>.*" is the canonical
        // form. Specific cap-by-cap declarations also resolve via wildcard
        // fallback in tierFor().
        var tier5Adapters = List.of(
            "email", "slack", "discord", "telegram", "matrix", "signal",
            "whatsapp", "sms", "irc", "protonmail",
            "mastodon", "bluesky", "reddit", "hn", "discourse", "lemmy",
            "blog", "linkedin",
            "github", "gitlab", "bitbucket", "gitea", "codeberg",
            "npm", "pypi", "rubygems", "crates", "maven", "nuget", "go",
            "docker", "ci", "sentry", "stackexchange",
            "calendar", "gdocs", "gdrive", "dropbox", "onedrive", "box", "icloud",
            "notion", "linear", "jira", "asana", "todoist", "trello", "clickup",
            "servicenow", "zendesk", "salesforce", "hubspot", "contacts",
            "reminders", "applenotes",
            "zotero", "mendeley", "pocket", "raindrop", "anki",
            "wikipedia", "wikidata", "wolfram", "arxiv", "pubmed", "crossref",
            "semanticscholar", "googlescholar", "scholar", "stackoverflow",
            "openlibrary", "archive",
            "huggingface", "hf", "kaggle", "paperswithcode",
            "replicate", "fal", "together", "fireworks", "mistral",
            "openai", "anthropic", "cohere", "gemini", "vertex",
            "wandb", "comet", "mlflow",
            "stability", "flux", "midjourney", "image", "tts", "stt",
            "video", "runway", "elevenlabs", "whisper",
            "ha", "hass", "mqtt", "ifttt", "zapier", "n8n", "shortcuts", "tasker",
            "hue", "apple_home", "sonos", "chromecast", "airplay", "appletv",
            "spotify", "applemusic", "apple_music", "youtube", "vimeo", "twitch",
            "lastfm", "podcast", "plex", "jellyfin", "emby",
            "musicbrainz", "discogs",
            "stripe", "paypal", "venmo", "cashapp",
            "plaid", "bank", "coinbase", "wise",
            "quickbooks", "xero", "freshbooks", "wave",
            "twilio", "vonage", "signalwire",
            "sip", "fax",
            "fakeservice"  // test convenience
        );
        for (var ns : tier5Adapters) {
            m.put(ns + ".*", 5);
        }
        // Crypto-wallet → Tier 7 (irreversible spend)
        m.put("crypto.btc.*", 7);
        m.put("crypto.eth.*", 7);
        m.put("crypto.sol.*", 7);

        // Phase Q meta-caps — productivity (§4.27) + knowledge (§4.28).
        // Reads = Tier 4, writes = Tier 5 per the spec table. These coarse
        // caps let manifests opt in at the namespace×role level; per-method
        // wildcards still resolve via the tier5Adapters fallback above.
        addAll(m, 4, "calendar.read", "gdrive.read", "notion.read",
            "linear.read", "asana.read", "todoist.read",
            "arxiv.read", "scholar.read", "wikipedia.read",
            "stackoverflow.read", "wolfram.read");
        addAll(m, 5, "calendar.write", "gdrive.write", "notion.write",
            "linear.write", "asana.write", "todoist.write");

        // §4.38 health & wearables (Phase U) — Tier 5 (sensitive PII).
        addAll(m, 5, "oura.read", "fitbit.read", "apple_health.read",
            "whoop.read", "garmin.read", "google_fit.read");
        m.put("oura.*", 5);
        m.put("fitbit.*", 5);
        m.put("apple_health.*", 5);
        m.put("whoop.*", 5);
        m.put("garmin.*", 5);
        m.put("google_fit.*", 5);

        // §4.39 government & civic data (Phase U) — Tier 4 reads.
        addAll(m, 4, "usajobs.read", "datagov.read", "congress.read", "irs.read");
        m.put("usajobs.*", 4);
        m.put("datagov.*", 4);
        m.put("congress.*", 4);
        m.put("irs.*", 4);

        // §4.40 maps, location, geocoding (Phase U) — Tier 4.
        addAll(m, 4, "maps.read", "maps.routes",
            "nominatim.read",
            "mapbox.read", "mapbox.routes",
            "timezone.read");
        m.put("maps.*", 4);
        m.put("nominatim.*", 4);
        m.put("mapbox.*", 4);
        m.put("timezone.*", 4);

        // §4.41 weather (Phase U) — Tier 4.
        addAll(m, 4, "openweather.read", "weatherapi.read", "visualcrossing.read");
        m.put("openweather.*", 4);
        m.put("weatherapi.*", 4);
        m.put("visualcrossing.*", 4);

        return Map.copyOf(m);
    }

    private static void addAll(Map<String, Integer> m, int tier, String... caps) {
        for (var c : caps) m.put(c, tier);
    }

    /** Visible for tests. */
    static Set<String> knownNames() {
        return new HashSet<>(KNOWN_CAPABILITIES.keySet());
    }
}
