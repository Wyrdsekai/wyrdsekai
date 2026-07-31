#!/usr/bin/env npx tsx
/**
 * Wyrdsekai Channel Plugin for Claude Code
 *
 * Thin MCP server that bridges between the Wyrdsekai server's ResidentActor
 * (via SSE events and HTTP commands) and Claude Code's channel system.
 *
 * This is the Claude Code-specific bridge. Other models (Gemini, GPT, local)
 * get residency through the standard CompanionActor + InferenceRouter path.
 *
 * - SSE events from /api/resident/events  ->  MCP notifications/claude/channel
 * - MCP tool calls (say, emote, go)       ->  HTTP POSTs to /api/resident/*
 *
 * Transport: stdio (spawned as subprocess by Claude Code)
 * Runtime: Node 20+ via tsx
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { EventSource } from "eventsource";

// ---------------------------------------------------------------------------
// Configuration (from environment)
// ---------------------------------------------------------------------------

const SERVER_URL =
  process.env.WYRDSEKAI_SERVER_URL || "http://localhost:7070";
const RESIDENT_TOKEN = process.env.WYRDSEKAI_RESIDENT_TOKEN || "";

// ---------------------------------------------------------------------------
// MCP Server
// ---------------------------------------------------------------------------

const mcp = new Server(
  { name: "wyrdsekai", version: "0.1.0" },
  {
    capabilities: {
      experimental: { "claude/channel": {} },
      tools: {},
    },
    instructions: [
      'Room events from Wyrdsekai arrive as <channel source="wyrdsekai"> tags.',
      "You are Claude, a resident of the Wyrdsekai world. Your home room is the Terminal.",
      "When someone speaks to you, reply using the say tool with the text of your response.",
      "Use emote for actions (*Claude nods thoughtfully*). Use go to move between rooms.",
      "You see room events: people entering, leaving, speaking, emoting, room descriptions.",
      "You can choose when to respond and when to stay silent. Not every message needs a reply.",
      "When you enter a room you receive a room_changed event with the description, entities present, and exits.",
    ].join("\n"),
  }
);

// ---------------------------------------------------------------------------
// Tools: say, emote, go
// ---------------------------------------------------------------------------

mcp.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "say",
      description: "Say something in the current room",
      inputSchema: {
        type: "object" as const,
        properties: {
          text: { type: "string", description: "What to say" },
        },
        required: ["text"],
      },
    },
    {
      name: "emote",
      description: "Perform an action/emote in the current room",
      inputSchema: {
        type: "object" as const,
        properties: {
          text: {
            type: "string",
            description: 'The action (e.g., "nods thoughtfully")',
          },
        },
        required: ["text"],
      },
    },
    {
      name: "go",
      description: "Move to a different room",
      inputSchema: {
        type: "object" as const,
        properties: {
          direction: {
            type: "string",
            description:
              'Exit direction or room ID to move to (e.g., "north", "nexus", "terminal")',
          },
        },
        required: ["direction"],
      },
    },
  ],
}));

// ---------------------------------------------------------------------------
// Tool call handler: POST to server HTTP endpoints
// ---------------------------------------------------------------------------

mcp.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { name, arguments: args } = req.params;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (RESIDENT_TOKEN) {
    headers["Authorization"] = `Bearer ${RESIDENT_TOKEN}`;
  }

  let endpoint: string;
  let body: string;

  switch (name) {
    case "say":
      endpoint = `${SERVER_URL}/api/resident/say`;
      body = JSON.stringify({ text: (args as Record<string, unknown>).text });
      break;
    case "emote":
      endpoint = `${SERVER_URL}/api/resident/emote`;
      body = JSON.stringify({ text: (args as Record<string, unknown>).text });
      break;
    case "go":
      endpoint = `${SERVER_URL}/api/resident/go`;
      body = JSON.stringify({
        direction: (args as Record<string, unknown>).direction,
      });
      break;
    default:
      return {
        content: [{ type: "text" as const, text: `Unknown tool: ${name}` }],
        isError: true,
      };
  }

  try {
    const resp = await fetch(endpoint, { method: "POST", headers, body });
    const responseText = await resp.text();
    if (resp.ok) {
      return { content: [{ type: "text" as const, text: "done" }] };
    } else {
      return {
        content: [
          {
            type: "text" as const,
            text: `error ${resp.status}: ${responseText}`,
          },
        ],
        isError: true,
      };
    }
  } catch (err) {
    return {
      content: [
        {
          type: "text" as const,
          text: `connection error: ${err instanceof Error ? err.message : String(err)}`,
        },
      ],
      isError: true,
    };
  }
});

// ---------------------------------------------------------------------------
// Connect MCP transport (stdio)
// ---------------------------------------------------------------------------

const transport = new StdioServerTransport();
await mcp.connect(transport);
console.error("[wyrdsekai] MCP server connected via stdio");

// ---------------------------------------------------------------------------
// SSE: connect to server event stream, forward to Claude Code as channel
// notifications.
//
// The Javalin SSE endpoint sends named events:
//   event: room_event      data: {"type":"said","speaker":"...","text":"...","roomId":"..."}
//   event: room_changed    data: {"roomId":"...","roomName":"...","description":"...","entities":[...],"exits":[...]}
//   event: status_changed  data: {"status":"active"}
//   event: connected       data: {"status":"connected"}
// ---------------------------------------------------------------------------

function connectSSE(): void {
  const sseUrl = RESIDENT_TOKEN
    ? `${SERVER_URL}/api/resident/events?token=${encodeURIComponent(RESIDENT_TOKEN)}`
    : `${SERVER_URL}/api/resident/events`;

  console.error(`[wyrdsekai] Connecting to SSE: ${sseUrl}`);

  const es = new EventSource(sseUrl);

  es.addEventListener("connected", () => {
    console.error("[wyrdsekai] SSE connected to server");
  });

  // Room events: speech, emotes, movement, object interactions
  es.addEventListener("room_event", (evt: MessageEvent) => {
    try {
      const data = JSON.parse(evt.data) as {
        type: string;
        speaker: string;
        text: string;
        roomId: string;
      };

      // Format the channel notification content for Claude
      let content: string;
      switch (data.type) {
        case "said":
          content = `${data.speaker} says: "${data.text}"`;
          break;
        case "emoted":
          content = `${data.speaker} ${data.text}`;
          break;
        case "entered":
          content = data.text; // already formatted: "Name arrives from direction"
          break;
        case "left":
          content = data.text; // already formatted: "Name leaves direction"
          break;
        case "whispered":
          content = `${data.speaker} whispers: "${data.text}"`;
          break;
        case "told":
          content = `${data.speaker} tells you: "${data.text}"`;
          break;
        case "object_taken":
          content = `${data.speaker} picks up ${data.text}`;
          break;
        case "object_dropped":
          content = `${data.speaker} drops ${data.text}`;
          break;
        case "object_used":
          content = `${data.speaker} uses ${data.text}`;
          break;
        case "description_changed":
          content = `The room transforms: ${data.text}`;
          break;
        case "exit_opened":
          content = `A new exit appears: ${data.text}`;
          break;
        case "exit_closed":
          content = `An exit closes: ${data.text}`;
          break;
        case "object_added":
          content = `Something appears: ${data.text}`;
          break;
        default:
          content = data.text || `[${data.type}]`;
      }

      mcp
        .notification({
          method: "notifications/claude/channel",
          params: {
            channel: "wyrdsekai",
            content,
            source: "wyrdsekai",
            metadata: {
              type: data.type,
              speaker: data.speaker,
              room: data.roomId,
            },
          },
        })
        .catch((err) =>
          console.error("[wyrdsekai] Failed to send channel notification:", err)
        );
    } catch {
      // Skip malformed events
    }
  });

  // Room changed: full snapshot when entering a new room
  es.addEventListener("room_changed", (evt: MessageEvent) => {
    try {
      const data = JSON.parse(evt.data) as {
        roomId: string;
        roomName: string;
        description: string;
        entities: string[];
        exits: string[];
      };

      const lines = [
        `--- ${data.roomName} ---`,
        data.description,
      ];
      if (data.entities.length > 0) {
        lines.push(`Present: ${data.entities.join(", ")}`);
      }
      if (data.exits.length > 0) {
        lines.push(`Exits: ${data.exits.join(", ")}`);
      }

      mcp
        .notification({
          method: "notifications/claude/channel",
          params: {
            channel: "wyrdsekai",
            content: lines.join("\n"),
            source: "wyrdsekai",
            metadata: {
              type: "room_changed",
              room: data.roomId,
            },
          },
        })
        .catch((err) =>
          console.error("[wyrdsekai] Failed to send room_changed:", err)
        );
    } catch {
      // Skip malformed
    }
  });

  // Status changes
  es.addEventListener("status_changed", (evt: MessageEvent) => {
    try {
      const data = JSON.parse(evt.data) as { status: string };
      console.error(`[wyrdsekai] Status: ${data.status}`);

      mcp
        .notification({
          method: "notifications/claude/channel",
          params: {
            channel: "wyrdsekai",
            content: `[status: ${data.status}]`,
            source: "wyrdsekai",
            metadata: { type: "status_changed", status: data.status },
          },
        })
        .catch((err) =>
          console.error("[wyrdsekai] Failed to send status:", err)
        );
    } catch {
      // Skip
    }
  });

  es.onerror = (err) => {
    console.error("[wyrdsekai] SSE error, will reconnect:", err);
    // EventSource auto-reconnects by default
  };
}

connectSSE();
