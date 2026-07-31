package org.wyrdsekai.core.nostr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.identity.IdentityOutboxRecord;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2c — verify the outbox→nostr fan-out path.
 *
 * <p>We don't exercise real relays here; we register a stub adapter under
 * the "nostr" namespace that captures the invocation, then assert shape.
 */
class OutboxNostrMirrorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapturingAdapter adapter;

    @BeforeEach void setUp() {
        ExternalAdapterRegistry.get().clearForTests();
        adapter = new CapturingAdapter();
        ExternalAdapterRegistry.get().register(adapter);
    }

    @AfterEach void tearDown() {
        ExternalAdapterRegistry.get().clearForTests();
    }

    @Test void noop_when_record_has_no_nostr_channel() throws Exception {
        var record = sampleRecord(List.of());  // no channels
        var fired = OutboxNostrMirror.maybeMirror(record);
        assertThat(fired).isFalse();
        assertThat(adapter.lastRequest.get()).isNull();
    }

    @Test void noop_when_no_adapter_registered() throws Exception {
        ExternalAdapterRegistry.get().clearForTests();
        var record = sampleRecord(List.of(new IdentityOutboxRecord.ChannelRef("nostr", "npub1abc")));
        var fired = OutboxNostrMirror.maybeMirror(record);
        assertThat(fired).isFalse();
    }

    @Test void noop_on_null_record() {
        assertThat(OutboxNostrMirror.maybeMirror(null)).isFalse();
        assertThat(adapter.lastRequest.get()).isNull();
    }

    @Test void publishes_kind0_with_nostr_channel() throws Exception {
        var record = sampleRecord(List.of(
            new IdentityOutboxRecord.ChannelRef("nostr", "npub1abc"),
            new IdentityOutboxRecord.ChannelRef("matrix", "@alice:matrix.org")));

        var fired = OutboxNostrMirror.maybeMirror(record);
        assertThat(fired).isTrue();

        var req = adapter.lastRequest.get();
        assertThat(req).isNotNull();
        assertThat(req.namespace()).isEqualTo("nostr");
        assertThat(req.method()).isEqualTo("publish");

        var args = req.args();
        assertThat(args.get("did")).isEqualTo(record.did());
        assertThat(args.get("kind")).isEqualTo(0);

        // Content is JSON with name + did + primaryZone fields
        var contentJson = (String) args.get("content");
        assertThat(contentJson).isNotBlank();
        var content = JSON.readValue(contentJson, Map.class);
        assertThat(content.get("name")).isEqualTo(record.displayName());
        assertThat(content.get("did")).isEqualTo(record.did());
        assertThat(content.get("primaryZone")).isEqualTo("alpha");

        @SuppressWarnings("unchecked")
        var tags = (List<List<String>>) args.get("tags");
        // 2 write zones + 1 read zone + L tag = 4
        assertThat(tags).hasSize(4);
        assertThat(tags.get(0)).containsExactly("z", "alpha", "write");
        assertThat(tags.get(1)).containsExactly("z", "beta", "write");
        assertThat(tags.get(2)).containsExactly("z", "gamma", "read");
        assertThat(tags.get(3)).containsExactly("L", "did:key");
    }

    @Test void case_insensitive_nostr_channel_type() throws Exception {
        var record = sampleRecord(List.of(new IdentityOutboxRecord.ChannelRef("Nostr", "npub1xyz")));
        var fired = OutboxNostrMirror.maybeMirror(record);
        assertThat(fired).isTrue();
        assertThat(adapter.lastRequest.get()).isNotNull();
    }

    @Test void survives_adapter_returning_failure() throws Exception {
        adapter.responseSupplier = () ->
            AdapterResponse.fail("publish_failed", "relays unreachable", true);

        var record = sampleRecord(List.of(new IdentityOutboxRecord.ChannelRef("nostr", "npub1abc")));
        // Just must not throw — mirror is fire-and-forget
        var fired = OutboxNostrMirror.maybeMirror(record);
        assertThat(fired).isTrue();
        assertThat(adapter.lastRequest.get()).isNotNull();
    }

    @Test void survives_adapter_throwing() throws Exception {
        adapter.responseSupplier = () -> { throw new RuntimeException("boom"); };

        var record = sampleRecord(List.of(new IdentityOutboxRecord.ChannelRef("nostr", "npub1abc")));
        var fired = OutboxNostrMirror.maybeMirror(record);
        assertThat(fired).isTrue();   // attempt was made
    }

    @Test void falls_back_to_did_when_displayname_blank() throws Exception {
        var did = newDid();
        var rec = unsigned(did, "", "alpha",
            List.of("alpha"), List.of(),
            List.of(new IdentityOutboxRecord.ChannelRef("nostr", "npub1nodisplay")));

        OutboxNostrMirror.maybeMirror(rec);

        var contentJson = (String) adapter.lastRequest.get().args().get("content");
        var content = JSON.readValue(contentJson, Map.class);
        assertThat(content.get("name")).isEqualTo(did);
    }

    // ───────── helpers ─────────

    private static IdentityOutboxRecord sampleRecord(
        List<IdentityOutboxRecord.ChannelRef> channels) throws Exception {
        return unsigned(newDid(), "alice",
            "alpha",
            List.of("alpha", "beta"),
            List.of("gamma"),
            channels);
    }

    /** Build a record without a real signature — mirror doesn't care about sig. */
    private static IdentityOutboxRecord unsigned(
        String did, String displayName, String primaryZone,
        List<String> writeZones, List<String> readZones,
        List<IdentityOutboxRecord.ChannelRef> channels
    ) {
        return new IdentityOutboxRecord(
            did, displayName, primaryZone,
            writeZones == null ? List.of() : List.copyOf(writeZones),
            readZones == null ? List.of() : List.copyOf(readZones),
            channels == null ? List.of() : List.copyOf(channels),
            System.currentTimeMillis(),
            "FAKE_SIG_NOT_VERIFIED_BY_MIRROR");
    }

    private static String newDid() throws Exception {
        var kg = KeyPairGenerator.getInstance("Ed25519");
        var kp = kg.generateKeyPair();
        return DidKey.fromPublicKey(kp.getPublic());
    }

    /** Test-only adapter that records the last invocation. */
    static final class CapturingAdapter implements ExternalAdapter {
        final AtomicReference<AdapterRequest> lastRequest = new AtomicReference<>();
        volatile Supplier<AdapterResponse> responseSupplier =
            () -> AdapterResponse.ok(Map.of("eventId", "stub"));

        @Override public String namespace() { return "nostr"; }
        @Override public Set<String> capabilities() { return Set.of("publish"); }
        @Override public String credentialSlot() { return "nostr.keypairs"; }
        @Override public AdapterResponse invoke(AdapterRequest req) {
            lastRequest.set(req);
            return responseSupplier.get();
        }

        // unused private field for IDE-only suppression
        @SuppressWarnings("unused")
        private ArrayList<Object> _filler;
    }
}
