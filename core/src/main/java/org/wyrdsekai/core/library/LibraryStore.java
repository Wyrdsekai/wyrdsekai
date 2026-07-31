package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * SQLite persistence for the capability library.
 * Adapted from CodePlane's LibraryStore with Wyrdsekai-specific schema.
 * Features: FTS5 search, audit trail, usage tracking, blocklist, security patterns.
 * WAL mode, busy_timeout, synchronized access.
 */
public final class LibraryStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LibraryStore.class);

    private final Connection connection;

    public LibraryStore(String dbPath) throws SQLException {
        this(dbPath, 5000);
    }

    public LibraryStore(String dbPath, int busyTimeoutMs) throws SQLException {
        String url = dbPath.startsWith("jdbc:") ? dbPath : "jdbc:sqlite:" + dbPath;
        this.connection = DriverManager.getConnection(url);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
        }
        initSchema();
        log.info("LibraryStore opened at {}", dbPath);
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Capabilities table — Wyrdsekai-specific schema
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_capabilities (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    version TEXT,
                    description TEXT,
                    cognitive_layer TEXT,
                    tags TEXT,
                    source TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    trust_score REAL DEFAULT -1,
                    verification_status TEXT DEFAULT 'UNVERIFIED',
                    last_verified TEXT,
                    attestation TEXT,
                    provenance TEXT,
                    provider TEXT NOT NULL,
                    required_ward TEXT,
                    token_cost INTEGER DEFAULT 0,
                    installed INTEGER DEFAULT 0,
                    installed_location TEXT,
                    registered_at TEXT,
                    installed_at TEXT
                )
                """);

            // FTS5 virtual table for ranked full-text search
            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS library_capabilities_fts USING fts5(
                    name, description, tags,
                    content=library_capabilities, content_rowid=rowid
                )
                """);

            // FTS5 sync triggers
            stmt.execute("""
                CREATE TRIGGER IF NOT EXISTS library_cap_ai AFTER INSERT ON library_capabilities BEGIN
                    INSERT INTO library_capabilities_fts(rowid, name, description, tags)
                    VALUES (new.rowid, new.name, new.description, new.tags);
                END
                """);

            stmt.execute("""
                CREATE TRIGGER IF NOT EXISTS library_cap_ad AFTER DELETE ON library_capabilities BEGIN
                    INSERT INTO library_capabilities_fts(library_capabilities_fts, rowid, name, description, tags)
                    VALUES ('delete', old.rowid, old.name, old.description, old.tags);
                END
                """);

            stmt.execute("""
                CREATE TRIGGER IF NOT EXISTS library_cap_au AFTER UPDATE ON library_capabilities BEGIN
                    INSERT INTO library_capabilities_fts(library_capabilities_fts, rowid, name, description, tags)
                    VALUES ('delete', old.rowid, old.name, old.description, old.tags);
                    INSERT INTO library_capabilities_fts(rowid, name, description, tags)
                    VALUES (new.rowid, new.name, new.description, new.tags);
                END
                """);

            // Sync state (for federated sync tracking)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_sync_state (
                    source_key TEXT PRIMARY KEY,
                    last_sync_time TEXT,
                    record_count INTEGER DEFAULT 0,
                    status TEXT DEFAULT 'never'
                )
                """);

            // Blocklist
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_blocklist (
                    name TEXT PRIMARY KEY,
                    reason TEXT,
                    added_by TEXT,
                    added_at TEXT
                )
                """);

            // Usage tracking
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_usage_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    capability_id TEXT NOT NULL,
                    agent_id TEXT,
                    timestamp TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    latency_ms INTEGER
                )
                """);

            // Audit trail
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    capability_id TEXT,
                    capability_name TEXT,
                    agent_id TEXT,
                    details TEXT,
                    timestamp TEXT NOT NULL
                )
                """);

            // Security patterns (shared with OutputSanitizer)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_security_patterns (
                    name TEXT PRIMARY KEY,
                    category TEXT NOT NULL,
                    pattern_type TEXT NOT NULL,
                    regex TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    trust_tier TEXT NOT NULL,
                    signature TEXT
                )
                """);

            // Pattern update log
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_pattern_update_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    signature TEXT,
                    additions_count INTEGER DEFAULT 0,
                    removals_count INTEGER DEFAULT 0,
                    modifications_count INTEGER DEFAULT 0
                )
                """);

            // Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_name ON library_capabilities(name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_version ON library_capabilities(version)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_source ON library_capabilities(source)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_status ON library_capabilities(verification_status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_installed ON library_capabilities(installed)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_trust ON library_capabilities(trust_score)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libcap_layer ON library_capabilities(cognitive_layer)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libusage_cap ON library_usage_log(capability_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libaudit_type ON library_audit_log(type, timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_libpat_type ON library_security_patterns(pattern_type, category)");
        }
    }

    // --- Capability CRUD ---

    public synchronized void upsertCapability(CapabilityRecord record) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO library_capabilities
            (id, name, version, description, cognitive_layer, tags, source, protocol,
             trust_score, verification_status, last_verified, attestation, provenance,
             provider, required_ward, token_cost, installed, installed_location,
             registered_at, installed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            bindCapability(ps, record);
            ps.executeUpdate();
        }
    }

    public synchronized void upsertCapabilities(List<CapabilityRecord> records) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO library_capabilities
            (id, name, version, description, cognitive_layer, tags, source, protocol,
             trust_score, verification_status, last_verified, attestation, provenance,
             provider, required_ward, token_cost, installed, installed_location,
             registered_at, installed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            for (CapabilityRecord record : records) {
                bindCapability(ps, record);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized Optional<CapabilityRecord> getById(String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readCapability(rs));
                return Optional.empty();
            }
        }
    }

    public synchronized List<CapabilityRecord> getByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE name = ? ORDER BY version")) {
            ps.setString(1, name);
            return readCapabilities(ps);
        }
    }

    /** Full-text search using FTS5 (ranked by relevance). */
    public synchronized List<CapabilityRecord> search(String keyword, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT c.* FROM library_capabilities c
            JOIN library_capabilities_fts f ON c.rowid = f.rowid
            WHERE library_capabilities_fts MATCH ?
            ORDER BY rank
            LIMIT ?
            """)) {
            ps.setString(1, keyword);
            ps.setInt(2, limit);
            return readCapabilities(ps);
        }
    }

    public synchronized List<CapabilityRecord> listAll() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE verification_status != 'BANNED' ORDER BY cognitive_layer, name")) {
            return readCapabilities(ps);
        }
    }

    public synchronized List<CapabilityRecord> listByLayer(CapabilityRecord.CognitiveLayer layer) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE cognitive_layer = ? AND verification_status != 'BANNED' ORDER BY name")) {
            ps.setString(1, layer.name());
            return readCapabilities(ps);
        }
    }

    public synchronized List<CapabilityRecord> listByTag(String tag) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE tags LIKE ? AND verification_status != 'BANNED' ORDER BY name")) {
            ps.setString(1, "%" + tag + "%");
            return readCapabilities(ps);
        }
    }

    public synchronized List<CapabilityRecord> listByStatus(CapabilityRecord.VerificationStatus status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE verification_status = ? ORDER BY name")) {
            ps.setString(1, status.name());
            return readCapabilities(ps);
        }
    }

    public synchronized List<CapabilityRecord> listInstalled() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_capabilities WHERE installed = 1 ORDER BY name")) {
            return readCapabilities(ps);
        }
    }

    public synchronized int totalCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM library_capabilities")) {
            return rs.getInt(1);
        }
    }

    public synchronized int countByStatus(CapabilityRecord.VerificationStatus status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM library_capabilities WHERE verification_status = ?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.getInt(1);
            }
        }
    }

    // --- Verification ---

    public synchronized void updateVerificationStatus(String id,
            CapabilityRecord.VerificationStatus status, float trustScore, Instant verifiedAt)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE library_capabilities SET verification_status = ?, trust_score = ?, last_verified = ?
            WHERE id = ?
            """)) {
            ps.setString(1, status.name());
            ps.setFloat(2, trustScore);
            ps.setString(3, verifiedAt != null ? verifiedAt.toString() : null);
            ps.setString(4, id);
            ps.executeUpdate();
        }
    }

    public synchronized void markInstalled(String id, String location, Instant installedAt)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE library_capabilities SET installed = 1, installed_location = ?, installed_at = ?
            WHERE id = ?
            """)) {
            ps.setString(1, location);
            ps.setString(2, installedAt != null ? installedAt.toString() : null);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public synchronized void markUninstalled(String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE library_capabilities SET installed = 0, installed_location = NULL, installed_at = NULL
            WHERE id = ?
            """)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized void ban(String id, String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE library_capabilities SET verification_status = 'BANNED' WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        appendAuditEntry(new AuditEntry(AuditType.BANNED, id, null, null,
            reason, Instant.now()));
    }

    // --- Sync State ---

    public synchronized void updateSyncState(String sourceKey, Instant lastSyncTime,
            int recordCount, String status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO library_sync_state (source_key, last_sync_time, record_count, status)
            VALUES (?, ?, ?, ?)
            """)) {
            ps.setString(1, sourceKey);
            ps.setString(2, lastSyncTime != null ? lastSyncTime.toString() : null);
            ps.setInt(3, recordCount);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    // --- Blocklist ---

    public synchronized void addToBlocklist(String name, String reason, String addedBy) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO library_blocklist (name, reason, added_by, added_at)
            VALUES (?, ?, ?, ?)
            """)) {
            ps.setString(1, name);
            ps.setString(2, reason);
            ps.setString(3, addedBy);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public synchronized boolean isBlocked(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM library_blocklist WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized List<BlocklistEntry> getBlocklist() throws SQLException {
        var result = new ArrayList<BlocklistEntry>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM library_blocklist ORDER BY name")) {
            while (rs.next()) {
                result.add(new BlocklistEntry(
                    rs.getString("name"), rs.getString("reason"),
                    rs.getString("added_by"), parseInstant(rs.getString("added_at"))));
            }
        }
        return result;
    }

    public synchronized void removeFromBlocklist(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM library_blocklist WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    // --- Usage Tracking ---

    public synchronized void recordUsage(String capabilityId, String agentId,
            Instant timestamp, boolean success, long latencyMs) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO library_usage_log (capability_id, agent_id, timestamp, success, latency_ms)
            VALUES (?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, capabilityId);
            ps.setString(2, agentId);
            ps.setString(3, timestamp.toString());
            ps.setInt(4, success ? 1 : 0);
            ps.setLong(5, latencyMs);
            ps.executeUpdate();
        }
    }

    public synchronized UsageStats getUsageStats(String capabilityId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT COUNT(*) as total, SUM(success) as successes,
                   AVG(latency_ms) as avg_latency, MAX(timestamp) as last_used
            FROM library_usage_log WHERE capability_id = ?
            """)) {
            ps.setString(1, capabilityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new UsageStats(capabilityId, rs.getInt("total"),
                        rs.getInt("successes"), rs.getDouble("avg_latency"),
                        parseInstant(rs.getString("last_used")));
                }
                return new UsageStats(capabilityId, 0, 0, 0.0, null);
            }
        }
    }

    // --- Audit Log ---

    public synchronized void appendAuditEntry(AuditEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO library_audit_log (type, capability_id, capability_name, agent_id, details, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, entry.type().name());
            ps.setString(2, entry.capabilityId());
            ps.setString(3, entry.capabilityName());
            ps.setString(4, entry.agentId());
            ps.setString(5, entry.details());
            ps.setString(6, entry.timestamp().toString());
            ps.executeUpdate();
        }
    }

    public synchronized List<AuditEntry> queryAudit(String capabilityId, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM library_audit_log WHERE capability_id = ? ORDER BY timestamp DESC LIMIT ?
            """)) {
            ps.setString(1, capabilityId);
            ps.setInt(2, limit);
            return readAuditEntries(ps);
        }
    }

    public synchronized List<AuditEntry> queryAuditByType(AuditType type, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM library_audit_log WHERE type = ? ORDER BY timestamp DESC LIMIT ?
            """)) {
            ps.setString(1, type.name());
            ps.setInt(2, limit);
            return readAuditEntries(ps);
        }
    }

    // --- Security Patterns ---

    public synchronized void upsertPattern(SecurityPatternManager.SecurityPattern pattern) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO library_security_patterns
            (name, category, pattern_type, regex, severity, trust_tier, signature)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, pattern.name());
            ps.setString(2, pattern.category());
            ps.setString(3, pattern.type().name());
            ps.setString(4, pattern.regex());
            ps.setString(5, pattern.severity().name());
            ps.setString(6, pattern.trustTier().name());
            ps.setString(7, pattern.signature());
            ps.executeUpdate();
        }
    }

    public synchronized void removePattern(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM library_security_patterns WHERE name = ? AND trust_tier != 'BUILTIN'")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    public synchronized List<SecurityPatternManager.SecurityPattern> getPatterns(
            SecurityPatternManager.PatternType type) throws SQLException {
        var result = new ArrayList<SecurityPatternManager.SecurityPattern>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM library_security_patterns WHERE pattern_type = ? ORDER BY category, name")) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SecurityPatternManager.SecurityPattern(
                        rs.getString("name"), rs.getString("category"),
                        SecurityPatternManager.PatternType.valueOf(rs.getString("pattern_type")),
                        rs.getString("regex"),
                        SecurityPatternManager.Severity.valueOf(rs.getString("severity")),
                        SecurityPatternManager.TrustTier.valueOf(rs.getString("trust_tier")),
                        rs.getString("signature")));
                }
            }
        }
        return result;
    }

    public synchronized void logPatternUpdate(String source, Instant timestamp,
            String signature, int additions, int removals, int modifications) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO library_pattern_update_log
            (source, timestamp, signature, additions_count, removals_count, modifications_count)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, source);
            ps.setString(2, timestamp.toString());
            ps.setString(3, signature);
            ps.setInt(4, additions);
            ps.setInt(5, removals);
            ps.setInt(6, modifications);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            log.info("LibraryStore closed");
        }
    }

    // --- Helpers ---

    private void bindCapability(PreparedStatement ps, CapabilityRecord r) throws SQLException {
        ps.setString(1, r.id());
        ps.setString(2, r.name());
        ps.setString(3, r.version());
        ps.setString(4, r.description());
        ps.setString(5, r.cognitiveLayer() != null ? r.cognitiveLayer().name() : null);
        ps.setString(6, r.tags() != null ? String.join(",", r.tags()) : null);
        ps.setString(7, r.source() != null ? r.source().name() : "MANUAL");
        ps.setString(8, r.protocol() != null ? r.protocol().name() : "ROOM_SCRIPT");
        ps.setFloat(9, r.trustScore());
        ps.setString(10, r.verificationStatus() != null ? r.verificationStatus().name() : "UNVERIFIED");
        ps.setString(11, r.lastVerified() != null ? r.lastVerified().toString() : null);
        ps.setString(12, r.attestation());
        ps.setString(13, r.provenance());
        ps.setString(14, r.provider());
        ps.setString(15, r.requiredWard());
        ps.setInt(16, r.tokenCost());
        ps.setInt(17, r.installed() ? 1 : 0);
        ps.setString(18, r.installedLocation());
        ps.setString(19, r.registeredAt() != null ? r.registeredAt().toString() : null);
        ps.setString(20, r.installedAt() != null ? r.installedAt().toString() : null);
    }

    private CapabilityRecord readCapability(ResultSet rs) throws SQLException {
        var tagsStr = rs.getString("tags");
        return new CapabilityRecord(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("description"),
            parseEnum(CapabilityRecord.CognitiveLayer.class, rs.getString("cognitive_layer")),
            tagsStr != null ? Arrays.asList(tagsStr.split(",")) : List.of(),
            parseEnum(CapabilityRecord.CapabilitySource.class, rs.getString("source")),
            parseEnum(CapabilityRecord.CapabilityProtocol.class, rs.getString("protocol")),
            rs.getFloat("trust_score"),
            parseEnum(CapabilityRecord.VerificationStatus.class, rs.getString("verification_status")),
            parseInstant(rs.getString("last_verified")),
            rs.getString("attestation"),
            rs.getString("provenance"),
            rs.getString("provider"),
            rs.getString("required_ward"),
            rs.getInt("token_cost"),
            rs.getInt("installed") == 1,
            rs.getString("installed_location"),
            parseInstant(rs.getString("registered_at")),
            parseInstant(rs.getString("installed_at"))
        );
    }

    private List<CapabilityRecord> readCapabilities(PreparedStatement ps) throws SQLException {
        var result = new ArrayList<CapabilityRecord>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(readCapability(rs));
        }
        return result;
    }

    private List<AuditEntry> readAuditEntries(PreparedStatement ps) throws SQLException {
        var result = new ArrayList<AuditEntry>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new AuditEntry(
                    AuditType.valueOf(rs.getString("type")),
                    rs.getString("capability_id"),
                    rs.getString("capability_name"),
                    rs.getString("agent_id"),
                    rs.getString("details"),
                    parseInstant(rs.getString("timestamp"))));
            }
        }
        return result;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> clazz, String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Enum.valueOf(clazz, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Data records ---

    public record BlocklistEntry(String name, String reason, String addedBy, Instant addedAt) {}

    public record UsageStats(String capabilityId, int totalInvocations, int successes,
            double avgLatencyMs, Instant lastUsed) {
        public double successRate() {
            return totalInvocations > 0 ? (double) successes / totalInvocations : 0.0;
        }
    }

    public record AuditEntry(AuditType type, String capabilityId, String capabilityName,
            String agentId, String details, Instant timestamp) {}

    public enum AuditType {
        REGISTERED,
        VERIFICATION_STARTED,
        VERIFICATION_PASSED,
        VERIFICATION_FAILED,
        INSTALLED,
        INVOKED,
        UPDATED,
        REMOVED,
        TRUST_CHANGED,
        BANNED,
        BLOCKED,
        UNBLOCKED
    }
}
