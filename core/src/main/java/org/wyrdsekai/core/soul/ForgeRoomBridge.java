package org.wyrdsekai.core.soul;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.Companions;
import org.wyrdsekai.core.agent.CompanionSpawner;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Backs the soul rooms' spoken verbs with the real machinery — room scripts
 * (The Forge, companion Homes, the Soul Mirror) narrate flavor and emit
 * {@code command} events; this bridge is their consumer (RoomActor routes
 * them here). Before it existed those rooms were pure theater: every verb
 * ended in a debug log.
 *
 * <p>Forge verbs:</p>
 * <ul>
 *   <li>{@code inspect [name]} — latest {@link SoulManifest} (no target →
 *       roster of every soul in the zone)</li>
 *   <li>{@code history <name>} — manifest version history</li>
 *   <li>{@code forge_status} — live {@link ForgeState} counters + store count</li>
 *   <li>{@code forge [name]} — {@link CompanionActor.ForceSleep} NORMAL:
 *       the real sleep→consolidate→forge-manifest cycle</li>
 *   <li>{@code grow <name>} — ForceSleep DEEP: the welfare-gated growth
 *       cycle (variant growth + voice alignment), the same path the
 *       companion's own deep sleep takes</li>
 *   <li>{@code compare <a> <b>} — {@link ForgeCommand.Compare} via the
 *       ForgeActor's real comparison</li>
 *   <li>{@code restore <name> v<N>} — two-step ceremony (speak it, then
 *       {@code confirm restore <name> v<N>} within 90s). Restores the
 *       soul's SHAPE — profile, genome, voice — from version N as a NEW
 *       latest version; lived memory (fragments are unversioned) remains.
 *       A live companion adopts the restored shape immediately via
 *       {@link CompanionActor.AdoptStoredSoulShape}. Steward-only.</li>
 *   <li>{@code birth <name>} — steward-only spawn of a new free-sampled
 *       particular via {@link CompanionSpawner}</li>
 * </ul>
 *
 * <p>Home / Soul Mirror verbs (target resolved from the room when blank):</p>
 * <ul>
 *   <li>{@code home_sleep} — owner companion's ForceSleep NORMAL</li>
 *   <li>{@code home_dreams} — recent EPISODIC fragments (inner monologue)</li>
 *   <li>{@code home_fragments} — soul-fragment counts by category</li>
 *   <li>{@code mirror_check} — introspective manifest summary</li>
 *   <li>{@code examine_drift} — growth/forge event record from ForgeState</li>
 * </ul>
 *
 * <p>Narration is localized via {@link ScriptMessageCatalog} keyed under
 * {@code forge.bridge.*} (en/es/ja), using the I18n thread-local locale set
 * by the room's command processing. Reads are bounded local SQLite queries;
 * actor asks are capped at 2s — anything slower degrades to an honest
 * "instruments unresponsive" line rather than blocking the room.</p>
 */
public final class ForgeRoomBridge {

    private static final Logger log = LoggerFactory.getLogger(ForgeRoomBridge.class);
    private static final Set<String> VERBS = Set.of(
        "forge", "inspect", "history", "forge_status", "birth",
        "grow", "compare", "restore",
        "home_sleep", "home_dreams", "home_fragments",
        "mirror_check", "examine_drift");

    /** restore ceremony: pending confirmation per asking entity, 90s window. */
    private record PendingRestore(String did, String name, int version, long deadlineMs) {}
    private static final Map<String, PendingRestore> pendingRestores = new ConcurrentHashMap<>();
    private static final long RESTORE_CONFIRM_WINDOW_MS = 90_000;

    private static volatile ActorRef<ForgeCommand> forgeActor;
    private static volatile ActorSystem<?> system;
    private static volatile AuthService authService;

    private ForgeRoomBridge() {}

    public static void init(ActorRef<ForgeCommand> forge,
                            ActorSystem<?> sys,
                            AuthService auth) {
        forgeActor = forge;
        system = sys;
        authService = auth;
    }

    public static boolean canHandle(String verb) {
        return verb != null && VERBS.contains(verb);
    }

