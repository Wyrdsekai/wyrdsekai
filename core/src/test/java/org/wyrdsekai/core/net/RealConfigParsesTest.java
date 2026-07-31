package org.wyrdsekai.core.net;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.EgressGate;
import static org.junit.jupiter.api.Assertions.*;
/** Guards that the real application.conf net + egress-gate blocks parse and default correctly. */
final class RealConfigParsesTest {
    @Test void real_conf_yields_default_posture() {
        var c = ConfigFactory.load();  // loads server/resources/application.conf on the test classpath
        var gate = NetworkGate.fromConfig(c);
        assertFalse(gate.check("ssh","anyhost",null).allowed(), "ssh default-deny from real conf");
        assertTrue(gate.check("http","anyhost","http").allowed(), "http default-allow from real conf");
        var eg = EgressGate.fromConfig(c);
        assertTrue(eg.enabled(), "egress gate enforcing by default from real conf");
    }
}
