import { NEXUS_COMPANION } from '../../src/engine/agent/AgentProfile';

describe('AgentProfile', () => {
  it('NEXUS_COMPANION has expected defaults', () => {
    expect(NEXUS_COMPANION.name).toBe('Wyrd');
    expect(NEXUS_COMPANION.entityId).toBe('companion-wyrd');
    expect(NEXUS_COMPANION.entityType).toBe('agent');
    expect(NEXUS_COMPANION.contextWindowTokens).toBe(4096);
    expect(NEXUS_COMPANION.maxResponseTokens).toBe(512);
    expect(NEXUS_COMPANION.temperature).toBe(0.7);
  });

  it('system prompt is non-empty and mentions Wyrd', () => {
    expect(NEXUS_COMPANION.systemPrompt.length).toBeGreaterThan(100);
    expect(NEXUS_COMPANION.systemPrompt).toContain('Wyrd');
    // The prompt was reworded away from the old "Nexus" room name to the
    // generic "living programmable space" framing.
    expect(NEXUS_COMPANION.systemPrompt).toContain('living programmable space');
  });
});
