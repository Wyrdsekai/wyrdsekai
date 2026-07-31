package org.wyrdsekai.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * mDNS (Zeroconf / Bonjour) advertise + browse for wyrdsekai household
 * discovery on a LAN. Tier 3 of the discovery hierarchy:
 *
 * <ul>
 *   <li><b>Always advertise</b> — every node that has {@code discovery.mdns_enabled
 *       = true} broadcasts {@code _wyrdsekai._tcp.local.} so nearby nodes can
 *       see it exists. Detection ≠ acceptance — joining still requires
 *       mutual consent (knock + admin approval).</li>
 *   <li><b>Browse on demand</b> — {@code wyrd discover --lan} or first-run
 *       {@code wyrd setup} calls {@link #browse(int)} to look for peers.
 *       Time-boxed (default 3s) so commands don't hang.</li>
 * </ul>
 *
 * <p>Service TXT record carries node properties: name, zone, household
 * fingerprint (or "none" for solo nodes), peer-training/relay-hosting
 * capabilities. The household fingerprint lets a fresh node distinguish
 * "Alice's household" from "Bob's household" before initiating a join.</p>
 *
 * <p>Privacy: only the LAN sees these announcements (mDNS is link-local).
 * Tokens are never advertised — only public node identity + capabilities.</p>
 */
public final class MdnsDiscovery implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MdnsDiscovery.class);

    /** What gets advertised in the TXT record. Operators can read this
     *  via {@code wyrd discover --lan} to inspect a peer before joining.
     *
     *  <p>{@code nodeId} is the UUID Between uses to dedup self vs peers
     *  on the bus; {@code nodeName} is the human-readable label. Both are
     *  carried so Between's mDNS-driven clustering logic and the operator
     *  CLI both have what they need from one advertisement.</p>
     *
     *  <p>{@code natsUrl} and {@code arteryPort} are optional cluster
     *  hints for Between peer discovery. {@code null} / {@code 0} means
     *  "not advertised" — typical on phones / nodes that connect to a
     *  remote NATS rather than hosting one.</p>
     */
    public record Advertisement(
        String nodeId,
        String nodeName,
        String zone,
        String householdId,    // "none" for solo, else fingerprint prefix
        int httpPort,
        String natsUrl,
        int arteryPort,
        boolean hostsRelay,
        boolean hostsPeerTraining,
        boolean hasInference
    ) {
        /** Convenience overload for callers that don't need cluster hints. */
        public Advertisement(String nodeName, String zone, String householdId,
                             int httpPort, boolean hostsRelay,
                             boolean hostsPeerTraining, boolean hasInference) {
            this(null, nodeName, zone, householdId, httpPort,
                 null, 0, hostsRelay, hostsPeerTraining, hasInference);
        }

        Map<String, String> txtMap() {
            var m = new HashMap<String, String>();
            if (nodeId != null && !nodeId.isBlank()) m.put("nodeId", nodeId);
            m.put("name", nodeName);
            m.put("zone", zone);
            m.put("hh", householdId);
            m.put("relay", String.valueOf(hostsRelay));
            m.put("peertrain", String.valueOf(hostsPeerTraining));
            m.put("inference", String.valueOf(hasInference));
            // Cluster hints — Between uses these to form Pekko peer connections.
            // SECURITY: relay token NEVER goes in TXT (link-local broadcast).
            if (natsUrl != null && !natsUrl.isBlank()) m.put("natsUrl", natsUrl);
            if (arteryPort > 0) m.put("arteryPort", String.valueOf(arteryPort));
            m.put("v", "1");  // protocol version, for future compat
            return m;
        }
    }

    /** What a browse() returns about a discovered peer. */
    public record DiscoveredPeer(
        String serviceName,
        String hostName,
        int port,
        Map<String, String> txt
    ) {
        public String displayName() {
            var n = txt.get("name");
            return n != null ? n : serviceName;
        }
        public String household() { return txt.getOrDefault("hh", "?"); }
    }

    private final String serviceType;
    private volatile JmDNS jmdns;
    private volatile ServiceInfo registered;

    public MdnsDiscovery(String serviceType) {
        this.serviceType = serviceType.endsWith(".") ? serviceType : serviceType + ".";
    }

    /** Start advertising. Idempotent — calling twice with the same advertisement
     *  is a no-op; with a different one, re-registers. */
    public synchronized void advertise(Advertisement adv) throws IOException {
        if (jmdns == null) {
            // Bind to the primary network interface. JmDNS auto-detects from
            // InetAddress.getLocalHost(); on dual-homed boxes this picks one
            // (usually the wired interface). Future: bind to all interfaces.
            jmdns = JmDNS.create(InetAddress.getLocalHost());
        }
        if (registered != null) {
            jmdns.unregisterService(registered);
        }
        registered = ServiceInfo.create(
            serviceType,
            adv.nodeName(),
            adv.httpPort(),
            0,    // weight
            0,    // priority
            adv.txtMap()
        );
        jmdns.registerService(registered);
        log.info("mDNS advertising {} as '{}' on port {} (household={}, peer-train={}, relay={})",
            serviceType, adv.nodeName(), adv.httpPort(),
            adv.householdId(), adv.hostsPeerTraining(), adv.hostsRelay());
    }

    /** Register a long-lived listener that fires whenever a new peer is
     *  resolved. Used by Between for cluster formation: every peer seen on
     *  the LAN gets a {@code PeerDiscovered} message. The listener is held
     *  for the lifetime of the JmDNS instance — call {@link #close} to stop.
     *
     *  <p>Self is filtered out automatically. Drop-out events (peer leaves
     *  the network) currently aren't propagated — the consumer is expected
     *  to age its own peer table from heartbeats.</p>
     */
    public void addListener(Consumer<DiscoveredPeer> onPeer) throws IOException {
        if (jmdns == null) {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
        }
        jmdns.addServiceListener(serviceType, new ServiceListener() {
            @Override public void serviceAdded(ServiceEvent e) { /* await resolved */ }
            @Override public void serviceRemoved(ServiceEvent e) { /* see javadoc */ }
            @Override public void serviceResolved(ServiceEvent e) {
                var info = e.getInfo();
                if (info == null) return;
                if (registered != null && registered.getName().equals(info.getName())) return;
                var txt = new HashMap<String, String>();
                for (var key : Collections.list(info.getPropertyNames())) {
                    var val = info.getPropertyString(key);
                    if (val != null) txt.put(key, val);
                }
                var host = info.getServer();
                if (host == null && info.getInet4Addresses().length > 0) {
                    host = info.getInet4Addresses()[0].getHostAddress();
                }
                onPeer.accept(new DiscoveredPeer(
                    info.getName(),
                    host != null ? host : "?",
                    info.getPort(),
                    txt
                ));
            }
        });
    }

    /** Browse for peers for the given duration. Returns whatever was seen.
     *  Excludes our own advertisement if it's running. */
    public List<DiscoveredPeer> browse(int durationMs) throws IOException {
        if (jmdns == null) {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
        }
        var found = new ConcurrentHashMap<String, DiscoveredPeer>();
        var listener = new ServiceListener() {
            @Override public void serviceAdded(ServiceEvent e) { /* no-op until resolved */ }
            @Override public void serviceRemoved(ServiceEvent e) { found.remove(e.getName()); }
            @Override public void serviceResolved(ServiceEvent e) {
                var info = e.getInfo();
                if (info == null) return;
                // Skip ourselves
                if (registered != null && registered.getName().equals(info.getName())) return;
                var txt = new HashMap<String, String>();
                for (var key : Collections.list(info.getPropertyNames())) {
                    var val = info.getPropertyString(key);
                    if (val != null) txt.put(key, val);
                }
                var host = info.getServer();
                if (host == null && info.getInet4Addresses().length > 0) {
                    host = info.getInet4Addresses()[0].getHostAddress();
                }
                found.put(info.getName(), new DiscoveredPeer(
                    info.getName(),
                    host != null ? host : "?",
                    info.getPort(),
                    txt
                ));
            }
        };
        jmdns.addServiceListener(serviceType, listener);
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            jmdns.removeServiceListener(serviceType, listener);
        }
        return new ArrayList<>(found.values());
    }

    @Override
    public synchronized void close() {
        if (registered != null && jmdns != null) {
            jmdns.unregisterService(registered);
            registered = null;
        }
        if (jmdns != null) {
            try { jmdns.close(); } catch (IOException e) { /* ignore */ }
            jmdns = null;
        }
    }

    /** Singleton-ish: one advertiser per process for the default service.
     *  Wired by {@code Main.java} during server startup. */
    private static final AtomicReference<MdnsDiscovery> DEFAULT = new AtomicReference<>();

    public static MdnsDiscovery defaultInstance() {
        var i = DEFAULT.get();
        if (i != null) return i;
        var fresh = new MdnsDiscovery(WyrdConfig.get().mdnsService());
        return DEFAULT.compareAndSet(null, fresh) ? fresh : DEFAULT.get();
    }
}
