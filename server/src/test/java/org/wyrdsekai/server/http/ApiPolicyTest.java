package org.wyrdsekai.server.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route registered in the server's source has an access rule, and every rule names a
 * route that exists. A route added without a rule would otherwise quietly fall back to
 * steward-only (safe, but a feature nobody can reach); a rule with a typo would leave its
 * real route on that fallback.
 */
class ApiPolicyTest {

    private static final Pattern ROUTE = Pattern.compile(
        "\\.(get|post|put|delete|patch)\\(\"((?:/api/|/metrics)[^\"]*)\"");

    private static List<String> registeredRoutes() throws IOException {
        var root = Path.of("src/main/java/org/wyrdsekai/server");
        var out = new TreeSet<String>();
        try (var files = Files.walk(root)) {
            for (var f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = ROUTE.matcher(Files.readString(f));
                while (m.find()) out.add(m.group(1).toUpperCase() + " " + m.group(2));
            }
        }
        return new ArrayList<>(out);
    }

    @Test
    void everyRegisteredRouteHasARule() throws IOException {
        var routes = registeredRoutes();
        assertThat(routes).hasSizeGreaterThan(200);
        var missing = routes.stream()
            .filter(r -> !ApiPolicy.isListed(r.substring(0, r.indexOf(' ')), r.substring(r.indexOf(' ') + 1)))
            .toList();
        assertThat(missing).as("routes with no access rule in ApiPolicy").isEmpty();
    }

    @Test
    void everyRuleNamesARealRoute() throws Exception {
        var routes = new TreeSet<>(registeredRoutes());
        var field = ApiPolicy.class.getDeclaredField("LEVELS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var levels = (java.util.Map<String, ApiAuth.Level>) field.get(null);
        var unknown = levels.keySet().stream().filter(k -> !routes.contains(k)).sorted().toList();
        assertThat(unknown).as("ApiPolicy rules that match no registered route").isEmpty();
    }

    @Test
    void theAuditedRoutesAreNoLongerOpen() {
        assertThat(ApiPolicy.levelFor("GET", "/api/pair/household-key")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/pair/household-key/generate")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("GET", "/api/pair/code")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("GET", "/api/shadow")).isEqualTo(ApiAuth.Level.OPERATOR);
        assertThat(ApiPolicy.levelFor("POST", "/api/study/add")).isEqualTo(ApiAuth.Level.OPERATOR);
        assertThat(ApiPolicy.levelFor("POST", "/api/study/import")).isEqualTo(ApiAuth.Level.OPERATOR);
        assertThat(ApiPolicy.levelFor("POST", "/api/federation/propose/{targetZone}")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/federation/accept/{targetZone}")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/federation/revoke/{targetZone}")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/mcp/vouch")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/inference/pause")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/skill/drafts/{id}/approve")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("POST", "/api/library/install")).isEqualTo(ApiAuth.Level.STEWARD);
        assertThat(ApiPolicy.levelFor("GET", "/api/familiar/journal")).isEqualTo(ApiAuth.Level.LOGIN);
        assertThat(ApiPolicy.levelFor("GET", "/api/resident/look")).isEqualTo(ApiAuth.Level.RESIDENT);
        assertThat(ApiPolicy.levelFor("POST", "/api/test/notify")).isEqualTo(ApiAuth.Level.OPERATOR);
        assertThat(ApiPolicy.levelFor("GET", "/api/never-registered")).isEqualTo(ApiAuth.Level.STEWARD);
    }
}
