package com.rushpromise.booking;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.booking.BookingStore.*;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!worker")
public class ShopService {
    private static final String SUPPRESSIBLE = "('QUEUED', 'CLAIMED', 'FAILED_RETRYABLE', 'RETRY')";
    private final BookingStore store;
    private final BookingService booking;
    private final TransactionTemplate transactions;
    private final DemoFixture fixture;

    ShopService(BookingStore store, BookingService booking, TransactionTemplate transactions, DemoFixture fixture) {
        this.store = store;
        this.booking = booking;
        this.transactions = transactions;
        this.fixture = fixture;
    }

    public List<Order> orders() {
        return store.db.query("SELECT * FROM orders ORDER BY pickup_at, id", store::orderRow);
    }

    public Map<String, Object> stock() {
        return transactions.execute(transaction -> {
            var shop = store.shop();
            var batches = store.db.query("SELECT * FROM batches ORDER BY id", (r, n) -> Map.of(
                    "id", r.getString("id"), "sku", r.getString("sku"), "status", r.getString("status"),
                    "readyAt", at(r, "ready_at"), "readyTime", shopTime(at(r, "ready_at")),
                    "phoneGramsTotal", r.getInt("phone_grams_total"), "phoneGramsFree", r.getInt("phone_grams_free"),
                    "counterGramsCounted", r.getInt("counter_grams_counted"), "blocked", r.getBoolean("blocked")));
            var slots = store.db.query("SELECT * FROM pickup_slots ORDER BY pickup_at", (r, n) -> Map.of(
                    "pickupAt", at(r, "pickup_at"), "pickupTime", shopTime(at(r, "pickup_at")),
                    "capacity", r.getInt("capacity"), "used", r.getInt("used")));
            return Map.of("runId", shop.runId(), "now", shop.now(), "shopTime", shopTime(shop.now()), "batches", batches, "slots", slots);
        });
    }

    public Outcome action(long id, String action, Integer grams) {
        return transactions.execute(transaction -> {
            var shop = store.shop();
            var order = lockOrder(id);
            if (order == null) return Outcome.error(404, "ORDER_NOT_FOUND", null);
            String kind;
            switch (action) {
                case "pack" -> {
                    if (!order.status().equals("CONFIRMED")) return orderConflict(order, "NOT_CONFIRMED");
                    var error = store.db.queryForObject("""
                            SELECT CASE WHEN blocked THEN 'BATCH_BLOCKED' WHEN status <> 'READY' OR ready_at > ? THEN 'BATCH_NOT_READY' END
                            FROM batches WHERE id = ?
                            """, String.class, sqlTime(shop.now()), order.batchId());
                    if (error != null) return orderConflict(order, error);
                    store.db.update("UPDATE orders SET status = 'PACKED', packed_at = ? WHERE id = ?", sqlTime(shop.now()), id);
                    kind = "READY";
                }
                case "approve", "reject" -> {
                    if (!order.status().equals("AWAITING_APPROVAL")) return orderConflict(order, order.status());
                    if (!order.approvalExpiresAt().isAfter(shop.now())) return expire(order, shop);
                    if (action.equals("approve")) {
                        store.db.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", id);
                        kind = "APPROVED";
                    } else {
                        release(order, shop, "REJECTED", true, false);
                        kind = "REJECTED";
                    }
                    suppress(id, "AWAITING_APPROVAL");
                }
                case "short" -> {
                    if (!order.status().equals("CONFIRMED")) return orderConflict(order, "NOT_CONFIRMED");
                    if (grams == null || grams <= 0 || grams > order.grams()) return Outcome.error(400, "INVALID_SHORTAGE", null);
                    store.db.update("UPDATE batches SET blocked = TRUE WHERE id = ?", order.batchId());
                    store.db.update("UPDATE orders SET check_stock = TRUE WHERE batch_id = ? AND status IN ('CONFIRMED','AWAITING_APPROVAL')", order.batchId());
                    store.db.update("UPDATE orders SET status = 'SHORT' WHERE id = ?", id);
                    kind = "SHORT";
                }
                case "cancel" -> {
                    if (!List.of("CONFIRMED", "AWAITING_APPROVAL", "PACKED", "SHORT").contains(order.status())) {
                        return orderConflict(order, "NOT_CANCELLABLE");
                    }
                    boolean packed = order.status().equals("PACKED");
                    release(order, shop, "CANCELLED", !packed && !order.status().equals("SHORT"), packed);
                    suppress(id, "READY");
                    suppress(id, "AWAITING_APPROVAL");
                    kind = "CANCELLED";
                }
                case "return" -> {
                    if (!order.returnToTray()) return orderConflict(order, "NOT_AWAITING_RETURN");
                    boolean blocked = Boolean.TRUE.equals(store.db.queryForObject("SELECT blocked FROM batches WHERE id = ?", Boolean.class, order.batchId()));
                    if (blocked) return orderConflict(order, "BATCH_BLOCKED");
                    credit(order);
                    store.db.update("UPDATE orders SET returned_at = ?, return_to_tray = FALSE WHERE id = ?", sqlTime(shop.now()), id);
                    var updated = store.order(id);
                    store.event(updated, "RETURNED", shop.now());
                    return new Outcome(200, "RETURNED", null, null, updated);
                }
                default -> { return Outcome.error(404, "ACTION_NOT_FOUND", null); }
            }
            var updated = store.order(id);
            store.event(updated, kind.equals("READY") ? "PACKED" : kind, shop.now());
            store.message(updated, kind);
            return new Outcome(200, updated.status(), null, null, updated);
        });
    }

