package org.wyrdsekai.core.body;

/**
 * Where a part is in its life on the map. A part that stops answering is numb, with a
 * duration, and becomes gone only when someone with authority says so. The interval between
 * is the phantom limb, and it is the honest interval: the failure we actually had was a
 * cortex missing for a month and nothing missing it.
 */
public enum PartState {
    ATTACHED, NUMB, GONE,
    /** Attached by someone the household did not put there: on the map, visible, not used, until a person vouches for it. */
    QUARANTINED
}
