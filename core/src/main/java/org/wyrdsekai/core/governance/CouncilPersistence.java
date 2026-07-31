package org.wyrdsekai.core.governance;

import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * JDBC persistence for CouncilService proposals (§34).
 */
public class CouncilPersistence {

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public CouncilPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
    }

    public void saveProposal(CouncilService.Proposal proposal) {
        var sql = dialect.upsert("council_proposals",
            "proposal_id, title, description, proposal_type, status, proposer, created_at, voting_ends_at, votes",
            "?, ?, ?, ?, ?, ?, ?, ?, ?",
            "proposal_id",
            "title = EXCLUDED.title, status = EXCLUDED.status, voting_ends_at = EXCLUDED.voting_ends_at, votes = EXCLUDED.votes");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposal.id());
            ps.setString(2, proposal.title());
            ps.setString(3, proposal.description());
            ps.setString(4, proposal.type().name());
            ps.setString(5, proposal.status().name());
            ps.setString(6, proposal.proposer());
            ps.setLong(7, proposal.createdAt().getEpochSecond());
            ps.setLong(8, proposal.votingEndsAt() != null ? proposal.votingEndsAt().getEpochSecond() : 0);
            ps.setString(9, serializeVotes(proposal.votes()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save proposal: " + proposal.id(), e);
        }
    }

    public Optional<CouncilService.Proposal> loadProposal(String proposalId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM council_proposals WHERE proposal_id = ?")) {
            ps.setString(1, proposalId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new CouncilService.Proposal(
                    rs.getString("proposal_id"),
                    rs.getString("title"),
                    rs.getString("description"),
                    CouncilService.ProposalType.valueOf(rs.getString("proposal_type")),
                    CouncilService.ProposalStatus.valueOf(rs.getString("status")),
                    rs.getString("proposer"),
                    Instant.ofEpochSecond(rs.getLong("created_at")),
                    rs.getLong("voting_ends_at") > 0 ? Instant.ofEpochSecond(rs.getLong("voting_ends_at")) : null,
                    deserializeVotes(rs.getString("votes"))
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load proposal: " + proposalId, e);
        }
    }

    public List<CouncilService.Proposal> activeProposals() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT * FROM council_proposals WHERE status IN ('DISCUSSION', 'VOTING') ORDER BY created_at DESC")) {
            var rs = ps.executeQuery();
            var proposals = new ArrayList<CouncilService.Proposal>();
            while (rs.next()) {
                proposals.add(new CouncilService.Proposal(
                    rs.getString("proposal_id"),
                    rs.getString("title"),
                    rs.getString("description"),
                    CouncilService.ProposalType.valueOf(rs.getString("proposal_type")),
                    CouncilService.ProposalStatus.valueOf(rs.getString("status")),
                    rs.getString("proposer"),
                    Instant.ofEpochSecond(rs.getLong("created_at")),
                    rs.getLong("voting_ends_at") > 0 ? Instant.ofEpochSecond(rs.getLong("voting_ends_at")) : null,
                    deserializeVotes(rs.getString("votes"))
                ));
            }
            return proposals;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query active proposals", e);
        }
    }

    public int proposalCount() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM council_proposals")) {
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count proposals", e);
        }
    }

    private static String serializeVotes(Map<String, Boolean> votes) {
        if (votes == null || votes.isEmpty()) return "";
        var sb = new StringBuilder();
        votes.forEach((entity, approve) -> {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(entity).append(":").append(approve ? "1" : "0");
        });
        return sb.toString();
    }

    private static Map<String, Boolean> deserializeVotes(String csv) {
        if (csv == null || csv.isBlank()) return new HashMap<>();
        var map = new HashMap<String, Boolean>();
        for (var entry : csv.split(",")) {
            var parts = entry.split(":");
            if (parts.length == 2) {
                map.put(parts[0], "1".equals(parts[1]));
            }
        }
        return map;
    }
}
