import { NativeModules } from 'react-native';

/**
 * In-tree iOS native module that pins self-signed household relay TLS certs for
 * the RN WebSocket (SocketRocket) transport — iOS companion to the Android
 * org.wyrdsekai.rn.HouseholdTrust* classes (#733).
 *
 * The actual consumer is clients/rn/src/server/HouseholdTrust.ts, which reaches
 * the native side via NativeModules.HouseholdTrust (legacy bridge name set by
 * RCT_EXPORT_MODULE(HouseholdTrust)). This re-export exists only so the package
 * `main` resolves and tooling can find a typed handle; behaviour lives in the
 * native .mm. The provider hook that pins every WebSocket arms itself at image
 * load (WyrdInstallSRWebSocketProvider in WyrdHouseholdTrust.mm) — importing
 * this file is NOT required for pinning to work.
 */
export interface NativeHouseholdTrust {
  addTrustedCert(host: string, pem: string): Promise<boolean>;
  removeTrustedCert(host: string): Promise<boolean>;
  listTrustedHosts(): Promise<
    Array<{ host: string; subject: string; validUntil: number }>
  >;
  fetchServerCertificates(
    host: string,
    port: number,
  ): Promise<Array<{ pem: string; fingerprint: string }>>;
}

export default (NativeModules.HouseholdTrust as NativeHouseholdTrust | undefined) ?? null;
