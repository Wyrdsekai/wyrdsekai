package org.wyrdsekai.scripting.api;

/**
 * runtime error thrown when an item script
 * invokes a {@code world.*} method that its manifest hasn't declared, or that
 * the steward has revoked.
 *
 * <p>Surfaces in the script as a {@code RuntimeError} the script can {@code try}
 * over; the {@link #capability} field carries the denied cap name so install-prompt
 * UIs and the audit log can surface it.</p>
 */
public class CapabilityDeniedError extends RuntimeException {

    private final String capability;

    public CapabilityDeniedError(String capability) {
        super("capability denied: " + capability);
        this.capability = capability;
    }

    public CapabilityDeniedError(String capability, String detail) {
        super("capability denied: " + capability + " (" + detail + ")");
        this.capability = capability;
    }

    public String capability() {
        return capability;
    }
}
