package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link AgentIdentity} — the key material behind a companion.
 *
 * <p><b>Why this did not exist until now.</b> {@link AgentIdentity} has had
 * {@code generate}, {@code sign}, {@code verify} and a KERI key log since §85.1,
 * and every one of them worked. Nothing ever wrote one down. {@code CompanionActor}
 * minted a DID with {@code DidKey.generate()}, kept the public half for the
 * manifest, and let the private key fall out of scope at the end of the method —
 * so a companion holds an identifier it cannot prove it owns. Live, on 2026-08-08:
 * no {@code agent_identities} table at all, {@code encryption_keys} empty. The
 * same defect the person work fixed for {@code PlayerAccount.create()}, never
 * applied to the agent side.</p>
 *
 * <p>Deliberately a sibling of {@link PersonIdentityStore} rather than a shared
 * table. People and agents are provisioned by different code at different moments,
 * and a row here holds a key the household can use to act <em>as</em> a companion;
 * keeping the blast radii separate is worth one duplicated schema.</p>
 *
 * <p>Two columns beyond the person schema:</p>
 * <ul>
 *   <li>{@code entity_id} — the spawn identity ({@code AgentProfile.entityId}).
 *       Until now the only entityId→DID mapping was a file, {@code souls/&lt;id&gt;.did}.
 *       On 2026-08-08 a stale copy of that file birthed a third companion twenty
 *       seconds after a rebind had already moved the first two. A row in the
 *       database that the mint path checks first makes that mistake harder.</li>
 *   <li>{@code encrypted_private_key} is NULLABLE — a foreign agent recognised via
 *       {@link AgentIdentity#fromResidencyToken} holds its own key and we only ever
 *       have the public half. That row is still worth having: it is how we verify
 *       what they sign. {@link #listKeyless()} is what distinguishes the two.</li>
 * </ul>
 */
public class AgentIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;

    public AgentIdentityStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initSchema();
    }

    private void initSchema() {
        try (var conn = getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS agent_identities(
                  did                    TEXT PRIMARY KEY,
                  entity_id              TEXT,
                  public_key             BLOB NOT NULL,
                  encrypted_private_key  BLOB,
                  key_log                TEXT NOT NULL,
                  parent_did             TEXT,
                  delegation             TEXT,
                  created_at             INTEGER NOT NULL
                )
                """);
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_agent_identities_entity "
                    + "ON agent_identities(entity_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init agent_identities schema", e);
        }
    }

    /**
     * Persist a freshly minted identity. Idempotent on DID.
     *
     * @param entityId the spawn identity this belongs to, or null for a foreign agent
     */
    public void save(AgentIdentity identity, String entityId) {
        var sql = """
            INSERT INTO agent_identities(did, entity_id, public_key, encrypted_private_key,
                                         key_log, parent_did, delegation, created_at)
            VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(did) DO NOTHING
            """;
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, identity.did());
            ps.setString(2, entityId);
            ps.setBytes(3, identity.publicKey());
            ps.setBytes(4, identity.privateKeyEncrypted());
            ps.setString(5, serializeKeyLog(identity.keyLog()));
            ps.setString(6, identity.parentDid());
            ps.setString(7, serializeDelegation(identity.delegation()));
            ps.setLong(8, identity.created() != null
                ? identity.created().getEpochSecond() : Instant.now().getEpochSecond());
            ps.executeUpdate();
            log.info("Agent identity stored: {}{}", identity.did(),
                entityId != null ? " (entity " + entityId + ")" : "");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store agent identity " + identity.did(), e);
        }
    }

    /**
     * Attach a spawn identity to an identity minted before that id was known.
     *
     * <p>The promotion ceremony derives its entityId <em>from</em> the new DID,
     * so the key has to exist first. Only fills a NULL — an existing link is a
     * fact about who this is and is not overwritten silently.</p>
     *
     * @return true if the link was written
     */
    public boolean linkEntity(String did, String entityId) {
        if (did == null || entityId == null || entityId.isBlank()) return false;
        try (var conn = getConnection();
             var ps = conn.prepareStatement("UPDATE agent_identities SET entity_id = ? "
                 + "WHERE did = ? AND entity_id IS NULL")) {
            ps.setString(1, entityId);
            ps.setString(2, did);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Could not link entity {} to {}: {}", entityId, did, e.getMessage());
            return false;
        }
    }

    public Optional<AgentIdentity> findByDid(String did) {
        if (did == null || did.isBlank()) return Optional.empty();
        return queryOne("WHERE did = ?", did);
    }

    /**
     * The identity minted for a spawn identity, if any.
     *
     * <p>Consulted before birth. A companion whose {@code souls/&lt;entityId&gt;.did}
     * file was lost or is stale still resolves here rather than being replaced.</p>
     */
    public Optional<AgentIdentity> findByEntityId(String entityId) {
        if (entityId == null || entityId.isBlank()) return Optional.empty();
        return queryOne("WHERE entity_id = ? ORDER BY created_at LIMIT 1", entityId);
    }

    /** The DID minted for a spawn identity, if any. */
    public Optional<String> didForEntity(String entityId) {
        return findByEntityId(entityId).map(AgentIdentity::did);
    }

    /** Every agent identity on this node, oldest first. */
    public List<String> listDids() {
        var out = new ArrayList<String>();
        try (var conn = getConnection();
             var ps = conn.prepareStatement("SELECT did FROM agent_identities ORDER BY created_at");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString("did"));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list agent identities", e);
        }
        return out;
    }

    /**
     * Identities we can verify but cannot act as — foreign agents, and any
     * legacy row imported without its key. Not an error; a distinction.
     */
    public List<String> listKeyless() {
        var out = new ArrayList<String>();
        try (var conn = getConnection();
             var ps = conn.prepareStatement("SELECT did FROM agent_identities "
                 + "WHERE encrypted_private_key IS NULL ORDER BY created_at");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString("did"));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list keyless agent identities", e);
        }
        return out;
    }

    public boolean exists(String did) {
        return findByDid(did).isPresent();
    }

    /** Whether this node holds the private half, and could therefore sign as {@code did}. */
    public boolean canSign(String did) {
        return findByDid(did)
            .map(i -> i.privateKeyEncrypted() != null && i.privateKeyEncrypted().length > 0)
            .orElse(false);
    }

    // --- internals ---

    private Optional<AgentIdentity> queryOne(String whereClause, String arg) {
        var sql = """
            SELECT did, entity_id, public_key, encrypted_private_key,
                   key_log, parent_did, delegation, created_at
            FROM agent_identities
            """ + whereClause;
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load agent identity " + arg, e);
        }
    }

    private static AgentIdentity read(ResultSet rs) throws SQLException {
        return new AgentIdentity(
            rs.getString("did"),
            rs.getBytes("public_key"),
            rs.getBytes("encrypted_private_key"),
            deserializeKeyLog(rs.getString("key_log")),
            Instant.ofEpochSecond(rs.getLong("created_at")),
            rs.getString("parent_did"),
            deserializeDelegation(rs.getString("delegation")));
    }

    private static String serializeKeyLog(List<ObjectNode> keyLog) {
        try {
            return MAPPER.writeValueAsString(keyLog != null ? keyLog : List.of());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise KERI key log", e);
        }
    }

    private static List<ObjectNode> deserializeKeyLog(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var arr = MAPPER.readTree(json);
            var out = new ArrayList<ObjectNode>();
            arr.forEach(n -> out.add((ObjectNode) n));
            return out;
        } catch (Exception e) {
            log.warn("Could not parse stored KERI key log — treating as empty: {}", e.getMessage());
            return List.of();
        }
    }

    private static String serializeDelegation(AgentIdentity.IdentityDelegation delegation) {
        if (delegation == null) return null;
        try {
            return MAPPER.writeValueAsString(delegation);
        } catch (Exception e) {
            log.warn("Could not serialise delegation — storing none: {}", e.getMessage());
            return null;
        }
    }

    private static AgentIdentity.IdentityDelegation deserializeDelegation(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, AgentIdentity.IdentityDelegation.class);
        } catch (Exception e) {
            // A delegation we cannot read is not an absent identity. Say so, keep the key.
            log.warn("Could not parse stored delegation — identity loads without it: {}",
                e.getMessage());
            return null;
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
