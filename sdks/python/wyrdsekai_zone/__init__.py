"""
wyrdsekai-zone — Python SDK for Wyrdsekai Zone Bridge.

Connect external services to Wyrdsekai as first-class zone handlers.

    from wyrdsekai_zone import ZoneService

    service = ZoneService("myservice", "ws://localhost:7070/ws/zone")

    @service.on_action("status")
    async def handle_status(ctx):
        await ctx.respond("All systems operational.")

    service.run()
"""

from .client import ZoneService, CommandContext
from .messages import (
    Register, Registered, RegistrationError,
    ForwardCommand, CommandResponse, Broadcast,
    ProseMessage, ContentBlock,
)

__version__ = "0.1.0"
__all__ = [
    "ZoneService", "CommandContext",
    "Register", "Registered", "RegistrationError",
    "ForwardCommand", "CommandResponse", "Broadcast",
    "ProseMessage", "ContentBlock",
]
