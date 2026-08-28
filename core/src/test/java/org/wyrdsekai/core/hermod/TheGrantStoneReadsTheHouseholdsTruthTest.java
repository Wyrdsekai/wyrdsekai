package org.wyrdsekai.core.hermod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.hermod.GrantAuthority;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The grant stone must tell the household the truth: verified grants
 * read active, time judges expiry, a foreign signature is named as
 * such, and revocation is a tombstone (history kept) — never a delete,
 * never a resurrection.
 */
class TheGrantStoneReadsTheHouseholdsTruthTest {

    static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path dir;
    PrivateKey key;
    HermodGrantStore store;

    @BeforeEach
    void setUp() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        key = kp.getPrivate();
        store = new HermodGrantStore(dir, kp.getPublic().getEncoded(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void write(String name, String grantId, Instant expires, PrivateKey signer) throws Exception {
        var grant = GrantAuthority.mint(grantId, "hh1", "photos", "llm.phone",
            NOW.minusSeconds(3600), expires, "v1", signer);
        Files.write(dir.resolve(name), JSON.writeValueAsBytes(grant));
    }

    @Test
    void verifiedCurrentGrantsReadActive() throws Exception {
        write("photos-llm.phone.json", "g-1", NOW.plusSeconds(3600), key);
        var views = store.list();
        assertEquals(1, views.size());
        assertEquals("active", views.get(0).status());
        assertEquals("g-1", views.get(0).grant().grantId());
    }

    @Test
    void timeJudgesExpiryAndForeignSignaturesAreNamed() throws Exception {
        write("photos-llm.phone.json", "g-old", NOW.minusSeconds(60), key);
        var foreign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        write("notes-llm.phone.json", "g-forged", NOW.plusSeconds(3600), foreign.getPrivate());
        assertTrue(store.list().stream().anyMatch(v ->
            "g-old".equals(v.grant().grantId()) && "expired".equals(v.status())));
        assertTrue(store.list().stream().anyMatch(v ->
            "g-forged".equals(v.grant().grantId()) && "invalid-signature".equals(v.status())));
    }

    @Test
    void revocationTombstonesButNeverDeletes() throws Exception {
        write("photos-llm.phone.json", "g-1", NOW.plusSeconds(3600), key);
        var revoked = store.revoke("g-1");
        assertNotNull(revoked);
        assertTrue(store.list().isEmpty(), "tombstoned grants leave the listing");
        assertTrue(Files.exists(dir.resolve("photos-llm.phone.json.revoked")),
            "the household keeps its history");
        assertNull(store.revoke("g-1"), "a tombstone cannot be revoked twice");
    }

    @Test
    void revokeByDomainClassStemWorksToo() throws Exception {
        write("photos-llm.phone.json", "g-1", NOW.plusSeconds(3600), key);
        assertNotNull(store.revoke("photos-llm.phone"));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void nothingMatchedMeansNullNeverAnAccident() throws Exception {
        write("photos-llm.phone.json", "g-1", NOW.plusSeconds(3600), key);
        assertNull(store.revoke("g-does-not-exist"));
        assertEquals(1, store.list().size(), "a miss must not touch the files");
    }
}
