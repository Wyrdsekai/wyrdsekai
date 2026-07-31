package org.wyrdsekai.common.i18n;

/**
 * Functional interface for objects that can translate themselves via a catalog.
 * Applied to hints, describe() output, onboarding messages, etc.
 */
@FunctionalInterface
public interface Translatable {
    String translate(MessageCatalog catalog);
}
