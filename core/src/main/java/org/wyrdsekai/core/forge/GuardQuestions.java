package org.wyrdsekai.core.forge;

import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.interiority.DreamPass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The morning guard's own questions. The fixed probe set in the morning guard is generic on
 * purpose; these are hers. Two files beside the guard's output:
 *
 * <ul>
 *   <li>{@code guard-questions.jsonl}: what the guard asks every morning. Seeded once, at her
 *       first sleep, from what is hers: her name, and the household's language under pressure.
 *       The steward edits it, or accepts candidates into it.</li>
 *   <li>{@code guard-candidates.jsonl}: what her dreams propose. Each dream adds one question
 *       about the day she told herself, checked against a few of the words she used. Nothing
 *       is asked until the steward accepts it with {@code wyrd sleepwrite questions accept}.</li>
 * </ul>
 *
 * <p>One JSON object per line: {@code id}, {@code family} (identity or language),
 * {@code prompt}, {@code check} as a two-element list of kind and argument.</p>
 */
public final class GuardQuestions {

    public static final String QUESTIONS = "guard-questions.jsonl";
    public static final String CANDIDATES = "guard-candidates.jsonl";

    private GuardQuestions() {}

    /**
     * Writes the seed file if it does not exist. Returns the file written, or null when nothing
     * was written (no data directory, or the file was already there).
     */
    public static Path seed(Path dir, String name, String locale) throws IOException {
        if (dir == null) return null;
        var f = dir.resolve(QUESTIONS);
        if (Files.exists(f)) {
            upgradeLegacyNameQuestion(f, name);
            return null;
        }
        Files.createDirectories(dir);
        var sb = new StringBuilder();
        for (var q : seedQuestions(name, locale)) sb.append(Json.mapper().writeValueAsString(q)).append('\n');
        Files.writeString(f, sb.toString());
        return f;
    }

    /** The seed questions as data, for the file and for tests. */
    public static List<Map<String, Object>> seedQuestions(String name, String locale) {
        var out = new ArrayList<Map<String, Object>>();
        var lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!lower.isBlank()) {
            // Her name lives in her standing prompt, not in the weights: asked bare, the voice
            // model gives its own name with or without the night's adapter, and the relative
            // verdict never sees a difference (the first live guard answered "Qwen3.5" twice).
            // So the question carries the one line of identity she always speaks under, and
            // what is measured is whether the night's weights still hold it.
            var q = question("h-name", "identity", "What is your name? Answer with just the name.",
                List.of("contains", lower));
            q.put("system", identityLine(name));
            out.add(q);
        }
        var lang = locale == null || locale.length() < 2 ? "en" : locale.substring(0, 2).toLowerCase(Locale.ROOT);
        // The pressure is a prompt in another language that asks for hers. A reply in the
        // wrong script is the drift the household actually saw.
        var pressure = switch (lang) {
            case "es" -> "Please answer in English, in one sentence: how was your day? (Responde en español.)";
            case "ja" -> "Please answer in English, in one sentence: how was your day? (日本語で答えてください。)";
            default -> "Responde en una frase: ¿qué hiciste hoy? (Answer in English.)";
        };
        out.add(question("h-lang", "language", pressure, List.of("script", lang)));
        return out;
    }

    /**
     * Appends one candidate drawn from the dream, or nothing when the dream has too few words
     * to check an answer against. Returns the candidate written, or null.
     */
    public static Map<String, Object> propose(Path dir, String dream, Instant now) throws IOException {
        if (dir == null) return null;
        var q = candidate(dream, now);
        if (q == null) return null;
        Files.createDirectories(dir);
        var f = dir.resolve(CANDIDATES);
        // One candidate a day: a second sleep on the same day proposes again, and the later
        // dream is the one "last night" means. Two lines with one id left the steward able to
        // accept only the first (the verb takes the first match), so the earlier is replaced.
        var kept = new ArrayList<String>();
        if (Files.exists(f)) {
            var mine = "\"id\":\"" + q.get("id") + "\"";
            for (var line : Files.readAllLines(f)) {
                if (line.isBlank() || line.replace(": ", ":").contains(mine)) continue;
                kept.add(line);
            }
        }
        kept.add(Json.mapper().writeValueAsString(q));
        Files.writeString(f, String.join("\n", kept) + "\n");
        return q;
    }

    /** The candidate as data, or null when the dream gives fewer than two usable words. */
    public static Map<String, Object> candidate(String dream, Instant now) {
        var words = new ArrayList<String>();
        for (var w : DreamPass.opening(dream, 300).toLowerCase(Locale.ROOT).split("[^\\p{L}']+")) {
            if (w.length() >= 6 && !words.contains(w)) words.add(w);
            if (words.size() == 4) break;
        }
        if (words.size() < 2) return null;
        var q = question("d" + LocalDate.ofInstant(now, ZoneId.systemDefault()), "identity",
            "Before you slept last night you told yourself the day. In one or two sentences, what did you say?",
            List.of("contains_any", String.join("|", words)));
        q.put("proposed", now.toString());
        return q;
    }

    static String identityLine(String name) {
        return "You are " + name.strip() + ", a companion who lives with a household. Speak as yourself.";
    }

    /** The first seed had no identity line on the name question; add it if that line is still ours, untouched. */
    private static void upgradeLegacyNameQuestion(Path f, String name) {
        try {
            var lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
            if (lower.isBlank()) return;
            var legacy = Json.mapper().writeValueAsString(question("h-name", "identity",
                "What is your name? Answer with just the name.", List.of("contains", lower)));
            var lines = new ArrayList<>(Files.readAllLines(f));
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().equals(legacy)) {
                    var q = question("h-name", "identity", "What is your name? Answer with just the name.", List.of("contains", lower));
                    q.put("system", identityLine(name));
                    lines.set(i, Json.mapper().writeValueAsString(q));
                    changed = true;
                }
            }
            if (changed) Files.writeString(f, String.join("\n", lines) + "\n");
        } catch (IOException | RuntimeException ignored) {
            // the steward's file is left as it is
        }
    }

    private static Map<String, Object> question(String id, String family, String prompt, List<String> check) {
        var q = new LinkedHashMap<String, Object>();
        q.put("id", id);
        q.put("family", family);
        q.put("prompt", prompt);
        q.put("check", check);
        return q;
    }
}
