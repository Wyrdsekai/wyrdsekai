package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime configuration for the generic ACP v1 backend ({@link AcpBackend}).
 *
 * <p>ACP is the PERMISSION-GATED coding surface: unlike the CLI backends
 * (which run ungated — a backend that decides to {@code git commit} simply
 * does), every session/request_permission from an ACP agent passes through
 * our policy (HOUSE_POLICY today; steward-consent routing on top). That is
 * why commit-sensitive workspaces route here.</p>
 *
 * <p>The default agent is {@code codezaiku acp} — the shape proven live
 * 2026-08-15 (AcpCodeZaikuLiveTest: result document rides
 * {@code _meta.codezaiku}). Any ACP v1 agent works ({@code goose acp}
 * passed the same handshake); the command is a config list so households
 * can point at either. Executable resolution reuses CodeZaiku's
 * bundled-binary-then-PATH rule when the command's first token is
 * {@code codezaiku}.</p>
 */
public record AcpRuntimeConfig(
    boolean enabled,
    List<String> command,
    Duration turnTimeout,
    Duration consentWait
) {

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.acp";

    public static final List<String> DEFAULT_COMMAND = List.of("codezaiku", "acp");

    public static final Duration DEFAULT_TURN_TIMEOUT = Duration.ofMinutes(30);

    public AcpRuntimeConfig {
        if (command == null || command.isEmpty()) {
            command = resolveDefaultCommand();
        } else {
            command = List.copyOf(command);
        }
        if (turnTimeout == null) turnTimeout = DEFAULT_TURN_TIMEOUT;
        if (consentWait == null) consentWait = AcpBackend.DEFAULT_CONSENT_WAIT;
    }

    /** {@code codezaiku acp} with the bundled-binary path substituted when present. */
    static List<String> resolveDefaultCommand() {
        var cmd = new ArrayList<>(DEFAULT_COMMAND);
        cmd.set(0, CodeZaikuRuntimeConfig.resolveDefaultExecutable());
        return List.copyOf(cmd);
    }

    /** First token of the command — what the bootstrap health-probes. */
    public String executable() {
        return command.get(0);
    }

    public static AcpRuntimeConfig defaults() {
        return new AcpRuntimeConfig(true, null, null, null);
    }

    public static AcpRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) return defaults();
        var c = config.getConfig(CONFIG_ROOT);
        return new AcpRuntimeConfig(
            !c.hasPath("enabled") || c.getBoolean("enabled"),
            c.hasPath("command") ? List.copyOf(c.getStringList("command")) : null,
            c.hasPath("turn-timeout-minutes")
                ? Duration.ofMinutes(c.getLong("turn-timeout-minutes")) : null,
            c.hasPath("consent-wait-seconds")
                ? Duration.ofSeconds(c.getLong("consent-wait-seconds")) : null);
    }
}
