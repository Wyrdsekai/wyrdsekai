package org.wyrdsekai.core.naming;

import java.util.Objects;

/**
 * Resolves user-typed strings into canonical {@link ZoneAddress}es.
 *
 * <p>This is the single entry point the rest of the system calls when it
 * sees a zone name from a human — {@code say travel alice:kitchen},
 * {@code wyrd zones publish garage}, docks-script {@code travel <input>}.
 * Everywhere below this layer deals in {@code ZoneAddress}, which is
 * unambiguous. for the grammar.</p>
 *
 * <h2>Input forms</h2>
 *
 * <table border="1">
 *   <tr><th>Input</th><th>Resolution</th></tr>
 *   <tr><td>{@code did:wyrd:z6Mk…:kitchen}</td><td>Canonical — returned as-is.</td></tr>
 *   <tr><td>{@code alice:kitchen}</td><td>Contact alias + explicit label.</td></tr>
 *   <tr><td>{@code alice}</td><td>Contact alias, use their default label.</td></tr>
 *   <tr><td>{@code garage}</td><td>Own zone label (checked in {@link LocalZoneRegistry}).</td></tr>
 *   <tr><td>{@code home}/{@code self}/{@code me}/{@code here}/{@code origin}</td>
 *       <td>Reserved keyword — never a lookup (see §2.4).</td></tr>
 * </table>
 *
 * <h2>Error semantics</h2>
 *
 * <p>This class never throws for user input. It returns a sealed
 * {@link Result} so the caller can render specific messages to the user
 * without parsing exception strings. Exceptions are reserved for programmer
 * errors (null resolver, null inputs).</p>
 */
public final class ZoneAddressResolver {

    /**
     * Sealed result type for {@link #resolve}. A caller typically switches
     * on this and formats the {@link Err#message} for the user on failure,
     * or passes the {@link Ok#address} to the federation layer on success.
     */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok(ZoneAddress address) implements Result {}

