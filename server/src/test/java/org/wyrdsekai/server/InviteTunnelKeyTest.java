package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** A phone invite carries the home's public tunnel key ( W3). */
class InviteTunnelKeyTest {

    private static String invite(String json) {
        return "wyrdphone://relay.example:4443/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(String url) {
        var b64 = url.substring(url.indexOf('/', "wyrdphone://".length()) + 1);
        return new String(Base64.getUrlDecoder().decode(b64 + "=".repeat((4 - b64.length() % 4) % 4)), StandardCharsets.UTF_8);
    }

    @Test
    void theTunnelKeyIsAddedAndTheRestKept() {
        var m = new ObjectMapper();
        var url = RelayNkeyAdminMain.stampTunnelKeyIntoInviteUrl(invite("{\"ca_fp\":\"ab:cd\",\"zone_id\":\"z1\",\"user\":\"hh-1\"}"), "ZK123", m);
        assertThat(url).startsWith("wyrdphone://relay.example:4443/");
        assertThat(payload(url)).contains("\"zk\":\"ZK123\"").contains("\"ca_fp\":\"ab:cd\"").contains("\"zone_id\":\"z1\"").contains("\"user\":\"hh-1\"");
    }

    @Test
    void theHomesOwnKeyReplacesAnyOther() {
        var url = RelayNkeyAdminMain.stampTunnelKeyIntoInviteUrl(invite("{\"zk\":\"someone-else\",\"zone_id\":\"z1\"}"), "MINE", new ObjectMapper());
        assertThat(payload(url)).contains("\"zk\":\"MINE\"").doesNotContain("someone-else");
    }

    @Test
    void anythingUnreadableIsLeftAlone() {
        assertThat(RelayNkeyAdminMain.stampTunnelKeyIntoInviteUrl("https://not-an-invite", "K", new ObjectMapper())).isEqualTo("https://not-an-invite");
        assertThat(RelayNkeyAdminMain.stampTunnelKeyIntoInviteUrl("wyrdphone://h/%%%", "K", new ObjectMapper())).isEqualTo("wyrdphone://h/%%%");
    }
}
