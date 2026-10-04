package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No item script reads a join secret without declaring it, and no crafted item may declare it.
 *
 * <p>Reported 2026-10-03 and confirmed: {@code pairing.code} and {@code pairing.household_key} were in
 * the set of capabilities every item gets without declaring them, the reads behind them made no
 * capability check, the providers made no steward check, and {@code web.fetch} (which refuses only
 * this machine, the LAN and the cloud metadata address) was implicit too. A key presented to the
 * pairing door returns a device token, a bus login and the relay token. {@code pairing.pending}
 * carried every waiting device's code, and {@code invite.list} the invite codes. None of the reads
 * reached the audit hook, because none went through the check.
 */
class AJoinSecretIsNeverImplicitTest {

    static final List<String> JOIN_SECRETS = List.of("pairing.code", "pairing.household_key", "pairing.pending", "invite.list");

    @Test
    void the_capabilities_that_return_a_join_secret_are_not_implicit_and_not_a_crafted_items_to_have() {
        for (var cap : JOIN_SECRETS) {
            assertThat(ItemCapabilitySet.IMPLICIT).as(cap).doesNotContain(cap);
            assertThat(ItemCapabilitySet.CRAFTED_ALLOW).as(cap).doesNotContain(cap);
            assertThat(ItemCapabilitySet.craftedDefault().has(cap)).as(cap + " on a crafted item").isFalse();
            assertThat(ItemManifestValidator.isKnownCapability(cap)).as(cap + " can be declared").isTrue();
            assertThat(ItemManifestValidator.tierFor(cap)).as(cap + " sits at the tier of the act it enables").isGreaterThanOrEqualTo(6);
        }
        assertThat(ItemManifestValidator.tierFor("pairing.household_key"))
            .isEqualTo(ItemManifestValidator.tierFor("pairing.generate_household_key"));
    }

    @Test
    void every_read_of_a_join_secret_goes_through_the_check_and_so_through_the_audit() throws Exception {
        var rel = "scripting/src/main/java/org/wyrdsekai/scripting/api/ItemWorldApi.java";
        var from = Path.of("..", rel);
        var api = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(api).contains("caps.require(\"pairing.pending\");\n            return provider.pendingPairings();");
        assertThat(api).contains("caps.require(\"pairing.code\");\n            return provider.activePairCode();");
        assertThat(api.split("caps.require\\(\"pairing.household_key\"\\);", -1).length - 1).as("both spellings").isEqualTo(2);
        assertThat(api).contains("caps.require(\"invite.list\");\n            return provider.inviteList();");
    }

    @Test
    void a_companion_never_holds_the_secret_and_a_member_who_is_not_the_steward_does_not_either() throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        var companion = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java"));
        assertThat(companion).contains("public String activePairCode() {\n        return null;");
        assertThat(companion).contains("public String activeHouseholdKey() {\n        return null;");
        assertThat(companion).doesNotContain("m.put(\"code\", p.code());");
        var person = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/item/HomeOwnerItemProvider.java"));
        assertThat(person).contains("public String activePairCode() {\n        // A join secret is the steward's to read.");
        assertThat(person).contains("public String activeHouseholdKey() {\n        if (!isSteward()) return null;");
        assertThat(person).contains("if (steward) m.put(\"code\", p.code());");
        assertThat(person).contains("return withoutCodesUnlessSteward(callSupplier(pendingPairingsSupplier, \"pendingPairings\"));");
        // The one bundled item that shows the threshold declares what it reads.
        var ledger = Files.readString(base.resolve("scripts/items/device_ledger.js"));
        assertThat(ledger).contains("\"pairing.pending\", \"pairing.code\", \"pairing.household_key\"");
    }
}
