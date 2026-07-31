package org.wyrdsekai.core.context;

import org.wyrdsekai.core.agent.CalendarContext;
import org.wyrdsekai.core.agent.ContextAccessManager;
import org.wyrdsekai.core.agent.LocationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton service maintaining a {@link PlayerContextProfile} per active player.
 *
 * <p>Various systems push updates (location, calendar, desktop, conversation topics,
 * email subjects, recent files) and the aggregator maintains a unified view per player.
 * When the agent needs context, it calls {@link #buildContextForAgent(String, String, ContextAccessManager)}
 * which returns a permission-filtered, connected-dots narrative string ready for prompt assembly.</p>
 *
 * <p>All state is in-memory only. Nothing is persisted to disk. Profiles are cleared on restart.</p>
 *
 * @see PlayerContextProfile
 * @see TopicExtractor
 */
public class PersonalContextAggregator {

    private final Map<String, PlayerContextProfile> profiles = new ConcurrentHashMap<>();

    /** Global singleton instance. */
    private static volatile PersonalContextAggregator instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() {
        instance = new PersonalContextAggregator();
    }

    /** Get the global instance. May be null if not initialized. */
    public static PersonalContextAggregator get() {
        return instance;
    }

    /** Reset for testing. */
    static void reset() {
        instance = null;
    }

    // --- Profile access ---

    /**
     * Get or create the profile for a player.
     *
     * @param playerDid Player's DID (or entity ID if no DID assigned)
     * @return The player's context profile, never null
     */
    public PlayerContextProfile getProfile(String playerDid) {
        return profiles.computeIfAbsent(playerDid, PlayerContextProfile::new);
    }

    /**
     * Get the profile for a player without creating one.
     *
     * @param playerDid Player's DID
     * @return The player's context profile, or null if none exists
     */
    public PlayerContextProfile getProfileIfExists(String playerDid) {
        return profiles.get(playerDid);
    }

    // --- Update methods (delegate to profile) ---

    public void updateLocation(String playerDid, LocationContext.LocationState state, String name) {
        getProfile(playerDid).updateLocation(state, name);
    }

    public void updateDesktop(String playerDid, String app, String category) {
        getProfile(playerDid).updateDesktop(app, category);
    }

    public void updateCalendar(String playerDid, List<CalendarContext.CalendarEvent> events) {
        getProfile(playerDid).updateCalendar(events);
    }

    public void updateTopics(String playerDid, List<String> topics) {
        getProfile(playerDid).updateTopics(topics);
    }

    public void updateEmailSubjects(String playerDid, List<String> subjects) {
        getProfile(playerDid).updateEmailSubjects(subjects);
    }

    public void updateRecentFiles(String playerDid, List<String> files) {
        getProfile(playerDid).updateRecentFiles(files);
    }

    public void markActive(String playerDid) {
        getProfile(playerDid).markActive();
    }

    // --- Context retrieval ---

    /**
     * Build context for a specific agent viewing a specific player,
     * filtered by the agent's permissions.
     *
     * @param playerDid    Player whose context to build
     * @param agentId      Agent requesting the context
     * @param accessManager Permission manager
     * @return Filtered context string, or null if no profile or nothing to show
     */
    public String buildContextForAgent(String playerDid, String agentId,
                                        ContextAccessManager accessManager) {
        var profile = profiles.get(playerDid);
        if (profile == null) return null;
        return profile.buildFilteredContext(agentId, accessManager);
    }

    /**
     * Build full (unfiltered) context for a player. Used for debugging /
     * "show [agent] context" commands.
     *
     * @param playerDid Player whose context to build
     * @return Full context string, or null if no profile
     */
    public String buildFullContext(String playerDid) {
        var profile = profiles.get(playerDid);
        if (profile == null) return null;
        return profile.buildFullContext();
    }

    /** Number of tracked player profiles. */
    public int profileCount() {
        return profiles.size();
    }
}
