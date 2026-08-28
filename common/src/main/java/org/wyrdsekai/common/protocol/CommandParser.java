package org.wyrdsekai.common.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Shared MUD command parser. Used by CLI InputHandler and Telnet adapter.
 * Parses user input text into structured ParsedCommand variants.
 */
public final class CommandParser {

    private static final List<String> DIRECTIONS = List.of(
        "north", "south", "east", "west", "up", "down",
        "n", "s", "e", "w", "u", "d",
        "northeast", "northwest", "southeast", "southwest",
        "ne", "nw", "se", "sw",
        "in", "out"
    );

    private CommandParser() {}

    public sealed interface ParsedCommand {
        record Say(String text) implements ParsedCommand {}
        record Tell(String targetName, String text) implements ParsedCommand {}
        record Go(String direction) implements ParsedCommand {}
        record Look() implements ParsedCommand {}
        record Take(String objectName) implements ParsedCommand {}
        record Drop(String objectName) implements ParsedCommand {}
        /**
         * Take an item out of the world for good — the counterpart {@code drop} never was.
         *
         * <p>Dropping leaves the thing in the room, so a world accumulates: two objects
         * called {@code codex} in a Nexus and no way to be rid of either (household node,
         * 2026-08-20). Retiring is soft — the backing script moves aside and can be put
         * back — because these are things the companion made.
         */
        record Retire(String objectName) implements ParsedCommand {}
        record Use(String objectName, String target) implements ParsedCommand {}
        record HintSelect(int index) implements ParsedCommand {}
        record Emote(String text) implements ParsedCommand {}
        record Whisper(String target, String text) implements ParsedCommand {}
        record SlashCommand(String command, List<String> args) implements ParsedCommand {}
        /** Detach THIS channel only; the account stays present on its other surfaces. */
        record Quit() implements ParsedCommand {}
        /** End the whole presence: drop every one of the account's live channels and leave. */
        record Logout() implements ParsedCommand {}
        /** List the account's live channels (and optionally kill one): "sessions" / "sessions kill &lt;n&gt;". */
        record Sessions(List<String> args) implements ParsedCommand {}
        /** Manage the account's own SSH public keys in-world: "key" / "key list" /
         *  "key add &lt;pubkey&gt;" / "key remove &lt;n&gt;" (scoped to the caller). */
        record Key(List<String> args) implements ParsedCommand {}

        /** Input that didn't match any command. Use 'text to say, :text to emote. */
        record Unknown(String text) implements ParsedCommand {}

        /** Define or list user aliases. "alias la look at" or "alias" to list. */
        record Alias(String name, String expansion) implements ParsedCommand {}
        /** Remove a user alias. "unalias la" */
        record Unalias(String name) implements ParsedCommand {}

        // Navigation commands (§N2)
        record MapCommand(int radius) implements ParsedCommand {}
        record Where() implements ParsedCommand {}
        record Nearby() implements ParsedCommand {}
        record Rooms() implements ParsedCommand {}
        record Path(String targetRoom) implements ParsedCommand {}

        /** Go to the player's personal Study — always works from anywhere. */
        record Office() implements ParsedCommand {}
        record Exits() implements ParsedCommand {}

        /**
         * Set a description on the player or a room.
         * @param target "me" or "room"
         * @param text   the description text
         */
        record Describe(String target, String text) implements ParsedCommand {}

        /**
         * Rename an entity.
         * @param target   "me" for self, or another entity name (steward / bondholder paths)
         * @param newName  the desired new display name
         */
        record Rename(String target, String newName) implements ParsedCommand {}

        /**
         * Passive observation of an object, entity, or readable. SPEC §2.2.
         *
         * <p>Distinguished from {@link Use}: examine does NOT invoke onUse
         * scripts, does NOT broadcast ObjectUsed, does NOT trigger a room
         * re-render. It returns the target's description and (for scripted
         * items with onExamine hooks) optional dynamic prose.</p>
         *
         * <p>Pre-refactor, examine/look-at/read all parsed to Use(X, null) —
         * which silently invoked scripts and emitted "You use the X."
         * fallback for non-scripted objects. The new Examine path keeps
         * passive verbs passive.</p>
         */
        record Examine(String target) implements ParsedCommand {}

        /** Give an object from inventory to another entity in the room. */
        record Give(String objectName, String targetName) implements ParsedCommand {}

