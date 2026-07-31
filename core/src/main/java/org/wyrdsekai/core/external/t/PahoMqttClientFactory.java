package org.wyrdsekai.core.external.t;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * default {@link MqttListener.MqttClient}
 * factory backed by Eclipse Paho.
 *
 * <p>Maps a broker URL ({@code tcp://broker:1883}, {@code ssl://broker:8883})
 * to a single shared {@link IMqttClient}, wraps it as a
 * {@link MqttListener.MqttClient}, and delivers messages back through the
 * registered {@link BiConsumer}.</p>
 *
 * <p>One {@code IMqttClient} per broker URL (Paho is thread-safe). Topic
 * fan-out happens inside the wrapper: subscribing to the same topic from
 * two items creates two consumer entries that both fire on the next message.
 * Disconnect is best-effort on factory close.</p>
 *
 * <p>Auth: passwords + usernames flow through the broker URL's userinfo
 * (e.g. {@code tcp://user:pass@broker:1883}) or via dedicated {@code
 * username}/{@code password} properties on the URL fragment. Future work
 * will resolve credentials from The Safe via {@code CredentialResolver}.</p>
 */
public final class PahoMqttClientFactory {

    private static final Logger log = LoggerFactory.getLogger(PahoMqttClientFactory.class);

    private static final ConcurrentHashMap<String, PahoClientWrapper> CACHE = new ConcurrentHashMap<>();

    private PahoMqttClientFactory() {}

    /** Factory function — pass to {@code new MqttListener(_, _, PahoMqttClientFactory::create)}. */
    public static MqttListener.MqttClient create(String brokerUrl) {
        if (brokerUrl == null || brokerUrl.isBlank()) {
            log.debug("mqtt: blank broker url");
            return null;
        }
        return CACHE.computeIfAbsent(brokerUrl, url -> {
            try {
                var clientId = "wyrdsekai-" + UUID.randomUUID().toString().substring(0, 8);
                var paho = new MqttClient(stripUserInfo(url), clientId, new MemoryPersistence());
                var opts = buildOpts(url);
                paho.connect(opts);
                log.info("mqtt: connected to {} (clientId={})", stripUserInfo(url), clientId);
                return new PahoClientWrapper(paho);
            } catch (Exception e) {
                log.warn("mqtt: connect to {} failed: {}", brokerUrl, e.getMessage());
                return null;
            }
        });
    }

    /** Test-only — drop all cached connections. */
    public static synchronized void resetForTests() {
        for (var w : CACHE.values()) {
            try { w.close(); } catch (Exception _) {}
        }
        CACHE.clear();
    }

    private static MqttConnectOptions buildOpts(String url) {
        var opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(60);
        var userInfo = extractUserInfo(url);
        if (userInfo != null) {
            var idx = userInfo.indexOf(':');
            if (idx > 0) {
                opts.setUserName(userInfo.substring(0, idx));
                opts.setPassword(userInfo.substring(idx + 1).toCharArray());
            } else {
                opts.setUserName(userInfo);
            }
        }
        return opts;
    }

    private static String extractUserInfo(String url) {
        try {
            var u = URI.create(url);
            return u.getUserInfo();
        } catch (Exception e) {
            return null;
        }
    }

    /** Strip user:pass@ from the URL — Paho wants it via setUserName/setPassword instead. */
    private static String stripUserInfo(String url) {
        try {
            var u = URI.create(url);
            if (u.getUserInfo() == null) return url;
            var port = u.getPort() == -1 ? "" : ":" + u.getPort();
            return u.getScheme() + "://" + u.getHost() + port
                + (u.getPath() == null ? "" : u.getPath());
        } catch (Exception e) {
            return url;
        }
    }

    /** Wraps Paho {@link IMqttClient} as the listener's {@link MqttListener.MqttClient}. */
    private static final class PahoClientWrapper implements MqttListener.MqttClient {

        private final IMqttClient paho;
        private final ConcurrentHashMap<String, BiConsumer<String, byte[]>> subscribers = new ConcurrentHashMap<>();

        PahoClientWrapper(IMqttClient paho) {
            this.paho = paho;
        }

        @Override
        public void subscribe(String topic, BiConsumer<String, byte[]> onMessage) {
            try {
                subscribers.put(topic, onMessage);
                paho.subscribe(topic, 1, (t, msg) -> {
                    var sub = subscribers.get(topic);
                    if (sub != null) {
                        sub.accept(t, msg.getPayload());
                    }
                });
            } catch (Exception e) {
                log.warn("mqtt: subscribe {} failed: {}", topic, e.getMessage());
                subscribers.remove(topic);
            }
        }

        @Override
        public void unsubscribe(String topic) {
            try {
                paho.unsubscribe(topic);
            } catch (Exception e) {
                log.debug("mqtt: unsubscribe {} failed: {}", topic, e.getMessage());
            }
            subscribers.remove(topic);
        }

        @Override
        public void close() {
            try {
                if (paho.isConnected()) paho.disconnect();
                paho.close();
            } catch (Exception e) {
                log.debug("mqtt: close failed: {}", e.getMessage());
            }
            subscribers.clear();
        }
    }
}
