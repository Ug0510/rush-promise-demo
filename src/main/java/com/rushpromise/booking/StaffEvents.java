package com.rushpromise.booking;

import com.rushpromise.platform.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!worker")
class StaffEvents {
    private static final int MAX_STREAMS = 24;
    private static final Duration HEARTBEAT_DELAY = Duration.ofSeconds(5);
    private final Map<SseEmitter, String> streams = new ConcurrentHashMap<>();
    private final Map<String, String> revisions = new ConcurrentHashMap<>();
    private final JdbcTemplate db;
    private final BookingService booking;
    private Instant lastHeartbeat = Instant.EPOCH;

    StaffEvents(JdbcTemplate db, BookingService booking) {
        this.db = db;
        this.booking = booking;
    }

    SseEmitter connect() {
        if (streams.size() >= MAX_STREAMS) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Staff event streams are busy");
        var stream = new SseEmitter(0L);
        stream.onCompletion(() -> streams.remove(stream));
        stream.onError(failure -> streams.remove(stream));
        stream.onTimeout(() -> streams.remove(stream));
        streams.put(stream, TenantContext.schema());
        // Every connection refreshes; an invalidation may have been missed while the network was down.
        send(stream, "changed");
        return stream;
    }

    @Scheduled(fixedDelayString = "${rush.staff.events-ms:1000}")
    void publish() {
        if (streams.isEmpty()) return;
        var schemas = streams.values().stream().distinct().toList();
        revisions.keySet().retainAll(schemas);
        Instant now = Instant.now();
        boolean heartbeat = !now.isBefore(lastHeartbeat.plus(HEARTBEAT_DELAY));
        if (heartbeat) lastHeartbeat = now;
        for (String schema : schemas) {
            try {
                String current = TenantContext.withSchema(schema, this::fingerprint);
                if (!Objects.equals(current, revisions.put(schema, current))) publishWithin(schema, "changed");
                if (heartbeat) publishWithin(schema, "heartbeat");
            } catch (DataAccessException failure) {
                streams.forEach((stream, ownerSchema) -> {
                    if (ownerSchema.equals(schema)) {
                        streams.remove(stream);
                        stream.completeWithError(failure);
                    }
                });
            }
        }
    }

    private void publishWithin(String schema, String event) {
        streams.forEach((stream, ownerSchema) -> {
            if (ownerSchema.equals(schema)) send(stream, event);
        });
    }

    private String fingerprint() {
        // A single snapshot notices web and worker commits even when they came from a different process.
        String stock = db.queryForObject("""
                SELECT md5(concat_ws('|',
                    (SELECT row(run_id,demo_now,demo_started_at,display_name,profile_updated_at)::text FROM shop_state WHERE id=1),
                    (SELECT jsonb_agg(to_jsonb(b) ORDER BY b.id)::text FROM batches b),
                    (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.pickup_at)::text FROM pickup_slots s),
                    (SELECT COALESCE(MAX(id),0) FROM order_events),
                    (SELECT md5(string_agg(id::text,',' ORDER BY id)) FROM (
                        SELECT id FROM quotes WHERE policy->'slot' IS NOT NULL
                        ORDER BY COALESCE((policy->>'evaluatedAt')::timestamptz,expires_at) DESC LIMIT 100
                    ) latest_policy_quotes),
                    (SELECT jsonb_agg(jsonb_build_array(id,status,updated_at) ORDER BY id)::text FROM outbox_messages)
                ))
                """, String.class);
        var flags = booking.shopPolicy();
        return stock + ":" + flags.version() + ":" + flags.digest() + ":" + flags.source();
    }

    private void send(SseEmitter stream, String event) {
        try {
            stream.send(SseEmitter.event().name(event).data("{}"));
        } catch (IOException | IllegalStateException failure) {
            streams.remove(stream);
            stream.completeWithError(failure);
        }
    }
}
