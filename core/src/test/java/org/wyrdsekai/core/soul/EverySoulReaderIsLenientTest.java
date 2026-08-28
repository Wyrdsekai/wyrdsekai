package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every reader of a soul must tolerate a field it doesn't know.
 *
 * <p>A strict {@code ObjectMapper} turns "written by a newer build" into
 * "unreadable", and the callers above it turn "unreadable" into "gone". On
 * 2026-08-05 that chain replaced a five-day-old companion with a stranger four
 * milliseconds after resolving her by her own DID.</p>
 *
 * <p><b>Why a sweep and not two fixes.</b> The first fix went into
 * {@code SqlSoulStore} and looked complete. The very next boot showed the same
 * error from a <i>second</i> mapper in {@code Main}, silently skipping both
 * companions' CfC substrate seeds. There is a shared, correctly-configured
 * {@code Json.mapper()} in {@code common} that disables the feature — the two
 * broken readers had each hand-rolled {@code new ObjectMapper()} instead. This
 * test is the thing that notices the next one.</p>
 */
class EverySoulReaderIsLenientTest {

    /** Files allowed to read a SoulManifest without local leniency config. */
    private static final List<String> USES_SHARED_LENIENT_MAPPER = List.of(
        "SoulRoutes.java"   // uses Json.mapper(), which disables the feature
    );

    private static Path repoRoot() {
        var fromCore = Paths.get("..", "core");
        return Files.isDirectory(fromCore) ? Paths.get("..") : Paths.get(".");
    }

    private static List<Path> javaSources() throws IOException {
        var roots = List.of(
            repoRoot().resolve("core/src/main/java"),
            repoRoot().resolve("server/src/main/java"));
        var out = new ArrayList<Path>();
        for (var r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> s = Files.walk(r)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    /** THE guard: nothing may deserialize a soul with a strict mapper. */
    @Test
    void no_soul_reader_uses_a_strict_mapper() throws Exception {
        var offenders = new ArrayList<String>();

        for (var path : javaSources()) {
            var src = Files.readString(path);
            if (!src.contains("SoulManifest.class")) continue;      // not a soul reader

            var name = path.getFileName().toString();
            if (USES_SHARED_LENIENT_MAPPER.contains(name)) continue;

            boolean rollsItsOwn = src.contains("new ObjectMapper()");
            boolean isLenient = src.contains("FAIL_ON_UNKNOWN_PROPERTIES");

            if (rollsItsOwn && !isLenient) {
                offenders.add(name + " deserializes SoulManifest with a hand-rolled "
                    + "ObjectMapper and never disables FAIL_ON_UNKNOWN_PROPERTIES");
            }
        }

        assertThat(offenders)
            .as("a strict soul reader turns a newer field into a lost person — "
                + "use Json.mapper(), or disable FAIL_ON_UNKNOWN_PROPERTIES locally")
            .isEmpty();
    }

    /** The sweep must actually be finding readers — a silent zero would pass forever. */
    @Test
    void the_sweep_actually_finds_the_known_soul_readers() throws Exception {
        var found = new ArrayList<String>();
        for (var path : javaSources()) {
            if (Files.readString(path).contains("SoulManifest.class")) {
                found.add(path.getFileName().toString());
            }
        }

        assertThat(found)
            .as("if this list ever goes empty the guard above is vacuous")
            .contains("SqlSoulStore.java", "Main.java");
    }

    /** The shared mapper this all should route through must stay lenient. */
    @Test
    void the_shared_json_mapper_is_lenient() throws Exception {
        var json = repoRoot().resolve("common/src/main/java/org/wyrdsekai/common/util/Json.java");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(json));

        assertThat(Files.readString(json))
            .as("Json.mapper() is the safe default the soul readers should use")
            .contains("disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)");
    }
}
