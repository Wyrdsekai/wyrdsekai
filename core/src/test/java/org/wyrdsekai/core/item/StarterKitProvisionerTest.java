package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import static org.assertj.core.api.Assertions.assertThat;

class StarterKitProvisionerTest {

    private static final String DID = "did:key:z6MkTest";
    private static final String FAMILY_ID = "test-family";

    static FamilyLocker testLocker() {
        var bud = SoulBud.original(DID, "z6MkTestPub", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        return FamilyLocker.create(FAMILY_ID, "locker://test", bud);
    }

    @Test void standard_kit_has_seven_items() {
        var items = StarterKitProvisioner.standardKit(DID);
        assertThat(items).hasSize(7);
    }

    @Test void phone_kit_has_three_items() {
        var items = StarterKitProvisioner.phoneKit(DID);
        assertThat(items).hasSize(3);
    }

    @Test void standard_kit_contains_aspects_and_reagents() {
        var items = StarterKitProvisioner.standardKit(DID);
        long aspects = items.stream().filter(i -> "aspect".equals(i.category())).count();
        long reagents = items.stream().filter(i -> "reagent".equals(i.category())).count();
        assertThat(aspects).isEqualTo(5); // Everyday, Focused, Social, Compass, Journal
        assertThat(reagents).isEqualTo(2); // 2x Restoring Draught
    }

    @Test void phone_kit_contains_aspects_and_reagents() {
        var items = StarterKitProvisioner.phoneKit(DID);
        long aspects = items.stream().filter(i -> "aspect".equals(i.category())).count();
        long reagents = items.stream().filter(i -> "reagent".equals(i.category())).count();
        assertThat(aspects).isEqualTo(2); // Everyday, Focused
        assertThat(reagents).isEqualTo(1); // 1x Restoring Draught
    }

    @Test void everyday_garb_present() {
        var items = StarterKitProvisioner.standardKit(DID);
        var garb = items.stream().filter(i -> i.label().equals("Everyday Garb")).findFirst();
        assertThat(garb).isPresent();
        assertThat(garb.get().category()).isEqualTo("aspect");
    }

    @Test void pocket_journal_has_highest_significance() {
        var items = StarterKitProvisioner.standardKit(DID);
        var journal = items.stream().filter(i -> i.label().equals("Pocket Journal")).findFirst();
        assertThat(journal).isPresent();
        assertThat(journal.get().significance()).isEqualTo(0.5);
    }

    @Test void provision_stores_in_locker() {
        var locker = testLocker();
        var items = StarterKitProvisioner.provision(DID, 8192, locker);
        assertThat(items).hasSize(7);

        var stored = locker.byCategory("aspect", DID);
        assertThat(stored).hasSize(5);
        // Content-addressed: 2 identical draughts deduplicate to 1
        var reagents = locker.byCategory("reagent", DID);
        assertThat(reagents).hasSize(1);
    }

    @Test void provision_uses_phone_kit_below_threshold() {
        var locker = testLocker();
        var items = StarterKitProvisioner.provision(DID, 2048, locker);
        assertThat(items).hasSize(3);
    }

    @Test void provision_works_without_locker() {
        var items = StarterKitProvisioner.provision(DID, 8192, null);
        assertThat(items).hasSize(7);
    }

    @Test void all_items_have_valid_hashes() {
        var items = StarterKitProvisioner.standardKit(DID);
        for (var item : items) {
            assertThat(item.hash()).isNotNull().isNotBlank();
            assertThat(item.verifyIntegrity()).isTrue();
        }
    }
}
