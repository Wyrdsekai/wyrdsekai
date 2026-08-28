package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code world.*} call the contract documents must exist in the API.
 *
 * <h2>Why this is the last piece</h2>
 * {@link ItemApiSurface} generates the EXTERNAL adapter section from the registry, so that
 * half cannot drift. The rest of the contract — {@code world.library.*},
 * {@code world.llm.*}, {@code world.regex.*}, {@code world.self.*} — is hand-written prose
 * describing hand-written Java, and nothing connected the two.
 *
 * <p>It had drifted, four times, and each one is a tool that dies in a person's hands
 * having done exactly what it was told:
 *
 * <ul>
 *   <li>{@code world.self.callerDid()} — documented from the beginning, never exported.
 *       Live 2026-08-21: {@code library_storyteller} died on
 *       {@code TypeError: Unknown identifier: callerDid} and the steward got no tool.</li>
 *   <li>{@code world.regex.matchAll()} — documented, only {@code match} existed.</li>
 *   <li>{@code world.mcp.call()} and {@code world.self.drives()} — documented with no
 *       runtime behind them at all; removed from the contract, because inventing a
 *       surface to justify a sentence is worse than deleting the sentence.</li>
 * </ul>
 *
 * <p>The failure is silent by construction: the document is convincing, the code is
 * correct against it, and the only symptom arrives when somebody uses the thing. This is
 * the cheapest possible check for it and it should have existed first.
 */
class EveryDocumentedCallExistsTest {

    /** {@code world.library.search(...)} → (library, search). */
    private static final Pattern DOCUMENTED =
        Pattern.compile("world\\.([a-z_]+)\\.([a-zA-Z_]\\w*)\\s*\\(");

    /** A method exported to scripts, however its signature wraps. */
    private static final Pattern EXPORTED = Pattern.compile(
        "@HostAccess\\.Export\\s+(?:public\\s+)?(?:static\\s+)?[\\w<>,\\[\\]. ?]+?\\s+(\\w+)\\s*\\(",
        Pattern.DOTALL);

    @Test
    void the_contract_promises_nothing_the_api_cannot_do() throws Exception {
        var contract = Files.readString(find(
            "core/src/main/java/org/wyrdsekai/core/coding/OpenHandsBackend.java"));
        var api = Files.readString(find(
            "scripting/src/main/java/org/wyrdsekai/scripting/api/ItemWorldApi.java"));

        var exported = new LinkedHashSet<String>();
        var e = EXPORTED.matcher(api);
        while (e.find()) exported.add(e.group(1));
        assertThat(exported)
            .as("the API scan must find something, or this guard proves nothing")
            .hasSizeGreaterThan(20);

        var missing = new ArrayList<String>();
        var d = DOCUMENTED.matcher(contract);
        while (d.find()) {
            var call = "world." + d.group(1) + "." + d.group(2) + "()";
            if (!exported.contains(d.group(2)) && !missing.contains(call)) missing.add(call);
        }

        assertThat(missing)
            .as("a documented call with no implementation produces a tool that dies in "
                + "someone's hands having done exactly what the contract told it to. "
                + "Either export it, or delete the line — do not leave it promising.")
            .isEmpty();
    }

    /**
     * Fails loudly rather than skipping. A drift guard that quietly finds no sources is
     * worse than none, because the suite still reports green.
     */
    private static Path find(String repoRelative) {
        for (var candidate : List.of(repoRelative, "../" + repoRelative,
                repoRelative.replaceFirst("^core/", ""))) {
            var p = Path.of(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("not found from " + System.getProperty("user.dir")
            + ": " + repoRelative + " — this guard must never silently pass");
    }
}
