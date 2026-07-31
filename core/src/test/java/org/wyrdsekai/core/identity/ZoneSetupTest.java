package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZoneSetupTest {

    @Test void create_zone() {
        var creator = PlayerAccount.create("Masumi");
        var zone = ZoneSetup.createZone("Masumi's Zone", creator);

        assertThat(zone.zoneId()).startsWith("zone-");
        assertThat(zone.zoneName()).isEqualTo("Masumi's Zone");
        assertThat(zone.creatorDid()).isEqualTo(creator.did());
        assertThat(zone.secret()).hasSize(32);
        assertThat(zone.createdAt()).isNotNull();
    }

    @Test void generate_and_validate_join_token() {
        var creator = PlayerAccount.create("Masumi");
        var zone = ZoneSetup.createZone("Test Zone", creator);

        var token = ZoneSetup.generateJoinToken(zone, creator.did());
        assertThat(token).isNotBlank();

        var approval = ZoneSetup.validateJoinToken(token, zone.secret());
        assertThat(approval).isPresent();
        assertThat(approval.get().zoneId()).isEqualTo(zone.zoneId());
        assertThat(approval.get().approvedBy()).isEqualTo(creator.did());
    }

    @Test void invalid_token_rejected() {
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);

        var result = ZoneSetup.validateJoinToken("totally-bogus-token", secret);
        assertThat(result).isEmpty();
    }

    @Test void tampered_token_rejected() {
        var creator = PlayerAccount.create("Masumi");
        var zone = ZoneSetup.createZone("Test Zone", creator);

        var token = ZoneSetup.generateJoinToken(zone, creator.did());
        // Tamper by flipping a character
        var tampered = token.substring(0, token.length() - 1)
            + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        var result = ZoneSetup.validateJoinToken(tampered, zone.secret());
        assertThat(result).isEmpty();
    }

    @Test void wrong_secret_rejected() {
        var creator = PlayerAccount.create("Masumi");
        var zone = ZoneSetup.createZone("Test Zone", creator);

        var token = ZoneSetup.generateJoinToken(zone, creator.did());

        var wrongSecret = new byte[32];
        new SecureRandom().nextBytes(wrongSecret);

        var result = ZoneSetup.validateJoinToken(token, wrongSecret);
        assertThat(result).isEmpty();
    }

    @Test void zone_requires_valid_inputs() {
        assertThatThrownBy(() -> new ZoneSetup.ZoneInfo(null, "name", "did", new byte[32], null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneSetup.ZoneInfo("id", null, "did", new byte[32], null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneSetup.ZoneInfo("id", "name", null, new byte[32], null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneSetup.ZoneInfo("id", "name", "did", new byte[16], null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
