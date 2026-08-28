package org.wyrdsekai.server.hermod;

import io.javalin.websocket.WsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.PairingService;

import java.util.function.Consumer;

/**
 * /ws/hermod — a paired phone's mesh listener channel. Deliberately NOT
 * /ws: this is the task plane, so no session actor and no presence is
 * created here. Auth mirrors /ws device auth: ?device_token=wyrd_dev_…
 * validated against the pairing registry; the paired device id becomes
 * the phone's hermod device id for as long as the socket lives.
 */
public final class HermodPhoneWs implements Consumer<WsConfig> {

    private static final Logger log = LoggerFactory.getLogger(HermodPhoneWs.class);

    private final PhoneDoorProxy proxy;
    private final PairingService pairing;

    public HermodPhoneWs(PhoneDoorProxy proxy, PairingService pairing) {
        this.proxy = proxy;
        this.pairing = pairing;
    }

    @Override
    public void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
            if (!proxy.attached()) {
                log.warn("hermod: phone knocked but mesh is inert (proxy unattached)");
                ctx.closeSession(1013, "mesh inert on this zone");
                return;
            }
            var deviceToken = ctx.queryParam("device_token");
            if (deviceToken == null || !deviceToken.startsWith("wyrd_dev_") || pairing == null) {
                log.warn("hermod: phone rejected — no usable device token");
                ctx.closeSession(4004, "Device token required");
                return;
            }
            var device = pairing.validateDeviceToken(deviceToken);
            if (device.isEmpty()) {
                log.warn("hermod: phone rejected — invalid or revoked device token");
                ctx.closeSession(4004, "Invalid or revoked device token");
                return;
            }
            try {
                pairing.touchDevice(deviceToken);
            } catch (Exception e) {
                // Liveness bookkeeping is best-effort: a failed timestamp
                // write (e.g. DB busy during zone startup) must never bounce
                // a phone whose token already validated.
                log.warn("hermod: touchDevice failed (continuing): {}", e.getMessage());
            }
            var deviceId = device.get().id();
            // ONE channel object per connection: close/error must identify
            // exactly the leg that opened here, or a stale close after a
            // roam would tear down the superseding channel.
            PhoneDoorProxy.PhoneChannel channel = ctx::send;
            ctx.attribute("hermodDeviceId", deviceId);
            ctx.attribute("hermodChannel", channel);
            proxy.connected(deviceId, channel);
            log.info("hermod: phone {} listening ({})", deviceId, device.get().name());
        });
        ws.onMessage(ctx -> {
            String deviceId = ctx.attribute("hermodDeviceId");
            if (deviceId != null) {
                proxy.message(deviceId, ctx.message());
            }
        });
        ws.onClose(ctx -> {
            String deviceId = ctx.attribute("hermodDeviceId");
            PhoneDoorProxy.PhoneChannel channel = ctx.attribute("hermodChannel");
            if (deviceId != null && channel != null) {
                proxy.disconnected(deviceId, channel);
                log.info("hermod: phone {} gone", deviceId);
            }
        });
        ws.onError(ctx -> {
            String deviceId = ctx.attribute("hermodDeviceId");
            PhoneDoorProxy.PhoneChannel channel = ctx.attribute("hermodChannel");
            if (deviceId != null && channel != null) {
                proxy.disconnected(deviceId, channel);
            }
        });
    }
}
