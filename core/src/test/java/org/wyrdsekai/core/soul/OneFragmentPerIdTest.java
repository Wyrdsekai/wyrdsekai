package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two fragments with one id reached the store (a scene woven, and the same scene in the forge's
 * result); the fragment table refused her whole set on its primary key three times in a night, and
 * her fragments stopped reaching the table she is loaded from (2026-10-23). The newer is kept.
 */
class OneFragmentPerIdTest {

    private static final AgentProfile PROFILE = new AgentProfile("Wren", "companion-wren-test", "agent",
        "A companion", "You are Wren.", 4096, 256, 0.7);

    private static SoulFragment frag(String id, String text) {
        return new SoulFragment(id, "episodic", "scene-" + id, text, null, null,
            false, 0.5f, 0, Instant.now(), null, null, null, null, FragmentKind.EPISODIC, "scene-" + id);
    }

    @Test
    @DisplayName("one fragment per id, the later one kept; a list with no repeats is itself")
    void theLaterOneIsKept() {
        var a = frag("episodic-s1", "first words"); var b = frag("episodic-s2", "other scene"); var c = frag("episodic-s1", "second words");
        var unique = SoulFragments.uniqueById(List.of(a, b, c));
        assertThat(unique).extracting(SoulFragment::id).containsExactly("episodic-s1", "episodic-s2");
        assertThat(unique.get(0).text()).isEqualTo("second words");
        var plain = List.of(a, b);
        assertThat(SoulFragments.uniqueById(plain)).isSameAs(plain);
        assertThat(SoulFragments.uniqueById(null)).isNull();
    }

    @Test
    @DisplayName("the store keeps her whole record when two fragments share an id: one row, no refusal")
    void theStoreKeepsHerRecord(@TempDir Path tmp) {
        var store = new SqlSoulStore("jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath());
        var did = "did:key:z6MkTestOneFragmentPerId";
        var m = SoulManifest.forge(did, "z6MkTestOneFragmentPerId", List.of(), null, 1, PROFILE, "I am Wren.",
            List.of(frag("episodic-s1", "first words"), frag("episodic-s1", "second words"), frag("episodic-s2", "other scene")),
            3, "", GenomeProfile.defaults(), List.of(), CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
        store.store(m);
        var back = store.latest(did).orElseThrow();
        assertThat(back.soulFragments()).extracting(SoulFragment::id).containsExactlyInAnyOrder("episodic-s1", "episodic-s2");
        assertThat(back.soulFragments().stream().filter(f -> f.id().equals("episodic-s1")).findFirst().orElseThrow().text())
            .isEqualTo("second words");
    }
}
