package org.wyrdsekai.core.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.interiority.ChronicleEntryStore;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * end to end: a seed made on one node brings the same
 * companion back on another, key and all, and never displaces anyone.
 */
class RecoverySeedServiceTest {

    private static final char[] PASS = "a long enough passphrase".toCharArray();

    @TempDir Path tmp;

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
        ChronicleEntryStore.resetForTests();
    }

    /** One node: its database, its household secret, its souls folder. */
    private record Node(String jdbc, byte[] secret, SqlSoulStore souls, BondStore bonds,
                        Path soulsDir, Path seedDir, RecoverySeedService seeds) {
        void activate() {
            AgentIdentityProvisioner.reset();
            AgentIdentityProvisioner.init(jdbc, () -> secret);
            ChronicleEntryStore.setInstance(new ChronicleEntryStore(jdbc));
        }
    }

    private Node node(String name) throws Exception {
        var dir = tmp.resolve(name);
        Files.createDirectories(dir);
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        var bonds = new BondStore(jdbc);
        var souls = new SqlSoulStore(jdbc, SqlDialect.fromJdbcUrl(jdbc), null, bonds);
        var soulsDir = dir.resolve("souls");
        var seedDir = dir.resolve("recovery-seed");
        return new Node(jdbc, secret, souls, bonds, soulsDir, seedDir,
            new RecoverySeedService(souls, bonds, soulsDir, seedDir));
    }

    /** A companion born on {@code n}: key minted, soul stored, birth record written, one bond. */
    private String bear(Node n, String name, String entityId) throws Exception {
        n.activate();
        var minted = AgentIdentityProvisioner.mint(entityId);
        var identity = AgentIdentityProvisioner.find(minted.did()).orElseThrow();
        var profile = new AgentProfile(name, entityId, "agent", "a companion",
            "You are " + name + ".", 8192, 1024, 0.7, null);
        n.souls().store(SoulManifest.birth(minted.did(), minted.publicKeyMultibase(),
            identity.keyLog(), profile, GenomeProfile.defaults()));
        n.bonds().save(Bond.acquaintance(minted.did(), "did:key:z6MkTheBondholder"));
        Files.createDirectories(n.soulsDir());
        Files.writeString(n.soulsDir().resolve(entityId + ".did"), minted.did() + "\n");
        return minted.did();
    }

    @Test
    void a_seed_made_on_one_node_brings_her_back_on_another_as_herself() throws Exception {
        var home = node("home");
        var did = bear(home, "Mia", "mia");

        var made = home.seeds().generate("mia", PASS);
        assertThat(made.summary().did()).isEqualTo(did);
        assertThat(made.summary().carriesKey()).isTrue();
        assertThat(made.localCopy()).exists();
        assertThat(Files.getPosixFilePermissions(made.localCopy()))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        var sealed = new String(made.file(), StandardCharsets.ISO_8859_1);
        assertThat(sealed).doesNotContain(did).doesNotContain("Mia").doesNotContain("You are Mia");

        var fresh = node("fresh");
        fresh.activate();
        var seen = fresh.seeds().verify(made.file(), PASS);
        assertThat(seen.name()).isEqualTo("Mia");
        assertThat(seen.hereAlready()).isFalse();

        fresh.seeds().restore(made.file(), PASS);

        assertThat(fresh.souls().latest(did)).isPresent()
            .get().extracting(m -> m.profile().name()).isEqualTo("Mia");
        assertThat(fresh.bonds().bondsForAgent(did)).hasSize(1);
        assertThat(Files.readString(fresh.soulsDir().resolve("mia.did")).strip()).isEqualTo(did);
        assertThat(AgentIdentityProvisioner.existingDidFor("mia")).contains(did);

        // Her key came with her and is sealed under the NEW node's secret: she can sign,
        // and what she signs verifies against the public key her DID encodes.
        assertThat(AgentIdentityProvisioner.canSign(did)).isTrue();
        var data = "still me".getBytes(StandardCharsets.UTF_8);
        var signature = AgentIdentityProvisioner.sign(did, data).orElseThrow();
        var check = Signature.getInstance("Ed25519");
        check.initVerify(DidKey.publicKeyFromDid(did).orElseThrow());
        check.update(data);
        assertThat(check.verify(Base64.getDecoder().decode(signature))).isTrue();

        var notes = ChronicleEntryStore.get().recent(did, Duration.ofDays(1), 10);
        assertThat(notes).anySatisfy(e -> assertThat(e.summary()).contains("Recovery Seed"));
    }

    @Test
    void a_node_that_already_holds_her_refuses_and_changes_nothing() throws Exception {
        var home = node("home");
        bear(home, "Mia", "mia");
        var made = home.seeds().generate(null, PASS);

        assertThatThrownBy(() -> home.seeds().restore(made.file(), PASS))
            .isInstanceOfSatisfying(RecoverySeedService.Refused.class,
                r -> assertThat(r.reason()).isEqualTo(RecoverySeedService.Reason.ALREADY_HERE));
    }

    @Test
    void a_different_companion_under_her_name_is_never_displaced() throws Exception {
        var home = node("home");
        var did = bear(home, "Mia", "mia");
        var made = home.seeds().generate("Mia", PASS);

        var other = node("other");
        var theirs = bear(other, "Mia", "mia");

        assertThatThrownBy(() -> other.seeds().restore(made.file(), PASS))
            .isInstanceOfSatisfying(RecoverySeedService.Refused.class,
                r -> assertThat(r.reason()).isEqualTo(RecoverySeedService.Reason.NAME_TAKEN));
        assertThat(Files.readString(other.soulsDir().resolve("mia.did")).strip()).isEqualTo(theirs);
        assertThat(other.souls().exists(did)).isFalse();
        assertThat(AgentIdentityProvisioner.find(did)).isEmpty();
    }

    @Test
    void the_wrong_passphrase_opens_nothing() throws Exception {
        var home = node("home");
        bear(home, "Mia", "mia");
        var made = home.seeds().generate("Mia", PASS);

        var fresh = node("fresh");
        fresh.activate();
        assertThatThrownBy(() -> fresh.seeds().restore(made.file(), "not the passphrase".toCharArray()))
            .isInstanceOfSatisfying(RecoverySeedService.Refused.class,
                r -> assertThat(r.reason()).isEqualTo(RecoverySeedService.Reason.WRONG_PASSPHRASE));
        assertThatThrownBy(() -> fresh.seeds().verify("not a seed".getBytes(), PASS))
            .isInstanceOfSatisfying(RecoverySeedService.Refused.class,
                r -> assertThat(r.reason()).isEqualTo(RecoverySeedService.Reason.NOT_A_SEED));
    }

    @Test
    void a_short_passphrase_and_an_unnamed_companion_in_a_full_house_are_refused() throws Exception {
        var home = node("home");
        bear(home, "Mia", "mia");
        bear(home, "Rose", "rose");

        assertThatThrownBy(() -> home.seeds().generate("Mia", "short".toCharArray()))
            .isInstanceOfSatisfying(RecoverySeedService.Refused.class,
                r -> assertThat(r.reason()).isEqualTo(RecoverySeedService.Reason.PASSPHRASE_TOO_SHORT));
        assertThatThrownBy(() -> home.seeds().generate("", PASS))
            .isInstanceOfSatisfying(RecoverySeedService.Refused.class, r -> {
                assertThat(r.reason()).isEqualTo(RecoverySeedService.Reason.WHICH_COMPANION);
                assertThat(r.names()).containsExactlyInAnyOrder("Mia", "Rose");
            });
        assertThat(home.seeds().generate("rose", PASS).summary().name()).isEqualTo("Rose");
    }

    @Test
    void a_key_that_is_not_hers_is_refused() throws Exception {
        var home = node("home");
        var did = bear(home, "Mia", "mia");
        var stranger = new byte[32];
        new SecureRandom().nextBytes(stranger);
        assertThatThrownBy(() -> AgentIdentity.restored(did, stranger, List.of(), null, null, home.secret()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
    }
}
