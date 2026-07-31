package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodingFamiliarRegistryTest {

    private static final String BONDHOLDER = "did:wyrd:user:operator";
    private static final String PARENT = "did:wyrd:companion:wyrd-of-operator";

    @Test void emptyDirectory_getsEmpty(@TempDir Path tmp) {
        var reg = new CodingFamiliarRegistry(tmp);
        assertThat(reg.get(BONDHOLDER)).isEmpty();
        assertThat(reg.list()).isEmpty();
    }

    @Test void saveAndRoundTrip(@TempDir Path tmp) throws IOException {
        var reg = new CodingFamiliarRegistry(tmp);
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "Coder");
        reg.save(id);

        var loaded = reg.get(BONDHOLDER);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().did()).isEqualTo(id.did());
        assertThat(loaded.get().name()).isEqualTo("Coder");
        assertThat(loaded.get().parentAgentDid()).isEqualTo(PARENT);
    }

    @Test void saveCreatesFamiliarsSubdir(@TempDir Path tmp) throws IOException {
        var reg = new CodingFamiliarRegistry(tmp);
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, null);
        reg.save(id);

        var familiarsDir = tmp.resolve("familiars");
        assertThat(Files.isDirectory(familiarsDir)).isTrue();
        try (var stream = Files.list(familiarsDir)) {
            var files = stream.toList();
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getFileName().toString())
                .startsWith("codeplane-")
                .endsWith(".json");
        }
    }

    @Test void freshRegistry_readsFromDisk(@TempDir Path tmp) throws IOException {
        var first = new CodingFamiliarRegistry(tmp);
        first.save(CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "Compañero"));

        var second = new CodingFamiliarRegistry(tmp);
        var loaded = second.get(BONDHOLDER);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("Compañero");
    }

    @Test void list_returnsAllPersistedBondholderDids(@TempDir Path tmp) throws IOException {
        var reg = new CodingFamiliarRegistry(tmp);
        reg.save(CodingFamiliarIdentity.newBorn(
            "did:wyrd:user:operator", "did:wyrd:companion:a", "Coder"));
        reg.save(CodingFamiliarIdentity.newBorn(
            "did:wyrd:user:partner", "did:wyrd:companion:b", "Compañero"));

        var fresh = new CodingFamiliarRegistry(tmp);
        assertThat(fresh.list())
            .containsExactlyInAnyOrder("did:wyrd:user:operator", "did:wyrd:user:partner");
    }

    @Test void fileFor_sanitizesDidColons(@TempDir Path tmp) {
        var reg = new CodingFamiliarRegistry(tmp);
        var file = reg.fileFor(BONDHOLDER);
        // ':' is not portable across filesystems — must be replaced
        assertThat(file.getFileName().toString()).doesNotContain(":");
        assertThat(file.getFileName().toString())
            .startsWith("codeplane-")
            .endsWith(".json");
    }

    @Test void update_replacesPriorIdentityOnSameBondholder(@TempDir Path tmp) throws IOException {
        var reg = new CodingFamiliarRegistry(tmp);
        var v1 = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "Coder");
        reg.save(v1);
        var v2 = v1.withName("弟子");
        reg.save(v2);

        var fresh = new CodingFamiliarRegistry(tmp);
        var loaded = fresh.get(BONDHOLDER);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("弟子");
        // Still exactly one file on disk — same bondholder, same path
        try (var stream = Files.list(tmp.resolve("familiars"))) {
            assertThat(stream.count()).isEqualTo(1);
        }
    }

    @Test void unreadableFile_returnsEmpty(@TempDir Path tmp) throws IOException {
        var reg = new CodingFamiliarRegistry(tmp);
        var file = reg.fileFor(BONDHOLDER);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not valid json {{{");

        assertThat(reg.get(BONDHOLDER)).isEmpty();
    }

    @Test void roundTripPreservesModeLock(@TempDir Path tmp) throws IOException {
        var reg = new CodingFamiliarRegistry(tmp);
        var id = CodingFamiliarIdentity.newBorn(BONDHOLDER, PARENT, "Coder")
            .withModeLock(new CodingFamiliarIdentity.ModeLock(
                "Repair", null, "BONDHOLDER_DECLARED", "portal-prod-api", null));
        reg.save(id);

        var fresh = new CodingFamiliarRegistry(tmp);
        var loaded = fresh.get(BONDHOLDER).orElseThrow();
        assertThat(loaded.modeLock()).isNotNull();
        assertThat(loaded.modeLock().mode()).isEqualTo("Repair");
        assertThat(loaded.modeLock().portalId()).isEqualTo("portal-prod-api");
        assertThat(loaded.modeLock().declaredBy()).isEqualTo("BONDHOLDER_DECLARED");
    }
}
