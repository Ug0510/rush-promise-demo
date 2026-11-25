package com.rushpromise.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    NotificationWorker(OutboxRepository outbox, DeliverySettings settings, ObjectMapper json,
                       @Value("${RUSH_WORKER_BOOT:ready}") String boot) {
        if (!boot.equals("ready")) {
            throw new IllegalStateException("Worker startup blocked by this isolated demo revision");
        }
        this.outbox = outbox;
        this.settings = settings;
        if (settings.live()) {
            settings.requireLiveConfiguration();
        }
        this.provider = settings.live() ? new TwilioWhatsApp(settings, json) : null;
    }

    @Scheduled(fixedDelayString = "${RUSH_WORKER_POLL_MS:750}")
    void poll() {
        var claim = outbox.claim(settings.live(), settings.liveRecipient());
        if (claim == null) {
            return;
        }
        var dispatch = outbox.begin(claim, settings.live(), settings.liveRecipient());
        if (dispatch == null) {
            return;
        }

        if (!outbox.stillCurrent(dispatch, settings.live())) {
            outbox.observe(dispatch.attemptId(), new DeliveryResult("SUPPRESSED", null, "ORDER_STATE_CHANGED"));
            return;
        }
        DeliveryResult outcome = provider == null
            ? new DeliveryResult("SIMULATED", null, null) : provider.send(dispatch);
        outbox.observe(dispatch.attemptId(), outcome);
        log.info("Notification {} kind={} outcome={}", dispatch.messageId(), dispatch.kind(), outcome.status());
    }

    @Scheduled(fixedDelay = 5000)
    void recover() {
        outbox.recoverLeases();
    }
}