        /** Display player vitality/stats summary. */
        record Score() implements ParsedCommand {}

        /** Shout text — broadcasts loudly. */
        record Shout(String text) implements ParsedCommand {}

        /** Reply to the last person who sent a tell. */
        record Reply(String text) implements ParsedCommand {}

        /** Begin following a target entity. */
        record Follow(String targetName) implements ParsedCommand {}

        /** Toggle AFK status with optional message. */
        record Afk(String message) implements ParsedCommand {}

        /** Toggle brief mode — shortened room descriptions on entry. */
        record Brief() implements ParsedCommand {}

        /** Grant a Study Ward to an agent — permanent Study access. */
        record GrantWard(String agentName) implements ParsedCommand {}
        /** Revoke a Study Ward from an agent. */
        record RevokeWard(String agentName) implements ParsedCommand {}
        /** Invite an agent into the current room (temporary access). */
        record Invite(String agentName) implements ParsedCommand {}
        /** Dismiss an agent from the current room. */
        record Dismiss(String agentName) implements ParsedCommand {}
        /** Abort/cancel the agent's current task or plan. */
        record AbortPlan() implements ParsedCommand {}

        /**
         * sit at/on/in a target. The target may be a
         * RoomObject id or alias; null means "sit (no target)" for non-anchored
         * postures. Dispatcher resolves the object, and either routes to the
         * scripted onUse hook (for scripted sittables) or emits a generic
         * "sat at X" posture for plain {@code state.sittable=true} objects.
         */
        record Sit(String target) implements ParsedCommand {}

        /** — stand back up; clears any current posture. */
        record Stand() implements ParsedCommand {}
    }

    /**
     * Parse a line of user input into a structured command.
     * Returns null for blank/null input.
     */
    public static ParsedCommand parse(String input) {
        return parse(input, "en");
    }

    /**
     * Parse with locale-aware command aliases.
     */
    public static ParsedCommand parse(String input, String locale) {
        return parse(input, locale, Map.of());
    }

    /**
     * Parse with locale + user-defined aliases.
     * User aliases take priority over locale aliases.
     * @param userAliases user-defined alias map (name → expansion)
     */
    public static ParsedCommand parse(String input, String locale, Map<String, String> userAliases) {
        if (input == null || input.isBlank()) return null;

        var trimmed = input.trim();

        // "alias" command — define or list
        if (trimmed.equalsIgnoreCase("alias") || trimmed.equalsIgnoreCase("aliases")) {
            return new ParsedCommand.Alias(null, null); // list all
        }
        if (trimmed.toLowerCase().startsWith("alias ")) {
            var rest = trimmed.substring(6).trim();
            var spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) {
                var name = rest.substring(0, spaceIdx).trim();
                var expansion = rest.substring(spaceIdx + 1).trim();
                return new ParsedCommand.Alias(name, expansion);
            }
            // "alias la" with no expansion — show that alias
            return new ParsedCommand.Alias(rest, null);
        }

        // "unalias" command
        if (trimmed.toLowerCase().startsWith("unalias ")) {
            var name = trimmed.substring(8).trim();
            if (!name.isEmpty()) return new ParsedCommand.Unalias(name);
        }

        // Expand user aliases first (highest priority)
        trimmed = expandUserAliases(trimmed, userAliases);

        // Then expand locale aliases
        trimmed = expandAliases(trimmed, locale);

        // Quit — detach this channel only. Logout/quitall — end the whole presence.
        if (trimmed.equalsIgnoreCase("/quit") || trimmed.equalsIgnoreCase("/exit")) {
            return new ParsedCommand.Quit();
        }
        if (trimmed.equalsIgnoreCase("/logout") || trimmed.equalsIgnoreCase("/quitall")) {
            return new ParsedCommand.Logout();
        }
        if (trimmed.equalsIgnoreCase("/sessions")) {
            return new ParsedCommand.Sessions(List.of());
        }

        // SSH key management for the logged-in account (mirrors `wyrd key`).
        // Bare "key" or "key list" → list; "key add <pubkey>" / "key remove <n>".
        // Requiring the subcommand keeps in-world "key" objects (take/examine key)
        // unaffected — only these exact forms are the account command.
        {
            var kparts = trimmed.split("\\s+");
            var kv = kparts[0].toLowerCase();
            if (kv.equals("key")) {
                var sub = kparts.length > 1 ? kparts[1].toLowerCase() : "list";
                if (sub.equals("list") || sub.equals("add") || sub.equals("remove") || sub.equals("rm")) {
                    return new ParsedCommand.Key(Arrays.asList(kparts).subList(1, kparts.length));
                }
            }
        }

