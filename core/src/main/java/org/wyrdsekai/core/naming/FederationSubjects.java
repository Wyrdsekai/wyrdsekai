package org.wyrdsekai.core.naming;

/**
 * Constructors for NATS subjects on the federation wire.
 *
 * <p> replaces the bare-string subject form
 * {@code federation.{zoneId}.gate.*} with the keypair-anchored form
 * {@code federation.{fingerprint}.{label}.gate.*}. During Phase 1 we
 * accept <b>both</b> forms on receive so rolling upgrades don't require
 * a coordinated cutover. Phase 2 drops the legacy form.</p>
 *
 * <p>Keep every subject construction in this one file so the migration is
 * a single-site change:</p>
 * <ul>
 *   <li>Callers that hold only a legacy zoneId use
 *       {@link #legacyGate(String, String)} — emits
 *       {@code federation.{zoneId}.gate.{action}}.</li>
 *   <li>Callers that hold a {@link ZoneAddress} use
 *       {@link #canonicalGate(ZoneAddress, String)} — emits
 *       {@code federation.{fingerprint}.{label}.gate.{action}}.</li>
 *   <li>Listeners use the two {@code *Pattern} helpers to build their
 *       own subscription filters. A Phase-1 FederationActor subscribes
 *       to both — one handler, two patterns — so legacy and canonical
 *       senders both reach it.</li>
 * </ul>
 *
 * <p>This class is pure string construction: no validation, no side
 * effects. Validation of labels/fingerprints happens at the
 * {@link ZoneAddress} boundary, so by the time a subject string lands on
 * the wire it's already been filtered through {@link ZoneLabels} rules.</p>
 */
public final class FederationSubjects {

    /** The literal prefix — single source of truth across the codebase. */
    public static final String FEDERATION_PREFIX = "federation.";

    private FederationSubjects() {}

    // ── gate subjects ─────────────────────────────────────────────────

    /**
     * Legacy Phase-0 form: {@code federation.{zoneId}.gate.{action}} where
     * {@code zoneId} is the bare string from {@code WYRDSEKAI_ZONE_ID}.
     * Callers that have not yet migrated to keypair-anchored addresses use
     * this form; the receive side of every Phase-1 node still subscribes to
     * it.
     */
    public static String legacyGate(String zoneId, String action) {
        return FEDERATION_PREFIX + zoneId + ".gate." + action;
    }

    /**
     * Phase-1/2 canonical form: {@code federation.{fingerprint}.{label}.gate.{action}}.
     * The address is already validated ({@link ZoneAddress} rejects reserved
     * labels and malformed fingerprints), so the resulting subject is
     * guaranteed NATS-safe without further escaping.
     */
    public static String canonicalGate(ZoneAddress address, String action) {
        return FEDERATION_PREFIX + address.toWireSubject() + ".gate." + action;
    }

    /**
     * Subscription pattern for the legacy gate: {@code federation.{zoneId}.gate.>}.
     * Matches all gate actions (propose/accept/revoke/manifest/transit_*).
     */
    public static String legacyGatePattern(String zoneId) {
        return FEDERATION_PREFIX + zoneId + ".gate.>";
    }

    /**
     * Subscription pattern for the canonical gate:
     * {@code federation.{fingerprint}.{label}.gate.>}.
     */
    public static String canonicalGatePattern(ZoneAddress address) {
        return FEDERATION_PREFIX + address.toWireSubject() + ".gate.>";
    }

    // ── tell subjects (cross-zone targeted delivery) ──────────────────

    /** Legacy: {@code federation.{zoneId}.tell}. */
    public static String legacyTell(String zoneId) {
        return FEDERATION_PREFIX + zoneId + ".tell";
    }

    /** Canonical: {@code federation.{fingerprint}.{label}.tell}. */
    public static String canonicalTell(ZoneAddress address) {
        return FEDERATION_PREFIX + address.toWireSubject() + ".tell";
    }

    // ── peek subjects (cross-zone read-only room snapshot, ) ─

    /**
     * Request subject a zone listens on for {@code world.peek} lookups:
     * {@code federation.{zoneId}.peek}. The requester publishes a
     * {@code {requestId, sourceZone, roomAlias}} payload here; the target
     * zone's responder replies on {@link #peekReply(String)} of the source.
     */
    public static String peekRequest(String zoneId) {
        return FEDERATION_PREFIX + zoneId + ".peek";
    }

    /**
     * Reply subject a requesting zone listens on for peek results:
     * {@code federation.{zoneId}.peek_reply}. The responder publishes a
     * {@code {requestId, snapshot}} payload here (snapshot null ⇒ denied /
     * no such room); the requester correlates by {@code requestId}.
     */
    public static String peekReply(String zoneId) {
        return FEDERATION_PREFIX + zoneId + ".peek_reply";
    }

    // ── inspection ────────────────────────────────────────────────────

    /**
     * @return true if the subject is a federation subject in EITHER form.
     *     Used by RelayBridge and similar forwarders to decide whether to
     *     bridge a subject they don't recognise more specifically.
     */
    public static boolean isFederationSubject(String subject) {
        return subject != null && subject.startsWith(FEDERATION_PREFIX);
    }
}
