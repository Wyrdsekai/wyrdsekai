/**
 * Registers and invokes device capabilities (camera, microphone, GPS, etc.)
 * as Between services callable from any household node.
 * TypeScript port of KMP's DeviceCapabilityService.kt.
 *
 * Subject pattern:
 *   between.{householdId}.{src}.{dst}.device.invoke       — invoke
 *   between.{householdId}.{src}.{dst}.device.result        — reply
 *   between.{householdId}.{deviceId}.*.device.capabilities — advertise
 */
import type { BetweenClient } from './BetweenClient';

export type CapabilityHandler = (params: Record<string, string>) => Record<string, string>;

export interface CapabilityRequest {
  requesterId: string;
  capabilityName: string;
  params: Record<string, string>;
  requestId: string;
}

export interface CapabilityResult {
  requestId: string;
  success: boolean;
  data?: Record<string, string>;
  error?: string;
}

export interface CapabilityAdvertisement {
  deviceId: string;
  capabilities: string[];
}

export type CapabilityEvent =
  | { type: 'authorization_required'; request: CapabilityRequest }
  | { type: 'invoked'; capabilityName: string; requesterId: string };

export class DeviceCapabilityService {
  private capabilities = new Map<string, CapabilityHandler>();
  private grants = new Map<string, Set<string>>(); // requesterId → capability names
  private unsubInvoke: (() => void) | null = null;
  private listeners: ((event: CapabilityEvent) => void)[] = [];

  constructor(
    private readonly between: BetweenClient,
    private readonly deviceId: string,
    private readonly householdId: string,
  ) {}

  register(name: string, handler: CapabilityHandler): void {
    this.capabilities.set(name, handler);
  }

  grant(requesterId: string, capabilityName: string): void {
    if (!this.grants.has(requesterId)) this.grants.set(requesterId, new Set());
    this.grants.get(requesterId)!.add(capabilityName);
  }

  revoke(requesterId: string, capabilityName: string): void {
    this.grants.get(requesterId)?.delete(capabilityName);
  }

  isGranted(requesterId: string, capabilityName: string): boolean {
    return this.grants.get(requesterId)?.has(capabilityName) === true;
  }

  startListening(): void {
    const subject = `between.${this.householdId}.*.${this.deviceId}.device.invoke`;
    this.unsubInvoke = this.between.subscribe(subject, (_sub, data) => {
      try {
        const request: CapabilityRequest = JSON.parse(new TextDecoder().decode(data));
        this.handleInvocation(request);
      } catch { /* skip */ }
    });
  }

  stopListening(): void {
    this.unsubInvoke?.();
    this.unsubInvoke = null;
  }

  onEvent(callback: (event: CapabilityEvent) => void): void {
    this.listeners.push(callback);
  }

  advertise(): void {
    if (!this.between.isConnected) return;
    const msg: CapabilityAdvertisement = {
      deviceId: this.deviceId,
      capabilities: [...this.capabilities.keys()],
    };
    try {
      const subject = `between.${this.householdId}.${this.deviceId}.*.device.capabilities`;
      this.between.publish(subject, new TextEncoder().encode(JSON.stringify(msg)));
    } catch { /* non-fatal */ }
  }

  invoke(targetDeviceId: string, capabilityName: string, params: Record<string, string> = {}): void {
    if (!this.between.isConnected) return;
    const request: CapabilityRequest = {
      requesterId: this.deviceId,
      capabilityName,
      params,
      requestId: `req-${Date.now()}`,
    };
    try {
      const subject = `between.${this.householdId}.${this.deviceId}.${targetDeviceId}.device.invoke`;
      this.between.publish(subject, new TextEncoder().encode(JSON.stringify(request)));
    } catch { /* non-fatal */ }
  }

  private handleInvocation(request: CapabilityRequest): void {
    const handler = this.capabilities.get(request.capabilityName);
    if (!handler) {
      this.sendResult(request, { requestId: request.requestId, success: false, error: `Unknown capability: ${request.capabilityName}` });
      return;
    }

    if (!this.isGranted(request.requesterId, request.capabilityName)) {
      for (const l of this.listeners) l({ type: 'authorization_required', request });
      return;
    }

    try {
      const data = handler(request.params);
      this.sendResult(request, { requestId: request.requestId, success: true, data });
      for (const l of this.listeners) l({ type: 'invoked', capabilityName: request.capabilityName, requesterId: request.requesterId });
    } catch (e) {
      this.sendResult(request, { requestId: request.requestId, success: false, error: String(e) });
    }
  }

  private sendResult(request: CapabilityRequest, result: CapabilityResult): void {
    if (!this.between.isConnected) return;
    try {
      const subject = `between.${this.householdId}.${this.deviceId}.${request.requesterId}.device.result`;
      this.between.publish(subject, new TextEncoder().encode(JSON.stringify(result)));
    } catch { /* non-fatal */ }
  }
}
