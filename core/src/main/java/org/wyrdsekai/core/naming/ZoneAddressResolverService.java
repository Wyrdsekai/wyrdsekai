package org.wyrdsekai.core.naming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton holder for the household's {@link ZoneAddressResolver}.
 *
 * <p>The resolver combines three stateful inputs: the caller's
 * {@link HouseholdIdentity} (derived from the node keypair), their
 * {@link ContactsBook} (loaded from disk), and their {@link LocalZoneRegistry}
 * (loaded from disk). Wiring those three together in one place lets downstream
 * call sites (docks.js, CLI, wire layer) resolve zone addresses with a single
 * lookup through the singleton.</p>
 *
 * <p>Init/get pattern mirrors {@link org.wyrdsekai.core.agent.CrossZoneTellService}
 * et al. The {@link #get()} accessor returns null with a rate-limited WARN if
 * called before {@link #init}, matching the CoreServices anti-pattern fix from
 * the 2026-04-19 bootstrap audit.</p>
 *
 * <h2>Migration: legacy zoneId</h2>
 *
 * <p>Existing deployments boot with {@code WYRDSEKAI_ZONE_ID=home} (or similar
 * bare string). At init time we auto-register that string in
 * {@link LocalZoneRegistry} <em>if</em> it's a valid, non-reserved label. If
 * it's reserved ({@code home}), we log a WARN and skip — the operator will
 * need to migrate via {@code wyrd zones rename home <new-label>} per
 * This keeps Phase-1 deployments functional without
 * silently blessing the reserved keyword.</p>
 */
public final class ZoneAddressResolverService {

    private static final Logger log = LoggerFactory.getLogger(ZoneAddressResolverService.class);
    private static final AtomicReference<ZoneAddressResolverService> INSTANCE = new AtomicReference<>();

    // Rate-limited null-get WARN — one log per minute per process on the first
    // null access after a gap.
    private static volatile Instant lastNullWarn = Instant.MIN;
    private static final Duration NULL_WARN_WINDOW = Duration.ofMinutes(1);

    private final HouseholdIdentity household;
    // Hot-reloaded (2026-07-18): `wyrd contacts` / `wyrd zones` run as a SEPARATE
    // process that rewrites these files on disk. Boot-once hydration meant the
    // live server never saw CLI-added contacts (travel <alias> failed till
    // restart) AND — worse — the federation write path re-saved its STALE book,
    // clobbering the CLI's additions. We re-stat both files on each accessor and
    // rebuild when either mtime moves; since every mutator fetches contacts()
    // fresh, the reload also closes the clobber window.
    private volatile ContactsBook contacts;
    private volatile LocalZoneRegistry myZones;
    private volatile ZoneAddressResolver resolver;
    private final Path contactsFile;
    private final Path zonesFile;
    private volatile long contactsMtime;
    private volatile long zonesMtime;
    private final String legacyZoneId;  // preserved for migration diagnostics; nullable
    private ZoneAddressResolver.DirectoryLookup directoryLookup;

    private static long mtime(Path f) {
        try {
            return f != null && Files.exists(f)
                ? Files.getLastModifiedTime(f).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Reload contacts/zones from disk if either file changed out-of-band (CLI),
     *  and rebuild the resolver over the fresh books. Cheap double-stat on the
     *  hot path; a real reload only on an actual edit. */
    private synchronized void reloadIfChanged() {
        long cm = mtime(contactsFile), zm = mtime(zonesFile);
        if (cm == contactsMtime && zm == zonesMtime) return;
        try {
            if (cm != contactsMtime) { contacts = ContactsBook.load(contactsFile); contactsMtime = cm; }
            if (zm != zonesMtime) { myZones = LocalZoneRegistry.load(zonesFile); zonesMtime = zm; }
            resolver = new ZoneAddressResolver(household, contacts, myZones, directoryLookup);
            log.info("ZoneAddressResolverService: reloaded contacts/zones (out-of-band change)");
        } catch (IOException e) {
            log.warn("ZoneAddressResolverService: reload failed, keeping current: {}", e.getMessage());
            contactsMtime = cm; zonesMtime = zm;   // don't spin on a broken file
        }
    }

    private ZoneAddressResolverService(
            HouseholdIdentity household,
            ContactsBook contacts,
            LocalZoneRegistry myZones,
            Path contactsFile,
            Path zonesFile,
            String legacyZoneId) {
        this.household = household;
        this.contacts = contacts;
        this.myZones = myZones;
        this.contactsFile = contactsFile;
        this.zonesFile = zonesFile;
        this.contactsMtime = mtime(contactsFile);
        this.zonesMtime = mtime(zonesFile);
        this.legacyZoneId = legacyZoneId;
        // Directory fallback: the rendezvous pool is consulted only after
        // local contacts + zones miss. Lazy get() on every call so tests
        // that don't init ZoneDirectoryService stay on the null path.
        this.directoryLookup = label -> {
            var dir = ZoneDirectoryService.get();
            if (dir == null) return null;
            var matches = new ArrayList<ZoneManifestV1>();
            try {
                // recent(limit) returns all published zones (the rendezvous
                // pool caps at 100k; real meshes have <<100 zones). Label
                // match is O(n) but n is tiny.
                for (var m : dir.recent(500)) {
                    if (m.zoneLabel() != null && label.equalsIgnoreCase(m.zoneLabel())) {
                        matches.add(m);
                    }
                }
            } catch (Exception e) {
                return null; // best-effort — fall back to "unknown_label"
            }
            if (matches.isEmpty()) return null;
            if (matches.size() > 1) {
                var dids = matches.stream()
                    .map(ZoneManifestV1::did)
                    .reduce((a, b) -> a + ", " + b).orElse("");
                return new ZoneAddressResolver.Result.Err("ambiguous_label",
                    "Label '" + label + "' is advertised by " + matches.size()
                        + " zones in the directory (" + dids
                        + "). Disambiguate with: wyrd contacts add <alias> <did>, then travel <alias>:"
                        + label);
            }
            var only = matches.get(0);
            var did = only.did();
            if (did == null || !did.startsWith(HouseholdIdentity.DID_SCHEME)) return null;
            var fp = did.substring(HouseholdIdentity.DID_SCHEME.length());
            try {
                return new ZoneAddressResolver.Result.Ok(new ZoneAddress(fp, label));
            } catch (IllegalArgumentException e) {
                return null; // malformed DID in a manifest — ignore this match
            }
        };
        this.resolver = new ZoneAddressResolver(household, contacts, myZones, this.directoryLookup);
    }

    /**
     * Initialise the singleton. Subsequent calls are no-ops unless
     * {@link #resetForTests()} was called first.
     *
     * @param spkiBytes       Ed25519 public-key SPKI bytes (from
     *                        {@code NodeIdentity.publicKeyBytes()}).
     * @param dataDir         wyrdsekai data directory — the service looks for
     *                        {@code contacts} and {@code my-zones} files
     *                        inside, creating empty registries if absent.
     * @param legacyZoneId    the value of {@code WYRDSEKAI_ZONE_ID} (may be
     *                        null). Auto-registered in the local zone
     *                        registry if non-reserved; WARN-skipped if
     *                        reserved (deployment must migrate).
     */
    public static synchronized void init(byte[] spkiBytes, Path dataDir, String legacyZoneId) {
        if (INSTANCE.get() != null) return;
        try {
            var household = HouseholdIdentity.fromSpkiBytes(spkiBytes);
            var contactsFile = dataDir.resolve("contacts");
            var zonesFile = dataDir.resolve("my-zones");
            var contacts = ContactsBook.load(contactsFile);
            var myZones = LocalZoneRegistry.load(zonesFile);

            // Auto-register legacy zoneId for Phase-1 backwards compat. We
            // don't save() — don't mutate the operator's file without their
            // say-so. The in-memory registration is enough for the resolver
            // to return valid addresses for self-zone lookups today.
            if (legacyZoneId != null && !legacyZoneId.isBlank()) {
                if (ZoneLabels.isReserved(legacyZoneId)) {
                    log.warn("Zone ID '{}' is reserved and cannot resolve as a zone label. "
                        + "Migrate via `wyrd zones rename {} <new-label>`.",
                        legacyZoneId, legacyZoneId);
                } else if (ZoneLabels.isWellFormed(legacyZoneId)) {
                    if (!myZones.contains(legacyZoneId)) {
                        try {
                            myZones.add(legacyZoneId);
                            log.info("Auto-registered legacy zoneId '{}' in local zone registry (in-memory only). "
                                + "Run `wyrd zones create {}` to persist.", legacyZoneId, legacyZoneId);
                        } catch (IllegalArgumentException ignore) {
                            // race with another thread; just leave it
                        }
                    }
                } else {
                    log.warn("Zone ID '{}' is not a well-formed zone label (charset/length) — "
                        + "skipping auto-registration. Zone naming requires "
                        + "[a-z0-9] with internal hyphens, 1-32 chars.", legacyZoneId);
                }
            }

            INSTANCE.set(new ZoneAddressResolverService(
                household, contacts, myZones, contactsFile, zonesFile, legacyZoneId));
            log.info("ZoneAddressResolverService initialised: household={}, contacts={}, myZones={}, legacyZoneId={}",
                household.did(), contacts.size(), myZones.size(), legacyZoneId);
        } catch (IOException e) {
            throw new IllegalStateException(
                "ZoneAddressResolverService.init failed loading contacts/my-zones from " + dataDir, e);
        }
    }

    /**
     * @return the singleton instance, or {@code null} if {@link #init} has
     *     not been called. Null returns fire a rate-limited WARN so
     *     bootstrap bugs surface in logs instead of presenting as silent
     *     downstream failures.
     */
    public static ZoneAddressResolverService get() {
        var inst = INSTANCE.get();
        if (inst == null) warnUninitialised();
        return inst;
    }

    private static synchronized void warnUninitialised() {
        var now = Instant.now();
        if (now.isAfter(lastNullWarn.plus(NULL_WARN_WINDOW))) {
            log.warn("ZoneAddressResolverService.get() called before init — call sites will degrade. "
                + "Ensure CoreServices.init() has been called at startup.");
            lastNullWarn = now;
        }
    }

    /** Clear the singleton so tests can re-init per run. Never called in production. */
    public static synchronized void resetForTests() {
        INSTANCE.set(null);
        lastNullWarn = Instant.MIN;
    }

    /** @return the household identity this service is scoped to. */
    public HouseholdIdentity household() {
        return household;
    }

    /** @return the contacts book (mutable — callers may {@code add}/{@code remove}/{@code save}). */
    public ContactsBook contacts() {
        reloadIfChanged();
        return contacts;
    }

    /** @return the local zone registry (mutable). */
    public LocalZoneRegistry myZones() {
        reloadIfChanged();
        return myZones;
    }

    /** @return the resolver that maps user-typed strings to {@link ZoneAddress}. */
    public ZoneAddressResolver resolver() {
        reloadIfChanged();
        return resolver;
    }

    /** @return the legacy zoneId environment value, or {@code null} if unset. */
    public String legacyZoneId() {
        return legacyZoneId;
    }
}
