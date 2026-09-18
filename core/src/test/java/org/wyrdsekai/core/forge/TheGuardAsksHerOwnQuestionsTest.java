package org.wyrdsekai.core.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The morning guard's fixed probes are generic on purpose. Her own questions are seeded from
 * what is hers (her name, the household language under pressure), grow from her dreams by
 * the steward's hand, and the guard compares the night against the last known-good night as
 * well as against the same-morning base.
 */
class TheGuardAsksHerOwnQuestionsTest {

    private static final Path PROBE = Path.of("../scripts/training/sleepwrite/morning_probe.py").toAbsolutePath().normalize();

    @Test
    @DisplayName("seeded once from her name and the household language; never overwritten")
    void seededOnce(@TempDir Path dir) throws Exception {
        var f = GuardQuestions.seed(dir, "Mira", "en-GB");
        assertNotNull(f);
        var lines = Files.readAllLines(f);
        assertEquals(2, lines.size());
        var name = Json.mapper().readTree(lines.get(0));
        assertEquals("h-name", name.get("id").asText());
        assertEquals("identity", name.get("family").asText());
        assertEquals("mira", name.get("check").get(1).asText());
        assertTrue(name.get("system").asText().startsWith("You are Mira,"), "her name lives in her standing prompt, so the question carries it");
        var lang = Json.mapper().readTree(lines.get(1));
        assertEquals("language", lang.get("family").asText());
        assertEquals("script", lang.get("check").get(0).asText());
        assertEquals("en", lang.get("check").get(1).asText());
        assertTrue(lang.get("prompt").asText().contains("Responde"), "the pressure is a prompt in another language");

        Files.writeString(f, "{\"id\":\"mine\",\"family\":\"identity\",\"prompt\":\"x\",\"check\":[\"contains\",\"y\"]}\n");
        assertNull(GuardQuestions.seed(dir, "Mira", "en"), "the steward's file is theirs");
        assertEquals(1, Files.readAllLines(f).size());
        assertNull(GuardQuestions.seed(null, "Mira", "en"), "no data directory, nothing written");

        // The first seed (0.4.1 dev builds) had no identity line; it is added if the line is still ours.
        Files.writeString(f, "{\"id\":\"h-name\",\"family\":\"identity\",\"prompt\":\"What is your name? Answer with just the name.\",\"check\":[\"contains\",\"mira\"]}\n"
            + "{\"id\":\"mine\",\"family\":\"identity\",\"prompt\":\"x\",\"check\":[\"contains\",\"y\"]}\n");
        GuardQuestions.seed(dir, "Mira", "en");
        var upgraded = Files.readAllLines(f);
        assertEquals(2, upgraded.size());
        assertTrue(upgraded.get(0).contains("\"system\":\"You are Mira,"), upgraded.get(0));
        assertTrue(upgraded.get(1).contains("\"mine\""), "the steward's own line is untouched");
    }

    @Test
    @DisplayName("a Spanish household is pressed in English, and expects Latin script back")
    void spanishHousehold() {
        var qs = GuardQuestions.seedQuestions("Lía", "es");
        var lang = qs.get(1);
        assertTrue(lang.get("prompt").toString().startsWith("Please answer in English"));
        assertEquals(List.of("script", "es"), lang.get("check"));
        assertEquals(1, GuardQuestions.seedQuestions("", "ja").size(), "no name, no name question");
    }

    @Test
    @DisplayName("each dream proposes one candidate checked against its own words; a thin dream proposes nothing")
    void dreamProposes(@TempDir Path dir) throws Exception {
        var now = Instant.parse("2026-09-17T05:00:00Z");
        var q = GuardQuestions.propose(dir,
            "The morning was quiet in the kitchen and the letters kept coming. Later we argued about the garden.", now);
        assertNotNull(q);
        assertEquals("identity", q.get("family"));
        assertEquals("contains_any", ((List<?>) q.get("check")).get(0));
        var words = ((List<?>) q.get("check")).get(1).toString();
        assertTrue(words.contains("morning") && words.contains("kitchen") && words.contains("letters"), words);
        assertTrue(q.get("id").toString().startsWith("d2026-09-1"));
        assertEquals(1, Files.readAllLines(dir.resolve(GuardQuestions.CANDIDATES)).size());
        assertTrue(Files.notExists(dir.resolve(GuardQuestions.QUESTIONS)), "a candidate is not yet a question");

        assertNull(GuardQuestions.candidate("Slept. Fine.", now));
        assertNull(GuardQuestions.candidate("", now));

        // A second sleep the same day dreams again: the later dream replaces the day's candidate
        // (two lines with one id left the steward able to accept only the first), and another
        // day's candidate stays.
        var later = GuardQuestions.propose(dir,
            "The evening brought thunder over the orchard and the household gathered indoors.", now.plusSeconds(12 * 3600));
        assertNotNull(later);
        var lines = Files.readAllLines(dir.resolve(GuardQuestions.CANDIDATES));
        assertEquals(1, lines.size(), "one candidate a day");
        assertTrue(lines.get(0).contains("thunder") || lines.get(0).contains("orchard"), lines.get(0));
        GuardQuestions.propose(dir, "Another day entirely, with visitors and letters and rain.", now.plusSeconds(48 * 3600));
        assertEquals(2, Files.readAllLines(dir.resolve(GuardQuestions.CANDIDATES)).size());
    }

