package org.wyrdsekai.core.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * steward-confirmation token gate for
 * Tier 7 financial mutations.
 *
 * <p>This is a Phase S <em>stub</em> — the full stewardship UX (chapel
 * ritual, push-confirm to phone, receipt write to The Safe) lands in a
 * later phase. For now the service exposes a minimal contract:</p>
 *
 * <ul>
 *   <li>{@link #grantToken(String, String)} — record that the steward has
 *       authorised an operation under the given {@code purpose} (e.g.
 *       {@code "financial.write"}). One-shot tokens; consumed on require.</li>
 *   <li>{@link #requireStewardToken(String)} — adapters call this from
 *       inside their write paths. Returns silently if a matching token is
 *       present; otherwise throws {@link StewardTokenMissingError}.</li>
 *   <li>{@link #setMode(Mode)} — the test harness can flip to
 *       {@link Mode#ALLOW_ALL} to bypass the gate, or {@link Mode#DENY_ALL}
 *       to harden tests around the deny path.</li>
 * </ul>
 *
 * <p>Production wiring will replace the {@code ConcurrentHashMap} with the
 * persistent receipt log and bind {@code grantToken} to the chapel-ritual
 * outcome. Until then, all financial writes default-deny — consistent with
 * §4.32's "highest-risk category" framing.</p>
 */
public final class SafeService {

    private static final Logger log = LoggerFactory.getLogger(SafeService.class);

    public enum Mode {
        /** Default: every {@code requireStewardToken} call must find a granted token. */
        REQUIRE_TOKEN,
        /** Test convenience: bypass the gate entirely. */
        ALLOW_ALL,
        /** Test convenience: deny every call regardless of grants. */
        DENY_ALL
    }

    private static final SafeService INSTANCE = new SafeService();

    private volatile Mode mode = Mode.REQUIRE_TOKEN;
    private final ConcurrentHashMap<String, Long> activeTokens = new ConcurrentHashMap<>();

    private SafeService() {}

    public static SafeService get() {
        return INSTANCE;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode m) {
        this.mode = m == null ? Mode.REQUIRE_TOKEN : m;
    }

    /**
     * Stub — record a one-shot steward grant for {@code purpose}. The grant
     * lives until consumed by a matching {@link #requireStewardToken(String)}
     * call. {@code tokenId} is stored for audit; the stub doesn't enforce
     * uniqueness or TTL.
     */
    public void grantToken(String purpose, String tokenId) {
        if (purpose == null || purpose.isBlank()) return;
        activeTokens.put(purpose, System.currentTimeMillis());
        log.info("steward token granted: purpose={} tokenId={}", purpose, tokenId);
    }

    /**
     * Adapter-side gate. Throws when no token is present. Consumes the
     * token on success — callers must {@link #grantToken} per call.
     *
     * @throws StewardTokenMissingError when no grant is active in REQUIRE_TOKEN mode
     */
    public void requireStewardToken(String purpose) {
        if (mode == Mode.ALLOW_ALL) return;
        if (mode == Mode.DENY_ALL) {
            throw new StewardTokenMissingError(purpose);
        }
        var ts = activeTokens.remove(purpose);
        if (ts == null) {
            throw new StewardTokenMissingError(purpose);
        }
    }

    /** Non-throwing predicate; useful for adapters that want to short-circuit. */
    public boolean hasToken(String purpose) {
        if (mode == Mode.ALLOW_ALL) return true;
        if (mode == Mode.DENY_ALL) return false;
        return activeTokens.containsKey(purpose);
    }

    /** Test-only helper: drop all granted tokens and reset to REQUIRE_TOKEN. */
    public void resetForTests() {
        mode = Mode.REQUIRE_TOKEN;
        activeTokens.clear();
    }

    /** Thrown when an adapter mutation is attempted without a steward grant. */
    public static final class StewardTokenMissingError extends RuntimeException {
        private final String purpose;

        public StewardTokenMissingError(String purpose) {
            super("steward token missing for purpose: " + purpose);
            this.purpose = purpose;
        }

        public String purpose() {
            return purpose;
        }
    }
}
