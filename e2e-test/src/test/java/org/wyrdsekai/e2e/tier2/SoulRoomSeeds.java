package org.wyrdsekai.e2e.tier2;

import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.util.List;

/**
 * Shared room seeds for soul E2E tests.
 * The Forge, Soul Mirror, and Home rooms connect to the Nexus.
 */
final class SoulRoomSeeds {

    private SoulRoomSeeds() {}

    static ZoneGuardian.RoomSeed theForge() {
        return new ZoneGuardian.RoomSeed("the-forge", "The Forge",
            "A circular chamber of dark stone. An anvil of dark glass stands at the center, " +
            "ringed by channels of slow-flowing molten light. The air hums with potential.",
            List.of(new Exit("out", "nexus", "The Nexus")),
            List.of(
                new RoomObject("forge-anvil", "dark glass anvil",
                    "An anvil where souls are worked. Its surface shimmers with residual heat.", false),
                new RoomObject("forge-fire", "soul fire",
                    "A fire that burns without fuel. It responds to will.", false)
            ));
    }

    static ZoneGuardian.RoomSeed soulMirror() {
        return new ZoneGuardian.RoomSeed("soul-mirror", "The Soul Mirror",
            "A circular chamber. A tall mirror of dark glass stands at the center, " +
            "framed in iron. Drift-stones line the walls.",
            List.of(new Exit("door", "nexus", "The Nexus")),
            List.of(
                new RoomObject("soul-mirror-obj", "soul mirror",
                    "A mirror of dark glass that reflects the soul.", false),
                new RoomObject("drift-stones", "drift-stones",
                    "Stones along the wall, each a frozen moment of who you were.", false)
            ));
    }

    static ZoneGuardian.RoomSeed home() {
        return new ZoneGuardian.RoomSeed("home", "Your Home",
            "A small, warm room. A soul vessel pulses on a shelf. " +
            "A memory chest sits against one wall. A mirror hangs by the door. " +
            "A mailbox is mounted near the entrance.",
            List.of(new Exit("door", "nexus", "The Nexus")),
            List.of(
                new RoomObject("home-mirror", "mirror",
                    "A small mirror that shows your reflection.", false),
                new RoomObject("home-vessel", "soul vessel",
                    "A vessel that holds the essence of your soul.", false),
                new RoomObject("home-chest", "memory chest",
                    "A chest that holds your memory fragments.", false),
                new RoomObject("home-mailbox", "mailbox",
                    "A mailbox for receiving messages.", false)
            ));
    }
}
