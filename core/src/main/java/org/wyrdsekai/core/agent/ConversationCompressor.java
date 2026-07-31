package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compresses conversation history when it exceeds a fraction of the context budget.
 *
 * <p>Deterministic extraction — no LLM call. Pattern-matches action types and
 * key nouns from older messages, producing a single summary prefix followed by
 * the most recent messages verbatim.</p>
 */
public final class ConversationCompressor {

    private ConversationCompressor() {}

    /** When conversation exceeds this fraction of usable context, compress. */
    static final double COMPRESS_THRESHOLD = 0.40;

    /** Keep this many recent messages verbatim (never compressed). */
    static final int KEEP_RECENT = 3;

    private static final int CHARS_PER_TOKEN = 4;

    /** JSON action block pattern for extraction. */
    private static final Pattern ACTION_PATTERN =
        Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");

    /** Quoted string pattern for entity extraction. */
    private static final Pattern QUOTED_PATTERN =
        Pattern.compile("\"([^\"]{2,30})\"");

    /**
     * Compress conversation history if it exceeds the budget threshold.
     *
     * @param history             full conversation history (user + assistant messages)
     * @param contextWindowTokens total context window size in tokens
     * @param maxResponseTokens   tokens reserved for response
     * @return compressed history (may be unchanged if under threshold)
     */
    public static List<ChatMessage> compress(
            List<ChatMessage> history,
            int contextWindowTokens,
            int maxResponseTokens) {

        if (history == null || history.size() <= KEEP_RECENT) {
            return history;
        }

        int usableTokens = (int) (contextWindowTokens * 0.85) - maxResponseTokens;
        int historyTokens = history.stream()
            .mapToInt(m -> estimateTokens(m.content()))
            .sum();

        if (historyTokens <= usableTokens * COMPRESS_THRESHOLD) {
            return history; // under threshold — no compression needed
        }

        // Split into older and recent
        int splitPoint = history.size() - KEEP_RECENT;
        var older = history.subList(0, splitPoint);
        var recent = history.subList(splitPoint, history.size());

        // Extract summaries from older messages
        var summaries = new ArrayList<String>();
        for (var msg : older) {
            var summary = summarizeMessage(msg);
            if (summary != null) {
                summaries.add(summary);
            }
        }

        var result = new ArrayList<ChatMessage>();
        if (!summaries.isEmpty()) {
            var summaryText = "[Earlier conversation: " + String.join(". ", summaries) + "]";
            result.add(new ChatMessage("system", summaryText));
        }
        result.addAll(recent);
        return result;
    }

    /**
     * Extract a one-line summary from a single message.
     * Returns null if the message has no useful content to summarize.
     */
    static String summarizeMessage(ChatMessage msg) {
        var content = msg.content();
        if (content == null || content.isBlank()) return null;

        // Extract action type if present
        var actionMatcher = ACTION_PATTERN.matcher(content);
        String actionType = actionMatcher.find() ? actionMatcher.group(1) : null;

        // Determine speaker
        String speaker = "assistant".equals(msg.role()) ? "Agent" : "User";

        if (actionType != null) {
            return speaker + " " + describeActionBriefly(actionType, content);
        }

        // For plain speech, extract first meaningful sentence
        var text = content;
        // Strip any speaker prefix like "Name says: "
        int saysIdx = text.indexOf(" says: ");
        if (saysIdx > 0 && saysIdx < 40) {
            speaker = text.substring(0, saysIdx);
            text = text.substring(saysIdx + 7);
        }

        // Truncate to first sentence
        int end = Math.min(text.length(), 80);
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '.' || text.charAt(i) == '!' || text.charAt(i) == '?') {
                end = i + 1;
                break;
            }
        }
        return speaker + " said: " + text.substring(0, end).trim();
    }

    private static String describeActionBriefly(String actionType, String content) {
        return switch (actionType) {
            case "go_to_room" -> "navigated" + extractTarget(content);
            case "library_search" -> "searched library" + extractQuery(content);
            case "web_search" -> "searched web" + extractQuery(content);
            case "tell_agent" -> "told agent" + extractTarget(content);
            case "create_task_plan" -> "created a task plan";
            case "goal_done" -> "completed a goal";
            case "read_content" -> "read content";
            case "query_oracle" -> "queried Oracle";
            case "remember" -> "remembered something";
            default -> "performed " + actionType.replace('_', ' ');
        };
    }

    private static String extractTarget(String content) {
        var m = Pattern.compile("\"target\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
        return m.find() ? " to " + m.group(1) : "";
    }

    private static String extractQuery(String content) {
        var m = Pattern.compile("\"query\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
        return m.find() ? " for '" + m.group(1) + "'" : "";
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }
}
