package org.wyrdsekai.server.hermod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.GrantAuthority;
import org.wyrdsekai.hermod.SignedGrant;

import java.security.KeyPairGenerator;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** The grant survives its file form and still verifies — and only for its own domain. */
class AMintedGrantFileOpensTheRightDoorTest {

    @Test
    void mintSerializeVerify() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var now = Instant.now();
        var grant = GrantAuthority.mint("g1", "home", "photos", "phone",
            now, now.plusSeconds(3600), "v1", kp.getPrivate());

        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        var back = json.readValue(json.writeValueAsBytes(grant), SignedGrant.class);

        var verify = GrantAuthority.verifier(kp.getPublic().getEncoded());
        assertTrue(verify.test(back), "file round-trip preserves the signature");
        assertEquals("photos", back.dataDomain());
    }
}
