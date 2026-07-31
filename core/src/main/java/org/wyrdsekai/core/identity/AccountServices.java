package org.wyrdsekai.core.identity;

/**
 * Process-wide singleton holder for the default {@link AccountService} +
 * {@link AccountStore}. Mirrors the
 * {@link org.wyrdsekai.core.home.HomeClients} pattern so core services
 * (CompanionActor, PromptAssembler glue) can look up the bondholder
 * account profile without threading the dependency through many
 * constructors.
 *
 * <p>Set once from Main at startup; tests use {@link #set} / {@link #clear}
 * for setup/teardown. Both fields are nullable — production code paths
 * must null-check before use, matching the rest of the optional-services
 * pattern in core.</p>
 */
public final class AccountServices {

    private static volatile AccountService serviceInstance;
    private static volatile AccountStore storeInstance;

    private AccountServices() {}

    /** Install the default AccountService + AccountStore (called once from Main.java). */
    public static void set(AccountService service, AccountStore store) {
        serviceInstance = service;
        storeInstance = store;
    }

    /** The default AccountService, or {@code null} if not yet installed. */
    public static AccountService service() {
        return serviceInstance;
    }

    /** The default AccountStore, or {@code null} if not yet installed. */
    public static AccountStore store() {
        return storeInstance;
    }

    /** Test-only: clear the singleton. */
    public static void clear() {
        serviceInstance = null;
        storeInstance = null;
    }
}
