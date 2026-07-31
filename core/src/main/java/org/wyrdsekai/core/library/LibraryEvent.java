package org.wyrdsekai.core.library;

/**
 * Events emitted by the Library subsystem.
 * Sealed interface — exhaustive pattern matching in consumers.
 */
public sealed interface LibraryEvent {
    record CapabilityRegistered(String capId, String name, String source) implements LibraryEvent {}
    record VerificationCompleted(String capId, boolean passed, float score) implements LibraryEvent {}
    record CapabilityBanned(String capId, String name, String reason) implements LibraryEvent {}
    record CapabilityBlocked(String name, String reason) implements LibraryEvent {}
    record CapabilityUnblocked(String name) implements LibraryEvent {}
}
