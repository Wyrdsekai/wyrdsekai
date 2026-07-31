package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL-backed SoulStore using libSQL (SQLite) or PostgreSQL.
 * Soul manifests are stored as JSON blobs with DID + version as
 * composite key. Follows existing VitalityPersistence/LibraryStore patterns.
 *
 * <p> canonical: world.db:soul_manifests.
 * The {@code souls/companion-*.json} filesystem layout used by
 * {@link SoulAutoForge} is a legacy bootstrap shadow — F7b Phase 3
 * deprecates filesystem manifests entirely. Until then: read through
 * this store, never through a filesystem JSON.</p>
 *
 * <p>F7b Phase 2 + 3a (SHIPPED 2026-04-27): the manifest blob's four
 * sub-record fields ({@code voiceProfile}, {@code soulFragments},
 * {@code bonds}, {@code worldKnowledge}) are mirrored into canonical
 * tables on every {@link #store}. Reads via {@link #latest} /
 * {@link #load} now <i>hydrate</i> these fields from canonical tables
 * when present, with blob fall-through when the canonical table is
 * empty. Canonical wins on conflict because dual-writes go to the
 * table FIRST. New code must not introduce additional embedded
 * sub-records — give them their own table and a write-hook here.</p>
 */
public final class SqlSoulStore implements SoulStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqlSoulStore.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private final ObjectMapper mapper;
    /**
     * F7b Phase 2.2: optional canonical fragment store. When present,
     * every {@link #store(SoulManifest)} call dual-writes the embedded
     * fragments into the {@code soul_fragments} table FIRST, then writes
     * the manifest blob (so a crash mid-write leaves the table
     * authoritative). Null in legacy paths or when no jdbcUrl wiring is
     * available — falls back to manifest-only behaviour.
     */
    private final SoulFragmentStore fragmentStore;
    /**
     * F7b Phase 2.3: optional canonical bond store. {@code BondStore} is
     * already the source of truth for bonds (writes go through
     * {@code BondRitual} → {@code BondStore.save()}); the
     * {@code SoulManifest.bonds} field is a derived view assembled at
     * Forge time. Hook here exists for the cross-zone self-heal case:
     * when a manifest arrives in a new zone via replication and carries
     * a non-empty bonds list that the local table doesn't know about
     * yet, we reconcile on the manifest write so bond-aware features
     * (Hearth visit log, Shelf furnishing, knock cascades) light up
     * immediately. Idempotent (BondStore.save is upsert).
     */
    private final BondStore bondStore;
    /**
     * F7b Phase 2.4: optional canonical world-knowledge store. When
     * present, every {@link #store(SoulManifest)} call atomically
     * replaces the DID's {@code world_knowledge} rows from
     * {@code manifest.worldKnowledge()} BEFORE writing the manifest
     * blob. Same shape as Phase 2.2 fragment dual-write.
     */
    private final WorldKnowledgeStore worldKnowledgeStore;
    /**
     * F7b Phase 3a: optional canonical voice profile store. When present,
     * {@link #store(SoulManifest)} mirrors {@code manifest.voiceProfile()}
     * into the canonical table on every persist (idempotent upsert) — this
     * closes the cross-zone arrival gap where a foreign manifest's voice
     * profile sat in the blob but wasn't yet in the local table. Reads
     * from {@link #latest} / {@link #load} hydrate the manifest's
     * {@code voiceProfile} field from the canonical table.
     *
     * <p>Note: {@code VoiceProfileService} is the single mutation gate
     * for live edits (Forge cycles, REST, Study). This hook is the
     * catch-all for arrivals that bypass the service (cross-zone
     * replication, transit relocation, file imports).
     */
    private final VoiceProfileStore voiceProfileStore;

    public SqlSoulStore(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl), null, null, null, null);
    }

    public SqlSoulStore(String jdbcUrl, SqlDialect dialect) {
        this(jdbcUrl, dialect, null, null, null, null);
    }

    /** F7b Phase 2.2: canonical-fragment-store-aware constructor. */
    public SqlSoulStore(String jdbcUrl, SqlDialect dialect, SoulFragmentStore fragmentStore) {
        this(jdbcUrl, dialect, fragmentStore, null, null, null);
    }

    /** F7b Phase 2.3: canonical-fragment + bond store-aware constructor. */
    public SqlSoulStore(String jdbcUrl, SqlDialect dialect,
                         SoulFragmentStore fragmentStore, BondStore bondStore) {
        this(jdbcUrl, dialect, fragmentStore, bondStore, null, null);
    }

    /** F7b Phase 2.4: 5-arg canonical-store-aware constructor (no voice profile yet). */
    public SqlSoulStore(String jdbcUrl, SqlDialect dialect,
                         SoulFragmentStore fragmentStore, BondStore bondStore,
                         WorldKnowledgeStore worldKnowledgeStore) {
        this(jdbcUrl, dialect, fragmentStore, bondStore, worldKnowledgeStore, null);
    }

    /** F7b Phase 3a: full canonical-store-aware constructor — all four sub-records. */
    public SqlSoulStore(String jdbcUrl, SqlDialect dialect,
                         SoulFragmentStore fragmentStore, BondStore bondStore,
                         WorldKnowledgeStore worldKnowledgeStore,
                         VoiceProfileStore voiceProfileStore) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
        this.fragmentStore = fragmentStore;
        this.bondStore = bondStore;
        this.worldKnowledgeStore = worldKnowledgeStore;
        this.voiceProfileStore = voiceProfileStore;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        initSchema();
    }

    private void initSchema() {
        try (var conn = connection();
             var stmt = conn.createStatement()) {
            if (dialect instanceof SqlDialect.SQLite) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA busy_timeout=5000");
            }
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS soul_manifests (
                    did TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    forged_at TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    manifest_json TEXT NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0,
                    archive_reason TEXT,
                    PRIMARY KEY (did, version)
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_soul_manifests_did
                ON soul_manifests (did, version DESC)
                """);
            log.debug("Soul store schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize soul store schema", e);
        }
    }

    @Override
    public void saveBond(Bond bond) {
        if (bondStore == null || bond == null || bond.bondId() == null) return;
        bondStore.save(bond);
    }

    public void store(SoulManifest manifest) {
        // F7b Phase 2.2: dual-write fragments to the canonical table FIRST.
        // If the manifest blob write below crashes, the canonical table
        // already holds the truth — readers (post-Phase-3) will be correct.
        if (fragmentStore != null && manifest.did() != null) {
            fragmentStore.replaceAll(manifest.did(), manifest.soulFragments());
        }
        // F7b Phase 2.3: reconcile manifest's embedded bonds list into
        // the canonical bonds table. Idempotent upsert by bond_id; the
        // common case (BondRitual already wrote them) is a no-op. Lights
        // up the cross-zone arrival path: a foreign manifest arrives via
        // replication carrying its bond list, and we populate the local
        // table without waiting for the next Forge cycle.
        if (bondStore != null && manifest.bonds() != null) {
            for (var bond : manifest.bonds()) {
                if (bond == null || bond.bondId() == null) continue;
                bondStore.save(bond);
            }
        }
        // F7b Phase 2.4: dual-write world-knowledge map to canonical table
        // FIRST. Same atomic-replace pattern as fragments (manifest carries
        // the full map per cycle, replace semantics fit the Forge rhythm).
        if (worldKnowledgeStore != null && manifest.did() != null) {
            worldKnowledgeStore.replaceAll(manifest.did(), manifest.worldKnowledge());
        }
        // F7b Phase 3a: dual-write voice profile too. VoiceProfileService
        // is the single live-edit gate, but cross-zone arrivals + transit
        // relocations + file imports bypass it. This catch-all closes
        // that gap so all four sub-records have a uniform write path.
        // Idempotent upsert — when the live path already wrote it, this
        // is a no-op (same content).
        if (voiceProfileStore != null && manifest.did() != null
                && manifest.voiceProfile() != null) {
            voiceProfileStore.save(manifest.did(), manifest.voiceProfile());
        }
        var storageBlob = storageView(manifest);
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "INSERT INTO soul_manifests (did, version, forged_at, content_hash, manifest_json, archived)"
                     + " VALUES (?, ?, ?, ?, ?, 0)")) {
            stmt.setString(1, manifest.did());
            stmt.setInt(2, manifest.manifestVersion());
            stmt.setString(3, manifest.forgedAt() != null ? manifest.forgedAt().toString() : "");
            stmt.setString(4, manifest.contentHash());
            stmt.setString(5, mapper.writeValueAsString(storageBlob));
            stmt.executeUpdate();
            log.debug("Soul manifest stored: {} v{}", manifest.did(), manifest.manifestVersion());
        } catch (Exception e) {
            log.error("Failed to store soul manifest {}: {}", manifest.did(), e.getMessage());
            throw new RuntimeException("Soul store failed", e);
        }
    }

    /**
     * F7b Phase 3b: build the storage-side view of a manifest before
     * serializing it to the {@code manifest_json} column. Any sub-record
     * field whose canonical store is wired gets nulled — the data lives
     * only in the canonical table from this point on, and
     * {@link #hydrateFromCanonical} fills it back in on read.
     *
     * <p>Legacy callers (1-/2-arg constructor → no canonical stores
     * wired) get the original manifest back unchanged, so blob-only
     * behavior is preserved for tests and admin tools that haven't
     * adopted the new constructors.
     *
     * <p>Cross-zone replication and backup are <i>not</i> affected:
     * those code paths serialize the manifest directly via their own
     * {@link ObjectMapper} (e.g. {@code SoulRoutes.toJson}), bypassing
     * this storage-view transform. They continue to ship full
     * sub-records over the wire, and the destination/restore path
     * runs them through {@link #store} which dual-writes to canonical
     * tables anyway. End result: round-trip preserves every field.
     */
    private SoulManifest storageView(SoulManifest manifest) {
        if (manifest == null) return null;
        var view = manifest;
        if (fragmentStore != null && view.soulFragments() != null) {
            view = view.withFragments(null);
        }
        if (worldKnowledgeStore != null && view.worldKnowledge() != null) {
            view = view.withWorldKnowledge(null);
        }
        if (bondStore != null && view.bonds() != null) {
            view = view.withBonds(null);
        }
        if (voiceProfileStore != null && view.voiceProfile() != null) {
            view = view.withVoiceProfile(null);
        }
        return view;
    }

    @Override
    public Optional<SoulManifest> load(String did, int version) {
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "SELECT manifest_json FROM soul_manifests WHERE did = ? AND version = ?")) {
            stmt.setString(1, did);
            stmt.setInt(2, version);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                var fromBlob = mapper.readValue(rs.getString("manifest_json"), SoulManifest.class);
                return Optional.of(hydrateFromCanonical(fromBlob));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to load soul manifest {} v{}: {}", did, version, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<SoulManifest> latest(String did) {
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "SELECT manifest_json FROM soul_manifests"
                     + " WHERE did = ? AND archived = 0"
                     + " ORDER BY version DESC LIMIT 1")) {
            stmt.setString(1, did);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                var fromBlob = mapper.readValue(rs.getString("manifest_json"), SoulManifest.class);
                return Optional.of(hydrateFromCanonical(fromBlob));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to load latest soul manifest {}: {}", did, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * F7b Phase 3a: replace the manifest blob's sub-record fields with
     * data from canonical tables when those tables are wired and have
     * non-empty data for this DID. The blob remains a valid carrier
     * (so manifests round-trip across zones unchanged) but reads see
     * the canonical truth.
     *
     * <p>Fall-through semantics: if a canonical store is null (legacy
     * 1-/2-arg constructor) or its data is empty for this DID, the blob
     * value passes through unchanged. This keeps existing test fixtures
     * working without rewiring.
     *
     * <p>Why canonical wins on conflict: dual-writes go to the table
     * FIRST. If a write crashes between the table write and the blob
     * write, the table holds truth and the blob is stale. Canonical-first
     * read is therefore the safe default.
     */
    private SoulManifest hydrateFromCanonical(SoulManifest fromBlob) {
        if (fromBlob == null || fromBlob.did() == null) return fromBlob;
        var did = fromBlob.did();
        var result = fromBlob;
        try {
            // F7b Phase 3b invariant: when a canonical store is wired,
            // its data is authoritative even when empty. Always assign,
            // so callers don't see null where Phase 2 returned an empty
            // collection or the blob's old populated value. (Phase 3b
            // strips the blob's sub-records on write — without this
            // always-assign rule, fresh companions whose canonical
            // tables are empty would surface null and NPE downstream.)
            if (fragmentStore != null) {
                result = result.withFragments(fragmentStore.loadAll(did));
            }
            if (worldKnowledgeStore != null) {
                result = result.withWorldKnowledge(worldKnowledgeStore.loadAll(did));
            }
            if (bondStore != null) {
                result = result.withBonds(bondStore.bondsForAgent(did));
            }
            if (voiceProfileStore != null) {
                var canonicalVoice = voiceProfileStore.load(did);
                if (canonicalVoice.isPresent()) {
                    result = result.withVoiceProfile(canonicalVoice.get());
                }
                // Voice profile is intentionally nullable — companions
                // without an explicit profile keep the field null and
                // PromptAssembler skips the block. Don't overwrite a
                // non-null blob value with a null absence.
            }
        } catch (Exception e) {
            log.warn("Phase 3a hydrate failed for {} (falling back to blob): {}",
                did, e.getMessage());
        }
        return result;
    }

    @Override
    public List<SoulManifest> history(String did) {
        List<SoulManifest> manifests = new ArrayList<>();
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "SELECT manifest_json FROM soul_manifests"
                     + " WHERE did = ? ORDER BY version DESC")) {
            stmt.setString(1, did);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                var fromBlob = mapper.readValue(rs.getString("manifest_json"), SoulManifest.class);
                manifests.add(hydrateFromCanonical(fromBlob));
            }
        } catch (Exception e) {
            log.error("Failed to load soul history {}: {}", did, e.getMessage());
        }
        return manifests;
    }

    @Override
    public void archive(String did, String reason) {
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "UPDATE soul_manifests SET archived = 1, archive_reason = ? WHERE did = ?")) {
            stmt.setString(1, reason);
            stmt.setString(2, did);
            int updated = stmt.executeUpdate();
            log.debug("Archived {} versions of soul {}: {}", updated, did, reason);
        } catch (SQLException e) {
            log.error("Failed to archive soul {}: {}", did, e.getMessage());
            throw new RuntimeException("Soul archive failed", e);
        }
    }

    @Override
    public boolean exists(String did) {
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM soul_manifests WHERE did = ?")) {
            stmt.setString(1, did);
            var rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            log.error("Soul exists check failed for {}: {}", did, e.getMessage());
            return false;
        }
    }

    @Override
    public List<SoulManifest> listLatest() {
        List<SoulManifest> manifests = new ArrayList<>();
        try (var conn = connection();
             var stmt = conn.prepareStatement(
                 "SELECT m.manifest_json FROM soul_manifests m"
                     + " INNER JOIN ("
                     + "   SELECT did, MAX(version) AS max_version"
                     + "   FROM soul_manifests WHERE archived = 0"
                     + "   GROUP BY did"
                     + " ) latest ON m.did = latest.did AND m.version = latest.max_version"
                     + " ORDER BY m.did")) {
            var rs = stmt.executeQuery();
            while (rs.next()) {
                manifests.add(mapper.readValue(rs.getString("manifest_json"), SoulManifest.class));
            }
        } catch (Exception e) {
            log.error("Failed to list latest soul manifests: {}", e.getMessage());
        }
        return manifests;
    }

    @Override
    public int count() {
        try (var conn = connection();
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM soul_manifests");
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Soul count failed", e);
        }
    }

    @Override
    public void close() {
        // Connection pool would be closed here in production;
        // DriverManager connections are per-call so nothing to close.
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
