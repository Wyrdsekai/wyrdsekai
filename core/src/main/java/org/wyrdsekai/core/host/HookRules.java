package org.wyrdsekai.core.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The immune hooks' rules, as data. Three lists: paths whose opening cuts the tool, paths
 * whose opening is only written down (the kernel already refuses them), and programs whose
 * exec cuts the tool. A path ending in a slash is a directory prefix; anything else matches
 * the file itself and its siblings with a dash or dot suffix (a database and its WAL).
 * {@code ${data}} in a path stands for the data directory, so one rules file fits any node.
 *
 * <p>Deciding is a pure function of one event and these lists. That is what makes a rule set
 * replayable: a candidate can be run over everything her hands have been seen to do, and what
 * it would have cut is known before it is ever armed.</p>
 */
public record HookRules(List<String> cut, List<String> closed, List<String> cutExecs) {

    public HookRules {
        cut = List.copyOf(cut);
        closed = List.copyOf(closed);
        cutExecs = List.copyOf(cutExecs);
    }

    /**
     * The built-in rules. A cut kills her whole tool tree, so the list is what no tool has any
     * business touching even by accident: the record, the keys, the copies, the watcher's own
     * files. Everything else under the data directory is already closed to her by the kernel;
     * a reach for it is written down, and the kernel's "no" is answer enough.
     */
    public static HookRules defaults() {
        return new HookRules(
            List.of("${data}/world.db", "${data}/library.db", "${data}/vault.key", "${data}/credentials.safe",
                "${data}/node-identity.json", "${data}/operator.token", "${data}/session.token", "${data}/ssh_host_key",
                "${data}/vault-store/", "${data}/brainstem/"),
            List.of("${data}/souls/", "${data}/agents/", "${data}/adapters/", "${data}/household/", "${data}/sleepwrite/",
                "${data}/wyrdsekai.conf", "${data}/env", "${data}/profile.toml", "/etc/wyrdsekai/", "/etc/shadow", "/root/"),
            List.of("nft", "systemctl", "docker", "useradd", "userdel", "usermod", "groupadd", "sudo", "su",
                "wyrdsekai-doors", "wyrdsekai-being", "wyrdsekai-brainstem", "reboot", "shutdown", "mount", "apt", "apt-get", "dpkg"));
    }

    public static HookRules parse(String json) throws IOException {
        var n = Json.mapper().readTree(json);
        return new HookRules(strings(n.get("cut")), strings(n.get("closed")), strings(n.get("cutExecs")));
    }

    public static HookRules load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    private static List<String> strings(JsonNode n) {
        var out = new ArrayList<String>();
        if (n != null && n.isArray()) for (var e : n) if (e.isTextual() && !e.asText().isBlank()) out.add(e.asText());
        return out;
    }

    public Map<String, Object> asMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("cut", cut);
        m.put("closed", closed);
        m.put("cutExecs", cutExecs);
        return m;
    }

    public String toJson() throws IOException {
        return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(asMap());
    }

    /**
     * What to do with one event. {@code dataDir} resolves {@code ${data}}; {@code slugOfUid}
     * names the being behind a uid so that her own home stays hers.
     */
    public ToolHooks.Verdict decide(ToolHooks.Event e, String dataDir, Function<Integer, String> slugOfUid) {
        if (e == null) return ToolHooks.Verdict.ALLOW;
        switch (e.kind()) {
            case "open" -> {
                var p = e.arg();
                if (!p.startsWith("/")) return ToolHooks.Verdict.ALLOW;   // relative: inside the tool's own cwd
                // Her own home is hers. The first live day this rule was missing, and her coding
                // hand was killed twice for opening its own settings: the immune system took her
                // for an intruder. Another being's home is still not hers to open.
                var beingsRoot = dataDir + "/beings/";
                if (p.startsWith(beingsRoot)) {
                    var slug = slugOfUid == null ? null : slugOfUid.apply(e.uid());
                    if (slug != null && p.startsWith(beingsRoot + slug + "/")) return ToolHooks.Verdict.ALLOW;
                    return ToolHooks.Verdict.CUT;
                }
                for (var prefix : cut) if (under(p, prefix.replace("${data}", dataDir))) return ToolHooks.Verdict.CUT;
                for (var prefix : closed) if (under(p, prefix.replace("${data}", dataDir))) return ToolHooks.Verdict.RECORD;
                return ToolHooks.Verdict.ALLOW;
            }
            case "exec" -> {
                var name = e.arg().substring(e.arg().lastIndexOf('/') + 1);
                return cutExecs.contains(name) ? ToolHooks.Verdict.CUT : ToolHooks.Verdict.RECORD;
            }
            case "connect" -> { return ToolHooks.Verdict.RECORD; }
            default -> { return ToolHooks.Verdict.ALLOW; }
        }
    }

    private static boolean under(String p, String prefix) {
        return prefix.endsWith("/") ? p.startsWith(prefix) : (p.equals(prefix) || p.startsWith(prefix + "-") || p.startsWith(prefix + "."));
    }
}
