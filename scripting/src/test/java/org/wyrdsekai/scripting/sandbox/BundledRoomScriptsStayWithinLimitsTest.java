package org.wyrdsekai.scripting.sandbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.WorldApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The room-script limit must never trip on legitimate work. Every bundled room script is run
 * through every hook it defines, with every command word it branches on, under one hundredth of
 * the default statement limit. Measured 2026-09-28: the heaviest call needed 256 statements.
 */
class BundledRoomScriptsStayWithinLimitsTest {

    private static final List<Path> DIRS = List.of(Path.of("../scripts/rooms"), Path.of("../scripts/std/room"));
    private static final Pattern BRANCH_WORD = Pattern.compile(
        "(?:===|==|startsWith\\(|indexOf\\()\\s*\"([a-z][a-z _-]{1,30})\"");

    private record Call(String hook, Object[] args) {}

    private static List<Call> callsFor(String src) {
        var calls = new ArrayList<Call>();
        calls.add(new Call("onEnter", new Object[]{"p1", "Operator", "north"}));
        calls.add(new Call("onLeave", new Object[]{"p1", "Operator", "south"}));
        calls.add(new Call("onTake", new Object[]{"p1", "book", "book-1"}));
        calls.add(new Call("onDrop", new Object[]{"p1", "book", "book-1"}));
        calls.add(new Call("onToolCall", new Object[]{"p1", "card_catalog", "{\"query\":\"tea\"}"}));
        calls.add(new Call("onWorkbenchResult", new Object[]{"p1", "ok", "{}"}));
        calls.add(new Call("onActivate", new Object[0]));
        calls.add(new Call("onPassivate", new Object[0]));
        calls.add(new Call("onTimer", new Object[]{"t1"}));
        calls.add(new Call("getMounts", new Object[0]));
        var words = new LinkedHashSet<String>(List.of("", "help", "look", "list", "status"));
        var m = BRANCH_WORD.matcher(src);
        while (m.find()) words.add(m.group(1).trim());
        for (var w : words) {
            calls.add(new Call("onSay", new Object[]{"p1", "Operator", w}));
            calls.add(new Call("onSay", new Object[]{"p1", "Operator", w + " tea"}));
            calls.add(new Call("onUse", new Object[]{"p1", w, "", "Operator"}));
            calls.add(new Call("onUse", new Object[]{"p1", "scroll", w, "Operator"}));
        }
        return calls;
    }

    @Test
    void every_bundled_room_hook_fits_in_a_hundredth_of_the_limit() throws Exception {
        var files = new ArrayList<Path>();
        for (var dir : DIRS) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".js")).sorted().forEach(files::add);
            }
        }
        assumeTrue(!files.isEmpty(), "run from a source checkout");

        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var sandboxLog = (Logger) LoggerFactory.getLogger(ScriptSandbox.class);
        sandboxLog.addAppender(appender);
        var tight = new ResourceLimits(ResourceLimits.ROOM_SCRIPT.statementLimit() / 100, 0, 0, 0);
        int calls = 0;
        try {
            for (var file : files) {
                var src = Files.readString(file);
                var roomId = file.getFileName().toString().replace(".js", "");
                try (var sandbox = new ScriptSandbox(roomId, tight)) {
                    for (var call : callsFor(src)) {
                        if (!src.contains("function " + call.hook())) continue;
                        sandbox.callHook(src, call.hook(), new WorldApi(roomId), call.args());
                        calls++;
                    }
                    sandbox.callHintsFunction(src, new WorldApi(roomId));
                    sandbox.executeAsString(src + "\n;typeof getToolDefinitions === 'function' "
                        + "? JSON.stringify(getToolDefinitions()) : '[]'", new WorldApi(roomId));
                    calls += 2;
                }
            }
        } finally {
            sandboxLog.detachAppender(appender);
        }
        var stopped = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertThat(calls).isGreaterThan(500);
        assertThat(stopped).as("room hooks stopped by a hundredth of the limit").isEmpty();
    }
}
