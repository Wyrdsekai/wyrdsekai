import {
  signingData, createEnvelope, verifyEnvelope,
  envelopeToBytes, envelopeFromBytes,
} from '../../src/engine/between/BetweenEnvelope';
import { generateIdentity, TestCryptoProvider } from '../../src/engine/between/NodeIdentity';

describe('BetweenEnvelope', () => {
  const crypto = new TestCryptoProvider();

  it('signing data format matches spec', () => {
    const data = signingData('node-1', 'node-2', 1234567890, { type: 'headline' });
    const str = new TextDecoder().decode(data);
    expect(str).toContain('node-1:node-2:1234567890:');
    expect(str).toContain('"type":"headline"');
  });

  it('signing data uses asterisk for broadcast', () => {
    const data = signingData('node-1', null, 100, 'test');
    const str = new TextDecoder().decode(data);
    expect(str).toContain('node-1:*:100:');
  });

  it('create and serialize', () => {
    const identity = generateIdentity(crypto);
    const payload = { type: 'headline', summary: 'all is well' };

    const envelope = createEnvelope('node-1', null, payload, identity, crypto);

    expect(envelope.v).toBe(1);
    expect(envelope.src).toBe('node-1');
    expect(envelope.dst).toBeNull();
    expect(envelope.sig.length).toBeGreaterThan(0);
    expect(envelope.ts).toBeGreaterThan(0);

    // Round-trip serialization
    const bytes = envelopeToBytes(envelope);
    const restored = envelopeFromBytes(bytes);
    expect(restored.src).toBe(envelope.src);
    expect(restored.dst).toBe(envelope.dst);
    expect(restored.ts).toBe(envelope.ts);
    expect(restored.sig).toBe(envelope.sig);
  });

  it('verify with test crypto', () => {
    const identity = generateIdentity(crypto);
    const envelope = createEnvelope('node-1', 'node-2', { test: true }, identity, crypto);
    expect(verifyEnvelope(envelope, identity.publicKey, crypto)).toBe(true);
  });

  it('envelope version is 1', () => {
    const identity = generateIdentity(crypto);
    const envelope = createEnvelope('n1', null, 'p', identity, crypto);
    expect(envelope.v).toBe(1);
  });
});
