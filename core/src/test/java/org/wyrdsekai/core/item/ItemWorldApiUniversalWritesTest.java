package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * -§4.3 — round-trip the new write APIs
 * through {@link ItemWorldApiProviderImpl} backed by real
 * {@link WyrdLuceneStore} and {@link StudyService}.
 */
class ItemWorldApiUniversalWritesTest {

    @TempDir Path tempDir;

    private WyrdLuceneStore luceneStore;
    private ItemScriptExecutor executor;
    private ItemWorldApiProviderImpl provider;
    private static final String AGENT_ID = "did:wyrd:test-agent";

    @BeforeEach
    void setUp() {
        luceneStore = new WyrdLuceneStore(tempDir, 384);
        luceneStore.ensureAllCollections();
        executor = new ItemScriptExecutor();

        provider = new ItemWorldApiProviderImpl(
            luceneStore, null, null, null,
            AGENT_ID, "Test Agent",
            text -> {},
            content -> {},
            (target, msg) -> {},
            null, executor);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.close();
        luceneStore.close();
    }

    @Test
    void library_delete_round_trip() {
        var addCaps = ItemCapabilitySet.of(List.of("library.add"));
        var addRes = executor.execute("library_writer",
            "function invoke(p){return world.library.add(p.text,{title:'Banshee'});}",
            Map.of("text", "The banshee wails before a death."),
            provider, addCaps);
        var id = String.valueOf(addRes.get("id"));
        assertNotNull(id);

        var beforeFound = provider.searchKnowledge("banshee", 5);
        assertFalse(beforeFound.isEmpty(), "search should find the chunk before delete");

        var delCaps = ItemCapabilitySet.of(List.of("library.delete"));
        var delRes = executor.execute("library_deleter",
            "function invoke(p){return world.library.delete(p.id);}",
            Map.of("id", id), provider, delCaps);
        assertEquals(true, delRes.get("ok"), "delete should succeed");
        assertEquals(id, delRes.get("chunkId"));
        assertNotNull(delRes.get("deletedAt"));

        var afterFound = provider.searchKnowledge("banshee", 5);
        boolean stillThere = afterFound.stream()
            .anyMatch(r -> id.equals(String.valueOf(r.get("id"))));
        assertFalse(stillThere, "deleted chunk must not appear in search");
    }

    /**
     * An id nobody wrote is refused as not-yours, not reported as missing.
     * Ownership is checked BEFORE existence on purpose (2026-08-25): if a
     * refusal said "not_found" for chunks that exist and "not_yours" for
     * chunks that don't, the refusal itself would be a probe for which ids
     * are real. A chunk the caller DID author, but which is gone, still
     * reports not_found — see below.
     */
    @Test
    void library_delete_refuses_an_id_the_caller_did_not_write() {
        var delCaps = ItemCapabilitySet.of(List.of("library.delete"));
        var delRes = executor.execute("library_deleter",
            "function invoke(p){return world.library.delete(p.id);}",
            Map.of("id", "no-such-chunk"), provider, delCaps);
        assertEquals(false, delRes.get("ok"));
        assertEquals("not_yours", delRes.get("reason"));
    }

    @Test
    void library_delete_returns_not_found_for_the_callers_own_missing_id() {
        var delCaps = ItemCapabilitySet.of(List.of("library.delete"));
        var delRes = executor.execute("library_deleter",
            "function invoke(p){return world.library.delete(p.id);}",
            Map.of("id", "lib:" + AGENT_ID + ":no-such-uuid"), provider, delCaps);
        assertEquals(false, delRes.get("ok"));
        assertEquals("not_found", delRes.get("reason"));
    }

    @Test
    void library_tag_updates_and_persists_subject_field() {
        var addCaps = ItemCapabilitySet.of(List.of("library.add"));
        var addRes = executor.execute("library_writer", """
            function invoke(p){
              return world.library.add(p.text, {title:'Wyvern', tags:['draco','fire']});
            }
            """,
            Map.of("text", "The wyvern coils above the keep."),
            provider, addCaps);
        var id = String.valueOf(addRes.get("id"));

        var tagCaps = ItemCapabilitySet.of(List.of("library.tag"));
        var tagRes = executor.execute("library_tagger", """
            function invoke(p){
              return world.library.tag(p.id, ['operator', 'beast', 'aerial']);
            }
            """,
            Map.of("id", id), provider, tagCaps);
        assertEquals(true, tagRes.get("ok"), "tag should succeed");
        assertEquals(id, tagRes.get("chunkId"));

        var refetched = luceneStore.getById(
            SearchCollections.KNOWLEDGE, id);
        assertNotNull(refetched, "chunk must still exist after retag");
        var subject = String.valueOf(refetched.metadata().getOrDefault("subject", ""));
        assertEquals("operator|beast|aerial", subject,
            "subject field should hold the new pipe-delimited tags");
        assertTrue(refetched.content().contains("wyvern"),
            "content must be preserved across tag update");
    }

