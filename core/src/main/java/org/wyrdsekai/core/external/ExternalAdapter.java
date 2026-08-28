package org.wyrdsekai.core.external;

import java.util.List;
import java.util.Map;
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

    /**
     * The subset of {@link #capabilities()} that actually reaches a live backend.
     *
     * <h2>Why this exists</h2>
     * {@code capabilities()} is the adapter's INTENT — the surface it means to cover, and
     * the right thing to keep in the registry as documentation. But the items-as-tools
     * contract is generated from the registry, so intent was being handed to an authoring
     * model as if it were fact. On 2026-08-22, {@code maps} advertised four methods with a
     * live key while three of them returned {@code not_yet_wired}; {@code openweather}
     * advertised {@code alerts} the same way; {@code datagov} advertised {@code query}
     * while every call answered {@code credential_missing}. A tool built on any of them
     * would pass its smoke and fail in a person's hands.
     *
     * <p>Defaults to {@code capabilities()} so an adapter that wires everything it declares
     * needs no extra code. Scaffolding declares the difference — see
     * {@code AbstractPhaseUAdapter}, where the default flips to none.
     */
    default Set<String> wiredCapabilities() { return capabilities(); }

    /**
     * The keys each wired method actually returns in {@code data}, by method name.
     *
     * <h2>Why this exists</h2>
     * The generated items-as-tools contract named methods but never said what came back,
     * so an authoring model guessed. On 2026-08-22 a working weather tool spoke
     * "67.06°F (undefined°C)" — it had assumed a {@code temp_c} beside {@code temp_f},
     * and nothing in the contract could have told it otherwise. A method's return shape is
     * part of its interface; a contract that omits it invites exactly this.
     *
     * <p>Empty by default: no declaration is an honest "we promise nothing", and the
     * contract then says so rather than inventing keys. Where an adapter declares them,
     * a test holds the declaration to what the code really produces.
     */
    default Map<String, List<String>> resultKeys() { return Map.of(); }

    /**
     * For a result key that is a LIST, the keys of each row: method → (list key → row keys).
     * A list named without its row shape is a list the author guesses at — 2026-08-23 a
     * barometer printed "undefined: undefined°F" for every forecast day.
     */
    default Map<String, Map<String, List<String>>> nestedResultKeys() { return Map.of(); }

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
