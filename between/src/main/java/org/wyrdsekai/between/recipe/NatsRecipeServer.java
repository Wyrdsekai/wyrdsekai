package org.wyrdsekai.between.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;

import java.util.function.Predicate;

/**
 * Lender side of the cross-zone recipe-borrow path (, option b).
 * Subscribes to {@code federation.recipe.{myZone}.run}, trust-gates the source
 * zone, runs the recipe locally via an injected {@link BorrowExecutor} (whose own
 * resource preflight remains the final authority), and publishes a single
 * {@link NatsRecipeProtocol.Response} back to the borrower.
 *
 * <p>Trust is mandatory: a request from a zone without a standing bilateral
 * agreement is refused with {@code status="DENIED"} and never reaches the
 * executor. A household does not lend its GPU for a day to a stranger.</p>
 */
public final class NatsRecipeServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsRecipeServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Runs a borrowed recipe on this (lender) node and reports the outcome. */
    @FunctionalInterface
    public interface BorrowExecutor {
        Outcome run(NatsRecipeProtocol.Request req) throws Exception;
    }

    /** Lender-side run result. {@code status} is a {@code RecipeRunner.Status} name. */
    public record Outcome(String status, String message, String runId) {}

    private final RelaySessionTransport transport;
    private final String myZone;
    private final Predicate<String> trustedZone;
    private final BorrowExecutor executor;
    private volatile Object subscription;

    public NatsRecipeServer(RelaySessionTransport transport, String myZone,
                            Predicate<String> trustedZone, BorrowExecutor executor) {
        this.transport = transport;
        this.myZone = myZone;
        this.trustedZone = trustedZone;
        this.executor = executor;
    }

    /** Begin listening for borrow requests addressed to this zone. */
    public void start() {
        if (transport == null || !transport.isConnected()) {
            log.warn("NatsRecipeServer for '{}' not started — transport not connected", myZone);
            return;
        }
        subscription = transport.subscribe(
            NatsRecipeProtocol.runSubject(myZone), this::onRequest);
        log.info("NatsRecipeServer listening for borrow requests on zone '{}'", myZone);
    }

    private void onRequest(byte[] data) {
        NatsRecipeProtocol.Request req;
        try {
            req = MAPPER.readValue(data, NatsRecipeProtocol.Request.class);
        } catch (Exception e) {
            log.warn("NatsRecipeServer '{}' dropped unparseable borrow request: {}", myZone, e.toString());
            return;
        }

        // Trust gate — only standing bilateral peers may borrow our hardware.
        if (req.sourceZone() == null || !trustedZone.test(req.sourceZone())) {
            log.info("NatsRecipeServer '{}' DENIED borrow of '{}' from untrusted zone '{}'",
                myZone, req.recipeName(), req.sourceZone());
            respond(req, new NatsRecipeProtocol.Response(req.requestId(), myZone,
                "DENIED",
                "Zone '" + req.sourceZone() + "' has no standing agreement with '" + myZone + "'.",
                null, null));
            return;
        }

        log.info("NatsRecipeServer '{}' accepting borrow of '{}' from '{}' (agent={})",
            myZone, req.recipeName(), req.sourceZone(), req.agentDid());
        try {
            Outcome out = executor.run(req);
            respond(req, new NatsRecipeProtocol.Response(req.requestId(), myZone,
                out.status(), out.message(), out.runId(), null));
        } catch (Exception e) {
            log.warn("NatsRecipeServer '{}' borrow of '{}' threw: {}",
                myZone, req.recipeName(), e.toString());
            respond(req, new NatsRecipeProtocol.Response(req.requestId(), myZone,
                "ERROR", "Lender failed to run recipe", null, e.toString()));
        }
    }

    private void respond(NatsRecipeProtocol.Request req, NatsRecipeProtocol.Response resp) {
        try {
            transport.publish(NatsRecipeProtocol.resultSubject(req.requestId()),
                MAPPER.writeValueAsBytes(resp));
        } catch (Exception e) {
            log.error("NatsRecipeServer '{}' failed to publish borrow result for {}: {}",
                myZone, req.requestId(), e.toString());
        }
    }

    @Override
    public void close() {
        if (subscription != null && transport != null) {
            transport.closeDispatcherObj(subscription);
            subscription = null;
        }
    }
}
