package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC store for voice profiles
 * factored out of {@code SoulManifest.voiceProfile}.
 *
 * <p> canonical: world.db:voice_profiles.
 * The {@code SoulManifest.voiceProfile} field becomes a serialization
 * view assembled at read time from this store. Writers must route
 * through {@link VoiceProfileService} which dual-writes to both this
 * table and the manifest during the transition; once Phase 3 lands the
 * manifest field becomes computed-at-serialize only.
 *
 * <p>Schema lives in {@code core/resources/schema/{sqlite,postgresql}-create-schema.sql}.
 *
 * <p>Schema (SQLite/PostgreSQL):
 * <pre>
 *   voice_profiles(
 *     did            TEXT PRIMARY KEY,
 *     clauses_json   TEXT,    -- LinkedHashMap&lt;String,String&gt;
 *     revision       INTEGER,
 *     frozen         INTEGER, -- 0 / 1
 *     history_json   TEXT,    -- List&lt;ProfileRevision&gt;
 *     updated_at     INTEGER  -- epoch seconds
 *   )
 * </pre>
 */
public final class VoiceProfileStore {

    private static final Logger log = LoggerFactory.getLogger(VoiceProfileStore.class);

    private final String jdbcUrl;
    private final ObjectMapper mapper;

    public VoiceProfileStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Upsert a profile keyed on DID. Updates {@code updated_at}. */
    public void save(String did, VoiceProfile profile) {
        if (did == null || did.isBlank()) {
            log.warn("VoiceProfileStore.save called with blank did — skipping");
            return;
        }
        if (profile == null) profile = VoiceProfile.empty();
        var sql = "INSERT INTO voice_profiles "
            + "(did, clauses_json, revision, frozen, history_json, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (did) DO UPDATE SET "
            + "  clauses_json = excluded.clauses_json, "
            + "  revision = excluded.revision, "
            + "  frozen = excluded.frozen, "
            + "  history_json = excluded.history_json, "
            + "  updated_at = excluded.updated_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            st.setString(2, mapper.writeValueAsString(profile.clauses()));
            st.setInt(3, profile.revision());
            st.setInt(4, profile.frozen() ? 1 : 0);
            st.setString(5, mapper.writeValueAsString(profile.history()));
            st.setLong(6, Instant.now().getEpochSecond());
            st.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to save voice_profile for {}: {}", did, e.getMessage());
        }
    }

    /** Load by DID. Empty if no row. */
    public Optional<VoiceProfile> load(String did) {
        if (did == null || did.isBlank()) return Optional.empty();
        var sql = "SELECT clauses_json, revision, frozen, history_json "
            + "FROM voice_profiles WHERE did = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Map<String, String> clauses = mapper.readValue(
                    rs.getString(1), new TypeReference<LinkedHashMap<String, String>>() {});
                int revision = rs.getInt(2);
                boolean frozen = rs.getInt(3) != 0;
                List<VoiceProfile.ProfileRevision> history = mapper.readValue(
                    rs.getString(4),
                    new TypeReference<List<VoiceProfile.ProfileRevision>>() {});
                return Optional.of(new VoiceProfile(clauses, revision, frozen, history));
            }
        } catch (Exception e) {
            log.error("Failed to load voice_profile for {}: {}", did, e.getMessage());
            return Optional.empty();
        }
    }

    /** Count rows. Used by backfill / dump. */
    public int count() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT COUNT(*) FROM voice_profiles");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("voice_profiles count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * One-time backfill: walk every {@code soul_manifests} row's embedded
     * voiceProfile and persist into {@code voice_profiles}, skipping
     * dids that already have a row. Idempotent — safe to run on every
     * startup until the SoulManifest.voiceProfile field is dropped
     * (Phase 3).
     *
     * @return number of newly written rows
     */
    public int backfillFromManifests(SoulStore manifestStore) {
        int written = 0;
        // Get every distinct DID from manifests by enumerating souls.
        // SoulStore doesn't expose listAll; use the latest-per-did via DB.
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "SELECT DISTINCT did FROM soul_manifests WHERE archived = 0");
             var rs = st.executeQuery()) {
            while (rs.next()) {
                var did = rs.getString(1);
                // Skip if voice_profiles row already exists.
                if (load(did).isPresent()) continue;
                var manifestOpt = manifestStore.latest(did);
                if (manifestOpt.isEmpty()) continue;
                var vp = manifestOpt.get().voiceProfile();
                if (vp == null) vp = VoiceProfile.empty();
                save(did, vp);
                written++;
            }
        } catch (SQLException e) {
            log.warn("voice_profiles backfill query failed: {}", e.getMessage());
        }
        if (written > 0) {
            log.info("VoiceProfileStore: backfilled {} profile(s) from soul_manifests", written);
        }
        return written;
    }
}
