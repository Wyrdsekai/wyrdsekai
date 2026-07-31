package org.wyrdsekai.core.item;

import org.wyrdsekai.core.room.TheSafe;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

/**
 * Minimal ItemWorldApiProvider for visitor-carried scripted items when no full
 * host provider is available. Returns safe defaults for all services but
 * reports the visitor's home zone and current host zone accurately.
 *
 * <p>Use this as a fallback when a visitor uses a carried scripted item in a
 * remote zone that doesn't have a full ItemWorldApiProvider wired for visitors.
 * Scripts get zone info via {@code world.zone.*}; other services return
 * empty/default values.</p>
 *
 * <p>For full cross-zone item functionality, wrap a real host-zone provider with
 * {@link TransitItemProvider} instead.</p>
 */
public class VisitorItemProvider implements ItemWorldApiProvider {

    private final String currentZoneId;
    private final String homeZoneId;

    /**
     * Template catalog for {@code world.catalog.*} — the host zone's
     * StandardItemLibrary. Rita campaign 2026-07-11 (#26): the player-side
     * provider (this class, via WyrdWebSocket.buildPlayerProvider →
     * ItemProviderRegistry → RoomActor furnishing invocations) never bound
     * the item library, so `use template catalog` on the Workshop surface
     * always answered "the item library isn't bound on this surface".
     * Nullable — unwired surfaces keep the safe empty-list defaults.
     */
    private volatile StandardItemLibrary itemLibrary;

    /**
     * The zone's Safe for {@code world.safe.list/has} — same 4-surfaces bug
     * class as the catalog above (#31 item 1, post-restart verify 2137ea49):
     * the companion-side provider got {@code setSafe(TheSafe.local())} and
     * the catalog got all four surfaces, but the PLAYER provider (this class
     * and HomeOwnerItemProvider via WyrdWebSocket.buildPlayerProvider) never
     * did, so player-invoked items saw an empty safe even with slots stored.
     * Nullable — unwired surfaces keep the empty/deny defaults.
     */
    private volatile TheSafe safe;

    public VisitorItemProvider(String currentZoneId, String homeZoneId) {
        this.currentZoneId = currentZoneId;
        this.homeZoneId = homeZoneId;
    }

    /** Bind the host zone's template catalog (read-only; safe for visitors). */
    public VisitorItemProvider withCatalog(StandardItemLibrary library) {
        this.itemLibrary = library;
        return this;
    }

    /** Wire the zone's Safe (mirrors {@link ItemWorldApiProviderImpl#setSafe}). */
    public void setSafe(TheSafe s) {
        this.safe = s;
    }

    @Override public String currentZone() { return currentZoneId; }
    @Override public String homeZone() { return homeZoneId; }

    // ─── Catalog / Standard Library (mirrors ItemWorldApiProviderImpl) ───

    @Override
    public List<Map<String, Object>> catalogSearch(String query) {
        var lib = itemLibrary;
        if (lib == null) return List.of();
        return lib.search(query).stream()
            .map(ItemWorldApiProviderImpl::templateToMap)
            .toList();
    }

    @Override
    public List<Map<String, Object>> catalogByCategory(String category) {
        var lib = itemLibrary;
        if (lib == null) return List.of();
        return lib.byCategory(category).stream()
            .map(ItemWorldApiProviderImpl::templateToMap)
            .toList();
    }

    @Override
    public Map<String, Object> catalogTemplateInfo(String templateName) {
        var lib = itemLibrary;
        if (lib == null) return null;
        var template = lib.get(templateName);
        if (template == null) return null;
        return ItemWorldApiProviderImpl.templateInfoToMap(template);
    }

    // All service calls return safe defaults. Scripts that need these should
    // check world.zone.isTraveling() and adapt.

    @Override
    public List<Map<String, Object>> searchKnowledge(String query, int limit) {
        return List.of(Map.of("error", "Knowledge search unavailable — visiting foreign zone"));
    }

    @Override
    public Map<String, Object> readKnowledgeChunk(String chunkId) {
        return Map.of("error", "Read unavailable — visiting foreign zone");
    }

    @Override
    public List<Map<String, Object>> webSearch(String query, String type, int limit) {
        return List.of();
    }

    @Override
    public String webFetch(String url, int maxChars) {
        return "[web fetch unavailable — visiting foreign zone]";
    }

    @Override
    public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
        return List.of();
    }

    @Override
    public String llmSummarize(String text, String instruction) {
        return "[LLM unavailable — visiting foreign zone]";
    }

    @Override
    public String llmAnalyze(String text, String prompt) {
        return "[LLM unavailable — visiting foreign zone]";
    }

    @Override
    public void agentSpeak(String text) {
        // Visitor can't speak as a local agent in foreign zone — no-op
    }

    @Override
    public void agentRemember(String content) {
        // No memory in foreign zone — no-op
    }

    @Override
    public void agentTell(String target, String message) {
        // Tell delegated to CrossZoneTellService at a higher layer — no-op here
    }

    // ─── §4.18 The Safe (mirrors ItemWorldApiProviderImpl) ───────

    @Override
    public List<String> safeListSlots() {
        var s = safe;
        if (s == null) return List.of();
        return s.listSecretIds();
    }

    @Override
    public boolean safeHas(String slot) {
        var s = safe;
        if (s == null || slot == null) return false;
        return s.hasSecret(slot);
    }

    @Override
    public Map<String, Object> safeSet(String slot, String value) {
        if (safe == null) return Map.of("ok", false, "error", "safe_not_wired");
        // Writes stay on the MCP keychest path — same policy as the full provider.
        return Map.of("ok", false, "error", "use_mcp_keychest_for_writes");
    }

    @Override
    public Map<String, Object> safeDelete(String slot) {
        if (safe == null) return Map.of("ok", false, "error", "safe_not_wired");
        return Map.of("ok", false, "error", "use_mcp_keychest_for_deletes");
    }

    @Override
    public List<Map<String, Object>> inventoryList() {
        return List.of();
    }

    @Override
    public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
        return Map.of("error", "Inventory use unavailable — visiting foreign zone");
    }
}
