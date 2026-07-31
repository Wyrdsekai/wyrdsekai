package org.wyrdsekai.common.topology;

/**
 * Policy classification for how a node relates to the rooms it hosts.
 *
 * <ul>
 *   <li>{@link #SHARED} — the node hosts shared/communal rooms (e.g. foundation rooms).</li>
 *   <li>{@link #PERSONAL} — the node primarily hosts a single user's personal rooms.</li>
 *   <li>{@link #INFRASTRUCTURE} — the node provides infrastructure services (e.g. relay, bridge).</li>
 * </ul>
 */
public enum NodeRoomPolicy {

    /** Node hosts shared/communal rooms. */
    SHARED,

    /** Node primarily hosts personal rooms for a single user. */
    PERSONAL,

    /** Node provides infrastructure services (relay, bridge, etc.). */
    INFRASTRUCTURE
}
