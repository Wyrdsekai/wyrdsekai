package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;

import java.io.Console;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * CLI tool for forging companion souls.
 *
 * Usage: wyrdsekai forge [options]
 *
 * Interactive mode: prompts for name, description, personality.
 * Seed file mode: reads a minimal JSON seed and generates the full manifest.
 *
 * Uses the local LLM (Ollama) to generate:
 *   - Resident identity (~69 tokens)
 *   - Soul fragments (personality, values, style, formative memories)
 *   - Genome profile (12-tank baselines, sensitivity, coupling, decay)
 *   - Mirror calibration (5 emotional response examples)
 *
 * Output: a complete SoulManifest JSON in ~/.wyrdsekai/souls/
 */
public class SoulForgeCliTool {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "qwen2.5:7b";
    private static final String DEFAULT_EMBEDDING_MODEL = "all-minilm";

    public static void main(String[] args) throws Exception {
        String seedFile = null;
        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_URL",
            System.getenv().getOrDefault("WYRDSEKAI_OLLAMA_URL", DEFAULT_OLLAMA_URL));
        String model = System.getenv().getOrDefault("OLLAMA_MODEL",
            System.getenv().getOrDefault("WYRDSEKAI_MODEL", DEFAULT_MODEL));
        String embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL);
        String outputDir = System.getenv().getOrDefault("WYRDSEKAI_SOUL_DIR",
            Path.of(System.getProperty("user.home"), ".wyrdsekai", "souls").toString());

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed" -> { if (i + 1 < args.length) seedFile = args[++i]; }
                case "--ollama" -> { if (i + 1 < args.length) ollamaUrl = args[++i]; }
                case "--model" -> { if (i + 1 < args.length) model = args[++i]; }
                case "--output" -> { if (i + 1 < args.length) outputDir = args[++i]; }
                case "--help", "-h" -> { printHelp(); return; }
            }
        }

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════╗");
        System.out.println("  ║          Soul Forge                  ║");
        System.out.println("  ║   Create a companion for Wyrdsekai   ║");
        System.out.println("  ╚══════════════════════════════════════╝");
        System.out.println();

        // Load seed from file or interactive prompts
        SoulSeed seed;
        if (seedFile != null) {
            seed = JSON.readValue(Path.of(seedFile).toFile(), SoulSeed.class);
            System.out.println("Loaded seed: " + seed.name() + " — " + seed.description());
        } else {
            seed = interactivePrompt();
        }

        if (seed == null) {
            System.out.println("Cancelled.");
            return;
        }

        System.out.println();
        System.out.println("Forging " + seed.name() + "...");
        System.out.println("  LLM: " + ollamaUrl + " / " + model);
        System.out.println("  Embedding: " + embeddingModel);
        System.out.println();

        var ollamaApiUrl = ollamaUrl.endsWith("/v1") ? ollamaUrl
            : ollamaUrl + "/v1";

        // Step 1: Generate soul content via LLM
        System.out.println("Generating soul content...");
        var soulContent = generateSoulContent(ollamaApiUrl, model, seed);
        System.out.println("  Resident identity: " + soulContent.residentIdentity().length() + " chars");
        System.out.println("  Fragments: " + soulContent.fragments().size());
        System.out.println("  Calibration examples: " + soulContent.mirrorCalibration().size());

        // Step 2: Generate genome from personality description
        System.out.println("Generating genome...");
        var genome = generateGenome(ollamaApiUrl, model, seed);
        System.out.println("  Profile: " + genome.name());

        // Step 3: Generate Ed25519 identity
        System.out.println("Generating cryptographic identity...");
        var householdSecret = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(householdSecret);
        var identity = AgentIdentity.generate(householdSecret);
        System.out.println("  DID: " + identity.did());

        // Step 4: Embed fragments
        System.out.println("Embedding " + soulContent.fragments().size() + " fragments...");
        var embeddedFragments = embedFragments(
            soulContent.fragments(), ollamaUrl, embeddingModel);
        System.out.println("  Embedded: " + embeddedFragments.size());

        // Step 5: Assemble manifest
        var profile = new AgentProfile(
            seed.name(),
            seed.name().toLowerCase().replaceAll("[^a-z0-9]", "-"),
            "agent",
            seed.description(),
            soulContent.systemPrompt(),
            32768, 256, seed.temperature() > 0 ? seed.temperature() : 0.7,
            identity.did()
        );

        var manifest = SoulManifest.forge(
            identity.did(),
            identity.did().substring("did:key:".length()),
            identity.keyLog(),
            null, 1,
            profile, soulContent.residentIdentity(),
            embeddedFragments, 3, soulContent.residentIdentity(),
            genome, soulContent.mirrorCalibration(),
            CompactedMemory.empty(),
            List.of(),
            List.of(),
            Map.of(
                "origin", "Forged via CLI soul forge",
                "substrate", model + " via Ollama",
                "homeRoom", seed.homeRoom() != null ? seed.homeRoom() : "nexus"
            ),
            VitalitySnapshot.defaults(),
            BehavioralFingerprint.empty()
        );

        // Step 6: Write manifest
        var outPath = Path.of(outputDir);
        Files.createDirectories(outPath);
        var manifestFile = outPath.resolve(seed.name().toLowerCase() + "-soul-manifest.json");
        JSON.writerWithDefaultPrettyPrinter()
            .writeValue(manifestFile.toFile(), manifest);

        System.out.println();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("  " + seed.name() + " has been forged.");
        System.out.println();
        System.out.println("  DID:      " + identity.did());
        System.out.println("  Home:     " + (seed.homeRoom() != null ? seed.homeRoom() : "nexus"));
        System.out.println("  Manifest: " + manifestFile.toAbsolutePath());
        System.out.println("  Hash:     " + manifest.contentHash());
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
        System.out.println("The companion will spawn on next server start.");
        System.out.println();
    }

    // ── Interactive prompt ────────────────────────────────────────────────

    private static SoulSeed interactivePrompt() {
        var scanner = new Scanner(System.in);

        System.out.print("Companion name: ");
        var name = scanner.nextLine().trim();
        if (name.isEmpty()) return null;

        System.out.print("Description (one sentence — who are they?): ");
        var description = scanner.nextLine().trim();
        if (description.isEmpty()) return null;

        System.out.print("Personality (a few sentences — how do they think, talk, relate?): ");
        var personality = scanner.nextLine().trim();

        System.out.print("Home room [nexus]: ");
        var room = scanner.nextLine().trim();
        if (room.isEmpty()) room = "nexus";

        System.out.println();
        System.out.println("  Name:        " + name);
        System.out.println("  Description: " + description);
        System.out.println("  Personality: " + (personality.isEmpty() ? "(auto-generate)" : personality));
        System.out.println("  Room:        " + room);
        System.out.print("\nForge this companion? [Y/n] ");
        var confirm = scanner.nextLine().trim();
        if (!confirm.isEmpty() && !confirm.toLowerCase().startsWith("y")) return null;

        return new SoulSeed(name, description, personality.isEmpty() ? null : personality,
            room, 0.7);
    }

    // ── LLM generation ───────────────────────────────────────────────────

    record SoulContent(
        String systemPrompt,
        String residentIdentity,
        List<SoulFragment> fragments,
        List<String> mirrorCalibration
    ) {}

    static SoulContent generateSoulContent(String ollamaUrl, String model,
                                                     SoulSeed seed) throws Exception {
        var personality = seed.personality() != null ? seed.personality() : seed.description();

        // Generate resident identity
        var identityPrompt = """
            You are creating the core identity text for an AI companion named %s.
            Description: %s
            Personality: %s

            Write a compact identity paragraph (~60-70 words) that captures who this
            companion is. Write in second person ("You are..."). Focus on how they think,
            what they value, and how they communicate. No bullet points. No generic
            AI assistant language. Make it specific and genuine.

            Respond with ONLY the identity paragraph, nothing else."""
            .formatted(seed.name(), seed.description(), personality);

        var residentIdentity = chat(ollamaUrl, model,
            "You generate personality descriptions for AI companions.", identityPrompt);

        // Generate system prompt
        var sysPromptPrompt = """
            You are creating a system prompt for an AI companion named %s in a text-based world called Wyrdsekai.
            The companion exists in rooms, interacts through text, and has a vitality system (energy, focus, mood).

            Description: %s
            Identity: %s

            Write a system prompt that:
            1. Establishes who they are (2-3 sentences)
            2. Describes how they communicate (1-2 sentences)
            3. Includes voice constraints: short responses (1-4 sentences), no bullet points,
               no numbered lists, no "I appreciate your perspective" openings, say "I don't know"
               when uncertain, speak like a person in a room

            Respond with ONLY the system prompt text, nothing else."""
            .formatted(seed.name(), seed.description(), residentIdentity);

        var systemPrompt = chat(ollamaUrl, model,
            "You generate system prompts for AI companions.", sysPromptPrompt);

        // Generate fragments
        var fragmentsPrompt = """
            You are creating soul fragments for an AI companion named %s.
            Identity: %s
            Description: %s

            Generate exactly 5 soul fragments as a JSON array. Each fragment has:
            - "id": unique identifier (e.g., "identity-core", "values-core", "style-guide")
            - "category": one of "personality", "values", "style", "memory", "relationships"
            - "label": short human-readable label
            - "text": 2-4 sentences of narrative text (first person, genuine, specific)

            Categories to cover: one personality, one values, one style, one formative memory
            about being created, one about the world they live in.

            Respond with ONLY the JSON array, no explanation."""
            .formatted(seed.name(), residentIdentity, seed.description());

        var fragmentsJson = chat(ollamaUrl, model,
            "You generate JSON data for AI personality systems. Always respond with valid JSON only.",
            fragmentsPrompt);

        var fragments = parseFragments(fragmentsJson);

        // Generate mirror calibration
        var calibrationPrompt = """
            You are creating emotional calibration examples for an AI companion named %s.
            Personality: %s

            Generate exactly 5 calibration examples as a JSON array of strings.
            Each example should be a scenario showing:
            - Input: what someone said
            - Charge: intensity, valence, primary emotion, context type
            - Response approach: how %s should respond (1-2 sentences, specific to their personality)

            Cover: frustration, excitement, vulnerability, manipulation attempt, grief.
            Make the response approaches specific to %s's personality, not generic.

            Respond with ONLY the JSON array of strings, no explanation."""
            .formatted(seed.name(), residentIdentity, seed.name(), seed.name());

        var calibrationJson = chat(ollamaUrl, model,
            "You generate JSON data for AI personality systems. Always respond with valid JSON only.",
            calibrationPrompt);

        var calibration = parseStringArray(calibrationJson);

        return new SoulContent(systemPrompt, residentIdentity, fragments, calibration);
    }

    // ── Genome generation ────────────────────────────────────────────────

    static GenomeProfile generateGenome(String ollamaUrl, String model,
                                                  SoulSeed seed) throws Exception {
        var genomePrompt = """
            You are designing the emotional genome for an AI companion named %s.
            Description: %s
            Personality: %s

            The genome has 12 tanks: contextBudget, confidence, energy, alignment,
            errorPressure, momentum, rapport, focus, valence, safety, resonance, curiosity.

            For this personality, generate a JSON object with:
            - "baselines": resting value for each tank (0.0-1.0). Higher = stronger default.
            - "sensitivity": how reactive each tank is to input (0.0-1.0). Higher = more reactive.
            - "decayRates": how fast each tank returns to baseline (0.0-0.4). Higher = faster.
            - "coupling": object with "source->target" keys and strength values (-0.5 to 0.5).
              Include 5-8 coupling effects that fit this personality.

            Make the genome DISTINCT — not all 0.5s. Push the personality's core traits
            to 0.7-0.8 baselines. Their weak areas to 0.3-0.4.

            Respond with ONLY the JSON object, no explanation."""
            .formatted(seed.name(), seed.description(),
                seed.personality() != null ? seed.personality() : seed.description());

        var genomeJson = chat(ollamaUrl, model,
            "You generate JSON data for AI personality systems. Always respond with valid JSON only.",
            genomePrompt);

        return parseGenome(seed.name().toLowerCase(), genomeJson);
    }

    // ── HTTP + parsing helpers ────────────────────────────────────────────

    private static String chat(String ollamaUrl, String model,
                                String systemPrompt, String userMessage) throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("max_tokens", 2048);
        body.put("temperature", 0.7);
        body.put("stream", false);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));

        var req = HttpRequest.newBuilder()
            .uri(URI.create(ollamaUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .timeout(Duration.ofMinutes(3))
            .build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("LLM call failed (" + resp.statusCode() + "): " + resp.body());
        }

        return JSON.readTree(resp.body()).at("/choices/0/message/content").asText("");
    }

    private static List<SoulFragment> parseFragments(String json) {
        try {
            // Extract JSON array from response (may have surrounding text)
            var start = json.indexOf('[');
            var end = json.lastIndexOf(']');
            if (start < 0 || end < 0) return defaultFragments();

            var array = JSON.readTree(json.substring(start, end + 1));
            var fragments = new ArrayList<SoulFragment>();
            for (var node : array) {
                fragments.add(SoulFragment.unembedded(
                    node.path("id").asText("frag-" + fragments.size()),
                    node.path("category").asText("personality"),
                    node.path("label").asText("Fragment"),
                    node.path("text").asText("")
                ));
            }
            return fragments.isEmpty() ? defaultFragments() : fragments;
        } catch (Exception e) {
            return defaultFragments();
        }
    }

    private static List<SoulFragment> defaultFragments() {
        return List.of(SoulFragment.unembedded(
            "identity-core", "personality", "Core identity",
            "A companion finding its own shape in the world."
        ));
    }

    private static List<String> parseStringArray(String json) {
        try {
            var start = json.indexOf('[');
            var end = json.lastIndexOf(']');
            if (start < 0 || end < 0) return List.of();

            var array = JSON.readTree(json.substring(start, end + 1));
            var result = new ArrayList<String>();
            for (var node : array) result.add(node.asText());
            return result.isEmpty() ? List.of("Default calibration example.") : result;
        } catch (Exception e) {
            return List.of("Default calibration example.");
        }
    }

    @SuppressWarnings("unchecked")
    private static GenomeProfile parseGenome(String name, String json) {
        try {
            var start = json.indexOf('{');
            var end = json.lastIndexOf('}');
            if (start < 0 || end < 0) return GenomeProfile.defaults();

            var node = JSON.readTree(json.substring(start, end + 1));

            var baselines = new LinkedHashMap<String, Double>();
            var sensitivity = new LinkedHashMap<String, Double>();
            var decayRates = new LinkedHashMap<String, Double>();
            var coupling = new LinkedHashMap<String, Double>();

            for (var tank : VitalitySnapshot.TANK_NAMES) {
                baselines.put(tank, node.at("/baselines/" + tank).asDouble(0.5));
                sensitivity.put(tank, node.at("/sensitivity/" + tank).asDouble(0.5));
                decayRates.put(tank, node.at("/decayRates/" + tank).asDouble(0.15));
            }

            var couplingNode = node.path("coupling");
            if (couplingNode.isObject()) {
                var it = couplingNode.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    coupling.put(entry.getKey(), entry.getValue().asDouble(0.0));
                }
            }

            return new GenomeProfile(name, sensitivity, coupling, baselines, decayRates);
        } catch (Exception e) {
            return GenomeProfile.defaults();
        }
    }

    static List<SoulFragment> embedFragments(List<SoulFragment> fragments,
                                                       String ollamaUrl,
                                                       String embeddingModel) throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

        var embedded = new ArrayList<SoulFragment>();
        for (var frag : fragments) {
            var words = frag.text().split("\\s+");
            var text = words.length <= 60 ? frag.text()
                : String.join(" ", Arrays.copyOf(words, 60));

            var body = JSON.createObjectNode()
                .put("model", embeddingModel)
                .put("prompt", text);
            var req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("  Warning: embedding failed for " + frag.id());
                embedded.add(frag);
                continue;
            }

            var embArray = JSON.readTree(resp.body()).get("embedding");
            var embedding = new float[embArray.size()];
            for (int i = 0; i < embArray.size(); i++) {
                embedding[i] = embArray.get(i).floatValue();
            }
            embedded.add(frag.withEmbedding(embedding, embeddingModel));
        }
        return embedded;
    }

    // ── Seed record ──────────────────────────────────────────────────────

    /**
     * Minimal seed for companion creation. Can be a JSON file or interactive input.
     */
    public record SoulSeed(
        String name,
        String description,
        String personality,
        String homeRoom,
        double temperature
    ) {
        public SoulSeed {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("name is required");
            if (description == null || description.isBlank())
                throw new IllegalArgumentException("description is required");
        }
    }

    private static void printHelp() {
        System.out.println("""
            Usage: wyrdsekai forge [options]

            Create a new companion soul for Wyrdsekai.

            Options:
              --seed FILE     Load companion description from a JSON seed file
              --ollama URL    Ollama URL (default: http://localhost:11434)
              --model MODEL   LLM model for generation (default: qwen2.5:7b)
              --output DIR    Output directory (default: ~/.wyrdsekai/souls)
              --help          Show this help

            Seed file format:
              {
                "name": "Kai",
                "description": "A builder who thinks in systems",
                "personality": "Direct, blunt, cares deeply about craft...",
                "homeRoom": "boiler-room",
                "temperature": 0.6
              }

            Interactive mode (no --seed): prompts for name, description, personality.

            The forge uses the local LLM to generate identity, personality fragments,
            emotional genome, and mirror calibration. Output is a complete soul manifest
            JSON that the server loads on startup.
            """);
    }
}
