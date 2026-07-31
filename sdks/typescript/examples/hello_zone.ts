/**
 * Minimal zone service example.
 *
 * Start Wyrdsekai, then run:
 *   npm install
 *   npx tsx examples/hello_zone.ts
 *
 * Players can now type: hello.greet, hello.status, hello.echo anything here
 */

import { ZoneService } from "../src/index.js";

const service = new ZoneService({
  namespace: "hello",
  url: "ws://localhost:7070/ws/zone",
});

service.onAction("greet", async (ctx) => {
  await ctx.respond(`Hello, ${ctx.playerId}! Welcome to the hello zone.`);
});

service.onAction("status", async (ctx) => {
  await ctx.respond("All systems operational. The hello zone is running.");
});

service.onAction("echo", async (ctx) => {
  const text = ctx.args.length > 0 ? ctx.args.join(" ") : "(nothing to echo)";
  await ctx.respond(`Echo: ${text}`);
});

service.onDefault(async (ctx) => {
  await ctx.respond(
    `Unknown action '${ctx.action}'. Try: hello.greet, hello.status, hello.echo <text>`
  );
});

service.run();
