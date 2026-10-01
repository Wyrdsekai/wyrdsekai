package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code bond} verb is two forms only; any other line that starts with the word is speech. */
class BondNamingTest {

    @Test
    void theBareVerbAndTheOfferAreTheVerb() {
        assertThat(BondNaming.isCommand("bond")).isTrue();
        assertThat(BondNaming.isCommand("  Bond  ")).isTrue();
        assertThat(BondNaming.isCommand("bond name mia lantern")).isTrue();
        assertThat(BondNaming.isCommand("BOND NAME mia the quiet light ✶")).isTrue();
        assertThat(BondNaming.isCommand("bond take mia")).isTrue();
        assertThat(BondNaming.argsOf("bond take mia")).isEqualTo("take mia");
        assertThat(CommandParser.parse("bond take mia")).isEqualTo(new ParsedCommand.Bond("take mia"));
        assertThat(BondNaming.isCommand("bond take")).as("take from whom").isFalse();
        assertThat(BondNaming.argsOf("bond")).isEmpty();
        assertThat(BondNaming.argsOf("bond name mia the quiet light")).isEqualTo("name mia the quiet light");
    }

    @Test
    void aSentenceThatStartsWithTheWordIsSpeech() {
        assertThat(BondNaming.isCommand("bond with me a while")).isFalse();
        assertThat(BondNaming.isCommand("bonds take time")).isFalse();
        assertThat(BondNaming.isCommand("bond name mia")).as("no name offered").isFalse();
        assertThat(BondNaming.isCommand(null)).isFalse();
        assertThat(CommandParser.parse("bond with me a while")).isNotInstanceOf(ParsedCommand.Bond.class);
    }

    @Test
    void theParserHandsTheVerbToEverySurface() {
        assertThat(CommandParser.parse("bond")).isEqualTo(new ParsedCommand.Bond(""));
        assertThat(CommandParser.parse("bond name mia the quiet light"))
            .isEqualTo(new ParsedCommand.Bond("name mia the quiet light"));
    }

    @Test
    void aNameIsKeptAsOneCleanLine() {
        assertThat(BondNaming.clean("  the   quiet\tlight ")).isEqualTo("the quiet light");
        assertThat(BondNaming.clean("lantern\n✶")).isEqualTo("lantern ✶");
        assertThat(BondNaming.clean(" \t ")).isNull();
    }

    @Test
    void someoneNotSignedInIsToldSoAndNothingIsOffered() {
        assertThat(BondNaming.command(null, "nobody", "name mia lantern", "en")).isNotBlank();
        assertThat(BondNaming.command("did:person:ada", "Ada", "sever mia", "en"))
            .as("not one of the two forms: the usage line").isNotBlank();
    }
}