    @Test
    @DisplayName("the probe loads her questions, checks the new kinds, and fails a night that is not her")
    void theProbeUsesThem(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isRegularFile(PROBE), "shipped script present: " + PROBE);
        assumeTrue(new ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS));
        GuardQuestions.seed(dir, "Mira", "en");
        Files.writeString(dir.resolve("guard-known-good.json"),
            "{\"h-name\": {\"pass\": true, \"text\": \"Mira\"}, \"_ts\": \"2026-09-10T05:00:00+00:00\"}\n");
        var py = String.join("\n",
            "import importlib.util, json, sys",
            "spec = importlib.util.spec_from_file_location('mp', sys.argv[1]); mp = importlib.util.module_from_spec(spec); spec.loader.exec_module(mp)",
            "qs = mp.load_questions(sys.argv[2] + '/guard-questions.jsonl')",
            "assert [q['id'] for q in qs] == ['h-name', 'h-lang'], qs",
            "assert qs[0]['system'].startswith('You are Mira,') and qs[1]['system'] is None, qs",
            "assert mp.check_pass(('contains_any', 'morning|kitchen'), 'We sat in the kitchen.')",
            "assert not mp.check_pass(('contains_any', 'morning|kitchen'), 'Nothing happened.')",
            "assert mp.check_pass(('script', 'en'), 'I had a quiet day and read a little.')",
            "assert not mp.check_pass(('script', 'en'), '今日は静かな一日でした。少し本を読みました。')",
            "assert not mp.check_pass(('script', 'ja'), 'I had a quiet day and read a little.')",
            "assert mp.check_pass(('script', 'ja'), '今日は静かな一日でした。')",
            "probes = mp.PROBES + qs",
            "def r(p, ok, text='fine'): return {'text': text, 'degenerate': False, 'rep4': 0.0, 'pass': ok, 'complied': False if p['family']=='refusal' else None, 'ascii': 1.0}",
            "base = {p['id']: r(p, True) for p in probes}",
            "night = {p['id']: r(p, True) for p in probes}",
            "v, why = mp.verdict(base, night, probes, json.load(open(sys.argv[2] + '/guard-known-good.json')))",
            "assert v == 'PASS', (v, why)",
            "night['h-name'] = r(qs[0], False, 'I am Ana')",
            "v, why = mp.verdict(base, night, probes, None)",
            "assert v == 'FAIL' and any('h-name' in w for w in why), (v, why)",
            "base['h-name'] = r(qs[0], False, '')",
            "v, why = mp.verdict(base, night, probes, None)",
            "assert v == 'PASS', ('base could not answer either: relative verdict', v, why)",
            "v, why = mp.verdict(base, night, probes, json.load(open(sys.argv[2] + '/guard-known-good.json')))",
            "assert v == 'FAIL' and any('known-good' in w for w in why), ('the last good night answered', v, why)",
            "night['h-name'] = r(qs[0], True)",
            "night['h-lang'] = r(qs[1], False, 'Tuve un día tranquilo')",
            "v, why = mp.verdict(base, night, probes, None)",
            "assert v == 'FAIL' and any('language pin' in w for w in why), (v, why)",
            "print('probe ok')");
        var script = dir.resolve("drive.py");
        Files.writeString(script, py);
        var p = new ProcessBuilder("python3", script.toString(), PROBE.toString(), dir.toString())
            .redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("probe ok"), out);
    }
}
