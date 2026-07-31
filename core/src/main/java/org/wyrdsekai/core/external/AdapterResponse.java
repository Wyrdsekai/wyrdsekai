package org.wyrdsekai.core.external;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * normalized adapter return shape.
 * Adapters always produce {@code {success, data, error: {code, message, retryable}}}.
 */
public record AdapterResponse(
    boolean success,
    Object data,
    AdapterError error
) {
    public record AdapterError(String code, String message, boolean retryable) {}

    public static AdapterResponse ok(Object data) {
        return new AdapterResponse(true, data, null);
    }

    public static AdapterResponse fail(String code, String message, boolean retryable) {
        return new AdapterResponse(false, null,
            new AdapterError(code, message, retryable));
    }

    /** Convert to the JS-friendly {@code Map} shape consumed by ItemWorldApi proxies. */
    public Map<String, Object> toMap() {
        var out = new LinkedHashMap<String, Object>();
        out.put("success", success);
        if (data != null) out.put("data", data);
        if (error != null) {
            var e = new LinkedHashMap<String, Object>();
            e.put("code", error.code());
            e.put("message", error.message());
            e.put("retryable", error.retryable());
            out.put("error", e);
        }
        return out;
    }
}
