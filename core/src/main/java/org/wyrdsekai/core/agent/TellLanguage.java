package org.wyrdsekai.core.agent;

/**
 * Whether a line about to go to a person privately reads as the wrong language for the turn.
 *
 * <p>Room speech has had a language floor since September: a draft that reads as another language
 * is re-rendered before it is said. A reply to a tell had none. It was voiced by the small model
 * with no language pin, checked for nothing but its names, and delivered to the person's session:
 * on a fresh install the first private answer a visitor got was in Spanish (build box, 2026-10-01).
 * Short lines are read with the short-line test, since the detector says nothing under four words.
 */
final class TellLanguage {

    private TellLanguage() {}

    static boolean readsWrong(String line, String expectedLang) {
        if (line == null || line.isBlank() || expectedLang == null) return false;
        if (!CompanionActor.detectorVerifiable(expectedLang)) return false;
        var lang = CompanionActor.detectLanguage(line);
        if (lang != null) return !lang.equals(expectedLang);
        return CompanionActor.offLanguageShort(line, expectedLang);
    }
}
