package org.wyrdsekai.app.engine.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentProfileTest {

    @Test
    fun nexusCompanionProfile() {
        val companion = Companions.NEXUS_COMPANION

        assertEquals("Wyrd", companion.name)
        assertTrue(companion.entityId.contains("companion"), "entityId should contain 'companion', was: ${companion.entityId}")
        assertEquals("agent", companion.entityType)
        assertTrue(companion.description.isNotBlank())
        assertTrue(companion.systemPrompt.isNotBlank())
        assertTrue(companion.contextWindowTokens > 0)
        assertTrue(companion.maxResponseTokens > 0)
        assertTrue(companion.temperature > 0.0)
    }

    @Test
    fun customProfileConstruction() {
        val profile = AgentProfile(
            name = "Sage",
            entityId = "companion-sage",
            entityType = "agent",
            description = "A wise advisor",
            systemPrompt = "You are Sage, a wise advisor.",
            contextWindowTokens = 8192,
            maxResponseTokens = 1024,
            temperature = 0.5,
        )

        assertEquals("Sage", profile.name)
        assertEquals("companion-sage", profile.entityId)
        assertEquals("agent", profile.entityType)
        assertEquals("A wise advisor", profile.description)
        assertEquals("You are Sage, a wise advisor.", profile.systemPrompt)
        assertEquals(8192, profile.contextWindowTokens)
        assertEquals(1024, profile.maxResponseTokens)
        assertEquals(0.5, profile.temperature)
    }
}
