package com.rushpromise.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.platform.TenantContext;
import com.rushpromise.platform.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@EnableScheduling
class NotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private final OutboxRepository outbox;
    private final DeliverySettings settings;
    private final TwilioWhatsApp provider;
    private final TenantDirectory tenants;
    private final OwnerWhatsApp owners;

    NotificationWorker(OutboxRepository outbox, DeliverySettings settings, ObjectMapper json, TenantDirectory tenants, OwnerWhatsApp owners,
                       @Value("${RUSH_WORKER_BOOT:ready}") String boot) {
        if (!boot.equals("ready")) {
            throw new IllegalStateException("Worker startup blocked by this isolated demo revision");
        }
        this.outbox = outbox;
        this.settings = settings;
        this.tenants = tenants;
        this.owners = owners;
        if (settings.live()) {
            settings.requireLiveConfiguration();
        }
        this.provider = settings.live() ? new TwilioWhatsApp(settings, json) : null;
    }

    @Scheduled(fixedDelayString = "${RUSH_WORKER_POLL_MS:750}")
    void poll() {
        forEachQueue(this::deliverOne);
        if (provider == null || !owners.enabled()) return;
        var dispatch = owners.claim();
        if (dispatch == null) return;
        var result = owners.stillPermitted(dispatch) ? provider.sendOwner(dispatch)
            : new DeliveryResult("SUPPRESSED", null, "OWNER_WINDOW_CLOSED");
        owners.observe(dispatch.attemptId(), result);
    }

    private void deliverOne() {
        boolean live = TenantContext.legacy() && settings.live();
        String recipient = live ? settings.liveRecipient() : "";
        var claim = outbox.claim(live, recipient);
        if (claim == null) {
            return;
        }
        var dispatch = outbox.begin(claim, live, recipient);
        if (dispatch == null) {
            return;
        }

        if (!outbox.stillCurrent(dispatch, live)) {
            outbox.observe(dispatch.attemptId(), new DeliveryResult("SUPPRESSED", null, "ORDER_STATE_CHANGED"));
            return;
        }
        DeliveryResult outcome = live ? provider.send(dispatch) : new DeliveryResult("SIMULATED", null, null);
        outbox.observe(dispatch.attemptId(), outcome);
        log.info("Notification {} kind={} outcome={}", dispatch.messageId(), dispatch.kind(), outcome.status());
    }

    @Scheduled(fixedDelay = 5000)
    void recover() {
        forEachQueue(outbox::recoverLeases);
        if (owners.enabled()) owners.recover();
    }

    private void forEachQueue(Runnable task) {
        runInQueue(TenantContext.LEGACY, task);
        for (String schema : tenants.activeSchemas()) {
            runInQueue(schema, task);
        }
    }

    private void runInQueue(String schema, Runnable task) {
        try (TenantContext.Scope ignored = TenantContext.enter(schema)) {
            task.run();
        } catch (RuntimeException failure) {
            log.warn("Notification queue processing failed in {}: {}", schema, failure.getClass().getSimpleName());
        }
    }
}
