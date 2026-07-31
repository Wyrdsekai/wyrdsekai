package org.wyrdsekai.core.skill;

/**
 * Where a skill can execute. Determines routing when a phone agent
 * invokes a skill that lives on the home zone (or vice versa).
 */
public enum SkillLocality {
    /** Must run on the home zone server (e.g., Home Assistant, Kiwix, file search). */
    LOCAL,

    /** Must run on the phone (e.g., camera, notifications, health data, GPS). */
    PHONE,

    /** Can run anywhere — routes to the closest available executor (e.g., weather, email). */
    ANY,

    /** Requires Between mesh connectivity (e.g., clipboard bridge, grocery list sync). */
    BETWEEN
}
