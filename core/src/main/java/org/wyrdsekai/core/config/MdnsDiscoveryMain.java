package org.wyrdsekai.core.config;

import java.util.Comparator;

/**
 * Standalone main for {@code wyrd discover --lan}. Browses mDNS for ~3
 * seconds and prints any wyrdsekai nodes found on the local network.
 *
 * <p>Output format is human-readable. For machine consumption pass
 * {@code --json}.</p>
 */
public final class MdnsDiscoveryMain {

    private MdnsDiscoveryMain() {}

    public static void main(String[] args) throws Exception {
        var json = false;
        var durationMs = 3000;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json" -> json = true;
                case "--timeout" -> {
                    if (i + 1 < args.length) durationMs = Integer.parseInt(args[++i]);
                }
                default -> { /* ignore */ }
            }
        }

        var cfg = WyrdConfig.get();
        var disco = new MdnsDiscovery(cfg.mdnsService());
        var peers = disco.browse(durationMs);
        peers.sort(Comparator.comparing(MdnsDiscovery.DiscoveredPeer::displayName));

        if (json) {
            // Hand-rolled JSON to avoid pulling jackson into the audit class
            // (this main is invoked via a thin classpath probe).
            var sb = new StringBuilder("[");
            for (var i = 0; i < peers.size(); i++) {
                var p = peers.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":\"").append(esc(p.displayName())).append('"');
                sb.append(",\"host\":\"").append(esc(p.hostName())).append('"');
                sb.append(",\"port\":").append(p.port());
                sb.append(",\"household\":\"").append(esc(p.household())).append('"');
                // Capability flags so callers (e.g. `wyrd setup` auto-join) can
                // tell whether a peer advertises a GPU/inference backend worth
                // borrowing, and whether it hosts a relay.
                sb.append(",\"inference\":").append("true".equals(p.txt().get("inference")));
                sb.append(",\"relay\":").append("true".equals(p.txt().get("relay")));
                sb.append('}');
            }
            sb.append(']');
            System.out.println(sb);
        } else {
            if (peers.isEmpty()) {
                System.out.println("No wyrdsekai nodes found on the local network.");
                System.out.println("(Browsed " + cfg.mdnsService() + " for " + durationMs + "ms.)");
            } else {
                System.out.println("Found " + peers.size() + " wyrdsekai node(s):");
                System.out.println();
                for (var p : peers) {
                    var hh = p.household();
                    var hhDisplay = "none".equals(hh) ? "solo" : hh;
                    System.out.printf("  %-18s  %s:%d%n",
                        p.displayName(), p.hostName(), p.port());
                    System.out.printf("    household: %s   zone: %s%n",
                        hhDisplay, p.txt().getOrDefault("zone", "?"));
                    var caps = new StringBuilder();
                    if ("true".equals(p.txt().get("relay")))     caps.append(" relay");
                    if ("true".equals(p.txt().get("peertrain"))) caps.append(" peer-train");
                    if ("true".equals(p.txt().get("inference"))) caps.append(" inference");
                    if (caps.length() > 0) {
                        System.out.println("    capabilities:" + caps);
                    }
                    System.out.println();
                }
            }
        }
        disco.close();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
