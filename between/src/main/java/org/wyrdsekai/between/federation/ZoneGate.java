package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.KeyRotation;
import org.wyrdsekai.core.naming.HouseholdIdentity;
import org.wyrdsekai.core.naming.ZoneAddressResolverService;

import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides which zone a federation envelope really comes from (audit 2026-09-28). Before this, the check
 * looked the sender up by node id in a map keyed by zone id, so it never ran; an unknown sender skipped
 * it; a mismatch was only logged; and a {@code manifest} overwrote the stored key.
 *
 * <p>Now every federation message must verify against the key pinned for the zone it claims to come
 * from. A zone's key is pinned on first verified contact: its {@code propose} (self-signed, when we hold
 * no active or pending agreement with it) or its {@code accept} of our proposal. After that the pinned
 * key wins: a proposal or manifest presenting another key is dropped, and only a {@code key_rotation}
 * signed by the old key changes it. When the naming contacts already list the zone, a first-contact key
 * must match that contact's DID.</p>
 */
final class ZoneGate {

    private static final Logger log = LoggerFactory.getLogger(ZoneGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** A message that verified: the zone it is from, and for first contact the key to pin. */
    record Verified(String zoneId, String keyToPin, String rotatedKey) {}

    private final FederationService service;
    private final String localZoneId;
    private final Function<String, Optional<String>> contactDid;

    ZoneGate(FederationService service, String localZoneId) {
        this(service, localZoneId, ZoneGate::contactDidFromNaming);
    }

    ZoneGate(FederationService service, String localZoneId, Function<String, Optional<String>> contactDid) {
        this.service = service;
        this.localZoneId = localZoneId;
        this.contactDid = contactDid;
    }

    /** The verified sender zone, or empty (the reason is logged at WARN). */
    Optional<Verified> verify(BetweenEnvelope env, String type) {
        var payload = env.payload();
        try {
            return switch (type) {
                case "propose" -> firstContact(env, MAPPER.treeToValue(payload.get("proposer"), ZoneManifest.class),
                    true);
                case "accept" -> {
                    var acceptor = MAPPER.treeToValue(payload.get("acceptor"), ZoneManifest.class);
                    if (!acceptor.zoneId().equals(payload.path("zoneId").asText(acceptor.zoneId()))) {
                        yield refuse(type, acceptor.zoneId(), "zoneId and acceptor manifest disagree");
                    }
                    if (service.getAgreement(localZoneId, acceptor.zoneId()).isEmpty()) {
                        yield refuse(type, acceptor.zoneId(), "we never proposed to this zone");
                    }
                    yield firstContact(env, acceptor, false);
                }
                case "manifest" -> {
                    var manifest = MAPPER.treeToValue(payload.get("manifest"), ZoneManifest.class);
                    var pinned = service.pinnedZoneKey(localZoneId, manifest.zoneId());
                    if (pinned.isPresent() && !pinned.get().equals(manifest.publicKey())) {
                        yield refuse(type, manifest.zoneId(),
                            "manifest carries a different key than the pinned one (a key change needs key_rotation)");
                    }
                    yield pinnedSender(env, type, manifest.zoneId());
                }
                case KeyRotation.TYPE -> {
                    var zone = claimedZone(payload, type);
                    var sender = pinnedSender(env, type, zone);
                    if (sender.isEmpty()) yield sender;
                    var oldKey = Base64.getDecoder().decode(service.pinnedZoneKey(localZoneId, zone).orElseThrow());
                    var newKey = KeyRotation.provenNewKey(zone, oldKey, payload);
                    if (newKey == null) yield refuse(type, zone, "key_rotation without a valid proof from the new key");
                    yield Optional.of(new Verified(zone, null, Base64.getEncoder().encodeToString(newKey)));
                }
                default -> pinnedSender(env, type, claimedZone(payload, type));
            };
        } catch (Exception e) {
            return refuse(type, env.src(), "unreadable payload (" + e.getMessage() + ")");
        }
    }

    /**
     * A propose (or an accept of our proposal) carries the sender's manifest and key. With a key already
     * pinned for that zone, the envelope must verify against the pinned key and the manifest must carry
     * it. With none, the envelope must verify against the key the manifest carries, which is then pinned.
     */
    private Optional<Verified> firstContact(BetweenEnvelope env, ZoneManifest manifest, boolean proposal) {
        var zone = manifest.zoneId();
        var type = proposal ? "propose" : "accept";
        if (zone.equals(localZoneId)) return refuse(type, zone, "claims to be this zone");
        var offered = manifest.publicKey();
        var pinned = service.pinnedZoneKey(localZoneId, zone);
        var agreement = service.getAgreement(localZoneId, zone);
        boolean agreementLive = agreement.isPresent()
            && (agreement.get().isActive() || agreement.get().isPending());
        if (pinned.isPresent() && !pinned.get().equals(offered)) {
            // A zone that reinstalled may propose afresh with a new key once no live agreement holds the
            // old one: the proposal lands as pending and waits for the steward to accept it.
            if (!proposal || agreementLive) {
                return refuse(type, zone, "presents a different key than the one pinned for it");
            }
            log.warn("Federation: zone '{}' proposes with a new key; no live agreement holds the old one, "
                + "so it waits as a pending proposal for the steward", zone);
        }
        var expectedDid = contactDid.apply(zone);
        if (expectedDid.isPresent()) {
            String offeredDid;
            try {
                offeredDid = HouseholdIdentity.fromSpkiBytes(Base64.getDecoder().decode(offered)).did();
            } catch (RuntimeException e) {
                return refuse(type, zone, "carries an unreadable key");
            }
            if (!expectedDid.get().equals(offeredDid)) {
                return refuse(type, zone, "key does not match the contact on file (" + expectedDid.get() + ")");
            }
        }
        if (!signedBy(env, offered)) return refuse(type, zone, "signature does not match the key it carries");
        return Optional.of(new Verified(zone, offered, null));
    }

    private Optional<Verified> pinnedSender(BetweenEnvelope env, String type, String claimed) {
        if (claimed != null && !claimed.isBlank()) {
            var key = service.pinnedZoneKey(localZoneId, claimed);
            if (key.isEmpty()) return refuse(type, claimed, "no key is pinned for this zone");
            if (!signedBy(env, key.get())) return refuse(type, claimed, "signature does not match the zone's pinned key");
            return Optional.of(new Verified(claimed, null, null));
        }
        // Older peers send some replies without naming their zone: find the partner whose key signed it.
        for (var a : service.listAgreements(localZoneId)) {
            var key = service.pinnedZoneKey(localZoneId, a.remoteZoneId());
            if (key.isPresent() && signedBy(env, key.get())) return Optional.of(new Verified(a.remoteZoneId(), null, null));
        }
        return refuse(type, env.src(), "not signed by any zone we hold a key for");
    }

    /** The zone a message says it is from: the new {@code senderZone} field, else the legacy field for its type. */
    static String claimedZone(JsonNode payload, String type) {
        var sender = payload.path("senderZone").asText("");
        if (!sender.isBlank()) return sender;
        return switch (type) {
            case "agreement_query" -> payload.path("askerZoneId").asText("");
            case "transit_request" -> payload.path("sourceZoneId").asText("");
            case "companion_relocate" -> payload.path("token").path("sourceZoneId").asText("");
            case "companion_relocate_ack" -> payload.path("fromZoneId").asText("");
            case "agreement_query_reply", "transit_response" -> "";
            default -> payload.path("zoneId").asText("");
        };
    }

    private static boolean signedBy(BetweenEnvelope env, String keyB64) {
        try {
            return env.verify(Base64.getDecoder().decode(keyB64));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Optional<Verified> refuse(String type, String who, String why) {
        log.warn("Federation: DROPPED {} from '{}': {}", type, who, why);
        return Optional.empty();
    }

    private static Optional<String> contactDidFromNaming(String zoneId) {
        try {
            var naming = ZoneAddressResolverService.get();
            if (naming == null) return Optional.empty();
            return naming.contacts().get(zoneId).map(c -> c.did());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