        /**
         * @param code    short machine-readable identifier
         *                ({@code reserved_keyword}, {@code unknown_alias},
         *                {@code unknown_label}, {@code malformed_did},
         *                {@code empty_input}, {@code no_default_zone}).
         * @param message human-facing explanation, safe to show directly.
         */
        record Err(String code, String message) implements Result {}
    }

    /**
     * Directory-backed fallback for unknown labels. Given a label, returns
     * either exactly one match (resolved to a {@link ZoneAddress}), nothing
     * (label absent), or an ambiguous-match signal via the error case. The
     * resolver uses this only after every local-state lookup has missed —
     * directory hits are best-effort and must never block on network I/O.
     */
    @FunctionalInterface
    public interface DirectoryLookup {
        /**
         * @return Ok with the resolved address if exactly one published
         *     manifest has this label; Err("ambiguous_label", …) if
         *     multiple zones advertise the same label; null if the lookup
         *     is unavailable (disabled, timing out, uninitialised).
         */
        Result resolveLabel(String label);
    }

    private final HouseholdIdentity me;
    private final ContactsBook contacts;
    private final LocalZoneRegistry myZones;
    private final DirectoryLookup directoryLookup; // nullable — tests + unit contexts may not wire one

    public ZoneAddressResolver(
            HouseholdIdentity me,
            ContactsBook contacts,
            LocalZoneRegistry myZones) {
        this(me, contacts, myZones, null);
    }

    public ZoneAddressResolver(
            HouseholdIdentity me,
            ContactsBook contacts,
            LocalZoneRegistry myZones,
            DirectoryLookup directoryLookup) {
        this.me = Objects.requireNonNull(me, "household identity");
        this.contacts = Objects.requireNonNull(contacts, "contacts book");
        this.myZones = Objects.requireNonNull(myZones, "zone registry");
        this.directoryLookup = directoryLookup; // may be null
    }

    /**
     * Resolve a user-typed input. Returns {@link Result.Ok} with the
     * canonical address, or {@link Result.Err} with a code + message
     * suitable for direct display.
     */
    public Result resolve(String input) {
        if (input == null || input.isBlank()) {
            return new Result.Err("empty_input",
                "No zone specified. Usage: travel <label>, travel <alias>:<label>, or travel <did>:<label>");
        }
        var s = input.strip();

        // 1. Reserved keywords — fail fast with the clearest message, before
        //    any lookup is attempted. This is intentional: a user who typed
        //    `travel home` should see the semantic explanation, not a
        //    confusing "unknown zone `home`" that suggests they might create
        //    one.
        if (ZoneLabels.isReserved(s)) {
            return new Result.Err("reserved_keyword",
                "'" + s + "' is a reserved keyword, not a zone. Use 'home' only as a directive "
                    + "(return to origin). To visit a specific zone, name it: travel <label> "
                    + "or travel <alias>:<label>.");
        }

        // 2. Canonical DID form — most specific, least ambiguous. Handle
        //    before alias resolution so a user can paste a DID from a
        //    first-contact exchange and it works immediately without adding
        //    to contacts first.
        if (s.startsWith(HouseholdIdentity.DID_SCHEME)) {
            var parsed = ZoneAddress.parseCanonical(s);
            if (parsed.isPresent()) {
                return new Result.Ok(parsed.get());
            }
            return new Result.Err("malformed_did",
                "'" + s + "' looks like a DID but doesn't parse. Expected: did:wyrd:z6Mk…:label");
        }

        // 3. Alias:label form — contact lookup with explicit zone.
        int sep = s.indexOf(':');
        if (sep > 0) {
            var alias = s.substring(0, sep);
            var label = s.substring(sep + 1);
            if (ZoneLabels.isReserved(alias)) {
                return new Result.Err("reserved_keyword",
                    "'" + alias + "' is a reserved keyword and can't be a contact alias.");
            }
            var contact = contacts.get(alias);
            if (contact.isEmpty()) {
                // Directory fallback: the label may be advertised in the
                // rendezvous pool even without a local contact. Users
                // shouldn't have to `wyrd contacts add beta <did>` when
                // `wyrd discover` already shows beta. The fingerprint from
                // the directory is the same DID they'd paste anyway.
                var fromDir = lookupInDirectory(alias);
                if (fromDir != null) {
                    if (fromDir instanceof Result.Ok ok) {
                        if (!ZoneLabels.isWellFormed(label)) {
                            return new Result.Err("malformed_label",
                                "'" + label + "' is not a valid zone label after ':'.");
                        }
                        if (ZoneLabels.isReserved(label)) {
                            return new Result.Err("reserved_keyword",
                                "'" + label + "' is a reserved keyword, not a zone label.");
                        }
                        try {
                            return new Result.Ok(new ZoneAddress(ok.address().fingerprint(), label));
                        } catch (IllegalArgumentException e) {
                            return new Result.Err("malformed_contact",
                                "Directory entry for '" + alias + "' has an invalid DID: " + e.getMessage());
                        }
                    }
                    return fromDir; // ambiguous_label etc. — surface directly
                }
                return new Result.Err("unknown_alias",
                    "No contact named '" + alias + "'. Add them with: wyrd contacts add " + alias + " <did>");
            }
            if (!ZoneLabels.isWellFormed(label)) {
                return new Result.Err("malformed_label",
                    "'" + label + "' is not a valid zone label after ':'.");
            }
            if (ZoneLabels.isReserved(label)) {
                return new Result.Err("reserved_keyword",
                    "'" + label + "' is a reserved keyword, not a zone label.");
            }
            try {
                return new Result.Ok(new ZoneAddress(contact.get().fingerprint(), label));
            } catch (IllegalArgumentException e) {
                return new Result.Err("malformed_contact",
                    "Contact '" + alias + "' has an invalid DID on file: " + e.getMessage());
            }
        }

        // 4. Bare alias (contact with default label). If the contact has no
        //    default set, we error out asking for an explicit label rather
        //    than picking arbitrarily — "alice" alone is ambiguous if alice
        //    has {kitchen, garden, studio}.
        var bareContact = contacts.get(s);
        if (bareContact.isPresent()) {
            var def = bareContact.get().defaultLabel();
            if (def == null) {
                return new Result.Err("no_default_zone",
                    "Contact '" + s + "' has no default zone set. Specify one: travel " + s + ":<label>");
            }
            try {
                return new Result.Ok(new ZoneAddress(bareContact.get().fingerprint(), def));
            } catch (IllegalArgumentException e) {
                return new Result.Err("malformed_contact",
                    "Contact '" + s + "' has an invalid default label: " + e.getMessage());
            }
        }

        // 5. Own zone label.
        if (myZones.contains(s)) {
            return new Result.Ok(me.zone(s));
        }

        // 6. Directory fallback. Before erroring, ask the rendezvous pool —
        //    a bare `travel beta` should Just Work when the beta zone is
        //    already publishing its manifest, without forcing every user
        //    through `wyrd contacts add beta <did>`.
        var fromDir = lookupInDirectory(s);
        if (fromDir != null) {
            return fromDir;
        }

        // 7. No match.
        return new Result.Err("unknown_label",
            "'" + s + "' is not one of your zones and not a known contact alias. "
                + "Try: wyrd zones list, wyrd contacts list, wyrd discover.");
    }

    private Result lookupInDirectory(String label) {
        if (directoryLookup == null || label == null || label.isBlank()) return null;
        if (!ZoneLabels.isWellFormed(label)) return null;
        try {
            return directoryLookup.resolveLabel(label);
        } catch (Exception e) {
            // Directory is best-effort — a timeout or backend error must
            // not block the resolver. Fall through to local-miss.
            return null;
        }
    }
}
