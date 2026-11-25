package com.rushpromise.delivery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class OutboxRepository {
    private static final String PENDING = "'QUEUED','CLAIMED','SENDING','FAILED_RETRYABLE'";
    private final JdbcClient database;

    OutboxRepository(JdbcClient database) {
        this.database = database;
    }

    record Claim(UUID messageId, UUID token) {}
    record Dispatch(UUID messageId, UUID runId, UUID attemptId, String kind, String recipient, String body) {}

    private record Message(UUID id, UUID runId, UUID attemptId, String kind, String recipient, String body,
                           int attemptCount, int retryCount, Instant retryStartedAt) {}
    private record Attempt(String status, String providerSid) {}

    Claim claim(boolean live, String liveRecipient) {
        UUID token = UUID.randomUUID();
        return database.sql("""
            WITH candidate AS (
              SELECT m.id FROM outbox_messages m
              WHERE m.status IN ('QUEUED', 'FAILED_RETRYABLE')
                AND m.next_attempt_at <= now()
                AND EXISTS (SELECT 1 FROM shop_state s WHERE s.run_id = m.run_id)
                AND (NOT :live OR (
                  m.kind IN ('CONFIRMED', 'READY') AND m.recipient = :recipient
                  AND EXISTS (
                    SELECT 1 FROM notification_live_grant g
                    WHERE g.run_id = m.run_id AND g.order_id = m.order_id
                  )
                ))
                AND NOT EXISTS (
                  SELECT 1 FROM outbox_messages prior
                  WHERE prior.run_id = m.run_id AND prior.order_id = m.order_id
                    AND (
                       (prior.kind='CONFIRMED' AND m.kind='READY')
                       OR (prior.kind<>'READY' OR m.kind<>'CONFIRMED')
                          AND (prior.created_at, prior.id) < (m.created_at, m.id)
                    )
                    AND prior.status IN ('QUEUED','CLAIMED','SENDING','FAILED_RETRYABLE','UNKNOWN','NEEDS_REVIEW')
                    AND (NOT :live OR prior.kind IN ('CONFIRMED', 'READY'))
                )
              ORDER BY m.next_attempt_at, m.created_at, m.id
              LIMIT 1 FOR UPDATE OF m SKIP LOCKED
            )
            UPDATE outbox_messages m
               SET status='CLAIMED', lease_token=:token,
                   lease_expires_at=now()+interval '30 seconds', updated_at=now()
              FROM candidate WHERE m.id=candidate.id
              RETURNING m.id
            """).param("live", live).param("recipient", liveRecipient).param("token", token)
            .query((row, index) -> new Claim(row.getObject("id", UUID.class), token)).optional().orElse(null);
    }

    @Transactional
    public Dispatch begin(Claim claim, boolean live, String liveRecipient) {
        String orderStatus = database.sql("""
            SELECT orders.status FROM orders
            JOIN outbox_messages m ON orders.id=m.order_id AND orders.run_id=m.run_id
            WHERE m.id=:id FOR SHARE OF orders
            """).param("id", claim.messageId()).query(String.class).optional().orElse(null);
        Message message = database.sql("""
            SELECT m.* FROM outbox_messages m
            WHERE m.id=:id AND m.lease_token=:token AND m.status='CLAIMED' AND m.lease_expires_at>now()
              AND EXISTS (SELECT 1 FROM shop_state s WHERE s.run_id=m.run_id)
              AND (NOT :live OR (
                  m.kind IN ('CONFIRMED','READY') AND m.recipient=:recipient
                  AND EXISTS (SELECT 1 FROM notification_live_grant g
                              WHERE g.run_id=m.run_id AND g.order_id=m.order_id)
              ))
            FOR UPDATE OF m
            """).param("id", claim.messageId()).param("token", claim.token())
            .param("live", live).param("recipient", liveRecipient)
            .query(OutboxRepository::message).optional().orElse(null);
        if (message == null) {
            return null;
        }
        if (!stillNeeded(message.kind(), orderStatus)) {
            database.sql("""
                UPDATE outbox_messages SET status='SUPPRESSED', last_error='ORDER_STATE_CHANGED',
                  lease_token=NULL, lease_expires_at=NULL, updated_at=now() WHERE id=:id
                """).param("id", message.id()).update();
            return null;
        }
        UUID attemptId = UUID.randomUUID();
        database.sql("""
            INSERT INTO notification_attempts
              (id, message_id, run_id, attempt_number, status, created_at, updated_at)
            VALUES (:attempt, :id, :run, :number, 'SENDING', now(), now())
            """).param("attempt", attemptId).param("id", message.id()).param("run", message.runId())
            .param("number", message.attemptCount() + 1).update();
        database.sql("""
            UPDATE outbox_messages SET status='SENDING', attempt_id=:attempt, attempt_count=attempt_count+1,
                retry_started_at=COALESCE(retry_started_at, now()), provider_sid=NULL, last_error=NULL,
                lease_expires_at=now()+interval '30 seconds', updated_at=now()
            WHERE id=:id
            """).param("attempt", attemptId).param("id", message.id()).update();
        return new Dispatch(message.id(), message.runId(), attemptId, message.kind(), message.recipient(), message.body());
    }

    boolean stillCurrent(Dispatch dispatch, boolean live) {
        return database.sql("""
            SELECT EXISTS (
                SELECT 1 FROM outbox_messages m
                JOIN orders o ON o.id=m.order_id AND o.run_id=m.run_id
                JOIN shop_state s ON s.run_id=m.run_id
                WHERE m.id=:id AND m.run_id=:run AND m.attempt_id=:attempt AND m.status='SENDING'
                AND (NOT :live OR EXISTS (
                    SELECT 1 FROM notification_live_grant g WHERE g.run_id=m.run_id AND g.order_id=m.order_id
                ))
                AND (
                    (m.kind IN ('CONFIRMED','APPROVED') AND o.status IN ('CONFIRMED','PACKED'))
                    OR (m.kind='READY' AND o.status='PACKED')
                    OR (m.kind IN ('SHORT','CANCELLED','AWAITING_APPROVAL','APPROVAL_EXPIRED','REJECTED')
                        AND m.kind=o.status)
                )
            )
            """).param("id", dispatch.messageId()).param("run", dispatch.runId())
            .param("attempt", dispatch.attemptId()).param("live", live).query(Boolean.class).single();
    }

    @Transactional
    public void observe(UUID attemptId, DeliveryResult result) {
        Message message = database.sql("""
            SELECT m.* FROM outbox_messages m
            JOIN notification_attempts a ON a.message_id=m.id AND a.run_id=m.run_id
            WHERE a.id=:attempt
              AND EXISTS (SELECT 1 FROM shop_state s WHERE s.run_id=m.run_id)
            FOR UPDATE OF m
            """).param("attempt", attemptId).query(OutboxRepository::message).optional().orElse(null);
        if (message == null) {
            return;
        }
        Attempt previous = database.sql("""
            SELECT status, provider_sid FROM notification_attempts WHERE id=:attempt FOR UPDATE
            """).param("attempt", attemptId)
            .query((row, index) -> new Attempt(row.getString("status"), row.getString("provider_sid"))).single();
        if (previous.providerSid() != null && result.providerSid() != null
                && !previous.providerSid().equals(result.providerSid())) {
            return;
        }
        if (!DeliveryResult.canAdvance(previous.status(), result.status())) {
            return;
        }
        database.sql("""
            UPDATE notification_attempts
            SET status=:status, provider_sid=COALESCE(:sid,provider_sid), last_error=:error, updated_at=now()
            WHERE id=:attempt
            """).param("attempt", attemptId).param("status", result.status()).param("sid", result.providerSid())
            .param("error", result.error()).update();

        if (result.status().equals("DELIVERED")) {
            database.sql("""
                UPDATE outbox_messages SET status='DELIVERED', provider_sid=:sid, last_error=NULL,
                  sent_at=COALESCE(sent_at, now()), delivered_at=COALESCE(delivered_at, now()),
                  lease_token=NULL, lease_expires_at=NULL, updated_at=now()
                WHERE id=:id AND status<>'DELIVERED'
                """).param("id", message.id()).param("sid", result.providerSid()).update();
        } else if (attemptId.equals(message.attemptId())) {
            if (result.status().equals("FAILED_RETRYABLE")) {
                recordRetry(message);
                return;
            }
            database.sql("""
                UPDATE outbox_messages SET status=:status, provider_sid=COALESCE(:sid,provider_sid),
                  last_error=:error, lease_token=NULL, lease_expires_at=NULL, updated_at=now(),
                  sent_at=CASE WHEN :status='SUBMITTED' THEN COALESCE(sent_at,now()) ELSE sent_at END
                WHERE id=:id AND status<>'DELIVERED'
                """).param("id", message.id()).param("status", result.status()).param("sid", result.providerSid())
                .param("error", result.error()).update();
        }
    }

    private void recordRetry(Message message) {
        var delays = List.of(5, 20, 60, 120);
        int retryCount = message.retryCount() + 1;
        int delay = retryCount <= delays.size() ? delays.get(retryCount - 1) : 0;
        database.sql("""
            WITH retry AS (SELECT :delay>0 AND now()+make_interval(secs=>:delay)
                                  <= :started + interval '10 minutes' AS eligible)
            UPDATE outbox_messages SET status=CASE WHEN retry.eligible THEN 'FAILED_RETRYABLE' ELSE 'FAILED' END,
                last_error=CASE WHEN retry.eligible THEN 'RATE_LIMITED' ELSE 'RETRY_EXHAUSTED' END,
                next_attempt_at=now()+make_interval(secs=>:delay), retry_count=:count,
                lease_expires_at=NULL, lease_token=NULL, updated_at=now()
            FROM retry WHERE id=:id
            """).param("id", message.id()).param("count", retryCount).param("delay", delay)
            .param("started", OffsetDateTime.ofInstant(message.retryStartedAt(), ZoneOffset.UTC)).update();
    }

    @Transactional
    public void recoverLeases() {
        database.sql("""
            UPDATE outbox_messages SET status='QUEUED', lease_token=NULL, lease_expires_at=NULL,
                next_attempt_at=now(), updated_at=now() WHERE status='CLAIMED' AND lease_expires_at<=now()
            """).update();
        database.sql("""
            WITH expired AS (
                UPDATE outbox_messages SET status='UNKNOWN', last_error='SEND_LEASE_EXPIRED',
                    lease_token=NULL, lease_expires_at=NULL, updated_at=now()
                WHERE status='SENDING' AND lease_expires_at<=now()
                RETURNING attempt_id
            )
            UPDATE notification_attempts a SET status='UNKNOWN', last_error='SEND_LEASE_EXPIRED', updated_at=now()
            FROM expired WHERE a.id=expired.attempt_id AND a.status='SENDING'
            """).update();
        database.sql("""
            UPDATE outbox_messages SET status='NEEDS_REVIEW', updated_at=now()
            WHERE status='UNKNOWN' AND updated_at<=now()-interval '2 minutes'
            """).update();
    }

    long pending(boolean readyOnly) {
        return database.sql("""
            SELECT COUNT(*) FROM outbox_messages m
            WHERE EXISTS (SELECT 1 FROM shop_state s WHERE s.run_id=m.run_id)
              AND (NOT :ready OR m.kind='READY') AND m.status IN (
            """ + PENDING + ")").param("ready", readyOnly).query(Long.class).single();
    }

    double oldestAge() {
        return database.sql("""
            SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM now()-MIN(m.created_at)),0),0)::float8
            FROM outbox_messages m
            WHERE EXISTS (SELECT 1 FROM shop_state s WHERE s.run_id=m.run_id) AND m.status IN (
            """ + PENDING + ")").query(Double.class).single();
    }

    private static Message message(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        OffsetDateTime retryStartedAt = row.getObject("retry_started_at", OffsetDateTime.class);
        return new Message(row.getObject("id", UUID.class), row.getObject("run_id", UUID.class),
            row.getObject("attempt_id", UUID.class), row.getString("kind"), row.getString("recipient"),
            row.getString("body"), row.getInt("attempt_count"), row.getInt("retry_count"),
            retryStartedAt == null ? null : retryStartedAt.toInstant());
    }

    private static boolean stillNeeded(String kind, String status) {
        if (status == null) {
            return false;
        }
        return switch (kind) {
            case "CONFIRMED", "APPROVED" -> status.equals("CONFIRMED") || status.equals("PACKED");
            case "READY" -> status.equals("PACKED");
            case "SHORT" -> status.equals("SHORT");
            case "AWAITING_APPROVAL", "APPROVAL_EXPIRED", "REJECTED", "CANCELLED" -> status.equals(kind);
            default -> false;
        };
    }
}
