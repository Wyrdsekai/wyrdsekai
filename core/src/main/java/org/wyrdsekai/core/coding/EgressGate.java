package org.wyrdsekai.core.coding;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * the coding-backend egress gate.
 *
 * <p>Every subprocess coding backend (Goose today; OpenClaw / OpenCode / Codex
 * as adapters land) spawns a real OS process with a real shell. Left ungated,
 * {@code new ProcessBuilder(argv).environment()} inherits the <em>full JVM
 * environment</em> — {@code SSH_AUTH_SOCK}, ambient keys, cloud tokens — and an
 * agent that dispatches {@code "run: ssh host …"} executes it with whatever
 * credentials the daemon process holds. We already closed bare-shell in
 * agent-authored recipes ({@code AuthoredRecipeValidator}); this is the same
 * fence, one gate over next door.</p>
 *
 * <p><b>Enforcing by default</b> (operator 2026-07-02). When enabled the gate
 * SCRUBS the subprocess environment: it clears the inherited parent env and
 * re-adds only an explicit minimal allowlist (PATH/HOME/LANG + the
 * OPENAI_HOST/key the backend needs to reach the LOCAL llama-server), then
 * layers the backend's own resolved env on top. Ambient credentials are
 * dropped, so a prompt-injected agent cannot pivot with the steward's keys.
 * Plain outbound HTTP is NOT walled — the gate's value is credential-scrubbing
 * + no key-backed lateral movement, not blocking the web (which agents already
 * reach via web_search). The hard OS-enforced form (netns/nftables) is the
 * phase-2 follow-up; this is the phase-1 env scrub.</p>
 *
 * <p>The gate is deliberately backend-agnostic — one shared knob at
 * {@code wyrdsekai.coding.egress-gate}, so every subprocess adapter that routes
 * its {@link java.lang.ProcessBuilder} through {@link #applyEnv} inherits it.</p>
 */
public final class EgressGate {

    private static final Logger log = LoggerFactory.getLogger(EgressGate.class);

    /** Config root for the shared gate. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.egress-gate";

    /**
     * Vars re-admitted from the parent env when the gate is enforcing. Keeps a
     * subprocess runnable (PATH to find its binary, HOME for config, locale)
     * and lets the local-llama-server routing survive (OPENAI_HOST/KEY are also
     * re-supplied by the backend's own env, but allowlisting them is harmless).
     * Deliberately EXCLUDES SSH_AUTH_SOCK and every *_KEY / *_TOKEN / *_SECRET.
     */
    public static final Set<String> DEFAULT_ENV_ALLOWLIST = Set.of(
        "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TZ", "TERM",
        "OPENAI_HOST", "OPENAI_API_KEY", "GOOSE_PROVIDER", "GOOSE_MODEL");

    private final boolean enabled;
    private final Set<String> envAllowlist;

    EgressGate(boolean enabled, Set<String> envAllowlist) {
        this.enabled = enabled;
        this.envAllowlist = envAllowlist == null || envAllowlist.isEmpty()
            ? DEFAULT_ENV_ALLOWLIST
            : Set.copyOf(envAllowlist);
    }

    /** A disabled gate — legacy inherit-everything behavior (steward opt-out). */
    public static EgressGate disabled() {
        return new EgressGate(false, DEFAULT_ENV_ALLOWLIST);
    }

    /** An enforcing gate with the default env allowlist. */
    public static EgressGate enforcing() {
        return new EgressGate(true, DEFAULT_ENV_ALLOWLIST);
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<String> envAllowlist() {
        return envAllowlist;
    }

    /**
     * Read {@code wyrdsekai.coding.egress-gate} from a HOCON config. Missing
     * block → enforcing with the default allowlist (default ON). {@code enabled}
     * honors the {@code WYRDSEKAI_CODING_EGRESS_GATE} env override the
     * application.conf wires via {@code ${?…}}.
     */
    public static EgressGate fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) {
            return enforcing();
        }
        var block = config.getConfig(CONFIG_ROOT);
        boolean en = readBool(block, "enabled", true);
        Set<String> allow = DEFAULT_ENV_ALLOWLIST;
        try {
            if (block.hasPath("env-allowlist")) {
                allow = new LinkedHashSet<>(block.getStringList("env-allowlist"));
            } else if (block.hasPath("env_allowlist")) {
                allow = new LinkedHashSet<>(block.getStringList("env_allowlist"));
            }
        } catch (ConfigException e) {
            log.warn("[EgressGate] bad env-allowlist in config — using default: {}", e.getMessage());
        }
        return new EgressGate(en, allow);
    }

    /**
     * Lazily-resolved default instance from {@code ConfigFactory.load()},
     * cached so the no-arg {@link GooseBackend.DefaultProcessRunner} doesn't
     * re-parse config per spawn. Enforcing unless the household opts out.
     */
    private static volatile EgressGate defaultInstance;

    public static EgressGate defaultInstance() {
        var cached = defaultInstance;
        if (cached != null) return cached;
        synchronized (EgressGate.class) {
            if (defaultInstance == null) {
                try {
                    defaultInstance = fromConfig(ConfigFactory.load());
                } catch (Exception e) {
                    log.warn("[EgressGate] could not load config — defaulting to enforcing: {}",
                        e.getMessage());
                    defaultInstance = enforcing();
                }
            }
            return defaultInstance;
        }
    }

    /** Test/reset hook — clears the cached default so a test can re-resolve. */
    static void resetDefaultForTest() {
        defaultInstance = null;
    }

    /**
     * Apply the gate to a subprocess environment. {@code processEnv} is the
     * live {@link java.lang.ProcessBuilder#environment()} map (starts as a copy
     * of the parent JVM env); {@code backendEnv} is the backend's resolved
     * overlay (GOOSE_PROVIDER, OPENAI_HOST, the sentinel key, …).
     *
     * <ul>
     *   <li><b>Disabled</b>: {@code processEnv.putAll(backendEnv)} — the legacy
     *       inherit-parent-then-layer behavior.</li>
     *   <li><b>Enforcing</b>: capture the allowlisted parent values, clear the
     *       whole inherited env, re-add only those, then layer the backend env.
     *       Every ambient credential is dropped.</li>
     * </ul>
     */
    public void applyEnv(Map<String, String> processEnv, Map<String, String> backendEnv) {
        if (processEnv == null) return;
        if (!enabled) {
            if (backendEnv != null) processEnv.putAll(backendEnv);
            return;
        }
        // Snapshot the allowlisted parent values BEFORE clearing.
        var keep = new HashMap<String, String>();
        for (var key : envAllowlist) {
            var v = processEnv.get(key);
            if (v != null) keep.put(key, v);
        }
        int dropped = processEnv.size() - keep.size();
        processEnv.clear();
        processEnv.putAll(keep);
        if (backendEnv != null) processEnv.putAll(backendEnv);
        if (dropped > 0) {
            log.debug("[EgressGate] scrubbed {} inherited env var(s) from the coding subprocess "
                + "(ambient credentials dropped; {} allowlisted kept)", dropped, keep.size());
        }
    }

    /**
     * The ONE seam every subprocess coding backend must use to spawn a process.
     * Builds a {@link java.lang.ProcessBuilder} whose environment is scrubbed by
     * the shared default gate (enforcing unless the household opts out), so no
     * backend can inherit the daemon's ambient credentials ({@code SSH_AUTH_SOCK},
     * cloud keys). Callers set {@code directory}/{@code redirectErrorStream} on
     * the returned builder as needed. {@code backendEnv} may be null (the
     * inherited env is still scrubbed to the allowlist).
     *
     * <p>: "GooseBackend AND every subprocess adapter
     * MUST build its ProcessBuilder env through … EgressGate." This factory is
     * that shared base — a new backend that spawns via {@code new
     * ProcessBuilder(...)} directly is the bug; use this instead.</p>
     */
    public static ProcessBuilder gatedProcessBuilder(List<String> args, Map<String, String> backendEnv) {
        var pb = new ProcessBuilder(args);
        defaultInstance().applyEnv(pb.environment(), backendEnv);
        return pb;
    }

    private static boolean readBool(Config c, String path, boolean dflt) {
        try {
            return c.hasPath(path) ? c.getBoolean(path) : dflt;
        } catch (ConfigException e) {
            // tolerate a string "true"/"false"
            try {
                var s = c.getString(path).trim();
                return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s);
            } catch (Exception ignored) {
                return dflt;
            }
        }
    }

    @Override
    public String toString() {
        return "EgressGate{enabled=" + enabled + ", allow=" + Arrays.toString(envAllowlist.toArray()) + "}";
    }
}
