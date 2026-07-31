package org.wyrdsekai.core.pak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Manifest for a .wyrdpak extension package.
 *
 * <pre>
 * {
 *   "name": "weather-station",
 *   "version": "1.0.0",
 *   "description": "A weather monitoring room with forecasts",
 *   "author": "Community Author",
 *   "license": "MIT",
 *   "compatibility": ">=0.1.0",
 *   "rooms": ["rooms/weather-room.js"],
 *   "mcp_services": ["mcp/open-meteo.json"],
 *   "i18n": ["i18n/en.json", "i18n/ja.json"],
 *   "souls": ["souls/weatherbot.json"],
 *   "dependencies": {"oracle-core": ">=0.1.0"}
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PakManifest(
    String name,
    String version,
    String description,
    String author,
    String license,
    String compatibility,
    List<String> rooms,
    List<String> mcp_services,
    List<String> i18n,
    List<String> souls,
    Map<String, String> dependencies
) {
    public PakManifest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Manifest 'name' is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Manifest 'version' is required");
        }
        if (rooms == null) rooms = List.of();
        if (mcp_services == null) mcp_services = List.of();
        if (i18n == null) i18n = List.of();
        if (souls == null) souls = List.of();
        if (dependencies == null) dependencies = Map.of();
    }

    /** Display string for listing. */
    public String displayString() {
        return name + " v" + version
            + (author != null ? " by " + author : "")
            + (description != null ? " — " + description : "");
    }
}