        // Office / Study / Home / Return — always go to personal Study.
        if (trimmed.equalsIgnoreCase("office") || trimmed.equalsIgnoreCase("study")
                || trimmed.equalsIgnoreCase("home") || trimmed.equalsIgnoreCase("return")) {
            return new ParsedCommand.Office();
        }

        // Hint selection: single or double digit
        if (trimmed.matches("\\d{1,2}")) {
            int index = Integer.parseInt(trimmed) - 1;
            if (index >= 0) {
                return new ParsedCommand.HintSelect(index);
            }
        }

        // Slash command
        if (trimmed.startsWith("/")) {
            var parts = trimmed.substring(1).split("\\s+", 2);
            var cmd = parts[0].toLowerCase();
            var args = parts.length > 1
                ? Arrays.asList(parts[1].split("\\s+"))
                : List.<String>of();
            return new ParsedCommand.SlashCommand(cmd, args);
        }

        // "look" command (bare look = Look; "look at <X>" / "l at <X>" = Use)
        if (trimmed.equalsIgnoreCase("look") || trimmed.equalsIgnoreCase("l")) {
            return new ParsedCommand.Look();
        }

        // "examine <object>" / "ex <object>" → Examine (passive observation)
        // SPEC §2.2. Was Use(object, null) pre-refactor — caused the
        // examine→Use coupling that emitted "you use the X" fallback.
        if (trimmed.toLowerCase().startsWith("examine ") || trimmed.toLowerCase().startsWith("ex ")) {
            var rest = trimmed.substring(trimmed.indexOf(' ') + 1).trim();
            if (!rest.isEmpty()) {
                return new ParsedCommand.Examine(rest);
            }
        }

        // "look at <object>" / "l at <object>" → Examine (look-at = examine)
        if (trimmed.toLowerCase().startsWith("look at ") || trimmed.toLowerCase().startsWith("l at ")) {
            var atIdx = trimmed.toLowerCase().indexOf(" at ");
            var rest = trimmed.substring(atIdx + 4).trim();
            if (!rest.isEmpty()) {
                return new ParsedCommand.Examine(rest);
            }
        }

        // sit / stand body verbs.
        // "sit at <X>", "sit on <X>", "sit in <X>", "sit <X>" → Sit(X)
        // "sit" alone (no target) → Sit(null) — non-anchored posture
        // "stand" / "stand up" / "rise" → Stand()
        var lower = trimmed.toLowerCase();
        if (lower.equals("stand") || lower.equals("stand up") || lower.equals("rise")
                || lower.equals("get up")) {
            return new ParsedCommand.Stand();
        }
        if (lower.equals("sit") || lower.equals("sit down")) {
            return new ParsedCommand.Sit(null);
        }
        if (lower.startsWith("sit at ") || lower.startsWith("sit on ")
                || lower.startsWith("sit in ")) {
            var rest = trimmed.substring(7).trim();
            if (!rest.isEmpty()) {
                return new ParsedCommand.Sit(rest);
            }
        }
        if (lower.startsWith("sit ")) {
            var rest = trimmed.substring(4).trim();
            if (!rest.isEmpty()) {
                return new ParsedCommand.Sit(rest);
            }
        }

        // "inventory" command
        if (trimmed.equalsIgnoreCase("inventory") || trimmed.equalsIgnoreCase("i")) {
            return new ParsedCommand.SlashCommand("inventory", List.of());
        }

        // Navigation commands (§N2)
        if (trimmed.equalsIgnoreCase("where")) {
            return new ParsedCommand.Where();
        }
        if (trimmed.equalsIgnoreCase("nearby")) {
            return new ParsedCommand.Nearby();
        }
        if (trimmed.equalsIgnoreCase("rooms")) {
            return new ParsedCommand.Rooms();
        }
        if (trimmed.equalsIgnoreCase("exits") || trimmed.equalsIgnoreCase("x")) {
            return new ParsedCommand.Exits();
        }

