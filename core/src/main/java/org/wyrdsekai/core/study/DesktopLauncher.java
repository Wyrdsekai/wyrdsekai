package org.wyrdsekai.core.study;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * Abstraction over platform-specific desktop app launching.
 * Only available in the Study room for human sessions.
 * Agents NEVER invoke this — only human commands trigger launches.
 */
public interface DesktopLauncher {

    /** Open a file with its default application. */
    LaunchResult openFile(Path file);

    /** Open a named application by registered alias. */
    LaunchResult openApp(String alias, String... args);

    /** Open a URL in the default browser. */
    LaunchResult openUrl(URI url);

    /** Check if a GUI session is available (not SSH, not headless). */
    boolean isGuiAvailable();

    /** Get all registered app bindings. */
    Map<String, AppBinding> registeredApps();

    /** Register an app binding. */
    void registerApp(AppBinding binding);

    /** Auto-detect the current platform and return the right launcher. */
    static DesktopLauncher detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) return new LinuxDesktopLauncher();
        if (os.contains("mac"))   return new MacOsDesktopLauncher();
        if (os.contains("win"))   return new WindowsDesktopLauncher();
        return new NoOpDesktopLauncher();
    }
}
