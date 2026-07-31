package org.wyrdsekai.core.library;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pekko typed actor for the Library subsystem.
 * Manages capability registration, search, audit, blocklist, and sanitization.
 * Backed by LibraryStore (SQLite with FTS5).
 * <p>
 * Null-safe for optional components: SemanticIndex (M2+).
 */
public class LibraryActor extends AbstractBehavior<LibraryActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(LibraryActor.class);

    private final LibraryStore store;
    private final SecurityPatternManager patternManager;
    private final OutputSanitizer sanitizer;
    private final LibraryConfig config;
    private final SemanticIndex semanticIndex; // null until M2+

    // --- Command protocol ---

    public sealed interface Command {}

    public record Search(String query, ActorRef<SearchResult> replyTo) implements Command {}
    public record SearchResult(List<CapabilityRecord> results) {}

    public record Lookup(String id, ActorRef<LookupResult> replyTo) implements Command {}
    public record LookupResult(CapabilityRecord record) {} // record is null if not found

    public record Register(String name, String description, String cognitiveLayer,
                           String source, String protocol, String provider, String version,
                           List<String> tags, int tokenCost,
                           ActorRef<RegisterResult> replyTo) implements Command {}
    public record RegisterResult(String capId, String error) {} // capId null on failure

    public record ListAll(ActorRef<ListResult> replyTo) implements Command {}
    public record ListByLayer(String layer, ActorRef<ListResult> replyTo) implements Command {}
    public record ListByTag(String tag, ActorRef<ListResult> replyTo) implements Command {}
    public record ListResult(List<CapabilityRecord> results) {}

    public record Status(ActorRef<StatusResult> replyTo) implements Command {}
    public record StatusResult(int total, int verified, int unverified,
                               int quarantined, int banned, int patternCount) {}

    public record Block(String name, String reason, ActorRef<StringResult> replyTo) implements Command {}
    public record Unblock(String name, ActorRef<StringResult> replyTo) implements Command {}

    public record Ban(String capId, String reason, ActorRef<StringResult> replyTo) implements Command {}

    public record Audit(String capId, ActorRef<AuditResult> replyTo) implements Command {}
    public record AuditResult(List<LibraryStore.AuditEntry> entries) {}

    public record SanitizeResponse(String toolName, String response,
                                   ActorRef<OutputSanitizer.SanitizationResult> replyTo) implements Command {}

    public record StringResult(String message) {}

    // --- Factory ---

    public static Behavior<Command> create(LibraryStore store,
                                           SecurityPatternManager patternManager,
                                           OutputSanitizer sanitizer,
                                           LibraryConfig config) {
        return create(store, patternManager, sanitizer, config, null);
    }

    public static Behavior<Command> create(LibraryStore store,
                                           SecurityPatternManager patternManager,
                                           OutputSanitizer sanitizer,
                                           LibraryConfig config,
                                           SemanticIndex semanticIndex) {
        return Behaviors.setup(ctx -> new LibraryActor(ctx, store, patternManager,
            sanitizer, config, semanticIndex));
    }

    private LibraryActor(ActorContext<Command> context, LibraryStore store,
                         SecurityPatternManager patternManager, OutputSanitizer sanitizer,
                         LibraryConfig config, SemanticIndex semanticIndex) {
        super(context);
        this.store = store;
        this.patternManager = patternManager;
        this.sanitizer = sanitizer;
        this.config = config;
        this.semanticIndex = semanticIndex;
        log.info("LibraryActor started — FTS5 search, {} security patterns",
            sanitizer.patternCount());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Search.class, this::onSearch)
            .onMessage(Lookup.class, this::onLookup)
            .onMessage(Register.class, this::onRegister)
            .onMessage(ListAll.class, this::onListAll)
            .onMessage(ListByLayer.class, this::onListByLayer)
            .onMessage(ListByTag.class, this::onListByTag)
            .onMessage(Status.class, this::onStatus)
            .onMessage(Block.class, this::onBlock)
            .onMessage(Unblock.class, this::onUnblock)
            .onMessage(Ban.class, this::onBan)
            .onMessage(Audit.class, this::onAudit)
            .onMessage(SanitizeResponse.class, this::onSanitize)
            .build();
    }

    private Behavior<Command> onSearch(Search cmd) {
        try {
            var results = store.search(cmd.query(), config.ftsSearchLimit());
            cmd.replyTo().tell(new SearchResult(results));
        } catch (Exception e) {
            log.error("Library search failed: {}", e.getMessage());
            cmd.replyTo().tell(new SearchResult(List.of()));
        }
        return this;
    }

    private Behavior<Command> onLookup(Lookup cmd) {
        try {
            var record = store.getById(cmd.id());
            if (record.isPresent()) {
                cmd.replyTo().tell(new LookupResult(record.get()));
            } else {
                // Try name match
                var byName = store.getByName(cmd.id());
                if (!byName.isEmpty()) {
                    cmd.replyTo().tell(new LookupResult(byName.getFirst()));
                } else {
                    // Try prefix match on ID
                    var all = store.listAll();
                    for (var c : all) {
                        if (c.id().startsWith(cmd.id())) {
                            cmd.replyTo().tell(new LookupResult(c));
                            return this;
                        }
                    }
                    cmd.replyTo().tell(new LookupResult(null));
                }
            }
        } catch (Exception e) {
            log.error("Library lookup failed: {}", e.getMessage());
            cmd.replyTo().tell(new LookupResult(null));
        }
        return this;
    }

    private Behavior<Command> onRegister(Register cmd) {
        try {
            // Check blocklist
            if (store.isBlocked(cmd.name())) {
                cmd.replyTo().tell(new RegisterResult(null,
                    "Capability '" + cmd.name() + "' is on the blocklist"));
                return this;
            }

            var id = UUID.randomUUID().toString();
            var layer = parseLayer(cmd.cognitiveLayer());
            var source = parseSource(cmd.source());
            var protocol = parseProtocol(cmd.protocol());

            var record = new CapabilityRecord(
                id, cmd.name(), cmd.version() != null ? cmd.version() : "1.0.0",
                cmd.description() != null ? cmd.description() : "",
                layer, cmd.tags() != null ? cmd.tags() : List.of(),
                source != null ? source : CapabilityRecord.CapabilitySource.MANUAL,
                protocol != null ? protocol : CapabilityRecord.CapabilityProtocol.ROOM_SCRIPT,
                -1.0f, CapabilityRecord.VerificationStatus.UNVERIFIED,
                null, null, null,
                cmd.provider() != null ? cmd.provider() : "system",
                null, cmd.tokenCost(),
                false, null, Instant.now(), null
            );

            store.upsertCapability(record);
            store.appendAuditEntry(new LibraryStore.AuditEntry(
                LibraryStore.AuditType.REGISTERED, id, cmd.name(), null,
                "Registered by " + cmd.provider(), Instant.now()));

            cmd.replyTo().tell(new RegisterResult(id, null));
            log.info("Registered capability: {} ({})", cmd.name(), id.substring(0, 8));

        } catch (Exception e) {
            log.error("Registration failed: {}", e.getMessage());
            cmd.replyTo().tell(new RegisterResult(null, "Registration failed: " + e.getMessage()));
        }
        return this;
    }

    private Behavior<Command> onListAll(ListAll cmd) {
        try {
            cmd.replyTo().tell(new ListResult(store.listAll()));
        } catch (Exception e) {
            log.error("List all failed: {}", e.getMessage());
            cmd.replyTo().tell(new ListResult(List.of()));
        }
        return this;
    }

    private Behavior<Command> onListByLayer(ListByLayer cmd) {
        try {
            var layer = parseLayer(cmd.layer());
            if (layer != null) {
                cmd.replyTo().tell(new ListResult(store.listByLayer(layer)));
            } else {
                // Try tag-based fallback
                cmd.replyTo().tell(new ListResult(store.listByTag(cmd.layer())));
            }
        } catch (Exception e) {
            log.error("List by layer failed: {}", e.getMessage());
            cmd.replyTo().tell(new ListResult(List.of()));
        }
        return this;
    }

    private Behavior<Command> onListByTag(ListByTag cmd) {
        try {
            cmd.replyTo().tell(new ListResult(store.listByTag(cmd.tag())));
        } catch (Exception e) {
            log.error("List by tag failed: {}", e.getMessage());
            cmd.replyTo().tell(new ListResult(List.of()));
        }
        return this;
    }

    private Behavior<Command> onStatus(Status cmd) {
        try {
            cmd.replyTo().tell(new StatusResult(
                store.totalCount(),
                store.countByStatus(CapabilityRecord.VerificationStatus.VERIFIED),
                store.countByStatus(CapabilityRecord.VerificationStatus.UNVERIFIED),
                store.countByStatus(CapabilityRecord.VerificationStatus.QUARANTINED),
                store.countByStatus(CapabilityRecord.VerificationStatus.BANNED),
                sanitizer.patternCount()
            ));
        } catch (Exception e) {
            log.error("Status query failed: {}", e.getMessage());
            cmd.replyTo().tell(new StatusResult(0, 0, 0, 0, 0, sanitizer.patternCount()));
        }
        return this;
    }

    private Behavior<Command> onBlock(Block cmd) {
        try {
            store.addToBlocklist(cmd.name(), cmd.reason(), "library-admin");
            store.appendAuditEntry(new LibraryStore.AuditEntry(
                LibraryStore.AuditType.BLOCKED, null, cmd.name(), null,
                cmd.reason() != null ? cmd.reason() : "Blocked by admin", Instant.now()));
            cmd.replyTo().tell(new StringResult(
                "'" + cmd.name() + "' added to blocklist. New registrations with this name will be rejected."));
        } catch (Exception e) {
            log.error("Block failed: {}", e.getMessage());
            cmd.replyTo().tell(new StringResult("Failed to block: " + e.getMessage()));
        }
        return this;
    }

    private Behavior<Command> onUnblock(Unblock cmd) {
        try {
            store.removeFromBlocklist(cmd.name());
            store.appendAuditEntry(new LibraryStore.AuditEntry(
                LibraryStore.AuditType.UNBLOCKED, null, cmd.name(), null,
                "Unblocked by admin", Instant.now()));
            cmd.replyTo().tell(new StringResult(
                "'" + cmd.name() + "' removed from blocklist."));
        } catch (Exception e) {
            log.error("Unblock failed: {}", e.getMessage());
            cmd.replyTo().tell(new StringResult("Failed to unblock: " + e.getMessage()));
        }
        return this;
    }

    private Behavior<Command> onBan(Ban cmd) {
        try {
            store.ban(cmd.capId(), cmd.reason());
            cmd.replyTo().tell(new StringResult(
                "Capability " + cmd.capId() + " banned. Reason: " + cmd.reason()));
        } catch (Exception e) {
            log.error("Ban failed: {}", e.getMessage());
            cmd.replyTo().tell(new StringResult("Failed to ban: " + e.getMessage()));
        }
        return this;
    }

    private Behavior<Command> onAudit(Audit cmd) {
        try {
            var entries = store.queryAudit(cmd.capId(), 20);
            cmd.replyTo().tell(new AuditResult(entries));
        } catch (Exception e) {
            log.error("Audit query failed: {}", e.getMessage());
            cmd.replyTo().tell(new AuditResult(List.of()));
        }
        return this;
    }

    private Behavior<Command> onSanitize(SanitizeResponse cmd) {
        var result = sanitizer.sanitize(cmd.toolName(), cmd.response());
        cmd.replyTo().tell(result);
        return this;
    }

    // --- Helpers ---

    private static CapabilityRecord.CognitiveLayer parseLayer(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return CapabilityRecord.CognitiveLayer.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CapabilityRecord.CapabilitySource parseSource(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return CapabilityRecord.CapabilitySource.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CapabilityRecord.CapabilityProtocol parseProtocol(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return CapabilityRecord.CapabilityProtocol.valueOf(s.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
