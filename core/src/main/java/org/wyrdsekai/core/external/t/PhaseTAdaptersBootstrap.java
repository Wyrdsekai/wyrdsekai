package org.wyrdsekai.core.external.t;

import org.apache.pekko.actor.typed.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.item.ItemScheduleService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * (Phase T) — boot-time wiring for the
 * {@code world.inbound.*} surface.
 *
 * <p>Unlike Phase O/P/Q/R/S/U/V/W which are pure-config and can boot from
 * {@code CoreServices}, Phase T listeners need three runtime dependencies the
 * core-services layer doesn't have:
 * <ul>
 *   <li>JDBC URL — the {@link InboundSubscriptionRegistry} persists
 *       subscriptions to disk so they survive restart.</li>
 *   <li>{@link ActorSystem} — {@link ScheduledListenerBridge} delegates to
 *       {@link ItemScheduleService} which is actor-backed.</li>
 *   <li>{@link ItemScheduleService} — the cron/interval scheduler that the
 *       scheduled-listener bridge piggybacks on.</li>
 * </ul>
 * So Main constructs this bootstrap once it has all three. The result
 * exposes the {@link WebhookListener} so {@code WebhookRoutes} can register
 * the {@code POST /api/webhook/{id}} endpoint with the same instance.
 *
 * <p>Idempotent. Listeners that don't have credentials or a backing daemon
 * (MQTT broker, IMAP host) wire up but return {@code AuthMissing}-shaped
 * results when {@code subscribe} runs; the {@code InboundAdapter} surfaces
 * that to the script as {@code subscribe_failed}. Households that never
 * provision MQTT/email never see the failure surface.</p>
 */
public final class PhaseTAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseTAdaptersBootstrap.class);

    private static volatile boolean initialised = false;
    private static volatile WebhookListener webhookListenerHandle;

    /** Pin a small thread pool for IMAP polling — one shared scheduler keeps
     *  per-subscription tasks off the actor system's dispatcher. */
    private static volatile ScheduledExecutorService emailScheduler;

    private PhaseTAdaptersBootstrap() {}

    /**
     * Wire Phase T. First call wins; subsequent calls return the existing
     * webhook listener. The {@code system} and {@code scheduleService}
     * arguments are required for {@link ScheduledListenerBridge}; pass
     * {@code null} when the caller has no scheduler (the scheduled-kind
     * subscribe will surface {@code "scheduled bridge not wired"} in that
     * mode).
     *
     * @return the {@link WebhookListener} so the HTTP layer can register
     *         {@code WebhookRoutes(listener)}.
     */
    public static synchronized WebhookListener init(String jdbcUrl,
                                                      ActorSystem<?> system,
                                                      ItemScheduleService scheduleService) {
        if (initialised) {
            log.debug("PhaseTAdaptersBootstrap already initialised — skipping");
            return webhookListenerHandle;
        }
        var registry = InboundSubscriptionRegistry.get(jdbcUrl);
        var dispatch = InboundDispatchService.init(registry, HookCallbackInvoker.defaults());

        var webhookListener = new WebhookListener(registry, dispatch);

        // EmailPollListener needs a small dedicated scheduler — IMAP polling
        // is bursty + blocking and we don't want to share Pekko's
        // dispatchers. Default fetcher is {@link JakartaMailFetcher} which
        // speaks IMAP/IMAPS via the same jakarta.mail dep that powers
        // EmailAlertChannel. The fetcher reads connection params from the
        // filter map the script passes to {@code world.inbound.email_watch}
        // (host autodetected from username domain for gmail / outlook /
        // yahoo / icloud / fastmail).
        emailScheduler = Executors.newScheduledThreadPool(2, r -> {
            var t = new Thread(r, "phase-t-email-poll-" + nextId());
            t.setDaemon(true);
            return t;
        });
        var emailListener = new EmailPollListener(registry, dispatch, emailScheduler,
            _ -> JakartaMailFetcher.INSTANCE);

        // MQTT clients are pooled by broker URL via Eclipse Paho. One Paho
        // client per broker, shared across all subscriptions; subscribers
        // for the same topic on the same broker fan out inside the wrapper.
        // Smart-home use case: items can subscribe to
        // {@code tcp://homeassistant.local:1883} and {@code zigbee2mqtt/+}
        // topics out of the box.
        var mqttListener = new MqttListener(registry, dispatch,
            PahoMqttClientFactory::create);

        // FilesystemWatchListener — itemHomeResolver returns the per-item
        // sandboxed root. For now, anchor on the household's data dir
        // {@code data/items/<itemId>/}; missing dirs are created lazily by
        // the listener on first subscribe. SandboxedFs (Phase C) uses the
        // same convention.
        var fsListener = new FilesystemWatchListener(registry, dispatch,
            itemId -> resolveItemHome(itemId));

        var scheduledBridge = (system != null && scheduleService != null)
            ? new ScheduledListenerBridge(registry, dispatch, scheduleService)
            : null;
        if (scheduledBridge == null) {
            log.warn("PhaseT: ItemScheduleService unavailable — "
                + "world.inbound.scheduled subscribes will return "
                + "'scheduled bridge not wired' until Main re-runs init");
        } else {
            // Definitive re-audit fix (#33-1): constructing the bridge is NOT
            // enough — the scheduler's fire-listener slot stays null until we
            // call this, so a scheduled timer would fire + log but never reach
            // the item's inbound hook (silent false-success on
            // world.inbound.scheduled / world.schedule.cron|every). Wire it now
            // so a scheduled fire actually dispatches an InboundEvent.
            scheduledBridge.wireFireListener();
        }

        var adapter = new InboundAdapter(registry, webhookListener, emailListener,
            mqttListener, fsListener, scheduledBridge);
        try {
            ExternalAdapterRegistry.get().register(adapter);
        } catch (Throwable t) {
            log.warn("Phase T inbound adapter failed to register: {}", t.getMessage());
        }

        webhookListenerHandle = webhookListener;
        initialised = true;
        log.info("Phase T inbound listeners wired: webhook={}, email={}, mqtt={}, "
            + "file_watch={}, scheduled={}",
            "live", "live(jakarta.mail)", "live(paho)", "live",
            scheduledBridge != null ? "live" : "off");
        return webhookListener;
    }

    /** The webhook listener constructed by {@link #init}, or null pre-init. */
    public static WebhookListener webhookListener() {
        return webhookListenerHandle;
    }

    /** Test-only escape. Resets all Phase T singletons + the email pool. */
    public static synchronized void resetForTests() {
        if (emailScheduler != null) {
            emailScheduler.shutdownNow();
            emailScheduler = null;
        }
        PahoMqttClientFactory.resetForTests();
        InboundDispatchService.resetForTesting();
        InboundSubscriptionRegistry.resetForTesting();
        webhookListenerHandle = null;
        initialised = false;
    }

    private static Path resolveItemHome(String itemId) {
        var dataDir = SystemPaths.dataDir();
        var home = dataDir.resolve("items").resolve(itemId);
        try {
            Files.createDirectories(home);
        } catch (Exception ignored) {
            // Listener handles missing-path itself; we tried.
        }
        return home;
    }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static int nextId() { return THREAD_SEQ.incrementAndGet(); }
}
