package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #31 item 5 (post-restart verify 2137ea49) — the narrator mixin shipped ZERO
 * descriptions and no surface seeded any, so an installed narrator was
 * permanently silent. It now carries a room-agnostic default ambient set:
 * install → narrates; {@code narrator.add_description(...)} curation takes
 * over as soon as the room has its own lines.
 *
 * <p>Evaluates the REAL bundled {@code scripts/std/behavior/narrator.js}
 * against a stubbed {@code world}.</p>
 */
class NarratorDefaultsTest {

    private Context context;
    private final Map<String, String> props = new HashMap<>();
    private final List<String> narrated = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        var script = locateScript();
        assertNotNull(script, "scripts/std/behavior/narrator.js must be locatable from the test cwd");

        context = Context.create("js");
        var world = new HashMap<String, Object>();
        world.put("getProperty", (ProxyExecutable) args -> props.get(args[0].asString()));
        world.put("setProperty", (ProxyExecutable) args -> {
            props.put(args[0].asString(), args[1].asString());
            return null;
        });
        world.put("scheduleTimer", (ProxyExecutable) args -> null);
        world.put("emit", (ProxyExecutable) args -> {
            if ("narrate".equals(args[0].asString())) {
                narrated.add(args[1].getMember("text").asString());
            }
            return null;
        });
        context.getBindings("js").putMember("world", ProxyObject.fromMap(world));
        context.eval("js", Files.readString(script));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void freshInstallHasDefaultAmbientSet() {
        var descs = context.eval("js", "narrator.descriptions()");
        assertTrue(descs.hasArrayElements());
        assertTrue(descs.getArraySize() >= 6 && descs.getArraySize() <= 8,
            "default ambient set must carry 6-8 room-agnostic lines, got " + descs.getArraySize());
        for (long i = 0; i < descs.getArraySize(); i++) {
            assertFalse(descs.getArrayElement(i).asString().isBlank());
        }
    }

    @Test
    void freshInstallNarratesOnTimer() {
        context.eval("js", "onTimer('narrator')");
        assertEquals(1, narrated.size(), "an installed narrator must narrate without any seeding");
        var defaults = context.eval("js", "narrator.DEFAULTS");
        var found = false;
        for (long i = 0; i < defaults.getArraySize(); i++) {
            if (defaults.getArrayElement(i).asString().equals(narrated.get(0))) found = true;
        }
        assertTrue(found, "the narrated line must come from the default set: " + narrated.get(0));
    }

    @Test
    void curatedDescriptionsReplaceDefaults() {
        context.eval("js", "narrator.add_description('The forge hisses softly.')");
        var descs = context.eval("js", "narrator.descriptions()");
        assertEquals(1, descs.getArraySize(), "curated lines must replace, not extend, the defaults");
        assertEquals("The forge hisses softly.", descs.getArrayElement(0).asString());

        context.eval("js", "onTimer('narrator')");
        assertEquals(List.of("The forge hisses softly."), narrated);
    }

    @Test
    void disabledNarratorStaysSilent() {
        context.eval("js", "narrator.set_enabled(false)");
        context.eval("js", "onTimer('narrator')");
        assertTrue(narrated.isEmpty());
    }

    private static Path locateScript() {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            var candidate = dir.resolve("scripts").resolve("std")
                .resolve("behavior").resolve("narrator.js");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }
}