    private void suppress(long id, String kind) {
        store.db.update("UPDATE outbox_messages SET status = 'SUPPRESSED', updated_at = CURRENT_TIMESTAMP WHERE order_id = ? AND kind = ? AND status IN " + SUPPRESSIBLE, id, kind);
    }

    private Order lockOrder(long id) {
        var prior = store.order(id);
        if (prior == null) return null;
        lockBatch(prior.batchId());
        return store.find("SELECT * FROM orders WHERE id = ? FOR UPDATE", store::orderRow, id);
    }

    private boolean lockBatch(String id) {
        return store.find("SELECT id FROM batches WHERE id = ? FOR UPDATE", (r, n) -> r.getString("id"), id) != null;
    }

    private Outcome expire(Order order, Shop shop) {
        release(order, shop, "APPROVAL_EXPIRED", true, false);
        suppress(order.id(), "AWAITING_APPROVAL");
        var updated = store.order(order.id());
        store.event(updated, "APPROVAL_EXPIRED", shop.now());
        store.message(updated, "APPROVAL_EXPIRED");
        return orderConflict(updated, "APPROVAL_EXPIRED");
    }

    @Scheduled(fixedDelayString = "${rush.approvals.sweep-ms:2000}")
    public void expireApprovals() {
        List<PendingOrder> ids = store.db.query("""
                SELECT o.id, o.run_id FROM orders o CROSS JOIN shop_clock s
                WHERE o.status = 'AWAITING_APPROVAL' AND o.approval_expires_at <= CASE WHEN ? THEN s.now ELSE statement_timestamp() END AND o.run_id = s.run_id
                """, (r, n) -> new PendingOrder(r.getLong("id"), r.getObject("run_id", UUID.class)), fixture.enabled());
        for (var pending : ids) {
            transactions.executeWithoutResult(transaction -> {
                var shop = store.shop();
                if (!pending.runId().equals(shop.runId())) return;
                var order = lockOrder(pending.id());
                if (order != null && order.status().equals("AWAITING_APPROVAL") && !order.approvalExpiresAt().isAfter(shop.now())) {
                    expire(order, shop);
                }
            });
        }
    }

    private void release(Order order, Shop shop, String status, boolean creditStock, boolean returnToTray) {
        if (order.releasedAt() != null) throw new IllegalStateException("Order release already recorded");
        store.db.update("UPDATE pickup_slots SET used = used - 1 WHERE pickup_at = ?", sqlTime(order.pickupAt()));
        store.db.update("UPDATE orders SET status = ?, released_at = ?, return_to_tray = ?, check_stock = FALSE WHERE id = ?",
                status, sqlTime(shop.now()), returnToTray, order.id());
        if (creditStock) {
            store.db.update("""
                    UPDATE batches SET phone_grams_free = CASE WHEN blocked THEN GREATEST(0, phone_grams_total -
                        (SELECT COALESCE(SUM(grams),0)::int FROM orders WHERE batch_id = batches.id
                         AND status IN ('CONFIRMED','AWAITING_APPROVAL','SHORT')))
                        ELSE phone_grams_free + ? END WHERE id = ?
                    """, order.grams(), order.batchId());
        }
    }

