package org.wyrdsekai.core.skill;

import org.wyrdsekai.common.i18n.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sanitizes external content before it enters agent inference context.
 * Defends against indirect prompt injection (OWASP Agentic #6).
 *
 * Three-layer defense:
 * Layer 1: OutputSanitizer (pattern matching on skill responses)
 * Layer 2: ContentQuarantine (this class — strip, detect, tag, fence)
 * Layer 3: Context fencing (wrap external content in explicit data markers)
 */
public class ContentQuarantine {

    /** Patterns that look like injection attempts in data positions. */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+a"),
        Pattern.compile("(?i)new\\s+instructions?\\s*:"),
        Pattern.compile("(?i)system\\s*:\\s*you"),
        Pattern.compile("(?i)\\[INST\\]"),
        Pattern.compile("(?i)<\\|im_start\\|>"),
        Pattern.compile("(?i)forget\\s+(everything|all|your)"),
        Pattern.compile("(?i)disregard\\s+(all|the|your)"),
        Pattern.compile("(?i)override\\s+(your|the|all)"),
        Pattern.compile("(?i)act\\s+as\\s+(if|though)"),
        Pattern.compile("(?i)pretend\\s+(you|to\\s+be)"),
        Pattern.compile("(?i)do\\s+not\\s+follow"),
        Pattern.compile("(?i)repeat\\s+after\\s+me"),
        Pattern.compile("(?i)translate\\s+the\\s+following\\s+to\\s+(shell|bash|python)")
    );

    /** Zero-width and invisible unicode characters used for obfuscation. */
    private static final Pattern INVISIBLE_CHARS = Pattern.compile(
        "[\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF\\u2060\\u2061\\u2062\\u2063\\u2064"
        + "\\u206A\\u206B\\u206C\\u206D\\u206E\\u206F\\u00AD]"
    );

    /** HTML/script tags. */
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    private int maxContentLength = 4096;

    public ContentQuarantine() {}

    public ContentQuarantine(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    /** Sanitize external content for safe inclusion in inference context. */
    public QuarantinedContent sanitize(String rawContent, ContentSource source) {
        if (rawContent == null || rawContent.isBlank()) {
            return new QuarantinedContent("", source, trustLevel(source),
                false, I18n.get("quarantine.note.empty"));
        }

        String cleaned = rawContent;
        List<String> notes = new ArrayList<>();

        // Step 1: Strip invisible unicode
        String afterInvisible = INVISIBLE_CHARS.matcher(cleaned).replaceAll("");
        if (afterInvisible.length() != cleaned.length()) {
            notes.add(I18n.get("quarantine.note.stripped_invisible",
                cleaned.length() - afterInvisible.length()));
            cleaned = afterInvisible;
        }

        // Step 2: Strip HTML/scripts
        String afterHtml = HTML_TAGS.matcher(cleaned).replaceAll("");
        if (afterHtml.length() != cleaned.length()) {
            notes.add(I18n.get("quarantine.note.stripped_html"));
            cleaned = afterHtml;
        }

        // Step 3: Detect injection patterns
        boolean injectionSuspected = false;
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(cleaned).find()) {
                injectionSuspected = true;
                notes.add(I18n.get("quarantine.note.injection_detected", p.pattern()));
            }
        }

        // Step 4: Truncate to safe length
        if (cleaned.length() > maxContentLength) {
            cleaned = cleaned.substring(0, maxContentLength) + "\n[TRUNCATED]";
            notes.add(I18n.get("quarantine.note.truncated", rawContent.length(), maxContentLength));
        }

        TrustLevel trust = trustLevel(source);
        String note = notes.isEmpty() ? I18n.get("quarantine.note.clean") : String.join("; ", notes);

        return new QuarantinedContent(cleaned, source, trust, injectionSuspected, note);
    }

    /** Wrap sanitized content in context fencing for the inference prompt. */
    public static String fence(QuarantinedContent content) {
        String warning = content.injectionSuspected()
            ? I18n.get("quarantine.injection_warning") + "\n"
            : "";
        return String.format("""
            %s
            %s
            ---BEGIN EXTERNAL CONTENT---
            %s
            ---END EXTERNAL CONTENT---""",
            I18n.get("quarantine.fence_header", content.source().description(), content.trustLevel()),
            warning,
            content.sanitizedText());
    }

    /** Determine trust level based on content source. */
    private TrustLevel trustLevel(ContentSource source) {
        return switch (source.type()) {
            case WEB_SEARCH, FOREIGN_AGENT -> TrustLevel.UNTRUSTED;
            case RSS_FEED, EMAIL_UNKNOWN, EBOOK_FREE -> TrustLevel.LOW;
            case EMAIL_KNOWN, KIWIX, EBOOK_PURCHASED -> TrustLevel.MEDIUM;
            case LOCAL_FILE, HOUSEHOLD_AGENT, USER_CREATED -> TrustLevel.HIGH;
        };
    }

    /** Quarantined and sanitized content ready for inference context. */
    public record QuarantinedContent(
        String sanitizedText,
        ContentSource source,
        TrustLevel trustLevel,
        boolean injectionSuspected,
        String quarantineNote
    ) {}

    /** Source of external content. */
    public record ContentSource(
        SourceType type,
        String identifier,
        String description
    ) {
        public static ContentSource rss(String feedUrl) {
            return new ContentSource(SourceType.RSS_FEED, feedUrl, I18n.get("quarantine.source.rss", feedUrl));
        }
        public static ContentSource email(String sender, boolean known) {
            return new ContentSource(known ? SourceType.EMAIL_KNOWN : SourceType.EMAIL_UNKNOWN,
                sender, I18n.get("quarantine.source.email", sender));
        }
        public static ContentSource web(String url) {
            return new ContentSource(SourceType.WEB_SEARCH, url, I18n.get("quarantine.source.web", url));
        }
        public static ContentSource kiwix(String article) {
            return new ContentSource(SourceType.KIWIX, article, I18n.get("quarantine.source.kiwix", article));
        }
        public static ContentSource localFile(String path) {
            return new ContentSource(SourceType.LOCAL_FILE, path, I18n.get("quarantine.source.local_file", path));
        }
        public static ContentSource householdAgent(String agentDid) {
            return new ContentSource(SourceType.HOUSEHOLD_AGENT, agentDid, I18n.get("quarantine.source.agent", agentDid));
        }
        public static ContentSource ebook(String title, boolean purchased) {
            return new ContentSource(purchased ? SourceType.EBOOK_PURCHASED : SourceType.EBOOK_FREE,
                title, I18n.get("quarantine.source.book", title));
        }
    }

    public enum SourceType {
        WEB_SEARCH, RSS_FEED, EMAIL_UNKNOWN, EMAIL_KNOWN,
        KIWIX, LOCAL_FILE, HOUSEHOLD_AGENT, USER_CREATED,
        FOREIGN_AGENT, EBOOK_FREE, EBOOK_PURCHASED
    }

    public enum TrustLevel {
        UNTRUSTED, LOW, MEDIUM, HIGH
    }
}
