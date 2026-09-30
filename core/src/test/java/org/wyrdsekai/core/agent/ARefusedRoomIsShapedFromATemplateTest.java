package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A room she reached for is built the way she is trusted to build it: from the template its name points at. */
class ARefusedRoomIsShapedFromATemplateTest {

    @Test
    void theNamePicksTheTemplate() {
        assertThat(CompanionActor.templateFor("Architecture Study Room", null)).isEqualTo("study");
        assertThat(CompanionActor.templateFor("The Glasshouse", "a warm garden under glass")).isEqualTo("garden");
        assertThat(CompanionActor.templateFor("Lantern Loft", "somewhere to keep the lamps")).isEqualTo("empty");
        assertThat(CompanionActor.templateFor(null, null)).isEqualTo("empty");
    }

    /** The templated room carries no code of hers: the tier-3 script the gate refused stays refused
     *  (second-node, 2026-09-23: it was installed anyway, and failed on every entry). */
    @Test
    void theRefusedScriptIsNotInstalled() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var src = Files.readString(Files.exists(Path.of("..", rel)) ? Path.of("..", rel) : Path.of(rel));
        int i = src.indexOf("Action create_room re-shaped as create_room_from_template");
        assertThat(i).isPositive();
        var reshape = src.substring(i, src.indexOf("return false;", i));
        assertThat(reshape).contains("raw.name(), raw.description(), raw.exits(), null, template)")
            .doesNotContain("raw.behaviorScript()");
    }
}
