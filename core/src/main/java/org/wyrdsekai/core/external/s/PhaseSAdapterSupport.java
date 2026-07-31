package org.wyrdsekai.core.external.s;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Shared plumbing for Phase S (financial + telephony) adapters — see
 *
 * <p>Centralises the bits every Phase S adapter wants but doesn't want to
 * re-implement: a 30s {@link HttpClient}, a singleton {@link ObjectMapper},
 * credential lookup with normalized {@code credential_missing} response,
 * and a tiny logger handle.</p>
 *
 * <p>Adapters extend this class for convenience but the SPI contract is
 * still {@link org.wyrdsekai.core.external.ExternalAdapter} — there is no
 * inheritance requirement.</p>
 */
abstract class PhaseSAdapterSupport {

    /** 30s — Phase S adapters all hit external APIs over the public internet. */
    protected static final Duration ADAPTER_TIMEOUT = Duration.ofSeconds(30);

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final HttpClient http;

    protected PhaseSAdapterSupport() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(ADAPTER_TIMEOUT)
            .build();
    }

    /** Look up {@code slot} from the resolver; return a normalized fail when absent. */
    protected Optional<String> credential(String slot) {
        return CredentialResolver.get().resolve(slot);
    }

    protected AdapterResponse credentialMissing(String slot) {
        return AdapterResponse.fail(
            "credential_missing",
            "credential slot '" + slot + "' is empty — populate via The Safe",
            false);
    }

    /** Mask an API token for logging — keeps the first 4 + last 2 chars only. */
    protected static String mask(String secret) {
        if (secret == null || secret.length() < 8) return "****";
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 2);
    }

    /** Extract a string arg, falling back to {@code dflt} when absent / blank. */
    protected static String strArg(Map<String, Object> args, String key, String dflt) {
        if (args == null) return dflt;
        var v = args.get(key);
        if (v == null) return dflt;
        var s = String.valueOf(v);
        return s.isBlank() ? dflt : s;
    }

    /** Extract a string arg or return {@code null} when absent. */
    protected static String strArg(Map<String, Object> args, String key) {
        return strArg(args, key, null);
    }

    /** Extract a number arg; supports Integer/Long/Double/String forms. */
    protected static Long longArg(Map<String, Object> args, String key) {
        if (args == null) return null;
        var v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
