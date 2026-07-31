package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link NostrAdapter} — exercises method dispatch, arg parsing,
 * rate limiting, and the credential-missing path. Does NOT spin up real
 * relays; for that, see {@code NostrRelayPoolIntegrationTest} (live test
 * gated behind an env flag).
 */
class NostrAdapterTest {

    private static byte[] fixedSeed() {
        var b = new byte[32];
        Arrays.fill(b, (byte) 0x42);
        return b;
    }

    /** Pool stub that never connects; publish() always returns 0 accepted. */
    private static NostrRelayPool emptyPool() {
        return new NostrRelayPool(List.of());
    }

    @Test void unknown_method_returns_unknown_method_error() {
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 60);
        var resp = adapter.invoke(AdapterRequest.of("nostr", "tweet",
            Map.of("content", "hi", "did", "did:key:zABC")));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("unknown_method");
    }

    @Test void publish_without_content_returns_bad_request() {
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 60);
        var resp = adapter.invoke(AdapterRequest.of("nostr", "publish",
            Map.of("did", "did:key:zABC")));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("bad_request");
        assertThat(resp.error().message()).contains("content");
    }

    @Test void publish_without_did_returns_bad_request() {
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 60);
        var resp = adapter.invoke(AdapterRequest.of("nostr", "publish",
            Map.of("content", "hi")));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("bad_request");
        assertThat(resp.error().message()).contains("did");
    }

    @Test void publish_with_no_seed_returns_credential_missing() {
        var adapter = new NostrAdapter(emptyPool(), did -> null, 60);
        var resp = adapter.invoke(AdapterRequest.of("nostr", "publish",
            Map.of("content", "hello", "did", "did:key:zNoSuchDid")));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credential_missing");
    }

    @Test void publish_with_no_relays_returns_publish_failed() {
        // Seed is good; key derives fine; but pool has no relays so all publish
        // attempts trivially fail.
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 60);
        var resp = adapter.invoke(AdapterRequest.of("nostr", "publish",
            Map.of("content", "hello world", "did", "did:key:zABC")));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("publish_failed");
        // Failure is retryable — caller can come back later
        assertThat(resp.error().retryable()).isTrue();
    }

    @Test void rate_limit_kicks_in_after_max() {
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 2);
        var did = "did:key:zRateTest";
        var args = Map.<String, Object>of("content", "spam", "did", did);

        var r1 = adapter.invoke(AdapterRequest.of("nostr", "publish", args));
        var r2 = adapter.invoke(AdapterRequest.of("nostr", "publish", args));
        var r3 = adapter.invoke(AdapterRequest.of("nostr", "publish", args));

        // Both first two go through to publish (and fail on empty pool with
        // publish_failed). Third hits rate limit.
        assertThat(r1.error().code()).isEqualTo("publish_failed");
        assertThat(r2.error().code()).isEqualTo("publish_failed");
        assertThat(r3.error().code()).isEqualTo("rate_limited");
        assertThat(r3.error().retryable()).isTrue();
    }

    @Test void capabilities_advertise_publish() {
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 60);
        assertThat(adapter.namespace()).isEqualTo("nostr");
        assertThat(adapter.capabilities()).contains("publish");
        assertThat(adapter.credentialSlot()).isEqualTo("nostr.keypairs");
    }

    @Test void tags_arg_parses_list_of_lists() {
        // Direct unit-test of tags parsing via the publish path. The adapter
        // would build the event with these tags before failing on the empty
        // pool — we verify the path runs without throwing.
        var adapter = new NostrAdapter(emptyPool(), did -> fixedSeed(), 60);
        var resp = adapter.invoke(AdapterRequest.of("nostr", "publish", Map.of(
            "content", "tagged",
            "did", "did:key:zTagTest",
            "tags", List.of(List.of("t", "wyrdsekai"), List.of("p", "abc")))));
        // The call fails on empty pool, but reaching publish_failed means
        // tag parsing didn't throw.
        assertThat(resp.error().code()).isEqualTo("publish_failed");
    }
}
