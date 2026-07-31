package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Events for the Counting House actor (persisted to journal).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CountingHouseEvent.UsageRecorded.class, name = "UsageRecorded")
})
public sealed interface CountingHouseEvent {

    /** A resource usage was recorded. */
    record UsageRecorded(ResourceUsage usage) implements CountingHouseEvent {}
}