        // "actions" / "menu" / "options" — render the numbered action menu
        // on demand instead of flooding every room display with it.
        if (trimmed.equalsIgnoreCase("actions") || trimmed.equalsIgnoreCase("menu")
                || trimmed.equalsIgnoreCase("options") || trimmed.equals("?")) {
            return new ParsedCommand.SlashCommand("actions", List.of());
        }

        // "who" — zone-wide roster with permission filtering
        if (trimmed.equalsIgnoreCase("who")) {
            return new ParsedCommand.SlashCommand("who", List.of());
        }

        var words = trimmed.split("\\s+");
        var firstWord = words[0].toLowerCase();

        // "passwd <current> <new>" — the handler has always existed, but only ever as a
        // SLASH command, so the bare form that `help` and tab-completion both advertise
        // ("passwd - Change your password") fell through to Unknown and answered
        // "Didn't catch that." Help is the contract; the bare verb has to reach the
        // handler. Same bare-verb → SlashCommand shape as inventory / actions / who above,
        // so every surface that shares this parser (SSH, telnet, web) gets it at once.
        if (firstWord.equals("passwd") || firstWord.equals("password")) {
            return new ParsedCommand.SlashCommand("passwd",
                List.of(words).subList(1, words.length));
        }

        // "map" / "m" with optional radius
        if (firstWord.equals("map") || (firstWord.equals("m") && words.length <= 2)) {
            int radius = 2;
            if (words.length > 1) {
                try { radius = Math.clamp(Integer.parseInt(words[1]), 1, 5); }
                catch (NumberFormatException ignored) { radius = 2; }
            }
            // Don't match bare "m" — it's a direction abbreviation handled below
            if (!firstWord.equals("m") || words.length > 1) {
                return new ParsedCommand.MapCommand(radius);
            }
        }

        // "path <room>" / "path to <room>"
        if (firstWord.equals("path") && words.length > 1) {
            var target = trimmed.substring(5).trim();
            if (target.toLowerCase().startsWith("to ")) target = target.substring(3).trim();
            return new ParsedCommand.Path(target);
        }

        // "go <direction>"
        if (firstWord.equals("go") && words.length > 1) {
            return new ParsedCommand.Go(expandDirection(words[1].toLowerCase()));
        }

        // Bare direction
        if (DIRECTIONS.contains(firstWord) && words.length == 1) {
            return new ParsedCommand.Go(expandDirection(firstWord));
        }

