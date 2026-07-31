package org.wyrdsekai.core.recipe;

import java.nio.file.Path;

/**
 * Track-C C9 follow-up (#1008) — process singleton holding
 * the runtime context needed to enroll a new companion in ship-default
 * recipes at spawn time.
 *
 * <p>Boot-time provisioning (via {@code RecipeSchedulerBoot}) handles the
 * companions that exist at server start; this registry is what lets a
 * companion spawned <em>post-boot</em> (e.g. a fresh steward bonds, a
 * new companion is summoned in-world) get the same ship-default
 * enrollment row written within milliseconds of soul-birth. Per
 * {@code feedback-no-paternalism-on-agent-defaults}: the familiar gets
 * its substrate-evolution surface day one, not earned.</p>
 *
 * <p>Set by {@code RecipeSchedulerBoot} after the scheduler actor is
 * spawned and the enrollment store is wired. {@link #get} returns
 * {@code null} when no scheduler is running — callers must null-check
 * and treat that as "no-op," since the system is functional without
 * the scheduler (e.g. unit-test bootstrap, scheduler-disabled deploys).</p>
 *
 * <p>Same pattern as {@link RecipeSchedulerRegistry}.</p>
 */
public final class RecipeEnrollmentRegistry {

    /**
     * Snapshot of everything {@code ShipDefaultEnrollmentProvisioner.provision}
     * needs to enroll one new companion DID. Immutable.
     */
    public record Context(
            RecipeEnrollmentStore store,
            String headsConfigCsv,
            Path pretrainedDir) {}

    private RecipeEnrollmentRegistry() {}

    private static volatile Context INSTANCE;

    public static Context get() { return INSTANCE; }

    public static void setInstance(Context ctx) { INSTANCE = ctx; }

    public static void resetForTests() { INSTANCE = null; }
}
