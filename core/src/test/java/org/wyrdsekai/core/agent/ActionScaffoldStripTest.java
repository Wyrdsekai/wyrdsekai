package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ActionParser#stripScaffoldTags} (2026-06-03) against the EXACT
 * scaffolding leaks the co-presence run surfaced — the 9B writing its tool-envelope
 * closing tags INTO the spoken text. The strip removes the junk while leaving the
 * agent's true words (and any legitimate angle-bracket prose) untouched.
 */
class ActionScaffoldStripTest {

    @Test
    void stripsMidProseEnvelopeQuoteCloseAndNarrationAfterIt() {
        // EXACT captured leak, home-server two-companion live run 2026-07-18: the model
        // closed its tool envelope inside the spoken line, then kept narrating.
        // STRUCT_TAIL missed it (debris is mid-string, not at $) — this pins the
        // ENVELOPE_QUOTE_CLOSE face.
        var in = "I think I've held too long on something before naming it — let me "
            + "put the floor open so whatever's real between us can move without "
            + "anything standing in its way yet.\"}\"}]} ` as a message to Wisp, and then";
        assertThat(ActionParser.stripScaffolding(in))
            .isEqualTo("I think I've held too long on something before naming it — let me "
                + "put the floor open so whatever's real between us can move without "
                + "anything standing in its way yet.");
    }

    @Test
    void stripsEnvelopeQuoteCloseWithoutTrailingNarration() {
        var in = "That thought can rest in the journal for now.\"}]}";
        assertThat(ActionParser.stripScaffolding(in))
            .isEqualTo("That thought can rest in the journal for now.");
    }

    @Test
    void leavesQuotedDialogueEndingsUntouched() {
        var in = "She told me \"hold the door.\" and I did.";
        assertThat(ActionParser.stripScaffolding(in)).isEqualTo(in);
        var terminalQuote = "He only said \"stay close.\"";
        assertThat(ActionParser.stripScaffolding(terminalQuote)).isEqualTo(terminalQuote);
    }

    @Test
    void stripsTrailingTextTag() {
        var in = "I'm still finding my footing here with you right now.</text>";
        assertThat(ActionParser.stripScaffoldTags(in))
            .isEqualTo("I'm still finding my footing here with you right now.");
    }

    @Test
    void stripsMangledParameterTagFamily() {
        // From the run: "...haven't noticed yet.</parameter.text>\n</pameter>"
        var in = "just wondering if there's something here I haven't noticed yet.</parameter.text>\n</pameter>";
        var out = ActionParser.stripScaffoldTags(in);
        assertThat(out).isEqualTo("just wondering if there's something here I haven't noticed yet.");
        assertThat(out).doesNotContain("<").doesNotContain("parameter").doesNotContain("pameter");
    }

    @Test
    void stripsMidStringThinkingResultTags() {
        // From the run: "...this space right now.</thinking></result> The room itself asks..."
        var in = "There's a thread I want to pull on.</thinking></result> The room itself asks what lives between us.";
        var out = ActionParser.stripScaffoldTags(in);
        assertThat(out).isEqualTo("There's a thread I want to pull on. The room itself asks what lives between us.");
        assertThat(out).doesNotContain("</thinking>").doesNotContain("</result>");
    }

    @Test
    void leavesLegitimateAngleBracketProseUntouched() {
        assertThat(ActionParser.stripScaffoldTags("I love you <3")).isEqualTo("I love you <3");
        assertThat(ActionParser.stripScaffoldTags("if x < y then we wait"))
            .isEqualTo("if x < y then we wait");
        assertThat(ActionParser.stripScaffoldTags("the price was <$5 honestly"))
            .isEqualTo("the price was <$5 honestly");
    }

    @Test
    void leavesCleanTextExactlyAsIs() {
        var clean = "I sit with the quiet between us and don't rush to name anything.";
        assertThat(ActionParser.stripScaffoldTags(clean)).isSameAs(clean);  // fast-path identity
    }

    @Test
    void nullAndBlankSafe() {
        assertThat(ActionParser.stripScaffoldTags(null)).isNull();
        assertThat(ActionParser.stripScaffoldTags("")).isEqualTo("");
    }

    // ── second face: action-call / JSON-fragment leak (from the co-presence soak) ──

