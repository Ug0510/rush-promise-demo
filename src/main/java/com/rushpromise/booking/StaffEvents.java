package com.rushpromise.booking;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!worker")
class StaffEvents {
    private static final int MAX_STREAMS = 24;
    private static final Duration HEARTBEAT_DELAY = Duration.ofSeconds(5);
    private final Set<SseEmitter> streams = ConcurrentHashMap.newKeySet();
    private final JdbcTemplate db;
    private final PolicyService policy;
    private String revision;
    private Instant lastHeartbeat = Instant.EPOCH;

    StaffEvents(JdbcTemplate db, PolicyService policy) {
        this.db = db;
        this.policy = policy;
    }

    SseEmitter connect() {
        if (streams.size() >= MAX_STREAMS) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Staff event streams are busy");
        var stream = new SseEmitter(0L);
        stream.onCompletion(() -> streams.remove(stream));
        stream.onError(failure -> streams.remove(stream));
        stream.onTimeout(() -> streams.remove(stream));
        streams.add(stream);
        // Every connection refreshes; an invalidation may have been missed while the network was down.
        send(stream, "changed");
        return stream;
    }

    @Scheduled(fixedDelayString = "${rush.staff.events-ms:1000}")
    void publish() {
        if (streams.isEmpty()) return;
        String current;
        try {
            current = fingerprint();
        } catch (DataAccessException failure) {
            streams.forEach(stream -> {
                streams.remove(stream);
                stream.completeWithError(failure);
            });
            return;
        }
        if (!Objects.equals(current, revision)) {
            revision = current;
            streams.forEach(stream -> send(stream, "changed"));
        }
        Instant now = Instant.now();
        if (!now.isBefore(lastHeartbeat.plus(HEARTBEAT_DELAY))) {
            lastHeartbeat = now;
            streams.forEach(stream -> send(stream, "heartbeat"));
        }
    }

    private String fingerprint() {
        // A single snapshot notices web and worker commits even when they came from a different process.
        String stock = db.queryForObject("""
                SELECT md5(concat_ws('|',
                    (SELECT row(run_id,demo_now,demo_started_at,display_name,profile_updated_at)::text FROM shop_state WHERE id=1),
                    (SELECT jsonb_agg(to_jsonb(b) ORDER BY b.id)::text FROM batches b),
                    (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.pickup_at)::text FROM pickup_slots s),
                    (SELECT COALESCE(MAX(id),0) FROM order_events),
                    (SELECT jsonb_agg(jsonb_build_array(id,status,updated_at) ORDER BY id)::text FROM outbox_messages)
                ))
                """, String.class);
        var flags = policy.current();
        return stock + ":" + flags.version() + ":" + flags.source();
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
