package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which capability each {@code world.<namespace>.<method>(} call in an item needs, and which of
 * those the item's manifest does not declare.
 *
 * <p>The answer is learned from the runtime, not written down: every exported method of every
 * {@link ItemWorldApi} namespace is called once, with empty arguments and a provider that does
 * nothing, under a capability set that refuses everything and records what was asked for. A
 * method that asks for nothing is Tier 1. A namespace {@link ItemWorldApi} does not have is an
 * adapter, gated as {@code <namespace>.<method>}.
 *
 * <p>Since 2026-09-28 the manifest is enforced when an item runs, so an undeclared call fails in
 * a person's hands. The loader's audit, {@code wyrd items check} and the contract gate an item
 * passes before it is placed report it here first, with the fix.
 */
public final class ItemCapabilityAudit {

    private static final Pattern WORLD_CALL = Pattern.compile("world\\.(\\w+)\\.(\\w+)\\s*\\(");
    private static final Map<String, Set<String>> REQUIRED = learn();

    private ItemCapabilityAudit() {}

    /** The capabilities a call to {@code world.<namespace>.<method>} asks for; empty for Tier 1. */
    public static Set<String> requiredFor(String namespace, String method) {
        if (!isBuiltInNamespace(namespace)) return Set.of(namespace + "." + method);
        return REQUIRED.getOrDefault(namespace + "." + method, Set.of());
    }

    /** Each call in {@code script} whose capability {@code manifest} does not grant, with the fix. */
    public static List<String> undeclared(String script, ItemManifest manifest) {
        var out = new ArrayList<String>();
        if (script == null || manifest == null) return out;
        var caps = ItemCapabilitySet.from(manifest);
        var missing = new LinkedHashSet<String>();
        var calls = new LinkedHashSet<String>();
        var m = WORLD_CALL.matcher(script);
        while (m.find()) {
            for (var cap : requiredFor(m.group(1), m.group(2))) {
                if (!caps.has(cap) && missing.add(cap)) calls.add("world." + m.group(1) + "." + m.group(2));
            }
        }
        if (!missing.isEmpty()) {
            out.add("the script calls " + String.join(", ", calls) + " but the manifest does not declare "
                + String.join(", ", missing.stream().map(c -> "'" + c + "'").toList())
                + " — the call would be refused when the item runs. Add "
                + (missing.size() == 1 ? "it" : "them") + " to exports.manifest.capabilities.");
        }
        return out;
    }

    private static boolean isBuiltInNamespace(String namespace) {
        try {
            var field = ItemWorldApi.class.getField(namespace);
            return field.isAnnotationPresent(HostAccess.Export.class);
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static Map<String, Set<String>> learn() {
        var out = new HashMap<String, Set<String>>();
        var recorded = new LinkedHashSet<String>();
        var recorder = ItemCapabilitySet.of(List.of(), (cap, allowed) -> {
            if (!allowed) recorded.add(cap);
        });
        var api = new ItemWorldApi(new SilentProvider(), recorder);
        for (var field : ItemWorldApi.class.getFields()) {
            if (!field.isAnnotationPresent(HostAccess.Export.class)
                    || Modifier.isStatic(field.getModifiers())) continue;
            Object target;
            try {
                target = field.get(api);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (target == null) continue;
            for (Method method : target.getClass().getMethods()) {
                if (!method.isAnnotationPresent(HostAccess.Export.class)) continue;
                recorded.clear();
                try {
                    method.invoke(target, emptyArgs(method));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // The refusal was recorded by the hook; anything else is the silent provider.
                }
                if (!recorded.isEmpty()) {
                    out.computeIfAbsent(field.getName() + "." + method.getName(),
                        k -> new LinkedHashSet<>()).addAll(recorded);
                }
            }
        }
        return Map.copyOf(out);
    }

    private static Object[] emptyArgs(Method method) {
        var types = method.getParameterTypes();
        var args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) args[i] = 1;
            else if (types[i] == long.class) args[i] = 1L;
            else if (types[i] == double.class) args[i] = 1.0;
            else if (types[i] == float.class) args[i] = 1.0f;
            else if (types[i] == boolean.class) args[i] = false;
        }
        return args;
    }

    /** A provider that does nothing: learning must not touch the world. */
    private static final class SilentProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
            return Map.of();
        }
    }
}
