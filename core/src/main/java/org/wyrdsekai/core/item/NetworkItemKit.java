package org.wyrdsekai.core.item;

import java.util.ArrayList;
import java.util.List;

import org.wyrdsekai.core.item.ToolItem.ToolParam;
import org.wyrdsekai.core.net.NetworkGate;
import org.wyrdsekai.core.net.NetworkWiring;

/**
 * the four permission-fixed network items.
 *
 * <p>These are PROGRAMS over the {@code world.net.*} / {@code world.web.*} API.
 * They carry no credentials and open no sockets themselves — every verb funnels
 * through the boot-wired {@link org.wyrdsekai.core.net.NetworkCapability}, which
 * is {@link org.wyrdsekai.core.net.NetworkGate}-checked (steward allowlist) and
 * credential-resolved at call time.</p>
 *
 * <p><b>Permission-fixed</b>: unlike the standard tool
 * kit these are NOT auto-surfaced to every companion — the steward places them
 * (and, for ssh/scp, allowlists a host + binds a key-ref, without which the gate
 * default-denies). So even a surfaced item reaches nothing credentialed until
 * the steward opens a door; HTTP (the {@code wire}) stays permissive.</p>
 */
public final class NetworkItemKit {

    private NetworkItemKit() {}

    /** All four network items, for a steward to place / a test to exercise. */
    public static List<ToolItem> networkItems() {
        return List.of(courierSatchel(), farHand(), postrider(), wire());
    }

    /** The four item ids — the WS/SSH carried-script trust check keys on these. */
    public static final List<String> ITEM_IDS =
        List.of("courier_satchel", "far_hand", "postrider", "wire");

    /**
     * permission-fixed surfacing, realized as
     * enable-by-grant: an item appears on a companion's tool surface only once
     * the steward has opened the door it walks through. {@code far_hand} /
     * {@code postrider} need an ssh/scp allowlist entry ({@code scroll net
     * allow …}); the {@code courier_satchel} needs the household-bus transport
     * (an enrolled household); the {@code wire} rides along once ANY network
     * reach is enabled (it is default-permissive HTTP, but surfacing it to
     * every companion unconditionally would only bloat the tool menu — the
     * searching glass already covers open-web reads).
     *
     * <p>An ungranted item is also not resolvable for dispatch — and even a
     * hallucinated call would hit the {@link NetworkGate} default-deny, so
     * this gating is affordance hygiene on top of the real boundary.</p>
     */
    public static List<ToolItem> enabledItems(NetworkGate gate, boolean householdTransportWired) {
        var out = new ArrayList<ToolItem>();
        boolean anySsh = gate != null && gate.allowlist().stream().anyMatch(e -> e.grants("ssh"));
        boolean anyScp = gate != null && gate.allowlist().stream().anyMatch(e -> e.grants("scp"));
        boolean anyEntry = gate != null && !gate.allowlist().isEmpty();
        if (householdTransportWired) out.add(courierSatchel());
        if (anySsh) out.add(farHand());
        if (anyScp) out.add(postrider());
        if (anyEntry || householdTransportWired) out.add(wire());
        // declared in ToolItemStarterKit.EMBODIMENT_REGISTRY.
        return out.stream().map(ToolItemStarterKit::attachEmbodiment).toList();
    }

