package org.wyrdsekai.core.home;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.RevocationMode;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC persistence for the Home model: grants and audit log.
 *
 * <p>Thin wrapper. {@link HomeRegistryActor} holds an in-memory cache and
 * writes through here. All queries are plain SQL compatible with both SQLite
 * and PostgreSQL, following the pattern in {@code FederationService}.</p>
 */
public final class HomeStore {

    private static final Logger log = LoggerFactory.getLogger(HomeStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String jdbcUrl;

    public HomeStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    // --- Grant CRUD ----------------------------------------------------

    public void saveGrant(Grant g) {
        var sql = "INSERT INTO grants(id, issuer, subject, resource, resource_type, capability, "
            + "scope_json, revocation_mode, issued_at, expires_at, revoked_at, reason, witness, delegated_from) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
            + "ON CONFLICT(id) DO UPDATE SET "
            + "revoked_at = excluded.revoked_at, expires_at = excluded.expires_at";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, g.id());
            st.setString(2, g.issuer());
            st.setString(3, g.subject());
            st.setString(4, g.resource().toString());
            st.setString(5, g.resource().type());
            st.setString(6, g.capability().name());
            st.setString(7, writeScope(g.scope()));
            st.setString(8, g.revocationMode().name());
            st.setLong(9, g.issuedAt().getEpochSecond());
            if (g.expiresAt() != null) st.setLong(10, g.expiresAt().getEpochSecond());
            else st.setNull(10, Types.BIGINT);
            if (g.revokedAt() != null) st.setLong(11, g.revokedAt().getEpochSecond());
            else st.setNull(11, Types.BIGINT);
            st.setString(12, g.reason());
            st.setString(13, g.witness());
            st.setString(14, g.delegatedFrom());
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save grant {}: {}", g.id(), e.getMessage());
            throw new RuntimeException("save grant failed", e);
        }
    }

    public Optional<Grant> getGrant(String id) {
        var sql = "SELECT * FROM grants WHERE id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, id);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(grantFromRow(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to get grant {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** Grants issued by a given owner. */
    public List<Grant> grantsByIssuer(String issuerDid) {
        var sql = "SELECT * FROM grants WHERE issuer = ? ORDER BY issued_at DESC";
        return queryGrants(sql, issuerDid);
    }

    /** Grants held by a given subject. */
    public List<Grant> grantsBySubject(String subjectDid) {
        var sql = "SELECT * FROM grants WHERE subject = ? ORDER BY issued_at DESC";
        return queryGrants(sql, subjectDid);
    }

    /** Active grants (not revoked, not expired) where subject holds capability on resource. */
    public List<Grant> findActiveGrants(String subjectDid, String resourceUri, Capability capability) {
        var nowSec = Instant.now().getEpochSecond();
        var sql = "SELECT * FROM grants "
            + "WHERE subject = ? AND resource = ? AND capability = ? "
            + "AND revoked_at IS NULL "
            + "AND (expires_at IS NULL OR expires_at > ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, subjectDid);
            st.setString(2, resourceUri);
            st.setString(3, capability.name());
            st.setLong(4, nowSec);
            return collect(st);
        } catch (SQLException e) {
            log.error("findActiveGrants failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Revoke a grant by id. Writes {@code revoked_at}. */
    public void revokeGrant(String id, Instant when) {
        var sql = "UPDATE grants SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, when.getEpochSecond());
            st.setString(2, id);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to revoke grant {}: {}", id, e.getMessage());
        }
    }

    /** Revoke all grants delegated from a parent. Returns count. */
    public int revokeDelegatedFrom(String parentGrantId, Instant when) {
        var sql = "UPDATE grants SET revoked_at = ? WHERE delegated_from = ? AND revoked_at IS NULL";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, when.getEpochSecond());
            st.setString(2, parentGrantId);
            return st.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed cascade-revoke of delegations from {}: {}", parentGrantId, e.getMessage());
            return 0;
        }
    }

    // --- Audit log -----------------------------------------------------

    public void appendAudit(AuditEntry e) {
        var sql = "INSERT INTO audit_log(id, home_owner, timestamp, actor, verb, resource, "
            + "outcome, detail_json, correlation) VALUES(?,?,?,?,?,?,?,?,?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, e.id());
            st.setString(2, e.homeOwner());
            st.setLong(3, e.timestamp().getEpochSecond());
            st.setString(4, e.actor());
            st.setString(5, e.verb());
            st.setString(6, e.resource());
            st.setString(7, e.outcome().name());
            st.setString(8, writeScope(e.detail()));
            st.setString(9, e.correlation());
            st.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to append audit entry: {}", ex.getMessage());
        }
    }

    public List<AuditEntry> queryAudit(String homeOwner, Instant since, int limit) {
        var sql = "SELECT * FROM audit_log WHERE home_owner = ? AND timestamp >= ? "
            + "ORDER BY timestamp DESC LIMIT ?";
        var result = new ArrayList<AuditEntry>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, homeOwner);
            st.setLong(2, since == null ? 0 : since.getEpochSecond());
            st.setInt(3, limit);
            try (var rs = st.executeQuery()) {
                while (rs.next()) result.add(auditFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("queryAudit failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Find active grants whose {@code expires_at} is in the past (as epoch
     * seconds). Used by the reaper to emit {@code grant-expired} audit
     * entries and let caches drop these.
     */
    public List<Grant> findNewlyExpired(Instant now) {
        var sql = "SELECT * FROM grants "
            + "WHERE revoked_at IS NULL AND expires_at IS NOT NULL AND expires_at <= ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, now.getEpochSecond());
            return collect(st);
        } catch (SQLException e) {
            log.error("findNewlyExpired failed: {}", e.getMessage());
            return List.of();
        }
    }

    // --- Grant requests ------------------------------

    public void saveGrantRequest(GrantRequest r) {
        var sql = "INSERT INTO grant_requests("
            + "id, requester, owner, resource, resource_type, capability, "
            + "scope_json, reason, status, created_at, responded_at, responder_note, issued_grant) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
            + "ON CONFLICT(id) DO UPDATE SET "
            + "status = excluded.status, "
            + "responded_at = excluded.responded_at, "
            + "responder_note = excluded.responder_note, "
            + "issued_grant = excluded.issued_grant";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, r.id());
            st.setString(2, r.requester());
            st.setString(3, r.owner());
            st.setString(4, r.resource().toString());
            st.setString(5, r.resource().type());
            st.setString(6, r.capability().name());
            st.setString(7, writeScope(r.scope()));
            st.setString(8, r.reason());
            st.setString(9, r.status().name());
            st.setLong(10, r.createdAt().getEpochSecond());
            if (r.respondedAt() != null) st.setLong(11, r.respondedAt().getEpochSecond());
            else st.setNull(11, Types.BIGINT);
            st.setString(12, r.responderNote());
            st.setString(13, r.issuedGrantId());
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("saveGrantRequest failed: {}", e.getMessage());
        }
    }

    public Optional<GrantRequest> getGrantRequest(String id) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT * FROM grant_requests WHERE id = ?")) {
            st.setString(1, id);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(grantRequestFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("getGrantRequest failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public List<GrantRequest> pendingForOwner(String ownerDid) {
        var sql = "SELECT * FROM grant_requests WHERE owner = ? AND status = 'pending' "
            + "ORDER BY created_at DESC";
        return queryGrantRequests(sql, ownerDid);
    }

    public List<GrantRequest> byRequester(String requesterDid) {
        var sql = "SELECT * FROM grant_requests WHERE requester = ? ORDER BY created_at DESC";
        return queryGrantRequests(sql, requesterDid);
    }

    private List<GrantRequest> queryGrantRequests(String sql, String arg) {
        var out = new ArrayList<GrantRequest>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, arg);
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(grantRequestFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("queryGrantRequests failed: {}", e.getMessage());
        }
        return out;
    }

    private static GrantRequest grantRequestFromRow(ResultSet rs)
            throws SQLException {
        var responded = rs.getLong("responded_at");
        return new GrantRequest(
            rs.getString("id"),
            rs.getString("requester"),
            rs.getString("owner"),
            ResourceUri.parse(rs.getString("resource")),
            Capability.valueOf(rs.getString("capability")),
            readScope(rs.getString("scope_json")),
            rs.getString("reason"),
            GrantRequest.Status.valueOf(rs.getString("status")),
            Instant.ofEpochSecond(rs.getLong("created_at")),
            rs.wasNull() || responded == 0 ? null : Instant.ofEpochSecond(responded),
            rs.getString("responder_note"),
            rs.getString("issued_grant"));
    }

    // --- Helpers -------------------------------------------------------

    private List<Grant> queryGrants(String sql, String arg) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, arg);
            return collect(st);
        } catch (SQLException e) {
            log.error("queryGrants failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Grant> collect(PreparedStatement st) throws SQLException {
        var result = new ArrayList<Grant>();
        try (var rs = st.executeQuery()) {
            while (rs.next()) result.add(grantFromRow(rs));
        }
        return result;
    }

    private static Grant grantFromRow(ResultSet rs) throws SQLException {
        var resource = ResourceUri.parse(rs.getString("resource"));
        var capability = Capability.valueOf(rs.getString("capability"));
        var scope = readScope(rs.getString("scope_json"));
        var mode = RevocationMode.parse(rs.getString("revocation_mode"));
        var issuedAt = Instant.ofEpochSecond(rs.getLong("issued_at"));
        var expiresLong = rs.getLong("expires_at");
        var expiresAt = rs.wasNull() ? null : Instant.ofEpochSecond(expiresLong);
        var revokedLong = rs.getLong("revoked_at");
        var revokedAt = rs.wasNull() ? null : Instant.ofEpochSecond(revokedLong);
        return new Grant(
            rs.getString("id"), rs.getString("issuer"), rs.getString("subject"),
            resource, capability, scope, mode,
            issuedAt, expiresAt, revokedAt,
            rs.getString("reason"), rs.getString("witness"), rs.getString("delegated_from"));
    }

    private static AuditEntry auditFromRow(ResultSet rs) throws SQLException {
        return new AuditEntry(
            rs.getString("id"),
            rs.getString("home_owner"),
            Instant.ofEpochSecond(rs.getLong("timestamp")),
            rs.getString("actor"),
            rs.getString("verb"),
            rs.getString("resource"),
            AuditEntry.Outcome.valueOf(rs.getString("outcome")),
            readScope(rs.getString("detail_json")),
            rs.getString("correlation"));
    }

    private static String writeScope(Map<String, Object> scope) {
        if (scope == null || scope.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(scope);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> readScope(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
