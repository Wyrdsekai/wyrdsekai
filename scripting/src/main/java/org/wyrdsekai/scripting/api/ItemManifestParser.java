package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * extracts {@code exports.manifest = {...}}
 * from an item script's head into a structured {@link ItemManifest}.
 *
 * <p>Strategy: a one-shot GraalJS eval restricted to the {@code exports}
 * declaration. We don't run the rest of the script (that happens later in
 * {@code ItemScriptExecutor}); we just bind a stub {@code exports} object,
 * eval the manifest assignment, and read back the resulting object.</p>
 *
 * <p>For files that don't follow the pattern (or where the head doesn't
 * parse), the parser returns {@code null} — callers decide whether that's
 * a hard reject (Phase A0 disk loader: yes) or a soft fallback (legacy
 * JVM-baked items: yes, treated as empty manifest).</p>
 */
public final class ItemManifestParser {

    private static final Logger log = LoggerFactory.getLogger(ItemManifestParser.class);

    /** Cap on how much of the file we eval — manifests live at the top. */
    private static final int HEAD_BYTES = 16_384;

    /** Loose match for "exports.manifest = {...};". Used to extract just the head. */
    private static final Pattern MANIFEST_PATTERN = Pattern.compile(
        "exports\\s*\\.\\s*manifest\\s*=\\s*\\{",
        Pattern.MULTILINE);

    private ItemManifestParser() {}

