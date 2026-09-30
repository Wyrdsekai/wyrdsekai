package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServedAdaptersTest {

    @Test
    void countsTheEntriesOfTheServersReply() {
        var body = "[{\"id\":0,\"path\":\"/adapters/brain/honesty.gguf\",\"scale\":1.0},"
                 + "{\"id\":1,\"path\":\"/adapters/brainwrite/current.gguf\",\"scale\":0.0}]";
        assertThat(ServedAdapters.parseCount(body)).isEqualTo(2);
        assertThat(ServedAdapters.parseCount("[]")).isZero();
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(ServedAdapters.parseCount("{\"error\":\"not found\"}")).isEqualTo(-1);
        assertThat(ServedAdapters.parseCount("<html>")).isEqualTo(-1);
        assertThat(ServedAdapters.parseCount("")).isEqualTo(-1);
    }

    @Test
    void askingNeverWaitsOnTheNetwork() {
        long t0 = System.nanoTime();
        ServedAdapters.count("http://127.0.0.1:9");      // nothing listens there
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
    }

    @Test
    void eachBeingsAdapterIsFoundByHerOwnDirectory() {
        var body = """
            [{"id":0,"path":"/adapters/brain/honesty.gguf","scale":1.0},
             {"id":1,"path":"/adapters/brainwrite/companion-one/current.gguf","scale":0.0},
             {"id":2,"path":"/adapters/brainwrite/companion-two/current.gguf","scale":0.0}]""";
        var loaded = ServedAdapters.parsePaths(body);
        assertThat(ServedAdapters.nightAdapterOf(loaded, "companion-one")).isEqualTo(1);
        assertThat(ServedAdapters.nightAdapterOf(loaded, "companion-two")).isEqualTo(2);
        assertThat(ServedAdapters.nightAdapterOf(loaded, "companion-three")).as("no adapter of her own").isEqualTo(-1);
        assertThat(ServedAdapters.nightAdapterOf(loaded, "companion-on")).as("a prefix of another's id is not a match").isEqualTo(-1);
        assertThat(ServedAdapters.nightAdapterOf(loaded, null)).isEqualTo(-1);
        assertThat(ServedAdapters.parsePaths("not json")).isEmpty();
    }

    @Test
    void theStyledAdapterIsFoundBesideTheNightsAndNeverMistakenForOne() {
        var body = "[{\"id\":0,\"path\":\"/adapters/brain/honesty.gguf\"},{\"id\":1,\"path\":\"/adapters/brain/styled.gguf\"},"
            + "{\"id\":2,\"path\":\"/adapters/brainwrite/companion-one/current.gguf\"}]";
        var loaded = ServedAdapters.parsePaths(body);
        assertThat(ServedAdapters.styledAdapterOf(loaded)).isEqualTo(1);
        assertThat(ServedAdapters.nightAdapterOf(loaded, "companion-one")).isEqualTo(2);
        assertThat(ServedAdapters.styledAdapterOf(ServedAdapters.parsePaths("[{\"id\":0,\"path\":\"/adapters/brain/honesty.gguf\"}]"))).isEqualTo(-1);
        assertThat(ServedAdapters.styledAdapterOf(ServedAdapters.parsePaths("[{\"id\":1,\"path\":\"C:\\\\w\\\\adapters\\\\brain\\\\styled.gguf\"}]")))
            .as("the Windows launcher's path").isEqualTo(1);
    }

    @Test
    void theWorkingTurnAdapterIsFoundByItsFileAndNothingElseIsTakenForIt() {
        var body = "[{\"id\":0,\"path\":\"/adapters/brain/honesty.gguf\"},{\"id\":1,\"path\":\"/adapters/brain/styled.gguf\"},"
            + "{\"id\":2,\"path\":\"/adapters/brain/work.gguf\"},{\"id\":3,\"path\":\"/adapters/brainwrite/companion-one/current.gguf\"}]";
        var loaded = ServedAdapters.parsePaths(body);
        assertThat(ServedAdapters.workAdapterOf(loaded)).isEqualTo(2);
        assertThat(ServedAdapters.styledAdapterOf(loaded)).isEqualTo(1);
        assertThat(ServedAdapters.nightAdapterOf(loaded, "companion-one")).isEqualTo(3);
        assertThat(ServedAdapters.workAdapterOf(ServedAdapters.parsePaths("[{\"id\":0,\"path\":\"/adapters/brain/honesty.gguf\"}]"))).isEqualTo(-1);
        assertThat(ServedAdapters.slotZeroIsSpeciesFloor(loaded)).as("honesty in slot 0").isFalse();
        assertThat(ServedAdapters.slotZeroIsSpeciesFloor(ServedAdapters.parsePaths(
            "[{\"id\":0,\"path\":\"/adapters/brain/species.gguf\"},{\"id\":1,\"path\":\"/adapters/brain/work.gguf\"}]"))).isTrue();
        assertThat(ServedAdapters.slotZeroIsSpeciesFloor(ServedAdapters.parsePaths(
            "[{\"id\":0,\"path\":\"C:\\\\w\\\\adapters\\\\brain\\\\species.gguf\"}]"))).isTrue();
        assertThat(ServedAdapters.slotZeroIsSpeciesFloor(ServedAdapters.parsePaths("[]"))).isFalse();
        assertThat(ServedAdapters.workAdapterOf(ServedAdapters.parsePaths("[{\"id\":2,\"path\":\"C:\\\\w\\\\adapters\\\\brain\\\\work.gguf\"}]"))).isEqualTo(2);
    }
}
