package org.wyrdsekai.core.oracle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 — subject-shape CONTRACT between the server's
 * {@link OracleBetweenSync#broadcastPredictions} publisher and the phone
 * subscribers. Both RN ({@code PhoneOracle.ts}) and KMP
 * ({@code PhoneOracle.kt}) subscribe with the literal pattern:
 *
 * <pre>  between.&lt;householdId&gt;.*.*.oracle.predictions</pre>
 *
 * If the server-side subject shape drifts (token count, token order, a
 * renamed segment), phones silently receive nothing — exactly the failure
 * mode the 2026-07-11 audit found when the class was never constructed in
 * prod. This test pins the published subject against the phone pattern
 * under NATS wildcard rules, plus the exact segment structure.
 */
class OracleBetweenSyncSubjectContractTest {

    /** The pattern phones subscribe with — keep in sync with
     *  clients/rn/src/engine/oracle/PhoneOracle.ts and
     *  clients/kmp/.../engine/oracle/PhoneOracle.kt. */
    private static String phoneSubscription(String householdId) {
        return "between." + householdId + ".*.*.oracle.predictions";
    }

    private static String publishedSubject(String householdId, String nodeId) {
        var subjects = new ArrayList<String>();
        var sync = new OracleBetweenSync(nodeId, householdId,
            (subject, data) -> subjects.add(subject));
        sync.broadcastPredictions(List.of(
            new OraclePrediction("p1", "weekly pattern detected", "pattern",
                0.85, "oracle.pattern.periodic", "acf=0.85", false)));
        assertThat(subjects).hasSize(1);
        return subjects.get(0);
    }

    /**
     * Minimal NATS subject matcher: subjects are dot-separated tokens;
     * in a SUBSCRIPTION, {@code *} matches exactly one token and {@code >}
     * matches one-or-more trailing tokens. Wildcards in a PUBLISHED subject
     * are NOT interpreted — they are ordinary tokens (and are matched by a
     * subscription-side {@code *}/{@code >}).
     */
    private static boolean natsMatches(String subscription, String published) {
        var sub = subscription.split("\\.", -1);
        var pub = published.split("\\.", -1);
        for (int i = 0; i < sub.length; i++) {
            if (sub[i].equals(">")) return pub.length > i;
            if (i >= pub.length) return false;
            if (sub[i].equals("*")) {
                if (pub[i].isEmpty()) return false; // empty token never matches
                continue;
            }
            if (!sub[i].equals(pub[i])) return false;
        }
        return sub.length == pub.length;
    }

    @Test
    void published_subject_matches_phone_subscription_pattern() {
        var subject = publishedSubject("household-abc", "node-second-node");
        assertThat(natsMatches(phoneSubscription("household-abc"), subject))
            .as("published subject %s must match phone subscription %s "
                + "under NATS wildcard rules",
                subject, phoneSubscription("household-abc"))
            .isTrue();
    }

    @Test
    void published_subject_has_the_pinned_six_token_structure() {
        var subject = publishedSubject("hh-1", "node-1");
        var tokens = subject.split("\\.", -1);
        assertThat(tokens).hasSize(6);
        assertThat(tokens[0]).isEqualTo("between");
        assertThat(tokens[1]).isEqualTo("hh-1");
        assertThat(tokens[2]).isEqualTo("node-1"); // origin node id
        assertThat(tokens[3]).isNotEmpty();        // per-target slot
        assertThat(tokens[4]).isEqualTo("oracle");
        assertThat(tokens[5]).isEqualTo("predictions");
    }

    @Test
    void does_not_leak_across_households() {
        var subject = publishedSubject("household-abc", "node-second-node");
        assertThat(natsMatches(phoneSubscription("household-OTHER"), subject))
            .as("a phone in another household must NOT receive %s", subject)
            .isFalse();
    }

    @Test
    void kmp_test_fixture_shape_also_matches() {
        // The KMP PhoneOracleTest feeds "between.household-1.server.*.oracle
        // .predictions" into its merge path — assert the server publisher
        // produces exactly that shape for nodeId="server".
        var subject = publishedSubject("household-1", "server");
        assertThat(subject)
            .isEqualTo("between.household-1.server.*.oracle.predictions");
    }

    @Test
    void payload_is_utf8_predictions_json() {
        var payloads = new ArrayList<byte[]>();
        var sync = new OracleBetweenSync("node-1", "hh-1",
            (subject, data) -> payloads.add(data));
        sync.broadcastPredictions(List.of(
            new OraclePrediction("p1", "email spike", "anomaly",
                0.92, "", "z=3.2", true)));
        assertThat(payloads).hasSize(1);
        var json = new String(payloads.get(0), StandardCharsets.UTF_8);
        assertThat(json).startsWith("[").endsWith("]")
            .contains("email spike").contains("0.92");
    }
}
