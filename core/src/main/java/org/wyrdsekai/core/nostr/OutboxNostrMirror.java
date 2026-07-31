package org.wyrdsekai.core.nostr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.identity.IdentityOutboxRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * bridge IdentityOutboxRecord upserts to Nostr.
 *
 * <p>When an outbox record is accepted by {@code IdentityOutboxRoutes.handlePut}
 * and the record declares a {@code nostr}-typed {@link IdentityOutboxRecord.ChannelRef},
 * we fan out a Nostr kind:0 (set_metadata) event mirroring the identity. This
 * makes the identity discoverable on standard Nostr relays without the user
 * having to invoke {@code world.nostr.publish} from a companion script.
 *
 * <p>kind:0 content JSON keys:
 * <ul>
 *   <li>{@code name} — displayName (or DID if absent)</li>
 *   <li>{@code about} — short "primary zone: X" descriptor</li>
 *   <li>{@code did} — full {@code did:key:z…}</li>
 *   <li>{@code primaryZone} — canonical home zone id</li>
 * </ul>
 *
 * <p>Tags:
 * <ul>
 *   <li>One {@code ["z", zoneId]} tag per write/read zone (write first, read after)</li>
 *   <li>One {@code ["L", "did:key"]} tag identifying the DID scheme</li>
 * </ul>
 *
 * <p>Failure modes are all silent (logged at debug/warn):
 * <ul>
 *   <li>No Nostr adapter registered → no-op</li>
 *   <li>Record has no nostr ChannelRef → no-op</li>
 *   <li>Adapter returns a failure → log warn, never throw</li>
 * </ul>
 *
 * <p>The mirror is fire-and-forget: the outbox PUT route accepts the record
 * regardless of whether Nostr publish succeeds, because the outbox is
 * authoritative — Nostr is a downstream broadcast.
 */
public final class OutboxNostrMirror {

    private static final Logger log = LoggerFactory.getLogger(OutboxNostrMirror.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private OutboxNostrMirror() {}

    /**
     * Maybe publish a kind:0 metadata mirror of {@code record} to Nostr.
     *
     * @return true if a publish was attempted (whether or not it succeeded);
     *         false if the mirror was a no-op (no nostr adapter, no nostr
     *         ChannelRef, etc.)
     */
    public static boolean maybeMirror(IdentityOutboxRecord record) {
        if (record == null) return false;
        if (!hasNostrChannel(record)) return false;

        var nostr = ExternalAdapterRegistry.get().lookup("nostr");
        if (nostr.isEmpty()) {
            log.debug("Nostr adapter not registered — skipping outbox mirror for {}", record.did());
            return false;
        }

        String content;
        try {
            content = buildContent(record);
        } catch (Exception e) {
            log.warn("Failed to build outbox-mirror content for {}: {}",
                record.did(), e.getMessage());
            return false;
        }
        var tags = buildTags(record);

        var args = new LinkedHashMap<String, Object>();
        args.put("did", record.did());
        args.put("kind", 0);
        args.put("content", content);
        args.put("tags", tags);

        AdapterResponse resp;
        try {
            resp = ExternalAdapterRegistry.get().invoke(
                AdapterRequest.of("nostr", "publish", args));
        } catch (Exception e) {
            log.warn("Outbox→Nostr mirror threw for {}: {}", record.did(), e.getMessage());
            return true;  // attempt was made
        }
        if (resp.success()) {
            log.info("Outbox→Nostr mirror published for {} (kind:0)", record.did());
        } else {
            // Fail soft — outbox is authoritative; Nostr is downstream broadcast.
            var err = resp.error();
            log.warn("Outbox→Nostr mirror failed for {}: code={} message={}",
                record.did(),
                err == null ? "?" : err.code(),
                err == null ? "?" : err.message());
        }
        return true;
    }

    private static boolean hasNostrChannel(IdentityOutboxRecord record) {
        if (record.channels() == null) return false;
        for (var ch : record.channels()) {
            if (ch != null && "nostr".equalsIgnoreCase(ch.type())) return true;
        }
        return false;
    }

    private static String buildContent(IdentityOutboxRecord record) throws Exception {
        var m = new LinkedHashMap<String, Object>();
        var name = (record.displayName() == null || record.displayName().isBlank())
            ? record.did() : record.displayName();
        m.put("name", name);
        if (record.primaryZone() != null && !record.primaryZone().isBlank()) {
            m.put("about", "wyrdsekai identity in zone " + record.primaryZone());
            m.put("primaryZone", record.primaryZone());
        }
        m.put("did", record.did());
        return JSON.writeValueAsString(m);
    }

    private static List<List<String>> buildTags(IdentityOutboxRecord record) {
        var out = new ArrayList<List<String>>();
        if (record.writeZones() != null) {
            for (var z : record.writeZones()) {
                if (z != null && !z.isBlank()) out.add(List.of("z", z, "write"));
            }
        }
        if (record.readZones() != null) {
            for (var z : record.readZones()) {
                if (z != null && !z.isBlank()) out.add(List.of("z", z, "read"));
            }
        }
        out.add(List.of("L", "did:key"));
        return out;
    }
}
