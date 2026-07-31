package org.wyrdsekai.server.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.library.StudyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the JSON wire contract between the server StudySyncPeer and the clients'
 * StudySyncLayer — the highest-risk seam (camelCase field names, vectorClock as a
 * JSON object, tolerance of the client-only {@code conflictVersions} field). The
 * CRDT merge logic itself is covered by StudyServiceTest; live phone↔zone
 * convergence is verified separately.
 */
class StudySyncPeerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void parses_a_client_shaped_study_item_including_unknown_conflictVersions() throws Exception {
        // Exactly what the RN/KMP StudySyncLayer puts in study_delta.items[]:
        // camelCase, vectorClock as an object, plus the client-only conflictVersions
        // field the server record doesn't have (must be tolerated, not throw).
        var json = """
            [{
              "id":"note:z1:100","userDid":"did:key:zAlice","itemType":"note",
              "title":"t","content":"hello from phone","collection":"notes",
              "timestamp":100,"version":2,
              "vectorClock":{"phone-A":3,"srv-zone":1},
              "lastModifiedBy":"phone-A","deleted":false,
              "conflictVersions":[]
            }]""";
        var items = StudySyncPeer.parseItems(M.readTree(json));
        assertEquals(1, items.size());
        var it = items.get(0);
        assertEquals("note:z1:100", it.id());
        assertEquals("did:key:zAlice", it.userDid());
        assertEquals("note", it.itemType());
        assertEquals("hello from phone", it.content());
        assertEquals(2, it.version());
        assertEquals(3, it.vectorClock().get("phone-A"));
        assertEquals(1, it.vectorClock().get("srv-zone"));
        assertEquals("phone-A", it.lastModifiedBy());
        assertFalse(it.deleted());
    }

    @Test
    void tombstone_and_missing_optional_fields_parse() throws Exception {
        // A delete tombstone with minimal fields; missing lastModifiedBy is fine.
        var json = """
            [{"id":"n1","userDid":"u","itemType":"note","title":"","content":"",
              "collection":"notes","timestamp":1,"version":1,
              "vectorClock":{"phone-A":2},"deleted":true}]""";
        var items = StudySyncPeer.parseItems(M.readTree(json));
        assertEquals(1, items.size());
        assertTrue(items.get(0).deleted());
        assertNull(items.get(0).lastModifiedBy());
    }

    @Test
    void a_malformed_item_is_skipped_not_fatal() throws Exception {
        var json = """
            [ "not-an-object",
              {"id":"ok","userDid":"u","itemType":"note","title":"","content":"c",
               "collection":"notes","timestamp":1,"version":1,"vectorClock":{},"deleted":false} ]""";
        var items = StudySyncPeer.parseItems(M.readTree(json));
        assertEquals(1, items.size(), "the one valid item survives a malformed sibling");
        assertEquals("ok", items.get(0).id());
    }

    @Test
    void merge_item_serializes_to_client_shaped_camelCase_json() throws Exception {
        // What the server puts on the wire must be what the client's StudyItem reads.
        var item = new StudyService.StudyMergeItem(
            "note:z1:1", "did:key:zBob", "note", "T", "server text", "notes",
            42L, 1, Map.of("srv-zone", 1), "srv-zone", false);
        var node = M.valueToTree(item);
        assertEquals("note:z1:1", node.get("id").asText());
        assertEquals("did:key:zBob", node.get("userDid").asText());
        assertEquals("note", node.get("itemType").asText());
        assertEquals("server text", node.get("content").asText());
        assertTrue(node.get("vectorClock").isObject(), "vectorClock must be a JSON object");
        assertEquals(1, node.get("vectorClock").get("srv-zone").asInt());
        assertEquals("srv-zone", node.get("lastModifiedBy").asText());
        assertFalse(node.get("deleted").asBoolean());
    }
}
