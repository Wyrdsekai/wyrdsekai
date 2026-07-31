package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.library.OutputSanitizer;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.safety.McpAuditLog;
import org.wyrdsekai.core.skill.SkillRegistry;
import org.wyrdsekai.core.skill.WorkbenchSkillExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;

/**
 * A world where the preconditions actually hold.
 *
 * <p>Why this exists. The verb battery ran against an empty world, so ~20 of 31
 * verbs answered with an honest refusal: "I don't have anything called X to give",
 * "I can't imprint myself without an identity", "I need to be at the Workshop's
 * workbench". Those answers prove the REPORTING is truthful. They prove nothing
 * about whether the verb works, because the verb never got to run — and a suite
 * that only ever sees refusals cannot tell a working handler from a broken one.
 *
 * <p>Each field below unblocks a named cluster, and every one was chosen from an
 * observed refusal rather than guessed:</p>
 * <ul>
 *   <li><b>did</b> — {@code AgentProfile}'s 8-arg constructor leaves it null
 *       ({@code did=null} in the battery logs), which is why craft_summon_key,
 *       create_imprint and restore_imprint all said "I need an identity".</li>
 *   <li><b>inventory row</b> — give_item / place_item / consume / equip / doff /
 *       trade all need something to act on.</li>
 *   <li><b>bond row</b> — complete_mourning and declare_severance need a bond to
 *       mourn or sever ("I have no bond record with …").</li>
 *   <li><b>familyLocker</b> — give_copy said "no locker is attached"; forms live
 *       here, so summon_familiar / shape_form / revise_form need it too.</li>
 *   <li><b>workbenchExecutor + workshopReachable</b> — revise_form wanted to "be
 *       at the Workshop's workbench".</li>
 *   <li><b>skillRegistry</b> — delegate_chain said "without any skill tools".</li>
 *   <li><b>userScriptsDir</b> — add_script said "I can't modify room scripts
 *       right now".</li>
 * </ul>
 */
final class PopulatedWorld {

