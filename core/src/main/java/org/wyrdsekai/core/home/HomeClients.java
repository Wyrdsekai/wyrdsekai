package org.wyrdsekai.core.home;

/**
 * Process-wide singleton holder for the default {@link HomeClient}.
 *
 * <p>Core services that would otherwise have to thread HomeClient through
 * many constructors (CompanionActor, notification handlers, action parsers)
 * look it up via {@link #get()}. Set once from Main at startup.</p>
 */
public final class HomeClients {

    private static volatile HomeClient instance;

    private HomeClients() {}

    /** Install the default HomeClient (called once from Main.java). */
    public static void set(HomeClient client) {
        instance = client;
    }

    /** The default HomeClient, or {@code null} if not yet installed. */
    public static HomeClient get() {
        return instance;
    }
}
