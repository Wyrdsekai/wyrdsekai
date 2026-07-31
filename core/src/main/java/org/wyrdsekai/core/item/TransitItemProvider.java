package org.wyrdsekai.core.item;

import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

/**
 * Wraps a host zone's {@link ItemWorldApiProvider} for use by a traveling visitor.
 *
 * <p>When a visitor uses a carried scripted item in a foreign zone, the script
 * should see the host zone's services (library, web, oracle) but know it's being
 * used by someone whose home is elsewhere. This wrapper:
 * <ul>
 *   <li>Delegates all service calls to the host provider</li>
 *   <li>Reports the visitor's home zone via {@link #homeZone()}</li>
 *   <li>Reports the host's zone via {@link #currentZone()} (from env)</li>
 * </ul>
 *
 * <p>Scripts can use {@code world.zone.isTraveling()} to adapt behavior — e.g.,
 * a library_card can search the HOST zone's library (correct: you're browsing
 * their shelves) or a quill can note "You write in zone X while visiting from Y".</p>
 */
public final class TransitItemProvider implements ItemWorldApiProvider {

    private final ItemWorldApiProvider delegate;
    private final String visitorHomeZone;

    public TransitItemProvider(ItemWorldApiProvider delegate, String visitorHomeZone) {
        this.delegate = delegate;
        this.visitorHomeZone = visitorHomeZone;
    }

    @Override
    public String homeZone() {
        return visitorHomeZone != null ? visitorHomeZone : delegate.homeZone();
    }

    @Override
    public String currentZone() {
        return delegate.currentZone();
    }

    // --- Delegate everything else to the host zone's provider ---

    @Override
    public List<Map<String, Object>> searchKnowledge(String query, int limit) {
        return delegate.searchKnowledge(query, limit);
    }

    @Override
    public Map<String, Object> readKnowledgeChunk(String chunkId) {
        return delegate.readKnowledgeChunk(chunkId);
    }

    @Override
    public List<Map<String, Object>> webSearch(String query, String type, int limit) {
        return delegate.webSearch(query, type, limit);
    }

    @Override
    public String webFetch(String url, int maxChars) {
        return delegate.webFetch(url, maxChars);
    }

    @Override
    public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
        return delegate.queryOracle(topic, analysisType);
    }

    @Override
    public String llmSummarize(String text, String instruction) {
        return delegate.llmSummarize(text, instruction);
    }

    @Override
    public String llmAnalyze(String text, String prompt) {
        return delegate.llmAnalyze(text, prompt);
    }

    @Override
    public void agentSpeak(String text) {
        delegate.agentSpeak(text);
    }

    @Override
    public void agentRemember(String content) {
        delegate.agentRemember(content);
    }

    @Override
    public void agentTell(String target, String message) {
        delegate.agentTell(target, message);
    }

    @Override
    public List<Map<String, Object>> catalogSearch(String query) {
        return delegate.catalogSearch(query);
    }

    @Override
    public List<Map<String, Object>> catalogByCategory(String category) {
        return delegate.catalogByCategory(category);
    }

    @Override
    public Map<String, Object> catalogTemplateInfo(String templateName) {
        return delegate.catalogTemplateInfo(templateName);
    }

    @Override
    public Map<String, Object> composeEvaluate(String item1Id, String item2Id) {
        return delegate.composeEvaluate(item1Id, item2Id);
    }

    @Override
    public Map<String, Object> composeBind(String item1Id, String item2Id, String intent) {
        return delegate.composeBind(item1Id, item2Id, intent);
    }

    @Override
    public List<Map<String, Object>> inventoryList() {
        return delegate.inventoryList();
    }

    @Override
    public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
        return delegate.inventoryUse(itemId, params, depth);
    }
}
