/**
 * Node identity — Ed25519 keypair for Between authentication.
 * Platform-independent interface with test implementation.
 *
 * Production implementations use:
 * - Web Crypto API (WebCryptoService.ts) for web
 * - react-native-keychain for native RN
 *
 */

export interface CryptoProvider {
  /** Generate a new Ed25519 keypair. Returns { publicKey, privateKey } as hex strings. */
  generateKeyPair(): { publicKey: string; privateKey: string };

  /** Sign data with a private key. Returns signature as hex string. */
  sign(privateKey: string, data: Uint8Array): string;

  /** Verify a signature against a public key. */
  verify(publicKey: string, data: Uint8Array, signature: string): boolean;
}

export interface NodeIdentity {
  nodeId: string;
  publicKey: string;
  privateKey: string;
}

/** Generate a new node identity. */
export function generateIdentity(crypto: CryptoProvider): NodeIdentity {
  const { publicKey, privateKey } = crypto.generateKeyPair();
  return {
    nodeId: generateNodeId(),
    publicKey,
    privateKey,
  };
}

function generateNodeId(): string {
  const chars = '0123456789abcdef';
  const seg = (n: number) => Array.from({ length: n }, () => chars[Math.floor(Math.random() * 16)]).join('');
  return `${seg(8)}-${seg(4)}-${seg(4)}-${seg(12)}`;
}

/**
 * Test-only crypto provider.
 * Uses simple XOR-based "signatures" — NOT cryptographically secure.
 */
export class TestCryptoProvider implements CryptoProvider {
  generateKeyPair(): { publicKey: string; privateKey: string } {
    const rand = () => Array.from({ length: 32 }, () =>
      Math.floor(Math.random() * 256).toString(16).padStart(2, '0'),
    ).join('');
    return { publicKey: rand(), privateKey: rand() };
  }

  sign(_privateKey: string, _data: Uint8Array): string {
    // Return a fake 64-byte signature as hex
    return Array.from({ length: 64 }, () =>
      Math.floor(Math.random() * 256).toString(16).padStart(2, '0'),
    ).join('');
  }

  verify(_publicKey: string, _data: Uint8Array, signature: string): boolean {
    // Test verification: just check signature length (128 hex chars = 64 bytes)
    return signature.length === 128;
  }
}
