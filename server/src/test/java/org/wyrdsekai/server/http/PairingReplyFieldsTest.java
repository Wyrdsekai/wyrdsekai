package org.wyrdsekai.server.http;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.PairingService;

import static org.assertj.core.api.Assertions.assertThat;

/** D3: the HTTP pairing replies carry the device's own home-bus login as nats_user / nats_pass. */
class PairingReplyFieldsTest {

    @Test
    void thePairingReplyCarriesTheBusLogin() throws Exception {
        var r = new PairingService.PairingResult("wyrd_dev_x", "hh", "Home", "", "nats://127.0.0.1:4222",
            "https://192.0.2.5:7443", null, null, "phone-abc", "s3cret");
        var json = Json.mapper().readTree(Json.mapper().writeValueAsString(PairingRoutes.PairResultResponse.of(r)));
        assertThat(json.path("nats_user").asText()).isEqualTo("phone-abc");
        assertThat(json.path("nats_pass").asText()).isEqualTo("s3cret");
        assertThat(json.path("token").asText()).isEqualTo("wyrd_dev_x");
        assertThat(json.path("serverUrl").asText()).isEqualTo("https://192.0.2.5:7443");
    }

    @Test
    void theHouseholdKeyReplyOffersTheJoinKeyWhenTheHomeHasItsCa() {
        var body = PairingRoutes.householdKeyBody("wyrd_hk_" + "0".repeat(64), 1L);
        assertThat(body).containsKey("key").containsKey("createdAt");
        if (body.containsKey("home_ca_fp")) {
            assertThat((String) body.get("join_key")).isEqualTo(body.get("key") + "." + body.get("home_ca_fp"));
        }
    }
}
