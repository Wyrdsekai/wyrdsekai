package org.wyrdsekai.server.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node-local operator token.
 *
 * <p>Steward-gated CLI verbs required an interactive {@code wyrd login} that nobody
 * performs on a household node — the steward is already on the box over SSH. So the
 * commands shipped, passed their tests, and were unreachable: the consent route had
 * zero calls in the four days after it landed. The gate was right and the path a
 * person walks was never exercised.
 */
class OperatorTokenTest {

    @AfterEach
    void clear() {
        OperatorToken.setForTesting(null);
    }

    @Test
    void a_token_is_created_on_first_use_and_matches(@TempDir Path dir) {
        OperatorToken.ensure(dir);
        var written = readToken(dir);
        assertThat(written).isNotBlank();
        assertThat(OperatorToken.matches(written)).isTrue();
    }

    @Test
    void the_token_is_readable_only_by_its_owner(@TempDir Path dir) throws Exception {
        OperatorToken.ensure(dir);
        var perms = Files.getPosixFilePermissions(dir.resolve("operator.token"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void an_existing_token_survives_a_restart(@TempDir Path dir) {
        OperatorToken.ensure(dir);
        var first = readToken(dir);
        OperatorToken.setForTesting(null);
        OperatorToken.ensure(dir);          // second boot
        assertThat(readToken(dir)).isEqualTo(first);
        assertThat(OperatorToken.matches(first)).isTrue();
    }

    @Test
    void a_wrong_or_absent_value_never_matches(@TempDir Path dir) {
        OperatorToken.ensure(dir);
        assertThat(OperatorToken.matches("not-the-token")).isFalse();
        assertThat(OperatorToken.matches("")).isFalse();
        assertThat(OperatorToken.matches(null)).isFalse();
    }

    @Test
    void nothing_matches_before_a_token_exists() {
        OperatorToken.setForTesting(null);
        assertThat(OperatorToken.matches("anything")).isFalse();
        assertThat(OperatorToken.matches(null)).isFalse();
    }

    @Test
    void a_missing_data_dir_is_survivable_rather_than_fatal() {
        // A node that cannot write its data dir must still start; the operator simply
        // falls back to `wyrd login`.
        OperatorToken.ensure(null);
        assertThat(OperatorToken.matches("anything")).isFalse();
    }

    private static String readToken(Path dir) {
        try {
            return Files.readString(dir.resolve("operator.token")).strip();
        } catch (Exception e) {
            return "";
        }
    }
}
