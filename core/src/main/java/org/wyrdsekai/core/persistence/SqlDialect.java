package org.wyrdsekai.core.persistence;

/**
 * SQL dialect abstraction for SQLite vs PostgreSQL.
 * Handles the few syntax differences between backends.
 */
public sealed interface SqlDialect {

    /** INSERT ... ON CONFLICT DO NOTHING (idempotent insert). */
    String insertIgnore(String table, String columns, String values);

    /** INSERT ... ON CONFLICT (keys) DO UPDATE (upsert). */
    String upsert(String table, String columns, String values,
                  String conflictColumns, String updateSet);

    /** Case-insensitive string comparison. */
    String caseInsensitiveEquals(String column, String placeholder);

    /** Current epoch timestamp. */
    String currentEpoch();

    /** Boolean literal true. */
    String boolTrue();

    /** Boolean literal false. */
    String boolFalse();

    // --- Implementations ---

    record SQLite() implements SqlDialect {
        @Override
        public String insertIgnore(String table, String columns, String values) {
            return "INSERT OR IGNORE INTO " + table + " (" + columns + ") VALUES (" + values + ")";
        }

        @Override
        public String upsert(String table, String columns, String values,
                              String conflictColumns, String updateSet) {
            // INSERT OR REPLACE is not an upsert: SQLite implements it as
            // DELETE + INSERT, so every column absent from the INSERT list
            // snaps back to its schema default. Live 2026-08-11: the sleep
            // backlog persisted beside the vitality snapshot was zeroed by
            // the very next vitality save — the companion's adenosine erased
            // thirty seconds after every deposit, and at PostStop (no chaser
            // write follows) the zero stuck. Callers hand this method an
            // explicit column-scoped update set; honor it. ON CONFLICT DO
            // UPDATE (SQLite ≥3.24) accepts the same EXCLUDED syntax the
            // PostgreSQL dialect uses.
            return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ") "
                + "ON CONFLICT(" + conflictColumns + ") DO UPDATE SET " + updateSet;
        }

        @Override
        public String caseInsensitiveEquals(String column, String placeholder) {
            return column + " = " + placeholder + " COLLATE NOCASE";
        }

        @Override
        public String currentEpoch() {
            return "unixepoch()";
        }

        @Override public String boolTrue() { return "1"; }
        @Override public String boolFalse() { return "0"; }
    }

    record PostgreSQL() implements SqlDialect {
        @Override
        public String insertIgnore(String table, String columns, String values) {
            return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ") ON CONFLICT DO NOTHING";
        }

        @Override
        public String upsert(String table, String columns, String values,
                              String conflictColumns, String updateSet) {
            return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")"
                + " ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + updateSet;
        }

        @Override
        public String caseInsensitiveEquals(String column, String placeholder) {
            return "LOWER(" + column + ") = LOWER(" + placeholder + ")";
        }

        @Override
        public String currentEpoch() {
            return "EXTRACT(EPOCH FROM NOW())::bigint";
        }

        @Override public String boolTrue() { return "TRUE"; }
        @Override public String boolFalse() { return "FALSE"; }
    }

    /** Create a dialect based on JDBC URL. */
    static SqlDialect fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:postgresql")) {
            return new PostgreSQL();
        }
        return new SQLite();
    }
}
