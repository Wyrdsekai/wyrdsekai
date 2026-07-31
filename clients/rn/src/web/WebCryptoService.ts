/**
 * WebCryptoService — Web Crypto API wrapper for Ed25519 key generation,
 * signing, and verification.
 *
 * Ed25519 via Web Crypto is supported in Chrome 113+, Edge 113+, and Safari 17+.
 * This service provides the same identity primitives as the server-side Ed25519Math
 * but using browser-native cryptography.
 *
 * Keys are hex-encoded for transport/storage. Public keys are raw 32-byte format.
 * Private keys are PKCS#8-encoded (for Web Crypto API compatibility).
 */

/**
 * Ed25519 algorithm identifier for Web Crypto API.
 * Not yet in the standard TypeScript DOM lib types, so we define it here.
 */
interface Ed25519Params extends Algorithm {
  name: 'Ed25519';
}

const ED25519_ALGORITHM: Ed25519Params = { name: 'Ed25519' };

export interface KeyPair {
  /** Hex-encoded raw public key (32 bytes = 64 hex chars). */
  publicKey: string;
  /** Hex-encoded PKCS#8 private key. */
  privateKey: string;
}

export class WebCryptoService {
  /**
   * Generate an Ed25519 key pair using Web Crypto API.
   * Ed25519 support was added in Chrome 113+ and Safari 17+.
   */
  async generateKeyPair(): Promise<KeyPair> {
    if (!this.isSupported()) {
      throw new Error('Web Crypto Ed25519 not supported in this browser');
    }

    const keyPair = await crypto.subtle.generateKey(
      ED25519_ALGORITHM,
      true, // extractable
      ['sign', 'verify'],
    );

    const publicKeyRaw = await crypto.subtle.exportKey(
      'raw',
      keyPair.publicKey,
    );
    const privateKeyRaw = await crypto.subtle.exportKey(
      'pkcs8',
      keyPair.privateKey,
    );

    return {
      publicKey: this.bufferToHex(publicKeyRaw),
      privateKey: this.bufferToHex(privateKeyRaw),
    };
  }

  /**
   * Sign a message with an Ed25519 private key.
   * @param privateKeyHex - Hex-encoded PKCS#8 private key
   * @param message - The plaintext message to sign
   * @returns Hex-encoded Ed25519 signature (64 bytes = 128 hex chars)
   */
  async sign(privateKeyHex: string, message: string): Promise<string> {
    const privateKeyBuffer = this.hexToBuffer(privateKeyHex);
    const key = await crypto.subtle.importKey(
      'pkcs8',
      privateKeyBuffer,
      ED25519_ALGORITHM,
      false,
      ['sign'],
    );

    const messageBuffer = new TextEncoder().encode(message);
    const signature = await crypto.subtle.sign('Ed25519', key, messageBuffer);

    return this.bufferToHex(signature);
  }

  /**
   * Verify an Ed25519 signature.
   * @param publicKeyHex - Hex-encoded raw public key (32 bytes)
   * @param message - The original plaintext message
   * @param signatureHex - Hex-encoded signature to verify
   * @returns true if the signature is valid
   */
  async verify(
    publicKeyHex: string,
    message: string,
    signatureHex: string,
  ): Promise<boolean> {
    const publicKeyBuffer = this.hexToBuffer(publicKeyHex);
    const key = await crypto.subtle.importKey(
      'raw',
      publicKeyBuffer,
      ED25519_ALGORITHM,
      false,
      ['verify'],
    );

    const messageBuffer = new TextEncoder().encode(message);
    const signatureBuffer = this.hexToBuffer(signatureHex);

    return crypto.subtle.verify(
      'Ed25519',
      key,
      signatureBuffer,
      messageBuffer,
    );
  }

  /**
   * Check if Web Crypto Ed25519 is available in the current environment.
   * Note: This only checks for the presence of the API, not Ed25519 algorithm support.
   * Actual Ed25519 support varies by browser version.
   */
  isSupported(): boolean {
    return (
      typeof crypto !== 'undefined' &&
      typeof crypto.subtle !== 'undefined' &&
      typeof crypto.subtle.generateKey === 'function'
    );
  }

  private bufferToHex(buffer: ArrayBuffer): string {
    return Array.from(new Uint8Array(buffer))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }

  private hexToBuffer(hex: string): ArrayBuffer {
    const bytes = new Uint8Array(hex.length / 2);
    for (let i = 0; i < hex.length; i += 2) {
      bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
    }
    return bytes.buffer;
  }
}
