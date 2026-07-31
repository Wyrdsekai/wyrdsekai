"""Zone Bridge message types."""

from __future__ import annotations
from dataclasses import dataclass, field, asdict
from typing import Any


@dataclass
class Register:
    namespace: str
    secret: str | None = None
    type: str = field(default="register", init=False)

    def to_dict(self) -> dict:
        d = {"type": self.type, "namespace": self.namespace}
        if self.secret is not None:
            d["secret"] = self.secret
        return d


@dataclass
class Registered:
    namespace: str
    type: str = field(default="registered", init=False)

    @classmethod
    def from_dict(cls, d: dict) -> Registered:
        return cls(namespace=d["namespace"])


@dataclass
class RegistrationError:
    namespace: str
    reason: str
    type: str = field(default="error", init=False)

    @classmethod
    def from_dict(cls, d: dict) -> RegistrationError:
        return cls(namespace=d.get("namespace", ""), reason=d.get("reason", ""))


@dataclass
class ForwardCommand:
    request_id: str
    player_id: str
    action: str
    args: list[str] = field(default_factory=list)
    payload: dict[str, Any] = field(default_factory=dict)
    type: str = field(default="command", init=False)

    @classmethod
    def from_dict(cls, d: dict) -> ForwardCommand:
        return cls(
            request_id=d["requestId"],
            player_id=d["playerId"],
            action=d["action"],
            args=d.get("args", []),
            payload=d.get("payload", {}),
        )


@dataclass
class ContentBlock:
    format: str
    data: dict[str, Any]
    fallback: str

    def to_dict(self) -> dict:
        return {"format": self.format, "data": self.data, "fallback": self.fallback}


@dataclass
class ProseMessage:
    speaker: str
    text: str
    hints: list[dict] = field(default_factory=list)
    content_blocks: list[ContentBlock] | None = None
    priority: str = "normal"
    locale: str = "en"

    def to_dict(self) -> dict:
        d: dict[str, Any] = {
            "type": "prose",
            "seq": 0,
            "speaker": self.speaker,
            "text": self.text,
            "hints": self.hints,
            "priority": self.priority,
            "locale": self.locale,
        }
        if self.content_blocks:
            d["contentBlocks"] = [b.to_dict() for b in self.content_blocks]
        else:
            d["contentBlocks"] = None
        return d


@dataclass
class CommandResponse:
    request_id: str
    player_id: str
    messages: list[dict]
    type: str = field(default="response", init=False)

    def to_dict(self) -> dict:
        return {
            "type": self.type,
            "requestId": self.request_id,
            "playerId": self.player_id,
            "messages": self.messages,
        }


@dataclass
class Broadcast:
    messages: list[dict]
    room_id: str | None = None
    type: str = field(default="broadcast", init=False)

    def to_dict(self) -> dict:
        return {
            "type": self.type,
            "roomId": self.room_id,
            "messages": self.messages,
        }


def parse_server_message(data: dict) -> Register | Registered | RegistrationError | ForwardCommand:
    """Parse a server-to-client message."""
    msg_type = data.get("type", "")
    if msg_type == "registered":
        return Registered.from_dict(data)
    elif msg_type == "error":
        return RegistrationError.from_dict(data)
    elif msg_type == "command":
        return ForwardCommand.from_dict(data)
    else:
        raise ValueError(f"Unknown message type: {msg_type}")
