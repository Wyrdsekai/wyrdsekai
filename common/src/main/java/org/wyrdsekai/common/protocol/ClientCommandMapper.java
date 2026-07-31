package org.wyrdsekai.common.protocol;

/**
 * Maps a parsed WORLD command ({@link CommandParser.ParsedCommand}) to the
 * typed {@link C2SMessage} the zone expects — the single source of truth for
 * "what a client verb means on the wire".
 *
 * <p>Why this exists: the SSH/CLI client (cli/InputHandler) parses a rich verb
 * set and sends dedicated C2S envelopes (e.g. {@code map} → {@link
 * C2SMessage.MapRequest}, not a generic {@link C2SMessage.Command}). The phone
 * terminals send anything they lack a typed envelope for as a generic Command.
 * Rather than duplicate the whole verb table into every client, the server runs
 * a generic Command it doesn't recognise back through {@link CommandParser} and
 * this mapper, then re-dispatches the resulting typed message — so
 * {@code map/where/nearby/rooms/path/exits/tell/…} behave identically on the
 * phone as over SSH (2026-07-24). The CLI InputHandler mirrors these exact
 * mappings; keep the two in sync (or migrate InputHandler onto this).
 *
 * <p>Only WORLD/room commands are mapped. Client-local concerns (quit, logout,
 * sessions, key, slash commands, aliases, afk, brief, hint-select, the actions
 * menu) return {@code null} — the caller handles or ignores them. Commands that
 * are ALREADY generic {@link C2SMessage.Command} on the SSH path (office,
 * score) also return {@code null}: they need no re-mapping (the server's
 * command switch already handles them, identically for every client).
 */
public final class ClientCommandMapper {

    private ClientCommandMapper() {}

    /**
     * @param parsed        the parsed command (may be null → returns null)
     * @param id            correlation id to stamp on the produced message
     * @param currentRoomId the sender's current room (used as the Say/Look roomId)
     * @return the typed C2S message, or {@code null} if this command has no
     *         world-C2S mapping (client-local, unknown, or already a generic Command)
     */
    public static C2SMessage toWorldC2S(CommandParser.ParsedCommand parsed,
                                        String id, String currentRoomId) {
        if (parsed == null) return null;
        return switch (parsed) {
            // ── navigation / map family (dedicated MapRequest envelope) ──
            case CommandParser.ParsedCommand.MapCommand mc ->
                new C2SMessage.MapRequest(id, "map", mc.radius(), null);
            case CommandParser.ParsedCommand.Where w ->
                new C2SMessage.MapRequest(id, "where", 0, null);
            case CommandParser.ParsedCommand.Nearby n ->
                new C2SMessage.MapRequest(id, "nearby", 1, null);
            case CommandParser.ParsedCommand.Rooms r ->
                new C2SMessage.MapRequest(id, "rooms", 0, null);
            case CommandParser.ParsedCommand.Path p ->
                new C2SMessage.MapRequest(id, "path", 0, p.targetRoom());
            case CommandParser.ParsedCommand.Exits e ->
                new C2SMessage.MapRequest(id, "exits", 0, null);

            // ── typed world verbs ──
            case CommandParser.ParsedCommand.Go go ->
                new C2SMessage.Go(id, currentRoomId, go.direction());
            case CommandParser.ParsedCommand.Look l ->
                new C2SMessage.Look(id, currentRoomId);
            case CommandParser.ParsedCommand.Take t ->
                new C2SMessage.Take(id, currentRoomId, t.objectName());
            case CommandParser.ParsedCommand.Drop d ->
                new C2SMessage.Drop(id, currentRoomId, d.objectName());
            case CommandParser.ParsedCommand.Use u ->
                new C2SMessage.Use(id, currentRoomId, u.objectName(), u.target());
            case CommandParser.ParsedCommand.Examine ex ->
                new C2SMessage.Examine(id, currentRoomId, ex.target());
            case CommandParser.ParsedCommand.Rename rn ->
                new C2SMessage.Rename(id, rn.target(), rn.newName());

            // ── social / say-shaped (server parses the prefix) ──
            case CommandParser.ParsedCommand.Say say ->
                new C2SMessage.Say(id, currentRoomId, say.text());
            case CommandParser.ParsedCommand.Emote em ->
                new C2SMessage.Say(id, currentRoomId, ":" + em.text());
            case CommandParser.ParsedCommand.Tell tell ->
                new C2SMessage.Say(id, currentRoomId, "tell " + tell.targetName() + " " + tell.text());
            case CommandParser.ParsedCommand.Whisper w ->
                new C2SMessage.Say(id, currentRoomId, ">" + w.target() + " " + w.text());
            case CommandParser.ParsedCommand.Describe de ->
                new C2SMessage.Say(id, currentRoomId, "@describe " + de.target() + "=" + de.text());
            case CommandParser.ParsedCommand.Give g ->
                new C2SMessage.Say(id, currentRoomId, "give " + g.objectName() + " to " + g.targetName());
            case CommandParser.ParsedCommand.Shout sh ->
                new C2SMessage.Say(id, currentRoomId, "[shout] " + sh.text());
            case CommandParser.ParsedCommand.Reply re ->
                new C2SMessage.Say(id, currentRoomId, re.text());

            // Everything else (Quit/Logout/Sessions/Key/Slash/Alias/Unalias/
            // Afk/Brief/HintSelect/Office/Score/Unknown/ward+plan verbs) → no
            // world-C2S mapping here.
            default -> null;
        };
    }
}
