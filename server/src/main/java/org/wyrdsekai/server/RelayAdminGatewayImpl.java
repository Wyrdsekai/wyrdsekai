package org.wyrdsekai.server;

import org.wyrdsekai.core.home.RelayAdminGateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * (P4) — the server-side implementation of the
 * core {@link RelayAdminGateway} seam. Wraps a {@link RelayAdminClient} (which
 * holds the node's signing key) so the in-world Warden furnishing — wired in
 * {@code core} — can dispatch signed admin ops without {@code core} depending
 * on {@code between}.
 *
 * <p>Performs ONLY the signed network call. Per-action grant-scope
 * authorization happens zone-side in {@code RelayGovernor.authorizeAndCall}
 * (before this is reached) and again relay-side at the signed endpoint.</p>
 */
public final class RelayAdminGatewayImpl implements RelayAdminGateway {

    private final RelayAdminClient client;
    private final String relayDid;
    private final String relayLabel;

    public RelayAdminGatewayImpl(RelayAdminClient client, String relayDid, String relayLabel) {
        this.client = client;
        this.relayDid = relayDid;
        this.relayLabel = relayLabel;
    }

    @Override
    public String actingDid() { return client.actingDid(); }

    @Override
    public String relayDid() { return relayDid; }

    @Override
    public String relayLabel() { return relayLabel; }

    @Override
    public Map<String, Object> call(String op, Map<String, Object> args) {
        var res = client.call(op, args);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", res.ok());
        out.put("status", res.status());
        if (res.body() != null) out.putAll(res.body());
        return out;
    }
}
