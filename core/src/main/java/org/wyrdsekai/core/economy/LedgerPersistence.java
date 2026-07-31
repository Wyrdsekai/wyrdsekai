package org.wyrdsekai.core.economy;

import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for MutualCreditLedger (§68).
 * Double-entry bookkeeping storage.
 */
public class LedgerPersistence {

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public LedgerPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
    }

    public void saveTransaction(MutualCreditLedger.Transaction tx) {
        var sql = dialect.upsert("ledger_transactions",
            "tx_id, from_entity, to_entity, amount, description, created_at",
            "?, ?, ?, ?, ?, ?",
            "tx_id",
            "description = EXCLUDED.description");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, tx.id());
            ps.setString(2, tx.fromEntity());
            ps.setString(3, tx.toEntity());
            ps.setLong(4, tx.amount());
            ps.setString(5, tx.description());
            ps.setLong(6, tx.timestamp().getEpochSecond());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save transaction: " + tx.id(), e);
        }
    }

    public Optional<MutualCreditLedger.Transaction> loadTransaction(String txId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM ledger_transactions WHERE tx_id = ?")) {
            ps.setString(1, txId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new MutualCreditLedger.Transaction(
                    rs.getString("tx_id"),
                    rs.getString("from_entity"),
                    rs.getString("to_entity"),
                    rs.getLong("amount"),
                    rs.getString("description"),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load transaction: " + txId, e);
        }
    }

    public List<MutualCreditLedger.Transaction> transactionsForEntity(String entityId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT * FROM ledger_transactions WHERE from_entity = ? OR to_entity = ? ORDER BY created_at DESC")) {
            ps.setString(1, entityId);
            ps.setString(2, entityId);
            var rs = ps.executeQuery();
            var txs = new ArrayList<MutualCreditLedger.Transaction>();
            while (rs.next()) {
                txs.add(new MutualCreditLedger.Transaction(
                    rs.getString("tx_id"),
                    rs.getString("from_entity"),
                    rs.getString("to_entity"),
                    rs.getLong("amount"),
                    rs.getString("description"),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
            return txs;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query transactions for: " + entityId, e);
        }
    }

    public void saveBalance(String entityId, CreditBalance balance) {
        var sql = dialect.upsert("ledger_balances",
            "entity_id, balance, credit_limit, total_earned, total_spent",
            "?, ?, ?, ?, ?",
            "entity_id",
            "balance = EXCLUDED.balance, credit_limit = EXCLUDED.credit_limit, total_earned = EXCLUDED.total_earned, total_spent = EXCLUDED.total_spent");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityId);
            ps.setLong(2, balance.balance());
            ps.setLong(3, balance.creditLimit());
            ps.setLong(4, balance.totalEarned());
            ps.setLong(5, balance.totalSpent());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save balance: " + entityId, e);
        }
    }

    public Optional<CreditBalance> loadBalance(String entityId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM ledger_balances WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new CreditBalance(
                    rs.getString("entity_id"),
                    rs.getLong("balance"),
                    rs.getLong("credit_limit"),
                    rs.getLong("total_earned"),
                    rs.getLong("total_spent")
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load balance: " + entityId, e);
        }
    }

    public int transactionCount() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_transactions")) {
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count transactions", e);
        }
    }
}
