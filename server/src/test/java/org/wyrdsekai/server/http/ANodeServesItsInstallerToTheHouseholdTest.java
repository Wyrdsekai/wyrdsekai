package org.wyrdsekai.server.http;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.update.UpdateConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nodes update each other through the mesh: a node that has a release installer in its updates
 * folder serves it, and the release's checksums, to a household peer. Only release-named files, no
 * path in the name, nothing it does not hold.
 */
@Tag("integration")
class ANodeServesItsInstallerToTheHouseholdTest {

    private Javalin app;

    @AfterEach void stop() { if (app != null) app.stop(); }

    @Test
    void the_installer_and_the_checksums_are_served_and_nothing_else_is(@TempDir Path data) throws Exception {
        var updates = Files.createDirectories(data.resolve("updates"));
        Files.writeString(updates.resolve("wyrdsekai_0.5.4_amd64.deb"), "not really a deb");
        Files.writeString(updates.resolve("SHA256SUMS.0.5.4"), "abc  wyrdsekai_0.5.4_amd64.deb\n");
        Files.writeString(data.resolve("secret.txt"), "never served");
        var config = new UpdateConfig("", UpdateConfig.UpdatePolicy.MANUAL, Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var routes = new UpdateRoutes(config, null, null, null, data);
        app = Javalin.create(cfg -> routes.register(cfg.routes)).start(0);
        var http = HttpClient.newHttpClient();
        var base = "http://localhost:" + app.port();

        var deb = http.send(HttpRequest.newBuilder(URI.create(base + "/api/update/artifact/wyrdsekai_0.5.4_amd64.deb")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(deb.statusCode()).isEqualTo(200);
        assertThat(deb.body()).isEqualTo("not really a deb");
        assertThat(deb.headers().firstValue("Content-Length")).contains("16");

        var sums = http.send(HttpRequest.newBuilder(URI.create(base + "/api/update/sums/0.5.4")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(sums.statusCode()).isEqualTo(200);
        assertThat(sums.body()).contains("wyrdsekai_0.5.4_amd64.deb");

        for (var bad : new String[] {"/api/update/artifact/secret.txt", "/api/update/artifact/..%2Fsecret.txt",
                                     "/api/update/artifact/wyrdsekai_0.5.5_amd64.deb", "/api/update/sums/0.5.5", "/api/update/sums/..%2Fsecret.txt"}) {
            var r = http.send(HttpRequest.newBuilder(URI.create(base + bad)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(bad).isEqualTo(404);
            assertThat(r.body()).as(bad).doesNotContain("never served");
        }
        assertThat(UpdateRoutes.ARTIFACT.matcher("Wyrdsekai-0.5.4.pkg").matches()).isTrue();
        assertThat(UpdateRoutes.ARTIFACT.matcher("wyrdsekai_0.5.4~dev10_amd64.deb").matches()).as("a dev deb is a release-shaped name; what is kept is decided by the launcher").isTrue();
        assertThat(UpdateRoutes.ARTIFACT.matcher("../x.deb").matches()).isFalse();
        assertThat(new String(Files.readAllBytes(data.resolve("secret.txt")), StandardCharsets.UTF_8)).isEqualTo("never served");
    }
}
