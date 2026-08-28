package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerAccountTest {

    @Test void create_generates_did() {
        var account = PlayerAccount.create("Operator");

        assertThat(account.did()).startsWith("did:key:z");
        assertThat(account.displayName()).isEqualTo("Operator");
        assertThat(account.createdAt()).isNotNull();
        assertThat(account.lastSeen()).isNotNull();
        assertThat(account.primaryNodeId()).isNull();
        assertThat(account.deviceIds()).isEmpty();
    }

    @Test void create_generates_unique_dids() {
        var a = PlayerAccount.create("Alice");
        var b = PlayerAccount.create("Bob");
        assertThat(a.did()).isNotEqualTo(b.did());
    }

    @Test void jackson_serialization_roundtrip() throws Exception {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        var account = PlayerAccount.create("Operator")
            .withDevice("device-home-server")
            .withPrimaryNode("node-1");

        var json = mapper.writeValueAsString(account);
        var deserialized = mapper.readValue(json, PlayerAccount.class);

        assertThat(deserialized.did()).isEqualTo(account.did());
        assertThat(deserialized.displayName()).isEqualTo("Operator");
        assertThat(deserialized.primaryNodeId()).isEqualTo("node-1");
        assertThat(deserialized.deviceIds()).containsExactly("device-home-server");
    }

    @Test void withDevice_adds_device() {
        var account = PlayerAccount.create("Test");
        var updated = account.withDevice("phone-1").withDevice("laptop-1");

        assertThat(updated.deviceIds()).containsExactly("phone-1", "laptop-1");
        // Original unchanged
        assertThat(account.deviceIds()).isEmpty();
    }

    @Test void withDevice_is_idempotent() {
        var account = PlayerAccount.create("Test").withDevice("phone-1");
        var again = account.withDevice("phone-1");
        assertThat(again.deviceIds()).containsExactly("phone-1");
    }

    @Test void blank_name_rejected() {
        assertThatThrownBy(() -> PlayerAccount.withDid("did:key:z6MkTest", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void blank_did_rejected() {
        assertThatThrownBy(() -> PlayerAccount.withDid("", "Alice"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void withLastSeen_updates_timestamp() {
        var account = PlayerAccount.create("Test");
        var later = Instant.now().plusSeconds(3600);
        var updated = account.withLastSeen(later);

        assertThat(updated.lastSeen()).isEqualTo(later);
        assertThat(updated.did()).isEqualTo(account.did());
    }
}
