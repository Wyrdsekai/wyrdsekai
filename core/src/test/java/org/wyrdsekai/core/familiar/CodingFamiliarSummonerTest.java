package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.soul.BondStore;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for CodingFamiliarSummoner — the first-summon ceremony service
 *
 * <p>The ZoneGuardian arg is left {@code null} for these unit tests; the
 * service handles that path defensively (Workshop room provisioning is a
 * "best effort" side-effect on top of the canonical identity-file truth).
 * Room provisioning is covered by {@link
 * org.wyrdsekai.core.room.WorkshopProvisionerTest} and any integration
 * test that brings up a real ZoneGuardian.</p>
 */
class CodingFamiliarSummonerTest {

    private static final String BONDHOLDER = "did:wyrd:user:operator";
    private static final String PARENT = "did:wyrd:companion:wyrd-of-operator";

    @TempDir Path workspace;

    private BondStore newBondStore() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("bonds.db"));
        return new BondStore(jdbc);
    }

    private CodingFamiliarRegistry newRegistry(Path soulsRoot) {
        return new CodingFamiliarRegistry(soulsRoot);
    }

    @Test void firstSummon_createsIdentityFromBlankState(@TempDir Path souls) throws IOException {
        var reg = newRegistry(souls);
        var summoner = new CodingFamiliarSummoner(reg, newBondStore(), null);

        var outcome = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, null);

        assertThat(outcome.alreadyExisted()).isFalse();
        assertThat(outcome.bondRecorded()).isTrue();
        assertThat(outcome.workshopRequested()).isFalse(); // zoneGuardian == null
        assertThat(outcome.identity().name()).isEqualTo("Coder");
        assertThat(outcome.identity().bondholderDid()).isEqualTo(BONDHOLDER);
        assertThat(outcome.identity().parentAgentDid()).isEqualTo(PARENT);
        assertThat(outcome.narration())
            .contains("Coder")
            .contains("Coding Familiar")
            .contains("wyrd-of-operator");
    }

    @Test void firstSummon_honoursChosenName(@TempDir Path souls) throws IOException {
        var summoner = new CodingFamiliarSummoner(
            newRegistry(souls), newBondStore(), null);
        var outcome = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, "弟子");
        assertThat(outcome.identity().name()).isEqualTo("弟子");
    }

    @Test void firstSummon_idempotent_reSummonNoOp(@TempDir Path souls) throws IOException {
        var reg = newRegistry(souls);
        var bond = newBondStore();
        var summoner = new CodingFamiliarSummoner(reg, bond, null);

        var first = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, "Coder");
        var second = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, "Coder");

        assertThat(first.alreadyExisted()).isFalse();
        assertThat(second.alreadyExisted()).isTrue();
        // Same DID & name on re-summon — no shadow identity created
        assertThat(second.identity().did()).isEqualTo(first.identity().did());
        assertThat(second.identity().name()).isEqualTo(first.identity().name());
        assertThat(second.narration()).contains("already here");
    }

    @Test void firstSummon_writesPersistentFile(@TempDir Path souls) throws IOException {
        var reg = newRegistry(souls);
        var summoner = new CodingFamiliarSummoner(reg, newBondStore(), null);
        summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, "Coder");

        // Fresh registry sees the same identity — proof of disk durability
        var fresh = newRegistry(souls);
        var loaded = fresh.get(BONDHOLDER);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("Coder");
        assertThat(loaded.get().parentAgentDid()).isEqualTo(PARENT);
    }

    @Test void firstSummon_recordsIdentityBond(@TempDir Path souls) throws IOException {
        var bond = newBondStore();
        var summoner = new CodingFamiliarSummoner(newRegistry(souls), bond, null);
        var outcome = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, null);

        var bondId = CodingFamiliarSummoner.bondIdFor(
            BONDHOLDER, outcome.identity().did());
        var saved = bond.get(bondId);
        assertThat(saved).isPresent();
        assertThat(saved.get().agentADid()).isEqualTo(BONDHOLDER);
        assertThat(saved.get().agentBDid()).isEqualTo(outcome.identity().did());
        assertThat(saved.get().depth()).isEqualTo(CodingFamiliarSummoner.INITIAL_BOND_DEPTH);
        assertThat(saved.get().active()).isTrue();
        assertThat(saved.get().interactionCount()).isEqualTo(1);
    }

    @Test void firstSummon_bondInteractionCountAccumulates(@TempDir Path souls) throws IOException {
        var bondStore = newBondStore();
        var summoner = new CodingFamiliarSummoner(newRegistry(souls), bondStore, null);

        var first = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, null);
        summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, null);
        summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, null);

        var bondId = CodingFamiliarSummoner.bondIdFor(BONDHOLDER, first.identity().did());
        var saved = bondStore.get(bondId).orElseThrow();
        // Each summon bumps the counter; formedAt stays at the first
        assertThat(saved.interactionCount()).isEqualTo(3);
    }

    @Test void firstSummon_nullBondStoreSkipsBondGracefully(@TempDir Path souls) throws IOException {
        var summoner = new CodingFamiliarSummoner(newRegistry(souls), null, null);
        var outcome = summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, null);
        assertThat(outcome.bondRecorded()).isFalse();
        // Identity still landed on disk — that's the canonical truth
        assertThat(outcome.identity().bondholderDid()).isEqualTo(BONDHOLDER);
    }

    @Test void firstSummon_rejectsBlankBondholder(@TempDir Path souls) {
        var summoner = new CodingFamiliarSummoner(newRegistry(souls), null, null);
        assertThatThrownBy(() -> summoner.firstSummon("", "Masumi", PARENT, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bondholderDid");
    }

    @Test void firstSummon_rejectsBlankParent(@TempDir Path souls) {
        var summoner = new CodingFamiliarSummoner(newRegistry(souls), null, null);
        assertThatThrownBy(() -> summoner.firstSummon(BONDHOLDER, "M", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parentAgentDid");
    }

    @Test void bondIdFor_isDeterministic() {
        var a = CodingFamiliarSummoner.bondIdFor(BONDHOLDER, "did:wyrd:familiar:codeplane:x");
        var b = CodingFamiliarSummoner.bondIdFor(BONDHOLDER, "did:wyrd:familiar:codeplane:x");
        assertThat(a).isEqualTo(b);
        assertThat(a).startsWith("coding-familiar:");
    }

    @Test void currentFamiliarFor_returnsEmptyWhenAbsent(@TempDir Path souls) {
        var summoner = new CodingFamiliarSummoner(newRegistry(souls), null, null);
        assertThat(summoner.currentFamiliarFor(BONDHOLDER)).isEmpty();
    }

    @Test void currentFamiliarFor_returnsPostSummonIdentity(@TempDir Path souls) throws IOException {
        var summoner = new CodingFamiliarSummoner(
            newRegistry(souls), newBondStore(), null);
        summoner.firstSummon(BONDHOLDER, "Masumi", PARENT, "Coder");

        var current = summoner.currentFamiliarFor(BONDHOLDER);
        assertThat(current).isPresent();
        assertThat(current.get().name()).isEqualTo("Coder");
    }
}
