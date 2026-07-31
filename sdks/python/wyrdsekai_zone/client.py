"""Zone Bridge WebSocket client with auto-reconnect and decorator routing."""

from __future__ import annotations

import asyncio
import json
import logging
import signal
from typing import Any, Callable, Awaitable

from .messages import (
    Register, Registered, RegistrationError, ForwardCommand,
    CommandResponse, Broadcast, ProseMessage, ContentBlock,
    parse_server_message,
)

log = logging.getLogger("wyrdsekai_zone")


class CommandContext:
    """Context passed to action handlers. Provides helpers for responding."""

    def __init__(self, command: ForwardCommand, service: ZoneService):
        self.command = command
        self.action = command.action
        self.args = command.args
        self.payload = command.payload
        self.player_id = command.player_id
        self.request_id = command.request_id
        self._service = service

    async def respond(self, text: str, speaker: str | None = None,
                      blocks: list[ContentBlock] | None = None,
                      priority: str = "normal"):
        """Send a prose response to the player who issued the command."""
        msg = ProseMessage(
            speaker=speaker or self._service.namespace,
            text=text,
            content_blocks=blocks,
            priority=priority,
        )
        resp = CommandResponse(
            request_id=self.request_id,
            player_id=self.player_id,
            messages=[msg.to_dict()],
        )
        await self._service._send(resp.to_dict())

    async def respond_raw(self, messages: list[dict]):
        """Send raw S2C messages as response."""
        resp = CommandResponse(
            request_id=self.request_id,
            player_id=self.player_id,
            messages=messages,
        )
        await self._service._send(resp.to_dict())

    async def error(self, message: str, code: str = "zone_error"):
        """Send an error response."""
        await self._service._send(CommandResponse(
            request_id=self.request_id,
            player_id=self.player_id,
            messages=[{
                "type": "error",
                "seq": 0,
                "code": code,
                "message": message,
                "requestId": self.request_id,
            }],
        ).to_dict())


ActionHandler = Callable[[CommandContext], Awaitable[None]]


class ZoneService:
    """Wyrdsekai Zone Bridge client.

    Usage:
        service = ZoneService("myservice", "ws://localhost:7070/ws/zone")

        @service.on_action("status")
        async def handle_status(ctx):
            await ctx.respond("All systems go.")

        service.run()
    """

    def __init__(self, namespace: str, url: str = "ws://localhost:7070/ws/zone",
                 secret: str | None = None):
        self.namespace = namespace
        self.url = url
        self.secret = secret
        self._handlers: dict[str, ActionHandler] = {}
        self._default_handler: ActionHandler | None = None
        self._ws = None
        self._connected = False
        self._reconnect_delay = 1.0

    def on_action(self, action: str):
        """Decorator to register a handler for a specific action.

            @service.on_action("status")
            async def handle_status(ctx: CommandContext):
                await ctx.respond("OK")
        """
        def decorator(fn: ActionHandler) -> ActionHandler:
            self._handlers[action] = fn
            return fn
        return decorator

    def on_default(self, fn: ActionHandler) -> ActionHandler:
        """Decorator for the fallback handler (unmatched actions)."""
        self._default_handler = fn
        return fn

    async def broadcast(self, text: str, speaker: str | None = None,
                        room_id: str | None = None,
                        blocks: list[ContentBlock] | None = None):
        """Push an unsolicited message to all zone players."""
        msg = ProseMessage(
            speaker=speaker or self.namespace,
            text=text,
            content_blocks=blocks,
        )
        await self._send(Broadcast(
            messages=[msg.to_dict()],
            room_id=room_id,
        ).to_dict())

    async def broadcast_raw(self, messages: list[dict], room_id: str | None = None):
        """Push raw S2C messages to zone players."""
        await self._send(Broadcast(messages=messages, room_id=room_id).to_dict())

    def run(self):
        """Start the service (blocking). Handles SIGINT/SIGTERM for clean shutdown."""
        asyncio.run(self._run_forever())

    async def start(self):
        """Start the service (non-blocking, for use inside existing event loops)."""
        await self._run_forever()

    # ── Internal ──

    async def _run_forever(self):
        loop = asyncio.get_event_loop()
        stop = asyncio.Event()

        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop.set)
            except NotImplementedError:
                pass  # Windows

        while not stop.is_set():
            try:
                await self._connect_and_serve(stop)
            except Exception as e:
                log.warning("Connection lost: %s", e)

            if stop.is_set():
                break

            delay = min(self._reconnect_delay, 30.0)
            log.info("Reconnecting in %.1fs...", delay)
            try:
                await asyncio.wait_for(stop.wait(), timeout=delay)
                break  # stop was set during wait
            except asyncio.TimeoutError:
                pass
            self._reconnect_delay = min(self._reconnect_delay * 2, 30.0)

        log.info("Zone service '%s' shut down.", self.namespace)

    async def _connect_and_serve(self, stop: asyncio.Event):
        try:
            import websockets
        except ImportError:
            raise ImportError(
                "websockets package required: pip install websockets"
            )

        async with websockets.connect(self.url) as ws:
            self._ws = ws

            # Register
            await ws.send(json.dumps(
                Register(namespace=self.namespace, secret=self.secret).to_dict()
            ))
            raw = await ws.recv()
            msg = parse_server_message(json.loads(raw))

            if isinstance(msg, RegistrationError):
                raise ConnectionError(f"Registration failed: {msg.reason}")
            if not isinstance(msg, Registered):
                raise ConnectionError(f"Unexpected response: {msg}")

            self._connected = True
            self._reconnect_delay = 1.0  # reset on successful connect
            log.info("Registered as '%s' at %s", self.namespace, self.url)

            # Serve commands
            async for raw in ws:
                if stop.is_set():
                    break
                try:
                    data = json.loads(raw)
                    msg = parse_server_message(data)
                    if isinstance(msg, ForwardCommand):
                        await self._dispatch(msg)
                except Exception as e:
                    log.error("Error handling message: %s", e)

        self._connected = False
        self._ws = None

    async def _dispatch(self, cmd: ForwardCommand):
        ctx = CommandContext(cmd, self)
        handler = self._handlers.get(cmd.action, self._default_handler)
        if handler:
            try:
                await handler(ctx)
            except Exception as e:
                log.error("Handler error for '%s': %s", cmd.action, e)
                await ctx.error(f"Internal error: {e}")
        else:
            await ctx.error(f"Unknown action: {cmd.action}", code="unknown_action")

    async def _send(self, data: dict):
        if self._ws and self._connected:
            await self._ws.send(json.dumps(data))
