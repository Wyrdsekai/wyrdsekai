package org.wyrdsekai.between.zonegrant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * #1184 — wire protocol for the multi-node zone-secret GRANT over the NATS relay.
 *
 * <p>A zone's 32-byte master secret is the root of its argot codebook (see
 * {@code core.crypto.ZoneSecretService}); every node that installs the SAME master derives the
 * identical secret argot key. A single-node zone originates its own master. When a SECOND node joins
 * the same zone, it must receive the EXISTING master from the holder rather than originate a
 * divergent one (which would split the zone's language) — see {@code ZoneSecrets.bootstrapLocalZone}
 * which deliberately leaves a multi-node joiner master-less until granted.
 *
 * <p>This protocol moves the master from the holder (granter) to the joiner (requester) WITHOUT the
 * plaintext on the wire, riding the already-authed household relay (same pattern as the cross-zone
 * recipe borrow, {@link org.wyrdsekai.between.recipe.NatsRecipeProtocol}):
 * <ol>
 *   <li>Joiner subscribes to {@code federation.zonegrant.result.{requestId}} BEFORE publishing.</li>
 *   <li>Joiner publishes a {@link Request} carrying its X25519 grant <b>public</b> key to
 *       {@code federation.zonegrant.{zoneId}.request} — every same-zone holder hears it.</li>
 *   <li>The holder's {@code NatsZoneGrantServer} trust-gates the requester node, ECIES-wraps the
 *       master to the requester's public key via {@code ZoneSecretService.grantTo} (ephemeral-static
 *       X25519 — forward-secure, master never plaintext), and publishes the opaque blob.</li>
 *   <li>Joiner {@code ZoneSecrets.installGrantedMaster}s it (unwrap with its X25519 private key →
 *       persist wrapped under its node KEK → install the secret argot provider). Both nodes now
 *       derive the identical argot key.</li>
 * </ol>
 *
 * <p>The request subject is scoped by ZONE (not node) so the joiner needn't know which peer holds
 * the master; the holder self-selects (only a node that actually holds it responds). An eavesdropper
 * with the blob + both public keys cannot recover the master (X25519 ECDH); the relay auth already
 * scopes traffic to the household.
 */
public final class NatsZoneGrantProtocol {

    public record Request(
        @JsonProperty("requestId") String requestId,
        @JsonProperty("zoneId") String zoneId,
        @JsonProperty("requesterNodeId") String requesterNodeId,
        @JsonProperty("requesterX25519Pub") String requesterX25519PubBase64  // X.509 SPKI, base64
    ) {}

    public record Response(
        @JsonProperty("requestId") String requestId,
        @JsonProperty("granterNodeId") String granterNodeId,
        @JsonProperty("zoneId") String zoneId,
        @JsonProperty("grantBlob") String grantBlobBase64,  // ECIES grant envelope, base64; null on error
        @JsonProperty("error") String error                 // non-null on trust/holder/transport failure
    ) {
        public boolean ok() { return error == null && grantBlobBase64 != null; }
    }

    /** Subject every holder of {@code zoneId} subscribes to for incoming grant requests. */
    public static String requestSubject(String zoneId) {
        return "federation.zonegrant." + zoneId + ".request";
    }

    /** Subject a single request's grant flows back on. Joiner subscribes; the holder publishes once. */
    public static String resultSubject(String requestId) {
        return "federation.zonegrant.result." + requestId;
    }

    private NatsZoneGrantProtocol() {}
}
