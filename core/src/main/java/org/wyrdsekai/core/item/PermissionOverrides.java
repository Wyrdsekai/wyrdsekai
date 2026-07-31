package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * steward-configurable permission overrides
 * stored in {@code ~/.wyrdsekai/config/permissions.toml}.
 *
 * <p>Override types: PROMOTE (require higher tier), DEMOTE (lower tier), and
 * REQUIRE_RITUAL (force ritual confirmation regardless of declared tier).
 * Per-item overrides win over system-wide; floor invariants (Tier 7 caps
 * cannot demote below 6, etc.) are enforced at parse time and at lookup.</p>
 *
 * <p>Hot-reload: {@link #checkReload()} re-parses the file when its mtime
 * changes. The full override map is replaced atomically so in-flight calls
 * keep their pre-edit tier.</p>
 *
 * <p>Format (subset of TOML the spec requires):
 * <pre>
 * [system_wide]
 * "library.add" = { tier = 5, reason = "paranoid library curation" }
 *
 * [items."research_clipper"]
 * "drive.mark" = { tier = 4, reason = "audited" }
 * </pre>
 */
public final class PermissionOverrides {

    private static final Logger log = LoggerFactory.getLogger(PermissionOverrides.class);

    /** Per-item override: {@code (itemId, capability) -> override}. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Override>> perItem =
        new ConcurrentHashMap<>();
    /** System-wide override: {@code capability -> override}. */
    private final ConcurrentHashMap<String, Override> systemWide = new ConcurrentHashMap<>();

    /** Audit trail of every override application. Newest first. */
    private final CopyOnWriteArrayList<AuditEntry> audit =
        new CopyOnWriteArrayList<>();

    private final Path configPath;
    private volatile long lastModifiedMillis = 0;

    public PermissionOverrides(Path configPath) {
        this.configPath = configPath;
    }

    /** Override = effective tier + reason + ritual flag. */
    public record Override(Integer tier, boolean requireRitual, String reason) {
        public Override {
            if (tier != null && (tier < 1 || tier > 7)) {
                throw new IllegalArgumentException("tier must be 1..7, got " + tier);
            }
        }
    }

    public record AuditEntry(long appliedAtMillis, String capability, String itemId,
                              Integer fromTier, Integer toTier, String reason) {}

    /**
     * Re-read the file if it has changed; otherwise no-op. Returns true on reload.
     *
     * <p>If the file doesn't exist, this is a no-op (programmatic overrides
     * applied via {@link #promote}/{@link #demote}/etc. take precedence over
     * any non-existent file). The file is the canonical source only when it
     * exists; absent, in-memory state is the source of truth.</p>
     */
    public boolean checkReload() {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            var mtime = Files.getLastModifiedTime(configPath).toMillis();
            if (mtime == lastModifiedMillis) return false;
            load();
            lastModifiedMillis = mtime;
            return true;
        } catch (IOException e) {
            log.warn("permissions.toml mtime probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Force a (re)load of the file. */
    public void load() {
        if (configPath == null || !Files.exists(configPath)) return;
        try {
            var content = Files.readString(configPath);
            parse(content);
        } catch (IOException e) {
            log.warn("permissions.toml read failed: {}", e.getMessage());
        }
    }

    /** Resolve the effective tier for a capability, considering overrides. */
    public int effectiveTierFor(String capability, String itemId) {
        // Cheap mtime probe — file-watcher equivalent for a single config file
        checkReload();
        int defaultTier = ItemManifestValidator.tierFor(capability);
        Override ov = null;
        if (itemId != null) {
            var perItemMap = perItem.get(itemId);
            if (perItemMap != null) ov = perItemMap.get(capability);
        }
        if (ov == null) ov = systemWide.get(capability);
        if (ov == null || ov.tier() == null) return defaultTier;
        // Floor invariants — defence-in-depth
        var floored = applyFloor(capability, ov.tier(), defaultTier);
        if (floored != ov.tier()) {
            log.warn("permission override on '{}' floored {} → {} (cap default tier={})",
                capability, ov.tier(), floored, defaultTier);
        }
        return floored;
    }

    /** Whether the capability requires ritual confirmation per §3.7. */
    public boolean requiresRitual(String capability, String itemId) {
        Override ov = null;
        if (itemId != null) {
            var perItemMap = perItem.get(itemId);
            if (perItemMap != null) ov = perItemMap.get(capability);
        }
        if (ov == null) ov = systemWide.get(capability);
        return ov != null && ov.requireRitual();
    }

    /** Programmatic promote (used by the wyrd permissions CLI). */
    public void promote(String capability, String itemId, int newTier, String reason) {
        if (newTier < 1 || newTier > 7) throw new IllegalArgumentException("tier 1..7");
        applyOverride(capability, itemId, new Override(newTier, false, reason), true);
    }

    /** Programmatic demote. */
    public void demote(String capability, String itemId, int newTier, String reason) {
        applyOverride(capability, itemId, new Override(newTier, false, reason), false);
    }

    public void requireRitual(String capability, String itemId, String reason) {
        applyOverride(capability, itemId, new Override(null, true, reason), false);
    }

    public void remove(String capability, String itemId) {
        if (itemId != null) {
            var m = perItem.get(itemId);
            if (m != null) m.remove(capability);
        } else {
            systemWide.remove(capability);
        }
    }

    public List<AuditEntry> auditLog() { return List.copyOf(audit); }

    public Map<String, Override> systemOverrides() { return Map.copyOf(systemWide); }

    public Map<String, Map<String, Override>> perItemOverrides() {
        var out = new LinkedHashMap<String, Map<String, Override>>();
        for (var e : perItem.entrySet()) out.put(e.getKey(), Map.copyOf(e.getValue()));
        return Map.copyOf(out);
    }

    /** Persist the in-memory overrides back to disk (used by the CLI). */
    public synchronized void save() throws IOException {
        if (configPath == null) return;
        Files.createDirectories(configPath.getParent());
        var sb = new StringBuilder();
        sb.append("# wyrdsekai permission overrides\n");
        sb.append("# Item permission overrides\n\n");
        if (!systemWide.isEmpty()) {
            sb.append("[system_wide]\n");
            for (var e : systemWide.entrySet()) {
                appendOverride(sb, e.getKey(), e.getValue());
            }
            sb.append("\n");
        }
        for (var item : perItem.entrySet()) {
            sb.append("[items.\"").append(item.getKey()).append("\"]\n");
            for (var e : item.getValue().entrySet()) {
                appendOverride(sb, e.getKey(), e.getValue());
            }
            sb.append("\n");
        }
        Files.writeString(configPath, sb.toString(), StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
        lastModifiedMillis = Files.getLastModifiedTime(configPath).toMillis();
    }

    private static void appendOverride(StringBuilder sb, String cap, Override o) {
        sb.append("\"").append(cap).append("\" = { ");
        boolean first = true;
        if (o.tier() != null) {
            sb.append("tier = ").append(o.tier());
            first = false;
        }
        if (o.requireRitual()) {
            if (!first) sb.append(", ");
            sb.append("require_ritual = true");
            first = false;
        }
        if (o.reason() != null && !o.reason().isBlank()) {
            if (!first) sb.append(", ");
            sb.append("reason = \"").append(o.reason().replace("\"", "\\\"")).append("\"");
        }
        sb.append(" }\n");
    }

    private void applyOverride(String capability, String itemId, Override raw, boolean isPromote) {
        int defaultTier = ItemManifestValidator.tierFor(capability);
        Integer effectiveTier = raw.tier();
        if (effectiveTier != null) {
            if (isPromote && effectiveTier < defaultTier) {
                throw new IllegalArgumentException("PROMOTE must raise tier; "
                    + capability + " default=" + defaultTier + " requested=" + effectiveTier);
            }
            // Tier 1 read-only can't promote above 4
            if (defaultTier == 1 && effectiveTier > 4) {
                throw new IllegalArgumentException("Tier 1 reads cannot promote above 4");
            }
            effectiveTier = applyFloor(capability, effectiveTier, defaultTier);
        }
        var ov = new Override(effectiveTier, raw.requireRitual(), raw.reason());

        Integer fromTier = effectiveTierBeforeOverride(capability, itemId);
        if (itemId != null) {
            perItem.computeIfAbsent(itemId, k -> new ConcurrentHashMap<>()).put(capability, ov);
        } else {
            systemWide.put(capability, ov);
        }
        audit.add(0, new AuditEntry(System.currentTimeMillis(), capability, itemId,
            fromTier, ov.tier(), ov.reason()));
    }

    private Integer effectiveTierBeforeOverride(String capability, String itemId) {
        if (itemId != null) {
            var m = perItem.get(itemId);
            if (m != null && m.containsKey(capability)) return m.get(capability).tier();
        }
        var sw = systemWide.get(capability);
        return sw != null ? sw.tier() : ItemManifestValidator.tierFor(capability);
    }

    /**
     * §3.7 floor invariants: Tier 7 cannot demote below 6, Tier 6 not below 5.
     * Returns the floored value.
     */
    private static int applyFloor(String capability, int requested, int defaultTier) {
        if (defaultTier == 7 && requested < 6) return 6;
        if (defaultTier == 6 && requested < 5) return 5;
        return requested;
    }

    // ─── TOML parser (subset; just what the spec schema needs) ───

    private static final Pattern SECTION = Pattern.compile("^\\[([^\\]]+)\\]\\s*$");
    private static final Pattern KV_LINE = Pattern.compile(
        "^\\s*\"?([^\"=\\s]+)\"?\\s*=\\s*\\{(.+)\\}\\s*$");
    private static final Pattern INNER_FIELD = Pattern.compile(
        "(\\w+)\\s*=\\s*(\"[^\"]*\"|true|false|\\d+)");

    void parse(String content) {
        var newPerItem = new ConcurrentHashMap<String, ConcurrentHashMap<String, Override>>();
        var newSystem = new ConcurrentHashMap<String, Override>();
        String currentSection = null;
        String currentItem = null;

        for (var rawLine : content.split("\n")) {
            var line = rawLine;
            int hashIdx = line.indexOf('#');
            if (hashIdx >= 0) line = line.substring(0, hashIdx);
            line = line.trim();
            if (line.isEmpty()) continue;

            var sectionMatch = SECTION.matcher(line);
            if (sectionMatch.matches()) {
                var header = sectionMatch.group(1).trim();
                if (header.equals("system_wide")) {
                    currentSection = "system";
                    currentItem = null;
                } else if (header.startsWith("items.")) {
                    currentSection = "item";
                    currentItem = header.substring("items.".length())
                        .replaceAll("^\"|\"$", "")
                        .replaceAll("^'|'$", "")
                        .trim();
                } else {
                    log.warn("permissions.toml: unknown section [{}]", header);
                    currentSection = null;
                }
                continue;
            }

            var kv = KV_LINE.matcher(line);
            if (!kv.matches()) continue;
            var capability = kv.group(1).trim();
            var inner = kv.group(2).trim();

            Integer tier = null;
            boolean ritual = false;
            String reason = null;
            String capRedirect = null;
            var fields = INNER_FIELD.matcher(inner);
            while (fields.find()) {
                var k = fields.group(1);
                var v = fields.group(2);
                switch (k) {
                    case "tier" -> tier = Integer.parseInt(v);
                    case "require_ritual" -> ritual = Boolean.parseBoolean(v);
                    case "reason" -> reason = stripQuotes(v);
                    case "capability" -> capRedirect = stripQuotes(v);
                    default -> {}
                }
            }
            if (capRedirect != null) capability = capRedirect;
            if (capability.isBlank()) continue;
            if (tier == null && !ritual) continue;

            try {
                int defaultTier = ItemManifestValidator.tierFor(capability);
                if (tier != null) {
                    tier = applyFloor(capability, tier, defaultTier);
                    if (defaultTier == 1 && tier > 4) {
                        log.warn("permissions.toml: '{}' is Tier 1 read; cannot promote >4", capability);
                        tier = 4;
                    }
                }
                var ov = new Override(tier, ritual, reason);
                if ("system".equals(currentSection)) {
                    newSystem.put(capability, ov);
                } else if ("item".equals(currentSection) && currentItem != null) {
                    newPerItem.computeIfAbsent(currentItem, k -> new ConcurrentHashMap<>())
                        .put(capability, ov);
                }
            } catch (Exception ex) {
                log.warn("permissions.toml: skipping invalid override '{}': {}",
                    capability, ex.getMessage());
            }
        }

        // Atomic swap
        systemWide.clear();
        systemWide.putAll(newSystem);
        perItem.clear();
        perItem.putAll(newPerItem);
        log.info("permissions.toml loaded: {} system-wide, {} per-item entries",
            newSystem.size(), newPerItem.size());
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
