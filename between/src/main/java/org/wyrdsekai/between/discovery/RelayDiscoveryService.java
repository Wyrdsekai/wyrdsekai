package org.wyrdsekai.between.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Relay discovery with multi-layer fallback.
 *
 * Discovery cascade:
 * 1. Local cache (~/.wyrdsekai/relays.json)
 * 2. DNS TXT records (_wyrdsekai.{domain})
 * 3. DHT lookup (Kademlia over NATS)
 * 4. Hardcoded seed relays
 *
 * Relay selection uses Web of Trust scoring:
 * - Relays attested by bonded households get highest trust
 * - Relays from the directory authority consensus are verified
 * - Unknown relays are usable but flagged as untrusted
 *
 * The service also handles relay registration and liveness tracking.
 */
public class RelayDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(RelayDiscoveryService.class);

    /** Default discovery domain for DNS TXT lookup. */
    public static final String DEFAULT_DISCOVERY_DOMAIN = "wyrdsekai.org";
    /** Default seed relay (hardcoded fallback). */
    public static final String DEFAULT_SEED_RELAY = "nats://relay.wyrdsekai.org:4222";
    /** Local cache filename. */
    private static final String CACHE_FILE = "relays.json";

    /** A discovered relay with metadata. */
    public record DiscoveredRelay(
        String url,
        boolean publicRelay,
        int capacity,
        int registered,
        String region,
        double trustScore,
        String discoveredVia,
        Instant discoveredAt
    ) {
        public boolean hasCapacity() { return registered < capacity; }
    }

    /** Result of relay discovery. */
    public record DiscoveryResult(
        List<DiscoveredRelay> relays,
        String method,
        boolean fromCache
    ) {
        public Optional<DiscoveredRelay> best() {
            return relays.stream()
                .filter(DiscoveredRelay::hasCapacity)
                .max(Comparator.comparingDouble(DiscoveredRelay::trustScore));
        }
    }

    /** Relay configuration — public vs private. */
    public record RelayConfig(
        boolean publicRelay,
        int capacity,
        String region,
        String registrationApiKey,
        boolean announceToNetwork
    ) {
        /** Public relay with default capacity. */
        public static RelayConfig publicRelay(int capacity, String region) {
            return new RelayConfig(true, capacity, region, null, true);
        }

        /** Private relay (household only, not announced). */
        public static RelayConfig privateRelay() {
            return new RelayConfig(false, 1, null, null, false);
        }
    }

    private final Path dataDir;
    private final KademliaTable dht;
    private final RelayTrustGraph trustGraph;
    private final RelayConsensus consensus;
    private final List<String> seedRelays;
    private final String discoveryDomain;

    // Cached discovery results
    private volatile List<DiscoveredRelay> cachedRelays = new ArrayList<>();
    private volatile Instant cacheTimestamp = Instant.EPOCH;

    public RelayDiscoveryService(Path dataDir, KademliaTable dht,
                                  RelayTrustGraph trustGraph, RelayConsensus consensus) {
        this(dataDir, dht, trustGraph, consensus, List.of(DEFAULT_SEED_RELAY), DEFAULT_DISCOVERY_DOMAIN);
    }

    public RelayDiscoveryService(Path dataDir, KademliaTable dht,
                                  RelayTrustGraph trustGraph, RelayConsensus consensus,
                                  List<String> seedRelays, String discoveryDomain) {
        this.dataDir = dataDir;
        this.dht = dht;
        this.trustGraph = trustGraph;
        this.consensus = consensus;
        this.seedRelays = seedRelays;
        this.discoveryDomain = discoveryDomain;
    }

    /**
     * Discover available relays using the multi-layer cascade.
     */
    public DiscoveryResult discover() {
        // 1. Try local cache (< 30 min old)
        var cached = loadFromCache();
        if (!cached.isEmpty()) {
            log.debug("Relay discovery: {} relays from local cache", cached.size());
            return new DiscoveryResult(cached, "cache", true);
        }

        // 2. Try DNS TXT records
        var dnsTxt = discoverViaDnsTxt();
        if (!dnsTxt.isEmpty()) {
            log.info("Relay discovery: {} relays from DNS TXT ({})", dnsTxt.size(), discoveryDomain);
            saveToCache(dnsTxt);
            return new DiscoveryResult(dnsTxt, "dns-txt", false);
        }

        // 3. Try directory authority consensus
        var consensusRelays = discoverViaConsensus();
        if (!consensusRelays.isEmpty()) {
            log.info("Relay discovery: {} relays from authority consensus", consensusRelays.size());
            saveToCache(consensusRelays);
            return new DiscoveryResult(consensusRelays, "consensus", false);
        }

        // 4. Try DHT
        var dhtRelays = discoverViaDht();
        if (!dhtRelays.isEmpty()) {
            log.info("Relay discovery: {} relays from DHT", dhtRelays.size());
            saveToCache(dhtRelays);
            return new DiscoveryResult(dhtRelays, "dht", false);
        }

        // 5. Fallback to seed relays
        var seeds = seedRelays.stream()
            .map(url -> new DiscoveredRelay(url, true, 500, 0, "unknown",
                0.5, "seed", Instant.now()))
            .toList();
        log.info("Relay discovery: using {} seed relay(s)", seeds.size());
        return new DiscoveryResult(seeds, "seed", false);
    }

    /**
     * Discover relays via DNS TXT records.
     * Looks up _wyrdsekai.{domain} for TXT records containing relay URLs.
     * Format: "relay=nats://host:port region=us-east capacity=500"
     */
    List<DiscoveredRelay> discoverViaDnsTxt() {
        try {
            var props = new Hashtable<String, String>();
            props.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            var ctx = new InitialDirContext(props);
            var attrs = ctx.getAttributes("_wyrdsekai." + discoveryDomain, new String[]{"TXT"});
            var txtAttr = attrs.get("TXT");

            if (txtAttr == null) return List.of();

            var relays = new ArrayList<DiscoveredRelay>();
            for (int i = 0; i < txtAttr.size(); i++) {
                var txt = txtAttr.get(i).toString().replace("\"", "");
                var parsed = parseDnsTxtRelay(txt);
                if (parsed != null) relays.add(parsed);
            }
            return relays;
        } catch (Exception e) {
            log.debug("DNS TXT discovery failed for {}: {}", discoveryDomain, e.getMessage());
            return List.of();
        }
    }

    /** Parse a DNS TXT record into a DiscoveredRelay. */
    DiscoveredRelay parseDnsTxtRelay(String txt) {
        // Format: "relay=nats://host:port region=us-east capacity=500"
        String url = null, region = "unknown";
        int capacity = 500;

        for (var part : txt.split("\\s+")) {
            if (part.startsWith("relay=")) url = part.substring(6);
            else if (part.startsWith("region=")) region = part.substring(7);
            else if (part.startsWith("capacity=")) {
                try { capacity = Integer.parseInt(part.substring(9)); } catch (NumberFormatException ignored) {}
            }
        }

        if (url == null) return null;

        double trustScore = trustGraph != null ? trustGraph.trustForRelay(url) : 0.5;
        return new DiscoveredRelay(url, true, capacity, 0, region,
            trustScore, "dns-txt", Instant.now());
    }

    /** Discover relays from the authority consensus. */
    List<DiscoveredRelay> discoverViaConsensus() {
        if (consensus == null) return List.of();
        return consensus.availableRelays().stream()
            .map(r -> {
                double trust = trustGraph != null ? trustGraph.trustForRelay(r.url()) : 0.5;
                return new DiscoveredRelay(r.url(), r.publicRelay(), r.capacity(),
                    r.registered(), r.region(), trust, "consensus", Instant.now());
            })
            .toList();
    }

    /** Discover relays from the DHT. */
    List<DiscoveredRelay> discoverViaDht() {
        if (dht == null) return List.of();
        return dht.allRelays().stream()
            .map(v -> {
                var parts = new String(v.value()).split("\\|");
                if (parts.length < 4) return null;
                String url = parts[0];
                boolean pub = Boolean.parseBoolean(parts[1]);
                int cap = 500;
                int reg = 0;
                try { cap = Integer.parseInt(parts[2]); } catch (Exception ignored) {}
                try { reg = Integer.parseInt(parts[3]); } catch (Exception ignored) {}
                double trust = trustGraph != null ? trustGraph.trustForRelay(url) : 0.3;
                return new DiscoveredRelay(url, pub, cap, reg, "unknown", trust, "dht", Instant.now());
            })
            .filter(Objects::nonNull)
            .toList();
    }

    // --- Local cache ---

    List<DiscoveredRelay> loadFromCache() {
        var cacheFile = dataDir.resolve(CACHE_FILE);
        if (!Files.exists(cacheFile)) return List.of();

        try {
            var content = Files.readString(cacheFile);
            var mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            var type = mapper.getTypeFactory()
                .constructCollectionType(List.class, DiscoveredRelay.class);
            List<DiscoveredRelay> cached = mapper.readValue(content, type);

            // Check freshness — 30 min TTL
            if (!cached.isEmpty() && cached.get(0).discoveredAt()
                    .isAfter(Instant.now().minusSeconds(1800))) {
                return cached;
            }
            return List.of(); // stale
        } catch (Exception e) {
            log.debug("Failed to load relay cache: {}", e.getMessage());
            return List.of();
        }
    }

    void saveToCache(List<DiscoveredRelay> relays) {
        try {
            var cacheFile = dataDir.resolve(CACHE_FILE);
            Files.createDirectories(dataDir);
            var mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), relays);
        } catch (IOException e) {
            log.debug("Failed to save relay cache: {}", e.getMessage());
        }
    }

    // --- Accessors ---

    public KademliaTable dht() { return dht; }
    public RelayTrustGraph trustGraph() { return trustGraph; }
    public RelayConsensus consensus() { return consensus; }
}
