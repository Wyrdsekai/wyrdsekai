package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.HotReloadableConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides desktop context awareness: active window title and app categorization.
 * Cross-platform: Linux (xdotool), macOS (osascript), Windows (PowerShell).
 * Singleton pattern.
 *
 * <p>Privacy model: always checks {@link ContextAccessManager} before providing
 * data to an agent. If the current app is in the agent's allowed scope, returns
 * the full window title. Otherwise returns only the category ("coding", "browsing", etc.).</p>
 *
 * @see ContextAccessManager
 * @see ContextPermission
 */
public class DesktopContextProvider {

    private static final Logger log = LoggerFactory.getLogger(DesktopContextProvider.class);

    /** Global singleton instance. */
    private static volatile DesktopContextProvider instance;

    private HotReloadableConfig<Map<String, List<String>>> customCategoriesConfig;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() {
        instance = new DesktopContextProvider();
        instance.loadDefaults();
        var path = Path.of(System.getProperty("user.home"),
            ".wyrdsekai", "app-categories.properties");
        instance.customCategoriesConfig = new HotReloadableConfig<>(
            path, instance::loadCustomCategoriesFromFile, Map.of());
        // Trigger initial load
        instance.applyCustomCategories();
    }

    /** Get the global instance. May be null if not initialized. */
    public static DesktopContextProvider get() { return instance; }

    /**
     * Apply custom categories from the hot-reloadable config.
     * Merges loaded custom categories into the existing keyword map.
     */
    private void applyCustomCategories() {
        if (customCategoriesConfig == null) return;
        var custom = customCategoriesConfig.get();
        for (var entry : custom.entrySet()) {
            var existing = categoryKeywords.getOrDefault(entry.getKey(), List.of());
            var merged = new ArrayList<>(existing);
            for (var kw : entry.getValue()) {
                if (!merged.contains(kw)) merged.add(kw);
            }
            categoryKeywords.put(entry.getKey(), List.copyOf(merged));
        }
    }

