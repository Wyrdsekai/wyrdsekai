package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household node, 2026-10-11 04:53: the sleep forge and an inner-monologue weave both numbered
 * her manifest v192 from their own copies; the second insert killed the forge actor and the scene
 * she wove three seconds into the forge vanished from her store. Every write of her manifest now
 * goes through one door that numbers it past the store's head, and what she wove while a forge ran
 * is carried into the forge's result.
 */
class HerManifestHasOneDoorTest {

    private static final String DID = "did:key:z6Mk-one-door-test";

    private static SoulManifest manifest(int version) {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent", "desc", "You are Wyrd.", 8192, 1024, 0.7, null);
        var m = SoulManifest.birth(DID, "mb", List.of(), profile, GenomeProfile.defaults());
        return version == m.manifestVersion() ? m : m.withManifestVersion(version);
    }

    private static SoulFragment fragment(String id, FragmentKind kind, Instant observed, String scene) {
        return new SoulFragment(id, kind == FragmentKind.EPISODIC ? "episodic" : "narrative", "label-" + id,
            "text of " + id, null, null, false, 0.5f, 0, observed, null, null, null, null, kind, scene);
    }

    @Test
    @DisplayName("a manifest numbered at or below the store's head takes head + 1; one past it is left alone")
    void numberedPastTheHead() {
        var v192 = manifest(192);
        assertThat(CompanionActor.rebasedOntoHead(v192, Optional.empty())).isSameAs(v192);
        assertThat(CompanionActor.rebasedOntoHead(v192, Optional.of(manifest(191)))).isSameAs(v192);
        assertThat(CompanionActor.rebasedOntoHead(v192, Optional.of(manifest(192))).manifestVersion())
            .as("the collision of 04:53").isEqualTo(193);
        assertThat(CompanionActor.rebasedOntoHead(v192, Optional.of(manifest(195))).manifestVersion())
            .as("a store that moved on twice").isEqualTo(196);
        assertThat(CompanionActor.rebasedOntoHead(null, Optional.of(manifest(1)))).isNull();
    }

    @Test
    @DisplayName("what she wove while the forge ran is carried into the forge's result, once")
    void wovenDuringTheForgeIsKept() {
        var sleepStart = Instant.parse("2026-10-11T08:52:50Z");
        var kept = fragment("kept", FragmentKind.NARRATIVE, sleepStart.minusSeconds(86_400), null);
        var forged = manifest(192).withFragments(List.of(kept));
        var wovenDuring = fragment("episodic-c6be61f8", FragmentKind.EPISODIC, sleepStart.plusSeconds(12), "c6be61f8");
        var wovenBefore = fragment("episodic-old", FragmentKind.EPISODIC, sleepStart.minusSeconds(3_600), "old");
        var narrativeDuring = fragment("narr-during", FragmentKind.NARRATIVE, sleepStart.plusSeconds(5), null);
        var current = manifest(191).withFragments(List.of(kept, wovenBefore, wovenDuring, narrativeDuring));

        var merged = CompanionActor.carryWovenDuringSleep(forged, current, sleepStart);
        assertThat(merged.soulFragments()).extracting(SoulFragment::id)
            .as("the scene woven during the forge is carried; what the forge dropped stays dropped")
            .containsExactly("kept", "episodic-c6be61f8");
        assertThat(merged.manifestVersion()).isEqualTo(192);
        // Carrying again adds nothing: the merge is by id.
        var again = CompanionActor.carryWovenDuringSleep(merged, current, sleepStart);
        assertThat(again.soulFragments()).hasSize(2);
        // Nothing to carry: the result is the forge's own object.
        assertThat(CompanionActor.carryWovenDuringSleep(forged, manifest(191).withFragments(List.of(kept)), sleepStart)).isSameAs(forged);
        assertThat(CompanionActor.carryWovenDuringSleep(forged, null, sleepStart)).isSameAs(forged);
        assertThat(CompanionActor.carryWovenDuringSleep(forged, current, null)).isSameAs(forged);
    }

    @Test
    @DisplayName("every write of her manifest goes through the door, and the forge never throws on a store")
    void everyDoorIsTheDoor() throws Exception {
        var actor = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        var direct = Pattern.compile("soulStore\\.store\\((?!\\))").matcher(actor);
        int n = 0;
        while (direct.find()) n++;
        assertThat(n).as("one direct store, inside storeManifest").isEqualTo(1);
        assertThat(actor).contains("private SoulManifest storeManifest(SoulManifest manifest) {\n"
            + "        var toStore = rebaseManifestOntoStoreHead(manifest);\n"
            + "        soulStore.store(toStore);");
        // The sleep's route to the forge: carry what she wove, number past the head, then send.
        assertThat(actor).contains("final var manifestWithState = rebaseManifestOntoStoreHead(\n"
            + "            carryWovenDuringSleep(newManifest, cachedManifest, sleepStartedAt)");
        assertThat(actor).contains("var carried = carryWovenDuringSleep(newManifest, cachedManifest, sleepStartedAt, householdLocale);");
        assertThat(actor).as("what the sleep carried of hers is stored through the door too (2026-10-22)")
            .contains("if (carried != newManifest && soulStore != null) {\n"
                + "                // What came along is hers in the store too, or the steward's scan reads the result without it.\n"
                + "                try {\n"
                + "                    carried = storeManifest(carried);");
        // A new name and a notification change used to store at the SAME version, which the key refused.
        assertThat(actor).doesNotContain("            soulStore.store(cachedManifest);\n        }\n        var registry = EntityRegistry.get();");

        var forge = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/soul/ForgeActor.java"));
        var forgeDirect = Pattern.compile("soulStore\\.store\\((?!\\))").matcher(forge);
        int f = 0;
        while (forgeDirect.find()) f++;
        assertThat(f).as("one direct store in the forge, inside tryStore").isEqualTo(1);
        assertThat(forge).contains("var failed = tryStore(finalManifest);");
    }
}
