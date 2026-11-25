package com.rushpromise.booking;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.booking.BookingStore.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Profile("!worker")
public class BookingService {
    private final BookingStore store;
    private final PolicyService policyService;
    private final TransactionTemplate transactions;
    private final Timer batchLockTime;

    BookingService(BookingStore store, PolicyService policyService, TransactionTemplate transactions, MeterRegistry metrics) {
        this.store = store;
        this.policyService = policyService;
        this.transactions = transactions;
        this.batchLockTime = metrics.timer("rush.booking.batch.lock");
    }

    public Outcome quote(QuoteRequest request) {
        if (!validQuoteRequest(request)) return Outcome.error(400, "INVALID_REQUEST", null);
        Policy policy = policyService.current();
        if (request.grams() > policy.maxGrams()) return Outcome.error(422, "TOO_LARGE", null);
        if (!supportedGrams(request.grams())) return Outcome.error(422, "UNSUPPORTED_WEIGHT", null);
        return transactions.execute(transaction -> {
            Shop shop = store.shop();
            var item = item(request.sku());
            if (item == null) return Outcome.error(404, "ITEM_NOT_FOUND", null);
            var pickup = shop.now().atZone(SHOP_ZONE).toLocalDate().atTime(LocalTime.parse(request.pickupTime()))
                    .atZone(SHOP_ZONE).toInstant();
            store.db.update("INSERT INTO booking_sessions (id,run_id,phone) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING",
                    request.sessionId(), shop.runId(), request.phone());
            var session = session(request.sessionId());
            if (!session.phone().equals(request.phone())) return Outcome.error(409, "SESSION_MISMATCH", null);
            var match = replacement(shop, request, item, policy, pickup);
            if (match == null) {
                store.db.update("UPDATE booking_sessions SET latest_quote_id = NULL WHERE id = ?", request.sessionId());
                return Outcome.error(409, "CONFLICT", "NONE_AVAILABLE");
            }
            String reason = pickup.equals(match.pickupAt()) ? null : availabilityReason(shop, policy, request.sku(), request.grams(), pickup);
            return new Outcome(200, "QUOTED", reason, match, null);
        });
    }

    public Outcome accept(String quoteId, AcceptRequest request, String idempotencyKey) {
        UUID id = parseId(quoteId);
        if (id == null || request == null || !validIdentifier(request.sessionId()) || !validIdentifier(request.turnId())
                || (idempotencyKey != null && !validIdentifier(idempotencyKey))) {
            return Outcome.error(400, "INVALID_REQUEST", null);
        }
        Policy current = policyService.current();
        String derivedKey = "call:" + request.sessionId() + ":quote:" + id;
        String key = idempotencyKey == null ? derivedKey : "client:" + idempotencyKey;
        try {
            return transactions.execute(transaction -> acceptLocked(id, request, current, key));
        } catch (DataAccessException | TransactionException failure) {
            // A lost commit acknowledgement cannot tell us which outcome was durable. Resolve through the same key.
            return Outcome.error(503, "BOOKING_UNCERTAIN", null);
        }
    }

    public Outcome resolve(String quoteId, AcceptRequest request, String idempotencyKey) {
        UUID id = parseId(quoteId);
        if (id == null || request == null || !validIdentifier(request.sessionId()) || !validIdentifier(request.turnId())
                || (idempotencyKey != null && !validIdentifier(idempotencyKey))) {
            return Outcome.error(400, "INVALID_REQUEST", null);
        }
        String key = idempotencyKey == null ? "call:" + request.sessionId() + ":quote:" + id : "client:" + idempotencyKey;
        try {
            return transactions.execute(transaction -> {
                var shop = store.shop();
                lockKey(key);
                var saved = replay(shop, key, id, request.sessionId());
                return saved == null ? Outcome.error(404, "BOOKING_NOT_FOUND", null) : saved;
            });
        } catch (DataAccessException | TransactionException failure) {
            return Outcome.error(503, "BOOKING_UNCERTAIN", null);
        }
    }