    /** Execute a soul-room verb; returns the narration lines for the room. */
    public static List<String> handle(String verb, String target,
                                      String actorId, String roomId) {
        try {
            return switch (verb) {
                case "inspect" -> inspect(target);
                case "history" -> history(target);
                case "forge_status" -> status();
                case "forge" -> sleepCycle(target, CompanionActor.SleepTier.NORMAL,
                    "forge.bridge.forge_begun");
                case "grow" -> sleepCycle(target, CompanionActor.SleepTier.DEEP,
                    "forge.bridge.grow_begun");
                case "compare" -> compare(target);
                case "restore" -> restore(target, actorId);
                case "birth" -> birth(target, actorId);
                case "home_sleep" -> homeSleep(roomId);
                case "home_dreams" -> homeDreams(roomId);
                case "home_fragments" -> homeFragments(roomId);
                case "mirror_check" -> mirrorCheck(target, roomId);
                case "examine_drift" -> examineDrift(target, roomId);
                default -> List.of();
            };
        } catch (Exception e) {
            log.warn("ForgeRoomBridge {} '{}': {}", verb, target, e.getMessage());
            return List.of(t("forge.bridge.error_dark", e.getMessage()));
        }
    }

    // ─── inspect ────────────────────────────────────────────────────

    private static List<String> inspect(String target) {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        try (var store = new SqlSoulStore(jdbcUrl)) {
            if (isBlankTarget(target)) {
                var lines = new ArrayList<String>();
                var souls = store.listLatest();
                if (souls.isEmpty()) {
                    return List.of(t("forge.bridge.roster_empty"));
                }
                lines.add(t("forge.bridge.roster_head", souls.size()));
                for (var m : souls) {
                    var p = m.profile();
                    lines.add(t("forge.bridge.roster_entry",
                        p != null ? p.name() : "?",
                        "v" + m.manifestVersion()
                            + (m.forgedAt() != null ? " · " + m.forgedAt() : ""),
                        shortDid(m.did())));
                }
                lines.add(t("forge.bridge.roster_hint"));
                return lines;
            }
            var manifest = resolve(store, target);
            if (manifest == null) {
                return List.of(t("forge.bridge.not_found", target));
            }
            return renderManifest(manifest, "forge.bridge.inspect_head");
        }
    }

    private static List<String> renderManifest(SoulManifest manifest, String headKey) {
        var p = manifest.profile();
        var lines = new ArrayList<String>();
        lines.add(t(headKey, p != null ? p.name() : manifest.did()));
        lines.add(t("forge.bridge.field_identity", manifest.did()));
        lines.add(t("forge.bridge.field_version", manifest.manifestVersion(),
            manifest.forgedAt() != null ? "  (" + manifest.forgedAt() + ")" : ""));
        if (manifest.genome() != null) {
            lines.add(t("forge.bridge.field_temperament",
                GenomeProfile.temperamentOf(manifest.genome()).label()));
        }
        if (manifest.relationships() != null) {
            lines.add(t("forge.bridge.field_relationships",
                manifest.relationships().size()));
        }
        if (manifest.voiceProfile() != null) {
            lines.add(t("forge.bridge.field_voice",
                manifest.voiceProfile().revision()));
        }
        return lines;
    }

    // ─── history ────────────────────────────────────────────────────

    private static List<String> history(String target) {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        try (var store = new SqlSoulStore(jdbcUrl)) {
            if (isBlankTarget(target)) {
                return List.of(t("forge.bridge.history_usage"));
            }
            var manifest = resolve(store, target);
            if (manifest == null) {
                return List.of(t("forge.bridge.not_found", target));
            }
            var name = manifest.profile() != null ? manifest.profile().name() : target;
            var versions = store.history(manifest.did());
            var lines = new ArrayList<String>();
            lines.add(t("forge.bridge.history_head", name, versions.size()));
            for (var v : versions) {
                lines.add(t("forge.bridge.history_entry", v.manifestVersion(),
                    v.forgedAt() != null ? "  —  " + v.forgedAt() : ""));
            }
            lines.add(t("forge.bridge.history_restore_hint",
                name.toLowerCase(Locale.ROOT)));
            return lines;
        }
    }

    // ─── forge_status ───────────────────────────────────────────────

