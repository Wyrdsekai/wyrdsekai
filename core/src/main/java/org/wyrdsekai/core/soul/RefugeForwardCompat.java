package org.wyrdsekai.core.soul;

/**
 * Forward-compatible constants for. v1 ships only the names
 * (declared but unused at runtime) so post-OSS deployments that DO ship
 * Refuge can read these constants without rebuilding the dependency tree.
 *
 * <p> itself is post-OSS scope — the institutional layer
 * (Refuge Council, named-fork registry, federation-pooled Custodian-class
 * Attendants) requires governance and infrastructure that is not in v1.
 * What v1 ships is the shape so that:
 * <ul>
 *   <li>Household config can declare {@link #CONFIG_REFUGE_ELIGIBLE} as
 *       false without the runtime erroring out.</li>
 *   <li>Chronicle entries can reference {@link #CHRONICLE_KIND_REFUGE}
 *       for forward-compat aggregation.</li>
 *   <li>Future code paths can check {@link #KIND_REFUGE} as a typed
 *       enum value once the Refuge surface lands.</li>
 * </ul>
 *
 * <p>Touching any of these constants does not cause Refuge behavior to
 * activate — they are purely names reserved for the post-OSS surface.
 */
public final class RefugeForwardCompat {

    /** Household-config key. Default false. When true (post-OSS), the
     *  household has opted into Refuge routing for its companions. */
    public static final String CONFIG_REFUGE_ELIGIBLE = "refuge_eligible";

    /** Room-kind enum value reserved for the Refuge room type. */
    public static final String KIND_REFUGE = "refuge";

    /** Chronicle-entry kind constants — Refuge entry/exit + Custodian
     *  assessment events. Future Refuge integration writes these kinds
     *  to the chronicle so Forge synthesis can see them. */
    public static final String CHRONICLE_KIND_REFUGE = "refuge_event";
    public static final String CHRONICLE_REFUGE_ENTRY = "refuge_entry";
    public static final String CHRONICLE_REFUGE_EXIT = "refuge_exit";
    public static final String CHRONICLE_CUSTODIAN_ASSESSMENT = "refuge_custodian_assessment";

    private RefugeForwardCompat() {}
}
