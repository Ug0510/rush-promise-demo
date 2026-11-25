package com.rushpromise.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Profile("!worker")
class TenantInventory {
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate db;

    TenantInventory(JdbcTemplate db) { this.db = db; }

    List<Product> products() {
        requireTenant();
        return db.query("""
                SELECT i.sku,i.name,i.sale_unit,i.price_per_kg_paise,
                    COALESCE(SUM(b.phone_grams_total),0)::int AS total,
                    COALESCE(SUM(CASE WHEN NOT b.blocked THEN b.phone_grams_free ELSE 0 END),0)::int AS free
                FROM items i LEFT JOIN batches b ON b.sku=i.sku
                GROUP BY i.sku ORDER BY i.name,i.sku
                """, (r, n) -> new Product(r.getString("sku"), r.getString("name"), r.getString("sale_unit"),
                r.getInt("price_per_kg_paise"), r.getInt("total"), r.getInt("free")));
    }

    @Transactional
    public Product save(ProductInput request) {
        requireTenant();
        String name = TenantDirectory.clean(request.itemName(), 70, true);
        String sku = request.sku() == null || request.sku().isBlank() ? generatedSku(name) : request.sku().strip();
        String unit = request.unit();
        if (!sku.matches("[a-z0-9][a-z0-9-]{0,47}") || !("GRAMS".equals(unit) || "PIECE".equals(unit))
                || request.pricePerUnitPaise() < 1 || request.pricePerUnitPaise() > 1_000_000
                || request.stockQuantity() < 0 || request.stockQuantity() > ("PIECE".equals(unit) ? 10_000 : 100_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the product name, unit, price and quantity");
        }
        final String batch = "owner-" + sku;
        var revision = db.query("SELECT sale_unit FROM items WHERE sku=? FOR UPDATE", (r, n) -> r.getString("sale_unit"), sku)
                .stream().findFirst().orElse(null);
        if (revision != null && !revision.equals(unit)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use a new product to sell it in a different unit");
        }
        int written = db.update("""
                INSERT INTO items (sku,name,price_per_kg_paise,sale_unit) VALUES (?,?,?,?)
                ON CONFLICT (sku) DO UPDATE SET name=EXCLUDED.name,price_per_kg_paise=EXCLUDED.price_per_kg_paise
                WHERE items.sale_unit=EXCLUDED.sale_unit
                """, sku, name, request.pricePerUnitPaise(), unit);
        if (written == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Product unit changed during save");

        Instant now = db.queryForObject("SELECT now FROM shop_clock", OffsetDateTime.class).toInstant();
        Instant ready = readyAt(request.readyAt(), now);
        String state = ready.isAfter(now) ? "PLANNED" : "READY";
        db.update("""
                INSERT INTO batches (id,sku,ready_at,status,phone_grams_total,phone_grams_free,counter_grams_counted)
                VALUES (?,?,?,?,0,0,0) ON CONFLICT (id) DO NOTHING
                """, batch, sku, sqlTime(ready), state);
        db.queryForObject("SELECT id FROM batches WHERE id=? FOR UPDATE", String.class, batch);
        var pending = db.queryForObject("""
                SELECT COALESCE(SUM(grams),0)::int AS held, MIN(pickup_at) AS earliest
                FROM orders WHERE batch_id=? AND status IN ('CONFIRMED','AWAITING_APPROVAL','SHORT')
                """, (row, index) -> new ExistingOrders(row.getInt("held"), row.getObject("earliest", OffsetDateTime.class)), batch);
        if (pending.held() > request.stockQuantity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The count is below orders already held from this tray");
        }
        if (pending.earliest() != null && ready.isAfter(now)
                && ready.plus(15, ChronoUnit.MINUTES).isAfter(pending.earliest().toInstant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a ready time that honors orders already held from this tray");
        }
        int free = request.stockQuantity() - pending.held();
        db.update("""
                UPDATE batches SET phone_grams_total=?,phone_grams_free=?,ready_at=?,status=?,blocked=FALSE WHERE id=?
                """, request.stockQuantity(), free, sqlTime(ready), state, batch);
        db.update("UPDATE orders SET check_stock=FALSE WHERE batch_id=? AND status IN ('CONFIRMED','AWAITING_APPROVAL','SHORT')", batch);
        addPickupWindow(now, ready);
        return new Product(sku, name, unit, request.pricePerUnitPaise(), request.stockQuantity(), free);
    }

    private void addPickupWindow(Instant now, Instant ready) {
        Instant minimum = ready.isAfter(now) ? ready.plus(15, ChronoUnit.MINUTES) : now;
        Instant first = Instant.ofEpochSecond(((minimum.getEpochSecond() + 899) / 900) * 900);
        Instant endOfDay = now.atZone(SHOP_ZONE).toLocalDate().plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        Instant planned = first.plus(4, ChronoUnit.HOURS);
        Instant end = planned.isBefore(endOfDay) ? planned : endOfDay.minusSeconds(1);
        db.update("""
                INSERT INTO pickup_slots (pickup_at,capacity)
                SELECT generate_series(?::timestamptz,?::timestamptz,INTERVAL '15 minutes'),12
                ON CONFLICT (pickup_at) DO NOTHING
                """, sqlTime(first), sqlTime(end));
    }

    private static Instant readyAt(String raw, Instant current) {
        if (raw == null || raw.isBlank()) return current;
        try {
            Instant ready = Instant.parse(raw);
            if (ready.atZone(SHOP_ZONE).toLocalDate().equals(current.atZone(SHOP_ZONE).toLocalDate())) return ready;
        } catch (RuntimeException ignored) {}
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a ready time during today's shop day");
    }

    private static OffsetDateTime sqlTime(Instant time) { return time.atOffset(ZoneOffset.UTC); }

    private static String generatedSku(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.length() > 36) slug = slug.substring(0, 36).replaceAll("-$", "");
        return (slug.isEmpty() ? "item" : slug) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void requireTenant() {
        if (TenantContext.legacy()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in to a shop account");
    }

    record ProductInput(String sku, String itemName, String unit, int pricePerUnitPaise, int stockQuantity, String readyAt) {}
    record Product(String sku, String itemName, String unit, int pricePerUnitPaise, int stockQuantity, int freeQuantity) {}
    private record ExistingOrders(int held, OffsetDateTime earliest) {}
}
