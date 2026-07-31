package org.wyrdsekai.common.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson ObjectMapper configuration.
 */
public final class Json {

    private static final ObjectMapper MAPPER = createMapper();

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
