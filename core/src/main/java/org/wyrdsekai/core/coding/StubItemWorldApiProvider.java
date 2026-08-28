package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

/**
 * Empty-result stub of {@link ItemWorldApiProvider} for execution
 * contexts that don't have backend services wired (the room actor's
 * {@code use <coding-item>} dispatch path, isolated unit tests, etc).
 *
 * <p>Every required abstract method returns the empty / null value of
 * its declared return type — calls succeed without crashing but produce
 * no real-world side effects. All optional methods inherit their
 * interface default no-op. Items that touch only Tier 1 surfaces
 * (math, json, JSON, regex) work normally because those live in
 * {@code ItemWorldApi} itself, not in the provider; items that try to
 * reach into knowledge / web / oracle / LLM / inventory get harmless
 * empties instead of NPE.</p>
 *
 * <p>This is a <b>stop-the-bleeding</b> fix: a properly-scoped
 * {@code RoomScopedItemProvider} that backs narration onto the room
 * actor's {@code notifySubscribers}, room hooks onto room-event
 * subscriptions, and inventory onto the calling player's bag is the
 * eventual target. Tracked as the items-as-tools provider injection
 * follow-up; until then this stub keeps {@code use <coding-item>}
 * crash-free for any item the agent might generate.</p>
 *
 * <p>Reusable across coding backends — every adapter that hands an
 * item to {@link org.wyrdsekai.scripting.sandbox.ItemScriptExecutor}
 * outside a companion context can pass an instance of this. Singleton
 * since the stub is stateless.</p>
 */
public final class StubItemWorldApiProvider implements ItemWorldApiProvider {

    public static final StubItemWorldApiProvider INSTANCE = new StubItemWorldApiProvider();

    private StubItemWorldApiProvider() {}

    @Override
    public List<Map<String, Object>> searchKnowledge(String query, int limit) {
        // A PLAUSIBLE answer, not an empty one. An empty result sends most items down
        // their "nothing found" early return, so the smoke never reached the code that
        // actually does the work — which is the code that breaks in a person's hands.
        // Still side-effect free: this invents a reply, it does not touch the library.
        return List.of(Map.of(
            "id", "smoke-1",
            "title", "A Smoke Fixture",
            "text", "A short passage the smoke harness supplies so an item's real path runs.",
            "pack", "smoke",
            "score", 0.9));
    }

    @Override
    public Map<String, Object> readKnowledgeChunk(String chunkId) {
        return Map.of("id", chunkId == null ? "smoke-1" : chunkId,
            "title", "A Smoke Fixture",
            "text", "A short passage the smoke harness supplies.",
            "pack", "smoke");
    }

    @Override
    public List<Map<String, Object>> webSearch(String query, String type, int limit) {
        return List.of(Map.of(
            "title", "A Smoke Fixture",
            "url", "https://example.invalid/smoke",
            "snippet", "A snippet the smoke harness supplies so an item's real path runs."));
    }

    @Override
    public String webFetch(String url, int maxChars) {
        return "Page text the smoke harness supplies.";
    }

    @Override
    public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
        return List.of();
    }

    @Override
    public String llmSummarize(String text, String instruction) {
        return "A summary the smoke harness supplies.";
    }

    @Override
    public Map<String, Object> llmComplete(String prompt, Map<String, Object> opts) {
        // Shaped like the real thing — {text: ...} — so an item that reads `.text` runs
        // its real path instead of falling into a "nothing came back" branch.
        return Map.of("text",
            "Once upon a time the smoke harness supplied two short paragraphs.\n\n"
                + "And the item's real path ran all the way to the end.");
    }

    public String llmAnalyze(String text, String prompt) {
        return "An analysis the smoke harness supplies.";
    }

    @Override
    public List<Map<String, Object>> inventoryList() {
        return List.of();
    }

    @Override
    public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
        return Map.of("ok", false, "error", "no provider — stub returns empty");
    }

    @Override
    public void agentSpeak(String text) {
        // No-op: stub provider has no narration channel.
        // RoomScopedItemProvider follow-up will route this to the
        // room actor's notifySubscribers as a Said event.
    }

    @Override
    public void agentRemember(String content) {
        // No-op: stub provider has no memory store.
    }

    @Override
    public void agentTell(String target, String message) {
        // No-op: stub provider has no tell channel.
    }
}