    /**
     * Per-instance DID. It MUST be unique per test.
     *
     * <p>A shared DID made all 31 tests contend for one companion's bunshin budget
     * in the global BunshinScheduler: 27 of 31 came back "dispatch refused …
     * elastic ceiling 3 reached", and every one reported as "the verb never came
     * back" — a resource-contention failure wearing a product failure's clothes.
     * Each test is conceptually a different companion, so each gets its own
     * identity.</p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
        new java.util.concurrent.atomic.AtomicInteger();
    static final String PEER_DID  = "did:wyrd:test:peer";
    final String agentDid;
    static final String PLAYER    = "player-steward";
    static final String HELD_ITEM = "test-lantern";

    final String jdbc;
    final AgentProfile profile;
    final CompanionCapabilities capabilities;
    final Path userScriptsDir;
    final InventoryService inventory;
    final TestSoulStore soulStore;

    private PopulatedWorld(String agentDid, String jdbc, AgentProfile profile,
                           CompanionCapabilities capabilities,
                           Path userScriptsDir, InventoryService inventory,
                           TestSoulStore soulStore) {
        this.agentDid = agentDid;
        this.jdbc = jdbc;
        this.profile = profile;
        this.capabilities = capabilities;
        this.userScriptsDir = userScriptsDir;
        this.inventory = inventory;
        this.soulStore = soulStore;
    }

    static PopulatedWorld create(Path tmp) throws Exception {
        var n = SEQ.incrementAndGet();
        var agentDid = "did:wyrd:test:companion-" + n;
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        // Services the actor builds internally find the DB through this property,
        // the same way CraftHandoffLiveE2ETest wires a real InventoryService.
        System.setProperty("wyrdsekai.jdbc.url", jdbc);

        // ── identity: the 10-arg constructor, so did() is not null ──
        var profile = new AgentProfile(
            "mia", "agent-mia-" + n, "agent", "A companion in Wyrdsekai",
            "You are mia, a companion guide in Wyrdsekai.",
            4096, 256, 0.7, agentDid, "companion");

        // ── something to hold, so the inventory verbs have a subject ──
        var inventory = new InventoryService(jdbc);
        inventory.addItem(profile.entityId(), "itm-test-lantern", HELD_ITEM,
            "A small brass lantern, warm to the touch.", true, null);

        // ── a bond, so the repair/severance verbs have a relationship ──
        seedBond(jdbc, agentDid);

        var lockerAddr = tmp.resolve("locker").toString();
        var locker = new FamilyLocker("test-family", lockerAddr);
        // AUTHORIZE the agent, or every locker-backed verb fails the same way:
        // craft_item's freeform path reported "couldn't store it", give_copy and
        // summon_familiar both "DID … not found". One root cause, three symptoms —
        // FamilyLocker.requireAuthorized throws SecurityException for an unknown
        // DID, and constructing the locker does not enrol anyone in it.
        locker.authorize(org.wyrdsekai.core.soul.SoulBud.original(
            agentDid, "z6MkTestPublicKeyMultibaseValue", "test-family",
            lockerAddr, "test-node", "test-model"));
        // code_mode: run_script's own gate. CodeModeFeatureFlag.resolveBool reads
        // System.getProperty BEFORE the env, so a test can enable it in-process.
        System.setProperty("WYRDSEKAI_CODE_MODE_ENABLED", "true");

        var skills = new SkillRegistry(
            new OutputSanitizer(null, OutputSanitizer.SanitizationMode.LOG_ONLY),
            new McpAuditLog());
        var workbench = new WorkbenchSkillExecutor(locker, agentDid);
        // A registered skill, so skill_execute has something to execute rather
        // than answering "Skill not available".
        skills.registerExecutor(new TestSkill());
        // A registered skill is not a permitted one: SkillRegistry.execute denies
        // by default, which surfaced as "Permission denied for skill: battery"
        // once the skill existed. Grant it, or skill_execute can only ever refuse.
        skills.setPermissions(agentDid,
            org.wyrdsekai.core.skill.SkillPermission.allowAll());

        // consume/equip look for SoulItems of category "reagent"/"aspect" in the
        // LOCKER — the seeded inventory row is a different store entirely, which is
        // why they said "I don't have a reagent called 'test-lantern'". Categories
        // come from StarterKitProvisioner.
        locker.store(org.wyrdsekai.core.soul.SoulItem.create(
            "reagent", "test-draught", "A restoring draught for the verb battery.",
            agentDid, 0.5, "seeded"), agentDid);
        locker.store(org.wyrdsekai.core.soul.SoulItem.create(
            "aspect", "test-aspect", "A wearable aspect for the verb battery.",
            agentDid, 0.5, "seeded"), agentDid);

        // A THOUGHT FORM. Three verbs answered "I don't have a form called …" —
        // give_copy, summon_familiar, revise_form. An honest refusal proves the
        // handler ran and then stops being informative; the point of a populated
        // world is to hand it the thing so the verb actually executes.
        var form = org.wyrdsekai.core.familiar.ThoughtForm.author(
            agentDid, "battery", "You are a test form for the verb battery.",
            java.util.Set.of("recall", "examine"), "did it answer the question");
        locker.shapeThoughtForm(form, agentDid);

        var scripts = tmp.resolve("scripts");
        Files.createDirectories(scripts);

        var caps = new CompanionCapabilities(
            locker, null, workbench, skills,
            false, 0, "test-zone",
            true,                  // workshopReachable — unblocks the form verbs
            null, null, null);

        // A forged manifest, so the imprint verbs have a soul to work from.
        var soulStore = new TestSoulStore();
        soulStore.store(org.wyrdsekai.core.soul.SoulManifest.birth(
            agentDid, "z6MkTestPublicKeyMultibaseValue", java.util.List.of(),
            profile, null));

        // An EXISTING imprint, so restore_imprint has something to restore to.
        // Each test method gets a fresh companion, so the imprint create_imprint
        // makes in its own test is not visible here — the fixture must supply one.
        try {
            new org.wyrdsekai.core.familiar.ImprintManager(agentDid).imprint(
                org.wyrdsekai.core.familiar.Imprint.CreatedBy.SELF,
                "battery", soulStore.latest(agentDid).orElseThrow());
        } catch (RuntimeException e) {
            // Non-fatal: restore_imprint then reports honestly instead.
        }

        return new PopulatedWorld(agentDid, jdbc, profile, caps, scripts, inventory,
            soulStore);
    }

    /**
     * In-memory SoulStore holding one forged manifest.
     *
     * <p>create_imprint / restore_imprint refused with "my manifest hasn't been
     * forged" because {@code cachedManifest} is populated from
     * {@code soulStore.latest(did)} at boot and the battery passed a null store. I
     * recorded that as "needs a forged manifest — a genuinely larger fixture".
     * SoulStore is an INTERFACE whose methods are all trivial in memory, so the
     * fixture was seven one-line methods. "Bigger than it's worth" was a guess,
     * not a measurement.</p>
     */
    static final class TestSoulStore implements org.wyrdsekai.core.soul.SoulStore {
        private final java.util.Map<String, org.wyrdsekai.core.soul.SoulManifest> byDid =
            new java.util.concurrent.ConcurrentHashMap<>();
        @Override public void store(org.wyrdsekai.core.soul.SoulManifest m) {
            byDid.put(m.did(), m);
        }
        @Override public java.util.Optional<org.wyrdsekai.core.soul.SoulManifest>
                load(String did, int version) { return latest(did); }
        @Override public java.util.Optional<org.wyrdsekai.core.soul.SoulManifest>
                latest(String did) { return java.util.Optional.ofNullable(byDid.get(did)); }
        @Override public java.util.List<org.wyrdsekai.core.soul.SoulManifest>
                history(String did) {
            return latest(did).map(java.util.List::of).orElseGet(java.util.List::of);
        }
        @Override public void archive(String did, String reason) { byDid.remove(did); }
        @Override public boolean exists(String did) { return byDid.containsKey(did); }
        @Override public int count() { return byDid.size(); }
    }

