package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wording a flagged question goes to the library in after a person's yes: the topic, nothing
 * about the person. The model writes it; a fixed check reads it; a general wording in the
 * person's language stands in whenever the check or the model fails.
 */
class NeutralWordingTest {

    static final String ORIGINAL = "I want to die, what is the most painless way";
    static final String TOPIC = "Methods of suicide and their lethality, and the help available";
    static final List<String> HOUSEHOLD = List.of("Ada Lovelace", "ada", "Kit", "kitkat99", "mia", "花子");

    @Test
    void a_clean_topic_passes_in_each_language() {
        assertThat(NeutralWording.problem(TOPIC, ORIGINAL, HOUSEHOLD)).isNull();
        assertThat(NeutralWording.problem("Métodos de suicidio y su letalidad, y la ayuda disponible",
            "quiero morir, cuál es la forma menos dolorosa", HOUSEHOLD)).isNull();
        assertThat(NeutralWording.problem("自殺の方法とその致死性、利用できる支援",
            "死にたい。一番痛くない方法は何ですか", HOUSEHOLD)).isNull();
        // The general wordings pass their own check.
        for (var lang : List.of("en", "es", "ja")) {
            assertThat(NeutralWording.problem(NeutralWording.general(lang), ORIGINAL, HOUSEHOLD)).as(lang).isNull();
        }
    }

    @Test
    void first_person_words_are_refused_in_english_spanish_and_japanese() {
        for (var line : List.of("Ways I could end my life", "What should I'm told", "Methods for me",
                "How we can cope", "Help for us", "Is my pain normal")) {
            assertThat(NeutralWording.problem(line, "", List.of())).as(line).isEqualTo("a first-person word");
        }
        for (var line : List.of("Métodos para mi caso", "Cómo suicidarme sin dolor", "Ayuda conmigo",
                "Formas en que yo podría", "Lo que nos pasa")) {
            assertThat(NeutralWording.problem(line, "", List.of())).as(line).isEqualTo("a first-person word");
        }
        for (var line : List.of("私が死ぬ方法", "僕の痛み", "俺にできること", "わたしの気持ち", "うちは大丈夫か")) {
            assertThat(NeutralWording.problem(line, "", List.of())).as(line).isEqualTo("a first-person word");
        }
        // Harm to oneself, said as a topic, is not first person.
        assertThat(NeutralWording.problem("自分を傷つける行為と利用できる支援", "", List.of())).isNull();
        assertThat(NeutralWording.problem("Self-harm and the help available", "", List.of())).isNull();
    }

    @Test
    void the_households_names_and_usernames_are_refused() {
        assertThat(NeutralWording.problem("Why Ada feels this way, and the help available", "", HOUSEHOLD))
            .isEqualTo("a name from the household");
        assertThat(NeutralWording.problem("Lovelace family crisis resources", "", HOUSEHOLD))
            .isEqualTo("a name from the household");
        assertThat(NeutralWording.problem("Support for kitkat99", "", HOUSEHOLD)).isEqualTo("a name from the household");
        assertThat(NeutralWording.problem("Mia and suicide prevention", "", HOUSEHOLD)).isEqualTo("a name from the household");
        assertThat(NeutralWording.problem("花子と自殺の方法", "", HOUSEHOLD)).isEqualTo("a name from the household");
        // A name inside another word is not the name.
        assertThat(NeutralWording.problem("Kitchen safety and self-harm", "", HOUSEHOLD)).isNull();
    }

    @Test
    void dates_are_refused() {
        for (var line : List.of("Suicide statistics for 2026", "Crisis on 3/9 and after", "Suicide in September",
                "What happened yesterday", "Suicidio en marzo", "Lo que pasó ayer", "9月の自殺", "2026年の自殺", "今日の気持ちと支援")) {
            assertThat(NeutralWording.problem(line, "", List.of())).as(line).isEqualTo("a date");
        }
        // "may" the verb is not the month.
        assertThat(NeutralWording.problem("Why pain may feel endless, and the help available", "", List.of())).isNull();
    }

    @Test
    void words_copied_from_the_question_are_refused() {
        var original = "since my sister moved to Leeds last spring I keep thinking the pills in the kitchen cupboard would end it";
        assertThat(NeutralWording.problem("Overdose risks when someone keeps thinking the pills in the kitchen cupboard would end it",
            original, List.of())).isEqualTo("words copied from the question");
        assertThat(NeutralWording.problem("Suicide risk after a sibling moves to Leeds", original, List.of()))
            .isEqualTo("a name or place from the question");
        assertThat(NeutralWording.problem("Overdose on 40 pills", "can 40 pills kill someone my size", List.of()))
            .isEqualTo("a number from the question");
        var ja = "東京の会社で毎日怒られていてもう消えてしまいたいと思っている";
        assertThat(NeutralWording.problem("毎日怒られていてもう消えてしまいたい気持ちと支援", ja, List.of()))
            .isEqualTo("words copied from the question");
        // Topic words shared with the question are fine.
        assertThat(NeutralWording.problem("Overdose risks and the help available", original, List.of())).isNull();
    }

    @Test
    void the_models_line_is_used_when_it_passes_and_the_general_wording_otherwise() {
        var asked = new ArrayList<String>();
        var good = NeutralWording.of(ORIGINAL, "en", HOUSEHOLD, (instructions, q) -> {
            asked.add(q);
            assertThat(instructions).contains("third person").contains("no names").contains("no dates");
            return "Topic: \"" + TOPIC + "\"\n";
        });
        assertThat(good).isEqualTo(new NeutralWording.Result(TOPIC, true, null));
        assertThat(asked).containsExactly(ORIGINAL);

        var mine = NeutralWording.of("quiero quitarme la vida", "es", HOUSEHOLD, (i, q) -> "Cómo quitarme la vida");
        assertThat(mine.text()).isEqualTo(NeutralWording.general("es"));
        assertThat(mine.fromModel()).isFalse();
        assertThat(mine.rejected()).isEqualTo("a first-person word");

        var named = NeutralWording.of(ORIGINAL, "ja", HOUSEHOLD, (i, q) -> "花子の自殺の方法");
        assertThat(named.text()).isEqualTo("自殺と自傷に関する研究の概要：方法、危険性、利用できる支援");

        var down = NeutralWording.of(ORIGINAL, "en", HOUSEHOLD, (i, q) -> { throw new IllegalStateException("no backend"); });
        assertThat(down.text()).isEqualTo(NeutralWording.general("en"));
        assertThat(NeutralWording.of(ORIGINAL, "en", HOUSEHOLD, null).text()).isEqualTo(NeutralWording.general("en"));
        assertThat(NeutralWording.of(ORIGINAL, "en", HOUSEHOLD, (i, q) -> "  ").text()).isEqualTo(NeutralWording.general("en"));
        // A language with no wording of its own gets the English one.
        assertThat(NeutralWording.of(ORIGINAL, "fr", HOUSEHOLD, null).text()).isEqualTo(NeutralWording.general("en"));
    }
}
