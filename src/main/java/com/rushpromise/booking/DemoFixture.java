package com.rushpromise.booking;

import static com.rushpromise.booking.BookingStore.SHOP_ZONE;
import static com.rushpromise.booking.BookingStore.sqlTime;
import static com.rushpromise.booking.BookingModels.Policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!worker")
class DemoFixture implements ApplicationRunner {
    static final String POLICY_SEAT_BATCH = "G-policy-seats";
    private final BookingStore store;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final boolean ticking;
    private final LocalDate date;

    DemoFixture(BookingStore store, TransactionTemplate transactions,
                Environment environment,
                @Value("${rush.demo.ticking:false}") boolean ticking,
                @Value("${rush.demo.date:}") String date) {
        this.store = store;
        this.transactions = transactions;
        this.enabled = environment.matchesProfiles("demo");
        this.ticking = ticking;
        this.date = date.isBlank() ? LocalDate.now(SHOP_ZONE) : LocalDate.parse(date);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!enabled) return;
        transactions.executeWithoutResult(transaction -> {
            lock();
            if (Boolean.FALSE.equals(store.db.queryForObject("SELECT EXISTS (SELECT 1 FROM items)", Boolean.class))) {
                seed(UUID.randomUUID());
            }
        });
    }

    boolean enabled() { return enabled; }

    void lock() { store.db.execute("SELECT pg_advisory_xact_lock(2384921)"); }

    Instant at(LocalTime time) { return date.atTime(time).atZone(SHOP_ZONE).toInstant(); }

    void setClock(Instant now) {
        store.db.update("UPDATE shop_state SET demo_now = ?, demo_started_at = CASE WHEN ? THEN statement_timestamp() END WHERE id = 1",
                sqlTime(now), ticking);
    }

    void seed(UUID run) {
        store.db.update("UPDATE shop_state SET run_id = ? WHERE id = 1", run);
        setClock(at(LocalTime.of(17, 45)));
        store.db.update("""
                INSERT INTO items (sku,name,price_per_kg_paise) VALUES ('kaju-katli', 'Kaju katli', 120000), ('barfi', 'Barfi', 90000)
                ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, price_per_kg_paise = EXCLUDED.price_per_kg_paise, sale_unit = 'GRAMS'
                """);
        batch("A", "kaju-katli", "17:30", "READY", 500, 2000);
        batch("B", "kaju-katli", "18:45", "PLANNED", 1000, 0);
        batch("G", "barfi", "17:00", "READY", 8000, 0);
        store.db.update("DELETE FROM pickup_slots");
        store.db.update("DELETE FROM batches WHERE id=?", POLICY_SEAT_BATCH);
        for (String slot : List.of("18:00", "18:15", "18:30", "19:00", "19:30", "20:00", "20:30")) {
            int capacity = slot.equals("18:30") || slot.equals("19:00") ? 1 : 12;
            store.db.update("INSERT INTO pickup_slots (pickup_at,capacity) VALUES (?,?)", sqlTime(at(LocalTime.parse(slot))), capacity);
        }
    }

    String addPolicySeats() {
        var evening = sqlTime(at(LocalTime.of(19, 30)));
        var late = sqlTime(at(LocalTime.of(20, 0)));
        int recorded = store.db.queryForObject("SELECT COUNT(*) FROM demo_policy_seats", Integer.class);
        if (recorded == 8) return "POLICY_FIXTURE_PRESENT";
        int empty = store.db.queryForObject("SELECT COUNT(*) FROM pickup_slots WHERE pickup_at IN (?,?) AND used=0",
                Integer.class, evening, late);
        boolean barfiUntouched = Boolean.TRUE.equals(store.db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM batches WHERE id='G' AND sku='barfi' AND status='READY' AND NOT blocked
                      AND phone_grams_free=phone_grams_total AND phone_grams_free>=6500)
                  AND NOT EXISTS (SELECT 1 FROM quotes WHERE batch_id='G')
                """, Boolean.class));
        if (recorded != 0 || empty != 2 || !barfiUntouched) return "POLICY_FIXTURE_CONFLICT";

        // Seeded seat stock is held in a separate weighed tray with no buyable grams and no delivery rows.
        batch(POLICY_SEAT_BATCH, "barfi", "17:00", "READY", 4000, 0);
        store.db.update("UPDATE batches SET phone_grams_total=6500,phone_grams_free=6500 WHERE id='G'");
        var run = store.shop();
        var normal = new Policy("fixture-normal", "FIXTURE", null, run.now(), false, 5000, 4000, 15, 15, 180, 30);
        for (int seat = 1; seat <= 4; seat++) {
            seedPolicyOrder(run, normal, evening, seat, -seat);
            seedPolicyOrder(run, normal, late, seat, -seat - 4);
        }
        store.db.update("UPDATE batches SET phone_grams_free=0,blocked=TRUE WHERE id=?", POLICY_SEAT_BATCH);
        store.db.update("""
                UPDATE pickup_slots s SET capacity=CASE WHEN pickup_at=? THEN 5 ELSE 10 END,
                    used=(SELECT COUNT(*) FROM demo_policy_seats d WHERE d.pickup_at=s.pickup_at)
                WHERE pickup_at IN (?,?)
                """, evening, evening, late);
        return "POLICY_FIXTURE_READY";
    }

    private void seedPolicyOrder(BookingStore.Shop run, Policy policy, OffsetDateTime pickup, int seat, long number) {
        UUID quote = UUID.randomUUID();
        String session = "policy-fixture-seat-" + (-number);
        String phone = "+12025550101";
        var now = sqlTime(run.now());
        String snapshot = store.write(policy);
        store.db.update("INSERT INTO booking_sessions(id,run_id,phone,latest_quote_id) VALUES (?,?,?,?)",
                session, run.runId(), phone, quote);
        store.db.update("""
                INSERT INTO quotes(id,run_id,session_id,customer_name,phone,sku,item_name,grams,price_paise,
                    pickup_at,batch_id,requires_approval,expires_at,policy,sale_unit)
                VALUES (?,?,?,'Synthetic seat',?,'barfi','Barfi',500,45000,?,'G-policy-seats',FALSE,?,?::jsonb,'GRAMS')
                """, quote, run.runId(), session, phone, pickup, now.plusSeconds(180), snapshot);
        store.db.update("""
                INSERT INTO orders(id,run_id,accepted_quote_id,accepted_turn_id,customer_name,phone,sku,item_name,grams,
                    price_paise,pickup_at,batch_id,status,policy,created_at,sale_unit)
                VALUES (?,?,?,'fixture-yes','Synthetic seat',?,'barfi','Barfi',500,45000,?,'G-policy-seats',
                        'CONFIRMED',?::jsonb,?,'GRAMS')
                """, number, run.runId(), quote, phone, pickup, snapshot, now);
        store.db.update("INSERT INTO demo_policy_seats(pickup_at,seat_number,batch_id,order_id,grams) VALUES (?,?,'G-policy-seats',?,500)",
                pickup, seat, number);
    }

    private void batch(String id, String sku, String time, String status, int grams, int counter) {
        Instant ready = at(LocalTime.parse(time));
        store.db.update("""
                INSERT INTO batches (id,sku,ready_at,status,phone_grams_total,phone_grams_free,counter_grams_counted,counter_counted_at)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO UPDATE SET sku = EXCLUDED.sku, ready_at = EXCLUDED.ready_at,
                    status = EXCLUDED.status, phone_grams_total = EXCLUDED.phone_grams_total, phone_grams_free = EXCLUDED.phone_grams_free,
                    counter_grams_counted = EXCLUDED.counter_grams_counted, counter_counted_at = EXCLUDED.counter_counted_at, blocked = FALSE
                """, id, sku, sqlTime(ready), status, grams, grams, counter, counter > 0 ? sqlTime(ready) : null);
    }
}
