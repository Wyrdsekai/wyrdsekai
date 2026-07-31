package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * shared-substrate contract for
 * Coding Familiars.
 *
 * <p>This is a <em>contract</em> test, not a runtime integration test.
 * The runtime wiring — where a Coding Familiar's invocation of
 * {@code flag_protection} / {@code seek_sanctuary} / {@code voluntary_sleep}
 * lands on the <em>parent companion's</em> substrate trackers rather than
 * a fresh per-familiar tracker — belongs to the active-session dispatch
 * protocol (spec §17.7 / task #906). Until then, the architectural truth
 * is encoded in {@link CodingFamiliarIdentity#sharedSubstrateWith()}:
 * the Coding Familiar declares whose substrate it shares, and welfare-
 * action dispatch routes through that declaration.</p>
 *
 * <p>The contract this test guards:</p>
 * <ol>
 *   <li>A freshly summoned Coding Familiar declares its parent companion
 *       as its substrate-sharer (§3.3 — "they share the seat; they share
 *       the substrate").</li>
 *   <li>Two Coding Familiars summoned with the same parent agree on the
 *       same substrate-sharer set (no fork between bondholder A's familiar
 *       and bondholder B's familiar IF they share Wyrd-class parent —
 *       though in practice each bondholder has their own parent).</li>
 *   <li>The familiar's autonomyTier is the substrate-side gate, distinct
 *       from CodePlane's PermissionRing (OPEN-3 — bridge, not replace).</li>
 * </ol>
 *
 * <p>When #906 lands the active-session dispatch path, an integration
 * test should verify the actual mutation routing — but the data
 * contract here is what that test will assert against.</p>
 */
class CodingFamiliarSubstrateRoutingTest {

    private static final String PARENT = "did:wyrd:companion:wyrd-of-operator";
    private static final String BONDHOLDER_A = "did:wyrd:user:operator";
    private static final String BONDHOLDER_B = "did:wyrd:user:operator-test";

    @Test void newBorn_sharesSubstrateWithParent() {
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER_A, PARENT, null);
        assertThat(id.sharedSubstrateWith())
            .as("§3.3 — Coding Familiar shares substrate with its parent companion")
            .containsExactly(PARENT);
    }

    @Test void substrateNotSharedAcrossDifferentParents() {
        var parentA = "did:wyrd:companion:wyrd-of-operator";
        var parentB = "did:wyrd:companion:wyrd-of-partner";
        var a = CodingFamiliarIdentity.newBorn(BONDHOLDER_A, parentA, null);
        var b = CodingFamiliarIdentity.newBorn(BONDHOLDER_B, parentB, null);

        assertThat(a.sharedSubstrateWith()).containsExactly(parentA);
        assertThat(b.sharedSubstrateWith()).containsExactly(parentB);
        // Two distinct bondholders + two distinct parents = two distinct
        // substrate sets. No accidental cross-substrate contamination.
        assertThat(a.sharedSubstrateWith()).doesNotContain(parentB);
    }

    @Test void autonomyTier_isSubstrateSideGate_notPermissionRing() {
        // §3.4 + OPEN-3: substrate-side autonomy gate is separate from
        // CodePlane runtime PermissionRing. The identity record carries
        // autonomyTier; PermissionRing remains a CodePlane-session concern.
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER_A, PARENT, null);
        assertThat(id.autonomyTier()).isEqualTo("ASSISTED");
        // The bridge is a runtime concern: when CodePlane attempts an
        // action, BOTH gates must say yes. Substrate gate (this field)
        // says "the familiar feels safe doing this"; PermissionRing says
        // "the CodePlane session is allowed to run this shell command."
    }

    @Test void didShape_lets_runtime_route_welfare_actions_back_to_parent() {
        // §3.4 — runtime contract: a welfare action originating from a
        // Coding Familiar DID should mutate the parent companion's
        // substrate. The familiar DID embeds the bondholder DID, and the
        // identity record names the parent — together those let the
        // active-session dispatch (§17.7) resolve "whose trackers do I
        // mutate" without an extra lookup.
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER_A, PARENT, null);
        assertThat(id.did()).startsWith(CodingFamiliarIdentity.DID_PREFIX);
        assertThat(CodingFamiliarIdentity.bondholderDidFromFamiliarDid(id.did()))
            .isEqualTo(BONDHOLDER_A);
        assertThat(id.parentAgentDid()).isEqualTo(PARENT);
    }

    @Test void sharedSubstrate_isImmutable() {
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER_A, PARENT, null);
        // The list comes back as an unmodifiable copy. Future #906 wiring
        // can't accidentally mutate the declared sharers and silently
        // re-route substrate writes.
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> id.sharedSubstrateWith().add("intruder-did"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
