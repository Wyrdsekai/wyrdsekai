package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.inference.TriageClassifier.Tier.*;

class TriageClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {"hi", "hello", "hey", "good morning", "yo", "howdy"})
    void greetings_classified_as_routine(String input) {
        assertEquals(ROUTINE, TriageClassifier.classify(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ok", "thanks", "yes", "nope", "cool", "got it", "lol"})
    void acknowledgments_classified_as_routine(String input) {
        assertEquals(ROUTINE, TriageClassifier.classify(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hi!", "hello.", "THANKS!", "OK?"})
    void punctuation_and_case_dont_affect_routine(String input) {
        assertEquals(ROUTINE, TriageClassifier.classify(input));
    }

    @Test
    void null_and_blank_are_routine() {
        assertEquals(ROUTINE, TriageClassifier.classify(null));
        assertEquals(ROUTINE, TriageClassifier.classify(""));
        assertEquals(ROUTINE, TriageClassifier.classify("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"look", "go north", "take sword", "inventory", "n", "exits"})
    void mud_commands_return_null(String input) {
        assertNull(TriageClassifier.classify(input));
    }

    @Test
    void short_statements_are_routine() {
        assertEquals(ROUTINE, TriageClassifier.classify("nice day"));
        assertEquals(ROUTINE, TriageClassifier.classify("not bad"));
    }

    @Test
    void medium_statements_are_simple() {
        assertEquals(SIMPLE, TriageClassifier.classify("what time is it"));
        assertEquals(SIMPLE, TriageClassifier.classify("I like this room"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "explain how the soul system works in detail",
        "can you analyze the trade data and compare it with last month",
        "help me with writing a comprehensive research summary",
        "investigate why the predictions are not accurate for this time series"
    })
    void complex_keywords_trigger_complex(String input) {
        assertEquals(COMPLEX, TriageClassifier.classify(input));
    }

    @Test
    void long_input_is_complex() {
        var longInput = "I want to understand " + "word ".repeat(30) + "please";
        assertEquals(COMPLEX, TriageClassifier.classify(longInput));
    }

    @Test
    void long_question_is_complex() {
        assertEquals(COMPLEX, TriageClassifier.classify(
            "why does the inference router select the wrong backend when multiple are available"));
    }

    @Test
    void short_question_is_simple() {
        assertEquals(SIMPLE, TriageClassifier.classify("how are you?"));
    }

    @Test
    void tier_to_capability_mapping() {
        assertEquals("quick", TriageClassifier.tierToCapability(ROUTINE));
        assertEquals("default", TriageClassifier.tierToCapability(SIMPLE));
        assertEquals("reasoning", TriageClassifier.tierToCapability(COMPLEX));
    }
}
