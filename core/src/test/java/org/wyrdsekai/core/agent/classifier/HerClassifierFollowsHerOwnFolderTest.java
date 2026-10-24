package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A retrained head takes effect without a restart, and so does its undo.
 *
 * <p>The retrain recipe deploys into the companion's own classifier folder,
 * the Forge writes there too, and a steward may put a head there by hand. Her
 * {@link ClassifierArm} loads from that folder before the shipped head; since
 * 2026-10-24 it also looks at the folder once a minute and reloads a head whose
 * files appeared, changed or went away. A head whose files changed in the last
 * few seconds is left alone, because the model and its labels land one after
 * the other and a model read with another's labels names every class wrong.
 */
class HerClassifierFollowsHerOwnFolderTest {

    private static final ClassifierHead HEAD = ClassifierHead.TASK_PRESENT;

    @Test
    void a_head_that_lands_in_her_folder_is_picked_up_and_its_removal_puts_the_shipped_one_back(
            @TempDir Path hers) throws Exception {
        var arm = ClassifierArm.forAgentAt("did:test:follows-her-folder", hers);
        assumeTrue(arm != null, "ONNX runtime unavailable");
        try {
            assumeTrue("shipped".equals(arm.sourceOf(HEAD)), "shipped head not on the test class path");

            // 1. A retrain lands (written a while ago: settled).
            Path model = copyShipped(HEAD.modelResourcePath(), hers.resolve(HEAD.resourceName() + ".onnx"));
            Path labels = copyShipped(HEAD.labelsResourcePath(), hers.resolve(HEAD.resourceName() + ".labels.json"));
            ageByTenSeconds(model, labels);
            arm.refreshChangedHeads();
            assertThat(arm.sourceOf(HEAD)).isEqualTo("her folder");

            // 2. A rollback removes it: back on the shipped head.
            Files.delete(model);
            Files.delete(labels);
            arm.refreshChangedHeads();
            assertThat(arm.sourceOf(HEAD)).isEqualTo("shipped");

            // 3. A deploy in progress (files seconds old) is not read yet.
            copyShipped(HEAD.modelResourcePath(), model);
            copyShipped(HEAD.labelsResourcePath(), labels);
            arm.refreshChangedHeads();
            assertThat(arm.sourceOf(HEAD)).as("files still settling").isEqualTo("shipped");
            ageByTenSeconds(model, labels);
            arm.refreshChangedHeads();
            assertThat(arm.sourceOf(HEAD)).isEqualTo("her folder");
        } finally {
            arm.close();
        }
    }

    @Test
    void a_changed_head_in_her_folder_is_reloaded(@TempDir Path hers) throws Exception {
        Path model = copyShipped(HEAD.modelResourcePath(), hers.resolve(HEAD.resourceName() + ".onnx"));
        Path labels = copyShipped(HEAD.labelsResourcePath(), hers.resolve(HEAD.resourceName() + ".labels.json"));
        ageByTenSeconds(model, labels);
        var arm = ClassifierArm.forAgentAt("did:test:changed-head", hers);
        assumeTrue(arm != null, "ONNX runtime unavailable");
        try {
            assumeTrue("her folder".equals(arm.sourceOf(HEAD)), "per-companion head not loaded");
            // The same bytes rewritten with a later stamp count as a change: the
            // arm cannot know what changed inside, only that something did.
            Files.setLastModifiedTime(model, FileTime.from(Instant.now().minusSeconds(8)));
            arm.refreshChangedHeads();
            assertThat(arm.sourceOf(HEAD)).isEqualTo("her folder");
            assertThat(arm.classify(HEAD, null).label()).isNull();  // still a working arm
        } finally {
            arm.close();
        }
    }

    private static Path copyShipped(String resource, Path to) throws Exception {
        try (InputStream in = ClassifierArm.class.getClassLoader().getResourceAsStream(resource)) {
            assumeTrue(in != null, "shipped resource missing: " + resource);
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
        return to;
    }

    private static void ageByTenSeconds(Path... files) throws Exception {
        var stamp = FileTime.from(Instant.now().minusSeconds(10));
        for (var f : files) Files.setLastModifiedTime(f, stamp);
    }
}
