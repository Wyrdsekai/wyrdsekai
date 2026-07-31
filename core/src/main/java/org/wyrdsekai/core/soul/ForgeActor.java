package org.wyrdsekai.core.soul;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.persistence.typed.ReplicaId;
import org.apache.pekko.persistence.typed.ReplicationId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.Effect;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.ReplicatedEventSourcedBehavior;
import org.apache.pekko.persistence.typed.javadsl.ReplicatedEventSourcing;
import org.apache.pekko.persistence.typed.javadsl.ReplicationContext;
import org.apache.pekko.persistence.typed.javadsl.RetentionCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.AgentDelegation;
import org.wyrdsekai.core.substrate.VoiceAligner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-sourced actor managing the Forge — the in-world room where
 * soul operations happen. One per zone (singleton like CountingHouse).
 *
 * The ForgeActor persists an audit trail of all soul operations
 * (forge, restore, inspect, fork, archive, birth) via Pekko RES.
 * Actual soul manifest storage is delegated to SoulStore.
 *
 * All operations that modify a soul require the owning agent's
 * consent (via SoulConsent or delegation). The Forge Master (NPC)
 * facilitates but cannot act unilaterally.
 */
public class ForgeActor extends ReplicatedEventSourcedBehavior<
        ForgeCommand, ForgeEvent, ForgeState> {

    private static final Logger log = LoggerFactory.getLogger(ForgeActor.class);

    public static final ReplicaId DEFAULT_REPLICA = new ReplicaId("local");
    public static final Set<ReplicaId> DEFAULT_REPLICAS = Set.of(DEFAULT_REPLICA);
    static final String DEFAULT_QUERY_PLUGIN = "jdbc-read-journal";

    private final SoulStore soulStore;

    /**
     * Phase 6: Consent records — maps ownerDid to most recent consent per requesterDid.
     * Key: ownerDid + ":" + requesterDid (or "*" for public consent).
     */
    private final Map<String, SoulConsent> consentStore = new ConcurrentHashMap<>();

    /** Phase 6: Delegation registry for permission checks on soul operations. */
    private final AgentDelegation delegation;

    /** Crucible: Behavioral evaluator for variant fitness scoring. */
    private final BehavioralEvaluator evaluator = new BehavioralEvaluator();

    /**
     * Crucible (EXPERIMENTAL): Voice alignment via per-agent LoRA fine-tuning.
     * Default off — enable via ForgeActor.create(..., voiceAligner).
     * When enabled, triggers after variant adoption if enough conversation data exists.
     */
    private final VoiceAligner voiceAligner; // nullable
    private final boolean crucibleEnabled;

    private ForgeActor(ReplicationContext replicationContext, SoulStore soulStore,
                       AgentDelegation delegation,
                       VoiceAligner voiceAligner) {
        super(replicationContext);
        this.soulStore = soulStore;
        this.delegation = delegation;
        this.voiceAligner = voiceAligner;
        this.crucibleEnabled = voiceAligner != null;
        if (crucibleEnabled) {
            log.info("Crucible ENABLED (experimental) — voice alignment active");
        }
    }

    /** Create with default delegation registry (empty — agents always have self-access). */
    public static Behavior<ForgeCommand> create(SoulStore soulStore) {
        return create(soulStore, new AgentDelegation(), null);
    }

    /** Create with a shared delegation registry for consent/permission checks. */
    public static Behavior<ForgeCommand> create(SoulStore soulStore, AgentDelegation delegation) {
        return create(soulStore, delegation, null);
    }

    /**
     * Create with optional Crucible voice alignment (EXPERIMENTAL).
     * Pass non-null voiceAligner to enable per-agent LoRA fine-tuning during Forge cycles.
     */
    public static Behavior<ForgeCommand> create(SoulStore soulStore, AgentDelegation delegation,
                                                 VoiceAligner voiceAligner) {
        return create(DEFAULT_REPLICA, DEFAULT_REPLICAS, DEFAULT_QUERY_PLUGIN,
            soulStore, delegation, voiceAligner);
    }

    public static Behavior<ForgeCommand> create(
            ReplicaId selfReplica, Set<ReplicaId> allReplicas,
            String queryPluginId, SoulStore soulStore) {
        return create(selfReplica, allReplicas, queryPluginId, soulStore, new AgentDelegation(), null);
    }

    public static Behavior<ForgeCommand> create(
            ReplicaId selfReplica, Set<ReplicaId> allReplicas,
            String queryPluginId, SoulStore soulStore, AgentDelegation delegation) {
        return create(selfReplica, allReplicas, queryPluginId, soulStore, delegation, null);
    }

    public static Behavior<ForgeCommand> create(
            ReplicaId selfReplica, Set<ReplicaId> allReplicas,
            String queryPluginId, SoulStore soulStore, AgentDelegation delegation,
            VoiceAligner voiceAligner) {
        return ReplicatedEventSourcing.commonJournalConfig(
            new ReplicationId("Forge", "singleton", selfReplica),
            allReplicas,
            queryPluginId,
            repCtx -> {
                log.info("ForgeActor starting — replicated persistence, consent checks enabled");
                return new ForgeActor(repCtx, soulStore, delegation, voiceAligner);
            }
        );
    }

    // --- Consent Management (Phase 6) ---

    /**
     * Register a consent record. The soul owner grants a requester permission to
     * inspect (or fork) their soul at a specific level.
     */
    public void grantConsent(SoulConsent consent) {
        var key = consent.ownerDid() + ":" + consent.requesterDid();
        consentStore.put(key, consent);
        log.info("Consent granted: {} allows {} at level {}",
            consent.ownerDid(), consent.requesterDid(), consent.level());
    }

    /**
     * Look up the best consent record for a given owner and requester.
     * Checks specific consent first, then wildcard ("*") consent.
     */
    Optional<SoulConsent> findConsent(String ownerDid, String requesterDid) {
        // Check specific consent
        var specific = consentStore.get(ownerDid + ":" + requesterDid);
        if (specific != null && specific.isValid()) {
            return Optional.of(specific);
        }
        // Check wildcard (public) consent
        var wildcard = consentStore.get(ownerDid + ":*");
        if (wildcard != null && wildcard.isValid()) {
            return Optional.of(wildcard);
        }
        return Optional.empty();
    }

    @Override
    public ForgeState emptyState() {
        return ForgeState.empty();
    }

    @Override
    public CommandHandler<ForgeCommand, ForgeEvent, ForgeState> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(ForgeCommand.Forge.class, this::onForge)
            .onCommand(ForgeCommand.Restore.class, this::onRestore)
            .onCommand(ForgeCommand.Inspect.class, this::onInspect)
            .onCommand(ForgeCommand.Compare.class, this::onCompare)
            .onCommand(ForgeCommand.History.class, this::onHistory)
            .onCommand(ForgeCommand.Fork.class, this::onFork)
            .onCommand(ForgeCommand.Birth.class, this::onBirth)
            .onCommand(ForgeCommand.Archive.class, this::onArchive)
            .onCommand(ForgeCommand.GetState.class, this::onGetState)
            .onCommand(ForgeCommand.Grow.class, this::onGrow)
            .onCommand(ForgeCommand.Evaluate.class, this::onEvaluate)
            .onCommand(ForgeCommand.Adopt.class, this::onAdopt)
            .onCommand(ForgeCommand.Discard.class, this::onDiscard)
            .build();
    }

    @Override
    public EventHandler<ForgeState, ForgeEvent> eventHandler() {
        return newEventHandlerBuilder()
            .forAnyState()
            .onEvent(ForgeEvent.SoulForged.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.SoulRestored.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.SoulInspected.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.SoulForked.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.SoulArchived.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.SoulBorn.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.CrucibleStarted.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.VariantEvaluated.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.VariantAdopted.class, (state, event) -> state.apply(event))
            .onEvent(ForgeEvent.VariantDiscarded.class, (state, event) -> state.apply(event))
            .build();
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 2);
    }

    // --- Command Handlers ---

    private Effect<ForgeEvent, ForgeState> onForge(ForgeState state, ForgeCommand.Forge cmd) {
        // #428 defensive — preserve the prior manifest's voiceProfile if the
        // incoming manifest has none. SoulMaintenanceCycle now threads
        // voiceProfile through (Option A), but this catches any future forge
        // caller that forgets to do the same. Belt-and-suspenders: even one
        // wipe of voiceProfile loses hours of Forge proposals, so defending
        // at the write-site is worth the 5 lines.
        var manifest = cmd.manifest();
        var incomingVoice = manifest.voiceProfile();
        if (incomingVoice == null
                || (incomingVoice.clauses() != null && incomingVoice.clauses().isEmpty()
                    && incomingVoice.history() != null && incomingVoice.history().isEmpty())) {
            var prior = soulStore.latest(manifest.did());
            if (prior.isPresent() && prior.get().voiceProfile() != null
                    && prior.get().voiceProfile().clauses() != null
                    && !prior.get().voiceProfile().clauses().isEmpty()) {
                log.info("ForgeActor preserved prior voiceProfile for {} "
                        + "(rev={}, {} clauses) — incoming was empty",
                    manifest.did(),
                    prior.get().voiceProfile().revision(),
                    prior.get().voiceProfile().clauses().size());
                manifest = manifest.withVoiceProfile(prior.get().voiceProfile());
            }
        }
        final var finalManifest = manifest;
        log.info("Forging soul {} v{}", finalManifest.did(), finalManifest.manifestVersion());

        return Effect().persist(
            new ForgeEvent.SoulForged(finalManifest.did(), Instant.now(),
                finalManifest.manifestVersion(), finalManifest.contentHash())
        ).thenRun(newState -> {
            soulStore.store(finalManifest);
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Ok(
                "Soul forged: " + finalManifest.did() + " v" + finalManifest.manifestVersion()));
        });
    }

    private Effect<ForgeEvent, ForgeState> onRestore(ForgeState state, ForgeCommand.Restore cmd) {
        var manifest = soulStore.latest(cmd.did());
        if (manifest.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "No soul found for DID: " + cmd.did()));
            return Effect().none();
        }

        return Effect().persist(
            new ForgeEvent.SoulRestored(cmd.did(), Instant.now(), cmd.fromZone())
        ).thenRun(newState -> {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.ManifestResult(manifest.get()));
        });
    }

    private Effect<ForgeEvent, ForgeState> onInspect(ForgeState state, ForgeCommand.Inspect cmd) {
        var manifest = soulStore.latest(cmd.did());
        if (manifest.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "No soul found for DID: " + cmd.did()));
            return Effect().none();
        }

        // Phase 6: Check consent before returning soul data.
        // Self-inspection is always allowed (key sovereignty).
        // Others need consent or delegation.
        var consent = findConsent(cmd.did(), cmd.byDid());
        var permCheck = DelegationChainValidator.validateWithConsent(
            cmd.byDid(), cmd.did(),
            DelegationChainValidator.PERM_SOUL_INSPECT,
            delegation, consent.orElse(null));
        if (permCheck.isPresent()) {
            log.info("Inspect denied: {} tried to inspect {} — {}",
                cmd.byDid(), cmd.did(), permCheck.get());
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(permCheck.get()));
            return Effect().none();
        }

        return Effect().persist(
            new ForgeEvent.SoulInspected(cmd.did(), cmd.byDid(), Instant.now())
        ).thenRun(newState -> {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.ManifestResult(manifest.get()));
        });
    }

    private Effect<ForgeEvent, ForgeState> onCompare(ForgeState state, ForgeCommand.Compare cmd) {
        var m1 = soulStore.latest(cmd.did1());
        var m2 = soulStore.latest(cmd.did2());

        if (m1.isEmpty() || m2.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "Cannot compare: one or both DIDs not found"));
            return Effect().none();
        }

        var summary = compareSouls(m1.get(), m2.get());
        cmd.replyTo().tell(new ForgeCommand.ForgeResult.ComparisonResult(summary));
        return Effect().none();
    }

    private Effect<ForgeEvent, ForgeState> onHistory(ForgeState state, ForgeCommand.History cmd) {
        List<ForgeEvent> events = state.eventsForDid(cmd.did());
        cmd.replyTo().tell(new ForgeCommand.ForgeResult.HistoryResult(events));
        return Effect().none();
    }

    private Effect<ForgeEvent, ForgeState> onFork(ForgeState state, ForgeCommand.Fork cmd) {
        var parent = soulStore.latest(cmd.parentDid());
        if (parent.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "Parent soul not found: " + cmd.parentDid()));
            return Effect().none();
        }

        // Phase 6: Validate delegation chain before allowing fork.
        // The requestor needs soul:fork permission on the parent soul.
        // Self-fork (parent forking itself) is always allowed via DelegationChainValidator.
        var requestor = cmd.requestorDid() != null ? cmd.requestorDid() : cmd.parentDid();
        var consent = findConsent(cmd.parentDid(), requestor);
        var permCheck = DelegationChainValidator.validateWithConsent(
            requestor, cmd.parentDid(),
            DelegationChainValidator.PERM_SOUL_FORK,
            delegation, consent.orElse(null));
        if (permCheck.isPresent()) {
            log.info("Fork denied: {} tried to fork parent {} — {}",
                requestor, cmd.parentDid(), permCheck.get());
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(permCheck.get()));
            return Effect().none();
        }

        // Create child manifest from parent
        var parentManifest = parent.get();
        var childManifest = SoulManifest.forge(
            cmd.childDid(), cmd.childPublicKeyMultibase(),
            List.of(), // fresh key log
            cmd.parentDid(), 1,
            parentManifest.profile(), parentManifest.residentIdentity(),
            parentManifest.soulFragments(), parentManifest.retrievalK(),
            parentManifest.soulSpecCompat(),
            parentManifest.genome(), parentManifest.mirrorCalibration(),
            parentManifest.memory(), parentManifest.relationships(),
            parentManifest.learnedPatterns(), parentManifest.worldKnowledge(),
            parentManifest.vitalitySnapshot(), parentManifest.fingerprint()
        );

        return Effect().persist(
            new ForgeEvent.SoulForked(cmd.parentDid(), cmd.childDid(), Instant.now())
        ).thenRun(newState -> {
            soulStore.store(childManifest);
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.ManifestResult(childManifest));
        });
    }

    private Effect<ForgeEvent, ForgeState> onBirth(ForgeState state, ForgeCommand.Birth cmd) {
        var manifest = cmd.manifest();
        log.info("Soul born: {} with genome {}", manifest.did(),
            manifest.genome() != null ? manifest.genome().name() : "default");

        return Effect().persist(
            new ForgeEvent.SoulBorn(manifest.did(), Instant.now(),
                manifest.genome() != null ? manifest.genome().name() : "default")
        ).thenRun(newState -> {
            soulStore.store(manifest);
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Ok(
                "Soul born: " + manifest.did()));
        });
    }

    private Effect<ForgeEvent, ForgeState> onArchive(ForgeState state, ForgeCommand.Archive cmd) {
        if (!soulStore.exists(cmd.did())) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "No soul found for DID: " + cmd.did()));
            return Effect().none();
        }

        return Effect().persist(
            new ForgeEvent.SoulArchived(cmd.did(), Instant.now(), cmd.reason())
        ).thenRun(newState -> {
            soulStore.archive(cmd.did(), cmd.reason());
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Ok(
                "Soul archived: " + cmd.did()));
        });
    }

    private Effect<ForgeEvent, ForgeState> onGetState(ForgeState state, ForgeCommand.GetState cmd) {
        cmd.replyTo().tell(state);
        return Effect().none();
    }

    // --- Crucible Command Handlers (§85.16) ---

    private Effect<ForgeEvent, ForgeState> onGrow(ForgeState state, ForgeCommand.Grow cmd) {
        var manifest = soulStore.latest(cmd.did());
        if (manifest.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "No soul found for DID: " + cmd.did()));
            return Effect().none();
        }

        // Consent check: the agent (or its delegate) must authorize growth
        var consent = findConsent(cmd.did(), cmd.requesterDid());
        var permCheck = DelegationChainValidator.validateWithConsent(
            cmd.requesterDid(), cmd.did(),
            DelegationChainValidator.PERM_SOUL_FORGE,
            delegation, consent.orElse(null));
        if (permCheck.isPresent()) {
            log.info("Grow denied: {} tried to grow {} — {}",
                cmd.requesterDid(), cmd.did(), permCheck.get());
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(permCheck.get()));
            return Effect().none();
        }

        var currentManifest = manifest.get();
        log.info("Crucible growth starting for {} at level {} with {} max variants",
            cmd.did(), cmd.level(), cmd.maxVariants());

        // Register scenarios with the evaluator
        if (cmd.scenarios() != null && !cmd.scenarios().isEmpty()) {
            evaluator.addScenarios(cmd.scenarios());
        }

        // Generate variants
        var generator = new VariantGenerator(currentManifest, new Random());
        var variants = generator.generate(cmd.level(), cmd.maxVariants());

        // Evaluate each variant — using empty scenario results for structural evaluation
        // (actual LLM-based scenario testing would happen via CrucibleMcpBridge in production)
        var results = new ArrayList<BehavioralEvaluator.EvaluationResult>();
        for (var variant : variants) {
            // Build scenario results map — default to true for structural evaluation
            var scenarioResults = new HashMap<String, Boolean>();
            if (cmd.scenarios() != null) {
                for (var scenario : cmd.scenarios()) {
                    scenarioResults.put(scenario.id(), true);
                }
            }
            var evalResult = evaluator.evaluate(currentManifest, variant, scenarioResults);
            results.add(evalResult);
        }

        // Rank and find recommended
        var ranked = evaluator.rank(results);
        var recommended = ranked.isEmpty() ? null : ranked.getFirst();

        return Effect().persist(
            new ForgeEvent.CrucibleStarted(cmd.did(), cmd.level(), cmd.maxVariants(), Instant.now())
        ).thenRun(newState -> {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.GrowthResult(
                cmd.did(), ranked, recommended));
        });
    }

    private Effect<ForgeEvent, ForgeState> onEvaluate(ForgeState state, ForgeCommand.Evaluate cmd) {
        var manifest = soulStore.latest(cmd.did());
        if (manifest.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "No soul found for DID: " + cmd.did()));
            return Effect().none();
        }

        // Consent check
        var consent = findConsent(cmd.did(), cmd.requesterDid());
        var permCheck = DelegationChainValidator.validateWithConsent(
            cmd.requesterDid(), cmd.did(),
            DelegationChainValidator.PERM_SOUL_FORGE,
            delegation, consent.orElse(null));
        if (permCheck.isPresent()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(permCheck.get()));
            return Effect().none();
        }

        var evalResult = evaluator.evaluate(manifest.get(), cmd.variant(), cmd.scenarioResults());

        return Effect().persist(
            new ForgeEvent.VariantEvaluated(cmd.did(), cmd.variant().variantId(),
                evalResult.fitness(), evalResult.recommended(), Instant.now())
        ).thenRun(newState -> {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.EvaluationComplete(
                cmd.did(), evalResult));
        });
    }

    private Effect<ForgeEvent, ForgeState> onAdopt(ForgeState state, ForgeCommand.Adopt cmd) {
        var manifest = soulStore.latest(cmd.did());
        if (manifest.isEmpty()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(
                "No soul found for DID: " + cmd.did()));
            return Effect().none();
        }

        // Consent check
        var consent = findConsent(cmd.did(), cmd.requesterDid());
        var permCheck = DelegationChainValidator.validateWithConsent(
            cmd.requesterDid(), cmd.did(),
            DelegationChainValidator.PERM_SOUL_FORGE,
            delegation, consent.orElse(null));
        if (permCheck.isPresent()) {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Error(permCheck.get()));
            return Effect().none();
        }

        // Find the variant in pending state
        var pendingVariant = state.pendingVariant(cmd.variantId());
        if (pendingVariant.isEmpty()) {
            // Also accept if the variant ID matches a known pattern (for direct adoption)
            log.info("Variant {} not found in pending state for {}, persisting adoption anyway",
                cmd.variantId(), cmd.did());
        }

        var currentManifest = manifest.get();
        log.info("Adopting variant {} for soul {}", cmd.variantId(), cmd.did());

        // If we have the variant, apply it to the manifest
        if (pendingVariant.isPresent()) {
            var variant = pendingVariant.get();
            var updatedManifest = applyVariantToManifest(currentManifest, variant);
            soulStore.store(updatedManifest);
        }

        return Effect().persist(List.of(
            new ForgeEvent.VariantAdopted(cmd.did(), cmd.variantId(), Instant.now()),
            new ForgeEvent.SoulForged(cmd.did(), Instant.now(),
                currentManifest.manifestVersion() + 1, currentManifest.contentHash())
        )).thenRun(newState -> {
            // Crucible (EXPERIMENTAL): trigger voice alignment after variant adoption
            if (crucibleEnabled && cmd.conversationCorpus() != null) {
                try {
                    var modelPath = System.getenv().getOrDefault(
                        "WYRDSEKAI_MODEL_PATH", "qwen3.5-4b");
                    var adapterPath = voiceAligner.align(
                        cmd.did(), cmd.did(), modelPath, cmd.conversationCorpus());
                    if (adapterPath != null) {
                        log.info("Crucible: voice alignment complete for {} — adapter at {}",
                            cmd.did(), adapterPath);
                    }
                } catch (Exception e) {
                    log.warn("Crucible: voice alignment failed for {}: {}",
                        cmd.did(), e.getMessage());
                }
            }
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Ok(
                "Variant adopted: " + cmd.variantId() + " for " + cmd.did()));
        });
    }

    private Effect<ForgeEvent, ForgeState> onDiscard(ForgeState state, ForgeCommand.Discard cmd) {
        log.info("Discarding variant {} for soul {}: {}", cmd.variantId(), cmd.did(), cmd.reason());

        return Effect().persist(
            new ForgeEvent.VariantDiscarded(cmd.did(), cmd.variantId(), cmd.reason(), Instant.now())
        ).thenRun(newState -> {
            cmd.replyTo().tell(new ForgeCommand.ForgeResult.Ok(
                "Variant discarded: " + cmd.variantId()));
        });
    }

    /**
     * Apply a variant's changes to a soul manifest, producing an updated manifest.
     */
    private SoulManifest applyVariantToManifest(SoulManifest current, BehavioralEvaluator.SoulVariant variant) {
        return switch (variant.level()) {
            case 1 -> {
                // Level 1: Update identity, fragments, genome
                String newIdentity = variant.proposedResidentIdentity() != null
                    ? variant.proposedResidentIdentity() : current.residentIdentity();
                var newFragments = variant.proposedFragments() != null
                    ? variant.proposedFragments() : current.soulFragments();
                var newGenome = variant.proposedGenome() != null
                    ? variant.proposedGenome() : current.genome();

                yield SoulManifest.forge(
                    current.did(), current.publicKeyMultibase(), current.keyLog(),
                    current.parentDid(), current.manifestVersion() + 1,
                    current.profile(), newIdentity, newFragments, current.retrievalK(),
                    current.soulSpecCompat(), newGenome, current.mirrorCalibration(),
                    current.memory(), current.relationships(),
                    current.learnedPatterns(), current.worldKnowledge(),
                    current.vitalitySnapshot(), current.fingerprint()
                );
            }
            case 2 -> {
                // Level 2: Record adapter URI — manifest stays the same structurally,
                // but we bump version to record the LoRA adoption
                yield SoulManifest.forge(
                    current.did(), current.publicKeyMultibase(), current.keyLog(),
                    current.parentDid(), current.manifestVersion() + 1,
                    current.profile(), current.residentIdentity(),
                    current.soulFragments(), current.retrievalK(),
                    current.soulSpecCompat(), current.genome(), current.mirrorCalibration(),
                    current.memory(), current.relationships(),
                    current.learnedPatterns(),
                    addToWorldKnowledge(current.worldKnowledge(),
                        "activeAdapter", variant.adapterUri()),
                    current.vitalitySnapshot(), current.fingerprint()
                );
            }
            case 3 -> {
                // Level 3: Record model change in world knowledge
                yield SoulManifest.forge(
                    current.did(), current.publicKeyMultibase(), current.keyLog(),
                    current.parentDid(), current.manifestVersion() + 1,
                    current.profile(), current.residentIdentity(),
                    current.soulFragments(), current.retrievalK(),
                    current.soulSpecCompat(), current.genome(), current.mirrorCalibration(),
                    current.memory(), current.relationships(),
                    current.learnedPatterns(),
                    addToWorldKnowledge(current.worldKnowledge(),
                        "activeModel", variant.proposedModelId()),
                    current.vitalitySnapshot(), current.fingerprint()
                );
            }
            default -> current;
        };
    }

    private static Map<String, String> addToWorldKnowledge(Map<String, String> existing,
                                                             String key, String value) {
        var updated = new HashMap<>(existing);
        updated.put(key, value);
        return Map.copyOf(updated);
    }

    // --- Helpers ---

    private static String compareSouls(SoulManifest a, SoulManifest b) {
        var sb = new StringBuilder();
        sb.append("=== Soul Comparison ===\n\n");
        sb.append("Soul A: ").append(a.did()).append(" v").append(a.manifestVersion()).append("\n");
        sb.append("Soul B: ").append(b.did()).append(" v").append(b.manifestVersion()).append("\n\n");

        sb.append("Forged: A=").append(a.forgedAt()).append(", B=").append(b.forgedAt()).append("\n");
        sb.append("Fragments: A=").append(a.soulFragments().size())
            .append(", B=").append(b.soulFragments().size()).append("\n");
        sb.append("Memories: A=").append(a.memory().nodes().size())
            .append(", B=").append(b.memory().nodes().size()).append("\n");
        sb.append("Relationships: A=").append(a.relationships().size())
            .append(", B=").append(b.relationships().size()).append("\n");
        sb.append("Formative: A=").append(a.formativeMemoryCount())
            .append(", B=").append(b.formativeMemoryCount()).append("\n");

        if (a.genome() != null && b.genome() != null) {
            sb.append("Genome: A=").append(a.genome().name())
                .append(", B=").append(b.genome().name()).append("\n");
        }

        sb.append("Content hash match: ").append(a.contentHash().equals(b.contentHash()) ? "YES" : "NO");
        return sb.toString();
    }
}
