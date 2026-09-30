package org.wyrdsekai.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The environment a program the daemon starts is given: only what that path needs.
 *
 * <p>{@code new ProcessBuilder(...)} hands the child the daemon's whole environment:
 * {@code WYRDSEKAI_CRED_*}, {@code WYRDSEKAI_MCP_KEY_*}, cloud keys, {@code SSH_AUTH_SOCK}.
 * Every path that starts a program on the household's behalf (a skill's script, a recipe's
 * shell step, an MCP server, keybase, signal-cli, the Claude CLI) clears it and keeps
 * {@link #BASE} plus the names that path declares. Coding backends and CLI skills use
 * {@code EgressGate}, which does the same with its own list.
 *
 * <p>{@code WYRDSEKAI_SUBPROCESS_FULL_ENV=true} restores full inheritance for every path
 * here (transition setting; logged as a warning).
 */
public final class SubprocessEnv {

    private static final Logger log = LoggerFactory.getLogger(SubprocessEnv.class);

    /** Transition setting: when true, subprocesses inherit the daemon's whole environment again. */
    public static final String FULL_ENV_SETTING = "WYRDSEKAI_SUBPROCESS_FULL_ENV";

    /**
     * What any program needs to start and behave: finding binaries, who and where it is,
     * locale, time zone, temp dir, terminal, proxy and CA settings, and the Windows
     * variables a process cannot start without. No credential.
     */
    public static final Set<String> BASE = Set.of(
        "PATH", "HOME", "USER", "LOGNAME", "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "LC_MESSAGES",
        "TZ", "TMPDIR", "TERM",
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
        "SSL_CERT_FILE", "SSL_CERT_DIR",
        "SystemRoot", "windir", "ComSpec", "PATHEXT", "TEMP", "TMP", "USERPROFILE",
        "APPDATA", "LOCALAPPDATA", "ProgramData", "ProgramFiles", "HOMEDRIVE", "HOMEPATH");

    private static final List<String> SECRET_WORDS = List.of(
        "KEY", "TOKEN", "SECRET", "PASS", "CRED", "AUTH", "SEED", "COOKIE", "SESSION");

    private static volatile boolean warned;

    private final Set<String> names;
    private final List<String> prefixes;
    private final Function<String, String> settings;

    private SubprocessEnv(Set<String> names, List<String> prefixes, Function<String, String> settings) {
        this.names = names;
        this.prefixes = prefixes;
        this.settings = settings;
    }

    /** {@link #BASE} plus the names this path needs. */
    public static SubprocessEnv of(String... needed) {
        var all = new LinkedHashSet<>(BASE);
        for (var n : needed) if (n != null && !n.isBlank()) all.add(n);
        return new SubprocessEnv(Set.copyOf(all), List.of(), System::getenv);
    }

    /**
     * Also keep every variable whose name starts with {@code prefix}, unless its name
     * says it holds a secret ({@link #secretShaped}).
     */
    public SubprocessEnv withPrefix(String prefix) {
        var p = new ArrayList<>(prefixes);
        p.add(prefix.toUpperCase(Locale.ROOT));
        return new SubprocessEnv(names, List.copyOf(p), settings);
    }

    /** The same list reading the transition setting from {@code settings} (tests). */
    SubprocessEnv withSettings(Function<String, String> settings) {
        return new SubprocessEnv(names, prefixes, settings);
    }

    /** Whether a variable of this name is passed. */
    public boolean allows(String name) {
        if (name == null) return false;
        for (var n : names) if (n.equalsIgnoreCase(name)) return true;
        var upper = name.toUpperCase(Locale.ROOT);
        for (var p : prefixes) if (upper.startsWith(p) && !secretShaped(upper)) return true;
        return false;
    }

    /** A name that says it holds a secret: never passed by a prefix rule. */
    public static boolean secretShaped(String name) {
        var upper = name.toUpperCase(Locale.ROOT);
        for (var w : SECRET_WORDS) if (upper.contains(w)) return true;
        return false;
    }

    /**
     * Reduce {@code env} (a {@link ProcessBuilder#environment()}, which starts as a copy of
     * the daemon's) to what this path allows.
     */
    public void apply(Map<String, String> env) {
        if (env == null) return;
        if ("true".equalsIgnoreCase(String.valueOf(settings.apply(FULL_ENV_SETTING)).strip())) {
            if (!warned) {
                warned = true;
                log.warn("{}=true: programs the daemon starts inherit its whole environment, "
                    + "credentials included. Remove the setting to give each only what it needs.",
                    FULL_ENV_SETTING);
            }
            return;
        }
        var keep = new HashMap<String, String>();
        // get() by name first: on Windows the environment map ignores case ("Path").
        for (var n : names) {
            var v = env.get(n);
            if (v != null) keep.put(n, v);
        }
        if (!prefixes.isEmpty()) {
            for (var e : env.entrySet()) {
                if (allows(e.getKey())) keep.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        env.clear();
        env.putAll(keep);
    }

    /** A process builder for {@code command} with this environment. */
    public ProcessBuilder builder(List<String> command) {
        var pb = new ProcessBuilder(command);
        apply(pb.environment());
        return pb;
    }

    /** A process builder for {@code command} with this environment. */
    public ProcessBuilder builder(String... command) {
        return builder(List.of(command));
    }
}
