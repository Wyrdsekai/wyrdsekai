package org.wyrdsekai.core.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A machine that cannot reach the release still verifies a peer's installer: against the checksums
 * another household machine fetched from the release and passed on over the signed Between.
 */
class TheHouseholdCarriesTheReleasesChecksumsTest {

    private static final String SUMS = "a".repeat(64) + "  wyrdsekai_0.5.4_amd64.deb\n" + "b".repeat(64) + " *Wyrdsekai-0.5.4.pkg\n";

    @Test
    void only_the_shape_of_a_checksum_file_is_accepted() {
        assertThat(ReleaseSums.looksLikeSums(SUMS)).isTrue();
        assertThat(ReleaseSums.looksLikeSums("")).isFalse();
        assertThat(ReleaseSums.looksLikeSums("abc  wyrdsekai_0.5.4_amd64.deb\n")).as("not 64 hex").isFalse();
        assertThat(ReleaseSums.looksLikeSums("a".repeat(64) + "  ../etc/passwd\n")).as("no paths").isFalse();
        assertThat(ReleaseSums.looksLikeSums("a".repeat(64) + "  x.deb; rm -rf /\n")).isFalse();
        assertThat(ReleaseSums.looksLikeSums("a".repeat(64) + "  x.deb\n".repeat(3000))).as("too large").isFalse();
        assertThat(ReleaseSums.isVersion("0.5.4")).isTrue();
        assertThat(ReleaseSums.isVersion("0.5.4~dev12")).isFalse();
        assertThat(ReleaseSums.isVersion("../0.5.4")).isFalse();
    }

    @Test
    void what_a_peer_passed_on_is_kept_once_never_over_the_releases_own_copy_and_two_peers_that_disagree_leave_nothing(@TempDir Path dir) throws Exception {
        assertThat(ReleaseSums.storeCarried(dir, "0.5.4", SUMS, "laptop")).isEqualTo(ReleaseSums.Outcome.STORED);
        assertThat(Files.readString(ReleaseSums.carriedFile(dir, "0.5.4"))).isEqualTo(SUMS);
        assertThat(ReleaseSums.storeCarried(dir, "0.5.4", SUMS.replace("  ", " "), "nas")).as("the same sums, spacing aside").isEqualTo(ReleaseSums.Outcome.ALREADY_HAVE);
        var other = "c".repeat(64) + "  wyrdsekai_0.5.4_amd64.deb\n";
        assertThat(ReleaseSums.storeCarried(dir, "0.5.4", other, "phone")).isEqualTo(ReleaseSums.Outcome.CONFLICT);
        assertThat(Files.exists(ReleaseSums.carriedFile(dir, "0.5.4"))).as("neither is kept").isFalse();
        assertThat(ReleaseSums.storeCarried(dir, "0.5.4", "garbage", "phone")).isEqualTo(ReleaseSums.Outcome.REFUSED);
        assertThat(ReleaseSums.storeCarried(dir, "0.5.4~dev1", SUMS, "phone")).isEqualTo(ReleaseSums.Outcome.REFUSED);
        Files.writeString(ReleaseSums.releaseFile(dir, "0.5.5"), SUMS);
        assertThat(ReleaseSums.storeCarried(dir, "0.5.5", other, "phone")).isEqualTo(ReleaseSums.Outcome.ALREADY_HAVE_RELEASE);
        assertThat(Files.exists(ReleaseSums.carriedFile(dir, "0.5.5"))).isFalse();
    }

    @Test
    void a_machine_passes_on_only_what_it_fetched_from_the_release_itself(@TempDir Path dir) throws Exception {
        assertThat(ReleaseSums.ownSums(dir, "0.5.4")).isEmpty();
        ReleaseSums.storeCarried(dir, "0.5.4", SUMS, "laptop");
        assertThat(ReleaseSums.ownSums(dir, "0.5.4")).as("a carried copy is not re-signed by this machine").isEmpty();
        Files.writeString(ReleaseSums.releaseFile(dir, "0.5.4"), SUMS);
        assertThat(ReleaseSums.ownSums(dir, "0.5.4")).contains(SUMS);
        Files.writeString(ReleaseSums.releaseFile(dir, "0.5.6"), "not sums");
        assertThat(ReleaseSums.ownSums(dir, "0.5.6")).as("a damaged file is not passed on").isEmpty();
        assertThat(ReleaseSums.ownSums(null, "0.5.4")).isEmpty();
    }
}
