package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ItemWorldApi.ArtifactApi}
 * gating + delegation tests.
 */
class ArtifactApiTest {

    @Test
    void artifact_create_requires_artifact_write_cap() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.artifact.create("chart", "application/json", "{}"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void artifact_create_routes_through_provider_when_cap_held() {
        var captured = new AtomicReference<String>();
        var provider = new StubProvider() {
            @Override public Map<String, Object> artifactCreate(String kind, String mime,
                                                                  Object payload, Map<String, Object> opts) {
                captured.set(kind);
                return Map.of("ok", true, "id", "art_1");
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of("artifact.write")));
        var res = api.artifact.create("chart", "application/json", Map.of("k", "v"));
        assertThat(captured.get()).isEqualTo("chart");
        assertThat(res.get("id")).isEqualTo("art_1");
    }

    @Test
    void artifact_get_implicit_tier1_no_cap_required() {
        var captured = new AtomicReference<String>();
        var provider = new StubProvider() {
            @Override public Map<String, Object> artifactGet(String id) {
                captured.set(id);
                return Map.of("ok", true, "payload", "x");
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of()));
        api.artifact.get("art_xyz");
        assertThat(captured.get()).isEqualTo("art_xyz");
    }

    @Test
    void artifact_list_implicit_tier1_returns_provider_value() {
        var provider = new StubProvider() {
            @Override public List<Map<String, Object>> artifactList(Map<String, Object> filter) {
                return List.of(Map.of("id", "art_1"), Map.of("id", "art_2"));
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of()));
        var list = api.artifact.list();
        assertThat(list).hasSize(2);
    }

    @Test
    void artifact_attach_requires_attach_room_cap() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.artifact.attach("room-1", "art_1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void artifact_attach_succeeds_with_cap() {
        var captured = new AtomicReference<String>();
        var provider = new StubProvider() {
            @Override public Map<String, Object> artifactAttach(String roomId, String id) {
                captured.set(roomId + "/" + id);
                return Map.of("ok", true);
            }
        };
        var api = new ItemWorldApi(provider,
            ItemCapabilitySet.of(List.of("artifact.attach.room")));
        api.artifact.attach("room-1", "art_42");
        assertThat(captured.get()).isEqualTo("room-1/art_42");
    }

    @Test
    void artifact_revoke_requires_artifact_write_cap() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.artifact.revoke("art_1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void artifact_default_provider_returns_not_wired_error() {
        var api = new ItemWorldApi(new StubProvider(),
            ItemCapabilitySet.of(List.of("artifact.write")));
        var res = api.artifact.create("kind", "mime", "x");
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error").toString()).contains("not wired");
    }

    static class StubProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }
}
