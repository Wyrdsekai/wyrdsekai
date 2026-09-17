package org.wyrdsekai.core.body;

/**
 * What kind of part a thing is. The kind decides how a part is perceived (a brain is felt,
 * a camera is held), what losing it costs, and what the reflexes may do to it.
 */
public enum BodyKind {
    /** A model that thinks or speaks for her. Shed first; never where the self is. */
    BRAIN,
    /** The record, memories, the forge, indexes, adapters. The record is the self. */
    STORE,
    /** A camera, a microphone, a feed: something held that perceives one place. */
    SENSE,
    /** An effector: a coding backend, a message sender, a switch. */
    HAND,
    /** A place with a keeper: mail, the web, ssh, the relay, MCP. */
    DOOR,
    /** A whole machine that carries its own limbs. */
    NODE,
    /** Where the copies are kept. Not felt; known to exist. */
    VAULT,
    /** A phone or a session: a limb of a person, not of her. */
    PERSON_ENDPOINT,
    /** Another being: a companion, the librarian, a visitor. Never a limb. */
    BEING,
    /** Power, memory, heat, disk, the host itself. Felt as pressure or fever. */
    ENVIRONMENT
}
