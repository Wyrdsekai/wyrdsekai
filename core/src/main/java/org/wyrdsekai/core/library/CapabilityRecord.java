package org.wyrdsekai.core.library;

import java.time.Instant;
import java.util.List;

/**
 * A registered capability in The Library.
 * Domain-specific record for Wyrdsekai — structured around cognitive layers,
 * zone-native sources, and MUD-appropriate invocation protocols.
 */
public record CapabilityRecord(
    // Identity
    String id,
    String name,
    String version,
    String description,

    // Taxonomy
    CognitiveLayer cognitiveLayer,
    List<String> tags,
    CapabilitySource source,
    CapabilityProtocol protocol,

    // Trust
    float trustScore,
    VerificationStatus verificationStatus,
    Instant lastVerified,
    String attestation,
    String provenance,

    // Access
    String provider,
    String requiredWard,
    int tokenCost,

    // Lifecycle
    boolean installed,
    String installedLocation,
    Instant registeredAt,
    Instant installedAt
) {

    /**
     * 9 cognitive layers (adapted from Synapti Agent Capability Standard).
     * Structures capability discovery: "I need a tool that can PERCEIVE" vs "I need one that can EXECUTE".
     */
    public enum CognitiveLayer {
        PERCEIVE,    // Retrieve, search, observe (input)
        UNDERSTAND,  // Detect, classify, parse (comprehension)
        REASON,      // Plan, decide, evaluate (thinking)
        MODEL,       // Track state, maintain context (memory)
        SYNTHESIZE,  // Generate, compose, translate (creation)
        EXECUTE,     // Mutate, act, invoke (action)
        VERIFY,      // Check, validate, test (quality)
        REMEMBER,    // Persist, checkpoint, archive (long-term)
        COORDINATE   // Delegate, orchestrate, negotiate (multi-agent)
    }

    /** Where capabilities originate — zone-native taxonomy. */
    public enum CapabilitySource {
        SEED,        // Shipped with the zone (built-in)
        MANUAL,      // Registered by admin/player via Library room
        AGENT,       // Registered by an agent autonomously
        FEDERATED,   // Received via inter-library loan from another zone
        DISCOVERED   // Found via Between capability advertisement
    }

    /** How the capability is invoked — MUD-native protocols. */
    public enum CapabilityProtocol {
        ROOM_SCRIPT, // GraalJS room behavior (loaded by RoomScriptEngine)
        AGENT,       // Spawned as a Pekko actor (CompanionActor pattern)
        SERVICE,     // Registered in the Between as a service
        WORLD_API,   // Exposed via world.* API in room scripts
        INFERENCE    // Invoked via InferenceRouter (prompt-based tool)
    }

    /** Trust/verification lifecycle. */
    public enum VerificationStatus {
        UNVERIFIED,   // Just registered, not yet checked
        QUARANTINED,  // Failed verification or flagged
        VERIFIED,     // Passed verification pipeline
        BANNED        // Explicitly blocked
    }

    /** Format for display in room scripts. */
    public String describe() {
        var sb = new StringBuilder();
        sb.append(name).append(" v").append(version != null ? version : "?");
        if (cognitiveLayer != null) {
            sb.append(" [").append(cognitiveLayer.name()).append("]");
        }
        sb.append("\n");

        if (description != null && !description.isBlank()) {
            sb.append("  ").append(description).append("\n");
        }

        sb.append("  Provider: ").append(provider != null ? provider : "unknown").append("\n");
        sb.append("  Source: ").append(source != null ? source.name().toLowerCase() : "unknown");
        sb.append("  Protocol: ").append(protocol != null ? protocol.name().toLowerCase() : "unknown");
        sb.append("\n");

        // Trust info
        sb.append("  Trust: ");
        if (trustScore < 0) {
            sb.append("unscored");
        } else {
            sb.append(String.format("%.0f%%", trustScore * 100));
        }
        sb.append(" (").append(verificationStatus != null ? verificationStatus.name().toLowerCase() : "unknown").append(")");
        if (lastVerified != null) {
            sb.append(" — last verified ").append(lastVerified);
        }
        sb.append("\n");

        if (tags != null && !tags.isEmpty()) {
            sb.append("  Tags: ").append(String.join(", ", tags)).append("\n");
        }

        if (requiredWard != null && !requiredWard.isBlank()) {
            sb.append("  Required ward: ").append(requiredWard).append("\n");
        }

        if (tokenCost > 0) {
            sb.append("  Token cost: ~").append(tokenCost).append(" CU/invocation\n");
        }

        if (installed) {
            sb.append("  Installed: ").append(installedLocation != null ? installedLocation : "yes");
            if (installedAt != null) {
                sb.append(" (since ").append(installedAt).append(")");
            }
            sb.append("\n");
        }

        if (attestation != null && !attestation.isBlank()) {
            sb.append("  Attestation: present\n");
        }

        if (provenance != null && !provenance.isBlank()) {
            sb.append("  Provenance: present\n");
        }

        return sb.toString().stripTrailing();
    }

    /** Short one-line summary for listings. */
    public String summarize() {
        var sb = new StringBuilder();
        sb.append(name);
        if (id != null && id.length() >= 8) {
            sb.append(" (").append(id.substring(0, 8)).append(")");
        }
        sb.append(" v").append(version != null ? version : "?");
        if (cognitiveLayer != null) {
            sb.append(" [").append(cognitiveLayer.name()).append("]");
        }
        if (trustScore >= 0) {
            sb.append(" ").append(String.format("%.0f%%", trustScore * 100));
        }
        return sb.toString();
    }
}