    /**
     * Parse a manifest from script source. Returns null when the script has
     * no manifest declaration or the eval fails.
     */
    public static ItemManifest parse(String script) {
        if (script == null || script.isBlank()) return null;

        var head = script.length() > HEAD_BYTES ? script.substring(0, HEAD_BYTES) : script;
        var matcher = MANIFEST_PATTERN.matcher(head);
        if (!matcher.find()) {
            return null;
        }

        // Slice from the start through the matching closing brace + semicolon.
        var start = matcher.start();
        var braceStart = head.indexOf('{', matcher.end() - 1);
        if (braceStart < 0) return null;
        int depth = 0;
        int end = -1;
        for (int i = braceStart; i < head.length(); i++) {
            var c = head.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end < 0) return null;

        var snippet = head.substring(start, end) + ";";

        try (var context = Context.newBuilder("js")
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build()) {

            // Establish a bare `exports` object the snippet can attach to.
            context.eval("js", "var exports = {};");
            context.eval("js", snippet);

            var exports = context.getBindings("js").getMember("exports");
            if (exports == null) return null;
            var manifest = exports.getMember("manifest");
            if (manifest == null || !manifest.hasMembers()) return null;

            return readManifest(manifest);
        } catch (Exception e) {
            log.debug("Manifest parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static ItemManifest readManifest(Value manifest) {
        var name = stringOrNull(manifest, "name");
        var version = stringOrNull(manifest, "version");
        var description = stringOrNull(manifest, "description");
        var author = stringOrNull(manifest, "author");
        var capabilities = stringList(manifest, "capabilities");

        var rateLimits = new LinkedHashMap<String, ItemManifest.RateLimit>();
        var rl = manifest.getMember("rate_limits");
        if (rl != null && rl.hasMembers()) {
            for (var key : rl.getMemberKeys()) {
                var entry = rl.getMember(key);
                if (entry == null || !entry.hasMembers()) continue;
                rateLimits.put(key, new ItemManifest.RateLimit(
                    intOrNull(entry, "per_minute"),
                    intOrNull(entry, "per_hour"),
                    intOrNull(entry, "per_day")));
            }
        }

        var dataSensitivity = stringOrNull(manifest, "data_sensitivity");
        var installWarnings = stringList(manifest, "install_warnings");
        var externalDomains = stringList(manifest, "external_domains");
        var mcpServers = stringList(manifest, "mcp_servers");
        var safeSlots = stringList(manifest, "safe_slots");
        var signature = stringOrNull(manifest, "signature");
        var installHandler = stringOrNull(manifest, "install_handler");
        var uninstallHandler = stringOrNull(manifest, "uninstall_handler");
        var manifestVersion = stringOrNull(manifest, "manifest_version");
        var spendLimit = doubleOrNull(manifest, "spend_limit_usd_per_day");

        // Phase 2 — script-declared menu commands. Optional array of {label, args}.
        // The hint builder surfaces these as separate action-menu entries beside the
        // generic Examine/Use pair so a script can advertise its own sub-verbs.
        var commands = new ArrayList<ItemManifest.Command>();
        var cmdArray = manifest.getMember("commands");
        if (cmdArray != null && !cmdArray.isNull() && cmdArray.hasArrayElements()) {
            for (long i = 0; i < cmdArray.getArraySize(); i++) {
                var entry = cmdArray.getArrayElement(i);
                if (entry == null || !entry.hasMembers()) continue;
                var label = stringOrNull(entry, "label");
                if (label == null || label.isBlank()) continue;
                var args = stringOrNull(entry, "args");
                commands.add(new ItemManifest.Command(label, args == null ? "" : args));
            }
        }

        // parse the declared embodiment block (both shapes:
        // {silent, reason} and {emits, descriptor_template}). Dropped until
        // 2026-07-11, which made every disk-loaded item WARN at boot.
        ItemEmbodimentSpec embodiment = null;
        var emb = manifest.getMember("embodiment");
        if (emb != null && !emb.isNull() && emb.hasMembers()) {
            var silentV = emb.getMember("silent");
            boolean silent = silentV != null && silentV.isBoolean() && silentV.asBoolean();
            var emits = new ArrayList<String>();
            var emitsArr = emb.getMember("emits");
            if (emitsArr != null && !emitsArr.isNull() && emitsArr.hasArrayElements()) {
                for (long i = 0; i < emitsArr.getArraySize(); i++) {
                    var e = emitsArr.getArrayElement(i);
                    if (e != null && e.isString()) emits.add(e.asString());
                }
            }
            embodiment = new ItemEmbodimentSpec(
                silent,
                stringOrNull(emb, "reason"),
                emits,
                stringOrNull(emb, "descriptor_template") != null
                    ? stringOrNull(emb, "descriptor_template")
                    : stringOrNull(emb, "descriptorTemplate"),
                null);
        }

        // The typed parameters invoke(params) actually reads. Optional array of
        // {name, type, description, required}. When a script declares none we fall
        // back to the single free-form `query` slot (ScriptedItemDef.inferParams),
        // which is what every item got before — and why the model had to guess what
        // each tool wanted, calling morning_briefing with an empty query forever.
        var params = new ArrayList<ItemManifest.Param>();
        var paramArray = manifest.getMember("params");
        if (paramArray != null && !paramArray.isNull() && paramArray.hasArrayElements()) {
            for (long i = 0; i < paramArray.getArraySize(); i++) {
                var entry = paramArray.getArrayElement(i);
                if (entry == null || !entry.hasMembers()) continue;
                var pName = stringOrNull(entry, "name");
                if (pName == null || pName.isBlank()) continue;
                var pType = stringOrNull(entry, "type");
                var reqV = entry.getMember("required");
                boolean required = reqV != null && reqV.isBoolean() && reqV.asBoolean();
                params.add(new ItemManifest.Param(
                    pName,
                    pType == null || pType.isBlank() ? "string" : pType,
                    stringOrNull(entry, "description"),
                    required));
            }
        }

        return new ItemManifest(
            name, version, description, author,
            capabilities, rateLimits,
            dataSensitivity, installWarnings,
            externalDomains, mcpServers, safeSlots,
            signature, installHandler, uninstallHandler,
            manifestVersion, spendLimit, commands, embodiment, params);
    }

    private static String stringOrNull(Value parent, String key) {
        var v = parent.getMember(key);
        if (v == null || v.isNull() || !v.isString()) return null;
        return v.asString();
    }

    private static Integer intOrNull(Value parent, String key) {
        var v = parent.getMember(key);
        if (v == null || v.isNull() || !v.isNumber()) return null;
        return v.asInt();
    }

    private static Double doubleOrNull(Value parent, String key) {
        var v = parent.getMember(key);
        if (v == null || v.isNull() || !v.isNumber()) return null;
        return v.asDouble();
    }

    private static List<String> stringList(Value parent, String key) {
        var v = parent.getMember(key);
        if (v == null || v.isNull() || !v.hasArrayElements()) return List.of();
        var out = new ArrayList<String>((int) v.getArraySize());
        for (long i = 0; i < v.getArraySize(); i++) {
            var el = v.getArrayElement(i);
            if (el != null && el.isString()) out.add(el.asString());
        }
        return out;
    }

    /**
     * extract the {@code exports.manifest.embodiment}
     * block from the same script head. Returns null when the script declares
     * no embodiment block (caller decides whether that's a hard reject or
     * a migration-shim case).
     *
     * <p>This is a separate parse pass so legacy items that don't declare
     * the block still load through {@link #parse} unchanged.
     */
    public static ItemEmbodimentSpec parseEmbodiment(String script) {
        if (script == null || script.isBlank()) return null;
        var head = script.length() > HEAD_BYTES ? script.substring(0, HEAD_BYTES) : script;
        var matcher = MANIFEST_PATTERN.matcher(head);
        if (!matcher.find()) return null;
        var braceStart = head.indexOf('{', matcher.end() - 1);
        if (braceStart < 0) return null;
        int depth = 0;
        int end = -1;
        for (int i = braceStart; i < head.length(); i++) {
            var c = head.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end < 0) return null;
        var snippet = head.substring(matcher.start(), end) + ";";

        try (var context = Context.newBuilder("js")
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build()) {
            context.eval("js", "var exports = {};");
            context.eval("js", snippet);
            var exports = context.getBindings("js").getMember("exports");
            if (exports == null) return null;
            var manifest = exports.getMember("manifest");
            if (manifest == null || !manifest.hasMembers()) return null;
            var emb = manifest.getMember("embodiment");
            if (emb == null || emb.isNull() || !emb.hasMembers()) return null;
            return readEmbodiment(emb);
        } catch (Exception e) {
            log.debug("Embodiment parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static ItemEmbodimentSpec readEmbodiment(Value emb) {
        var silentVal = emb.getMember("silent");
        boolean silent = silentVal != null && !silentVal.isNull() && silentVal.isBoolean() && silentVal.asBoolean();
        var reason = stringOrNull(emb, "reason");
        var emits = stringList(emb, "emits");
        var descriptorTemplate = stringOrNull(emb, "descriptor_template");
        // Migration shim — readers should not normally write this, but if the
        // boot pass stamped a manifest in-disk we want to preserve the marker.
        ItemEmbodimentSpec.MigrationShim migration = null;
        var mig = emb.getMember("migration");
        if (mig != null && !mig.isNull() && mig.hasMembers()) {
            var versionStr = stringOrNull(mig, "version");
            var atStr = stringOrNull(mig, "at");
            Instant atInst = null;
            if (atStr != null) try { atInst = Instant.parse(atStr); } catch (Exception ignored) {}
            migration = new ItemEmbodimentSpec.MigrationShim(
                versionStr == null ? ItemEmbodimentSpec.MIGRATION_VERSION : versionStr,
                atInst == null ? Instant.now() : atInst);
        }
        return new ItemEmbodimentSpec(silent, reason, emits, descriptorTemplate, migration);
    }

    /** For tests: pretty-print a parsed manifest as a sanity check. */
    public static Map<String, Object> toMap(ItemManifest m) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", m.name());
        out.put("version", m.version());
        out.put("description", m.description());
        out.put("author", m.author());
        out.put("capabilities", m.capabilities());
        return out;
    }
}
