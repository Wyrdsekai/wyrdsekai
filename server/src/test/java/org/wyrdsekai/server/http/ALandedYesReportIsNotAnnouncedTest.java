package org.wyrdsekai.server.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.LibraryWebhook;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write-up researched after a person's {@code research yes} lands like any other, but it is not
 * announced to the companions: they talk in rooms where a child may be, and the report is never a
 * child's to read. Any other write-up is announced as before.
 */
class ALandedYesReportIsNotAnnouncedTest {

    @TempDir Path dir;
    private WyrdLuceneStore store;
    private Javalin app;
    private String base;
    private final List<String> asked = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        store = new WyrdLuceneStore(dir.resolve("idx"), 384);
        store.ensureAllCollections();
        var hooks = new LibraryWebhookRoutes(store, () -> List.of(Map.entry("did:key:zMia", "mia")),
            id -> "researchzosho".equals(id) ? "s3cret" : null,
            reportId -> {
                asked.add(reportId);
                return reportId.startsWith("I-0009");
            });
        app = Javalin.create(cfg -> hooks.register(cfg.routes)).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(LibraryWebhookRoutes.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.detachAppender(logs);
        app.stop();
        store.close();
    }

    private HttpResponse<String> landed(String id) throws Exception {
        var body = "{\"library_id\":\"lib_x\",\"library_name\":\"The Stacks\",\"change\":{\"seq\":9,\"at\":\"2026-09-29T03:00:00Z\","
            + "\"kind\":\"investigation\",\"id\":\"" + id + "\",\"event\":\"added\",\"detail\":\"draft by patron:household\"}}";
        var sig = "sha256=" + LibraryWebhook.hmac("s3cret", body.getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(base + "/api/library/webhook/researchzosho"))
            .header("Content-Type", "application/json").header(LibraryWebhook.SIGNATURE_HEADER, sig)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void a_yes_report_is_received_but_not_announced() throws Exception {
        var yes = landed("I-0009-methods");
        assertThat(yes.statusCode()).isEqualTo(200);
        assertThat(asked).containsExactly("I-0009-methods");
        var lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("I-0009-methods").contains("not announced"));

        logs.list.clear();
        assertThat(landed("I-0010-gears").statusCode()).isEqualTo(200);
        assertThat(asked).containsExactly("I-0009-methods", "I-0010-gears");
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
            .noneSatisfy(l -> assertThat(l).contains("not announced"));
    }
}
