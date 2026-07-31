/** Zone Bridge WebSocket client with auto-reconnect and action routing. */

import WebSocket from "ws";
import {
  Register, Registered, RegistrationError, ForwardCommand,
  CommandResponse, Broadcast, ContentBlock, S2CMessage,
  parseServerMessage, prose,
} from "./messages.js";

export interface CommandContext {
  command: ForwardCommand;
  action: string;
  args: string[];
  payload: Record<string, unknown>;
  playerId: string;
  requestId: string;
  respond(text: string, opts?: {
    speaker?: string;
    blocks?: ContentBlock[];
    priority?: "critical" | "normal" | "ambient";
  }): Promise<void>;
  respondRaw(messages: S2CMessage[]): Promise<void>;
  error(message: string, code?: string): Promise<void>;
}

export type ActionHandler = (ctx: CommandContext) => Promise<void>;

export interface ZoneServiceOptions {
  namespace: string;
  url?: string;
  secret?: string;
}

export class ZoneService {
  readonly namespace: string;
  readonly url: string;
  private secret?: string;
  private handlers = new Map<string, ActionHandler>();
  private defaultHandler?: ActionHandler;
  private ws?: WebSocket;
  private connected = false;
  private reconnectDelay = 1000;
  private stopping = false;

  constructor(opts: ZoneServiceOptions) {
    this.namespace = opts.namespace;
    this.url = opts.url ?? "ws://localhost:7070/ws/zone";
    this.secret = opts.secret;
  }

  onAction(action: string, handler: ActionHandler): this {
    this.handlers.set(action, handler);
    return this;
  }

  onDefault(handler: ActionHandler): this {
    this.defaultHandler = handler;
    return this;
  }

  async broadcast(text: string, opts?: {
    speaker?: string;
    roomId?: string;
    blocks?: ContentBlock[];
  }): Promise<void> {
    const msg = prose(opts?.speaker ?? this.namespace, text, { blocks: opts?.blocks });
    await this.send({ type: "broadcast", roomId: opts?.roomId, messages: [msg] });
  }

  async broadcastRaw(messages: S2CMessage[], roomId?: string): Promise<void> {
    await this.send({ type: "broadcast", roomId, messages });
  }

  async run(): Promise<void> {
    const shutdown = () => { this.stopping = true; };
    process.on("SIGINT", shutdown);
    process.on("SIGTERM", shutdown);

    while (!this.stopping) {
      try {
        await this.connectAndServe();
      } catch (e) {
        console.warn(`[${this.namespace}] Connection lost:`, (e as Error).message);
      }

      if (this.stopping) break;

      const delay = Math.min(this.reconnectDelay, 30000);
      console.info(`[${this.namespace}] Reconnecting in ${delay}ms...`);
      await sleep(delay);
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000);
    }

    console.info(`[${this.namespace}] Shut down.`);
  }

  // ── Internal ──

  private connectAndServe(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;

      ws.on("open", () => {
        const reg: Register = { type: "register", namespace: this.namespace };
        if (this.secret) reg.secret = this.secret;
        ws.send(JSON.stringify(reg));
      });

      ws.on("message", async (raw) => {
        try {
          const data = JSON.parse(raw.toString());
          const msg = parseServerMessage(data);

          if (msg.type === "registered") {
            this.connected = true;
            this.reconnectDelay = 1000;
            console.info(`[${this.namespace}] Registered at ${this.url}`);
          } else if (msg.type === "error") {
            const err = msg as RegistrationError;
            reject(new Error(`Registration failed: ${err.reason}`));
            ws.close();
          } else if (msg.type === "command") {
            await this.dispatch(msg as ForwardCommand);
          }
        } catch (e) {
          console.error(`[${this.namespace}] Error:`, (e as Error).message);
        }
      });

      ws.on("close", () => {
        this.connected = false;
        this.ws = undefined;
        resolve();
      });

      ws.on("error", (err) => {
        this.connected = false;
        reject(err);
      });
    });
  }

  private async dispatch(cmd: ForwardCommand): Promise<void> {
    const ctx = this.makeContext(cmd);
    const handler = this.handlers.get(cmd.action) ?? this.defaultHandler;
    if (handler) {
      try {
        await handler(ctx);
      } catch (e) {
        console.error(`[${this.namespace}] Handler error:`, (e as Error).message);
        await ctx.error(`Internal error: ${(e as Error).message}`);
      }
    } else {
      await ctx.error(`Unknown action: ${cmd.action}`, "unknown_action");
    }
  }

  private makeContext(cmd: ForwardCommand): CommandContext {
    const svc = this;
    return {
      command: cmd,
      action: cmd.action,
      args: cmd.args,
      payload: cmd.payload,
      playerId: cmd.playerId,
      requestId: cmd.requestId,
      async respond(text, opts) {
        const msg = prose(opts?.speaker ?? svc.namespace, text, {
          blocks: opts?.blocks,
          priority: opts?.priority,
        });
        await svc.send({
          type: "response",
          requestId: cmd.requestId,
          playerId: cmd.playerId,
          messages: [msg],
        });
      },
      async respondRaw(messages) {
        await svc.send({
          type: "response",
          requestId: cmd.requestId,
          playerId: cmd.playerId,
          messages,
        });
      },
      async error(message, code = "zone_error") {
        await svc.send({
          type: "response",
          requestId: cmd.requestId,
          playerId: cmd.playerId,
          messages: [{
            type: "error",
            seq: 0,
            code,
            message,
            requestId: cmd.requestId,
          }],
        });
      },
    };
  }

  private async send(data: Record<string, unknown>): Promise<void> {
    if (this.ws && this.connected) {
      this.ws.send(JSON.stringify(data));
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
