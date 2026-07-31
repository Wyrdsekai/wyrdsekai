package org.wyrdsekai.core.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Safe-mediated credential lookup for
 * external adapters.
 *
 * <p>Adapters declare a {@code credential_slot} (e.g. {@code github.token});
 * at call time the resolver reads from The Safe via {@link #safeReader}.
 * On miss, a Mailbox notification is fired to the steward asking them to
 * populate the slot, and the adapter returns
 * {@code {error: {code: "credential_missing", slot: ...}}}.</p>
 *
 * <p>The resolver itself is tiny — it's mostly a wiring point so adapters
 * never see the secret directly. The actual Safe lookup is injected via
 * {@link #safeReader} so tests + non-Safe callers can substitute fakes.</p>
 */
public final class CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(CredentialResolver.class);

    private static final CredentialResolver INSTANCE = new CredentialResolver();

    private volatile Function<String, Optional<String>> safeReader = slot -> Optional.empty();
    private volatile MailboxNotifier mailboxNotifier = (steward, slot) -> {};

    private final ConcurrentHashMap<String, Long> recentMisses = new ConcurrentHashMap<>();

    private CredentialResolver() {}

    public static CredentialResolver get() { return INSTANCE; }

    /** Wired by Main / CoreServices once The Safe is online. */
    public void setSafeReader(Function<String, Optional<String>> reader) {
        this.safeReader = reader == null ? slot -> Optional.empty() : reader;
    }

    /**
     * W13 — the canonical production resolution chain:
     * <ol>
     *   <li>The Safe slot (steward-populated, persisted, encrypted at rest)</li>
     *   <li>{@code WYRDSEKAI_CRED_*} environment variable (compat path;
     *       slot {@code github.token} → {@code WYRDSEKAI_CRED_GITHUB_TOKEN})</li>
     *   <li>{@code wyrdsekai.cred.<slot>} system property</li>
     * </ol>
     * Main wires {@code setSafeReader(chainedReader(TheSafe.local()::readSlot,
     * System::getenv, System::getProperty))}; the lookup functions are
     * parameters so tests can verify precedence without touching process env.
     * A throwing safe reader degrades to the env/property fallbacks.
     */
    public static Function<String, Optional<String>> chainedReader(
            Function<String, Optional<String>> safeSlotReader,
            Function<String, String> env,
            Function<String, String> systemProperty) {
        return slot -> {
            if (slot == null || slot.isBlank()) return Optional.empty();
            try {
                var fromSafe = safeSlotReader.apply(slot);
                if (fromSafe != null && fromSafe.isPresent()) return fromSafe;
            } catch (Exception e) {
                log.warn("Safe read failed for slot '{}' — falling back to env/property: {}",
                    slot, e.getMessage());
            }
            var envKey = "WYRDSEKAI_CRED_"
                + slot.toUpperCase().replace('-', '_').replace('.', '_');
            var envValue = env.apply(envKey);
            if (envValue != null && !envValue.isBlank()) {
                return Optional.of(envValue);
            }
            var prop = systemProperty.apply("wyrdsekai.cred." + slot);
            return prop != null && !prop.isBlank()
                ? Optional.of(prop) : Optional.empty();
        };
    }

    /** Wired by Main once NotificationService is available. */
    public void setMailboxNotifier(MailboxNotifier notifier) {
        this.mailboxNotifier = notifier == null ? (s, sl) -> {} : notifier;
    }

    /**
     * Resolve a credential slot. On miss, dispatches a one-shot Mailbox
     * notification (rate-limited to one per slot per hour) and returns empty.
     */
    public Optional<String> resolve(String slot) {
        return resolve(slot, null);
    }

    public Optional<String> resolve(String slot, String stewardDid) {
        if (slot == null || slot.isBlank()) return Optional.empty();
        var value = safeReader.apply(slot);
        if (value.isPresent()) {
            return value;
        }
        notifyMissOnce(slot, stewardDid);
        return Optional.empty();
    }

    private void notifyMissOnce(String slot, String stewardDid) {
        var now = System.currentTimeMillis();
        var key = (stewardDid == null ? "" : stewardDid) + ":" + slot;
        var prev = recentMisses.get(key);
        if (prev != null && now - prev < 3600_000L) return;
        recentMisses.put(key, now);
        log.info("credential miss: slot={} steward={}", slot, stewardDid);
        try {
            mailboxNotifier.notifyMissing(stewardDid, slot);
        } catch (Exception e) {
            log.debug("mailbox notify failed: {}", e.getMessage());
        }
    }

    /** Functional interface — wired to NotificationService. */
    public interface MailboxNotifier {
        void notifyMissing(String stewardDid, String slot);
    }

    /** Test-only escape. */
    public void resetForTests() {
        safeReader = slot -> Optional.empty();
        mailboxNotifier = (s, sl) -> {};
        recentMisses.clear();
    }
}
