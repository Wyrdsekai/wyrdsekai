package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Auto-forges a companion from a seed description using the local LLM.
 *
 * Bridges the gap between a minimal seed JSON and a full SoulManifest:
 *   seed.json → LLM generation → Ed25519 identity → embed → manifest.json
 *
 * Used by both:
 *   - SoulForgeCliTool (CLI: wyrdsekai forge)
 *   - SoulSeedWatcher (hot directory: ~/.wyrdsekai/souls/incoming/)
 *   - The Forge room (in-world creation, future)
 *
 * <p> shadow: writes
 * {@code souls/&lt;name&gt;-soul-manifest.json} as a bootstrap convenience
 * (so a freshly forged soul has a single inspectable file). Canonical
 * persistence is {@link SqlSoulStore} — callers MUST also call
 * {@code SoulStore.store()} after forging. F7b Phase 3 will drop the
 * filesystem write entirely and the Forge room will become a thin
 * facade over the SQL store.</p>
 */
public class SoulAutoForge {

    private static final Logger log = LoggerFactory.getLogger(SoulAutoForge.class);
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String ollamaUrl;
    private final String model;
    private final String embeddingModel;
    private final Path outputDir;

    /**
     * Optional sink for the per-soul household secret (did → 32-byte secret).
     * When set, the secret that encrypts the soul's Ed25519 private key is
     * persisted (canonically to TheSafe) at forge instead of being discarded,
     * so the soul retains the capability to sign later (re-sign after growth,
     * sign buds). Without a sink the secret is lost after forge and the manifest
     * is signed-once-then-orphaned — verifiable, but not re-signable.
     */
    private BiConsumer<String, byte[]> secretSink;

    public SoulAutoForge(String ollamaUrl, String model, String embeddingModel, Path outputDir) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.outputDir = outputDir;
    }

    /** Wire a persistence sink for the household secret (see {@link #secretSink}). */
    public SoulAutoForge secretPersister(BiConsumer<String, byte[]> sink) {
        this.secretSink = sink;
        return this;
    }

    /**
     * Forge a companion from a seed. Blocks until complete.
     *
     * @param seed the companion description
     * @return path to the written manifest, or null on failure
     */
    public Path forge(SoulForgeCliTool.SoulSeed seed) {
        try {
            log.info("Auto-forging companion: {} ({})", seed.name(),
                seed.homeRoom() != null ? seed.homeRoom() : "nexus");

            var ollamaApiUrl = ollamaUrl.endsWith("/v1") ? ollamaUrl : ollamaUrl + "/v1";

            // Step 1: Generate soul content via LLM
            log.info("  Generating soul content via {}...", model);
            var content = SoulForgeCliTool.generateSoulContent(ollamaApiUrl, model, seed);

            // Step 2: Generate genome
            log.info("  Generating genome...");
            var genome = SoulForgeCliTool.generateGenome(ollamaApiUrl, model, seed);

            // Step 3: Generate identity.
            //
            // Prefer the real one. Until AgentIdentityStore existed, this minted a
            // keypair under a RANDOM per-soul secret, signed once, pushed the secret
            // to a TheSafe slot, and dropped the encrypted private key on the floor
            // — so the slot held a key that decrypted nothing, and the forged soul
            // was signed-once-then-orphaned. When provisioning is on, the identity
            // is persisted with its key and the secret is the household's, so the
            // soul can sign again later; the sink is then deliberately NOT called,
            // because writing the household secret into a per-DID slot would spread
            // it far beyond the one place it belongs.
            byte[] householdSecret;
            AgentIdentity identity;
            boolean identityPersisted = false;
            var provisioned = AgentIdentityProvisioner.isEnabled()
                ? AgentIdentityProvisioner.secret().orElse(null) : null;
            if (provisioned != null) {
                householdSecret = provisioned;
                identity = AgentIdentity.generate(householdSecret);
                identityPersisted = AgentIdentityProvisioner.record(identity, null);
            } else {
                householdSecret = new byte[32];
                SecureRandom.getInstanceStrong().nextBytes(householdSecret);
                identity = AgentIdentity.generate(householdSecret);
            }
            log.info("  DID: {} (key persisted={})", identity.did(), identityPersisted);

            // Step 4: Embed fragments
            log.info("  Embedding {} fragments...", content.fragments().size());
            var embedded = SoulForgeCliTool.embedFragments(
                content.fragments(), ollamaUrl, embeddingModel);

            // Step 5: Assemble manifest
            var profile = new AgentProfile(
                seed.name(),
                seed.name().toLowerCase().replaceAll("[^a-z0-9]", "-"),
                "agent",
                seed.description(),
                content.systemPrompt(),
                32768, 256,
                seed.temperature() > 0 ? seed.temperature() : 0.7,
                identity.did()
            );

            var manifest = SoulManifest.forge(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(),
                null, 1,
                profile, content.residentIdentity(),
                embedded, 3, content.residentIdentity(),
                genome, content.mirrorCalibration(),
                CompactedMemory.empty(),
                List.of(),
                List.of(),
                Map.of(
                    "origin", "Auto-forged from seed",
                    "substrate", model + " via Ollama",
                    "homeRoom", seed.homeRoom() != null ? seed.homeRoom() : "nexus"
                ),
                VitalitySnapshot.defaults(),
                BehavioralFingerprint.empty()
            );

            // Step 5b: Sign the manifest with the soul's OWN Ed25519 key.
            // Real signature over the canonical bytes (verifiable against the DID),
            // replacing the historical no-sign path that shipped orphaned manifests.
            try {
                String sigB64 = identity.sign(manifest.canonicalBytes(), householdSecret);
                manifest = manifest.signed(Base64.getDecoder().decode(sigB64));
                // Only the legacy path needs the sink: there, the secret is a random
                // per-soul one and losing it means losing the ability to re-sign.
                // With the identity persisted, the household secret is already where
                // it belongs and must NOT be copied into a per-DID slot.
                if (!identityPersisted && secretSink != null) {
                    secretSink.accept(identity.did(), householdSecret);
                }
                log.info("  Signed manifest (Ed25519 over canonical bytes); "
                        + "identity persisted={}, legacy secret slot written={}",
                    identityPersisted, !identityPersisted && secretSink != null);
            } catch (Exception signEx) {
                // Never fail the forge on a signing hiccup — an unsigned manifest is
                // handled by the load-time verify gate (flagged, not fatal).
                log.warn("  Manifest signing failed — writing unsigned (load-time verify will flag): {}",
                    signEx.getMessage());
            }

            // Step 6: Write manifest
            Files.createDirectories(outputDir);
            var manifestFile = outputDir.resolve(
                seed.name().toLowerCase() + "-soul-manifest.json");
            JSON.writerWithDefaultPrettyPrinter()
                .writeValue(manifestFile.toFile(), manifest);

            log.info("  Forged: {} → {}", seed.name(), manifestFile);
            log.info("  Hash: {}", manifest.contentHash());

            return manifestFile;

        } catch (Exception e) {
            log.error("Auto-forge failed for {}: {}", seed.name(), e.getMessage());
            return null;
        }
    }

    /**
     * Create a watcher callback that auto-forges and notifies.
     *
     * @param onForged called with (manifest path, seed) after successful forge
     */
    public BiConsumer<SoulForgeCliTool.SoulSeed, Path> watcherCallback(
            BiConsumer<Path, SoulForgeCliTool.SoulSeed> onForged) {
        return (seed, seedPath) -> {
            var manifestPath = forge(seed);
            if (manifestPath != null && onForged != null) {
                onForged.accept(manifestPath, seed);
            }
        };
    }
}
