package org.wyrdsekai.core.config;

import java.util.function.Supplier;

/**
 * Standalone main for {@code wyrd config audit}. Prints every resolved
 * setting plus the source it came from (ENV / PROFILE / DEFAULT). Useful
 * when a node is behaving unexpectedly and the operator needs to see
 * <i>which</i> layer is contributing the live value.
 *
 * <p>Invoked from {@code bin/wyrd} as a tiny JVM probe. Has no side
 * effects — purely a read of the merged config.</p>
 */
public final class WyrdConfigAudit {

    private WyrdConfigAudit() {}

    public static void main(String[] args) {
        var cfg = WyrdConfig.get();
        System.out.println("# Wyrdsekai config audit");
        System.out.println("# profile: " + cfg.path()
            + (cfg.profileLoaded() ? "" : "  (not present)"));
        System.out.println();

        record Setting(String label, String env, String toml,
                       Supplier<String> dflt) {}

        Setting[] all = {
            new Setting("Node name",       "WYRDSEKAI_NODE_NAME", "node.name", null),
            new Setting("Zone",            "WYRDSEKAI_ZONE_ID",   "node.zone", null),
            new Setting("NATS URL",        "WYRDSEKAI_NATS_URL",  "nats.url",  null),
            new Setting("Inference URL",   "WYRDSEKAI_INFERENCE_URL", "inference.url", null),
            new Setting("Between enabled", "WYRDSEKAI_BETWEEN_ENABLED", "between.enabled", null),
            new Setting("Relay URL",       "WYRDSEKAI_RELAY_URL",   "relay.url",   null),
            new Setting("Relay user",      "WYRDSEKAI_RELAY_USER",  "relay.user",  null),
            new Setting("Relay token",     "WYRDSEKAI_RELAY_TOKEN", "relay.token", null),
            new Setting("Peer-train host", "WYRDSEKAI_PEER_TRAINING_HOST",
                        "peer_training.host", null),
            new Setting("Peer-train relay token", "WYRDSEKAI_PEER_TRAINING_RELAY_TOKEN",
                        "peer_training.relay_token", null),
            new Setting("Adapter dir",     "WYRDSEKAI_ADAPTER_DIR", "paths.adapter_dir", null),
            new Setting("Wyrd bin",        "WYRDSEKAI_BIN",        "paths.wyrd_bin",   null),
        };

        for (var s : all) {
            // Each typed accessor returns a value but not the source; call the
            // raw resolver here with the same defaults the typed accessor uses,
            // so the audit reflects the same precedence.
            String value;
            WyrdConfig.Source source;
            switch (s.toml()) {
                case "node.name" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> shellSafe(cfg.nodeName())); value = r.value(); source = r.source(); }
                case "node.zone" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "home"); value = r.value(); source = r.source(); }
                case "nats.url" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "nats://127.0.0.1:4222"); value = r.value(); source = r.source(); }
                case "inference.url" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "http://127.0.0.1:8200"); value = r.value(); source = r.source(); }
                case "between.enabled" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "true"); value = r.value(); source = r.source(); }
                case "peer_training.host" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "false"); value = r.value(); source = r.source(); }
                case "peer_training.relay_user" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "peer_trainer"); value = r.value(); source = r.source(); }
                case "paths.adapter_dir" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> System.getProperty("user.home") + "/.wyrdsekai/adapters");
                    value = r.value(); source = r.source(); }
                case "paths.wyrd_bin" -> { var r = cfg.resolveDetailed(s.env(), s.toml(),
                    () -> "/usr/local/wyrdsekai/bin/wyrd"); value = r.value(); source = r.source(); }
                default -> { var r = cfg.resolveDetailed(s.env(), s.toml(), null);
                    value = r.value(); source = r.source(); }
            }
            // Mask token-like values so the audit output is shareable in bug reports.
            var displayed = value == null ? "<unset>"
                : (s.env().contains("TOKEN") && value.length() > 6
                    ? value.substring(0, 4) + "***" + value.substring(value.length() - 2)
                    : value);
            System.out.printf("%-22s  %-9s  %s%n  %s%n%n",
                s.label(), source, displayed, "(env=" + s.env() + ", toml=" + s.toml() + ")");
        }
    }

    private static String shellSafe(String s) { return s == null ? "" : s; }
}
