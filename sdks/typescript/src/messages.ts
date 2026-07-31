/** Zone Bridge message types. */

export interface Register {
  type: "register";
  namespace: string;
  secret?: string;
}

export interface Registered {
  type: "registered";
  namespace: string;
}

export interface RegistrationError {
  type: "error";
  namespace: string;
  reason: string;
}

export interface ForwardCommand {
  type: "command";
  requestId: string;
  playerId: string;
  action: string;
  args: string[];
  payload: Record<string, unknown>;
}

export interface CommandResponse {
  type: "response";
  requestId: string;
  playerId: string;
  messages: S2CMessage[];
}

export interface Broadcast {
  type: "broadcast";
  roomId?: string;
  messages: S2CMessage[];
}

export interface ContentBlock {
  format: string;
  data: Record<string, unknown>;
  fallback: string;
}

export interface ProseMessage {
  type: "prose";
  seq: number;
  speaker: string;
  text: string;
  hints: unknown[];
  contentBlocks: ContentBlock[] | null;
  priority: "critical" | "normal" | "ambient";
  locale: string;
}

export interface ErrorMessage {
  type: "error";
  seq: number;
  code: string;
  message: string;
  requestId: string;
}

export type S2CMessage = ProseMessage | ErrorMessage | Record<string, unknown>;

export type ServerMessage = Registered | RegistrationError | ForwardCommand;

export function parseServerMessage(data: Record<string, unknown>): ServerMessage {
  switch (data.type) {
    case "registered":
      return data as unknown as Registered;
    case "error":
      return data as unknown as RegistrationError;
    case "command":
      return data as unknown as ForwardCommand;
    default:
      throw new Error(`Unknown message type: ${data.type}`);
  }
}

export function prose(speaker: string, text: string, opts?: {
  blocks?: ContentBlock[];
  priority?: "critical" | "normal" | "ambient";
  locale?: string;
}): ProseMessage {
  return {
    type: "prose",
    seq: 0,
    speaker,
    text,
    hints: [],
    contentBlocks: opts?.blocks ?? null,
    priority: opts?.priority ?? "normal",
    locale: opts?.locale ?? "en",
  };
}
