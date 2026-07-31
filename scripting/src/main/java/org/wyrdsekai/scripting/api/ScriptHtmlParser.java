package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-backed HTML parser exposed to GraalJS scripts.
 * Available at {@link org.wyrdsekai.scripting.sandbox.SandboxLevel#SKILL_BASIC} and above.
 *
 * <p>Uses regex-based parsing (no external dependency like JSoup).
 * Supports simple CSS selectors: tag names, classes, and IDs.
 *
 * <p>Scripts use this as:
 * <pre>
 *   var title = html.select(page, "title");
 *   var links = html.selectAll(page, "a");
 *   var plain = html.text(page);
 * </pre>
 */
public class ScriptHtmlParser {

    private static final Logger log = LoggerFactory.getLogger(ScriptHtmlParser.class);

    /** Pattern to match HTML tags with content. */
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "<\\s*([a-zA-Z][a-zA-Z0-9]*)([^>]*)>(.*?)</\\s*\\1\\s*>",
        Pattern.DOTALL
    );

    /** Pattern to match all HTML tags (for stripping). */
    private static final Pattern STRIP_TAGS = Pattern.compile("<[^>]+>");

    /** Pattern to match HTML entities. */
    private static final Pattern HTML_ENTITIES = Pattern.compile("&(amp|lt|gt|quot|apos|nbsp|#\\d+);");

    /**
     * Select the first element matching a simple CSS selector.
     * Supported selectors: tag, .class, #id, tag.class, tag#id.
     *
     * @param html        The HTML content
     * @param cssSelector Simple CSS selector
     * @return Inner HTML of the first matching element, or empty string if not found
     */
    @HostAccess.Export
    public String select(String html, String cssSelector) {
        if (html == null || html.isBlank() || cssSelector == null || cssSelector.isBlank()) {
            return "";
        }
        var matches = findMatches(html, cssSelector);
        return matches.isEmpty() ? "" : matches.getFirst();
    }

    /**
     * Select all elements matching a simple CSS selector.
     *
     * @param html        The HTML content
     * @param cssSelector Simple CSS selector
     * @return List of inner HTML strings for all matching elements
     */
    @HostAccess.Export
    public List<String> selectAll(String html, String cssSelector) {
        if (html == null || html.isBlank() || cssSelector == null || cssSelector.isBlank()) {
            return List.of();
        }
        return findMatches(html, cssSelector);
    }

    /**
     * Strip all HTML tags and return plain text.
     * Decodes common HTML entities.
     *
     * @param html The HTML content
     * @return Plain text content
     */
    @HostAccess.Export
    public String text(String html) {
        if (html == null || html.isBlank()) return "";

        // Remove script and style blocks
        String cleaned = html.replaceAll("(?si)<script[^>]*>.*?</script>", "");
        cleaned = cleaned.replaceAll("(?si)<style[^>]*>.*?</style>", "");

        // Strip tags
        cleaned = STRIP_TAGS.matcher(cleaned).replaceAll("");

        // Decode entities
        cleaned = decodeEntities(cleaned);

        // Collapse whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned;
    }

    /**
     * Find all matching elements for a simple CSS selector.
     * Supports: tag, .class, #id, tag.class, tag#id
     */
    private List<String> findMatches(String html, String selector) {
        var results = new ArrayList<String>();
        var parsed = parseSelector(selector);

        Matcher matcher = TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            String tagName = matcher.group(1).toLowerCase();
            String attributes = matcher.group(2);
            String innerHtml = matcher.group(3);

            if (matchesSelector(tagName, attributes, parsed)) {
                results.add(innerHtml.trim());
            }
        }
        return results;
    }

    /**
     * Parse a simple CSS selector into its components.
     */
    private SelectorParts parseSelector(String selector) {
        String tag = null;
        String className = null;
        String id = null;

        String s = selector.trim();

        // Check for ID
        int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            id = s.substring(hashIdx + 1);
            // Check for . in the ID part
            int dotInId = id.indexOf('.');
            if (dotInId >= 0) {
                className = id.substring(dotInId + 1);
                id = id.substring(0, dotInId);
            }
            s = s.substring(0, hashIdx);
        }

        // Check for class
        int dotIdx = s.indexOf('.');
        if (dotIdx >= 0) {
            if (className == null) {
                className = s.substring(dotIdx + 1);
            }
            s = s.substring(0, dotIdx);
        }

        if (!s.isEmpty()) {
            tag = s.toLowerCase();
        }

        return new SelectorParts(tag, className, id);
    }

    private boolean matchesSelector(String tagName, String attributes, SelectorParts selector) {
        // Match tag
        if (selector.tag != null && !selector.tag.equals(tagName)) {
            return false;
        }

        // Match class
        if (selector.className != null) {
            String classAttr = extractAttribute(attributes, "class");
            if (classAttr == null) return false;
            boolean found = false;
            for (String cls : classAttr.split("\\s+")) {
                if (cls.equals(selector.className)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }

        // Match id
        if (selector.id != null) {
            String idAttr = extractAttribute(attributes, "id");
            if (!selector.id.equals(idAttr)) return false;
        }

        // If no selector components were specified, match nothing
        return selector.tag != null || selector.className != null || selector.id != null;
    }

    /**
     * Extract an attribute value from an HTML tag's attribute string.
     */
    private String extractAttribute(String attributes, String attrName) {
        if (attributes == null || attributes.isBlank()) return null;

        // Match attribute="value" or attribute='value'
        Pattern p = Pattern.compile("\\b" + Pattern.quote(attrName) + "\\s*=\\s*[\"']([^\"']*)[\"']");
        Matcher m = p.matcher(attributes);
        return m.find() ? m.group(1) : null;
    }

    private String decodeEntities(String text) {
        return HTML_ENTITIES.matcher(text).replaceAll(mr -> switch (mr.group(1)) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            default -> {
                // Numeric entities (&#123;)
                String num = mr.group(1);
                if (num.startsWith("#")) {
                    try {
                        yield String.valueOf((char) Integer.parseInt(num.substring(1)));
                    } catch (NumberFormatException e) {
                        yield mr.group(0);
                    }
                }
                yield mr.group(0);
            }
        });
    }

    private record SelectorParts(String tag, String className, String id) {}
}
