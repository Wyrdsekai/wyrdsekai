package org.wyrdsekai.core.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.core.agent.research.ZoneArgotService;

import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * foundation — process-wide holder for this node's per-zone master secrets, and the
 * boot bootstrap that establishes the local zone's master and installs the argot key provider.
 *
 * <p>Architecture today is one zone per node ({@code WyrdConfig.zoneId()}), so the bootstrap
 * ensures exactly one master: load it (unwrap from {@code zone_wrapped_secrets} under the node KEK)
 * if this node already holds it, else ORIGINATE a fresh one and persist it wrapped. Originate-on-
 * first-boot is correct while each node is its own zone's originator. When true multi-node-same-zone
 * lands, a JOINING node must receive the master via {@link ZoneSecretService#acceptGrant} BEFORE
 * first argot use rather than originate a divergent one — gate the originate path then.
 *
 * <p>Non-fatal by design: any failure leaves the argot key provider unset, so {@link ZoneArgotService}
 * falls back to its public seed (wire-obfuscation only) — the node still runs, just without the
 * stronger opacity. Once installed, every same-zone node derives the identical argot key.
 */
public final class ZoneSecrets {

    private static final Logger log = LoggerFactory.getLogger(ZoneSecrets.class);
    private static final ZoneSecretService SERVICE = new ZoneSecretService();

    private ZoneSecrets() {}

    /** The process-wide zone-secret service (holds unwrapped masters in memory). */
    public static ZoneSecretService service() { return SERVICE; }

    /**
     * Establish the local zone's master secret and wire the argot key provider. Idempotent-ish:
     * re-running loads the same persisted master. Call once at boot, after the DB is initialized
     * and the node identity is loaded.
     *
     * <p>The {@code soleNode} flag governs the ORIGINATE decision, which is the load-bearing
     * multi-node-safety rule (#1184): a node may only ORIGINATE a fresh master when it is the sole
     * node in the zone. A multi-node JOINING node that does not yet hold the master must NOT
     * originate a divergent one — it waits for the holder to {@code grantTo} it (see
     * {@link #installGrantedMaster}), and stays on the public seed until then. A node that ALREADY
     * holds a persisted master (originated earlier as sole, or received + persisted via a prior
     * grant) loads it and installs the secret provider regardless of node count — because holding
     * the master IS the proof that this node agrees with the zone, and the grant flow guarantees all
     * holders share the one master.
     */
    public static synchronized void bootstrapLocalZone(String jdbcUrl, String zoneId,
                                                       String nodeId, byte[] nodeSeed,
                                                       boolean soleNode) {
        if (jdbcUrl == null || zoneId == null || zoneId.isBlank()
            || nodeId == null || nodeId.isBlank() || nodeSeed == null || nodeSeed.length == 0) {
            log.warn("Zone-secret bootstrap skipped — missing jdbc/zone/node/seed; argot uses public seed");
            return;
        }
        try {
            var store = new ZoneSecretStore(jdbcUrl);
            var kek = ZoneSecretService.nodeKek(nodeSeed);
            var wrapped = store.get(zoneId, nodeId);
            if (wrapped.isPresent()) {
                // We hold the zone's agreed master (originated-as-sole or granted-then-persisted) →
                // install the secret provider whatever the node count; this node shares the zone.
                SERVICE.installFromWrapped(zoneId, wrapped.get(), kek);
                log.info("Zone secret for '{}' loaded (unwrapped under node KEK)", zoneId);
                installSecretProvider(zoneId);
            } else if (soleNode) {
                // Sole node, no master yet → originate it (correct: nobody else to agree with).
                SERVICE.generate(zoneId);
                store.put(zoneId, nodeId, SERVICE.wrapForNode(zoneId, kek));
                log.info("Zone secret for '{}' originated + persisted (wrapped per node)", zoneId);
                installSecretProvider(zoneId);
            } else {
                // SAFETY GATE: multi-node joiner without the master must NOT originate a divergent
                // one — that would split the zone's language. Stay on the public seed (wire-
                // obfuscation only) until the holder grants us the master via installGrantedMaster.
                log.info("Zone '{}' has no local master and this is NOT the sole node — NOT "
                    + "originating (would diverge). Awaiting X25519 grant from the zone holder; "
                    + "argot uses the public seed until then.", zoneId);
            }
        } catch (Exception e) {
            log.warn("Zone-secret bootstrap failed (non-fatal; argot falls back to public seed): {}",
                e.getMessage());
        }
    }

    /**
     * Accept a zone-master {@link ZoneSecretService#grantTo grant} from the zone holder, persist it
     * wrapped under this node's KEK (so the next boot loads it via {@link #bootstrapLocalZone}), and
     * install the secret argot provider — promoting this joining node from the public seed to the
     * secret-derived codebook all same-zone nodes share. Idempotent: a repeat grant just re-installs
     * the same master. Returns {@code true} on success.
     *
     * @param myEcdhPrivPkcs8 this node's X25519 grant private key (PKCS#8), from
     *                        {@code NodeIdentity.x25519PrivateKeyPkcs8()}.
     */
    public static synchronized boolean installGrantedMaster(String jdbcUrl, String zoneId,
                                                            String nodeId, byte[] nodeSeed,
                                                            byte[] grantBlob, byte[] myEcdhPrivPkcs8) {
        if (jdbcUrl == null || zoneId == null || zoneId.isBlank() || nodeId == null
            || nodeSeed == null || grantBlob == null || myEcdhPrivPkcs8 == null) {
            log.warn("Zone-secret grant install skipped for '{}' — missing argument", zoneId);
            return false;
        }
        try {
            var priv = KeyFactory.getInstance("XDH")
                .generatePrivate(new PKCS8EncodedKeySpec(myEcdhPrivPkcs8));
            SERVICE.acceptGrant(zoneId, grantBlob, priv);          // unwrap → hold master in memory
            var kek = ZoneSecretService.nodeKek(nodeSeed);
            new ZoneSecretStore(jdbcUrl).put(zoneId, nodeId, SERVICE.wrapForNode(zoneId, kek));
            installSecretProvider(zoneId);
            log.info("Zone '{}' master received via grant + persisted; this node is now on the "
                + "secret-derived argot codebook (joined the zone's language).", zoneId);
            return true;
        } catch (Exception e) {
            log.warn("Zone-secret grant install failed for '{}' (argot stays on public seed): {}",
                zoneId, e.getMessage());
            return false;
        }
    }

    /** Switch {@link ZoneArgotService} to the secret-derived seed for any zone whose master we hold. */
    private static void installSecretProvider(String zoneId) {
        ZoneArgotService.setArgotKeyProvider(
            z -> SERVICE.has(z) ? SERVICE.derive(z, "argot-v1") : null);
        log.info("Argot key provider installed — zone '{}' argot is now secret-derived "
            + "(opacity + forge-resistance vs the public seed)", zoneId);
    }
}
