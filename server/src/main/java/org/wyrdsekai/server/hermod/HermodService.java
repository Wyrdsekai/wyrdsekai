package org.wyrdsekai.server.hermod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.hermod.AdmissionGate;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.CapabilityTable;
import org.wyrdsekai.hermod.DefaultRouter;
import org.wyrdsekai.hermod.GossipTransport;
import org.wyrdsekai.hermod.GrantAuthority;
import org.wyrdsekai.hermod.LocalAdmissionGate;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.TaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;

/**
 * One per device: owns this device's capability table, advertises on a
 * heartbeat, and answers its own door. Placement stays with each
 * origin's router; admission stays here. Remote doors (NATS RPC) arrive
 * with P3 — until then the mesh serves local-first deployments and the
 * table gives every device an honest view of the household.
 */
public final class HermodService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HermodService.class);
    private static final Duration TTL = Duration.ofSeconds(90);
    private static final long HEARTBEAT_SECONDS = 30;
    private static final long TOKEN_CEILING = 8192;

    private final GossipTransport transport;
    private final String deviceId;
    private final String scopeId;
    private final String capabilityClass;
    private final List<String> models;
    private final Clock clock;
    private final CapabilityTable table = new CapabilityTable(TTL);
    private final TaskExecutor localExecutor; // nullable until seat-config wires one
    private final byte[] authoritySpki;       // household authority public key; null = deny all granted-domain tasks
    private RemoteDoors remoteDoors;          // nullable: local-only deployments
    private volatile List<String> residentDomains = List.of();

    /** How this device knocks on another's door (NATS RPC in wyrdsekai). */
    public interface RemoteDoors {
        Mesh.DoorProtocol doorTo(String deviceId);
    }

    public void remoteDoors(RemoteDoors doors) {
        this.remoteDoors = doors;
    }

    /** Deployment-declared resident data domains (consent semantics are human judgments). */
    public void residentDomains(List<String> domains) {
        this.residentDomains = List.copyOf(domains);
    }

    /** This device's own door, for a door-server to answer remote knocks. */
    public Mesh.DoorProtocol ownDoor() {
        var gate = new LocalAdmissionGate(clock, TOKEN_CEILING,
            GrantAuthority.verifier(authoritySpki), e -> false);
        return localExecutor == null
            ? Mesh.closed("no local executor configured")
            : Mesh.local(gate, localExecutor);
    }
    private final ScheduledExecutorService heartbeat =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "hermod-heartbeat");
            t.setDaemon(true);
            return t;
        });

    public HermodService(GossipTransport transport, String scopeId, String deviceId,
                         String capabilityClass, List<String> models,
                         TaskExecutor localExecutor, Clock clock) {
        this(transport, scopeId, deviceId, capabilityClass, models, localExecutor, clock, null);
    }

    public HermodService(GossipTransport transport, String scopeId, String deviceId,
                         String capabilityClass, List<String> models,
                         TaskExecutor localExecutor, Clock clock, byte[] authoritySpki) {
        this.transport = transport;
        this.scopeId = scopeId;
        this.deviceId = deviceId;
        this.capabilityClass = capabilityClass;
        this.models = models;
        this.localExecutor = localExecutor;
        this.clock = clock;
        this.authoritySpki = authoritySpki;
    }

    public void start() {
        table.attach(transport);
        heartbeat.scheduleAtFixedRate(this::advertise, 0, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        log.info("hermod: advertising {} as '{}' on scope {}", models, capabilityClass, scopeId);
    }

    private void advertise() {
        try {
            // Real signals: normalized 1-min load; a mains-powered node is
            // always "charging"; idle below half load. Phones report their
            // own battery truth through the same fields (P3 listener).
            var os = ManagementFactory.getOperatingSystemMXBean();
            var loadAvg = os.getSystemLoadAverage();
            var load = loadAvg < 0
                ? 0.0
                : Math.min(1.0, loadAvg / Math.max(1, os.getAvailableProcessors()));
            var cap = new Capability(deviceId, scopeId, capabilityClass, models,
                residentDomains, true, load < 0.5, load, Instant.now(clock));
            transport.publish(cap);
            table.merge(cap); // a device always sees itself
        } catch (Exception e) {
            log.debug("hermod heartbeat skipped: {}", e.getMessage());
        }
    }

    public String deviceId() {
        return deviceId;
    }

    public CapabilityTable table() {
        return table;
    }

    /** The origin-side mesh for THIS device's requests. */
    public Mesh mesh() {
        var gate = new LocalAdmissionGate(clock, TOKEN_CEILING,
            GrantAuthority.verifier(authoritySpki), e -> false);
        return new Mesh(new DefaultRouter(table, clock), (envelope, cap) -> {
            if (cap.deviceId().equals(deviceId)) {
                if (localExecutor == null) {
                    return Mesh.closed("no local executor configured");
                }
                return Mesh.local(gate, localExecutor);
            }
            if (remoteDoors == null) {
                return Mesh.closed("no remote transport on this device");
            }
            return remoteDoors.doorTo(cap.deviceId());
        });
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
    }
}
