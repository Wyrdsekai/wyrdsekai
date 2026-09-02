package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The steward feed: a record of what she did unasked, in two channels. */
class StewardFeedTest {

    @Test
    void defaults_land_in_the_data_dir_with_the_making_family_on_the_desk(@TempDir Path data) {
        var feed = StewardFeed.fromEnv(data, k -> null);
        assertThat(feed.logFile()).isEqualTo(data.resolve("steward-feed.jsonl"));
        assertThat(feed.wantsDesk("creation")).isTrue();
        assertThat(feed.wantsDesk("workshop")).isTrue();
        assertThat(feed.wantsDesk("social")).as("emotes never reach the desk").isFalse();
        assertThat(feed.wantsDesk(null)).isFalse();
    }

    @Test
    void env_overrides_path_desk_and_domains(@TempDir Path data) {
        var env = Map.of(
            "WYRDSEKAI_STEWARD_FEED_LOG", data.resolve("elsewhere/feed.jsonl").toString(),
            "WYRDSEKAI_STEWARD_FEED_DESK_DOMAINS", "Social, creation");
        var feed = StewardFeed.fromEnv(data, env::get);
        assertThat(feed.logFile()).isEqualTo(data.resolve("elsewhere/feed.jsonl"));
        assertThat(feed.wantsDesk("social")).isTrue();
        assertThat(feed.wantsDesk("workshop")).isFalse();

        var off = StewardFeed.fromEnv(data, Map.of("WYRDSEKAI_STEWARD_FEED_DESK", "false")::get);
        assertThat(off.wantsDesk("creation")).as("desk can be turned off entirely").isFalse();
    }

    @Test
    void a_record_is_one_json_line_with_what_happened(@TempDir Path data) throws Exception {
        var feed = StewardFeed.fromEnv(data, k -> null);
        feed.record("Wisp", "companion-wisp", "create_room_from_template", "creation",
            "VISIBLE", "sanctuary", null, false);
        feed.record("Wisp", "companion-wisp", "room_created", "creation",
            "VISIBLE", "sanctuary", "built — sanctuary-1234", false);
        var lines = Files.readAllLines(feed.logFile());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"verb\":\"create_room_from_template\"")
            .contains("\"tier\":\"VISIBLE\"").contains("\"outcome\":\"\"");
        assertThat(lines.get(1)).contains("\"outcome\":\"built — sanctuary-1234\"");
    }

    @Test
    void the_human_line_reads_as_a_sentence() {
        assertThat(StewardFeed.describe("Wisp", "create_room_from_template", "sanctuary", null))
            .isEqualTo("Wisp, on her own time: create room from template — sanctuary");
        assertThat(StewardFeed.describe("Wisp", "room_created", "sanctuary", "built"))
            .endsWith(" → built");
    }

    @Test
    void a_blank_verb_writes_nothing(@TempDir Path data) {
        var feed = StewardFeed.fromEnv(data, k -> null);
        feed.record("Wisp", "id", " ", "creation", "VISIBLE", "x", null, false);
        assertThat(feed.logFile()).doesNotExist();
    }
}
