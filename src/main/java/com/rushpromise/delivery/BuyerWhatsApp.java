package com.rushpromise.delivery;

import com.rushpromise.booking.BookingModels.Order;
import com.rushpromise.platform.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class BuyerWhatsApp {
    private static final DateTimeFormatter PICKUP = DateTimeFormatter.ofPattern("d MMM, h:mm a 'IST'", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));
    private final JdbcClient jdbc;
    private final DeliverySettings settings;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    BuyerWhatsApp(JdbcClient jdbc, DeliverySettings settings, TransactionTemplate transactions,
                  @Value("${RUSH_BUYER_WHATSAPP_ENABLED:false}") boolean requested,
                  @Value("${RUSH_TWILIO_OWNER_LINK_URL:}") String webhook) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.transactions = transactions;
        enabled = requested && settings.callbackConfigured() && OwnerWhatsApp.validWebhook(webhook)
                && settings.from().matches("whatsapp:\\+[1-9][0-9]{7,14}");
    }

    boolean enabled() { return enabled; }

    public record Pairing(String state, String language, String replyText) {}

    @Transactional
    public Pairing offer(String session, String language) {
        boolean hindi = "hi-IN".equals(language);
        if (!enabled || TenantContext.legacy()) return unavailable(language, hindi);
        String hash = digest(session);
        var existing = jdbc.sql("""
                SELECT o.id, o.status IN ('CONFIRMED','PACKED') AND o.pickup_at>now()
                       AND o.created_at BETWEEN now()-interval '5 minutes' AND now() AS recent
                FROM orders o JOIN quotes q ON q.id=o.accepted_quote_id
                WHERE q.session_id=:session AND o.native_identity_kind='CALL'
                """).param("session", session).query((r,i) -> new ExistingOrder(r.getLong("id"), r.getBoolean("recent")))
                .optional().orElse(null);
        if (existing != null && !existing.recent()) {
            return new Pairing("BUYER_WHATSAPP_ALREADY_BOOKED", language, hindi
                    ? "यह ऑर्डर पहले ही दर्ज हो चुका है। फ़ोन पर मिला पिकअप कोड इस्तेमाल करें।"
                    : "This order is already booked. Use the pickup code you heard on the call.");
        }
        var stored = find(hash);
        if (stored == null) {
            UUID nonce = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO public.rush_buyer_pickup_links(call_hash,tenant_id,nonce,challenge_hash,expires_at)
                    SELECT r.call_hash,r.tenant_id,:nonce,:challenge,now()+interval '5 minutes'
                    FROM public.sarvam_shop_routes r JOIN public.rush_tenants t ON t.id=r.tenant_id
                    JOIN public.rush_accounts a ON a.id=t.owner_id
                    WHERE r.call_hash=:hash AND t.schema_name=:schema AND t.state='ACTIVE' AND a.preview_expires_at IS NULL
                    ON CONFLICT DO NOTHING
                    """).param("hash", hash).param("nonce", nonce).param("challenge", challengeHash(challenge(nonce)))
                    .param("schema", TenantContext.schema()).update();
            stored = find(hash);
        }
        if (stored == null || stored.revoked()) return unavailable(language, hindi);
        if (stored.paired()) {
            if (existing != null && queued(session, existing.number())) return new Pairing("BUYER_WHATSAPP_QUEUED", language, hindi
                    ? "पिकअप कोड व्हाट्सऐप पर भेजने के लिए रखा गया है।"
                    : "The pickup code is queued for WhatsApp.");
            if (existing != null) return unavailable(language, hindi);
            return new Pairing("BUYER_WHATSAPP_LINKED", language, hindi
                    ? "व्हाट्सऐप जुड़ गया। ऑर्डर पक्का होने पर वहीं पिकअप कोड भेजने के लिए रखा जाएगा।"
                    : "WhatsApp is linked. Once your order is confirmed, we’ll queue its pickup code there.");
        }
        if (!stored.open()) return new Pairing("BUYER_WHATSAPP_EXPIRED", language, hindi
                ? "व्हाट्सऐप जोड़ने का समय निकल गया। पिकअप कोड फ़ोन पर मिलेगा।"
                : "The WhatsApp link expired. You’ll still hear the pickup code on the call.");
        String code = challenge(stored.nonce());
        String sender = settings.from().substring("whatsapp:".length());
        return new Pairing("BUYER_WHATSAPP_LINK_REQUIRED", language, hindi
                ? "इस ऑर्डर का एक व्हाट्सऐप संदेश पाने के लिए, जुड़े हुए सैंडबॉक्स नंबर " + sender
                    + " पर PICKUP " + code + " लिखकर भेजें। कोड पाँच मिनट में खत्म होगा। आप फ़ोन रखने के बाद भी भेज सकते हैं।"
                : "To receive one WhatsApp about this pickup, send PICKUP " + code + " to the joined Sandbox number " + sender
                    + ". You can send it after hanging up, before the five-minute code expires.");
    }

    private Link find(String hash) {
        return jdbc.sql("""
                SELECT w.nonce,w.recipient IS NOT NULL AND w.consented_at>now()-interval '24 hours' AS paired,
                       w.expires_at>now() AS open,w.revoked_at IS NOT NULL AS revoked
                FROM public.rush_buyer_pickup_links w JOIN public.rush_tenants t ON t.id=w.tenant_id
                WHERE w.call_hash=:hash AND t.schema_name=:schema AND t.state='ACTIVE'
                """).param("hash", hash).param("schema", TenantContext.schema()).query((r,i) -> new Link(
                    r.getObject("nonce", UUID.class), r.getBoolean("paired"), r.getBoolean("open"), r.getBoolean("revoked")))
                .optional().orElse(null);
    }

    void pair(String code, String recipient) {
        if (!enabled) return;
        String challenge = challengeHash(code);
        var binding = jdbc.sql("""
                SELECT w.call_hash,t.schema_name FROM public.rush_buyer_pickup_links w
                JOIN public.rush_tenants t ON t.id=w.tenant_id
                WHERE w.challenge_hash=:challenge AND w.expires_at>now() AND t.state='ACTIVE'
                """).param("challenge", challenge).query((r,i) -> new Binding(r.getString("call_hash"), r.getString("schema_name")))
                .optional().orElse(null);
        if (binding == null) return;
        TenantContext.withSchema(binding.schema(), () -> transactions.execute(status -> {
            lockCall(binding.hash());
            int paired = jdbc.sql("""
                    UPDATE public.rush_buyer_pickup_links SET recipient=:recipient,consented_at=now(),challenge_hash=NULL
                    WHERE call_hash=:hash AND challenge_hash=:challenge AND expires_at>now() AND recipient IS NULL AND revoked_at IS NULL
                    """).param("recipient", recipient).param("hash", binding.hash()).param("challenge", challenge).update();
            if (paired == 1) {
                jdbc.sql("""
                        INSERT INTO public.rush_buyer_pickup_messages(call_hash,run_id,order_id)
                        SELECT :hash,o.run_id,o.id FROM orders o JOIN quotes q ON q.id=o.accepted_quote_id
                        WHERE encode(sha256(convert_to(q.session_id,'UTF8')),'hex')=:hash
                          AND o.native_identity_kind='CALL' AND o.status IN ('CONFIRMED','PACKED') AND o.pickup_at>now()
                          AND o.pickup_code IS NOT NULL
                        ON CONFLICT (call_hash) DO NOTHING
                        """).param("hash", binding.hash()).update();
            }
            return null;
        }));
    }

    void revoke(String recipient) {
        if (!enabled) return;
        jdbc.sql("""
                UPDATE public.rush_buyer_pickup_links SET recipient=NULL,revoked_at=now()
                WHERE recipient=:recipient AND revoked_at IS NULL
                """).param("recipient", recipient).update();
    }

    public void confirmed(String session, Order order) {
        if (!enabled || TenantContext.legacy() || order.pickupCode() == null || !"CONFIRMED".equals(order.status())) return;
        String hash = digest(session);
        lockCall(hash);
        jdbc.sql("""
                INSERT INTO public.rush_buyer_pickup_messages(call_hash,run_id,order_id)
                SELECT w.call_hash,:run,:number FROM public.rush_buyer_pickup_links w
                JOIN public.rush_tenants t ON t.id=w.tenant_id
                WHERE w.call_hash=:hash AND t.schema_name=:schema AND t.state='ACTIVE' AND w.recipient IS NOT NULL
                  AND w.revoked_at IS NULL AND w.consented_at>now()-interval '24 hours'
                ON CONFLICT (call_hash) DO NOTHING
                """).param("hash", hash).param("schema", TenantContext.schema()).param("run", order.runId())
                .param("number", order.id()).update();
    }

    private void lockCall(String hash) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,439710))")
                .param("key", hash).query((row,index) -> true).single();
    }

    public boolean queued(String session, long order) {
        if (!enabled || TenantContext.legacy()) return false;
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM public.rush_buyer_pickup_messages m
                JOIN public.rush_buyer_pickup_links w ON w.call_hash=m.call_hash JOIN public.rush_tenants t ON t.id=w.tenant_id
                WHERE m.call_hash=:hash AND m.order_id=:number AND t.schema_name=:schema
                  AND m.status IN ('QUEUED','SENDING','SUBMITTED','DELIVERED'))
                """).param("hash", digest(session)).param("number", order).param("schema", TenantContext.schema())
                .query(Boolean.class).single());
    }

    OutboxRepository.Dispatch claim() {
        if (!enabled || TenantContext.legacy()) return null;
        return jdbc.sql("""
                WITH next AS (
                  SELECT m.id,w.recipient,o.id AS order_number,o.pickup_code,o.pickup_at,t.display_name
                  FROM public.rush_buyer_pickup_messages m
                  JOIN public.rush_buyer_pickup_links w ON w.call_hash=m.call_hash
                  JOIN public.rush_tenants t ON t.id=w.tenant_id
                  JOIN orders o ON o.run_id=m.run_id AND o.id=m.order_id
                  WHERE t.schema_name=:schema AND t.state='ACTIVE' AND m.status='QUEUED'
                    AND w.recipient IS NOT NULL AND w.revoked_at IS NULL AND w.consented_at>now()-interval '24 hours'
                    AND o.native_identity_kind='CALL' AND o.status IN ('CONFIRMED','PACKED')
                    AND o.pickup_at>now() AND o.pickup_code IS NOT NULL
                  ORDER BY m.created_at,m.id LIMIT 1 FOR UPDATE OF m SKIP LOCKED
                ) UPDATE public.rush_buyer_pickup_messages m SET status='SENDING',attempt_id=gen_random_uuid(),updated_at=now()
                  FROM next WHERE m.id=next.id RETURNING m.id,m.run_id,m.attempt_id,next.recipient,
                    next.order_number,next.pickup_code,next.pickup_at,next.display_name
                """).param("schema", TenantContext.schema()).query((r,i) -> {
                    String shop = r.getString("display_name").replaceAll("[\\p{Cntrl}]", " ");
                    String pickup = PICKUP.format(r.getObject("pickup_at", OffsetDateTime.class));
                    String body = shop + " pickup #" + r.getLong("order_number") + " confirmed for " + pickup
                            + ". Pickup code: " + r.getString("pickup_code") + ". Pay at pickup.";
                    return new OutboxRepository.Dispatch(r.getObject("id", UUID.class), r.getObject("run_id", UUID.class),
                            r.getObject("attempt_id", UUID.class), "PICKUP_CONFIRMATION", r.getString("recipient"), body);
                }).optional().orElse(null);
    }

    boolean stillPermitted(OutboxRepository.Dispatch dispatch) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM public.rush_buyer_pickup_messages m
                JOIN public.rush_buyer_pickup_links w ON w.call_hash=m.call_hash JOIN public.rush_tenants t ON t.id=w.tenant_id
                JOIN orders o ON o.run_id=m.run_id AND o.id=m.order_id
                WHERE m.attempt_id=:attempt AND m.status='SENDING' AND t.schema_name=:schema AND t.state='ACTIVE'
                  AND w.recipient=:recipient AND w.revoked_at IS NULL AND w.consented_at>now()-interval '24 hours'
                  AND o.native_identity_kind='CALL' AND o.status IN ('CONFIRMED','PACKED') AND o.pickup_at>now())
                """).param("attempt", dispatch.attemptId()).param("schema", TenantContext.schema())
                .param("recipient", dispatch.recipient()).query(Boolean.class).single());
    }

    @Transactional
    public boolean observe(UUID attempt, DeliveryResult result) {
        var prior = jdbc.sql("""
                SELECT status,provider_sid FROM public.rush_buyer_pickup_messages WHERE attempt_id=:attempt FOR UPDATE
                """).param("attempt", attempt).query((r,i) -> new Previous(r.getString("status"), r.getString("provider_sid")))
                .optional().orElse(null);
        if (prior == null) return false;
        String next = "FAILED_RETRYABLE".equals(result.status()) ? "FAILED" : result.status();
        if ((prior.sid()!=null && result.providerSid()!=null && !prior.sid().equals(result.providerSid()))
                || !DeliveryResult.canAdvance(prior.status(), next)) return true;
        jdbc.sql("""
                UPDATE public.rush_buyer_pickup_messages SET status=:status,provider_sid=COALESCE(:sid,provider_sid),
                  error=:error,updated_at=now() WHERE attempt_id=:attempt
                """).param("status", next).param("sid", result.providerSid()).param("error", result.error())
                .param("attempt", attempt).update();
        return true;
    }

    void recover() {
        if (!enabled || TenantContext.legacy()) return;
        jdbc.sql("""
                UPDATE public.rush_buyer_pickup_messages m SET status='SUPPRESSED',updated_at=now()
                FROM public.rush_buyer_pickup_links w JOIN public.rush_tenants t ON t.id=w.tenant_id
                WHERE m.call_hash=w.call_hash AND t.schema_name=:schema AND m.status='QUEUED'
                  AND (w.recipient IS NULL OR w.revoked_at IS NOT NULL OR w.consented_at<=now()-interval '24 hours'
                    OR NOT EXISTS (SELECT 1 FROM orders o WHERE o.run_id=m.run_id AND o.id=m.order_id
                      AND o.status IN ('CONFIRMED','PACKED') AND o.pickup_at>now()))
                """).param("schema", TenantContext.schema()).update();
        jdbc.sql("""
                UPDATE public.rush_buyer_pickup_messages m SET status='UNKNOWN',error='DISPATCH_INTERRUPTED',updated_at=now()
                FROM public.rush_buyer_pickup_links w JOIN public.rush_tenants t ON t.id=w.tenant_id
                WHERE m.call_hash=w.call_hash AND t.schema_name=:schema AND m.status='SENDING'
                  AND m.updated_at<now()-interval '45 seconds'
                """).param("schema", TenantContext.schema()).update();
        jdbc.sql("""
                UPDATE public.rush_buyer_pickup_links w SET recipient=NULL
                FROM public.rush_tenants t WHERE t.id=w.tenant_id AND t.schema_name=:schema
                  AND w.recipient IS NOT NULL AND w.consented_at<=now()-interval '24 hours'
                """).param("schema", TenantContext.schema()).update();
    }

    private String challenge(UUID nonce) {
        long number = Integer.toUnsignedLong(ByteBuffer.wrap(mac("buyer-pairing-code:" + nonce)).getInt());
        return Long.toString(10_000_000 + number % 90_000_000);
    }

    private String challengeHash(String code) {
        return HexFormat.of().formatHex(mac("buyer-pairing-digest:" + code));
    }

    private byte[] mac(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.authToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Pickup pairing unavailable", failure);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Pickup pairing unavailable", failure);
        }
    }

    static Pairing unavailable(String language, boolean hindi) {
        return new Pairing("BUYER_WHATSAPP_UNAVAILABLE", language, hindi
                ? "इस बार पिकअप कोड फ़ोन पर मिलेगा।" : "You’ll hear the pickup code on this call.");
    }

    private record Link(UUID nonce, boolean paired, boolean open, boolean revoked) {}
    private record Binding(String hash, String schema) {}
    private record ExistingOrder(long number, boolean recent) {}
    private record Previous(String status, String sid) {}
}
