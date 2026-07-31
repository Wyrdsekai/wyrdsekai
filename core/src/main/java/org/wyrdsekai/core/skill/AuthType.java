package org.wyrdsekai.core.skill;

/**
 * Authentication mechanism type for skill credentials.
 */
public enum AuthType {
    /** API key passed as header or query parameter. */
    API_KEY,

    /** OAuth 2.0 Device Authorization Flow (RFC 8628) — Google, Microsoft, GitHub. */
    OAUTH_DEVICE_FLOW,

    /** Local bridge token (e.g., Home Assistant long-lived token). */
    LOCAL_BRIDGE,

    /** No authentication required (local services, Open-Meteo, etc.). */
    NONE
}
