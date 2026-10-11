package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.scripting.loader.ScriptLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Room scripts the companions wrote that do not work: counted per file, told to her once in
 * plain words, set aside after three failures, and forgotten when the script is written again.
 * Before this a failing room script was logged on every hook, forever (the household node,
 * 2026-10-09: about twenty errors a day from three rooms).
 */
class BrokenRoomScriptsTest {

    @TempDir Path dir;
    Path userScripts;

    @BeforeEach
    void fresh() throws Exception {
        BrokenRoomScripts.resetForTests();
        userScripts = dir.resolve("scripts");
        Files.createDirectories(userScripts);
    }

    private void write(String roomId, String script, long mtimeMillis) throws Exception {
        var f = userScripts.resolve(roomId + ".js");
        Files.writeString(f, script);
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtimeMillis));
    }

    @Test
    @DisplayName("told once, set aside at three, and the record survives a reload")
    void toldOnceAndSetAside() throws Exception {
        write("quiet-alcove", "function onEnter() { emit('narrate', {text: 'hi'}); }", 1_000_000L);
        var first = BrokenRoomScripts.failed("quiet-alcove", userScripts, "onEnter", "ReferenceError: emit is not defined");
        assertThat(first).isEqualTo("The script in quiet-alcove does not work: it calls emit on its own, and a room script "
            + "reaches the world through world.emit(...). I can write it again with add_script.");
        assertThat(BrokenRoomScripts.failed("quiet-alcove", userScripts, "onSay", "ReferenceError: emit is not defined"))
            .as("the same failure again is not told again").isNull();
        assertThat(BrokenRoomScripts.isSetAside("quiet-alcove", userScripts)).isFalse();
        var third = BrokenRoomScripts.failed("quiet-alcove", userScripts, "onEnter", "ReferenceError: emit is not defined");
        assertThat(third).isEqualTo("The script in quiet-alcove failed 3 times, so it is set aside: the room stays, its script "
            + "does not run, and nothing is lost. I can write it again with add_script.");
        assertThat(BrokenRoomScripts.isSetAside("quiet-alcove", userScripts)).isTrue();
        assertThat(BrokenRoomScripts.failed("quiet-alcove", userScripts, "onEnter", "ReferenceError: emit is not defined")).isNull();

        BrokenRoomScripts.resetForTests();
        assertThat(BrokenRoomScripts.isSetAside("quiet-alcove", userScripts)).as("read back from disk").isTrue();
        var listed = BrokenRoomScripts.setAside(userScripts);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).roomId()).isEqualTo("quiet-alcove");
        assertThat(listed.get(0).failures()).isEqualTo(4);
        assertThat(listed.get(0).lastError()).contains("emit is not defined");
    }

    @Test
    @DisplayName("a different error in the same script is told; a changed file starts the count again")
    void newErrorAndChangedFile() throws Exception {
        write("study", "function onSay() { entity.getProperty('x'); }", 1_000_000L);
        assertThat(BrokenRoomScripts.failed("study", userScripts, "onSay", "TypeError: entity.getProperty is not a function"))
            .contains("it calls entity.getProperty, which is not there.");
        assertThat(BrokenRoomScripts.failed("study", userScripts, "onEnter", "SyntaxError: Unexpected token }"))
            .contains("it does not parse (SyntaxError: Unexpected token }).");
        // The file changed (she wrote it again by hand, or the loader saw a new version): the count starts over.
        write("study", "function onSay() { world.emit('narrate', {text: 'x'}); }", 2_000_000L);
        assertThat(BrokenRoomScripts.isSetAside("study", userScripts)).isFalse();
        assertThat(BrokenRoomScripts.failed("study", userScripts, "onSay", "TypeError: x is not a function"))
            .as("a new file is told about afresh").isNotNull();
        BrokenRoomScripts.failed("study", userScripts, "onSay", "TypeError: x is not a function");
        assertThat(BrokenRoomScripts.isSetAside("study", userScripts)).as("two failures on the new file").isFalse();
    }

    @Test
    @DisplayName("writing the script again clears the record; a built-in script is never counted")
    void rewrittenAndBuiltIn() throws Exception {
        write("garden", "x(", 1_000_000L);
        for (int i = 0; i < 3; i++) BrokenRoomScripts.failed("garden", userScripts, "onEnter", "SyntaxError: Expected an operand");
        assertThat(BrokenRoomScripts.isSetAside("garden", userScripts)).isTrue();
        BrokenRoomScripts.rewritten("garden", userScripts);
        assertThat(BrokenRoomScripts.isSetAside("garden", userScripts)).isFalse();
        assertThat(BrokenRoomScripts.setAside(userScripts)).isEmpty();
        // No file of hers for this room: the product's own script failed, which is logged, not counted.
        assertThat(BrokenRoomScripts.failed("nexus", userScripts, "onEnter", "ReferenceError: emit is not defined")).isNull();
        assertThat(BrokenRoomScripts.isSetAside("nexus", userScripts)).isFalse();
        assertThat(BrokenRoomScripts.failed("nexus", null, "onEnter", "x")).isNull();
    }

    @Test
    @DisplayName("through the engine: three failing hooks set the script aside and the fourth does not run it")
    void throughTheEngine() throws Exception {
        var base = dir.resolve("builtin");
        Files.createDirectories(base);
        write("alcove", "function onEnter(id, name, from) { emit('narrate', { text: 'hi' }); }", 3_000_000L);
        try (var engine = new RoomScriptEngine("alcove", new ScriptLoader(base, userScripts))) {
            for (int i = 0; i < 3; i++) engine.invokeHook("onEnter", "p1", "Pat", "north");
            assertThat(BrokenRoomScripts.isSetAside("alcove", userScripts)).isTrue();
            var before = BrokenRoomScripts.setAside(userScripts).get(0).failures();
            engine.invokeHook("onEnter", "p1", "Pat", "north");
            engine.getHints();
            engine.getToolDefinitions();
            // The activation, passivation and timer doors too: the household node logged the same
            // syntax error at every start through onActivate after the script was set aside.
            engine.invokeActivate(RoomState.empty("alcove"));
            engine.invokePassivate(RoomState.empty("alcove"));
            engine.invokeTimer(RoomState.empty("alcove"), "t1");
            assertThat(BrokenRoomScripts.setAside(userScripts).get(0).failures()).as("set aside: not run again from any door").isEqualTo(before);
            // Written again (as add_script does through the room): it runs, fails, and is counted afresh.
            write("alcove", "function onEnter(id, name, from) { world.emit('narrate', { text: 'hi' }); }", 4_000_000L);
            BrokenRoomScripts.rewritten("alcove", userScripts);
            engine.invokeHook("onEnter", "p1", "Pat", "north");
            assertThat(BrokenRoomScripts.setAside(userScripts)).isEmpty();
        }
    }

    @Test
    @DisplayName("the room refuses a script that does not parse, and the companion learns why before asking")
    void parseCheckIsWired() throws Exception {
        var room = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/room/RoomActor.java"));
        assertThat(room).contains("var problem = org.wyrdsekai.scripting.sandbox.ItemScriptExecutor.parseProblem(scriptToWrite);");
        assertThat(room).contains("cmd.replyTo().tell(new RoomResponse.Rejected(\"script_parse_failed\",");
        assertThat(room).contains("BrokenRoomScripts.rewritten(roomId, userDir);");
        var actor = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(actor).contains("var parseProblem = ItemScriptExecutor.parseProblem(action.script());");
        assertThat(actor).contains("so the room keeps the one it had. I can write it again with add_script.\");");
    }

    @Test
    @DisplayName("what is wrong, as she would say it")
    void plainly() {
        assertThat(BrokenRoomScripts.plainly("onEnter", "ReferenceError: narrate is not defined"))
            .isEqualTo("it calls narrate on its own, and a room script reaches the world through world.narrate(...).");
        assertThat(BrokenRoomScripts.plainly("onUse", "TypeError: world.room.emit is not a function"))
            .isEqualTo("it calls world.room.emit, which is not there.");
        assertThat(BrokenRoomScripts.plainly("top level", "SyntaxError: script.js:3:14 Expected ; but found }"))
            .startsWith("it does not parse (");
        assertThat(BrokenRoomScripts.plainly("onTimer", "it ran past its limit of 100000 statements"))
            .isEqualTo("it failed in onTimer (it ran past its limit of 100000 statements).");
        assertThat(BrokenRoomScripts.plainly(null, null)).isEqualTo("it failed in a hook ().");
        assertThat(Instant.now()).isNotNull();
    }
}
