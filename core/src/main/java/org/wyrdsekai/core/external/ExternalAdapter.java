package org.wyrdsekai.core.external;

import java.util.Set;

/**
 * interface every external adapter implements.
 *
 * <p>Adapters expose a typed surface over an external service (e.g. GitHub,
 * Slack, Stripe). The runtime resolves {@code world.<namespace>.<method>(args)}
 * calls to {@link #invoke} via {@link ExternalAdapterRegistry}.</p>
 *
 * <p>Implementations should normalize errors into the
 * {@code {code, message, retryable}} shape — items can then implement clean
 * retry loops without parsing service-specific error bodies.</p>
 */
public interface ExternalAdapter {

    /** Top-level namespace, e.g. {@code "github"} for {@code world.github.*}. */
    String namespace();

    /** Set of method names this adapter supports. */
    Set<String> capabilities();

    /** Credential slot name read from The Safe (e.g. {@code "github.token"}). */
    String credentialSlot();

    /** Adapter API version per §3.8 — bumped when the upstream surface changes. */
    default String providerApiVersion() { return "1.0"; }

    /**
     * Dispatch a single call. Adapters MUST return a normalized response;
     * unhandled methods return {@code AdapterResponse.fail("unknown_method", ...)}
     * so the script can branch.
     */
    AdapterResponse invoke(AdapterRequest request);
}