        // Say shorthand: 'text or "text (standard MUD convention)
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            var text = trimmed.substring(1).trim();
            if (!text.isEmpty()) {
                return new ParsedCommand.Say(text);
            }
        }

        // "say <text>"
        if (firstWord.equals("say") && trimmed.length() > 4) {
            return new ParsedCommand.Say(trimmed.substring(4).trim());
        }

        // "tell <name> <text>"
        if (firstWord.equals("tell") && words.length > 2) {
            var targetName = words[1];
            var text = trimmed.substring(trimmed.indexOf(words[1]) + words[1].length()).trim();
            return new ParsedCommand.Tell(targetName, text);
        }

        // Whisper shorthand: ">name text" (same-room directed message)
        if (trimmed.startsWith(">") && trimmed.length() > 1) {
            var rest = trimmed.substring(1).trim();
            var spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) {
                var targetName = rest.substring(0, spaceIdx);
                var text = rest.substring(spaceIdx + 1).trim();
                if (!targetName.isEmpty() && !text.isEmpty()) {
                    return new ParsedCommand.Whisper(targetName, text);
                }
            }
        }

        // "whisper <name> <text>"
        if (firstWord.equals("whisper") && words.length > 2) {
            var targetName = words[1];
            var text = trimmed.substring(trimmed.indexOf(words[1]) + words[1].length()).trim();
            return new ParsedCommand.Whisper(targetName, text);
        }

        // "take <object>" / "get <object>"
        if ((firstWord.equals("take") || firstWord.equals("get")) && words.length > 1) {
            return new ParsedCommand.Take(trimmed.substring(firstWord.length()).trim());
        }

        // "drop <object>"
        if (firstWord.equals("drop") && words.length > 1) {
            return new ParsedCommand.Drop(trimmed.substring(5).trim());
        }

        // "retire <object>" — the counterpart to drop. Also accepts "destroy"/"discard",
        // because a person reaching to get rid of something will type whichever comes to
        // hand, and a command they cannot find is a command that does not exist.
        if ((firstWord.equals("retire") || firstWord.equals("destroy")
                || firstWord.equals("discard")) && words.length > 1) {
            return new ParsedCommand.Retire(
                trimmed.substring(firstWord.length()).trim());
        }

        // "use <object> [on <target>]"
        if (firstWord.equals("use") && words.length > 1) {
            var rest = trimmed.substring(4).trim();
            var onIndex = rest.toLowerCase().indexOf(" on ");
            if (onIndex >= 0) {
                return new ParsedCommand.Use(
                    rest.substring(0, onIndex).trim(),
                    rest.substring(onIndex + 4).trim());
            }
            return new ParsedCommand.Use(rest, null);
        }

        // Emote shorthand: :action or ;action
        if (trimmed.startsWith(":") || trimmed.startsWith(";")) {
            var text = trimmed.substring(1).trim();
            if (!text.isEmpty()) {
                return new ParsedCommand.Emote(text);
            }
        }

        // Emote full word: "emote action"
        if (firstWord.equals("emote") && trimmed.length() > 6) {
            var text = trimmed.substring(6).trim();
            if (!text.isEmpty()) {
                return new ParsedCommand.Emote(text);
            }
        }

        // "@describe <text>" / "@describe me=<text>" / "@describe room=<text>"
        if (trimmed.toLowerCase().startsWith("@describe ")) {
            var rest = trimmed.substring("@describe ".length()).trim();
            if (rest.toLowerCase().startsWith("me=")) {
                var text = rest.substring(3).trim();
                if (!text.isEmpty()) return new ParsedCommand.Describe("me", text);
            } else if (rest.toLowerCase().startsWith("room=")) {
                var text = rest.substring(5).trim();
                if (!text.isEmpty()) return new ParsedCommand.Describe("room", text);
            } else if (!rest.isEmpty()) {
                return new ParsedCommand.Describe("me", rest);
            }
        }

        // "rename me <new-name>" / "rename <target> <new-name>" — SPEC §7.4.
        // Two-word form is rejected: a one-word target without a new name is
        // ambiguous (`rename me` could mean "remove my name") and not a thing
        // we want to support. Three+ words required.
        if (firstWord.equals("rename") && words.length >= 3) {
            var target = words[1];
            // Join remainder so multi-word names work: `rename me Arda the Wise`.
            var newName = String.join(" ",
                Arrays.copyOfRange(words, 2, words.length)).trim();
            if (!newName.isEmpty()) {
                return new ParsedCommand.Rename(target, newName);
            }
        }

        // "describe me <text>" / "describe room <text>"
        if (firstWord.equals("describe") && words.length > 2) {
            var target = words[1].toLowerCase();
            if ("me".equals(target) || "room".equals(target)) {
                var text = trimmed.substring(trimmed.indexOf(words[1]) + words[1].length()).trim();
                if (!text.isEmpty()) return new ParsedCommand.Describe(target, text);
            }
        }

        // "give <object> to <target>" / "give <target> <object>"
        if (firstWord.equals("give") && words.length > 2) {
            var rest = trimmed.substring(5).trim();
            var toIndex = rest.toLowerCase().indexOf(" to ");
            if (toIndex >= 0) {
                var objectName = rest.substring(0, toIndex).trim();
                var targetName = rest.substring(toIndex + 4).trim();
                if (!objectName.isEmpty() && !targetName.isEmpty()) {
                    return new ParsedCommand.Give(objectName, targetName);
                }
            }
            // Fallback: "give <target> <object>" (first word is target, rest is object)
            return new ParsedCommand.Give(
                trimmed.substring(trimmed.indexOf(words[1]) + words[1].length()).trim(),
                words[1]);
        }

        // "score" / "vitals" / "stats"
        if (firstWord.equals("score") || firstWord.equals("vitals") || firstWord.equals("stats")) {
            if (words.length == 1) {
                return new ParsedCommand.Score();
            }
        }

        // "read <object>" → alias for examine → Use(object, null)
        if (firstWord.equals("read") && words.length > 1) {
            return new ParsedCommand.Use(trimmed.substring(5).trim(), null);
        }

        // "shout <text>" / "yell <text>"
        if ((firstWord.equals("shout") || firstWord.equals("yell")) && trimmed.length() > firstWord.length() + 1) {
            return new ParsedCommand.Shout(trimmed.substring(firstWord.length() + 1).trim());
        }

        // "reply <text>" / "r <text>" (but only bare "r" with text, not a direction)
        if (firstWord.equals("reply") && trimmed.length() > 6) {
            return new ParsedCommand.Reply(trimmed.substring(6).trim());
        }
        if (firstWord.equals("r") && words.length > 1) {
            // "r" alone is already handled as direction — only match "r <text>"
            return new ParsedCommand.Reply(trimmed.substring(2).trim());
        }

        // "follow <target>"
        if (firstWord.equals("follow") && words.length > 1) {
            return new ParsedCommand.Follow(trimmed.substring(7).trim());
        }

        // "afk" / "afk <message>"
        if (firstWord.equals("afk")) {
            var msg = words.length > 1 ? trimmed.substring(4).trim() : "AFK";
            return new ParsedCommand.Afk(msg);
        }

        // "brief" / "verbose" — toggle brief mode
        if (firstWord.equals("brief") || firstWord.equals("verbose")) {
            if (words.length == 1) {
                return new ParsedCommand.Brief();
            }
        }

        // "grant ward <agent>" — give Study Ward to an agent
        if (firstWord.equals("grant") && words.length >= 3
                && words[1].equalsIgnoreCase("ward")) {
            return new ParsedCommand.GrantWard(
                trimmed.substring(trimmed.indexOf(words[2])).trim());
        }

        // "revoke ward <agent>" — remove Study Ward from an agent
        if (firstWord.equals("revoke") && words.length >= 3
                && words[1].equalsIgnoreCase("ward")) {
            return new ParsedCommand.RevokeWard(
                trimmed.substring(trimmed.indexOf(words[2])).trim());
        }

        // "invite <agent>" — temporary room access
        if (firstWord.equals("invite") && words.length > 1) {
            return new ParsedCommand.Invite(trimmed.substring(7).trim());
        }

        // "dismiss <agent>" — ask agent to leave
        if (firstWord.equals("dismiss") && words.length > 1) {
            return new ParsedCommand.Dismiss(trimmed.substring(8).trim());
        }

        // "abort" / "stop" / "cancel" / "nevermind" — abort active agent plan
        if (firstWord.equals("abort") || firstWord.equals("stop")
                || firstWord.equals("cancel") || firstWord.equals("nevermind")) {
            return new ParsedCommand.AbortPlan();
        }

        // "help" — standard MUD command (without / prefix)
        if (firstWord.equals("help")) {
            return new ParsedCommand.SlashCommand("help",
                words.length > 1 ? List.of(trimmed.substring(5).trim()) : List.of());
        }

        // "quit" / "exit" / "q" — standard MUD-convention session-end aliases.
        // `q` was missing pre-fix; muscle memory from every BBS/MUD/vi makes
        // this a conformance requirement. Keep this
        // gate narrow: only bare-word `q`, not `q <args>`, so we don't shadow
        // a future verb like `q <player>` (quote, query, etc.).
        if (firstWord.equals("quit") || firstWord.equals("exit")
                || (firstWord.equals("q") && words.length == 1)) {
            return new ParsedCommand.Quit();
        }

        // "logout" / "quitall" — end the whole presence (all channels), not just
        // this one. Distinct from quit/exit per the single-presence-many-channels
        // model: the bare verb detaches a channel; logout leaves the world.
        if (firstWord.equals("logout") || firstWord.equals("quitall")) {
            return new ParsedCommand.Logout();
        }

        // "sessions" — list the account's live channels; "sessions kill <n>".
        if (firstWord.equals("sessions")) {
            return new ParsedCommand.Sessions(words.length > 1
                ? List.of(Arrays.copyOfRange(words, 1, words.length))
                : List.of());
        }

        // "journal <text>" — Study command (room script handles it, but parse here for routing)
        if (firstWord.equals("journal") && words.length > 1) {
            return new ParsedCommand.Say(trimmed);  // Route to room script via say
        }

        // "search <query>" — Study command
        if (firstWord.equals("search") && words.length > 1) {
            return new ParsedCommand.Say(trimmed);  // Route to room script via say
        }

        // "note <text>" — Study command
        if (firstWord.equals("note") && words.length > 1) {
            return new ParsedCommand.Say(trimmed);  // Route to room script via say
        }

        // Default: unknown command (NOT auto-say — standard MUD behavior)
        return new ParsedCommand.Unknown(trimmed);
    }

    // ── Locale command aliases ────────────────────────────────────────

    private static final Map<String, Map<String, String>> ALIASES = Map.of(
        "ja", Map.ofEntries(
            // Commands
            Map.entry("見る", "look"), Map.entry("みる", "look"),
            Map.entry("行く", "go"), Map.entry("いく", "go"),
            Map.entry("言う", "say"), Map.entry("いう", "say"), Map.entry("話す", "say"),
            Map.entry("取る", "take"), Map.entry("とる", "take"),
            Map.entry("落とす", "drop"), Map.entry("おとす", "drop"),
            Map.entry("使う", "use"), Map.entry("つかう", "use"),
            Map.entry("助けて", "help"), Map.entry("ヘルプ", "help"),
            Map.entry("やめる", "quit"),
            // Directions
            Map.entry("北", "north"), Map.entry("南", "south"),
            Map.entry("東", "east"), Map.entry("西", "west"),
            Map.entry("上", "up"), Map.entry("下", "down"),
            // Study commands
            Map.entry("日記", "journal"), Map.entry("にっき", "journal"),
            Map.entry("検索", "search"), Map.entry("けんさく", "search"),
            Map.entry("メモ", "note")
        ),
        "es", Map.ofEntries(
            // Commands
            Map.entry("mirar", "look"), Map.entry("ver", "look"),
            Map.entry("ir", "go"),
            Map.entry("decir", "say"), Map.entry("hablar", "say"),
            Map.entry("tomar", "take"), Map.entry("coger", "take"),
            Map.entry("soltar", "drop"),
            Map.entry("usar", "use"),
            Map.entry("ayuda", "help"),
            Map.entry("salir", "quit"),
            // Directions
            Map.entry("norte", "north"), Map.entry("sur", "south"),
            Map.entry("este", "east"), Map.entry("oeste", "west"),
            Map.entry("arriba", "up"), Map.entry("abajo", "down"),
            // Study commands
            Map.entry("diario", "journal"),
            Map.entry("buscar", "search"),
            Map.entry("nota", "note")
        )
    );

    /**
     * Expand user-defined aliases. First word is matched against the alias map.
     * The expansion replaces the first word; remaining args are appended.
     * Example: alias "la" → "look at", input "la crystal" → "look at crystal"
     */
    private static String expandUserAliases(String input, Map<String, String> userAliases) {
        if (userAliases == null || userAliases.isEmpty()) return input;
        var spaceIdx = input.indexOf(' ');
        var firstWord = spaceIdx > 0 ? input.substring(0, spaceIdx) : input;
        var rest = spaceIdx > 0 ? input.substring(spaceIdx) : "";
        var expanded = userAliases.get(firstWord);
        if (expanded != null) return expanded + rest;
        // Also try lowercase
        expanded = userAliases.get(firstWord.toLowerCase());
        if (expanded != null) return expanded + rest;
        return input;
    }

    /**
     * Expand locale-specific command aliases to English.
     * Only expands the first word (the command verb).
     * Arguments are preserved as-is.
     */
    private static String expandAliases(String input, String locale) {
        if (locale == null || locale.equals("en")) return input;

        var langKey = locale.length() > 2 ? locale.substring(0, 2) : locale;
        var aliasMap = ALIASES.get(langKey);
        if (aliasMap == null) return input;

        // Split first word from rest
        var spaceIdx = input.indexOf(' ');
        var firstWord = spaceIdx > 0 ? input.substring(0, spaceIdx) : input;
        var rest = spaceIdx > 0 ? input.substring(spaceIdx) : "";

        var expanded = aliasMap.get(firstWord);
        if (expanded != null) {
            return expanded + rest;
        }
        return input;
    }

    /** Expand single-letter direction abbreviation to full name. */
    public static String expandDirection(String abbrev) {
        return switch (abbrev) {
            case "n" -> "north";
            case "s" -> "south";
            case "e" -> "east";
            case "w" -> "west";
            case "u" -> "up";
            case "d" -> "down";
            case "ne" -> "northeast";
            case "nw" -> "northwest";
            case "se" -> "southeast";
            case "sw" -> "southwest";
            default -> abbrev;
        };
    }
}
