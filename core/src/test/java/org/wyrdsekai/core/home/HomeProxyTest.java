package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HomeProxyTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("HomeProxyTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("proxy.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        HomeProxy.Holder.set(null);
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void local_proxy_reports_local_zone() {
        var proxy = new HomeProxy.Local(homeClient, "alpha");
        assertThat(proxy.resolveHomeZone("alice")).contains("alpha");
        assertThat(proxy.resolveHomeZone("someone-else")).contains("alpha");
    }

    @Test void local_knock_creates_request() {
        var proxy = new HomeProxy.Local(homeClient, "alpha");
        var result = proxy.knock("bob", "alice", "visiting");

        assertThat(result.ok()).isTrue();
        assertThat(result.remote()).isFalse();
        assertThat(result.homeZone()).isEqualTo("alpha");

        var pending = homeClient.pendingForOwner("alice");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo(result.requestId());
        assertThat(pending.get(0).reason()).isEqualTo("visiting");
    }

    @Test void holder_round_trip() {
        var proxy = new HomeProxy.Local(homeClient, "alpha");
        HomeProxy.Holder.set(proxy);
        assertThat(HomeProxy.Holder.get()).isSameAs(proxy);
        HomeProxy.Holder.set(null);
        assertThat(HomeProxy.Holder.get()).isNull();
    }

    @Test void result_factory_shapes() {
        var unknown = HomeProxy.Result.unknown("nobody");
        assertThat(unknown.ok()).isFalse();
        assertThat(unknown.note()).contains("nobody");

        var err = HomeProxy.Result.error("bad weather");
        assertThat(err.ok()).isFalse();
        assertThat(err.note()).isEqualTo("bad weather");

        var ok = HomeProxy.Result.local("r-1", "alpha");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.remote()).isFalse();

        var rok = HomeProxy.Result.remote("r-2", "beta", "mailed");
        assertThat(rok.ok()).isTrue();
        assertThat(rok.remote()).isTrue();
        assertThat(rok.homeZone()).isEqualTo("beta");
    }
}