    /**
     * Load custom categories from a properties file and return as a map.
     * Format: category=keyword1,keyword2,keyword3
     */
    private Map<String, List<String>> loadCustomCategoriesFromFile(Path path) {
        var result = new LinkedHashMap<String, List<String>>();
        try {
            if (path != null && Files.exists(path)) {
                var props = new Properties();
                try (var reader = Files.newBufferedReader(path)) {
                    props.load(reader);
                }
                props.forEach((key, value) -> {
                    var keywords = Arrays.stream(((String) value).split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
                    if (!keywords.isEmpty()) {
                        result.put((String) key, keywords);
                    }
                });
                log.info("Loaded custom app categories from {}", path);
            }
        } catch (Exception e) {
            log.warn("Failed to load custom app categories from {}: {}", path, e.getMessage());
        }
        return result;
    }

    /** Reset for testing. */
    static void reset() { instance = null; }

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    /**
     * Get the active window title. Cross-platform:
     * <ul>
     *   <li>Linux: {@code xdotool getactivewindow getwindowname}</li>
     *   <li>macOS: {@code osascript -e 'tell application "System Events" to get name of first process whose frontmost is true'}</li>
     *   <li>Windows: PowerShell {@code (Get-Process | Where-Object {$_.MainWindowHandle -ne 0} | Where-Object {$_.MainWindowTitle -ne ""} | Select-Object -First 1).MainWindowTitle}</li>
     * </ul>
     *
     * @return Active window title, or empty if unavailable
     */
    public Optional<String> getActiveWindowTitle() {
        try {
            ProcessBuilder pb;
            if (OS.contains("linux")) {
                pb = new ProcessBuilder("xdotool", "getactivewindow", "getwindowname");
            } else if (OS.contains("mac") || OS.contains("darwin")) {
                pb = new ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to get name of first process whose frontmost is true");
            } else if (OS.contains("win")) {
                pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process | Where-Object {$_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -ne ''} | Select-Object -First 1).MainWindowTitle");
            } else {
                return Optional.empty();
            }
            var process = pb.redirectErrorStream(true).start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String title = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && title != null && !title.isBlank()) {
                    return Optional.of(title.strip());
                }
            }
        } catch (Exception e) {
            log.debug("Active window detection not available: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Category definitions: keyword → category. Loaded from config file or defaults.
     * The steward can customize by editing {@code ~/.wyrdsekai/app-categories.properties}
     * (format: category=keyword1,keyword2,keyword3).
     */
    private final Map<String, List<String>> categoryKeywords = new LinkedHashMap<>();

    private void loadDefaults() {
        addCategory("coding", "code", "vim", "nvim", "neovim", "idea", "intellij", "pycharm",
            "webstorm", "eclipse", "emacs", "sublime", "cursor", "android studio", "xcode",
            "visual studio", "rider", "fleet", "zed", "atom");
        addCategory("terminal", "terminal", "konsole", "alacritty", "kitty", "wezterm",
            "gnome-terminal", "xterm", "tmux", "foot", "iterm", "powershell", "cmd.exe",
            "windows terminal", "warp");
        addCategory("browsing", "firefox", "chrome", "chromium", "brave", "safari", "edge",
            "vivaldi", "opera", "arc");
        addCategory("meeting", "zoom", "meet", "teams", "webex", "discord", "jitsi", "facetime");
        addCategory("communication", "slack", "signal", "telegram", "whatsapp", "element",
            "thunderbird", "mail", "outlook", "messages", "line", "kakaotalk");
        addCategory("media", "spotify", "vlc", "mpv", "youtube", "netflix", "gimp", "krita",
            "blender", "obs", "davinci", "premiere", "photos", "music", "itunes");
        addCategory("writing", "libreoffice", "writer", "docs", "obsidian", "notion", "logseq",
            "typora", "word", "pages", "scrivener", "bear");
    }

    private void addCategory(String category, String... keywords) {
        categoryKeywords.put(category, List.of(keywords));
    }

    /**
     * Load custom category definitions from a properties file.
     * Format: category=keyword1,keyword2,keyword3
     * Custom definitions MERGE with defaults (don't replace).
     */
    public void loadCustomCategories(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                var props = new Properties();
                try (var reader = Files.newBufferedReader(path)) {
                    props.load(reader);
                }
                props.forEach((key, value) -> {
                    var keywords = Arrays.stream(((String) value).split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
                    if (!keywords.isEmpty()) {
                        var existing = categoryKeywords.getOrDefault((String) key, List.of());
                        var merged = new ArrayList<>(existing);
                        keywords.forEach(kw -> { if (!merged.contains(kw)) merged.add(kw); });
                        categoryKeywords.put((String) key, List.copyOf(merged));
                    }
                });
                log.info("Loaded custom app categories from {}", path);
            }
        } catch (Exception e) {
            log.warn("Failed to load custom app categories from {}: {}", path, e.getMessage());
        }
    }

    /**
     * Categorize a window title into a high-level activity category.
     * Uses configurable keyword matching. Categories are checked in order;
     * first match wins. Unknown apps return "other".
     *
     * @param title Window title (case-insensitive matching)
     * @return Category string
     */
    public String categorize(String title) {
        if (title == null || title.isBlank()) return "other";
        applyCustomCategories();
        String lower = title.toLowerCase();

        for (var entry : categoryKeywords.entrySet()) {
            for (var keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }

        return "other";
    }

    /**
     * Get context for an agent, respecting permissions.
     * If the agent has "active_window" access and the current app is in scope, returns the title.
     * If the app is NOT in scope, returns only the category.
     * If the agent has no "active_window" access, returns empty.
     *
     * @param agentId     Agent entity ID
     * @param accessMgr   Context access manager (non-null)
     * @return Context string, or empty if not permitted or unavailable
     */
    public Optional<String> getContext(String agentId, ContextAccessManager accessMgr) {
        if (accessMgr == null || !accessMgr.isGranted(agentId, "active_window")) {
            return Optional.empty();
        }

        var title = getActiveWindowTitle();
        if (title.isEmpty()) return Optional.empty();

        var scope = accessMgr.getScope(agentId, "active_window");
        String category = categorize(title.get());

        // If scope is "all" or empty (unrestricted), return full title
        if (scope.isEmpty() || "all".equals(scope.get()) || scope.get().isBlank()) {
            return Optional.of("Human is using: " + title.get() + " (" + category + ")");
        }

        // Check if the current app is in the allowed scope
        var allowedApps = Set.of(scope.get().split(","));
        String lowerTitle = title.get().toLowerCase();
        boolean inScope = allowedApps.stream()
            .map(String::strip)
            .map(String::toLowerCase)
            .anyMatch(app -> lowerTitle.contains(app) || category.equals(app));

        if (inScope) {
            return Optional.of("Human is using: " + title.get() + " (" + category + ")");
        } else {
            // Category-only fallback: don't reveal specific app title
            return Optional.of("Human is using: " + category);
        }
    }

    /**
     * Check if desktop context is available on this platform.
     * Linux: checks for xdotool. macOS: osascript always available. Windows: PowerShell always available.
     * Not available on headless servers or phones.
     */
    public boolean isAvailable() {
        try {
            if (OS.contains("linux")) {
                var process = new ProcessBuilder("which", "xdotool")
                    .redirectErrorStream(true).start();
                return process.waitFor() == 0;
            } else if (OS.contains("mac") || OS.contains("darwin")) {
                // osascript is always available on macOS
                return true;
            } else if (OS.contains("win")) {
                // PowerShell is always available on modern Windows
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
