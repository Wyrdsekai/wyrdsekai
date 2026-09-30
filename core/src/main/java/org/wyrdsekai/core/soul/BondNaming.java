package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.item.CompanionCodexView;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The {@code bond} verb: a person's own bonds with the companions of this home, and the naming
 * ritual.
 *
 * <p>When a bond becomes sacred the companion proposes a naming ritual, "a shared name or symbol
 * that only we understand", and until 2026-09-30 nothing could answer it: no verb, no place to keep
 * the name, and the ritual code that asks both sides had no caller. {@code bond name <companion>
 * <name>} is the person's side. The companion is asked, as herself, whether she takes the name
 * ({@link org.wyrdsekai.core.agent.decision.TypedDecision#BOND_NAME}); a name both hold is kept in
 * {@link BondNameStore} and shown only to the two of them.
 */
public final class BondNaming {

    private static final Logger log = LoggerFactory.getLogger(BondNaming.class);

    /** A name or symbol is short: a word, a phrase, a mark. */
    public static final int MAX_NAME_CHARS = 60;

    /** What the companion's side said to an offer, before she decides. */
    public enum Heard { OFFERED, NO_BOND, NOT_YET, ALREADY_NAMED, ASLEEP, DECIDING }

    private static final Pattern NAME = Pattern.compile("^name\\s+(\\S+)\\s+(\\S.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE = Pattern.compile("^bond(?:\\s+name\\s+\\S+\\s+\\S.*)?$", Pattern.CASE_INSENSITIVE);

    private BondNaming() {}

    /**
     * Whether a typed line is the verb: {@code bond} alone, or {@code bond name <companion> <name>}.
     * Anything else that starts with the word is speech.
     */
    public static boolean isCommand(String line) {
        return line != null && LINE.matcher(line.strip()).matches();
    }

    /** What follows {@code bond} on a line that {@link #isCommand}; empty for the bare verb. */
    public static String argsOf(String line) {
        var t = line == null ? "" : line.strip();
        return t.length() <= 4 ? "" : t.substring(4).strip();
    }

    /** A name as it is kept: one line, single spaces, no control characters. Null when nothing is left. */
    static String clean(String raw) {
        if (raw == null) return null;
        var t = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return t.isEmpty() ? null : t;
    }

    /** {@code bond} (the person's bonds here) and {@code bond name <companion> <name>} (the offer). */
    public static String command(String personId, String personName, String args, String locale) {
        var text = ScriptMessageCatalog.forLang(lang(locale));
        if (personId == null || personId.isBlank()) return text.get("bond.sign_in");
        var rest = args == null ? "" : args.strip();
        if (rest.isEmpty()) return list(personId, text);
        var m = NAME.matcher(rest);
        if (!m.matches()) return text.get("bond.usage");
        var who = m.group(1);
        var name = clean(m.group(2));
        if (name == null || name.length() > MAX_NAME_CHARS) return text.get("bond.name.too_long", String.valueOf(MAX_NAME_CHARS));
        var entityId = ForgeRoomBridge.resolveCompanionEntity(who);
        var companion = entityId == null ? null : ZoneGuardian.getCompanionRef(null, entityId);
        if (companion == null) return text.get("bond.no_companion", who);
        var heard = new CompletableFuture<Heard>();
        companion.tell(new CompanionActor.OfferBondName(personId, personName, name, heard));
        Heard answer;
        try {
            answer = heard.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return text.get("bond.name.not_heard", who);
        }
        log.info("Bond name offered to '{}' by {}: {}", who, personId, answer);
        return switch (answer) {
            case OFFERED -> text.get("bond.name.offered", who, name);
            case NO_BOND -> text.get("bond.name.no_bond", who);
            case NOT_YET -> text.get("bond.name.not_yet", who);
            case ALREADY_NAMED -> text.get("bond.name.already", who);
            case ASLEEP -> text.get("bond.name.asleep", who);
            case DECIDING -> text.get("bond.name.deciding", who);
        };
    }

    /** The person's living bonds with this home's companions: how deep, and the name when it has one. */
    private static String list(String personId, ScriptMessageCatalog text) {
        var url = BondNameStore.get().url();
        if (url == null) return text.get("bond.none");
        var me = PersonIds.canonical(personId);
        var names = new HashMap<String, String>();
        for (var c : CompanionCodexView.list()) {
            var name = String.valueOf(c.get("name"));
            if (c.get("did") != null) names.put(String.valueOf(c.get("did")), name);
            if (c.get("entityId") != null) names.put(String.valueOf(c.get("entityId")), name);
        }
        var lines = new ArrayList<String>();
        for (var b : new BondStore(url).all()) {
            if (!b.active() || me == null || !b.involves(me)) continue;
            var companionDid = b.otherParty(me);
            var companion = names.get(companionDid);
            if (companion == null) continue;
            var line = text.get("bond.list.row", companion,
                text.get("bond.depth." + b.depth().name().toLowerCase(Locale.ROOT)), String.valueOf(b.interactionCount()));
            if (b.depth().level() >= Bond.BondDepth.SACRED.level()) {
                var named = BondNameStore.get().find(companionDid, me);
                line += named.map(n -> text.get("bond.list.named", n.name()))
                    .orElseGet(() -> text.get("bond.list.unnamed", companion));
            }
            lines.add(line);
        }
        if (lines.isEmpty()) return text.get("bond.none");
        return text.get("bond.list.head") + "\n" + String.join("\n", lines);
    }

    static String lang(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        var l = locale.strip().toLowerCase(Locale.ROOT);
        int cut = l.indexOf('-');
        if (cut < 0) cut = l.indexOf('_');
        return cut > 0 ? l.substring(0, cut) : l;
    }
}
