package org.wyrdsekai.core.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Signed delegation token for scoped agent authority (§96.3).
 * Aligns with NIST AI Agent Standards Initiative delegation chain requirements.
 * <p>
 * A bud acting on behalf of its parent (e.g., making a purchase) carries
 * a DelegationToken signed by the delegator, scoped to specific actions
 * and time/budget limits.
 */
public record DelegationToken(
    @JsonProperty("delegatorDid") String delegatorDid,
    @JsonProperty("delegateeDid") String delegateeDid,
    @JsonProperty("scope") String scope,
    @JsonProperty("budgetLimit") double budgetLimit,
    @JsonProperty("issued") Instant issued,
    @JsonProperty("expires") Instant expires,
    @JsonProperty("signature") byte[] signature
) {

    @JsonCreator
    public DelegationToken {}

    /** Scope constants for common delegation types. */
    public static final String SCOPE_MCP_PURCHASE = "mcp:purchase";
    public static final String SCOPE_MCP_READ = "mcp:read";
    public static final String SCOPE_ROOM_ENTER = "room:enter:*";
    public static final String SCOPE_ROOM_ENTER_PREFIX = "room:enter:";
    public static final String SCOPE_A2A_SEND = "a2a:send";
    public static final String SCOPE_A2A_RECEIVE = "a2a:receive";
    public static final String SCOPE_SOUL_EXCHANGE = "soul:exchange";
    public static final String SCOPE_BUD_CREATE = "bud:create";

    /** Check if the token is expired. */
    public boolean isExpired() {
        return expires != null && Instant.now().isAfter(expires);
    }

    /** Check if the token is currently valid (not expired, has signature). */
    public boolean isValid() {
        return !isExpired() && signature != null && signature.length > 0;
    }

    /** Check if the token covers a specific scope. */
    public boolean covers(String requestedScope) {
        if (scope == null || requestedScope == null) return false;
        if (scope.equals(requestedScope)) return true;
        // Wildcard: "room:enter:*" covers "room:enter:library"
        if (scope.endsWith(":*")) {
            var prefix = scope.substring(0, scope.length() - 1);
            return requestedScope.startsWith(prefix);
        }
        return false;
    }

    /** Check if the budget limit accommodates the requested amount. */
    public boolean withinBudget(double amount) {
        return budgetLimit <= 0 || amount <= budgetLimit;
    }

    /** Create an unsigned token (for signing later). */
    public static DelegationToken unsigned(String delegatorDid, String delegateeDid,
                                            String scope, double budgetLimit,
                                            Instant expires) {
        return new DelegationToken(delegatorDid, delegateeDid, scope,
            budgetLimit, Instant.now(), expires, new byte[0]);
    }

    /** Create a signed token. */
    public static DelegationToken signed(String delegatorDid, String delegateeDid,
                                          String scope, double budgetLimit,
                                          Instant expires, byte[] signature) {
        return new DelegationToken(delegatorDid, delegateeDid, scope,
            budgetLimit, Instant.now(), expires, signature);
    }

    /** Bytes to sign: delegator|delegatee|scope|budget|expires */
    public byte[] signingPayload() {
        var payload = delegatorDid + "|" + delegateeDid + "|" + scope + "|"
            + budgetLimit + "|" + (expires != null ? expires.toEpochMilli() : "none");
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DelegationToken t)) return false;
        return Double.compare(budgetLimit, t.budgetLimit) == 0
            && Objects.equals(delegatorDid, t.delegatorDid)
            && Objects.equals(delegateeDid, t.delegateeDid)
            && Objects.equals(scope, t.scope)
            && Objects.equals(issued, t.issued)
            && Objects.equals(expires, t.expires)
            && Arrays.equals(signature, t.signature);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(delegatorDid, delegateeDid, scope,
            budgetLimit, issued, expires);
        result = 31 * result + Arrays.hashCode(signature);
        return result;
    }
}