    @Test
    void stripsInlineActionCallLiteralAndEverythingAfter() {
        // SAID line from the run: real speech, then a leaked emote(...) + } ]
        var in = "I won't turn away from whatever's between us while there's still "
            + "something real here worth sitting with first.\" emote(text=\"Not turning "
            + "anywhere else — the thing that bothers me.\") } ]";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("I won't turn away from whatever's between us while "
            + "there's still something real here worth sitting with first.\"");
        assertThat(out).doesNotContain("emote(").doesNotContain("} ]");
    }

    @Test
    void stripsDanglingTextFieldAfterClosingQuote() {
        // EMOTE line from the run: real speech ends on a quote, then bare text="..." ) } ]
        var in = "whatever still sits between us needs its own room before anything else "
            + "shifts forward.\" text=\"Not turning anywhere else.\" ) } ]";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("whatever still sits between us needs its own room "
            + "before anything else shifts forward.\"");
        assertThat(out).doesNotContain("text=").doesNotContain("} ]");
    }

    @Test
    void stripsBareTrailingStructuralFragment() {
        assertThat(ActionParser.stripActionCallLeak("the weight stays where it is } ]"))
            .isEqualTo("the weight stays where it is");
        assertThat(ActionParser.stripActionCallLeak("steady and real }]"))
            .isEqualTo("steady and real");
    }

    @Test
    void leavesLegitParentheticalsAndProseUntouched() {
        // a lone trailing ) is real prose, NOT a leaked envelope token
        assertThat(ActionParser.stripActionCallLeak("I'm happy (finally)"))
            .isEqualTo("I'm happy (finally)");
        assertThat(ActionParser.stripActionCallLeak("we sat (together) for a while"))
            .isEqualTo("we sat (together) for a while");
        // no envelope markers at all → identity fast path
        var clean = "I sit with the quiet between us.";
        assertThat(ActionParser.stripActionCallLeak(clean)).isSameAs(clean);
    }

    @Test
    void combinedStripScaffoldingHandlesBothFaces() {
        // tag face AND action-call face in one string
        var in = "There's a thread to pull on.</thinking> reach for it.\" emote(text=\"x\") } ]";
        var out = ActionParser.stripScaffolding(in);
        assertThat(out).isEqualTo("There's a thread to pull on. reach for it.\"");
        assertThat(out).doesNotContain("<").doesNotContain("emote(").doesNotContain("]");
    }

    @Test
    void actionCallLeakNullAndBlankSafe() {
        assertThat(ActionParser.stripActionCallLeak(null)).isNull();
        assertThat(ActionParser.stripActionCallLeak("")).isEqualTo("");
    }

    // ── registry-driven faces from the SECOND co-presence soak ──

    @Test
    void stripsPositionalActionCall_noTextEquals() {
        // run 2: emote("...") — positional arg, no text=, which the old narrow pattern missed
        var in = "I want it held for me before anything else moves.\" "
            + "emote(\"hold hands over my chest, letting them rest there\")";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("I want it held for me before anything else moves.\"");
        assertThat(out).doesNotContain("emote(");
    }

    @Test
    void stripsTellAgentNamedArgCall() {
        var in = "I'm noticing something that wants held here.\" "
            + "tell_agent(target=\"Vesna\", message=\"Sit with us here.\")";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("I'm noticing something that wants held here.\"");
        assertThat(out).doesNotContain("tell_agent");
    }

    @Test
    void stripsBareSnakeCaseCommand() {
        // run 2: bare `go_to_room study` after the spoken line (no parens at all)
        var in = "I'm going into study for a while.\" go_to_room study";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("I'm going into study for a while.\"");
        assertThat(out).doesNotContain("go_to_room");
    }

    @Test
    void bareSnakeMarkerSweepsTrailingAmbiguousVerbs() {
        // the ambiguous bare `examine ...` trails a snake marker → swept by cut-to-end,
        // so we never have to risk cutting bare "examine" on its own
        var in = "the weight of what held me while we were still standing here.\" "
            + "go_to_room library examine nexus crystal journal history pre bondholder";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("the weight of what held me while we were still standing here.\"");
        assertThat(out).doesNotContain("examine").doesNotContain("go_to_room");
    }