    private Outcome acceptLocked(UUID quoteId, AcceptRequest request, Policy current, String key) {
        Shop shop = store.shop();
        lockKey(key);
        var replay = replay(shop, key, quoteId, request.sessionId());
        if (replay != null) return replay;
        var session = session(request.sessionId());
        var quoteRow = store.quote(quoteId);
        if (session == null || quoteRow == null || !quoteRow.quote().sessionId().equals(request.sessionId())) {
            return Outcome.error(404, "QUOTE_NOT_FOUND", null);
        }
        var quote = quoteRow.quote();
        var existingOrder = store.find("SELECT * FROM orders WHERE accepted_quote_id = ?", store::orderRow, quoteId);
        if (existingOrder != null) return decide(shop, key, quoteId, request, new Outcome(200, existingOrder.status(), null, null, existingOrder));
        if (!quoteId.equals(session.latestQuoteId())) {
            return decide(shop, key, quoteId, request, Outcome.error(409, "STALE_ACCEPTANCE", null));
        }
        var item = item(quote.sku());
        var quoteRequest = new QuoteRequest(quote.sessionId(), quoteRow.name(), quoteRow.phone(), quote.sku(), quote.grams(), quote.pickupTime());
        boolean requiresApproval = quote.grams() > current.approvalAboveGrams();
        if (!quote.expiresAt().isAfter(shop.now())) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, current, quote, 410, "EXPIRED", null));
        }
        if (price(item, quote.grams()) != quote.pricePaise() || requiresApproval != quote.requiresApproval()
                || current.pickupGraceMinutes() != quote.policy().pickupGraceMinutes()) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, current, quote, 409, "TERMS_CHANGED", null));
        }
        if (quote.pickupAt().isBefore(shop.now().plus(current.leadMinutes(), ChronoUnit.MINUTES))) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, current, quote, 409, "CONFLICT", "TOO_SOON"));
        }
        if (requiresApproval) {
            lockKey("approval:" + quoteRow.phone());
            Boolean pending = store.db.queryForObject("SELECT EXISTS (SELECT 1 FROM orders WHERE run_id = ? AND phone = ? AND status = 'AWAITING_APPROVAL')",
                    Boolean.class, shop.runId(), quoteRow.phone());
            if (Boolean.TRUE.equals(pending)) {
                return decide(shop, key, quoteId, request, Outcome.error(409, "PENDING_APPROVAL_LIMIT", null));
            }
        }
        var batch = batchLockTime.record(() -> store.find("SELECT phone_grams_free, ready_at, blocked FROM batches WHERE id = ? FOR UPDATE",
                (r, n) -> new Batch(r.getInt("phone_grams_free"), at(r, "ready_at"), r.getBoolean("blocked")), quote.batchId()));
        if (batch.blocked() || batch.free() < quote.grams() || batch.readyAt().isAfter(packReadyCutoff(quote.pickupAt()))) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, current, quote, 409, "CONFLICT", "OUT_OF_STOCK"));
        }
        var slot = store.find("SELECT used, capacity FROM pickup_slots WHERE pickup_at = ? FOR UPDATE",
                (r, n) -> new Slot(r.getInt("used"), r.getInt("capacity")), sqlTime(quote.pickupAt()));
        if (slot.used() >= slot.capacity()) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, current, quote, 409, "CONFLICT", "SLOT_FULL"));
        }
        var order = reserve(shop, quoteRow, request.turnId(), current);
        return decide(shop, key, quoteId, request, new Outcome(201, order.status(), null, null, order));
    }

    private Order reserve(Shop shop, QuoteRow quoteRow, String turnId, Policy policy) {
        var quote = quoteRow.quote();
        store.db.update("UPDATE batches SET phone_grams_free = phone_grams_free - ? WHERE id = ?", quote.grams(), quote.batchId());
        store.db.update("UPDATE pickup_slots SET used = used + 1 WHERE pickup_at = ?", sqlTime(quote.pickupAt()));
        String status = quote.requiresApproval() ? "AWAITING_APPROVAL" : "CONFIRMED";
        Instant deadline = quote.requiresApproval() ? shop.now().plus(policy.approvalHoldMinutes(), ChronoUnit.MINUTES) : null;
        long number = store.db.queryForObject("""
                INSERT INTO orders (id,run_id,accepted_quote_id,accepted_turn_id,customer_name,phone,sku,item_name,grams,price_paise,
                    pickup_at,batch_id,status,approval_expires_at,policy,created_at)
                VALUES (nextval('order_number_seq'),?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING id
                """, Long.class, shop.runId(), quote.id(), turnId, quoteRow.name(), quoteRow.phone(), quote.sku(), quote.itemName(),
                quote.grams(), quote.pricePaise(), sqlTime(quote.pickupAt()), quote.batchId(), status,
                sqlTime(deadline), store.write(policy), sqlTime(shop.now()));
        var order = store.order(number);
        store.event(order, status, shop.now());
        store.message(order, status);
        return order;
    }

    private Outcome requote(Shop shop, QuoteRequest request, Item item, Policy policy, Quote original,
                            int status, String code, String reason) {
        var fresh = replacement(shop, request, item, policy, original.pickupAt());
        if (fresh == null) store.db.update("UPDATE booking_sessions SET latest_quote_id = NULL WHERE id = ?", request.sessionId());
        return new Outcome(status, code, reason == null && fresh == null ? "NONE_AVAILABLE" : reason, fresh, null);
    }

    private Quote replacement(Shop shop, QuoteRequest request, Item item, Policy policy, Instant requested) {
        Instant earliest = shop.now().plus(policy.leadMinutes(), ChronoUnit.MINUTES);
        Instant from = requested.isAfter(earliest) ? requested : earliest;
        var option = store.find("""
                SELECT s.pickup_at, b.id FROM pickup_slots s JOIN batches b
                  ON b.sku = ? AND b.ready_at <= s.pickup_at - INTERVAL '15 minutes'
                  WHERE s.pickup_at >= ? AND s.used < s.capacity AND NOT b.blocked AND b.phone_grams_free >= ?
                  ORDER BY s.pickup_at, b.ready_at, b.id LIMIT 1
                """, (r, n) -> new Option(at(r, "pickup_at"), r.getString("id")), request.sku(), sqlTime(from), request.grams());
        if (option == null) return null;
        var quote = new Quote(UUID.randomUUID(), request.sessionId(), request.sku(), item.name(), request.grams(),
                price(item, request.grams()), shopTime(option.pickupAt()), option.pickupAt(), option.batchId(),
                request.grams() > policy.approvalAboveGrams(), shop.now().plusSeconds(policy.ttlSeconds()), policy);
        store.db.update("""
                INSERT INTO quotes (id,run_id,session_id,customer_name,phone,sku,item_name,grams,price_paise,pickup_at,batch_id,
                    requires_approval,expires_at,policy) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """, quote.id(), shop.runId(), quote.sessionId(), request.customerName().strip(), request.phone(), quote.sku(),
                quote.itemName(), quote.grams(), quote.pricePaise(), sqlTime(quote.pickupAt()), quote.batchId(),
                quote.requiresApproval(), sqlTime(quote.expiresAt()), store.write(quote.policy()));
        store.db.update("UPDATE booking_sessions SET latest_quote_id = ? WHERE id = ?", quote.id(), quote.sessionId());
        return quote;
    }

    private String availabilityReason(Shop shop, Policy policy, String sku, int grams, Instant requested) {
        if (requested.isBefore(shop.now().plus(policy.leadMinutes(), ChronoUnit.MINUTES))) return "TOO_SOON";
        boolean stock = Boolean.TRUE.equals(store.db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM batches WHERE sku = ? AND NOT blocked AND phone_grams_free >= ? AND ready_at <= ?)
                """, Boolean.class, sku, grams, sqlTime(packReadyCutoff(requested))));
        return stock ? "SLOT_FULL" : "OUT_OF_STOCK";
    }

    private Outcome replay(Shop shop, String key, UUID id, String sessionId) {
        return store.find("SELECT quote_id,request_session,status,outcome FROM booking_keys WHERE run_id = ? AND key = ?", (r, n) -> {
            if (!id.equals(r.getObject("quote_id", UUID.class)) || !sessionId.equals(r.getString("request_session"))) {
                return Outcome.error(409, "KEY_REUSED", null);
            }
            var original = store.read(r.getString("outcome"), Outcome.class);
            int status = r.getInt("status");
            return new Outcome(status == 201 ? 200 : status, original.code(), original.reason(), original.quote(), original.order());
        }, shop.runId(), key);
    }

    private Outcome decide(Shop shop, String key, UUID quoteId, AcceptRequest request, Outcome outcome) {
        store.db.update("""
                INSERT INTO booking_keys (run_id,key,quote_id,request_session,request_turn,status,outcome) VALUES (?,?,?,?,?,?,?::jsonb)
                """, shop.runId(), key, quoteId, request.sessionId(), request.turnId(), outcome.status(), store.write(outcome));
        return outcome;
    }

    private void lockKey(String key) {
        store.db.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 2384921))", r -> {}, key);
    }

    private Session session(String sessionId) {
        return store.find("SELECT phone,latest_quote_id FROM booking_sessions WHERE id = ? FOR UPDATE",
                (r, n) -> new Session(r.getString("phone"), r.getObject("latest_quote_id", UUID.class)), sessionId);
    }

    private Item item(String sku) {
        return store.find("SELECT name,price_per_kg_paise FROM items WHERE sku = ? FOR SHARE", (r, n) -> new Item(r.getString("name"), r.getInt("price_per_kg_paise")), sku);
    }

    private static int price(Item item, int grams) {
        return Math.toIntExact((long) item.pricePerKgPaise() * grams / 1000);
    }

    private static Instant packReadyCutoff(Instant pickupAt) {
        return pickupAt.minus(PACK_BUFFER_MINUTES, ChronoUnit.MINUTES);
    }

    private static boolean validQuoteRequest(QuoteRequest request) {
        return request != null && validIdentifier(request.sessionId()) && validPhone(request.phone())
                && request.customerName() != null && !request.customerName().isBlank() && request.customerName().length() <= 100
                && validIdentifier(request.sku()) && request.pickupTime() != null
                && request.pickupTime().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") && request.grams() > 0;
    }

    static boolean validPhone(String value) {
        return value != null && value.matches("\\+[1-9][0-9]{7,14}");
    }

    private static boolean supportedGrams(int grams) {
        return grams == 250 || grams == 500 || (grams >= 1000 && grams % 1000 == 0);
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,160}");
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return null;
        }
    }

    private record Item(String name, int pricePerKgPaise) {}
    private record Batch(int free, Instant readyAt, boolean blocked) {}
    private record Slot(int used, int capacity) {}
    private record Option(Instant pickupAt, String batchId) {}
    private record Session(String phone, UUID latestQuoteId) {}
}