    @Test
    void library_tag_refuses_a_chunk_the_caller_did_not_write() {
        var tagCaps = ItemCapabilitySet.of(List.of("library.tag"));
        var res = executor.execute("library_tagger",
            "function invoke(p){return world.library.tag(p.id, ['x']);}",
            Map.of("id", "ghost-chunk-id"), provider, tagCaps);
        assertEquals(false, res.get("ok"));
        assertEquals("not_yours", res.get("error"));
    }

    @Test
    void library_tag_missing_chunk_errors() {
        var tagCaps = ItemCapabilitySet.of(List.of("library.tag"));
        var res = executor.execute("library_tagger",
            "function invoke(p){return world.library.tag(p.id, ['x']);}",
            Map.of("id", "lib:" + AGENT_ID + ":ghost"), provider, tagCaps);
        assertEquals(false, res.get("ok"));
        assertEquals("chunk not found", res.get("error"));
    }

    @Test
    void notes_delete_round_trip() {
        var addCaps = ItemCapabilitySet.of(List.of("notes.add"));
        var addRes = executor.execute("note_taker",
            "function invoke(p){return world.notes.add(p.content);}",
            Map.of("content", "Buy milk"), provider, addCaps);
        var id = String.valueOf(addRes.get("id"));
        assertNotNull(id);

        var delCaps = ItemCapabilitySet.of(List.of("notes.delete"));
        var delRes = executor.execute("note_deleter",
            "function invoke(p){return world.notes.delete(p.id);}",
            Map.of("id", id), provider, delCaps);
        assertEquals(true, delRes.get("ok"));
    }

    @Test
    void notes_delete_rejects_non_owner() {
        // Owner adds a note via studyService directly so the user_did is fixed
        var study = new StudyService(luceneStore);
        var noteId = study.addNote("did:wyrd:other-owner", "private thought");

        // Our provider's agent is AGENT_ID — different from owner.
        var delCaps = ItemCapabilitySet.of(List.of("notes.delete"));
        var delRes = executor.execute("note_deleter",
            "function invoke(p){return world.notes.delete(p.id);}",
            Map.of("id", noteId), provider, delCaps);
        assertEquals(false, delRes.get("ok"));
        assertEquals("not_owner", delRes.get("reason"));
    }

    @Test
    void pinboard_unpin_round_trip() {
        var pinCaps = ItemCapabilitySet.of(List.of("pinboard.pin"));
        var pinRes = executor.execute("pinner",
            "function invoke(p){return world.pinboard.pin(p.text);}",
            Map.of("text", "Refile tax docs"), provider, pinCaps);
        var id = String.valueOf(pinRes.get("id"));

        var unpinCaps = ItemCapabilitySet.of(List.of("pinboard.unpin"));
        var unpinRes = executor.execute("unpinner",
            "function invoke(p){return world.pinboard.unpin(p.id);}",
            Map.of("id", id), provider, unpinCaps);
        assertEquals(true, unpinRes.get("ok"));
    }

    @Test
    void pinboard_unpin_rejects_non_owner() {
        var study = new StudyService(luceneStore);
        var pinId = study.pin("did:wyrd:other-owner", "Their pin", "k", "snippet");

        var unpinCaps = ItemCapabilitySet.of(List.of("pinboard.unpin"));
        var unpinRes = executor.execute("unpinner",
            "function invoke(p){return world.pinboard.unpin(p.id);}",
            Map.of("id", pinId), provider, unpinCaps);
        assertEquals(false, unpinRes.get("ok"));
        assertEquals("not_owner", unpinRes.get("reason"));
    }

    @Test
    void library_add_persists_and_search_finds() {
        var addScript = """
            function invoke(p) {
              return world.library.add(p.text, { title: p.title, pack: 'agent' });
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("library.add"));
        var result = executor.execute("library_writer", addScript,
            Map.of("text", "The phoenix is reborn from ash.", "title", "Phoenix"),
            provider, caps);
        assertNotNull(result.get("id"), "library.add must return an id");

        var found = provider.searchKnowledge("phoenix", 5);
        assertFalse(found.isEmpty(), "search should find the inserted chunk");
    }

    @Test
    void room_emit_errors_without_bridge_no_silent_fallback() {
        var spoke = new AtomicReference<String>();
        var p2 = new ItemWorldApiProviderImpl(
            luceneStore, null, null, null,
            AGENT_ID, "Test Agent",
            spoke::set,
            content -> {},
            (target, msg) -> {},
            null, executor);

        var script = """
            function invoke(p) {
              return world.room.emit('greeting', { text: 'hello room' });
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("room.emit"));
        var result = executor.execute("emitter", script, Map.of(), p2, caps);
        assertEquals("no_room_bridge", result.get("error"),
            "room.emit must error (not fallback) when bridge is unwired");
        assertNull(spoke.get(),
            "speak must NOT have been called as a silent fallback");
    }

