package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The bug that survived to production, 2026-08-07.
 *
 * <p>The item-script Study leg found the passages and then threw them all away.
 * Live log: 10 Glass Tide passages retrieved, <b>"0 study"</b> returned.</p>
 *
 * <p>Cause: {@link StudyService#hasAccess} denies when its {@code HomeClient} is
 * null — correct, a grant check that cannot check grants must not answer "yes" —
 * and {@code CompanionActor} constructs the item provider with
 * {@code null /* homeClient *}{@code /}. So the consent oracle was absent, every
 * collection was denied, and the caller could not tell "you have no grant" apart
 * from "grants could not be checked".</p>
 *
 * <p>This test pins the fail-closed behaviour so nobody 'fixes' it by defaulting
 * to allow. The wiring fix lives in {@code ItemWorldApiProviderImpl}, which now
 * falls back to {@code HomeClients.get()} and WARNs when there is no oracle at
 * all rather than reporting an empty shelf.</p>
 */
class StudyAccessFailsClosedOnMissingOracleTest {

    private static final String USER = "did:key:zPerson";
    private static final String COMPANION = "did:key:zCompanion";

    /** THE invariant: no oracle means no access. Never "allow". */
    @Test
    void denies_every_collection_when_there_is_no_consent_oracle() {
        var svc = new StudyService(null, null);

        assertThat(svc.hasAccess(USER, COMPANION, "books"))
            .as("a grant check with no way to check grants must deny")
            .isFalse();
        assertThat(svc.hasAccess(USER, COMPANION, "research")).isFalse();
        assertThat(svc.hasAccess(USER, COMPANION, "anything-at-all")).isFalse();
    }

    /**
     * The documented exception, kept explicit so the fail-closed test above is
     * not read as "everything is always denied".
     */
    @Test
    void the_shared_journal_stays_reachable() {
        var svc = new StudyService(null, null);

        assertThat(svc.hasAccess(USER, COMPANION, "journal"))
            .as("the shared journal is the companion's by design")
            .isTrue();
    }

    /**
     * Listing grants without an oracle must be empty, not an error — and must not
     * be mistaken for "this person has shared nothing".
     */
    @Test
    void listing_grants_without_an_oracle_is_empty() {
        assertThat(new StudyService(null, null).listGrants(USER)).isEmpty();
    }

    /**
     * The caller-side guard: the item provider must consult the static oracle
     * when it was handed none, because that is exactly how it is constructed in
     * production.
     */
    @Test
    void the_item_provider_falls_back_to_the_static_oracle() throws Exception {
        var src = Files.readString(sourceOf(
            "core/src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java"));

        assertThat(src)
            .as("must not pass the (null in prod) field straight into StudyService")
            .doesNotContain("new StudyService(luceneStore, homeClient)");
        assertThat(src)
            .as("must fall back to the installed oracle")
            .contains("HomeClients.get()");
    }

    private static Path sourceOf(String repoRelative) {
        var fromCore = Paths.get("..", repoRelative);
        return Files.exists(fromCore)
            ? fromCore : Paths.get(repoRelative);
    }
}
