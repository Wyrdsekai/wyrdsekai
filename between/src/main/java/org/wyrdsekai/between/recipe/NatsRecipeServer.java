package org.wyrdsekai.between.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.ZoneSignedMessages;

import java.nio.charset.StandardCharsets;
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

    /**
     * Checks each borrow request is signed by the zone it names, with the key that zone's active
     * agreement pinned (2026-09-28: the zone used to be taken from the request, unsigned). Without it,
     * only a test wiring runs the lender.
     */
    private volatile ZoneSignedMessages seal;

    public void setSeal(ZoneSignedMessages seal) {
        this.seal = seal;
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
        String verifiedZone = null;
        var sealer = seal;
        if (sealer != null) {
            var opened = sealer.open(data);
            if (opened.isEmpty()) {
                denyUnverified(data);
                return;
            }
            verifiedZone = opened.get().zoneId();
            data = opened.get().message().toString().getBytes(StandardCharsets.UTF_8);
        }
        NatsRecipeProtocol.Request req;
        try {
            req = MAPPER.readValue(data, NatsRecipeProtocol.Request.class);
        } catch (Exception e) {
            log.warn("NatsRecipeServer '{}' dropped unparseable borrow request: {}", myZone, e.toString());
            return;
        }
        if (verifiedZone != null && !verifiedZone.equals(req.sourceZone())) {
            log.warn("NatsRecipeServer '{}' dropped a borrow request naming zone '{}' but signed by '{}'",
                myZone, req.sourceZone(), verifiedZone);
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

    /** Answer a request that failed the signature check, so the borrower is not left waiting. */
    private void denyUnverified(byte[] data) {
        try {
            var root = MAPPER.readTree(data);
            var body = root.path("payload").path("message");
            var requestId = (body.isMissingNode() ? root : body).path("requestId").asText("");
            if (requestId.isBlank()) return;
            var req = new NatsRecipeProtocol.Request(requestId, null, null, null, null, null);
            respond(req, new NatsRecipeProtocol.Response(requestId, myZone, "DENIED",
                "The request is not signed by a zone with an active agreement with '" + myZone + "'.",
                null, null));
        } catch (Exception ignored) {
            // unreadable: nothing to answer
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
