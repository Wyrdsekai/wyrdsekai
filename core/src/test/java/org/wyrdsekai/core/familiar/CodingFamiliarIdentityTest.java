package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for CodingFamiliarIdentity — covers the §3.2 soul-shape record's
 * invariants, default-construction, and structural updates.
 */
class CodingFamiliarIdentityTest {

    private static final String BONDHOLDER = "did:wyrd:user:operator";
    private static final String PARENT = "did:wyrd:companion:wyrd-of-operator";

    @Test void newBorn_setsDefaults() {
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, null);

        assertThat(id.did()).isEqualTo(
            "did:wyrd:familiar:codezaiku:did:wyrd:user:operator");
        assertThat(id.name()).isEqualTo("Coder");
        assertThat(id.kindSubtype()).isEqualTo("coding-familiar");
        assertThat(id.bondholderDid()).isEqualTo(BONDHOLDER);
        assertThat(id.parentAgentDid()).isEqualTo(PARENT);
        assertThat(id.autonomyTier()).isEqualTo("ASSISTED");
        assertThat(id.promotionEligible()).isFalse();
        assertThat(id.sharedSubstrateWith()).containsExactly(PARENT);
        assertThat(id.preferredLanguageStacks()).isEmpty();
        assertThat(id.preferredTaskShapes()).isEmpty();
        assertThat(id.codingDNA()).isEmpty();
        assertThat(id.soulFragmentIds()).isEmpty();
        assertThat(id.modeLock()).isNull();
        assertThat(id.createdAt()).isNotNull();
    }

    @Test void newBorn_acceptsCustomName() {
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "Compañero");
        assertThat(id.name()).isEqualTo("Compañero");
    }

    @Test void newBorn_blankNameFallsBackToDefault() {
        assertThat(CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "").name())
            .isEqualTo("Coder");
        assertThat(CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "   ").name())
            .isEqualTo("Coder");
    }

    @Test void didFor_buildsCanonicalDid() {
        assertThat(CodingFamiliarIdentity.didFor(BONDHOLDER))
            .isEqualTo("did:wyrd:familiar:codezaiku:did:wyrd:user:operator");
    }

    @Test void bondholderDidFromFamiliarDid_extractsBondholder() {
        var familiarDid = CodingFamiliarIdentity.didFor(BONDHOLDER);
        assertThat(CodingFamiliarIdentity.bondholderDidFromFamiliarDid(familiarDid))
            .isEqualTo(BONDHOLDER);
        assertThat(CodingFamiliarIdentity.bondholderDidFromFamiliarDid("did:wyrd:user:x"))
            .isNull();
        assertThat(CodingFamiliarIdentity.bondholderDidFromFamiliarDid(null)).isNull();
    }

    @Test void rejectsBlankRequiredFields() {
        assertThatThrownBy(() -> new CodingFamiliarIdentity(
            "did:wyrd:familiar:codezaiku:x", "",
            "coding-familiar", BONDHOLDER, PARENT,
            Instant.now(), false, null, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");

        assertThatThrownBy(() -> new CodingFamiliarIdentity(
            "wrong:prefix", "Coder", "coding-familiar", BONDHOLDER, PARENT,
            Instant.now(), false, null, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("did must start with");
    }

    @Test void withName_producesUpdatedIdentity() {
        var a = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, null);
        var b = a.withName("弟子");
        assertThat(b.name()).isEqualTo("弟子");
        assertThat(b.did()).isEqualTo(a.did());
        assertThat(b.bondholderDid()).isEqualTo(a.bondholderDid());
        assertThat(a.name()).isEqualTo("Coder"); // original unchanged
    }

    @Test void withFragment_appendsAndDedupes() {
        var a = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, null);
        var b = a.withFragment("frag-1");
        var c = b.withFragment("frag-2");
        var d = c.withFragment("frag-1"); // duplicate
        assertThat(d.soulFragmentIds()).containsExactly("frag-1", "frag-2");
    }

    @Test void withFragment_ignoresBlank() {
        var a = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, null);
        assertThat(a.withFragment(null)).isSameAs(a);
        assertThat(a.withFragment("").soulFragmentIds()).isEmpty();
    }

    @Test void modeLock_requiresMode() {
        assertThatThrownBy(() -> new CodingFamiliarIdentity.ModeLock(
            null, null, null, "portal", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mode");
    }

    @Test void modeLock_defaultsDeclaredAtAndDeclaredBy() {
        var lock = new CodingFamiliarIdentity.ModeLock(
            "Repair", null, null, "portal-a", null);
        assertThat(lock.mode()).isEqualTo("Repair");
        assertThat(lock.declaredAt()).isNotNull();
        assertThat(lock.lastActivityAt()).isEqualTo(lock.declaredAt());
        assertThat(lock.declaredBy()).isEqualTo("BONDHOLDER_DECLARED");
    }

    @Test void withModeLock_replacesLock() {
        var a = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, null);
        assertThat(a.modeLock()).isNull();
        var lock = new CodingFamiliarIdentity.ModeLock(
            "Repair", null, "BONDHOLDER_DECLARED", "portal-x", null);
        var b = a.withModeLock(lock);
        assertThat(b.modeLock()).isEqualTo(lock);
        assertThat(a.modeLock()).isNull(); // original unchanged
    }

    @Test void canonicalizesNullCollections() {
        var id = new CodingFamiliarIdentity(
            CodingFamiliarIdentity.didFor(BONDHOLDER),
            "Coder", null, BONDHOLDER, PARENT,
            null, false,
            null, null, null, null, null, null, null, null);
        // null collections normalize to defensively-copied empty/default lists
        assertThat(id.sharedSubstrateWith()).containsExactly(PARENT);
        assertThat(id.preferredLanguageStacks()).isEmpty();
        assertThat(id.preferredTaskShapes()).isEmpty();
        assertThat(id.codingDNA()).isEmpty();
        assertThat(id.soulFragmentIds()).isEmpty();
        assertThat(id.kindSubtype()).isEqualTo("coding-familiar");
        assertThat(id.autonomyTier()).isEqualTo("ASSISTED");
    }

    @Test void codingDNA_isUnmodifiable() {
        var id = new CodingFamiliarIdentity(
            CodingFamiliarIdentity.didFor(BONDHOLDER),
            "Coder", null, BONDHOLDER, PARENT,
            null, false,
            null, List.of("java"), List.of("maintain"),
            Map.of("project-a", "convention-x"), null, null, null, null);
        assertThatThrownBy(() -> id.codingDNA().put("k", "v"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
