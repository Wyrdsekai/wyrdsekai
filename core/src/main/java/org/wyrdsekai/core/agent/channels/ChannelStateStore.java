package org.wyrdsekai.core.agent.channels;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Durable per-channel state — offset checkpoints + processed-message ledger.
 *
 * <p>Channels (Telegram/Discord/Slack/Line/Keybase) historically held their
 * last-seen poll offset in memory only. After a server restart the offset
 * resets to 0, which produces one of two failure modes:</p>
 *
 * <ul>
 *   <li>Replay storm — the channel re-fetches every update server-side has
 *       buffered, all of which arrive before the agent's mailbox can dedup
 *       them. The agent sees the same message 3-5 times.</li>
 *   <li>Message loss — if the channel server-side already trimmed updates
 *       behind the previous offset (Telegram does this on ack), restarting
 *       at offset=0 fetches nothing newer than the trim point and any
 *       messages between the previous-poll and the crash never reach the
 *       agent.</li>
 * </ul>
 *
 * <p>This store fixes both: offset is checkpointed after each successful
 * publish, and a separate dedup ledger ensures even if a replay storm
 * happens (e.g. across two channel implementations sharing a chat), each
 * external message is only published to {@code AgentEventStream} once.</p>
 *
 * <p>Channels that wish to mature should:</p>
 * <ol>
 *   <li>{@link #readOffset} on listener start — resume from there, fall
 *       back to channel default (e.g. 0 / "latest") when null.</li>
 *   <li>{@link #isProcessed} before {@code publishAgentMessage} — skip if
 *       already seen.</li>
 *   <li>{@link #markProcessed} immediately before publishing — close the
 *       window between log-and-ack.</li>
 *   <li>{@link #writeOffset} after the channel-server-side ack of the
 *       outer batch (e.g. Telegram's getUpdates offset advance).</li>
 *   <li>{@link #pruneProcessedOlderThan} on a periodic schedule (~weekly)
 *       so the dedup ledger doesn't grow unbounded.</li>
 * </ol>
 *
 * <p>Singleton — one store instance per node, JDBC URL injected at startup
 * by the persistence bootstrap.</p>
 */
public final class ChannelStateStore {

    private static final Logger log = LoggerFactory.getLogger(ChannelStateStore.class);

    private static volatile ChannelStateStore INSTANCE;

    public static ChannelStateStore get() { return INSTANCE; }

    public static void setInstance(ChannelStateStore store) { INSTANCE = store; }

    public static void resetForTests() { INSTANCE = null; }

    private final String jdbcUrl;

    public ChannelStateStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    // ── Offset checkpoints ──────────────────────────────────────────────

    /**
     * Read the last-checkpointed offset for this (channel, thread) pair.
     * Returns empty if never checkpointed — callers should use the
     * channel-default starting offset in that case.
     */
    public Optional<String> readOffset(String channel, String threadKey) {
        var sql = "SELECT offset_value FROM channel_state "
            + "WHERE channel = ? AND thread_key = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, channel);
            st.setString(2, threadKey);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("ChannelStateStore.readOffset({}, {}) failed: {}",
                    channel, threadKey, e.getMessage());
        }
        return Optional.empty();
    }

    /** Persist offset for (channel, thread). Idempotent upsert. */
    public void writeOffset(String channel, String threadKey, String offset) {
        var sql = "INSERT INTO channel_state (channel, thread_key, offset_value, updated_at) "
            + "VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (channel, thread_key) DO UPDATE SET "
            + "  offset_value = excluded.offset_value, "
            + "  updated_at = excluded.updated_at";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, channel);
            st.setString(2, threadKey);
            st.setString(3, offset);
            st.setLong(4, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("ChannelStateStore.writeOffset({}, {}) failed: {}",
                    channel, threadKey, e.getMessage());
        }
    }

    // ── Dedup ledger ────────────────────────────────────────────────────

    /**
     * Has this (channel, externalId) already been published? When true,
     * the channel must skip re-publishing — even if it's seeing the same
     * external message a second time due to replay/retry.
     */
    public boolean isProcessed(String channel, String externalId) {
        var sql = "SELECT 1 FROM channel_processed "
            + "WHERE channel = ? AND external_id = ? LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, channel);
            st.setString(2, externalId);
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("ChannelStateStore.isProcessed({}, {}) failed: {}",
                    channel, externalId, e.getMessage());
            // Fail-open: better to re-process than to drop a real message.
            return false;
        }
    }

    /**
     * Record (channel, externalId) as processed. Idempotent — safe to call
     * twice without throwing on conflict.
     */
    public void markProcessed(String channel, String externalId) {
        var sql = "INSERT INTO channel_processed (channel, external_id, processed_at) "
            + "VALUES (?, ?, ?) "
            + "ON CONFLICT (channel, external_id) DO NOTHING";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, channel);
            st.setString(2, externalId);
            st.setLong(3, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("ChannelStateStore.markProcessed({}, {}) failed: {}",
                    channel, externalId, e.getMessage());
        }
    }

    /**
     * Delete dedup-ledger entries older than {@code ageMs}. Returns the
     * row count deleted. Safe-default: a week ({@code 7L * 86_400_000})
     * keeps even a paused channel's dedup window valid.
     */
    public int pruneProcessedOlderThan(long ageMs) {
        var threshold = System.currentTimeMillis() - ageMs;
        var sql = "DELETE FROM channel_processed WHERE processed_at < ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, threshold);
            return st.executeUpdate();
        } catch (SQLException e) {
            log.warn("ChannelStateStore.pruneProcessedOlderThan failed: {}", e.getMessage());
            return 0;
        }
    }
}
