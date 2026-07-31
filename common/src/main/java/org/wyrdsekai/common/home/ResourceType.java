package org.wyrdsekai.common.home;

import java.util.Set;

/**
 * A resource type registered in {@link ResourceTypeRegistry}. Defines the
 * URI contract and which capabilities are meaningful on this type. Grants
 * issued outside the capability matrix are rejected at issuance.
 *
 * @param name     type name used in URIs (e.g. {@code "journal"}, {@code "inference-budget"})
 * @param validCaps set of capabilities that make sense on this type
 * @param hasId    true if the URI includes a third segment ({@code home://o/type/id}), false for type-only ({@code home://o/type})
 */
public record ResourceType(
    String name,
    Set<Capability> validCaps,
    boolean hasId
) {
    public ResourceType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("resource type name must not be blank");
        }
        if (validCaps == null || validCaps.isEmpty()) {
            throw new IllegalArgumentException("resource type must declare at least one valid capability");
        }
    }

    public boolean supports(Capability cap) {
        return validCaps.contains(cap);
    }
}
