#!/usr/bin/env python3
"""Minimal zone service example.

Start Wyrdsekai, then run:
    pip install websockets
    python examples/hello_zone.py

Players can now type: hello.greet, hello.status, hello.echo anything here
"""

import logging
from wyrdsekai_zone import ZoneService, CommandContext

logging.basicConfig(level=logging.INFO)

service = ZoneService(
    namespace="hello",
    url="ws://localhost:7070/ws/zone",
)


@service.on_action("greet")
async def handle_greet(ctx: CommandContext):
    await ctx.respond(f"Hello, {ctx.player_id}! Welcome to the hello zone.")


@service.on_action("status")
async def handle_status(ctx: CommandContext):
    await ctx.respond("All systems operational. The hello zone is running.")


@service.on_action("echo")
async def handle_echo(ctx: CommandContext):
    text = " ".join(ctx.args) if ctx.args else "(nothing to echo)"
    await ctx.respond(f"Echo: {text}")


@service.on_default
async def handle_unknown(ctx: CommandContext):
    await ctx.respond(
        f"Unknown action '{ctx.action}'. Try: hello.greet, hello.status, hello.echo <text>"
    )


if __name__ == "__main__":
    service.run()
