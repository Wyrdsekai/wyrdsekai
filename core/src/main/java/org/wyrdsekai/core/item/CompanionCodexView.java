package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Companion roster for the Companion Codex Study furnishing
 * ({@code world.companions.list()}). One entry per companion soul born in
 * this zone — a household can keep more than one, so this is a list, not a
 * singleton view.
 *
 * <p>Reads the latest {@link org.wyrdsekai.core.soul.SoulManifest} per DID
 * from the soul store (same pattern as the {@code wyrd notify} CLI path) and
 * overlays live presence from the {@link EntityRegistry}. Self-contained so
 * {@link HomeOwnerItemProvider} can use it as its default — no per-transport
 * supplier wiring required.</p>
 */
public final class CompanionCodexView {

    private static final Logger log = LoggerFactory.getLogger(CompanionCodexView.class);

    private CompanionCodexView() {}

    /**
     * The server's actual DSN, set at boot right after the database is
     * initialized. WyrdConfig.jdbcUrl() reads {@code WYRDSEKAI_JDBC_URL} /
     * {@code storage.jdbc_url}, which the installed service never sets (Main
     * derives its own URL in initializeDatabase) — so on a real node this
     * view returned an empty roster forever (home-server live-verify 2026-07-18,
     * "The crystal shows no companions on this surface" with Wyrd alive).
     */
    private static volatile String bootJdbcUrl;

    /** Server boot: hand this view the real DSN. */
    public static void setJdbcUrl(String jdbcUrl) {
        bootJdbcUrl = jdbcUrl;
    }

    public static List<Map<String, Object>> list() {
        var jdbcUrl = bootJdbcUrl != null && !bootJdbcUrl.isBlank()
            ? bootJdbcUrl : WyrdConfig.get().jdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            // Loud, not silent (2026-07-18): this blank-config path made the
            // crystal's companions view answer "no companions" on a node with
            // a living companion, indistinguishable from real emptiness.
            log.warn("companionsList: no jdbc url (boot value unset, WyrdConfig blank) — returning empty roster");
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        try (var soulStore = new SqlSoulStore(jdbcUrl)) {
            for (var m : soulStore.listLatest()) {
                var profile = m.profile();
                if (profile == null || profile.entityId() == null) continue;
                if (!"agent".equals(profile.entityType())) continue;
                var entry = new HashMap<String, Object>();
                entry.put("name", profile.name());
                entry.put("entityId", profile.entityId());
                if (m.did() != null) entry.put("did", m.did());
                if (profile.archetype() != null) entry.put("archetype", profile.archetype());
                if (m.genome() != null) {
                    entry.put("temperament",
                        GenomeProfile.temperamentOf(m.genome()).label());
                }
                var vp = m.voiceProfile();
                if (vp != null) {
                    entry.put("voiceRevision", vp.revision());
                    entry.put("voiceClauses", vp.clauses() != null ? vp.clauses().size() : 0);
                    entry.put("voiceFrozen", vp.frozen());
                }
                entry.put("relationships",
                    m.relationships() != null ? m.relationships().size() : 0);
                if (m.forgedAt() != null) entry.put("forgedAt", m.forgedAt().toString());
                var registry = EntityRegistry.get();
                if (registry != null) {
                    var room = registry.roomOf(profile.entityId()).orElse(null);
                    entry.put("online", room != null);
                    if (room != null) entry.put("room", room);
                } else {
                    entry.put("online", false);
                }
                out.add(entry);
            }
        } catch (Exception e) {
            log.warn("CompanionCodexView.list: {}", e.getMessage());
            return List.of();
        }
        return out;
    }
}