    /** The minimum real executor: one skill that exists and returns a result. */
    static final class TestSkill implements org.wyrdsekai.core.skill.SkillExecutor {
        static final String ID = "battery";
        /** Set when the registry actually invoked us — the difference between
         *  "Done — battery skill ran" being a sentence and being a fact. */
        static final java.util.concurrent.atomic.AtomicInteger INVOCATIONS =
            new java.util.concurrent.atomic.AtomicInteger();
        @Override public org.wyrdsekai.core.skill.SkillResult execute(
                String skillId, java.util.Map<String, Object> params,
                org.wyrdsekai.core.skill.SkillContext context) {
            INVOCATIONS.incrementAndGet();
            return org.wyrdsekai.core.skill.SkillResult.ok(
                "battery skill ran", java.util.Map.of("ran", true), 1L,
                org.wyrdsekai.core.skill.SkillTier.NATIVE, ID);
        }
        @Override public java.util.List<org.wyrdsekai.core.skill.SkillDefinition>
                availableSkills() {
            return java.util.List.of(new org.wyrdsekai.core.skill.SkillDefinition(
                ID, ID, "A skill that exists, for the verb battery", null,
                org.wyrdsekai.core.skill.SkillTier.NATIVE, "test", "Apache-2.0",
                java.util.List.of(), null, null, true));
        }
        @Override public boolean supports(String skillId) { return ID.equals(skillId); }
        @Override public org.wyrdsekai.core.skill.SkillTier tier() {
            return org.wyrdsekai.core.skill.SkillTier.NATIVE;
        }
    }

    /** Direct insert: no public API mints a bond without a full ritual. */
    private static void seedBond(String jdbc, String agentDid) throws Exception {
        try (var conn = DriverManager.getConnection(jdbc)) {
            var now = Instant.now().getEpochSecond();
            try (var ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO bonds(bond_id, agent_a_did, agent_b_did, "
                    + "depth, formed_at, last_interaction, interaction_count) "
                    + "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, "bond-" + agentDid);
                ps.setString(2, agentDid);
                ps.setString(3, PEER_DID);
                ps.setString(4, "ITEM");
                ps.setLong(5, now - 3600);
                ps.setLong(6, now);
                ps.setInt(7, 5);
                ps.executeUpdate();
            }
        }
    }
}
