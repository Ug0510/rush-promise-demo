package com.rushpromise.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class DeliveryMetrics {
    DeliveryMetrics(MeterRegistry metrics, OutboxRepository outbox) {
        Gauge.builder("rush.messages.pending", outbox, store -> store.pending(false))
            .description("Messages waiting or in flight in the active demo run").register(metrics);
        Gauge.builder("rush.ready.messages.pending", outbox, store -> store.pending(true))
            .description("Pickup-ready messages waiting or in flight in the active demo run").register(metrics);
        Gauge.builder("rush.messages.oldest.pending.age", outbox, OutboxRepository::oldestAge)
            .baseUnit("seconds").description("Age of the oldest pending message in the active demo run").register(metrics);
    }
}
