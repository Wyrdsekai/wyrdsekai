package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns person identity ON at server startup, and migrates whatever is already here.
 *
 * <p><b>Called unconditionally from {@code Main}.</b> An earlier version left
 * provisioning behind an {@code init()} nobody called, so every guard passed
 * through, no person was ever minted, and the whole subsystem was dead code that
 * looked finished. There is one household running; building a switch for a fleet
 * that does not exist bought nothing and hid the fact that none of it ran.</p>
 *
 * <p>The household secret is derived from the zone master, the same source
 * {@code PrivateJournalCipher} uses. If the zone master is not installed yet
 * (first boot before zone bootstrap) this reports that and does nothing —
 * startup continues, and the next boot picks it up.</p>
 */
public final class PersonIdentityBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PersonIdentityBootstrap.class);

    /** HKDF purpose for the key that encrypts person private keys at rest. */
    private static final String PURPOSE = "person-identity:v1";

    private PersonIdentityBootstrap() {}

    /** What happened, for logging and tests. */
    public record Result(boolean enabled, int peopleProvisioned, int migrated, String note) {}

    /**
     * Enable person identity and bring existing accounts across.
     *
     * @param jdbcUrl the world database
     */
    public static Result run(String jdbcUrl) {
        String zoneId;
        try {
            zoneId = WyrdConfig.get().zoneId();
        } catch (RuntimeException e) {
            return new Result(false, 0, 0, "config unavailable: " + e.getMessage());
        }
        var service = ZoneSecrets.service();
        if (zoneId == null || zoneId.isBlank()) {
            log.warn("[PersonIdentity] OFF — no zoneId configured. Person identity cannot be "
                + "enabled without a zone; check `wyrd config get zone.id`.");
            return new Result(false, 0, 0, "no zoneId");
        }
        if (!service.has(zoneId)) {
            // Distinguish the two reasons this can happen, because they need
            // different responses and an earlier version of this code made them
            // indistinguishable: it was CALLED BEFORE ZoneSecrets.bootstrapLocalZone
            // and so could only ever report "not ready", which read as a config
            // problem rather than the ordering bug it was.
            log.error("[PersonIdentity] OFF — zone master not present for zone '{}' at the time "
                + "this ran. Either the zone-secret bootstrap failed earlier in startup (look for "
                + "'Zone-secret bootstrap' warnings above), or this is being called too early in "
                + "Main — it MUST come after ZoneSecrets.bootstrapLocalZone. Person identity is "
                + "disabled for this boot; no data was changed.", zoneId);
            return new Result(false, 0, 0, "zone master unavailable");
        }

        PersonIdentityProvisioner.init(jdbcUrl, () -> {
            try {
                return service.derive(zoneId, PURPOSE, 32);
            } catch (RuntimeException e) {
                log.warn("[PersonIdentity] could not derive household secret: {}", e.getMessage());
                return null;
            }
        });

        var accounts = existingAccounts(jdbcUrl);
        int provisioned = 0;
        int migrated = 0;
        for (var a : accounts) {
            var before = PersonIdentityProvisioner.resolver()
                .flatMap(r -> r.resolve(a.id())).isPresent();
            var result = PersonIdentityMigration.run(jdbcUrl, a.id(), a.displayName(),
                () -> service.derive(zoneId, PURPOSE, 32));
            if (result.ran()) migrated++;
            if (!before && PersonIdentityProvisioner.resolver()
                    .flatMap(r -> r.resolve(a.id())).isPresent()) {
                provisioned++;
            }
        }

        // Close the record on any companion fold that has no attestation yet.
        // The rebind itself can run offline (repair tool, database copy) where the
        // household secret does not exist; signing needs the zone master, which is
        // correctly installed only here. Idempotent — see AgentRebindAttestor.
        try {
            AgentRebindAttestor.reconcile(jdbcUrl,
                () -> service.derive(zoneId, PURPOSE, 32), null);
        } catch (RuntimeException e) {
            // Never let this block startup: an unattested fold is a gap in the
            // record, not a reason for the household not to come up.
            log.warn("[PersonIdentity] rebind attestation reconcile failed: {}", e.toString());
        }

        log.info("[PersonIdentity] ENABLED — {} account(s) known, {} person(s) provisioned, "
            + "{} migrated", accounts.size(), provisioned, migrated);
        return new Result(true, provisioned, migrated, null);
    }

    /**
     * Migrate STUDY CONTENT onto the person identity.
     *
     * <p>Separate entry point because the Lucene store does not exist yet when
     * {@link #run} is called at startup — and because the Study owner is a
     * different string again. World-database rows referenced the account UUID;
     * Study content was written by {@code bin/wyrd library ingest}, whose
     * {@code --user} defaulted to {@code $(whoami)} — so 13.7M chunks are owned
     * by a <b>username</b>, not the UUID. Both must be rewritten or the
     * companion's corridor finds nothing.</p>
     *
     * <p>Re-ingest is not the mechanism: a migrating household will not still
     * have the source files. The owner is rewritten in the index, resumably.</p>
     *
     * @param store the Study index
     * @return number of documents rewritten
     */
    /** Guards against two migrations running at once across restarts-in-place. */
    private static final AtomicBoolean STUDY_MIGRATION_RUNNING =
        new AtomicBoolean(false);

    /**
     * Start the Study owner migration on a BACKGROUND thread and return immediately.
     *
     * <p><b>This must not block startup.</b> The first live run of this migration
     * rewrote 13.7M documents at ~3,100/sec — 69 minutes — on the {@code main}
     * thread, before Javalin started, so the household was entirely offline for
     * over an hour. The world-database half (27,769 rows, sub-second) can stay
     * synchronous; an index pass measured in tens of minutes has no business
     * holding up a boot.</p>
     *
     * <p>Safe to run concurrently with normal traffic: each batch is committed
     * individually and already-rewritten documents stop matching, so a read during
     * the pass sees a mix of old and new owner rather than anything inconsistent.
     * A shutdown mid-pass simply resumes next boot.</p>
     */
    public static void migrateStudyOwnersAsync(WyrdLuceneStore store) {
        if (store == null || !PersonIdentityProvisioner.isEnabled()) return;
        if (!STUDY_MIGRATION_RUNNING.compareAndSet(false, true)) {
            log.debug("[PersonIdentity] Study migration already running — not starting a second");
            return;
        }
        var t = new Thread(() -> {
            try {
                var n = migrateStudyOwners(store);
                if (n > 0) {
                    log.info("[PersonIdentity] Study migration finished in the background "
                        + "— {} documents now owned by a person", n);
                }
            } catch (RuntimeException e) {
                log.warn("[PersonIdentity] Study migration stopped: {} — it is resumable, "
                    + "the next start continues from where it left off", e.toString());
            } finally {
                STUDY_MIGRATION_RUNNING.set(false);
            }
        }, "person-identity-study-migration");
        t.setDaemon(true);          // never delay shutdown; the pass resumes next boot
        t.setPriority(Thread.MIN_PRIORITY);   // yield to anything the household is doing
        t.start();
        log.info("[PersonIdentity] Study owner migration started in the background — "
            + "the server is available while it runs");
    }

    public static long migrateStudyOwners(WyrdLuceneStore store) {
        if (store == null || !PersonIdentityProvisioner.isEnabled()) return 0;
        var resolver = PersonIdentityProvisioner.resolver().orElse(null);
        if (resolver == null) return 0;

        long total = 0;
        String jdbcUrl = System.getProperty("wyrdsekai.jdbc.url");
        if (jdbcUrl == null || jdbcUrl.isBlank()) return 0;

        for (var a : existingAccountsWithNames(jdbcUrl)) {
            var personDid = resolver.resolve(a.id()).orElse(null);
            if (personDid == null) continue;

            // The legacy account id, and every name that account is known by —
            // Study content may sit under any of them.
            for (var legacy : new String[]{a.id(), a.username(), a.displayName()}) {
                if (legacy == null || legacy.isBlank() || legacy.equals(personDid)) continue;
                var n = store.rewriteStudyOwner(legacy, personDid, 500,
                    done -> log.info("[PersonIdentity] Study rewrite {} -> {}: {} documents",
                        legacy, personDid, done));
                if (n > 0) {
                    log.info("[PersonIdentity] Study content moved from '{}' to {} ({} documents)",
                        legacy, personDid, n);
                    total += n;
                }
            }
        }
        if (total > 0) {
            log.info("[PersonIdentity] Study migration complete — {} documents now owned by "
                + "a person rather than a placeholder", total);
        }
        return total;
    }

    private record NamedAccount(String id, String username, String displayName) {}

    private static List<NamedAccount> existingAccountsWithNames(String jdbcUrl) {
        var out = new ArrayList<NamedAccount>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT id, username, display_name FROM users");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new NamedAccount(rs.getString("id"),
                    rs.getString("username"), rs.getString("display_name")));
            }
        } catch (Exception e) {
            log.debug("[PersonIdentity] could not read users for Study migration: {}",
                e.getMessage());
        }
        return out;
    }

    private record Account(String id, String displayName) {}

    private static List<Account> existingAccounts(String jdbcUrl) {
        var out = new ArrayList<Account>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT id, COALESCE(display_name, username) AS name FROM users");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Account(rs.getString("id"), rs.getString("name")));
        } catch (Exception e) {
            log.debug("[PersonIdentity] no users table to migrate: {}", e.getMessage());
        }
        return out;
    }
}
