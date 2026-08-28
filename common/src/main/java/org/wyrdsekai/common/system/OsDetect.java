package org.wyrdsekai.common.system;

import java.util.Locale;

/**
 * OS detection utility. Cached from {@code os.name} system property.
 * Mirrors CodeZaiku's OsDetect pattern for cross-platform support.
 */
public final class OsDetect {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private OsDetect() {}

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isLinux()   { return OS.contains("linux"); }
    public static boolean isMacOS()   { return OS.contains("mac") || OS.contains("darwin"); }

    /**
     * Shell command wrapper: {@code cmd /c} on Windows, {@code sh -c} elsewhere.
     */
    public static String[] shellCommand(String cmd) {
        return isWindows()
            ? new String[]{"cmd", "/c", cmd}
            : new String[]{"sh", "-c", cmd};
    }

    /**
     * Binary discovery: {@code where.exe} on Windows, {@code which} elsewhere.
     */
    public static String[] findCommand(String binary) {
        return isWindows()
            ? new String[]{"where.exe", binary}
            : new String[]{"which", binary};
    }
}
