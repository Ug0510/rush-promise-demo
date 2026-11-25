package com.rushpromise.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.booking.BookingModels.Order;
import com.rushpromise.booking.BookingModels.Policy;
import com.rushpromise.booking.BookingModels.Quote;
import com.rushpromise.booking.BookingModels.ShopProfile;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!worker")
class BookingStore {
    static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    static final int PACK_BUFFER_MINUTES = 15;

    final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean demo;

    BookingStore(JdbcTemplate db, ObjectMapper json, Environment environment) {
        this.db = db;
        this.json = json;
        this.demo = environment.matchesProfiles("demo");
    }

    Shop shop() {
        db.execute("SELECT pg_advisory_xact_lock_shared(2384921)");
        String clockQuery = demo ? "SELECT run_id, now FROM shop_clock"
                : "SELECT run_id, statement_timestamp() AS now FROM shop_state WHERE id = 1";
        return db.queryForObject(clockQuery, (row, index) -> new Shop(row.getObject("run_id", UUID.class), at(row, "now")));
    }

    <T> T find(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = db.query(sql, mapper, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    QuoteRow quote(UUID id) {
        return find("SELECT * FROM quotes WHERE id = ?", this::quoteRow, id);
    }

    Order order(long id) {
        return find("SELECT * FROM orders WHERE id = ?", this::orderRow, id);
    }

    QuoteRow quoteRow(ResultSet row, int index) throws SQLException {
        Instant pickup = at(row, "pickup_at");
        Quote quote = new Quote(row.getObject("id", UUID.class), row.getString("session_id"), row.getString("sku"),
                row.getString("item_name"), row.getInt("grams"), row.getInt("price_paise"), shopTime(pickup), pickup,
                row.getString("batch_id"), row.getBoolean("requires_approval"), at(row, "expires_at"),
                read(row.getString("policy"), Policy.class));
        return new QuoteRow(quote, row.getObject("run_id", UUID.class), row.getString("customer_name"), row.getString("phone"));
    }

    Order orderRow(ResultSet row, int index) throws SQLException {
        Instant pickup = at(row, "pickup_at");
        return new Order(row.getLong("id"), row.getObject("run_id", UUID.class), row.getObject("accepted_quote_id", UUID.class),
                row.getString("accepted_turn_id"), row.getString("customer_name"), row.getString("phone"),
                row.getString("sku"), row.getString("item_name"), row.getInt("grams"), row.getInt("price_paise"),
                shopTime(pickup), pickup, row.getString("batch_id"), row.getString("status"), at(row, "approval_expires_at"),
                read(row.getString("policy"), Policy.class), at(row, "created_at"), at(row, "packed_at"),
                at(row, "released_at"), row.getBoolean("return_to_tray"), row.getBoolean("check_stock"));
    }

    ShopProfile profile() {
        return db.queryForObject("SELECT display_name,profile_updated_at FROM shop_state WHERE id=1", this::profileRow);
    }

    ShopProfile saveProfile(String displayName) {
        return db.queryForObject("UPDATE shop_state SET display_name=?,profile_updated_at=statement_timestamp() WHERE id=1 RETURNING display_name,profile_updated_at",
                this::profileRow, displayName);
    }

    private ShopProfile profileRow(ResultSet row, int index) throws SQLException {
        return new ShopProfile(row.getString("display_name"), at(row, "profile_updated_at"));
    }

    void event(Order order, String kind, Instant now) {
        db.update("INSERT INTO order_events (run_id,order_id,kind,created_at) VALUES (?,?,?,?)",
                order.runId(), order.id(), kind, sqlTime(now));
    }

    void message(Order order, String kind) {
        String orderLabel = profile().displayName() + " #" + order.id() + " (" + order.grams() + " g " + order.itemName() + ")";
        String pickup = order.pickupAt().atZone(SHOP_ZONE).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
        String body = switch (kind) {
            case "CONFIRMED", "APPROVED" -> orderLabel + " is confirmed for " + pickup + " pickup. Pay at the counter.";
            case "READY" -> orderLabel + " is packed and ready for your " + pickup + " pickup.";
            case "AWAITING_APPROVAL" -> orderLabel + " is waiting for shop approval. Please wait for confirmation.";
            case "APPROVAL_EXPIRED" -> orderLabel + " expired before the shop could approve it.";
            case "REJECTED" -> orderLabel + " could not be approved by the shop.";
            case "SHORT" -> orderLabel + " is affected by a stock shortage. The shop will follow up.";
            case "CANCELLED" -> orderLabel + " was cancelled. Please contact the shop with questions.";
            default -> throw new IllegalArgumentException("Unsupported outbox kind");
        };
        db.update("""
                INSERT INTO outbox_messages (id,order_id,run_id,kind,status,recipient,body)
                VALUES (gen_random_uuid(),?,?,?,'QUEUED',?,?) ON CONFLICT (run_id,order_id,kind) DO NOTHING
                """, order.id(), order.runId(), kind, order.phone(), body);
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to write booking decision", ex);
        }
    }

    <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to read booking decision", ex);
        }
    }

    static String shopTime(Instant instant) {
        return instant.atZone(SHOP_ZONE).toLocalTime().toString();
    }

    static OffsetDateTime sqlTime(Instant time) {
        return time == null ? null : time.atOffset(java.time.ZoneOffset.UTC);
    }

    static Instant at(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record Shop(UUID runId, Instant now) {}
    record QuoteRow(Quote quote, UUID runId, String name, String phone) {}
}
