package org.wyrdsekai.core.room;

import org.wyrdsekai.core.agent.EntityRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Function;

/**
 * Who is where, for the map — the same answer on every surface.
 *
 * <p>Public rooms list everyone in them by name; anyone in a private room is named in a
 * footer by the kind of place ("at home", "in their Study", "resting"), never the room.
 * The viewer is "you". Built once for the web client in 0.3.2 and left out of the ssh and
 * telnet map handlers (found 2026-09-14), so it lives here now and every handler calls it.</p>
 */
public final class MapOccupants {

    /** The key {@link ZoneTopology#renderTextMap} asks for the footer with. */
    public static final String ELSEWHERE = "*elsewhere*";

    private MapOccupants() {}

    /** Room id → names standing there (public rooms), plus the footer under {@link #ELSEWHERE}. */
    public static Function<String, String> forViewer(String viewerId) {
        var registry = EntityRegistry.get();
        if (registry == null) return id -> null;
        var publicNames = new HashMap<String, String>();
        var elsewhere = new ArrayList<String>();
        for (var e : registry.occupantsByRoom().entrySet()) {
            var kind = RoomPrivacy.whereabouts(e.getKey());
            var names = new ArrayList<String>();
            for (var o : e.getValue()) {
                if (o.entityId().equals(viewerId)) { names.add(0, "you"); continue; }
                if (kind == null) names.add(o.name()); else elsewhere.add(o.name() + " (" + kind + ")");
            }
            if (kind == null && !names.isEmpty()) publicNames.put(e.getKey(), String.join(", ", names));
        }
        var footer = elsewhere.isEmpty() ? null : "Elsewhere: " + String.join(", ", elsewhere);
        return id -> ELSEWHERE.equals(id) ? footer : publicNames.get(id);
    }

    /** {@code where <name>}: a public room by name, a private one by kind, or nowhere here. */
    public static String whereIs(String name, ZoneTopology topo) {
        var registry = EntityRegistry.get();
        if (registry == null) return "Nobody is registered here.";
        var id = registry.findByName(name);
        if (id.isEmpty()) return "Nobody here goes by '" + name + "'.";
        var room = registry.roomOf(id.get());
        var who = registry.nameOf(id.get()).orElse(name);
        if (room.isEmpty()) return who + " is not in any room right now.";
        var kind = RoomPrivacy.whereabouts(room.get());
        if (kind != null) return who + " is " + kind + ".";
        var roomName = topo == null ? room.get()
            : topo.room(room.get()).map(ZoneTopology.RoomNode::name).orElse(room.get());
        return who + " is in " + roomName + ".";
    }
}
