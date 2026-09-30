package org.wyrdsekai.scripting.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Can use, cannot read" for credentials in The Safe.
 *
 * <p>An item never receives a secret. {@code world.safe.get(slot)} returns a reference such as
 * {@code {{safe:github.token}}}; the item puts it in a request header
 * ({@code Authorization: "Bearer " + world.safe.get("github.token")}), and the HTTP layer swaps the
 * reference for the secret only while it builds the request. Whatever comes back has the secret
 * replaced by {@code [secret]}, so a service that echoes headers cannot hand it to the script
 * either. References are resolved only in header values, never in a URL or a body, and only for
 * slots the item's manifest lists in {@code safe_slots} next to the {@code safe.get} capability.
 */
public final class SafeRefs {

    private static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");
    private static final Pattern REF = Pattern.compile("\\{\\{safe:(" + SLOT.pattern() + ")\\}\\}");
    static final String MASK = "[secret]";

    private SafeRefs() {}

    /** Thrown when a referenced slot has no value on this node. */
    public static final class MissingCredential extends RuntimeException {
        private final String slot;

        MissingCredential(String slot) {
            super("credential not set: '" + slot + "' — the steward can set it with `wyrd cred set "
                + slot + "`");
            this.slot = slot;
        }

        public String slot() { return slot; }
    }

    /** The reference an item holds in place of the secret in {@code slot}, or null for a bad name. */
    public static String ref(String slot) {
        if (slot == null || !SLOT.matcher(slot).matches()) return null;
        return "{{safe:" + slot + "}}";
    }

    /** May an item with these capabilities have {@code slot} put into its requests? */
    public static boolean mayUse(ItemCapabilitySet caps, String slot) {
        if (caps == null || caps.isUnrestricted()) return true;
        return caps.has("safe.get") && caps.safeSlots().contains(slot);
    }

    /**
     * Request headers with every reference replaced by its secret. The secrets put in are added to
     * {@code used} so the response can be scrubbed with {@link #redact}.
     *
     * @throws CapabilityDeniedError when a referenced slot is not the item's to use
     * @throws MissingCredential     when a referenced slot has no value
     */
    public static Map<String, String> resolveHeaders(Map<?, ?> headers, ItemCapabilitySet caps,
                                                     Function<String, Optional<String>> secrets,
                                                     Set<String> used) {
        var out = new LinkedHashMap<String, String>();
        if (headers == null) return out;
        for (var e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(String.valueOf(e.getKey()), resolve(String.valueOf(e.getValue()), caps, secrets, used));
        }
        return out;
    }

    private static String resolve(String value, ItemCapabilitySet caps,
                                  Function<String, Optional<String>> secrets, Set<String> used) {
        Matcher m = REF.matcher(value);
        if (!m.find()) return value;
        var sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            var slot = m.group(1);
            if (!mayUse(caps, slot)) {
                throw new CapabilityDeniedError("safe.get",
                    "slot '" + slot + "' is not in this item's safe_slots");
            }
            Optional<String> secret = secrets == null ? Optional.empty() : secrets.apply(slot);
            if (secret == null || secret.isEmpty() || secret.get().isEmpty()) {
                throw new MissingCredential(slot);
            }
            used.add(secret.get());
            m.appendReplacement(sb, Matcher.quoteReplacement(secret.get()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code text} with every secret in {@code used} replaced by {@code [secret]}. */
    public static String redact(String text, Set<String> used) {
        if (text == null || used == null || used.isEmpty()) return text;
        var out = text;
        for (var secret : used) {
            if (secret != null && !secret.isEmpty()) out = out.replace(secret, MASK);
        }
        return out;
    }

    /** {@link #redact} through maps and lists, for the structured results of {@code world.web.*}. */
    public static Object redactDeep(Object value, Set<String> used) {
        if (used == null || used.isEmpty()) return value;
        return switch (value) {
            case String s -> redact(s, used);
            case Map<?, ?> map -> {
                var copy = new LinkedHashMap<String, Object>();
                for (var e : map.entrySet()) {
                    copy.put(redact(String.valueOf(e.getKey()), used), redactDeep(e.getValue(), used));
                }
                yield copy;
            }
            case List<?> list -> {
                var copy = new ArrayList<Object>(list.size());
                for (var v : list) copy.add(redactDeep(v, used));
                yield copy;
            }
            case null, default -> value;
        };
    }
}