    /** {@link #enabledItems(NetworkGate, boolean)} against the live zone wiring. */
    public static List<ToolItem> enabledItems() {
        try {
            return enabledItems(NetworkWiring.currentGate(),
                NetworkWiring.householdTransportWired());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Courier Satchel — transfer a file to a household-enrolled peer over the
     * authenticated household bus (no ssh, no host keys). The lowest-friction,
     * lowest-risk transfer: it rides the roster trust boundary.
     */
    public static ToolItem courierSatchel() {
        return ToolItem.scripted(
            "courier_satchel", "Courier Satchel",
            "Send a file to a household-enrolled peer machine over the trusted household channel "
                + "(no host keys needed). Use for moving a file between your own household's nodes.",
            COURIER_SATCHEL_SCRIPT,
            List.of(
                new ToolParam("node", "string",
                    "The household node id (or name) to send to. Example: 'second-node'", true, null),
                new ToolParam("local_path", "string",
                    "Path of the local file to send", true, null),
                new ToolParam("remote_path", "string",
                    "Destination path on the peer node", true, null)
            ),
            "wyrdsekai");
    }

    /**
     * Far-Hand — run one command on a steward-allowlisted host over ssh. The
     * host + (optionally) a command-prefix are governed by the zone allowlist;
     * the key never leaves the resolver.
     */
    public static ToolItem farHand() {
        return ToolItem.scripted(
            "far_hand", "Far-Hand",
            "Run a single command on a remote host the steward has allowlisted (over ssh). "
                + "Only allowlisted hosts are reachable; some hosts restrict which commands are permitted.",
            FAR_HAND_SCRIPT,
            List.of(
                new ToolParam("host", "string",
                    "The allowlisted host to run on. Example: 'second-node'", true, null),
                new ToolParam("command", "string",
                    "The command to run on that host", true, null)
            ),
            "wyrdsekai");
    }

    /**
     * Postrider — copy a file up to or down from a steward-allowlisted host
     * over scp.
     */
    public static ToolItem postrider() {
        return ToolItem.scripted(
            "postrider", "Postrider",
            "Copy a file to or from a steward-allowlisted host (over scp). "
                + "Set direction to 'to' (send local→remote) or 'from' (fetch remote→local).",
            POSTRIDER_SCRIPT,
            List.of(
                new ToolParam("host", "string", "The allowlisted host. Example: 'backups.example'", true, null),
                new ToolParam("direction", "string",
                    "'to' to send a local file up, 'from' to fetch a remote file down",
                    true, List.of("to", "from")),
                new ToolParam("local_path", "string", "The local file path", true, null),
                new ToolParam("remote_path", "string", "The remote file path", true, null)
            ),
            "wyrdsekai");
    }

    /**
     * Wire — a generic outbound HTTP(S) request. HTTP is permissive by default
     * (like web_search); a steward may restrict it via an http allowlist entry.
     * Backed by the existing {@code world.web} egress.
     */
    public static ToolItem wire() {
        return ToolItem.scripted(
            "wire", "Wire",
            "Make an HTTP request to any web address (GET by default; set method to POST/PUT/DELETE). "
                + "The open web is reachable by default.",
            WIRE_SCRIPT,
            List.of(
                new ToolParam("url", "string", "The full URL, including https://", true, null),
                new ToolParam("method", "string", "HTTP method. Default GET",
                    false, List.of("GET", "POST", "PUT", "DELETE")),
                new ToolParam("body", "string", "Optional request body (for POST/PUT)", false, null)
            ),
            "wyrdsekai");
    }

    // ─── Scripts ──────────────────────────────────────────────────────

    private static final String COURIER_SATCHEL_SCRIPT = """
        function invoke(params) {
            var r = world.net.household_copy(params.node, params.local_path, params.remote_path);
            if (r && r.ok) {
                return { ok: true, sent: true, node: params.node,
                         remote_path: r.landed_path || params.remote_path,
                         landed_path: r.landed_path || params.remote_path };
            }
            return { ok: false, error: (r && (r.error || r.reason)) || "transfer failed",
                     reason: r && r.reason };
        }
        """;

    private static final String FAR_HAND_SCRIPT = """
        function invoke(params) {
            var r = world.net.ssh(params.host, params.command);
            if (r && r.ok) {
                return { ok: true, host: params.host, exit: r.exit, output: r.stdout, stderr: r.stderr };
            }
            return { ok: false, host: params.host,
                     error: (r && (r.message || r.error)) || "command failed",
                     reason: r && r.reason, stderr: r && r.stderr };
        }
        """;

    private static final String POSTRIDER_SCRIPT = """
        function invoke(params) {
            var r;
            if (params.direction === "from") {
                r = world.net.scp_from(params.host, params.remote_path, params.local_path);
            } else {
                r = world.net.scp_to(params.host, params.local_path, params.remote_path);
            }
            if (r && r.ok) {
                return { ok: true, host: params.host, direction: params.direction || "to" };
            }
            return { ok: false, host: params.host,
                     error: (r && (r.message || r.error)) || "transfer failed",
                     reason: r && r.reason };
        }
        """;

    private static final String WIRE_SCRIPT = """
        function invoke(params) {
            var method = (params.method || "GET").toUpperCase();
            var r;
            if (method === "POST") {
                r = world.web.post(params.url, params.body || "");
            } else if (method === "PUT") {
                r = world.web.put(params.url, params.body || "");
            } else if (method === "DELETE") {
                r = world.web.delete(params.url);
            } else {
                var text = world.web.fetch(params.url);
                return { ok: true, method: "GET", body: text };
            }
            if (r && (r.status >= 200 && r.status < 400)) {
                return { ok: true, method: method, status: r.status, body: r.body };
            }
            return { ok: false, method: method, status: r && r.status,
                     error: (r && (r.message || r.error)) || "request failed" };
        }
        """;
}
