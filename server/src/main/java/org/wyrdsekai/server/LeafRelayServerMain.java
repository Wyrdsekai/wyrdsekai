package org.wyrdsekai.server;

import org.wyrdsekai.between.LeafRelayConfig;
import org.wyrdsekai.between.NodeIdentity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * headless CLI that materialises a leaf-relay NATS
 * config file from this node's environment and identity. Two subcommands:
 *
 * <ul>
 *   <li>{@code config} — generate (or rewrite) the leaf-relay config and print
 *       its path. Idempotent.</li>
 *   <li>{@code start-cmd} — print the exact nats-server invocation an operator
 *       (or systemd unit) should use to launch the leaf relay.</li>
 * </ul>
 *
 * <p>This is intentionally NOT a process supervisor — leaf relays run as their
 * own systemd-managed nats-server. The wyrdsekai server wires the same config
 * via {@code WYRDSEKAI_RELAY_ENABLED=true} so the embedded {@link
 * org.wyrdsekai.between.NatsServerManager} can boot it for development /
 * single-node setups.</p>
 *
 * <p>Required env:
 * <ul>
 *   <li>{@code WYRDSEKAI_RELAY_UPSTREAM} — upstream relay leaf URL,
 *       e.g. {@code nats://relay.example:7422}</li>
 *   <li>{@code WYRDSEKAI_HOUSEHOLD_TAG} — household tag (subject scoping)</li>
 *   <li>{@code WYRDSEKAI_ZONE_ID}       — zone id (federation gate scoping)</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code WYRDSEKAI_RELAY_LEAF_PORT}   (default 7422)</li>
 *   <li>{@code WYRDSEKAI_RELAY_CLIENT_PORT} (default 4223 — note: NOT 4222
 *       to avoid colliding with the in-process NATS that wyrdsekai-server
 *       runs at 4222)</li>
 *   <li>{@code WYRDSEKAI_RELAY_TRUSTED_PUBKEYS} — comma-separated NKey pubkeys
 *       allowed to connect AS clients to this leaf</li>
 *   <li>{@code WYRDSEKAI_RELAY_LEAF_CONF}   (default {@code ~/.wyrdsekai/relay-leafnode.conf})</li>
 * </ul>
 */
public final class LeafRelayServerMain {

    private LeafRelayServerMain() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err, args));
    }

    static int run(PrintStream out, PrintStream err, String... args) {
        if (args.length == 0) {
            printUsage(out);
            return 1;
        }
        try {
            return switch (args[0]) {
                case "config" -> doConfig(out, err);
                case "start-cmd" -> doStartCmd(out, err);
                case "help", "--help" -> { printUsage(out); yield 0; }
                default -> {
                    err.println("[wyrd relay-server] unknown subcommand: " + args[0]);
                    printUsage(err);
                    yield 1;
                }
            };
        } catch (Exception e) {
            err.println("[wyrd relay-server] " + e.getMessage());
            return 2;
        }
    }

    private static int doConfig(PrintStream out, PrintStream err) throws IOException {
        var spec = buildSpecFromEnv(err);
        if (spec == null) return 1;
        var target = leafConfPath();
        LeafRelayConfig.writeTo(target, spec);
        out.println("[wyrd relay-server] wrote leaf config: " + target);
        out.println("[wyrd relay-server] upstream=" + spec.upstreamUrl()
            + " householdTag=" + spec.householdTag() + " zoneId=" + spec.zoneId());
        out.println("[wyrd relay-server] start with: nats-server -c " + target);
        return 0;
    }

    private static int doStartCmd(PrintStream out, PrintStream err) {
        var target = leafConfPath();
        if (!Files.exists(target)) {
            err.println("[wyrd relay-server] no config at " + target
                + " — run `wyrd relay-server config` first");
            return 1;
        }
        out.println("nats-server -c " + target);
        return 0;
    }

    private static LeafRelayConfig.Spec buildSpecFromEnv(PrintStream err) throws IOException {
        var upstream = System.getenv("WYRDSEKAI_RELAY_UPSTREAM");
        var household = System.getenv("WYRDSEKAI_HOUSEHOLD_TAG");
        var zoneId = System.getenv("WYRDSEKAI_ZONE_ID");
        if (upstream == null || upstream.isBlank()) {
            err.println("[wyrd relay-server] WYRDSEKAI_RELAY_UPSTREAM is required "
                + "(e.g. nats://relay.example:7422)");
            return null;
        }
        if (household == null || household.isBlank()) {
            err.println("[wyrd relay-server] WYRDSEKAI_HOUSEHOLD_TAG is required");
            return null;
        }
        if (zoneId == null || zoneId.isBlank()) {
            err.println("[wyrd relay-server] WYRDSEKAI_ZONE_ID is required");
            return null;
        }

        int leafPort = parsePort("WYRDSEKAI_RELAY_LEAF_PORT", 7422);
        // Client port defaults to 4223 — NOT 4222 — to avoid colliding with the
        // in-process NATS the wyrdsekai server already runs.
        int clientPort = parsePort("WYRDSEKAI_RELAY_CLIENT_PORT", 4223);
        int monitorPort = parsePort("WYRDSEKAI_RELAY_MONITOR_PORT", 8223);

        var identity = NodeIdentity.loadOrGenerate(identityPath());
        var pubkey = identity.nkeyPublicKey();

        var trusted = new ArrayList<String>();
        var trustedRaw = System.getenv("WYRDSEKAI_RELAY_TRUSTED_PUBKEYS");
        if (trustedRaw != null && !trustedRaw.isBlank()) {
            for (var pk : trustedRaw.split(",")) {
                var t = pk.trim();
                if (!t.isEmpty()) trusted.add(t);
            }
        }
        return new LeafRelayConfig.Spec(leafPort, clientPort, monitorPort,
            upstream, household, zoneId, pubkey, List.copyOf(trusted));
    }

    private static int parsePort(String envKey, int fallback) {
        var v = System.getenv(envKey);
        if (v == null || v.isBlank()) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Path leafConfPath() {
        var override = System.getenv("WYRDSEKAI_RELAY_LEAF_CONF");
        if (override != null && !override.isEmpty()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai", "relay-leafnode.conf");
    }

    private static Path identityPath() {
        var override = System.getenv("WYRDSEKAI_NODE_IDENTITY_PATH");
        if (override != null && !override.isEmpty()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai", "node-identity.json");
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  wyrd relay-server config     "
            + "Generate the leaf-relay NATS config from env. Idempotent.");
        out.println("  wyrd relay-server start-cmd  "
            + "Print the nats-server command to launch the leaf relay.");
        out.println("");
        out.println("Required env: WYRDSEKAI_RELAY_UPSTREAM, WYRDSEKAI_HOUSEHOLD_TAG, "
            + "WYRDSEKAI_ZONE_ID");
        out.println("Optional env: WYRDSEKAI_RELAY_LEAF_PORT (7422), "
            + "WYRDSEKAI_RELAY_CLIENT_PORT (4223), WYRDSEKAI_RELAY_TRUSTED_PUBKEYS");
    }
}
