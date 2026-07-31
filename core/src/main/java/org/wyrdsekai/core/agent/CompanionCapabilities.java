package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.skill.SkillRegistry;
import org.wyrdsekai.core.skill.WorkbenchSkillExecutor;
import org.wyrdsekai.core.skill.WorkbenchValidator;
import org.wyrdsekai.core.skill.SkillItemCodec;
import org.wyrdsekai.core.soul.FamilyLocker;

/**
 * Bundles optional capability system references for CompanionActor.
 * Avoids constructor explosion — pass this as a single parameter.
 * All fields nullable; companion gracefully degrades without them.
 *
 * @param familyLocker                For skill item storage/retrieval (nullable)
 * @param mcpGateway                  For MCP service discovery in Layer 2.7 (nullable)
 * @param workbenchExecutor           For executing workbench-created skills (nullable)
 * @param skillRegistry               For executing skills through the full pipeline (nullable)
 * @param openClawConnected           Whether OpenClaw gateway is reachable
 * @param openClawSkillCount          Number of skills available via OpenClaw
 * @param zoneContext                 Pre-built zone resource context string (nullable)
 * @param workshopReachable           Whether the Workshop room is reachable
 * @param proactivityPolicy           Controls proactive skill activation (nullable)
 * @param usageTracker                Records skill invocations and outcomes (nullable)
 * @param inferenceCapabilityContext  Pre-built inference capability prompt context (nullable)
 */
public record CompanionCapabilities(
    FamilyLocker familyLocker,
    McpGatewayService mcpGateway,
    WorkbenchSkillExecutor workbenchExecutor,
    SkillRegistry skillRegistry,
    boolean openClawConnected,
    int openClawSkillCount,
    String zoneContext,
    boolean workshopReachable,
    ProactivityPolicy proactivityPolicy,
    SkillUsageTracker usageTracker,
    String inferenceCapabilityContext
) {
    /** Backward-compatible constructor without inferenceCapabilityContext. */
    public CompanionCapabilities(
            FamilyLocker familyLocker,
            McpGatewayService mcpGateway,
            WorkbenchSkillExecutor workbenchExecutor,
            SkillRegistry skillRegistry,
            boolean openClawConnected,
            int openClawSkillCount,
            String zoneContext,
            boolean workshopReachable,
            ProactivityPolicy proactivityPolicy,
            SkillUsageTracker usageTracker) {
        this(familyLocker, mcpGateway, workbenchExecutor, skillRegistry,
            openClawConnected, openClawSkillCount, zoneContext, workshopReachable,
            proactivityPolicy, usageTracker, null);
    }

    /** Backward-compatible constructor without proactivity/usage/inference fields. */
    public CompanionCapabilities(
            FamilyLocker familyLocker,
            McpGatewayService mcpGateway,
            WorkbenchSkillExecutor workbenchExecutor,
            SkillRegistry skillRegistry,
            boolean openClawConnected,
            int openClawSkillCount,
            String zoneContext,
            boolean workshopReachable) {
        this(familyLocker, mcpGateway, workbenchExecutor, skillRegistry,
            openClawConnected, openClawSkillCount, zoneContext, workshopReachable,
            null, null, null);
    }

    /**
     * Check whether a specific inference capability is available.
     * Looks for the capability name in the pre-built inference capability context string.
     *
     * @param capability the capability name (e.g. "vision", "reasoning", "coding")
     * @return true if the capability appears to be registered
     */
    public boolean hasCapability(String capability) {
        if (capability == null || inferenceCapabilityContext == null) return false;
        return inferenceCapabilityContext.contains(capability);
    }

    /** Empty capabilities — companion has no capability system access. */
    public static CompanionCapabilities none() {
        return new CompanionCapabilities(null, null, null, null, false, 0, null, false,
            null, null, null);
    }
}
