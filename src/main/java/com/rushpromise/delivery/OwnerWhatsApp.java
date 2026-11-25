package com.rushpromise.delivery;

import com.rushpromise.booking.BookingModels.Order;
import com.rushpromise.platform.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class OwnerWhatsApp {
    public static final String INBOUND_PATH = "/api/twilio/owner-link";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter PICKUP = DateTimeFormatter.ofPattern("d MMM, h:mm a 'IST'", Locale.ENGLISH)
        .withZone(ZoneId.of("Asia/Kolkata"));
    private final JdbcClient jdbc;
    private final DeliverySettings settings;
    private final String webhook;
    private final boolean enabled;

    OwnerWhatsApp(JdbcClient jdbc, DeliverySettings settings,
                  @Value("${RUSH_OWNER_WHATSAPP_ENABLED:false}") boolean requested,
                  @Value("${RUSH_TWILIO_OWNER_LINK_URL:}") String webhook) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.webhook = webhook;
        this.enabled = requested && settings.callbackConfigured()
            && settings.from().matches("whatsapp:\\+[1-9][0-9]{7,14}") && validWebhook(webhook);
    }

    boolean enabled() { return enabled; }
    String webhook() { return webhook; }

    public record View(String state, Instant windowUntil, Long latestOrder, String latestDelivery) {}
    public record LinkCode(String command, String sandboxNumber, Instant expiresAt) {}
    record ThreadOwner(UUID tenantId, String slug) {}

    public View status(UUID tenant) {
        if (!enabled) return new View("UNAVAILABLE", null, null, null);
        var link = jdbc.sql("""
            SELECT CASE WHEN recipient IS NOT NULL AND last_inbound_at > now() - interval '24 hours' THEN 'LINKED'
                        WHEN recipient IS NOT NULL THEN 'WINDOW_EXPIRED'
                        WHEN link_code_expires_at > now() THEN 'AWAITING_MESSAGE'
                        ELSE 'NOT_LINKED' END AS state,
                   last_inbound_at + interval '24 hours' AS until
            FROM public.rush_owner_whatsapp WHERE tenant_id=:tenant
            """).param("tenant", tenant).query((r,i) -> new View(r.getString("state"),
                instant(r.getObject("until", OffsetDateTime.class)), null, null)).optional()
            .orElse(new View("NOT_LINKED", null, null, null));
        return jdbc.sql("""
            SELECT order_id,status FROM public.rush_owner_whatsapp_messages
              WHERE tenant_id=:tenant ORDER BY created_at DESC LIMIT 1
            """).param("tenant", tenant).query((r,i) -> new View(link.state(), link.windowUntil(),
                r.getLong("order_id"), r.getString("status"))).optional().orElse(link);
    }

    @Transactional
    public LinkCode issue(UUID tenant) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        byte[] bytes = new byte[7];
        RANDOM.nextBytes(bytes);
        String code = "RP" + HexFormat.of().withUpperCase().formatHex(bytes);
        Instant expires = jdbc.sql("""
            INSERT INTO public.rush_owner_whatsapp (tenant_id,link_code_hash,link_code_expires_at)
            VALUES (:tenant,:hash,now()+interval '10 minutes')
            ON CONFLICT (tenant_id) DO UPDATE SET link_code_hash=excluded.link_code_hash,
                link_code_expires_at=excluded.link_code_expires_at
            RETURNING link_code_expires_at
            """).param("tenant", tenant).param("hash", hash(code)).query(OffsetDateTime.class).single().toInstant();
        return new LinkCode("LINK " + code, settings.from().substring("whatsapp:".length()), expires);
    }

    @Transactional
    public void unlink(UUID tenant) {
        jdbc.sql("""
            UPDATE public.rush_owner_whatsapp SET link_id=NULL,recipient=NULL,last_inbound_at=NULL,last_pin_at=NULL,
              link_code_hash=NULL,link_code_expires_at=NULL WHERE tenant_id=:tenant
            """).param("tenant", tenant).update();
        jdbc.sql("""
            UPDATE public.rush_owner_whatsapp_messages SET status='SUPPRESSED',updated_at=now()
            WHERE tenant_id=:tenant AND status='QUEUED'
            """).param("tenant", tenant).update();
    }

    boolean firstInbound(String sid) {
        return jdbc.sql("""
            INSERT INTO public.rush_owner_whatsapp_inbound(provider_sid) VALUES (:sid) ON CONFLICT DO NOTHING
            """).param("sid", sid).update() == 1;
    }

    @Transactional
    boolean link(String code, String recipient) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:recipient))").param("recipient", recipient)
            .query((r,i) -> true).single();
        int updated = jdbc.sql("""
            UPDATE public.rush_owner_whatsapp AS w SET recipient=:recipient,link_id=gen_random_uuid(),
                last_inbound_at=now(),last_pin_at=NULL,link_code_hash=NULL,link_code_expires_at=NULL
            WHERE w.link_code_hash=:hash AND w.link_code_expires_at>now()
                AND NOT EXISTS (SELECT 1 FROM public.rush_owner_whatsapp old
                                WHERE old.recipient=:recipient AND old.tenant_id<>w.tenant_id)
            """).param("recipient", recipient).param("hash", hash(code)).update();
        return updated == 1;
    }

    @Transactional
    ThreadOwner reservePin(String recipient, String slug) {
        return jdbc.sql("""
            UPDATE public.rush_owner_whatsapp w SET last_inbound_at=now(),last_pin_at=now()
            FROM public.rush_tenants t WHERE t.id=w.tenant_id AND t.state='ACTIVE'
              AND w.recipient=:recipient AND w.link_id IS NOT NULL AND (:slug='' OR t.slug=:slug)
              AND (w.last_pin_at IS NULL OR w.last_pin_at<now()-interval '1 minute')
            RETURNING t.id,t.slug
            """).param("recipient",recipient).param("slug",slug)
            .query((r,i)->new ThreadOwner(r.getObject("id",UUID.class),r.getString("slug"))).optional().orElse(null);
    }

    void refreshWindow(String recipient) {
        jdbc.sql("UPDATE public.rush_owner_whatsapp SET last_inbound_at=now() WHERE recipient=:recipient")
            .param("recipient", recipient).update();
    }

    public void confirmed(Order order) {
        if (!enabled || TenantContext.legacy() || !"CONFIRMED".equals(order.status())) return;
        String quantity = "PIECE".equals(order.unit()) ? order.quantity() + (order.quantity() == 1 ? " piece" : " pieces")
            : order.quantity() + " g";
        String body = "New pickup #" + order.id() + " · " + quantity + " " + order.itemName().replaceAll("[\\p{Cntrl}]", " ")
            + " · ₹" + BigDecimal.valueOf(order.pricePaise(), 2).stripTrailingZeros().toPlainString()
            + " cash at pickup · " + PICKUP.format(order.pickupAt()) + ".";
        jdbc.sql("""
            INSERT INTO public.rush_owner_whatsapp_messages (tenant_id,run_id,order_id,link_id,body)
            SELECT t.id,:run,:number,w.link_id,:body FROM public.rush_tenants t
            JOIN public.rush_owner_whatsapp w ON w.tenant_id=t.id
            WHERE t.schema_name=:schema AND t.state='ACTIVE' AND w.recipient IS NOT NULL
              AND w.last_inbound_at>now()-interval '24 hours'
            ON CONFLICT (tenant_id,run_id,order_id) DO NOTHING
            """).param("run", order.runId()).param("number", order.id()).param("body",body)
            .param("schema", TenantContext.schema()).update();
    }

    OutboxRepository.Dispatch claim() {
        if (!enabled) return null;
        return jdbc.sql("""
            WITH next AS (
              SELECT m.id,w.recipient FROM public.rush_owner_whatsapp_messages m
              JOIN public.rush_owner_whatsapp w ON w.tenant_id=m.tenant_id AND w.link_id=m.link_id
              WHERE m.status='QUEUED' AND w.last_inbound_at>now()-interval '24 hours'
              ORDER BY m.created_at,m.id LIMIT 1 FOR UPDATE OF m SKIP LOCKED
            )
            UPDATE public.rush_owner_whatsapp_messages m SET status='SENDING',attempt_id=gen_random_uuid(),updated_at=now()
            FROM next WHERE m.id=next.id RETURNING m.id,m.run_id,m.attempt_id,m.body,next.recipient
            """).query((r,i)->new OutboxRepository.Dispatch(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),
                r.getObject("attempt_id",UUID.class),"NEW_ORDER",r.getString("recipient"),r.getString("body")))
            .optional().orElse(null);
    }

    boolean stillPermitted(OutboxRepository.Dispatch dispatch) {
        return jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM public.rush_owner_whatsapp_messages m
              JOIN public.rush_owner_whatsapp w ON w.tenant_id=m.tenant_id AND w.link_id=m.link_id
              WHERE m.attempt_id=:attempt AND m.status='SENDING' AND w.recipient=:recipient
                AND w.last_inbound_at>now()-interval '24 hours')
            """).param("attempt",dispatch.attemptId()).param("recipient",dispatch.recipient()).query(Boolean.class).single();
    }

    @Transactional
    public boolean observe(UUID attempt, DeliveryResult result) {
        record Previous(String status,String sid) {}
        var prior = jdbc.sql("""
            SELECT status,provider_sid FROM public.rush_owner_whatsapp_messages WHERE attempt_id=:attempt FOR UPDATE
            """).param("attempt",attempt).query((r,i)->new Previous(r.getString("status"),r.getString("provider_sid")))
            .optional().orElse(null);
        if (prior == null) return false;
        String next = result.status().equals("FAILED_RETRYABLE") ? "FAILED" : result.status();
        if ((prior.sid() != null && result.providerSid() != null && !prior.sid().equals(result.providerSid()))
                || !DeliveryResult.canAdvance(prior.status(),next)) return true;
        jdbc.sql("""
            UPDATE public.rush_owner_whatsapp_messages SET status=:status,provider_sid=COALESCE(:sid,provider_sid),
              error=:error,updated_at=now() WHERE attempt_id=:attempt
            """).param("status",next).param("sid",result.providerSid()).param("error",result.error())
            .param("attempt",attempt).update();
        return true;
    }

    void recover() {
        jdbc.sql("""
            UPDATE public.rush_owner_whatsapp_messages m SET status='SUPPRESSED',updated_at=now()
            WHERE m.status='QUEUED' AND NOT EXISTS (SELECT 1 FROM public.rush_owner_whatsapp w
                WHERE w.tenant_id=m.tenant_id AND w.link_id=m.link_id AND w.last_inbound_at>now()-interval '24 hours')
            """).update();
        jdbc.sql("""
            UPDATE public.rush_owner_whatsapp_messages SET status='UNKNOWN',error='DISPATCH_INTERRUPTED',updated_at=now()
              WHERE status='SENDING' AND updated_at<now()-interval '45 seconds'
            """).update();
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static boolean validWebhook(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return "https".equals(uri.getScheme()) && uri.getHost()!=null && INBOUND_PATH.equals(uri.getRawPath())
                && uri.getRawQuery()==null && uri.getFragment()==null && uri.getUserInfo()==null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Instant instant(OffsetDateTime timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
