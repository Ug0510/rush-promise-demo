package com.rushpromise.booking;

import static com.rushpromise.booking.BookingStore.SHOP_ZONE;
import static com.rushpromise.booking.BookingStore.sqlTime;

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
import java.util.List;
import java.util.UUID;

@Component
@Profile("!worker")
class DemoFixture implements ApplicationRunner {
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
                INSERT INTO items VALUES ('kaju-katli', 'Kaju katli', 120000), ('barfi', 'Barfi', 90000)
                ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, price_per_kg_paise = EXCLUDED.price_per_kg_paise
                """);
        batch("A", "kaju-katli", "17:30", "READY", 500, 2000);
        batch("B", "kaju-katli", "18:45", "PLANNED", 1000, 0);
        batch("G", "barfi", "17:00", "READY", 8000, 0);
        store.db.update("DELETE FROM pickup_slots");
        for (String slot : List.of("18:00", "18:15", "18:30", "19:00", "19:30", "20:00", "20:30")) {
            int capacity = slot.equals("18:30") || slot.equals("19:00") ? 1 : 12;
            store.db.update("INSERT INTO pickup_slots (pickup_at,capacity) VALUES (?,?)", sqlTime(at(LocalTime.parse(slot))), capacity);
        }
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
