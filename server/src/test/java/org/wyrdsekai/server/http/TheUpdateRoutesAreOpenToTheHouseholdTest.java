package org.wyrdsekai.server.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household peer that takes this node's installer carries no login of this node; the two routes
 * that serve public release files are public. On the build box they answered 401 the first time
 * (2026-10-03): they had been listed beside the update status, which needs a login.
 */
class TheUpdateRoutesAreOpenToTheHouseholdTest {

    @Test
    void the_artifact_and_checksum_routes_are_public_as_the_filter_sees_them() {
        assertThat(ApiPolicy.levelFor("GET", "/api/update/status")).as("the node's own status needs a login, as before").isEqualTo(ApiAuth.Level.LOGIN);
        assertThat(ApiPolicy.levelFor("GET", "/api/update/artifact/{name}")).isEqualTo(ApiAuth.Level.PUBLIC);
        assertThat(ApiPolicy.levelFor("GET", "/api/update/sums/{version}")).isEqualTo(ApiAuth.Level.PUBLIC);
        assertThat(ApiPolicy.levelFor("GET", "/api/update/package")).as("the old package route is not opened by this").isNotEqualTo(ApiAuth.Level.PUBLIC);
    }
}
