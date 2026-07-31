package org.wyrdsekai.core.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Steward-allowlisted surface for acting on the host OS — the consumer for
 * the {@code app_launch} / {@code file_open} / {@code url_open} command
 * events that Study room scripts have always emitted (WorldApi.launchApp /
 * launchFile / launchUrl) and the backing for the {@code world.host} item
 * namespace. Before this existed those emits ended in a debug log.
 *
 * <p>Nothing here runs arbitrary commands. Every action is gated on
 * steward configuration:</p>
 * <ul>
 *   <li>{@code WYRDSEKAI_HOST_APPS} / profile {@code host.apps} —
 *       {@code "alias=command args;alias2=command2"}. Only a configured
 *       alias can be launched; the spoken/scripted side never supplies
 *       the command line.</li>
 *   <li>{@code WYRDSEKAI_HOST_OPEN_ROOTS} / profile {@code host.open_roots}
 *       — {@link File#pathSeparator}-separated directory roots. Files can
 *       only be opened beneath a configured root (real-path checked, so
 *       symlink/.. traversal can't escape).</li>
 *   <li>URLs: http/https only, opened with the platform opener.</li>
 * </ul>
 *
 * <p>Every attempt — allowed or refused — is audit-logged with the actor.
 * Results are Maps for the item API; {@link #handle} renders them as
 * localized room narration (keys {@code host.*} in scripts/i18n).</p>
 */
public final class HostActionService {

    private static final Logger log = LoggerFactory.getLogger(HostActionService.class);
    private static final Set<String> VERBS = Set.of("app_launch", "file_open", "url_open");
    private static final int MAX_FIND_DEPTH = 12;

    private HostActionService() {}

    /** True when {@code verb} is a host-action room command. */
    public static boolean canHandle(String verb) {
        return verb != null && VERBS.contains(verb);
    }

    /**
     * Room-command entry point (RoomActor routes here). Returns localized
     * narration lines for the room.
     */
    public static List<String> handle(String verb, String target, String actorId) {
        var result = switch (verb) {
            case "app_launch" -> launchApp(target, actorId);
            case "file_open" -> openFile(target, actorId);
            case "url_open" -> openUrl(target, actorId);
            default -> Map.<String, Object>of("ok", false, "error", "unknown_verb");
        };
        return narrate(verb, target, result);
    }

    // ─── Primitives (item-API shape: {ok, error?, ...}) ────────────────

    /**
     * Launch a configured application by alias. The command line comes from
     * the steward's allowlist, never from the caller.
     */
    public static Map<String, Object> launchApp(String alias, String actorId) {
        var apps = configuredApps();
        if (apps.isEmpty()) {
            return audit(actorId, "app_launch", alias, Map.of(
                "ok", false, "error", "none_configured"));
        }
        if (alias == null || alias.isBlank() || !apps.containsKey(alias.trim().toLowerCase(Locale.ROOT))) {
            return audit(actorId, "app_launch", alias, Map.of(
                "ok", false, "error", "not_allowlisted",
                "apps", List.copyOf(apps.keySet())));
        }
        var cmd = apps.get(alias.trim().toLowerCase(Locale.ROOT));
        try {
            spawnDetached(tokenize(cmd));
            return audit(actorId, "app_launch", alias, Map.of("ok", true, "alias", alias.trim()));
        } catch (Exception e) {
            return audit(actorId, "app_launch", alias, Map.of(
                "ok", false, "error", "launch_failed", "detail", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Open a file with the platform opener. The file must resolve (real
     * path) beneath a configured open-root.
     */
    public static Map<String, Object> openFile(String path, String actorId) {
        var roots = configuredOpenRoots();
        if (roots.isEmpty()) {
            return audit(actorId, "file_open", path, Map.of(
                "ok", false, "error", "no_roots"));
        }
        if (path == null || path.isBlank()) {
            return audit(actorId, "file_open", path, Map.of(
                "ok", false, "error", "missing", "path", ""));
        }
        Path real;
        try {
            real = Path.of(path.trim()).toRealPath();
        } catch (IOException e) {
            return audit(actorId, "file_open", path, Map.of(
                "ok", false, "error", "missing", "path", path.trim()));
        }
        var inside = false;
        for (var root : roots) {
            if (real.startsWith(root)) { inside = true; break; }
        }
        if (!inside) {
            return audit(actorId, "file_open", path, Map.of(
                "ok", false, "error", "outside_roots", "path", real.toString()));
        }
        try {
            spawnDetached(openerCommand(real.toString()));
            return audit(actorId, "file_open", path, Map.of("ok", true, "path", real.toString()));
        } catch (Exception e) {
            return audit(actorId, "file_open", path, Map.of(
                "ok", false, "error", "open_failed", "detail", String.valueOf(e.getMessage())));
        }
    }

    /** Open an http/https URL with the platform opener. */
    public static Map<String, Object> openUrl(String url, String actorId) {
        if (url == null || url.isBlank()
                || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return audit(actorId, "url_open", url, Map.of(
                "ok", false, "error", "bad_scheme", "url", url == null ? "" : url.trim()));
        }
        try {
            spawnDetached(openerCommand(url.trim()));
            return audit(actorId, "url_open", url, Map.of("ok", true, "url", url.trim()));
        } catch (Exception e) {
            return audit(actorId, "url_open", url, Map.of(
                "ok", false, "error", "open_failed", "detail", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Find files under the steward's open-roots — the agent-facing,
     * READ-ONLY counterpart to {@link #openFile}. {@code pattern} is a
     * filename glob ({@code *.epub}) or, with path separators, a relative
     * glob ({@code books/**}); a bare word matches as a case-insensitive
     * substring of the filename. Never escapes the granted roots; capped
     * at {@code maxResults}; every call is audit-logged.
     */
    public static Map<String, Object> findFiles(String pattern, int maxResults, String actorId) {
        return findFiles(configuredOpenRoots(), pattern, maxResults, actorId);
    }

    static Map<String, Object> findFiles(List<Path> roots, String pattern,
                                          int maxResults, String actorId) {
        if (roots.isEmpty()) {
            return audit(actorId, "file_find", pattern, Map.of(
                "ok", false, "error", "no_roots"));
        }
        if (pattern == null || pattern.isBlank()) {
            return audit(actorId, "file_find", pattern, Map.of(
                "ok", false, "error", "missing", "path", ""));
        }
        var limit = Math.max(1, Math.min(maxResults, 500));
        var needle = pattern.trim();
        var hasGlob = needle.indexOf('*') >= 0 || needle.indexOf('?') >= 0;
        var onPath = needle.indexOf('/') >= 0;
        var matcher = hasGlob
            ? FileSystems.getDefault().getPathMatcher("glob:" + (onPath ? needle : "**/" + needle))
            : null;
        var lowered = needle.toLowerCase(Locale.ROOT);

        var matches = new ArrayList<String>();
        var truncated = false;
        for (var root : roots) {
            if (matches.size() >= limit) break;
            try (var walk = Files.walk(root, MAX_FIND_DEPTH)) {
                var found = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> matcher != null
                        ? matcher.matches(root.relativize(p))
                        : p.getFileName().toString().toLowerCase(Locale.ROOT).contains(lowered))
                    .limit((long) limit - matches.size() + 1)
                    .map(Path::toString)
                    .toList();
                for (var f : found) {
                    if (matches.size() >= limit) { truncated = true; break; }
                    matches.add(f);
                }
            } catch (IOException e) {
                log.warn("host: find walk failed under {}: {}", root, e.getMessage());
            }
        }
        return audit(actorId, "file_find", pattern, Map.of(
            "ok", true, "matches", matches, "truncated", truncated));
    }

    /** Aliases the steward has allowlisted (introspection; no secrets — commands stay private). */
    public static List<String> allowedApps() {
        return List.copyOf(configuredApps().keySet());
    }

    /** Configured open-roots as strings (introspection for config hints). */
    public static List<String> openRoots() {
        return configuredOpenRoots().stream().map(Path::toString).toList();
    }

    // ─── Config parsing ────────────────────────────────────────────────

    private static Map<String, String> configuredApps() {
        var raw = WyrdConfig.get().resolve("WYRDSEKAI_HOST_APPS", "host.apps", () -> null);
        var apps = new LinkedHashMap<String, String>();
        if (raw == null || raw.isBlank()) return apps;
        for (var pair : raw.split(";")) {
            var eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) continue;
            var alias = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            var cmd = pair.substring(eq + 1).trim();
            if (!alias.isEmpty() && !cmd.isEmpty()) apps.put(alias, cmd);
        }
        return apps;
    }

    private static List<Path> configuredOpenRoots() {
        var raw = WyrdConfig.get().resolve("WYRDSEKAI_HOST_OPEN_ROOTS", "host.open_roots", () -> null);
        var roots = new ArrayList<Path>();
        if (raw == null || raw.isBlank()) return roots;
        for (var part : raw.split(File.pathSeparator)) {
            if (part.isBlank()) continue;
            try {
                var p = Path.of(part.trim()).toRealPath();
                if (Files.isDirectory(p)) roots.add(p);
            } catch (IOException e) {
                log.warn("host: configured open-root '{}' does not resolve: {}", part, e.getMessage());
            }
        }
        return roots;
    }

    // ─── Process plumbing ──────────────────────────────────────────────

    private static List<String> openerCommand(String target) {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return List.of("open", target);
        if (os.contains("win")) return List.of("cmd", "/c", "start", "", target);
        return List.of("xdg-open", target);
    }

    private static void spawnDetached(List<String> command) throws IOException {
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    /** Whitespace tokenizer with double-quote grouping for allowlist commands. */
    static List<String> tokenize(String cmd) {
        var tokens = new ArrayList<String>();
        var sb = new StringBuilder();
        var quoted = false;
        for (var ch : cmd.toCharArray()) {
            if (ch == '"') { quoted = !quoted; continue; }
            if (!quoted && Character.isWhitespace(ch)) {
                if (sb.length() > 0) { tokens.add(sb.toString()); sb.setLength(0); }
            } else {
                sb.append(ch);
            }
        }
        if (sb.length() > 0) tokens.add(sb.toString());
        return tokens;
    }

    private static Map<String, Object> audit(String actorId, String verb, String target,
                                             Map<String, Object> result) {
        log.info("[host-audit] actor={} verb={} target='{}' ok={} error={}",
            actorId, verb, target, result.get("ok"), result.getOrDefault("error", "-"));
        return result;
    }

    // ─── Narration (room surface) ──────────────────────────────────────

    private static List<String> narrate(String verb, String target, Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return List.of(switch (verb) {
                case "app_launch" -> t("host.launch.ok", result.get("alias"));
                case "file_open" -> t("host.open.ok", result.get("path"));
                default -> t("host.url.ok", result.get("url"));
            });
        }
        var error = String.valueOf(result.get("error"));
        return List.of(switch (error) {
            case "none_configured" -> t("host.launch.none_configured");
            case "not_allowlisted" -> t("host.launch.not_allowlisted",
                target == null ? "" : target,
                String.join(", ", allowedApps()));
            case "launch_failed" -> t("host.launch.failed", result.get("detail"));
            case "no_roots" -> t("host.open.no_roots");
            case "missing" -> t("host.open.missing", result.get("path"));
            case "outside_roots" -> t("host.open.outside_roots", result.get("path"));
            case "bad_scheme" -> t("host.url.bad_scheme", result.get("url"));
            default -> t("host.open.failed", result.getOrDefault("detail", error));
        });
    }

    private static String t(String key, Object... args) {
        var catalog = ScriptMessageCatalog.forLang(I18n.getLocale().getLanguage());
        return args.length == 0 ? catalog.get(key) : catalog.get(key, args);
    }
}
