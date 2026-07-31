package org.wyrdsekai.core.naming;

import java.util.Optional;

/**
 * Canonical address of a zone — the tuple {@code (householdFingerprint, zoneLabel)}.
 *
 * <p>On the wire, a zone is always identified by its canonical form:
 * {@code did:wyrd:{fingerprint}:{label}}. At the CLI / UX layer users type
 * short forms ({@code kitchen}, {@code alice:kitchen}) that resolve through
 * {@link ZoneAddressResolver} into a {@link ZoneAddress}.</p>
 *
 * <p>This record is the common currency between the address book, the
 * resolver, and the NATS/federation plumbing. It deliberately carries no
 * human-readable alias — aliases are caller-local and live in the
 * {@link ContactsBook} / {@link LocalZoneRegistry}. Passing a
 * {@code ZoneAddress} around means "we've already resolved; this is the
 * unforgeable identity."</p>
 *
 * <p> for the design rationale (keypair-anchored
 * identity + local aliases, SSH {@code known_hosts} model).</p>
 *
 * @param fingerprint the multibase household fingerprint ({@code z6Mk…}),
 *                    <em>without</em> the {@code did:wyrd:} scheme prefix.
 * @param label       the household-local zone label ({@code kitchen}),
 *                    validated at construction time.
 */
public record ZoneAddress(String fingerprint, String label) {

    public ZoneAddress {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint cannot be empty");
        }
        if (!fingerprint.startsWith("z")) {
            // We require the multibase 'z' prefix — catches the mistake of
            // passing a did-scheme string ({@code did:wyrd:z6Mk…}) or a raw
            // base58 string without the multibase marker.
            throw new IllegalArgumentException(
                "fingerprint must be multibase-encoded (starts with 'z'): " + fingerprint);
        }
        ZoneLabels.requireValid(label, "zone label");
    }

    /**
     * Fully-qualified wire form: {@code did:wyrd:z6Mk…:kitchen}. This is the
     * canonical string that lands in NATS subjects, manifest JSON, signed
     * envelopes — anywhere two nodes refer to a zone and need it to be
     * unambiguous.
     */
    public String toCanonical() {
        return HouseholdIdentity.DID_SCHEME + fingerprint + ":" + label;
    }

    /**
     * NATS-subject-safe form: {@code z6Mk….kitchen}. Suitable for
     * {@code federation.{fingerprint}.{label}.gate.*} patterns
     *
     * <p>The subject separator in NATS is {@code .}, so we join fingerprint
     * and label with a dot. Both components are validated at construction:
     * fingerprints are base58btc (no dots), labels reject dots via
     * {@link ZoneLabels}, so this projection is unambiguous in reverse —
     * see {@link #parseWireSubject(String)}.</p>
     */
    public String toWireSubject() {
        return fingerprint + "." + label;
    }

    /**
     * Parse a canonical form string ({@code did:wyrd:z6Mk…:kitchen}).
     * Returns empty on any malformed input — callers that want a specific
     * error message should re-validate and throw.
     */
    public static Optional<ZoneAddress> parseCanonical(String s) {
        if (s == null || !s.startsWith(HouseholdIdentity.DID_SCHEME)) {
            return Optional.empty();
        }
        var rest = s.substring(HouseholdIdentity.DID_SCHEME.length());
        // Split on the LAST colon — fingerprint contains no colon, labels
        // contain no colon, so at most one separator exists.
        int sep = rest.indexOf(':');
        if (sep < 0 || sep == 0 || sep == rest.length() - 1) {
            return Optional.empty();
        }
        var fp = rest.substring(0, sep);
        var label = rest.substring(sep + 1);
        try {
            return Optional.of(new ZoneAddress(fp, label));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Parse a wire-subject form {@code z6Mk….kitchen} — the inverse of
     * {@link #toWireSubject()}. Used when reading federation subjects off
     * NATS and reconstructing the zone identity.
     */
    public static Optional<ZoneAddress> parseWireSubject(String s) {
        if (s == null || s.isEmpty()) return Optional.empty();
        int sep = s.lastIndexOf('.');
        if (sep < 0 || sep == 0 || sep == s.length() - 1) return Optional.empty();
        var fp = s.substring(0, sep);
        var label = s.substring(sep + 1);
        try {
            return Optional.of(new ZoneAddress(fp, label));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Convenience: canonical string for display. Equivalent to
     * {@link #toCanonical()}.
     */
    @Override
    public String toString() {
        return toCanonical();
    }
}
