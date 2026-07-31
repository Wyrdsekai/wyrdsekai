package org.wyrdsekai.core.home;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DID → zone + zone → HTTP base URL resolution.
 *
 * <p>Used by {@link HomeProxy} to route cross-zone knocks to the zone that
 * hosts a DID's Home. Real deployments plug in a federation-backed directory
 * that reads from the ZoneManifest store and DID documents; tests + small
 * setups use {@link StaticZoneDirectory}.</p>
 */
public interface ZoneDirectory {

    /** Zone hosting the given DID's Home, or empty when unknown. */
    Optional<String> zoneOf(String did);

    /** HTTP base URL (e.g. {@code http://test-node:7070}) for reaching a zone's REST API. */
    Optional<String> httpBaseOf(String zoneId);

    /** A never-empty directory that reports every DID as living in one fixed zone. */
    static ZoneDirectory singleZone(String zoneId, String httpBase) {
        return new ZoneDirectory() {
            @Override public Optional<String> zoneOf(String did) { return Optional.of(zoneId); }
            @Override public Optional<String> httpBaseOf(String z) {
                return zoneId.equals(z) ? Optional.ofNullable(httpBase) : Optional.empty();
            }
        };
    }

    /** Mutable in-memory directory; useful for config-driven setups and tests. */
    final class StaticZoneDirectory implements ZoneDirectory {
        private final Map<String, String> didToZone = new ConcurrentHashMap<>();
        private final Map<String, String> zoneToHttp = new ConcurrentHashMap<>();
        private final String defaultZone;

        public StaticZoneDirectory(String defaultZone) {
            this.defaultZone = defaultZone;
        }

        public StaticZoneDirectory mapDid(String did, String zone) {
            didToZone.put(did, zone);
            return this;
        }

        public StaticZoneDirectory mapZoneHttp(String zone, String httpBase) {
            zoneToHttp.put(zone, httpBase);
            return this;
        }

        @Override public Optional<String> zoneOf(String did) {
            if (did == null) return Optional.empty();
            if (didToZone.containsKey(did)) return Optional.of(didToZone.get(did));
            // Convention: did:zone:{zoneId} maps back to its own zone.
            if (did.startsWith("did:zone:")) {
                return Optional.of(did.substring("did:zone:".length()));
            }
            return defaultZone != null ? Optional.of(defaultZone) : Optional.empty();
        }

        @Override public Optional<String> httpBaseOf(String zoneId) {
            return Optional.ofNullable(zoneToHttp.get(zoneId));
        }
    }
}
