package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.IdentityOutboxRecord;
import org.wyrdsekai.core.identity.IdentityOutboxStore;
import org.wyrdsekai.core.nostr.OutboxNostrMirror;

import java.util.Map;

/**
 * REST endpoints for signed outbox records.
 *
 * <ul>
 *   <li>{@code GET  /api/identity/outbox/{did}} — fetch (200 with wire JSON, or 404)</li>
 *   <li>{@code PUT  /api/identity/outbox}        — submit a signed record. Server
 *       verifies signature against the DID's derivable Ed25519 pubkey, then
 *       upserts if newer than the existing row.</li>
 * </ul>
 *
 * <p>Reads are public — the records are designed to be world-readable. Writes
 * don't need auth either: the signature IS the auth. If the signature
 * validates, the writer holds the DID's private key, and we accept the update.
 *
 * <p>Returns:
 * <ul>
 *   <li>200 — accepted (inserted or replaced)</li>
 *   <li>400 — malformed JSON, missing fields, or invalid signature</li>
 *   <li>409 — record is stale (existing has higher updatedAt)</li>
 * </ul>
 */
public final class IdentityOutboxRoutes {

    private static final Logger log = LoggerFactory.getLogger(IdentityOutboxRoutes.class);

    private final IdentityOutboxStore store;

    public IdentityOutboxRoutes(IdentityOutboxStore store) {
        this.store = store;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/identity/outbox/{did}", this::handleGet);
        app.put("/api/identity/outbox", this::handlePut);
    }

    private void handleGet(Context ctx) {
        var did = ctx.pathParam("did");
        // path params are URL-decoded by Javalin, so "did:key:..." round-trips fine
        var record = store.get(did);
        if (record.isEmpty()) {
            ctx.status(404).json(Map.of("error", "not_found", "did", did));
            return;
        }
        ctx.contentType("application/json");
        ctx.result(record.get().toWireJson());
    }

    private void handlePut(Context ctx) {
        var body = ctx.body();
        var parsed = IdentityOutboxRecord.fromWireJson(body);
        if (parsed.isEmpty()) {
            ctx.status(400).json(Map.of("error", "malformed_json"));
            return;
        }
        var record = parsed.get();
        if (record.did() == null || record.did().isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_did"));
            return;
        }
        if (record.sig() == null || record.sig().isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_signature"));
            return;
        }
        if (!record.verify()) {
            log.info("Outbox PUT rejected: signature invalid for {}", record.did());
            ctx.status(400).json(Map.of("error", "invalid_signature", "did", record.did()));
            return;
        }
        var result = store.upsertIfNewer(record);
        switch (result) {
            case INSERTED, REPLACED -> {
                ctx.status(200).json(Map.of(
                    "result", result.name().toLowerCase(),
                    "did", record.did(),
                    "updated_at", record.updatedAt()));
                // Phase 2c: fire-and-forget downstream Nostr mirror.
                // Outbox is authoritative; Nostr broadcast is downstream and
                // explicitly opt-in (only fires when record declares a
                // nostr ChannelRef AND the Nostr adapter is registered).
                try { OutboxNostrMirror.maybeMirror(record); }
                catch (Exception e) {
                    log.warn("Outbox→Nostr mirror raised on PUT for {}: {}",
                        record.did(), e.getMessage());
                }
            }
            case STALE -> ctx.status(409).json(Map.of(
                "error", "stale",
                "did", record.did(),
                "submitted_updated_at", record.updatedAt()));
        }
    }
}
