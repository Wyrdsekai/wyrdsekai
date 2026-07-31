package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ZoneAddressResolverTest {

    private HouseholdIdentity me;
    private ContactsBook contacts;
    private LocalZoneRegistry myZones;
    private ZoneAddressResolver resolver;

    // Sample DIDs for two contacts.
    private static final String DID_ALICE =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_BOB =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        me = HouseholdIdentity.fromPublicKey(kpg.generateKeyPair().getPublic());

        contacts = ContactsBook.empty(tmp.resolve("contacts"));
        contacts.add("alice", DID_ALICE, "kitchen");
        contacts.add("bob", DID_BOB, null);  // no default label

        myZones = LocalZoneRegistry.empty(tmp.resolve("my-zones"));
        myZones.add("garage");
        myZones.add("study");

        resolver = new ZoneAddressResolver(me, contacts, myZones);
    }

    // ── Own-zone resolution ─────────────────────────────────────────────

    @Test void resolve_ownZone_byLabel() {
        var result = resolver.resolve("garage");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        var addr = ((ZoneAddressResolver.Result.Ok) result).address();
        assertEquals(me.fingerprint(), addr.fingerprint());
        assertEquals("garage", addr.label());
    }

    @Test void resolve_ownZone_unknownLabel() {
        var result = resolver.resolve("mysteryzone");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("unknown_label", ((ZoneAddressResolver.Result.Err) result).code());
    }

    // ── Contact:label resolution ────────────────────────────────────────

    @Test void resolve_contactAndLabel() {
        var result = resolver.resolve("alice:parlor-public");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        var addr = ((ZoneAddressResolver.Result.Ok) result).address();
        // Fingerprint matches alice (not me)
        assertEquals(DID_ALICE, "did:wyrd:" + addr.fingerprint());
        assertEquals("parlor-public", addr.label());
    }

    @Test void resolve_contactAndLabel_unknownAlias() {
        var result = resolver.resolve("charlie:kitchen");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        var err = (ZoneAddressResolver.Result.Err) result;
        assertEquals("unknown_alias", err.code());
        assertTrue(err.message().contains("charlie"));
    }

    @Test void resolve_contactAndLabel_malformedLabel() {
        var result = resolver.resolve("alice:Kitchen");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("malformed_label", ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void resolve_contactAndLabel_reservedLabel() {
        var result = resolver.resolve("alice:home");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("reserved_keyword", ((ZoneAddressResolver.Result.Err) result).code());
    }

    // ── Bare contact alias (use default) ────────────────────────────────

    @Test void resolve_bareContact_withDefault() {
        var result = resolver.resolve("alice");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        var addr = ((ZoneAddressResolver.Result.Ok) result).address();
        assertEquals("kitchen", addr.label());
    }

    @Test void resolve_bareContact_noDefault() {
        var result = resolver.resolve("bob");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        var err = (ZoneAddressResolver.Result.Err) result;
        assertEquals("no_default_zone", err.code());
        assertTrue(err.message().contains("bob"));
        assertTrue(err.message().contains(":<label>"));
    }

    // ── Canonical DID form ──────────────────────────────────────────────

    @Test void resolve_canonicalDid() {
        var result = resolver.resolve(DID_ALICE + ":parlor-public");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        var addr = ((ZoneAddressResolver.Result.Ok) result).address();
        assertEquals(DID_ALICE, "did:wyrd:" + addr.fingerprint());
        assertEquals("parlor-public", addr.label());
    }

    @Test void resolve_canonicalDid_worksWithoutContacts() {
        // A DID that's not in contacts should still resolve — first-contact UX.
        var result = resolver.resolve(DID_BOB + ":garden");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
    }

    @Test void resolve_canonicalDid_malformed() {
        var result = resolver.resolve("did:wyrd:notavalidfp");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("malformed_did", ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void resolve_canonicalDid_missingLabel() {
        var result = resolver.resolve(DID_ALICE);
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("malformed_did", ((ZoneAddressResolver.Result.Err) result).code());
    }

    // ── Reserved keywords ───────────────────────────────────────────────

    @Test void resolve_reservedKeyword_home() {
        var result = resolver.resolve("home");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        var err = (ZoneAddressResolver.Result.Err) result;
        assertEquals("reserved_keyword", err.code());
        // Message must explain WHY, not just "unknown zone".
        assertTrue(err.message().contains("reserved"));
        assertTrue(err.message().contains("return to origin")
            || err.message().contains("directive"));
    }

    @Test void resolve_reservedKeyword_allFive() {
        for (var keyword : ZoneLabels.RESERVED) {
            var result = resolver.resolve(keyword);
            assertInstanceOf(ZoneAddressResolver.Result.Err.class, result,
                "'" + keyword + "' must be rejected as reserved");
            assertEquals("reserved_keyword",
                ((ZoneAddressResolver.Result.Err) result).code(),
                "'" + keyword + "' should return reserved_keyword code");
        }
    }

    @Test void resolve_reservedKeyword_caseInsensitive() {
        var result = resolver.resolve("Home");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("reserved_keyword", ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void resolve_reservedAliasInContactLookup() {
        // Defence in depth: even though `home` can't be added as an alias,
        // some hypothetical bypass shouldn't result in an accidental lookup.
        var result = resolver.resolve("home:kitchen");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("reserved_keyword", ((ZoneAddressResolver.Result.Err) result).code());
    }

    // ── Empty / whitespace ──────────────────────────────────────────────

    @Test void resolve_null() {
        var result = resolver.resolve(null);
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("empty_input", ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void resolve_empty() {
        var result = resolver.resolve("");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("empty_input", ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void resolve_whitespace() {
        var result = resolver.resolve("   ");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("empty_input", ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void resolve_stripsWhitespace() {
        var result = resolver.resolve("  garage  ");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
    }

    // ── Priority ordering (first match wins in grammar) ────────────────

    @Test void priority_ownZoneCanCoexistWithSameLabelContact() {
        // Add a contact with a default label that matches one of my own zones.
        // My own zone resolution only fires for bare-label input; contact
        // resolution fires for alias:label. The two namespaces never collide.
        contacts.add("zed", DID_BOB, "garage");

        var myGarage = resolver.resolve("garage");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, myGarage);
        assertEquals(me.fingerprint(),
            ((ZoneAddressResolver.Result.Ok) myGarage).address().fingerprint());

        var zedsGarage = resolver.resolve("zed:garage");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, zedsGarage);
        assertNotEquals(me.fingerprint(),
            ((ZoneAddressResolver.Result.Ok) zedsGarage).address().fingerprint());
    }

    @Test void constructor_rejectsNullDeps() {
        assertThrows(NullPointerException.class,
            () -> new ZoneAddressResolver(null, contacts, myZones));
        assertThrows(NullPointerException.class,
            () -> new ZoneAddressResolver(me, null, myZones));
        assertThrows(NullPointerException.class,
            () -> new ZoneAddressResolver(me, contacts, null));
    }

    // ── Directory fallback (rendezvous) ────────────────────────────────

    @Test void bareLabel_fallsBackToDirectory_whenLocallyUnknown() {
        // Scenario: `travel beta` — no contact named beta, no own-zone beta,
        // but the directory has a published manifest for label "beta".
        ZoneAddressResolver.DirectoryLookup dir = label -> {
            if ("beta".equalsIgnoreCase(label)) {
                var bobFp = DID_BOB.substring(HouseholdIdentity.DID_SCHEME.length());
                return new ZoneAddressResolver.Result.Ok(new ZoneAddress(bobFp, label));
            }
            return null;
        };
        var r = new ZoneAddressResolver(me, contacts, myZones, dir);
        var result = r.resolve("beta");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        var bobFp = DID_BOB.substring(HouseholdIdentity.DID_SCHEME.length());
        assertEquals(bobFp, ((ZoneAddressResolver.Result.Ok) result).address().fingerprint());
    }

    @Test void aliasColon_fallsBackToDirectory_whenContactUnknown() {
        // Scenario: `travel beta:beta` — no contact "beta", but directory has it.
        ZoneAddressResolver.DirectoryLookup dir = label -> {
            if ("beta".equalsIgnoreCase(label)) {
                var bobFp = DID_BOB.substring(HouseholdIdentity.DID_SCHEME.length());
                return new ZoneAddressResolver.Result.Ok(new ZoneAddress(bobFp, label));
            }
            return null;
        };
        var r = new ZoneAddressResolver(me, contacts, myZones, dir);
        var result = r.resolve("beta:beta");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
    }

    @Test void directory_ambiguousLabel_surfacesError() {
        // Two zones advertise the same label → refuse to guess.
        ZoneAddressResolver.DirectoryLookup dir = label ->
            new ZoneAddressResolver.Result.Err("ambiguous_label",
                "Label 'beta' is advertised by 2 zones in the directory (...)");
        var r = new ZoneAddressResolver(me, contacts, myZones, dir);
        var result = r.resolve("beta");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("ambiguous_label",
            ((ZoneAddressResolver.Result.Err) result).code());
    }

    @Test void directory_fallbackSkipped_whenContactExists() {
        // Contact alias has priority over directory — explicit user intent
        // beats discovery.
        var dirCalled = new boolean[]{false};
        ZoneAddressResolver.DirectoryLookup dir = label -> {
            dirCalled[0] = true;
            return new ZoneAddressResolver.Result.Ok(
                new ZoneAddress(DID_BOB.substring(HouseholdIdentity.DID_SCHEME.length()), label));
        };
        var r = new ZoneAddressResolver(me, contacts, myZones, dir);
        var result = r.resolve("alice:kitchen");
        assertInstanceOf(ZoneAddressResolver.Result.Ok.class, result);
        assertFalse(dirCalled[0], "directory must not be consulted when contact exists");
    }

    @Test void directory_exceptionDoesNotBreakResolve() {
        // A network hiccup in the directory must not propagate — we should
        // fall through to the normal "unknown_label" error.
        ZoneAddressResolver.DirectoryLookup dir = label -> {
            throw new RuntimeException("directory timeout");
        };
        var r = new ZoneAddressResolver(me, contacts, myZones, dir);
        var result = r.resolve("beta");
        assertInstanceOf(ZoneAddressResolver.Result.Err.class, result);
        assertEquals("unknown_label",
            ((ZoneAddressResolver.Result.Err) result).code());
    }
}