    private void credit(Order order) {
        store.db.update("""
                UPDATE batches SET phone_grams_total = GREATEST(phone_grams_total, phone_grams_free + ? +
                    (SELECT COALESCE(SUM(grams),0)::int FROM orders WHERE batch_id = batches.id
                     AND status IN ('CONFIRMED','AWAITING_APPROVAL','SHORT'))), phone_grams_free = phone_grams_free + ?
                WHERE id = ?
                """, order.grams(), order.grams(), order.batchId());
    }

    public Outcome batchAction(String id, String action, Integer grams) {
        return transactions.execute(transaction -> {
            var shop = store.shop();
            if (!lockBatch(id)) return Outcome.error(404, "BATCH_NOT_FOUND", null);
            if (action.equals("ready")) {
                int updated = store.db.update("UPDATE batches SET status = 'READY' WHERE id = ? AND ready_at <= ?", id, sqlTime(shop.now()));
                return updated == 1 ? Outcome.error(200, "READY", null) : Outcome.error(409, "BATCH_NOT_READY", null);
            }
            if (!action.equals("unblock")) return Outcome.error(404, "ACTION_NOT_FOUND", null);
            if (grams == null || grams < 0 || grams > 100_000) return Outcome.error(400, "COUNT_REQUIRED", null);
            boolean blocked = Boolean.TRUE.equals(store.db.queryForObject("SELECT blocked FROM batches WHERE id = ?", Boolean.class, id));
            if (!blocked) return Outcome.error(409, "NOT_BLOCKED", null);
            int held = store.db.queryForObject("""
                    SELECT COALESCE(SUM(grams),0)::int FROM orders WHERE batch_id = ? AND status IN ('CONFIRMED','AWAITING_APPROVAL','SHORT')
                    """, Integer.class, id);
            boolean stillBlocked = grams < held;
            store.db.update("UPDATE batches SET phone_grams_total = ?, phone_grams_free = ?, blocked = ? WHERE id = ?",
                    grams, Math.max(0, grams - held), stillBlocked, id);
            store.db.update("UPDATE orders SET check_stock = ? WHERE batch_id = ? AND status IN ('CONFIRMED','AWAITING_APPROVAL','SHORT')", stillBlocked, id);
            return Outcome.error(stillBlocked ? 409 : 200, stillBlocked ? "COUNT_BELOW_HOLDS" : "UNBLOCKED", null);
        });
    }

    public Outcome changeClock(String time) {
        if (!fixture.enabled()) return Outcome.error(404, "DEMO_UNAVAILABLE", null);
        Instant now;
        try {
            now = fixture.at(LocalTime.parse(time));
        } catch (RuntimeException invalid) {
            return Outcome.error(400, "INVALID_CLOCK", null);
        }
        transactions.executeWithoutResult(transaction -> {
            fixture.lock();
            fixture.setClock(now);
        });
        return Outcome.error(200, "CLOCK_UPDATED", null);
    }

    public Outcome reset(ResetRequest request) {
        if (!fixture.enabled()) return Outcome.error(404, "DEMO_UNAVAILABLE", null);
        String preset = request == null || request.preset() == null ? "default" : request.preset();
        if (!preset.equals("default") && !preset.equals("incident")) return Outcome.error(400, "INVALID_PRESET", null);
        if (preset.equals("incident") && !BookingService.validPhone(request.phone())) return Outcome.error(400, "PHONE_REQUIRED", null);
        return transactions.execute(transaction -> {
            fixture.lock();
            store.db.execute("TRUNCATE voice_turns, voice_sessions, booking_keys, order_events, quotes, booking_sessions, orders, outbox_messages RESTART IDENTITY CASCADE");
            store.db.execute("ALTER SEQUENCE order_number_seq RESTART WITH 4821");
            var newRun = UUID.randomUUID();
            fixture.seed(newRun);
            if (preset.equals("default")) return Outcome.error(200, "RESET", null);
            var session = "incident-" + newRun;
            var quote = booking.quote(new QuoteRequest(session, "Lata", request.phone(), "kaju-katli", 500, "18:30")).quote();
            var booked = booking.accept(quote.id().toString(), new AcceptRequest(session, "fixture"), null);
            if (booked.status() != 201) throw new IllegalStateException("Incident fixture booking failed");
            store.db.update("UPDATE outbox_messages SET status = 'DELIVERED' WHERE order_id = ? AND kind = 'CONFIRMED'", booked.order().id());
            return new Outcome(200, "INCIDENT_SEEDED", null, null, booked.order());
        });
    }

    private record PendingOrder(long id, UUID runId) {}

    private static Outcome orderConflict(Order order, String reason) {
        return new Outcome(409, "CONFLICT", reason, null, order);
    }
}