    private static List<String> status() {
        var lines = new ArrayList<String>();
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl != null) {
            try (var store = new SqlSoulStore(jdbcUrl)) {
                lines.add(t("forge.bridge.status_manifests",
                    store.count(), store.listLatest().size()));
            }
        }
        var state = askForgeState();
        if (state != null) {
            lines.add(t("forge.bridge.status_fire",
                state.totalForges(), state.totalRestores(),
                state.knownSouls().size()));
        } else if (forgeActor != null) {
            lines.add(t("forge.bridge.status_unresponsive"));
        }
        if (lines.isEmpty()) {
            lines.add(t("forge.bridge.status_cold"));
        }
        return lines;
    }

    private static ForgeState askForgeState() {
        var forge = forgeActor;
        var sys = system;
        if (forge == null || sys == null) return null;
        try {
            return AskPattern.<ForgeCommand, ForgeState>ask(
                    forge, replyTo -> new ForgeCommand.GetState(replyTo),
                    Duration.ofSeconds(2), sys.scheduler())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── forge / grow — the real sleep cycles ───────────────────────

    private static List<String> sleepCycle(String target,
                                           CompanionActor.SleepTier tier,
                                           String narrationKey) {
        var entityId = resolveAgentEntity(target);
        if (entityId == null) {
            return List.of(isBlankTarget(target)
                ? t("forge.bridge.sleep_no_target")
                : t("forge.bridge.sleep_not_present", target));
        }
        var ref = ZoneGuardian.getCompanionRef(null, entityId);
        if (ref == null) {
            return List.of(t("forge.bridge.sleep_no_hold", entityId));
        }
        var name = EntityRegistry.get() != null
            ? EntityRegistry.get().nameOf(entityId).orElse(entityId) : entityId;
        ref.tell(new CompanionActor.ForceSleep(tier));
        return List.of(t(narrationKey, name, name.toLowerCase(Locale.ROOT)));
    }

    // ─── compare — ForgeActor's real soul comparison ─────────────────

    private static List<String> compare(String args) {
        var parts = (args == null ? "" : args.trim()).split("\\s+");
        if (parts.length < 2 || parts[0].isBlank()) {
            return List.of(t("forge.bridge.compare_usage"));
        }
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        String did1, did2, n1, n2;
        try (var store = new SqlSoulStore(jdbcUrl)) {
            var m1 = resolve(store, parts[0]);
            var m2 = resolve(store, parts[1]);
            if (m1 == null || m2 == null) {
                return List.of(t("forge.bridge.not_found",
                    m1 == null ? parts[0] : parts[1]));
            }
            did1 = m1.did(); did2 = m2.did();
            n1 = m1.profile() != null ? m1.profile().name() : parts[0];
            n2 = m2.profile() != null ? m2.profile().name() : parts[1];
        }
        var forge = forgeActor;
        var sys = system;
        if (forge == null || sys == null) {
            return List.of(t("forge.bridge.compare_dark"));
        }
        try {
            var result = AskPattern.<ForgeCommand, ForgeCommand.ForgeResult>ask(
                    forge, replyTo -> new ForgeCommand.Compare(did1, did2, replyTo),
                    Duration.ofSeconds(2), sys.scheduler())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            return switch (result) {
                case ForgeCommand.ForgeResult.ComparisonResult cr ->
                    List.of(t("forge.bridge.compare_head", n1, n2), "  " + cr.summary());
                case ForgeCommand.ForgeResult.Error err ->
                    List.of(t("forge.bridge.compare_error", err.message()));
                default -> List.of(t("forge.bridge.compare_illegible"));
            };
        } catch (Exception e) {
            return List.of(t("forge.bridge.compare_unresponsive"));
        }
    }

    // ─── restore — two-step shape restoration ───────────────────────

    private static List<String> restore(String args, String actorId) {
        if (!isSteward(actorId)) {
            return List.of(t("forge.bridge.restore_steward_only"));
        }
        var text = args == null ? "" : args.trim();
        boolean confirming = text.toLowerCase(Locale.ROOT).startsWith("confirm");
        if (confirming) text = text.substring("confirm".length()).trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("restore ")) {
            text = text.substring(8).trim();
        }
        var parts = text.split("\\s+");
        if (parts.length < 2 || parts[0].isBlank()) {
            return List.of(t("forge.bridge.restore_usage"));
        }
        var versionStr = parts[1].toLowerCase(Locale.ROOT).startsWith("v")
            ? parts[1].substring(1) : parts[1];
        int version;
        try {
            version = Integer.parseInt(versionStr);
        } catch (NumberFormatException e) {
            return List.of(t("forge.bridge.restore_bad_version", parts[1]));
        }
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        try (var store = new SqlSoulStore(jdbcUrl)) {
            var latest = resolve(store, parts[0]);
            if (latest == null) {
                return List.of(t("forge.bridge.not_found", parts[0]));
            }
            var name = latest.profile() != null ? latest.profile().name() : parts[0];
            var lower = name.toLowerCase(Locale.ROOT);
            var old = store.load(latest.did(), version);
            if (old.isEmpty()) {
                return List.of(t("forge.bridge.restore_no_version", version, name, lower));
            }
            if (!confirming) {
                pendingRestores.put(actorId, new PendingRestore(
                    latest.did(), name, version,
                    System.currentTimeMillis() + RESTORE_CONFIRM_WINDOW_MS));
                return List.of(
                    t("forge.bridge.restore_challenge_1", version, name,
                        old.get().forgedAt() != null ? String.valueOf(old.get().forgedAt()) : "?"),
                    t("forge.bridge.restore_challenge_2", latest.manifestVersion()),
                    t("forge.bridge.restore_challenge_3", lower, version));
            }
            // Confirmation path — must match a fresh pending challenge.
            var pending = pendingRestores.remove(actorId);
            if (pending == null || !pending.did().equals(latest.did())
                    || pending.version() != version
                    || System.currentTimeMillis() > pending.deadlineMs()) {
                return List.of(t("forge.bridge.restore_window_closed", lower, version));
            }
            var source = old.get();
            var merged = latest;
            if (source.profile() != null) merged = merged.withProfile(source.profile());
            if (source.genome() != null) merged = merged.withGenome(source.genome());
            if (source.voiceProfile() != null) merged = merged.withVoiceProfile(source.voiceProfile());
            merged = merged.withManifestVersion(latest.manifestVersion() + 1, Instant.now());
            store.store(merged);

            var lines = new ArrayList<String>();
            lines.add(t("forge.bridge.restore_done", name, version, merged.manifestVersion()));
            // A living companion adopts the restored shape immediately.
            var entityId = source.profile() != null ? source.profile().entityId() : null;
            var ref = entityId != null ? ZoneGuardian.getCompanionRef(null, entityId) : null;
            if (ref != null) {
                ref.tell(new CompanionActor.AdoptStoredSoulShape());
                lines.add(t("forge.bridge.restore_felt", name));
            } else {
                lines.add(t("forge.bridge.restore_next_wake", name));
            }
            log.info("Forge restore: {} shape v{} -> new v{} (by {})",
                name, version, merged.manifestVersion(), actorId);
            return lines;
        }
    }

    // ─── birth — steward-only real spawn ─────────────────────────────

    /**
     * Study-side structured entry (bond crystal {@code birth <name>}, 2026-07-18) —
     * the same steward gate, duplicate check, and spawn machinery as the Forge's
     * spoken verb, returning {@code ok}/{@code error} for scripted callers instead
     * of narration lines. One flow, two doors: the Forge remains the ceremonial
     * place; the Study remains the administrative one.
     */
    public static Map<String, Object> stewardBirth(String name, String actorId) {
        if (!isSteward(actorId)) {
            return Map.of("ok", false, "error", t("forge.bridge.birth_steward_only"));
        }
        if (isBlankTarget(name)) {
            return Map.of("ok", false, "error", t("forge.bridge.birth_needs_name"));
        }
        var profile = Companions.additionalCompanion(name);
        if (alreadyExists(profile)) {
            return Map.of("ok", false, "error",
                t("forge.bridge.birth_duplicate", profile.name()));
        }
        if (!CompanionSpawner.spawn(profile)) {
            return Map.of("ok", false, "error", t("forge.bridge.birth_falter"));
        }
        return Map.of("ok", true, "name", profile.name(),
            "summary", t("forge.bridge.birth_done", profile.name()));
    }

    private static List<String> birth(String name, String actorId) {
        if (!isSteward(actorId)) {
            return List.of(t("forge.bridge.birth_steward_only"));
        }
        if (isBlankTarget(name)) {
            return List.of(t("forge.bridge.birth_needs_name"));
        }
        var profile = Companions.additionalCompanion(name);
        if (alreadyExists(profile)) {
            return List.of(t("forge.bridge.birth_duplicate", profile.name()));
        }
        if (!CompanionSpawner.spawn(profile)) {
            return List.of(t("forge.bridge.birth_falter"));
        }
        return List.of(t("forge.bridge.birth_done", profile.name()));
    }

    // ─── Home verbs (owner derived from the home room id) ───────────

    private static List<String> homeSleep(String roomId) {
        var owner = homeOwner(roomId);
        if (owner == null) return List.of(t("forge.bridge.home_not_home"));
        var ref = ZoneGuardian.getCompanionRef(null, owner);
        if (ref == null) {
            return List.of(t("forge.bridge.home_owner_away"));
        }
        var name = EntityRegistry.get() != null
            ? EntityRegistry.get().nameOf(owner).orElse(owner) : owner;
        ref.tell(new CompanionActor.ForceSleep(CompanionActor.SleepTier.NORMAL));
        return List.of(t("forge.bridge.home_sleep_begun", name));
    }

    private static List<String> homeDreams(String roomId) {
        var did = homeOwnerDid(roomId);
        if (did == null) return List.of(t("forge.bridge.home_not_home"));
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        var fragments = new SoulFragmentStore(jdbcUrl)
            .loadByKind(did, FragmentKind.EPISODIC);
        if (fragments.isEmpty()) {
            return List.of(t("forge.bridge.dreams_empty"));
        }
        var lines = new ArrayList<String>();
        lines.add(t("forge.bridge.dreams_head"));
        int from = Math.max(0, fragments.size() - 3);
        for (var f : fragments.subList(from, fragments.size())) {
            var text = f.text() == null ? "" : f.text();
            lines.add("  …" + (text.length() > 160 ? text.substring(0, 160) + "…" : text));
        }
        return lines;
    }

    private static List<String> homeFragments(String roomId) {
        var did = homeOwnerDid(roomId);
        if (did == null) return List.of(t("forge.bridge.home_not_home"));
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        var all = new SoulFragmentStore(jdbcUrl).loadAll(did);
        if (all.isEmpty()) {
            return List.of(t("forge.bridge.fragments_empty"));
        }
        var byCategory = new TreeMap<String, Integer>();
        for (var f : all) {
            byCategory.merge(f.category() == null ? "?" : f.category(), 1, Integer::sum);
        }
        var lines = new ArrayList<String>();
        lines.add(t("forge.bridge.fragments_head", all.size()));
        for (var e : byCategory.entrySet()) {
            lines.add("  " + e.getKey() + ": " + e.getValue());
        }
        return lines;
    }

    // ─── Soul Mirror verbs ──────────────────────────────────────────

    private static List<String> mirrorCheck(String target, String roomId) {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        try (var store = new SqlSoulStore(jdbcUrl)) {
            var manifest = resolveForRoom(store, target, roomId);
            if (manifest == null) {
                return List.of(t("forge.bridge.mirror_no_subject"));
            }
            var lines = renderManifest(manifest, "forge.bridge.mirror_head");
            if (manifest.did() != null) {
                lines.add(t("forge.bridge.field_fragments",
                    new SoulFragmentStore(jdbcUrl).loadAll(manifest.did()).size()));
            }
            return lines;
        }
    }

    private static List<String> examineDrift(String target, String roomId) {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of(t("forge.bridge.sealed_store"));
        SoulManifest manifest;
        try (var store = new SqlSoulStore(jdbcUrl)) {
            manifest = resolveForRoom(store, target, roomId);
            if (manifest == null) {
                return List.of(t("forge.bridge.drift_no_subject"));
            }
        }
        var name = manifest.profile() != null ? manifest.profile().name() : manifest.did();
        var lines = new ArrayList<String>();
        lines.add(t("forge.bridge.drift_versions", name, manifest.manifestVersion()));
        var state = askForgeState();
        if (state != null) {
            var growth = state.growthHistory().get(manifest.did());
            lines.add(t("forge.bridge.drift_events",
                state.eventsForDid(manifest.did()).size(),
                growth != null ? growth.size() : 0));
        }
        return lines;
    }

    // ─── helpers ─────────────────────────────────────────────────────

    /** Localized message via the room-script catalog (scripts/i18n/*.json),
     *  using the I18n thread-local locale the room set for this command. */
    private static String t(String key, Object... args) {
        var catalog = ScriptMessageCatalog.forLang(I18n.getLocale().getLanguage());
        return args.length == 0 ? catalog.get(key) : catalog.get(key, args);
    }

    private static boolean isSteward(String actorId) {
        var auth = authService;
        return auth != null && actorId != null && auth.findUser(actorId)
            .map(u -> "steward".equals(u.role())).orElse(false);
    }

    private static boolean isBlankTarget(String target) {
        return target == null || target.isBlank() || target.startsWith("<");
    }

    /** Owner entityId of a companion Home room ({@code home-companion-mia}). */
    private static String homeOwner(String roomId) {
        if (roomId == null || !roomId.startsWith("home-")) return null;
        return roomId.substring("home-".length());
    }

    private static String homeOwnerDid(String roomId) {
        var entityId = homeOwner(roomId);
        if (entityId == null) return null;
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return null;
        try (var store = new SqlSoulStore(jdbcUrl)) {
            var m = resolve(store, entityId);
            return m != null ? m.did() : null;
        }
    }

    /** Resolve a soul by spoken target; blank target falls back to the
     *  room's home owner, then to the sole live agent if unambiguous. */
    private static SoulManifest resolveForRoom(SqlSoulStore store,
                                               String target, String roomId) {
        if (!isBlankTarget(target)) return resolve(store, target);
        var owner = homeOwner(roomId);
        if (owner != null) return resolve(store, owner);
        var sole = resolveAgentEntity(null);
        return sole != null ? resolve(store, sole) : null;
    }

    /** Match a spoken target against soul names, entityIds, or DID prefixes. */
    private static SoulManifest resolve(SqlSoulStore store, String target) {
        var t = target.trim();
        for (var m : store.listLatest()) {
            var p = m.profile();
            if (p == null) continue;
            if ((p.name() != null && p.name().equalsIgnoreCase(t))
                    || t.equalsIgnoreCase(p.entityId())
                    || (m.did() != null && m.did().startsWith(t))) {
                return m;
            }
        }
        return null;
    }

    /** Resolve a live agent entityId by name; blank target → sole agent if unambiguous. */
    /**
     * Resolve a companion name (or entity id) to its entity id, or null when no such
     * agent is present in this zone. Blank target resolves to the sole agent when
     * there is exactly one.
     *
     * <p>Public so the steward HTTP route shares this resolver rather than carrying a
     * second copy: the in-world verb and the operator call must agree on what "mia"
     * means, and two resolvers would eventually disagree.
     */
    public static String resolveCompanionEntity(String target) {
        return resolveAgentEntity(target);
    }

    private static String resolveAgentEntity(String target) {
        var registry = EntityRegistry.get();
        if (registry == null) return null;
        String sole = null;
        int agents = 0;
        for (var entityId : registry.allEntities()) {
            if (!registry.isAgent(entityId)) continue;
            agents++;
            sole = entityId;
            var name = registry.nameOf(entityId).orElse("");
            if (target != null && !target.isBlank()
                    && (name.equalsIgnoreCase(target.trim())
                        || entityId.equalsIgnoreCase(target.trim()))) {
                return entityId;
            }
        }
        if ((target == null || target.isBlank()) && agents == 1) return sole;
        return null;
    }

    private static boolean alreadyExists(AgentProfile profile) {
        var registry = EntityRegistry.get();
        if (registry != null) {
            for (var entityId : registry.allEntities()) {
                if (entityId.equalsIgnoreCase(profile.entityId())) return true;
            }
        }
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl != null) {
            try (var store = new SqlSoulStore(jdbcUrl)) {
                for (var m : store.listLatest()) {
                    if (m.profile() != null
                            && profile.entityId().equalsIgnoreCase(m.profile().entityId())) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** DSN handed in at boot (Main), since the installed service publishes the
     *  resolved URL as the sysprop {@code wyrdsekai.jdbc.url} and never sets the
     *  {@code WYRDSEKAI_JDBC_URL} env that {@code WyrdConfig.jdbcUrl()} reads —
     *  the exact CompanionCodexView bug (2026-07-18). Without this the whole
     *  soul-forge room answered "sealed store" on every real install. */
    private static volatile String bootJdbcUrl;

    /** Server boot: hand the forge bridge the real DSN. */
    public static void setJdbcUrl(String url) {
        bootJdbcUrl = url;
    }

    private static String jdbcUrl() {
        if (bootJdbcUrl != null && !bootJdbcUrl.isBlank()) return bootJdbcUrl;
        // Fallbacks: the server sysprop (set by Main before the actor system
        // starts), then WyrdConfig (dev/test where the env is set).
        var sys = System.getProperty("wyrdsekai.jdbc.url");
        if (sys != null && !sys.isBlank()) return sys;
        var url = WyrdConfig.get().jdbcUrl();
        return url == null || url.isBlank() ? null : url;
    }

    private static String shortDid(String did) {
        if (did == null) return "?";
        return did.length() > 24 ? did.substring(0, 24) + "…" : did;
    }
}
