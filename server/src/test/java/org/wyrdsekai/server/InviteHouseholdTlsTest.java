package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.crypto.HouseholdTls;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D1: a phone invite carries the household CA fingerprint and this home's HTTPS address. */
class InviteHouseholdTlsTest {

    private static String invite(String json) {
        return "wyrdphone://relay.example:4443/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(String url) {
        var b64 = url.substring(url.indexOf('/', "wyrdphone://".length()) + 1);
        return new String(Base64.getUrlDecoder().decode(b64 + "=".repeat((4 - b64.length() % 4) % 4)), StandardCharsets.UTF_8);
    }

    @Test
    void theHomesFingerprintAndHttpsAddressAreStampedNextToTheTunnelKey(@TempDir Path data) throws Exception {
        var m = HouseholdTls.ensure(data);
        var fields = HouseholdTls.inviteFields(data, true, 7443);
        var url = RelayNkeyAdminMain.stampFieldsIntoInviteUrl(
            invite("{\"zone_id\":\"z1\",\"zk\":\"ZK\",\"home_ca_fp\":\"someone-else\"}"), fields, new ObjectMapper());
        var p = payload(url);
        assertThat(p).contains("\"home_ca_fp\":\"" + m.caFingerprint() + "\"").doesNotContain("someone-else");
        assertThat(p).contains("\"zk\":\"ZK\"").contains("\"zone_id\":\"z1\"");
        if (fields.containsKey("lan_https")) assertThat(p).contains("\"lan_https\":\"https://");
        assertThat(m.caFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void withNothingToStampTheInviteIsUnchanged() {
        var url = invite("{\"zone_id\":\"z1\"}");
        assertThat(RelayNkeyAdminMain.stampFieldsIntoInviteUrl(url, Map.of(), new ObjectMapper())).isEqualTo(url);
    }
}
