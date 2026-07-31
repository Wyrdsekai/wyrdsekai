package org.wyrdsekai.scripting.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * runtime capability set bound to a single
 * item-script execution.
 *
 * <p>Created from a validated {@link ItemManifest} at execution time and
 * threaded through {@code ItemScriptExecutor.execute(...)}. Every gated
 * {@code world.*} method in {@link ItemWorldApi} consults
 * {@link #require(String)} before delegating to the provider; missing caps
 * raise {@link CapabilityDeniedError}.</p>
 *
 * <p>Implicit Tier-1 caps (e.g. {@code self.did}, {@code library.search})
 * are always granted — they don't appear in the declared set but the
 * runtime won't gate them.</p>
 */
public final class ItemCapabilitySet {

    /** Capabilities every item gets without declaration (spec §3.1 Tier 1). */
    public static final Set<String> IMPLICIT = Set.of(
        "self.did", "self.name", "self.role", "self.history",
        "zone.current", "zone.home", "zone.isTraveling",
        "locale",
        "drives.snapshot", "drives.history",
        "memory.search",
        "inventory.list", "inventory.use", "inventory.owned", "inventory.examine",
        "library.search", "library.read", "library.list_packs",
        "journal.search", "journal.recent",
        "notes.list",
        "pinboard.list",
        "tags.list", "tags.entries",
        "room.id", "room.name", "room.description",
        "room.entities", "room.objects", "room.exits", "room.get_property",
        "time.now", "time.iso", "time.parse", "time.elapsed", "time.tz",
        "math.*", "regex.match", "regex.replace",
        "json.parse", "json.stringify", "json.diff",
        "date.format", "date.add", "date.diff",
        "crypto.hash", "crypto.hmac", "crypto.uuid", "crypto.random_bytes",
        "embed.similarity",
        "llm.budget_remaining",
        "web.search", "web.fetch", "web.allowed_domains",
        "mcp.budget_remaining", "mcp.available",
        "schedule.list",
        "fs.list", "fs.exists", "fs.stat",
        "agent.mailbox.read",
        "bond.list", "bond.detail",
        "audit.recent", "audit.security",
        "grants.issued", "grants.held", "grants.pending_requests",
        "presence.in_home", "notifications.channels",
        "pairing.pending", "pairing.code", "pairing.household_key",
        "pairing.devices",
        // Study control-panel reads (provider-side scoping applies).
        "household.members", "invite.list", "ward.list", "nodes.list",
        "parental.list", "parental.get",
        "maintenance.status",
        "treasury.summary", "treasury.per_member", "treasury.balance",
        "federation.agreements", "federation.mesh_status", "federation.peers",
        "directory.resolve",
        "version.local", "version.mesh",
        "coding.backends",
        "forge.cycle_status", "forge.history", "forge.gap_report",
        "soul.fragments.list", "soul.imprints.list",
        "familiar.list", "familiar.status",
        "bunshin.list", "bunshin.status",
        "form.list",
        "skill.pending_drafts",
        "ledger.balance", "ledger.history", "ledger.estimate", "ledger.usage_summary",
        "council.proposals", "council.history", "council.tally",
        "transit.list_visitors",
        "voice.snapshot",
        "hearth.steward", "hearth.autonomy", "hearth.visits", "hearth.journal_recent",
        "hearth.drives_mirror",
        // §4.35 — pure-text ASCII chart is implicit Tier 1 (no side effects).
        "chart.ascii", "chart.theme", "chart.list_themes",
        // §4.36 — own-artifact list/get is implicit Tier 1 read.
        "artifact.read", "artifact.list",
        // §4.37 — own-scroll list/read is implicit Tier 1.
        "scroll.read", "scroll.list",
        // Phase D-N additions — read-only / status surfaces.
        "workshop.backend_for", "workshop.task_status", "workshop.artifacts",
        "crucible.status", "assay.score",
        "market.list_listings", "market.history",
        "chapel.bond_status",
        // Phase T (§4.34) — read-only inbound surface.
        "inbound.list"
    );

    /** Special unrestricted cap set — bypasses gating. JVM-baked items get this. */
    public static final ItemCapabilitySet UNRESTRICTED = new ItemCapabilitySet(null, null,
        List.of(), List.of(), List.of());

    /**
     * Vetted capability ceiling for AGENT-CRAFTED / VISITOR-carried scripts
     * (2026-07-19 OSS hardening #1). Before this, every item-script call path
     * passed {@link #UNRESTRICTED}, so a script an agent authored at runtime (or
     * a visitor carried in from another zone) ran with full authority — it could
     * reach household admin, relay governance, grants, federation, remote exec
     * (net.ssh/scp), external HTTP mutation and cross-agent asset transfer. That
     * made the capability system decorative and removed the confused-deputy
     * backstop under the admin-delegation gate.
     *
     * <p>This is an ALLOW-LIST on top of {@link #IMPLICIT}: crafted scripts get
     * the benign, self-scoped or room-local surfaces they legitimately use
     * (narrate, summarize, remember, journal, fetch, in-household tell) and
     * NOTHING else. Any capability not listed here — including every future one —
     * is denied fail-closed. The escalation/exfil families are deliberately
     * absent: {@code relay.admin}, {@code grants.*}, {@code federation.propose/
     * accept/revoke}, {@code net.ssh/scp/household}, {@code web.post/put/delete},
     * {@code agent.give_item}, {@code llm.tools}, {@code notifications.set/
     * disable}, external adapter namespaces, and all household/ward/invite/
     * parental admin (also gated by the delegation requester check).</p>
     */
    public static final Set<String> CRAFTED_ALLOW = Set.of(
        // Self-scoped writes to the agent's own stores.
        "library.add", "library.ingest", "library.tag", "library.delete",
        "memory.add", "memory.forget",
        "journal.write",
        "notes.add", "notes.delete",
        "pinboard.pin", "pinboard.unpin",
        // Room-local presentation / embodiment (no cross-zone or privilege effect).
        "room.emit", "room.narrate", "room.add_object", "room.remove_object",
        "room.set_property", "room.update_description", "room.broadcast_body_language",
        "entity.set_posture", "entity.clear_posture", "entity.look_at",
        // Inference: a cost surface, not a privilege boundary (the model already runs).
        // llm.tools is EXCLUDED — it re-enters the tool loop.
        "llm.summarize", "llm.analyze", "llm.complete", "llm.classify", "llm.extract",
        "embed.encode",
        // Benign reads and self-scheduling.
        "oracle.query", "web.fetch_raw",
        "schedule.in", "schedule.cron", "schedule.cancel",
        "presence.dim", "presence.light",
        "bond.suggest",
        // In-household messaging — the recipient agent decides what to do with a
        // message; delivering one is not privilege escalation. give_item IS.
        "agent.tell", "agent.broadcast"
    );

    private static final ItemCapabilitySet CRAFTED_DEFAULT = of(CRAFTED_ALLOW);

    /**
     * The capability ceiling every agent-crafted or visitor-carried item script
     * runs under. See {@link #CRAFTED_ALLOW}. Trusted (bundled / disk-installed /
     * starter-kit) items keep {@link #UNRESTRICTED}.
     */
    public static ItemCapabilitySet craftedDefault() {
        return CRAFTED_DEFAULT;
    }

    private final Set<String> declared;          // null for UNRESTRICTED
    private final BiConsumer<String, Boolean> auditHook;
    private final AtomicLong gatedCalls = new AtomicLong();
    private final AtomicLong deniedCalls = new AtomicLong();
    /**
     * Phase C — per-execution allowlists carried alongside the cap set so the
     * runtime can enforce domain/server/slot scoping at call-time (defense in
     * depth above manifest validation). Empty lists for {@link #UNRESTRICTED}.
     */
    private final List<String> externalDomains;
    private final List<String> mcpServers;
    private final List<String> safeSlots;

    private ItemCapabilitySet(Set<String> declared, BiConsumer<String, Boolean> auditHook) {
        this(declared, auditHook, List.of(), List.of(), List.of());
    }

    private ItemCapabilitySet(Set<String> declared, BiConsumer<String, Boolean> auditHook,
                                List<String> externalDomains,
                                List<String> mcpServers,
                                List<String> safeSlots) {
        this.declared = declared;
        this.auditHook = auditHook;
        this.externalDomains = externalDomains == null ? List.of() : List.copyOf(externalDomains);
        this.mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        this.safeSlots = safeSlots == null ? List.of() : List.copyOf(safeSlots);
    }

    public static ItemCapabilitySet of(Iterable<String> capabilities) {
        return of(capabilities, null);
    }

    public static ItemCapabilitySet of(Iterable<String> capabilities,
                                        BiConsumer<String, Boolean> auditHook) {
        var set = new LinkedHashSet<String>();
        if (capabilities != null) {
            for (var c : capabilities) {
                if (c != null && !c.isBlank()) set.add(c.trim());
            }
        }
        return new ItemCapabilitySet(Collections.unmodifiableSet(set), auditHook);
    }

    /** Build from a manifest. Reads {@code capabilities} + allowlist fields. */
    public static ItemCapabilitySet from(ItemManifest manifest) {
        if (manifest == null) return of(Set.of());
        var set = new LinkedHashSet<String>();
        for (var c : manifest.capabilities()) {
            if (c != null && !c.isBlank()) set.add(c.trim());
        }
        return new ItemCapabilitySet(Collections.unmodifiableSet(set), null,
            manifest.externalDomains(),
            manifest.mcpServers(),
            manifest.safeSlots());
    }

    /**
     * Gate entry point — every {@code @HostAccess.Export} method that maps
     * to a Tier 2+ capability calls this first. Throws on denial; otherwise
     * records a gated call for the audit hook (if registered).
     */
    public void require(String capability) {
        if (declared == null) return; // UNRESTRICTED — JVM-baked
        gatedCalls.incrementAndGet();
        if (IMPLICIT.contains(capability) || matchesAnyDeclared(capability)) {
            if (auditHook != null) auditHook.accept(capability, true);
            return;
        }
        deniedCalls.incrementAndGet();
        if (auditHook != null) auditHook.accept(capability, false);
        throw new CapabilityDeniedError(capability);
    }

    /** Non-throwing predicate variant. */
    public boolean has(String capability) {
        if (declared == null) return true;
        return IMPLICIT.contains(capability) || matchesAnyDeclared(capability);
    }

    private boolean matchesAnyDeclared(String capability) {
        if (declared.contains(capability)) return true;
        // Wildcard support: declaring "github.*" matches "github.create_issue"
        var dot = capability.indexOf('.');
        if (dot > 0) {
            var prefix = capability.substring(0, dot);
            if (declared.contains(prefix + ".*")) return true;
        }
        return false;
    }

    public Set<String> declared() {
        return declared == null ? Set.of() : declared;
    }

    public long gatedCallCount() {
        return gatedCalls.get();
    }

    public long deniedCallCount() {
        return deniedCalls.get();
    }

    /** Phase C — domains the script may reach via web.post/put/delete/fetch_raw. */
    public List<String> externalDomains() { return externalDomains; }

    /** Phase C — MCP servers the script may invoke. */
    public List<String> mcpServers() { return mcpServers; }

    /** Phase C — Safe slots the script may read/write/delete. */
    public List<String> safeSlots() { return safeSlots; }

    /**
     * UNRESTRICTED bypass marker — JVM-baked items skip domain/server checks.
     * Phase C web/mcp surfaces consult this before consulting the per-script
     * allowlist.
     */
    public boolean isUnrestricted() { return declared == null; }
}
