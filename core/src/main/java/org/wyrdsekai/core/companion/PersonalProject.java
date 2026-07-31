package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A companion's first-class personal project.
 *
 * <p>Projects live in the companion's Hearth, not in the user's Study. They
 * are work the companion has chosen — research, drafts, meditations, tools
 * of her own — that she may or may not surface to the bondholder. They
 * persist across offline windows so a companion can pick up tomorrow what
 * she set down today.</p>
 *
 * <p>Status values are intentionally informal — companion-defined. Common
 * ones: {@code active}, {@code paused}, {@code complete}, {@code abandoned}.
 * Tags let the companion organize across projects (e.g. {@code research},
 * {@code drafts}, {@code self-care}).</p>
 */
public record PersonalProject(
    @JsonProperty("id") String id,
    @JsonProperty("title") String title,
    @JsonProperty("description") String description,
    @JsonProperty("status") String status,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("lastTouched") Instant lastTouched,
    @JsonProperty("entries") List<Entry> entries,
    @JsonProperty("tags") List<String> tags,
    @JsonProperty("scope") String scope
) {

    @JsonCreator
    public PersonalProject {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (lastTouched == null) lastTouched = createdAt;
        if (entries == null) entries = List.of();
        if (tags == null) tags = List.of();
        if (status == null) status = "active";
        if (scope == null) scope = "private";
    }

    /** A timestamped note attached to a project. */
    public record Entry(
        @JsonProperty("at") Instant at,
        @JsonProperty("text") String text
    ) {
        @JsonCreator
        public Entry {
            if (at == null) at = Instant.now();
        }
    }

    public static PersonalProject create(String title, String description, List<String> tags) {
        return new PersonalProject(
            UUID.randomUUID().toString(), title, description, "active",
            Instant.now(), Instant.now(), List.of(), tags == null ? List.of() : tags,
            "private");
    }

    public PersonalProject withEntry(String text) {
        var e = new Entry(Instant.now(), text);
        var next = new ArrayList<>(entries);
        next.add(e);
        return new PersonalProject(id, title, description, status,
            createdAt, Instant.now(), List.copyOf(next), tags, scope);
    }

    public PersonalProject withStatus(String newStatus) {
        return new PersonalProject(id, title, description, newStatus,
            createdAt, Instant.now(), entries, tags, scope);
    }
}
