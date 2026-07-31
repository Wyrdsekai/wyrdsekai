package org.wyrdsekai.core.study;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Registry of application aliases for the Study room.
 * Maps MUD-friendly names ("notes", "editor") to actual binaries.
 * Validates that binaries exist in PATH on startup.
 */
public class AppRegistry {

    private final Map<String, AppBinding> bindings = new ConcurrentHashMap<>();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();

    /** Register an app binding. Checks if binary exists in PATH. */
    public void register(String alias, String command, String description) {
        var binding = new AppBinding(alias, command, description);
        bindings.put(alias, binding);
        if (!isInPath(command)) {
            warnings.add(alias + " → " + command + " (not found in PATH)");
        }
    }

    /** Register from a map (e.g., parsed from HOCON config). */
    public void registerAll(Map<String, String> aliasToCommand) {
        for (var entry : aliasToCommand.entrySet()) {
            register(entry.getKey(), entry.getValue(), entry.getValue());
        }
    }

    /** Resolve an alias to its binding. */
    public Optional<AppBinding> resolve(String alias) {
        return Optional.ofNullable(bindings.get(alias));
    }

    /** Check if an alias is registered. */
    public boolean hasApp(String alias) {
        return bindings.containsKey(alias);
    }

    /** Get all registered bindings. */
    public Map<String, AppBinding> all() {
        return Map.copyOf(bindings);
    }

    /** Get warnings from registration (binaries not found in PATH). */
    public Set<String> warnings() {
        return Set.copyOf(warnings);
    }

    /** Get number of registered apps. */
    public int size() {
        return bindings.size();
    }

    /** Load bindings into a DesktopLauncher. */
    public void populateLauncher(DesktopLauncher launcher) {
        bindings.values().forEach(launcher::registerApp);
    }

    /** Check if a command exists in PATH (best-effort, platform-dependent). */
    private boolean isInPath(String command) {
        // Extract binary name (handle "libreoffice --calc" → "libreoffice")
        String binary = command.split("\\s+")[0];
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("where", binary);
            } else {
                pb = new ProcessBuilder("which", binary);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false; // Can't check — assume not found
        }
    }
}
