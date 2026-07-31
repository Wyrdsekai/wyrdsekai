package org.wyrdsekai.core.external;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * per-item allowlist of external domains
 * an adapter or {@code world.web.post} call may target.
 *
 * <p>Loaded from the manifest's {@code external_domains} field. Each entry
 * is either a literal hostname or a wildcard pattern using {@code *} as
 * the only wildcard character (matches a single label or the rest of the
 * domain depending on position).</p>
 */
public final class DomainAllowlist {

    private final List<Pattern> compiled;

    private DomainAllowlist(List<Pattern> compiled) {
        this.compiled = compiled;
    }

    public static DomainAllowlist of(List<String> patterns) {
        var compiled = new ArrayList<Pattern>();
        if (patterns != null) {
            for (var p : patterns) {
                if (p == null || p.isBlank()) continue;
                compiled.add(compile(p));
            }
        }
        return new DomainAllowlist(List.copyOf(compiled));
    }

    private static Pattern compile(String pattern) {
        // Escape regex special chars except '*'
        var sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            var c = pattern.charAt(i);
            if (c == '*') {
                sb.append("[a-zA-Z0-9_.-]*");
            } else if ("\\.+?()[]{}|^$".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    /** True when the URL's host matches any allowlist entry. */
    public boolean isAllowed(String url) {
        if (compiled.isEmpty()) return false;
        var host = extractHost(url);
        if (host == null) return false;
        for (var p : compiled) {
            if (p.matcher(host).matches()) return true;
        }
        return false;
    }

    public boolean isEmpty() { return compiled.isEmpty(); }

    private static String extractHost(String url) {
        if (url == null) return null;
        try {
            var uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
