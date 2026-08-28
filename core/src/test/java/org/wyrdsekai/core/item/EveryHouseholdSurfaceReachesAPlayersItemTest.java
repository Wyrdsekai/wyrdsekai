package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * If the household can do it, an item a PERSON is holding can do it too.
 *
 * <h2>Why this is a source scan</h2>
 * {@code ItemWorldApiProviderImpl} is the companion's provider — the only one wired to the
 * real library, model and web. {@code VisitorItemProvider} (which {@code HomeOwnerItem
 * Provider} extends, and which every player-held item gets) forwards to it only for the
 * methods someone remembered to write out.
 *
 * <p>Twice on 2026-08-21 that list was short. First the content surfaces were missing
 * entirely — a person's item read "visiting foreign zone" from inside their own house.
 * Then, after I hand-picked seven methods to forward, an audit found <b>twenty more</b>:
 * an item called {@code world.llm.complete}, got {@code "[error] llm.complete not wired"}
 * from the interface default, and died — while the same item's {@code library.search}
 * worked, because search was on my list and complete was not.
 *
 * <p>A hand-picked forwarding list is the same rotting mirror as a hand-written API doc.
 * This counts instead: every content method the household implements must be forwarded,
 * so the next one that gets added fails here rather than in someone's hands.
 */
class EveryHouseholdSurfaceReachesAPlayersItemTest {

    /** Content the household serves. Deliberately excludes household ADMIN and identity. */
    private static final Pattern CONTENT = Pattern.compile(
        "^(llm|web|library|knowledge|oracle|embed|journal|notes|tags|memory|pinboard)",
        Pattern.CASE_INSENSITIVE);

    /**
     * Deliberate exclusions, each for a stated reason — not an oversight list.
     *
     * <p>{@code llmTools} re-enters the tool loop, which is why {@code CRAFTED_ALLOW}
     * excludes {@code llm.tools} as well: a crafted item may ask the model a question,
     * but it may not hand the model tools and let it act. Forwarding it here would give
     * a player-held item a capability the ceiling deliberately withholds.
     */
    private static final List<String> NOT_CONTENT = List.of(
        "webhookRegister", "webhookList", "webhookDelete",
        "llmTools");

    private static final Pattern OVERRIDE = Pattern.compile(
        "@Override\\s+public\\s+[\\w<>,\\[\\]. ?]+\\s+(\\w+)\\(");

    @Test
    void every_content_surface_the_household_implements_is_forwarded() throws Exception {
        var impl = names(Files.readString(find(
            "core/src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java")));
        var visitor = names(Files.readString(find(
            "core/src/main/java/org/wyrdsekai/core/item/VisitorItemProvider.java")));

        assertThat(impl)
            .as("the scan must find the household's methods, or this guard proves nothing")
            .hasSizeGreaterThan(50);

        var unreachable = new ArrayList<String>();
        for (var m : impl) {
            if (!CONTENT.matcher(m).find()) continue;
            if (NOT_CONTENT.contains(m)) continue;
            if (!visitor.contains(m)) unreachable.add(m);
        }
        assertThat(unreachable)
            .as("the household can do these and an item in a person's hands cannot — "
                + "that difference is never something anybody chose")
            .isEmpty();
    }

    /** And the forwarding actually forwards, rather than being an override that returns nothing. */
    @Test
    void a_forwarded_surface_returns_the_households_answer() {
        var household = new VisitorItemProvider("home", "home") {
            @Override
            public java.util.Map<String, Object> llmComplete(
                    String prompt, java.util.Map<String, Object> opts) {
                return java.util.Map.of("text", "from the household");
            }
        };
        var players = new VisitorItemProvider("home", "home")
            .withHouseholdContent(household);
        assertThat(players.llmComplete("hello", java.util.Map.of()))
            .containsEntry("text", "from the household");
    }

    /** Abroad, there is nothing to forward to — and that stays true. */
    @Test
    void a_genuine_foreign_zone_still_answers_for_itself() {
        var abroad = new VisitorItemProvider("far", "far");
        assertThat(abroad.llmComplete("hello", java.util.Map.of()))
            .as("the interface default, not a crash")
            .isNotNull();
    }

    private static LinkedHashSet<String> names(String src) {
        var out = new LinkedHashSet<String>();
        Matcher m = OVERRIDE.matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Path find(String repoRelative) {
        for (var candidate : List.of(repoRelative, "../" + repoRelative,
                repoRelative.replaceFirst("^core/", ""))) {
            var p = Path.of(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("not found from " + System.getProperty("user.dir")
            + ": " + repoRelative + " — this guard must never silently pass");
    }

    @SuppressWarnings("unused")
    private static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