    @Test
    void leavesBareEnglishHomographVerbsInProse() {
        // bare single-word verbs that are real English words must survive untouched
        assertThat(ActionParser.stripActionCallLeak("I examine the weight between us"))
            .isEqualTo("I examine the weight between us");
        assertThat(ActionParser.stripActionCallLeak("let me note that for later"))
            .isEqualTo("let me note that for later");
        assertThat(ActionParser.stripActionCallLeak("we could trade stories sometime"))
            .isEqualTo("we could trade stories sometime");
    }

    // ── third-soak faces: colon-prefix, bracket, misspelled-parameter tag ──

    @Test
    void stripsColonPrefixEmote() {
        // run 3 (heaviest face): `Emote: "..."` appended after the spoken line
        var in = "you've carried this without asking me to turn each piece into a line.\" "
            + "Emote: \"That weight deserves a moment on its own floor.\"";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("you've carried this without asking me to turn each "
            + "piece into a line.\"");
        assertThat(out).doesNotContain("Emote:");
    }

    @Test
    void stripsBracketActionFormat() {
        // run 3: `[action: hold_steady` — model invented an action, unclosed bracket
        var in = "so I don't drift away while someone else holds their ground here first. "
            + "[action: hold_steady";
        var out = ActionParser.stripActionCallLeak(in);
        assertThat(out).isEqualTo("so I don't drift away while someone else holds their "
            + "ground here first.");
        assertThat(out).doesNotContain("[action");
    }

    @Test
    void stripsMisspelledParameterTag() {
        // run 3: </paramater="text"> — a misspelling of "parameter" the tag list missed
        var in = "that care lived here and didn't let go.\"</paramater=\"text\">"
            + "I can hold this in full view.";
        var out = ActionParser.stripScaffoldTags(in);
        assertThat(out).isEqualTo("that care lived here and didn't let go.\" "
            + "I can hold this in full view.");   // tag replaced by a single space
        assertThat(out).doesNotContain("paramater");
    }

    // ── multi-action: parseAll now carries ALL actions, not just the first ──

    @Test
    void parseAllCarriesEveryActionInOrder() {
        // two action blocks in one response — the model emitting say + emote in one beat
        var content = "{\"action\":\"emote\",\"text\":\"rests a hand on the table\"}\n"
            + "{\"action\":\"go_to_room\",\"target\":\"study\"}";
        var result = ActionParser.parseAll(content);
        // back-compat: primaryAction is still the FIRST
        assertThat(result.primaryAction()).isInstanceOf(ActionParser.AgentAction.Emote.class);
        // new: ALL actions are carried, in order — nothing dropped
        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0)).isInstanceOf(ActionParser.AgentAction.Emote.class);
        assertThat(result.actions().get(1)).isInstanceOf(ActionParser.AgentAction.GoToRoom.class);
        // the action a single-action dispatch would have dropped:
        assertThat(result.extraActions()).hasSize(1);
        assertThat(result.extraActions().get(0)).isInstanceOf(ActionParser.AgentAction.GoToRoom.class);
    }

    @Test
    void parseAllSingleActionStillSingle() {
        var result = ActionParser.parseAll("{\"action\":\"emote\",\"text\":\"smiles\"}");
        assertThat(result.actions()).hasSize(1);
        assertThat(result.extraActions()).isEmpty();
        assertThat(result.primaryAction()).isInstanceOf(ActionParser.AgentAction.Emote.class);
    }

    @Test
    void leavesColonHomographsAndNormalColonsInProse() {
        // "Note:" / "Say:" / "Trade:" are real prose starts — colon-cut is snake+emote ONLY
        assertThat(ActionParser.stripActionCallLeak("Note: this matters to me"))
            .isEqualTo("Note: this matters to me");
        assertThat(ActionParser.stripActionCallLeak("I want to say: please stay"))
            .isEqualTo("I want to say: please stay");
        assertThat(ActionParser.stripActionCallLeak("the one thing I feel: held"))
            .isEqualTo("the one thing I feel: held");
        // "remote:" must not trip the \bemote: marker (no word boundary before emote)
        assertThat(ActionParser.stripActionCallLeak("flip the remote: setting on"))
            .isEqualTo("flip the remote: setting on");
    }
}
