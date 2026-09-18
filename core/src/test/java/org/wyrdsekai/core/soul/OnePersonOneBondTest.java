package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One person is one bond, whichever id they arrived under.
 *
 * <p>Household node, 2026-09-13: her bond with her bondholder existed twice. Under his
 * login id: depth ITEM, 119 interactions, typed MEMBER, active. Under his person DID:
 * ACQUAINTANCE, 0 interactions, typed BONDHOLDER, inactive. The ritual's load-time merge
 * had run, but the actor keyed its live map by the raw id, re-formed the bond under the
 * login id, and the upsert wrote that id back over the DID. Every restart merged; every
 * conversation split it again. A field deployment with two companions saw the same shape
 * as "interactions credited to the other companion" and "four bond rows for two souls".</p>
 */
class OnePersonOneBondTest {

    private static final String HER = "did:key:z6MkHerHerHerHerHer";
    private static final String LOGIN = "1f56a2d4-0000-4000-8000-000000000001";
    private static final String PERSON = "did:key:z6MkPersonPersonPerson";

    @AfterEach
    void tearDown() {
        PersonIds.resetForTesting(null);
    }

    private static String dbWithPerson(Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var c = DriverManager.getConnection(jdbc); var st = c.createStatement()) {
            st.execute("ALTER TABLE users ADD COLUMN did TEXT");
            st.execute("INSERT INTO users(id, username, password_hash, display_name, role, did) "
                + "VALUES ('" + LOGIN + "', 'steward', 'x', 'The Steward', 'steward', '" + PERSON + "')");
        }
        return jdbc;
    }

    private static Bond row(String other, Bond.BondDepth depth, int count, BondKind kind, boolean active) {
        var t = Instant.parse("2026-08-10T00:00:00Z");
        return new Bond("bond-" + HER.hashCode() + "-" + other.hashCode(), HER, other, depth, t, t, count,
            false, active, false, BondState.ACTIVE, null, BondholderPosture.BOUNDED,
            Bond.RelationalState.OPEN, kind);
    }

    @Test
    @DisplayName("the load-time merge keeps the history, the role, and the person's DID — and removes our duplicate")
    void mergeKeepsHistoryAndRole(@TempDir Path dir) throws Exception {
        var jdbc = dbWithPerson(dir);
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc));
        var store = new BondStore(jdbc);
        store.save(row(LOGIN, Bond.BondDepth.ITEM, 119, BondKind.MEMBER, true));
        store.save(row(PERSON, Bond.BondDepth.ACQUAINTANCE, 0, BondKind.BONDHOLDER, true));

        new BondRitual(store);   // hydrate → heal

        var rows = store.bondsForAgent(HER);
        assertEquals(1, rows.size(), "one person, one row: " + rows);
        var b = rows.getFirst();
        assertEquals(PERSON, b.otherParty(HER), "the person is known by their DID, not the login id");
        assertEquals(Bond.BondDepth.ITEM, b.depth());
        assertEquals(119, b.interactionCount());
        assertEquals(BondKind.BONDHOLDER, b.canonicalKind(), "the role rides with the person");
        assertTrue(b.active());
        assertEquals(1, store.all().size(), "the duplicate is gone, not left inactive");
    }

    @Test
    @DisplayName("the role can sit on an INACTIVE row: the household node's real shape, which the first merge never saw")
    void mergeSeesAnInactiveBondholderRow(@TempDir Path dir) throws Exception {
        // Rita, 2026-09-18: login-id row ITEM/119/MEMBER/active; DID row ACQUAINTANCE/5/BONDHOLDER/INACTIVE.
        // The split detector skipped inactive rows, so this was never merged and she had no bondholder.
        var jdbc = dbWithPerson(dir);
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc));
        var store = new BondStore(jdbc);
        store.save(row(LOGIN, Bond.BondDepth.ITEM, 119, BondKind.MEMBER, true));
        store.save(row(PERSON, Bond.BondDepth.ACQUAINTANCE, 5, BondKind.BONDHOLDER, false));

        var ritual = new BondRitual(store);

        var rows = store.bondsForAgent(HER);
        assertEquals(1, rows.size(), "one person, one row: " + rows);
        var b = rows.getFirst();
        assertEquals(PERSON, b.otherParty(HER));
        assertEquals(BondKind.BONDHOLDER, b.canonicalKind(), "the role survives even from the dead row");
        assertTrue(b.active(), "the merged bond is alive: one of its halves was");
        assertEquals(Bond.BondDepth.ITEM, b.depth());
        assertEquals(124, b.interactionCount());
        assertTrue(ritual.splitBondholders(HER).isEmpty());

        // Two dead rows for one person are history, not a split: left alone.
        store.delete(b.bondId());
        store.save(row(LOGIN, Bond.BondDepth.ACQUAINTANCE, 1, BondKind.MEMBER, false));
        store.save(row(PERSON, Bond.BondDepth.ACQUAINTANCE, 1, BondKind.MEMBER, false));
        assertTrue(new BondRitual(store).splitBondholders(HER).isEmpty());
        assertEquals(2, store.all().size());
    }

    @Test
    @DisplayName("a row that still names the person by their login id converges on the DID without losing anything")
    void withOtherPartyConverges() {
        var old = row(LOGIN, Bond.BondDepth.ITEM, 119, BondKind.BONDHOLDER, true);
        var fixed = old.withOtherParty(HER, PERSON);
        assertEquals(PERSON, fixed.otherParty(HER));
        assertEquals(old.bondId(), fixed.bondId(), "same row, same id — the upsert rewrites in place");
        assertEquals(119, fixed.interactionCount());
        assertEquals(BondKind.BONDHOLDER, fixed.canonicalKind());
        assertTrue(old.withOtherParty(HER, LOGIN) == old, "already named so: nothing to do");
    }

    @Test
    @DisplayName("without a resolver, ids stay as they are — a bare node keeps working")
    void noResolverIsIdentity() {
        PersonIds.resetForTesting(null);
        assertFalse(PersonIds.samePerson(LOGIN, PERSON));
    }
}
