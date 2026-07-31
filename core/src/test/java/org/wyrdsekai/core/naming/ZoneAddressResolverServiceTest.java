package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ZoneAddressResolverServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private byte[] spki;

    @BeforeEach
    void setUp() throws Exception {
        ZoneAddressResolverService.resetForTests();
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var kp = kpg.generateKeyPair();
        spki = kp.getPublic().getEncoded();
    }

    @AfterEach
    void tearDown() {
        ZoneAddressResolverService.resetForTests();
    }

    @Test void get_returnsNullBeforeInit() {
        assertNull(ZoneAddressResolverService.get());
    }

    @Test void init_wiresResolverWithEmptyFiles(@TempDir Path dir) {
        ZoneAddressResolverService.init(spki, dir, null);

        var svc = ZoneAddressResolverService.get();
        assertNotNull(svc);
        assertEquals(0, svc.contacts().size());
        assertEquals(0, svc.myZones().size());
        assertNotNull(svc.resolver());
        assertTrue(svc.household().did().startsWith("did:wyrd:"));
    }

    @Test void init_autoRegistersLegacyZoneId(@TempDir Path dir) {
        ZoneAddressResolverService.init(spki, dir, "beta");

        var svc = ZoneAddressResolverService.get();
        assertTrue(svc.myZones().contains("beta"),
            "legacy zoneId must be auto-registered for Phase-1 compat");
    }

    @Test void init_skipsReservedLegacyZoneId(@TempDir Path dir) {
        // `home` is a reserved keyword per spec §2.4 — must NOT auto-register,
        // so the deployment is forced to migrate (§7). Expected behavior: the
        // service still initialises, the reserved ID is just not in the registry.
        ZoneAddressResolverService.init(spki, dir, "home");

        var svc = ZoneAddressResolverService.get();
        assertNotNull(svc);
        assertFalse(svc.myZones().contains("home"),
            "reserved legacy zoneId must not be auto-registered");
    }

    @Test void init_skipsMalformedLegacyZoneId(@TempDir Path dir) {
        // A malformed zoneId (e.g. uppercase) falls through — log WARN but
        // still init. Service usable for contact resolution; self-zone lookups
        // will fail until operator migrates.
        ZoneAddressResolverService.init(spki, dir, "BETA");

        var svc = ZoneAddressResolverService.get();
        assertNotNull(svc);
        assertFalse(svc.myZones().contains("BETA"));
        assertFalse(svc.myZones().contains("beta"),
            "no silent lowercasing — operator must migrate explicitly");
    }

    @Test void init_loadsExistingContactsAndZones(@TempDir Path dir) throws Exception {
        // Pre-seed files on disk and confirm the service reads them.
        var contactsFile = dir.resolve("contacts");
        var didAlice = "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
        Files.writeString(contactsFile, "alice\t" + didAlice + "\tkitchen\n");

        var zonesFile = dir.resolve("my-zones");
        Files.writeString(zonesFile, "garage\nstudy\n");

        ZoneAddressResolverService.init(spki, dir, null);

        var svc = ZoneAddressResolverService.get();
        assertEquals(1, svc.contacts().size());
        assertTrue(svc.contacts().get("alice").isPresent());
        assertEquals(2, svc.myZones().size());
        assertTrue(svc.myZones().contains("garage"));
        assertTrue(svc.myZones().contains("study"));
    }

    @Test void init_idempotent_returnsSameInstance(@TempDir Path dir) {
        ZoneAddressResolverService.init(spki, dir, null);
        var first = ZoneAddressResolverService.get();

        // Second init is a no-op.
        ZoneAddressResolverService.init(spki, dir, "different-zone");
        var second = ZoneAddressResolverService.get();

        assertSame(first, second);
        assertFalse(second.myZones().contains("different-zone"),
            "second init should not mutate state");
    }

    @Test void resetForTests_clearsSingleton(@TempDir Path dir) {
        ZoneAddressResolverService.init(spki, dir, null);
        assertNotNull(ZoneAddressResolverService.get());

        ZoneAddressResolverService.resetForTests();
        assertNull(ZoneAddressResolverService.get());
    }

    @Test void resolver_worksAfterInit(@TempDir Path dir) {
        ZoneAddressResolverService.init(spki, dir, "garage");
        var svc = ZoneAddressResolverService.get();

        var result = svc.resolver().resolve("garage");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        var addr = ((ZoneAddressResolver.Result.Ok) result).address();
        assertEquals(svc.household().fingerprint(), addr.fingerprint());
        assertEquals("garage", addr.label());
    }

    @Test void resolver_rejectsReservedAfterInit(@TempDir Path dir) {
        ZoneAddressResolverService.init(spki, dir, "garage");
        var svc = ZoneAddressResolverService.get();

        var result = svc.resolver().resolve("home");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("reserved_keyword", ((ZoneAddressResolver.Result.Err) result).code());
    }

    // ── Integration with ZoneResolveJson ──────────────────────────────

    @Test void jsonIntegration_okShape(@TempDir Path dir) throws Exception {
        ZoneAddressResolverService.init(spki, dir, "garage");

        var json = ZoneResolveJson.fromService(ZoneAddressResolverService.get(), "garage");
        var node = MAPPER.readTree(json);

        assertTrue(node.get("ok").asBoolean());
        assertEquals("garage", node.get("label").asText());
        assertTrue(node.get("canonical").asText().startsWith("did:wyrd:"));
    }

    @Test void jsonIntegration_reservedKeywordHasProperMessage(@TempDir Path dir) throws Exception {
        ZoneAddressResolverService.init(spki, dir, null);

        var json = ZoneResolveJson.fromService(ZoneAddressResolverService.get(), "home");
        var node = MAPPER.readTree(json);

        assertFalse(node.get("ok").asBoolean());
        assertEquals("reserved_keyword", node.get("code").asText());
        // Message must explain WHY, not just "unknown". docks.js shows this
        // string to the user verbatim.
        assertTrue(node.get("message").asText().contains("reserved"),
            "user-facing message must say 'reserved': " + node.get("message").asText());
    }

    @Test void jsonIntegration_unknownLabelWhenNoZones(@TempDir Path dir) throws Exception {
        ZoneAddressResolverService.init(spki, dir, null);  // no legacy zone

        var json = ZoneResolveJson.fromService(ZoneAddressResolverService.get(), "nonesuch");
        var node = MAPPER.readTree(json);

        assertFalse(node.get("ok").asBoolean());
        assertEquals("unknown_label", node.get("code").asText());
    }
}