    @Test
    void room_narrate_errors_without_bridge_no_silent_fallback() {
        var spoke = new AtomicReference<String>();
        var p2 = new ItemWorldApiProviderImpl(
            luceneStore, null, null, null,
            AGENT_ID, "Test Agent",
            spoke::set,
            content -> {},
            (target, msg) -> {},
            null, executor);
        var script = """
            function invoke(p) {
              return world.room.narrate(p.text);
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("room.narrate"));
        var result = executor.execute("narrator", script,
            Map.of("text", "the wind rises"), p2, caps);
        assertEquals("no_room_bridge", result.get("error"));
        assertNull(spoke.get());
    }

    @Test
    void room_emit_routes_through_bridge_when_wired() {
        var emittedEvents = new ArrayList<Map<String, Object>>();
        var bridge = new ItemWorldApiProviderImpl.RoomBridge() {
            @Override public String roomId() { return "test-room"; }
            @Override public void emit(String eventType, Map<String, Object> data) {
                var record = new HashMap<String, Object>();
                record.put("eventType", eventType);
                record.put("data", data);
                emittedEvents.add(record);
            }
        };
        var p2 = new ItemWorldApiProviderImpl(
            luceneStore, null, null, null,
            AGENT_ID, "Test Agent",
            t -> {}, c -> {}, (a, b) -> {},
            null, executor);
        p2.setRoomBridge(bridge);

        var script = """
            function invoke(p) {
              return world.room.emit('scrying.frame', { phase: 'flicker', x: 7 });
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("room.emit"));
        var result = executor.execute("emitter", script, Map.of(), p2, caps);

        assertEquals(true, result.get("ok"));
        assertEquals(true, result.get("queued"));
        assertEquals(1, emittedEvents.size());
        assertEquals("scrying.frame", emittedEvents.get(0).get("eventType"));

        // Subsequent calls without re-wiring must error (no leakage)
        p2.setRoomBridge(null);
        var resAfter = executor.execute("emitter2",
            "function invoke(p){return world.room.emit('x',{});}",
            Map.of(), p2, caps);
        assertEquals("no_room_bridge", resAfter.get("error"));
    }

    @Test
    void room_narrate_routes_through_bridge_when_wired() {
        var narrated = new AtomicReference<String>();
        var bridge = new ItemWorldApiProviderImpl.RoomBridge() {
            @Override public void narrate(String text) { narrated.set(text); }
        };
        var p2 = new ItemWorldApiProviderImpl(
            luceneStore, null, null, null,
            AGENT_ID, "Test Agent",
            t -> {}, c -> {}, (a, b) -> {},
            null, executor);
        p2.setRoomBridge(bridge);

        var script = """
            function invoke(p) {
              return world.room.narrate(p.text);
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("room.narrate"));
        var result = executor.execute("narrator", script,
            Map.of("text", "the lantern brightens"), p2, caps);
        assertEquals(true, result.get("ok"));
        assertEquals("the lantern brightens", narrated.get());
    }

    @Test
    void memory_add_alias_maps_to_remember() {
        var captured = new AtomicReference<String>();
        var p2 = new ItemWorldApiProviderImpl(
            luceneStore, null, null, null,
            AGENT_ID, "Test Agent",
            text -> {},
            captured::set,           // remember callback
            (target, msg) -> {},
            null, executor);
        var script = """
            function invoke(p) {
              return world.memory.add(p.content);
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("memory.add"));
        var result = executor.execute("memer", script,
            Map.of("content", "important fact"), p2, caps);
        assertEquals("important fact", captured.get());
        assertEquals(true, result.get("ok"));
    }

    @Test
    void adapter_dispatches_through_external_registry() {
        var registry = ExternalAdapterRegistry.get();
        registry.clearForTests();
        registry.register(new ExternalAdapter() {
            @Override public String namespace() { return "fakeservice"; }
            @Override public Set<String> capabilities() {
                return Set.of("ping");
            }
            @Override public String credentialSlot() { return "fakeservice.token"; }
            @Override public AdapterResponse invoke(
                    AdapterRequest req) {
                return AdapterResponse.ok(
                    Map.of("pong", true, "method", req.method()));
            }
        });
        var script = """
            function invoke(p) {
              return world.fakeservice.ping({ x: 1 });
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("fakeservice.ping"));
        var result = executor.execute("svc_caller", script, Map.of(), provider, caps);
        assertEquals(true, result.get("success"));
        registry.clearForTests();
    }
}
