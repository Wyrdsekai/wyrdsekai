package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.library.LibraryWebhook;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where a librarian's pushed changes arrive (LIBRARY_PROTOCOL.md contract 1.5,
 * {@code library_subscribe}): {@code POST /api/library/webhook/{serviceId}}.
 *
 * <p>The signature is checked against the secret {@code wyrd researcher link} stored as
 * {@code WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_<SERVICEID>}; an unsigned or wrongly signed post is
 * refused and nothing is read from it. A verified recall marks the companions' findings that
 * cite the changed entry, on the spot; a verified landed investigation is told to every
 * companion as a message from the librarian, so "read I-0014" is a thought that occurs
 * rather than a check to remember. The changes feed stays the source of truth: the sleep-time
 * recall still reads it.
 */
public final class LibraryWebhookRoutes {

    private static final Logger log = LoggerFactory.getLogger(LibraryWebhookRoutes.class);

    private final WyrdLuceneStore store;
    /** The unarchived companions' dids and entity ids, read at call time. */
    private final Supplier<List<Map.Entry<String, String>>> companions;
    private final Function<String, String> secrets;

    public LibraryWebhookRoutes(WyrdLuceneStore store, Supplier<List<Map.Entry<String, String>>> companions,
                                Function<String, String> secrets) {
        this.store = store;
        this.companions = companions;
        this.secrets = secrets == null ? id -> System.getenv(LibraryWebhook.secretEnvVar(id)) : secrets;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/library/webhook/{serviceId}", this::handle);
    }

    private void handle(Context ctx) {
        var serviceId = ctx.pathParam("serviceId");
        var secret = secrets.apply(serviceId);
        if (secret == null || secret.isBlank()) {
            ctx.status(404).json(Map.of("ok", false, "error", "no_subscription"));
            return;
        }
        var body = ctx.bodyAsBytes();
        if (!LibraryWebhook.verify(secret, body, ctx.header(LibraryWebhook.SIGNATURE_HEADER))) {
            log.warn("[library-webhook] {}: bad or missing signature — ignored", serviceId);
            ctx.status(401).json(Map.of("ok", false, "error", "invalid_signature"));
            return;
        }
        var change = LibraryWebhook.parse(body);
        if (change == null) {
            ctx.status(400).json(Map.of("ok", false, "error", "not_a_change"));
            return;
        }
        var rows = companions.get();
        var owners = rows.stream().map(Map.Entry::getKey).toList();
        int marked = 0, noted = 0;
        if (change.isRecall() || change.isSourceNotice()) {
            var applied = LibraryWebhook.apply(store, owners, change);
            marked = applied.marked(); noted = applied.noted();
        }
        boolean told = false;
        if (change.isLandedInvestigation()) {
            var name = change.libraryName().isBlank() ? "the librarian" : change.libraryName();
            var text = "A write-up has landed on " + name + "'s shelves: " + change.id()
                + ". It answers a question this house handed over for the night. At the librarian's desk, `read "
                + change.id() + "` shows it. It is reviewed background from another library, never an override of what you know yourself.";
            for (var row : rows) {
                var ref = ZoneGuardian.getCompanionRef(null, row.getValue());
                if (ref != null) { ref.tell(new CompanionActor.ExternalTell("library:" + serviceId, name, text)); told = true; }
            }
        }
        log.info("[library-webhook] {} seq {} {} {} → marked {}, noted {}, told {}", serviceId, change.seq(), change.event(), change.id(), marked, noted, told);
        ctx.json(Map.of("ok", true, "seq", change.seq(), "marked", marked, "noted", noted, "told", told));
    }
}
