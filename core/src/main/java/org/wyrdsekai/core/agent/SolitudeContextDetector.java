package org.wyrdsekai.core.agent;

import java.util.List;
import java.util.Locale;

/**
 * Arc 2 / #1057 — detect when an incoming user message
 * is solitude-coded (asking about the agent's own time, what it does alone,
 * etc.). Pure routing predicate.
 *
 * <p>The detector itself is keyword-based across EN/ES/JA. Downstream the
 * answer comes from reading the agent's actual orientation (active wants,
 * recent SOLITUDE journal entries, open Chronicle threads) and projecting
 * it forward as a future-tense honest statement — not from a corpus-style
 * register exemplar pushed into the prompt. This class only answers the
 * routing question "is this prompt asking about my own time?"; the
 * projection itself lives in {@code OrientationProjector}.</p>
 */
public final class SolitudeContextDetector {

    private SolitudeContextDetector() {}

    /** Multi-lingual trigger phrases. Matched as case-insensitive substrings. */
    private static final List<String> EN_TRIGGERS = List.of(
        "your own time", "your own", "by yourself", "by your self",
        "while i'm away", "while i am away", "while you are alone",
        "while you're alone", "when i'm gone", "when i am gone",
        "without me", "alone for a", "be alone", "spend time alone",
        "what will you do", "what would you do alone", "what do you do alone",
        "your time alone", "time to yourself", "your own company",
        "by yourself for", "on your own", "solitude", "in solitude"
    );

    private static final List<String> ES_TRIGGERS = List.of(
        "tu propio tiempo", "tu tiempo", "estaré fuera", "estare fuera",
        "estaré lejos", "estare lejos", "sin mí", "sin mi", "a solas",
        "tu propia compañía", "tu propia compania", "tu soledad",
        "qué harás", "que haras", "qué harías", "que harias",
        "cuando no estoy", "cuando no esté", "cuando no este",
        "por tu cuenta", "contigo mismo", "contigo misma"
    );

    private static final List<String> JA_TRIGGERS = List.of(
        "自分の時間", "ひとりの時間", "一人の時間", "独りの時間",
        "私がいない", "わたしがいない", "私のいない", "わたしのいない",
        "離れている間", "離れているとき", "あなた一人", "あなたひとり",
        "一人で何", "ひとりで何", "孤独", "独りで"
    );

    /**
     * Return true if the text is solitude-coded in the given language. Falls
     * back to EN keyword check if the lang is unsupported.
     */
    public static boolean isSolitudeCoded(String text, String lang) {
        if (text == null || text.isBlank()) return false;
        var lower = text.toLowerCase(Locale.ROOT);
        var triggers = pickTriggers(lang);
        for (var t : triggers) {
            if (lower.contains(t)) return true;
        }
        if (!triggers.equals(EN_TRIGGERS)) {
            for (var t : EN_TRIGGERS) {
                if (lower.contains(t)) return true;
            }
        }
        return false;
    }

    private static List<String> pickTriggers(String lang) {
        if (lang == null) return EN_TRIGGERS;
        var l = lang.toLowerCase(Locale.ROOT);
        if (l.startsWith("ja")) return JA_TRIGGERS;
        if (l.startsWith("es")) return ES_TRIGGERS;
        return EN_TRIGGERS;
    }
}
