package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers (form-shaping across zones) and §16.4
 * (named-familiar cross-zone access) primitives.
 *
 * <p>These are structural-integration tests at the primitive level. Full
 * NATS-backed two-zone verification lives in
 * {@code e2e-test/tier3/CrossZoneCopyE2ETest}. Here we lock in the
 * invariants that make those higher-level flows safe:</p>
 *
 * <ul>
 *   <li>§16.2 — ownership follows the <em>author</em>, not the <em>soil</em>
 *       where the form was shaped. A form authored on host zone but destined
 *       for visitor's home FamilyLocker still carries visitor as provenance
 *       author.</li>
 *   <li>§16.4 — a summon key issued by a home-zone agent validates on any
 *       zone via {@code DidKey.publicKeyFromDid}, with no host-side key
 *       registry needed.</li>
 * </ul>
 */
class CrossZoneFormAndFamiliarTest {

    /**
     * §16.2 — "No form ever belongs to the host by virtue of being shaped
     * there. Ownership follows the author, not the soil."
     */
    @Test
    void cross_zone_form_shape_preserves_original_author() {
        var homeDid = "did:key:zVisitor";
        var form = ThoughtForm.author(homeDid, "xzone_shaped",
            "Task shaped on home soil.", Set.of(), "");
        assertThat(form.provenance().originalAuthor()).isEqualTo(homeDid);

        // Fork — even if the shape happens on host compute, the provenance
        // chain attributes authorship to the visitor's DID.
        var fork = FormTransfer.copy(form, homeDid,
            FormTransfer.Intent.GIFT, "mirror to home locker");
        assertThat(fork.provenance().originalAuthor()).isEqualTo(homeDid);
    }

    /**
     * §16.2 — host zone's FamilyLocker rejects direct shape-storage by a DID
     * that isn't part of the host's family. The visitor's form must flow to
     * their own home-zone locker instead; this is the structural rail that
     * prevents forms from being "stolen" by the soil they were shaped on.
     */
    @Test
    void host_locker_rejects_visitor_shape() {
        var visitorDid = "did:key:zVisitor";
        var hostFamilyDid = "family-host";
        var form = ThoughtForm.author(visitorDid, "shaped_on_host",
            "Work task.", Set.of(), "");

        // Host family locker has its own authorized DID (not the visitor's)
        var hostBud = SoulBud.original("did:key:zHost", "z6MkHost", hostFamilyDid,
            "locker://host", "host-node", "qwen2.5:4b");
        var hostLocker = FamilyLocker.create(hostFamilyDid, "locker://host", hostBud);

        try {
            hostLocker.shapeThoughtForm(form, visitorDid);
            throw new AssertionError("expected authorization rejection, got none");
        } catch (Exception e) {
            // Expected — visitor DID not authorized to write to host family locker
        }
    }

    /**
     * §16.4 — a summon key issued by a home-zone agent validates anywhere
     * via {@code DidKey.publicKeyFromDid}. Proves the primitive that makes
     * cross-zone named-familiar access possible: no host-side key registry
     * required, signature verifies from DID alone.
     */
    @Test
    void summon_key_validates_across_zones_via_did_key() throws Exception {
        // Owner generates a real Ed25519 pair. did:key embeds the public key,
        // so any zone can resolve it without calling home.
        var ownerPair = DidKey.generate();
        var ownerDid = ownerPair.did();

        // DidKey.publicKeyFromDid should reconstruct the same pub-key material
        var recovered = DidKey.publicKeyFromDid(ownerDid);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().getEncoded())
            .isEqualTo(ownerPair.keyPair().getPublic().getEncoded());
        // That's what makes §16.4 cross-zone summon-key validation possible
        // without any host-side key registry — validation runs purely from
        // the DID. See SummonKeyRegistry.validate(key, callerDid, now).
    }

    /**
     * §16.3 — cross-zone tool copy preserves provenance (full cross-zone
     * transit is covered in e2e-test/tier3/CrossZoneCopyE2ETest; this locks
     * in the primitive invariant at the core-module level).
     */
    @Test
    void cross_zone_tool_copy_preserves_provenance() {
        var ownerDid = "did:key:zOwner";
        var tool = SoulItem.create(
            "skill", "cross_zone_tool",
            "A tool about to travel.",
            ownerDid, 0.5);
        var copy = ToolTransfer.copy(tool, ownerDid,
            FormTransfer.Intent.GIFT, "taking it along");
        assertThat(copy.creatorDid()).isEqualTo(ownerDid);
        assertThat(copy.label()).isEqualTo("cross_zone_tool");
    }
}
