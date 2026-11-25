package com.rushpromise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.booking.BookingModels.Policy;
import com.rushpromise.booking.BookingModels.AcceptRequest;
import com.rushpromise.booking.BookingModels.NativeIdentityKind;
import com.rushpromise.booking.BookingModels.QuoteRequest;
import com.rushpromise.booking.BookingService;
import com.rushpromise.booking.SlotPolicyProvider;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${RUSH_TEST_JDBC_URL}",
        "spring.datasource.username=${RUSH_TEST_JDBC_USER:shop_app}",
        "spring.datasource.password=${RUSH_TEST_JDBC_PASSWORD}",
        "RUSH_SIGNUP_ENABLED=true",
        "RUSH_MERCHANT_DEMO_CLOCK=true",
        "RUSH_STAFF_TOKEN=platform-legacy-fixture",
        "RUSH_OPERATOR_TOKEN=platform-operator-fixture",
        "RUSH_SARVAM_TOOL_TOKEN=isolated-native-pickup-fixture"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "RUSH_TEST_JDBC_URL", matches = "jdbc:postgresql:.*/rush_test_[A-Za-z0-9_]+(?:\\?.*)?")
class PlatformIsolationTest {
    private static final String PASSWORD = "Test-only-strong-merchant-passphrase";
    private static final String SHARED_SKU = "shared-product";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired DataSource routed;
    @Autowired @Qualifier("platformConnectionPool") HikariDataSource pool;
    @Autowired PlatformContextFilter filter;
    @Autowired TenantDirectory tenants;
    @Autowired SarvamShopRouting nativeRouting;
    @Autowired DemoPreviews previews;
    @Autowired BookingService bookings;
    @MockitoBean SlotPolicyProvider slotPolicies;

    @Test
    void routedOpaqueSubjectUsesShopPolicyWithoutSendingOrForgingContact() throws Exception {
        String subject = "c".repeat(64);
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        var mithai = signup("native-mithai-" + nonce);
        var bakery = signup("native-bakery-" + nonce);
        shop(mithai, "deepak-mithai-bhandar", "Deepak Mithai Bhandar");
        shop(bakery, "virajs-bakery", "Viraj's Bakery");
        var mithaiShop = tenants.bySlug("deepak-mithai-bhandar");
        var bakeryShop = tenants.bySlug("virajs-bakery");
        tenants.markFixture(mithaiShop);
        tenants.markFixture(bakeryShop);
        setClock(mithaiShop.schema());
        setClock(bakeryShop.schema());
        response(owner(body(post("/api/owner/products"), Map.of("sku", "barfi", "itemName", "Barfi",
                "unit", "GRAMS", "pricePerUnitPaise", 90000, "stockQuantity", 20000)), mithai), 200);
        response(owner(body(post("/api/owner/products"), Map.of("sku", "vanilla-cupcake", "itemName", "Vanilla cupcake",
                "unit", "PIECE", "pricePerUnitPaise", 7500, "stockQuantity", 12)), bakery), 200);
        TenantContext.withSchema(mithaiShop.schema(), () -> db.update("""
                INSERT INTO pickup_slots(pickup_at,capacity,used) VALUES
                    ('2031-02-09T14:00:00Z',5,4),('2031-02-09T14:30:00Z',10,4),('2031-02-09T15:00:00Z',5,4)
                ON CONFLICT (pickup_at) DO UPDATE SET capacity=EXCLUDED.capacity,used=EXCLUDED.used
                """));
        String digest = "d".repeat(64);
        when(slotPolicies.capture()).thenReturn((grams, used, capacity) -> {
            boolean busy = 100L * used / capacity >= 80;
            String profile = busy ? "BUSY_SLOT" : "RUSH";
            int threshold = busy ? 2000 : 3000;
            return new Policy(digest, "LIVE", Instant.EPOCH, Instant.EPOCH, true, 5000, threshold, 15, 30, 120, 20)
                    .withSlot(SlotPolicyProvider.audit(digest, profile, busy ? "SLOT_NEAR_CAPACITY" : "RUSH_HOUR", grams, threshold, used, capacity));
        });

        nativeCall("select-shop", "mock-call-mithai", mithaiShop.slug(), null, 1, "", null);
        var preview = nativeCall("turn", "mock-call-mithai", null, null, 1, "2.5 kg barfi at 19:30", null);
        assertThat(preview.at("/replyText").asText()).contains("2250");
        assertThat(preview.at("/quote/id").isMissingNode()).isTrue();
        assertThat(tenantCount(mithaiShop.schema(), "quotes")).isZero();
        var ambiguous = nativeCall("turn", "mock-call-mithai", null, subject, 1, "चार ग्राम बर्फी 19:30", null);
        assertThat(ambiguous.at("/state").asText()).isEqualTo("CLARIFY_QUANTITY");
        assertThat(ambiguous.at("/quote").isNull()).isTrue();
        assertThat(ambiguous.at("/replyText").asText()).containsAnyOf("ग्राम", "grams");
        var busy = nativeCall("turn", "mock-call-mithai", null, subject, 2, "ढाई किलो", null);
        String busyId = busy.at("/quote/id").asText();
        assertThat(busy.at("/quote/grams").asInt()).isEqualTo(2500);
        assertThat(busy.at("/quote/decision/profile").asText()).isEqualTo("BUSY_SLOT");
        assertThat(busy.at("/quote/requiresApproval").asBoolean()).isTrue();
        var next = nativeCall("turn", "mock-call-mithai", null, subject, 3, "20:00 instead", null);
        String nextId = next.at("/quote/id").asText();
        assertThat(next.at("/quote/decision/profile").asText()).isEqualTo("RUSH");
        assertThat(next.at("/quote/decision/digest").asText()).isEqualTo(busy.at("/quote/decision/digest").asText()).isEqualTo(digest);
        assertThat(next.at("/quote/requiresApproval").asBoolean()).isFalse();
        var late = nativeCall("turn", "mock-call-mithai", null, subject, 4, "haan", busyId);
        assertThat(late.at("/quote/id").asText()).isEqualTo(nextId);
        assertThat(tenantCount(mithaiShop.schema(), "orders")).isZero();
        mvc.perform(owner(body(post("/api/quotes/" + nextId + "/accept"), Map.of("sessionId", "sarvam:mock-call-mithai", "turnId", "forged")), mithai))
                .andExpect(status().isNotFound());
        var confirmed = nativeCall("turn", "mock-call-mithai", null, subject, 5, "haan", nextId);
        assertThat(confirmed.at("/order/status").asText()).isEqualTo("CONFIRMED");
        String code = confirmed.at("/order/pickupCode").asText();
        assertThat(code).matches("[1-9][0-9]{7}");
        assertThat(confirmed.at("/replyText").asText()).contains(confirmed.at("/order/id").asText(), "pickup code");
        assertThat(nativeCall("turn", "mock-call-mithai", null, subject, 5, "haan", nextId).at("/order/pickupCode").asText()).isEqualTo(code);
        assertThat(response(owner(get("/api/orders"), mithai), 200).at("/0/pickupCode").asText()).isEqualTo(code);
        assertThat(response(owner(get("/api/orders"), bakery), 200).size()).isZero();
        assertThat(tenantCount(mithaiShop.schema(), "outbox_messages")).isZero();

        var held = bookNative("mock-call-hold", mithaiShop.slug(), subject, "2.5 kg barfi at 19:30");
        assertThat(held.at("/order/status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(held.at("/order/pickupCode").asText()).matches("[1-9][0-9]{7}");
        assertThat(held.at("/replyText").asText()).contains(held.at("/order/id").asText(), "needs shop approval");
        assertThat(bookNative("mock-call-second-hold", mithaiShop.slug(), subject, "2.5 kg barfi at 20:30").at("/order").isNull()).isTrue();
        assertThat(tenantCount(mithaiShop.schema(), "orders")).isEqualTo(2);

        var bakeryOrder = bookNative("mock-call-cupcakes", bakeryShop.slug(), subject, "two vanilla cupcakes at 20:00");
        assertThat(bakeryOrder.at("/order/status").asText()).isEqualTo("CONFIRMED");
        assertThat(bakeryOrder.at("/order/unit").asText()).isEqualTo("PIECE");
        assertThat(bakeryOrder.at("/order/pricePaise").asInt()).isEqualTo(15000);
        assertThat(bakeryOrder.at("/order/decision").isNull()).isTrue();
        var mithaiSubject = pickupDigest(mithaiShop.schema());
        var bakerySubject = pickupDigest(bakeryShop.schema());
        assertThat(mithaiSubject).matches("[a-f0-9]{64}").isNotEqualTo(subject).isNotEqualTo(bakerySubject);
        assertThat(tenantCount(mithaiShop.schema(), "outbox_messages")).isZero();
        assertThat(tenantCount(bakeryShop.schema(), "outbox_messages")).isZero();

        String call = "sarvam:pickup-proof-" + nonce;
        assertThat(nativeRouting.choose(call, mithaiShop.slug()).schema()).isEqualTo(mithaiShop.schema());
        TenantContext.withSchema(mithaiShop.schema(), () -> {
            var identity = bookings.nativeInteractionSubject(call);
            assertThat(identity).matches("[a-f0-9]{64}");
            var large = bookings.quoteNativePickup(new QuoteRequest(call, "Caller", null, "barfi", 4000, "20:30"), identity, NativeIdentityKind.CALL);
            assertThat(large.code()).isEqualTo("CALL_APPROVAL_UNAVAILABLE");
            assertThat(large.quote()).isNull();
            var fake = bookings.quoteNativePickup(new QuoteRequest(call, "Caller", null, "barfi", 2500, "20:30"), subject, NativeIdentityKind.CALL);
            assertThat(fake.status()).isEqualTo(404);
            var alternate = bookings.quoteNativePickup(new QuoteRequest(call, "Caller", null, "barfi", 2500, "20:30"), identity, NativeIdentityKind.CALL);
            assertThat(alternate.reason()).isEqualTo("CALL_APPROVAL_UNAVAILABLE");
            assertThat(alternate.quote().pickupTime()).isEqualTo("21:00");
            assertThat(alternate.quote().requiresApproval()).isFalse();
            assertThat(tenantCount(mithaiShop.schema(), "orders")).isEqualTo(2);
            var consent = new AcceptRequest(call, "accepted-fallback");
            String quoteId = alternate.quote().id().toString();
            var first = CompletableFuture.supplyAsync(() -> TenantContext.withSchema(mithaiShop.schema(), () -> bookings.acceptNativePickup(quoteId, consent)));
            var second = CompletableFuture.supplyAsync(() -> TenantContext.withSchema(mithaiShop.schema(), () -> bookings.acceptNativePickup(quoteId, consent)));
            var accepted = first.join();
            var replayed = second.join();
            assertThat(java.util.List.of(accepted.status(), replayed.status())).containsExactlyInAnyOrder(201, 200);
            assertThat(accepted.order().pickupCode()).matches("[1-9][0-9]{7}");
            assertThat(replayed.order()).isEqualTo(accepted.order());
            assertThat(bookings.quoteNativePickup(new QuoteRequest(call, "Caller", null, "barfi", 500, "21:30"), identity, NativeIdentityKind.CALL).code())
                    .isEqualTo("CALL_ALREADY_BOOKED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM orders WHERE native_identity_kind='CALL'", Integer.class)).isEqualTo(1);
            return null;
        });
        assertThat(tenantCount(mithaiShop.schema(), "outbox_messages")).isZero();
    }

    private void setClock(String schema) {
        setClock(schema, "2031-02-09T12:15:00Z");
    }

    private void setClock(String schema, String iso) {
        TenantContext.withSchema(schema, () -> db.update("UPDATE shop_state SET demo_now=?::timestamptz,demo_started_at=NULL", iso));
    }

    @Test
    void merchantScheduleBindsTheSelectedTimeAndExcludesClosedFullOrHeldSlots() throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String aSlug = "hours-proof-a-" + nonce;
        String bSlug = "hours-proof-b-" + nonce;
        var a = signup(aSlug);
        var b = signup(bSlug);
        shop(a, aSlug, "Hours A");
        shop(b, bSlug, "Hours B");
        String aSchema = tenants.bySlug(aSlug).schema();
        String bSchema = tenants.bySlug(bSlug).schema();
        setClock(aSchema, "2031-02-09T03:00:00Z");
        setClock(bSchema, "2031-02-09T03:00:00Z");
        Map<String, Object> product = Map.of("sku", "peda", "itemName", "Peda", "unit", "GRAMS", "pricePerUnitPaise", 100000, "stockQuantity", 20000);
        response(owner(body(post("/api/owner/products"), product), a), 200);
        response(owner(body(post("/api/owner/products"), product), b), 200);
        setHours(a, "09:00", "21:00");
        assertThat(response(owner(get("/api/owner/pickup-hours"), a), 200).at("/timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(response(owner(get("/api/owner/pickup-hours"), b), 200).at("/allDay").asBoolean()).isTrue();
        mvc.perform(owner(body(post("/api/owner/pickup-hours"), Map.of("allDay", false, "opensAt", "09:15", "closesAt", "21:00")), a))
                .andExpect(status().isBadRequest());
        var morning = response(get("/api/storefront/" + aSlug).param("sku", "peda").param("quantity", "500").param("from", "08:30"), 200);
        assertThat(morning.at("/availablePickups/0/pickupTime").asText()).isEqualTo("09:00");
        assertThat(morning.at("/availablePickups/1/pickupTime").asText()).isEqualTo("09:30");
        assertThat(tenantCount(aSchema, "pickup_slots")).isZero();
        assertThat(tenantCount(aSchema, "quotes")).isZero();
        var before = offer(a, "early", "08:30", 500);
        assertThat(before.at("/reason").asText()).isEqualTo("OUTSIDE_HOURS");
        assertThat(before.at("/quote/pickupTime").asText()).isEqualTo("09:00");
        assertThat(accept(a, "early", before, 201).at("/order/pickupTime").asText()).isEqualTo("09:00");
        var ending = offer(a, "evening", "21:00", 500);
        assertThat(ending.at("/quote/pickupTime").asText()).isEqualTo("21:00");
        var committed = accept(a, "evening", ending, 201);
        assertThat(committed.at("/order/pickupAt").asText()).isEqualTo("2031-02-09T15:30:00Z");
        // The only slot at 11:00 is consumed by a real temporary approval hold.
        TenantContext.withSchema(aSchema, () -> db.update("INSERT INTO pickup_slots(pickup_at,capacity) VALUES ('2031-02-09T05:30:00Z',1)"));
        var pending = accept(a, "held", offer(a, "held", "11:00", 4500), 201);
        assertThat(pending.at("/order/status").asText()).isEqualTo("AWAITING_APPROVAL");
        var any = response(get("/api/storefront/" + aSlug).param("sku", "peda").param("quantity", "500").param("from", "11:00"), 200);
        assertThat(any.at("/availablePickups/0/pickupTime").asText()).isEqualTo("11:30");
        var later = offer(a, "after-hours", "21:30", 500);
        assertThat(later.at("/reason").asText()).isEqualTo("OUTSIDE_HOURS");
        assertThat(later.at("/quote/pickupAt").asText()).isEqualTo("2031-02-10T03:30:00Z");
        var interval = offer(a, "half-hour", "18:15", 500);
        assertThat(interval.at("/reason").asText()).isEqualTo("PICKUP_INTERVAL");
        assertThat(interval.at("/quote/pickupTime").asText()).isEqualTo("18:30");

        var waiting = offer(a, "awaiting-hours-edit", "20:00", 500);
        setHours(a, "09:00", "17:00");
        var conflict = accept(a, "awaiting-hours-edit", waiting, 409);
        assertThat(conflict.at("/reason").asText()).isEqualTo("OUTSIDE_HOURS");
        assertThat(conflict.at("/quote/pickupAt").asText()).isEqualTo("2031-02-10T03:30:00Z");
        assertThat(tenantCount(aSchema, "orders")).isEqualTo(3);
        assertThat(response(owner(get("/api/orders"), a), 200).findValuesAsText("pickupAt")).contains("2031-02-09T15:30:00Z");
        var tomorrow = response(owner(body(post("/api/quotes"), Map.of("sessionId", "confirmed-date", "customerName", "Fixture buyer", "sku", "peda",
                "phone", "+12025550101", "quantity", 500, "pickupTime", "09:30", "requestedPickupAt", "2031-02-10T04:00:00Z")), a), 200);
        assertThat(accept(a, "confirmed-date", tomorrow, 201).at("/order/pickupAt").asText()).isEqualTo("2031-02-10T04:00:00Z");
        mvc.perform(owner(body(post("/api/owner/shop/settings"), Map.of("displayName", "", "pickupHours",
                Map.of("allDay", false, "opensAt", "10:00", "closesAt", "20:00"))), a)).andExpect(status().isBadRequest());
        assertThat(response(owner(get("/api/owner/pickup-hours"), a), 200).at("/closesAt").asText()).isEqualTo("17:00");
        assertThat(offer(b, "separate", "21:30", 500).at("/quote/pickupTime").asText()).isEqualTo("21:30");
        TenantContext.withSchema(bSchema, () -> db.update("UPDATE batches SET phone_grams_free=0 WHERE sku='peda'"));
        assertThat(response(get("/api/storefront/" + bSlug).param("sku", "peda").param("quantity", "500"), 200)
                .at("/availablePickups").size()).isZero();
    }

    private void setHours(Credentials owner, String open, String close) throws Exception {
        response(owner(body(post("/api/owner/pickup-hours"), Map.of("allDay", false, "opensAt", open, "closesAt", close)), owner), 200);
    }

    private JsonNode offer(Credentials owner, String session, String time, int quantity) throws Exception {
        return response(owner(body(post("/api/quotes"), Map.of("sessionId", session, "customerName", "Test buyer", "sku", "peda",
                "phone", "+12025550101", "quantity", quantity, "pickupTime", time)), owner), 200);
    }

    private JsonNode accept(Credentials owner, String session, JsonNode offer, int expected) throws Exception {
        String id = offer.at("/quote/id").asText();
        return response(owner(body(post("/api/quotes/" + id + "/accept"), Map.of("sessionId", session, "turnId", "bound-yes")), owner), expected);
    }

    private int tenantCount(String schema, String table) {
        return TenantContext.withSchema(schema, () -> db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    private String pickupDigest(String schema) {
        return TenantContext.withSchema(schema, () -> db.queryForObject("SELECT native_pickup_subject FROM orders WHERE phone IS NULL ORDER BY id LIMIT 1", String.class));
    }

    private JsonNode bookNative(String interaction, String shop, String subject, String phrase) throws Exception {
        nativeCall("select-shop", interaction, shop, null, 1, "", null);
        var offer = nativeCall("turn", interaction, null, subject, 1, phrase, null);
        assertThat(offer.at("/quote/id").asText()).isNotEmpty();
        return nativeCall("turn", interaction, null, subject, 2, "haan", offer.at("/quote/id").asText());
    }

    private JsonNode nativeCall(String event, String interaction, String shop, String subject, int sequence, String phrase, String offer) throws Exception {
        Map<String, Object> turn = new HashMap<>(Map.of("event", event, "routed", true, "interactionId", interaction,
                "sequence", sequence, "utterance", phrase, "language", "en-IN"));
        if (shop != null) turn.put("shopSlug", shop);
        if (subject != null) turn.put("phone", subject);
        if (offer != null) turn.put("confirmationQuoteId", offer);
        return response(body(post("/api/voice/sarvam/turn").header("Authorization", "Bearer isolated-native-pickup-fixture"), turn), 200);
    }

    @Test
    void publicOnboardingListsOnlyShopDetailsAndPinsAnExactSpokenShop() throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String east = "basil-east-" + nonce;
        String west = "basil-west-" + nonce;
        String name = "Harbor Basil " + nonce;
        var eastOwner = signup(east);
        var westOwner = signup(west);
        mvc.perform(get("/owner/desk")).andExpect(status().isFound());
        mvc.perform(owner(get("/owner/desk"), eastOwner)).andExpect(status().isFound());
        var hours = Map.of("allDay", false, "opensAt", "09:00", "closesAt", "21:00");
        var invalid = Map.of("allDay", false, "opensAt", "09:15", "closesAt", "21:00");
        var setup = new HashMap<String, Object>(Map.of("slug", east, "displayName", name, "area", "Indiranagar", "pickupHours", invalid));
        mvc.perform(owner(body(post("/api/owner/shop"), setup), eastOwner)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/storefront/" + east)).andExpect(status().isNotFound());
        setup.put("pickupHours", hours);
        response(owner(body(post("/api/owner/shop"), setup), eastOwner), 201);
        mvc.perform(owner(body(post("/api/owner/shop"), setup), westOwner)).andExpect(status().isConflict());
        setup.put("slug", west);
        setup.put("area", "Jayanagar");
        response(owner(body(post("/api/owner/shop"), setup), westOwner), 201);
        mvc.perform(owner(get("/owner/desk"), eastOwner)).andExpect(status().isOk());
        mvc.perform(get("/api/staff/snapshot").header("X-Rush-Public-Edge", "1")
                .header("X-Staff-Token", "platform-legacy-fixture")).andExpect(status().isUnauthorized());

        setClock(tenants.bySlug(east).schema());
        setClock(tenants.bySlug(west).schema());
        var product = Map.of("sku", "lemon-tart", "itemName", "Lemon Tart", "unit", "PIECE", "pricePerUnitPaise", 9500, "stockQuantity", 4);
        mvc.perform(body(post("/api/owner/products"), product)).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/owner/products"), product).cookie(eastOwner.cookie()).header("X-CSRF-Token", westOwner.csrf()))
                .andExpect(status().isForbidden());
        response(owner(body(post("/api/owner/products"), product), eastOwner), 200);
        var directory = response(get("/api/public/shops").param("query", nonce), 200);
        assertThat(directory.at("/shops").findValuesAsText("slug")).containsExactly(east, west);
        assertThat(directory.toString()).doesNotContain("schema", "owner", "password", "token", "phone");
        assertThat(response(get("/api/storefront/" + east), 200).at("/pickupHours/closesAt").asText()).isEqualTo("21:00");
        assertThat(response(get("/api/storefront/" + west), 200).at("/menu/items").size()).isZero();

        var select = new HashMap<String, Object>(Map.of("event", "select-shop", "interactionId", "public-shop-" + nonce,
                "routed", true, "shopQuery", name, "language", "en-IN"));
        var selectionEndpoint = "/api/voice/sarvam/turn";
        var bearer = "Bearer isolated-native-pickup-fixture";
        mvc.perform(body(post(selectionEndpoint), select)).andExpect(status().isUnauthorized());
        assertThat(response(body(post(selectionEndpoint).header("Authorization", bearer), select), 200).at("/state").asText())
                .isEqualTo("SHOP_AMBIGUOUS");
        assertThat(nativeRouting.route("sarvam:public-shop-" + nonce)).isNull();
        select.put("shopQuery", name + ", Indiranagar");
        assertThat(response(body(post(selectionEndpoint).header("Authorization", bearer), select), 200).at("/state").asText())
                .isEqualTo("SHOP_SELECTED");
        assertThat(nativeRouting.route("sarvam:public-shop-" + nonce).slug()).isEqualTo(east);
        select.put("shopQuery", name + " Jayanagar");
        assertThat(response(body(post(selectionEndpoint).header("Authorization", bearer), select), 200).at("/state").asText())
                .isEqualTo("SHOP_ALREADY_SELECTED");
        var turn = new HashMap<String, Object>(Map.of("event", "turn", "interactionId", "public-shop-" + nonce,
                "routed", true, "pickupMode", "interaction", "sequence", 1, "utterance", "two lemon tarts at 19:00", "language", "en-IN"));
        var offered = response(body(post(selectionEndpoint).header("Authorization", bearer), turn), 200);
        assertThat(offered.at("/quote/pricePaise").asInt()).isEqualTo(19000);
        assertThat(response(owner(get("/api/orders"), eastOwner), 200).size()).isZero();
        turn.put("sequence", 2);
        turn.put("utterance", "yes");
        turn.put("confirmationQuoteId", offered.at("/confirmationToken").asText());
        var booked = response(body(post(selectionEndpoint).header("Authorization", bearer), turn), 200);
        assertThat(booked.at("/order/pickupCode").asText()).matches("[1-9][0-9]{7}");
        assertThat(response(owner(get("/api/orders"), eastOwner), 200).size()).isEqualTo(1);
        assertThat(response(owner(get("/api/orders"), westOwner), 200).size()).isZero();
        String orderId = booked.at("/order/id").asText();
        String packing = "/api/orders/" + orderId + "/pack";
        assertThat(response(owner(get("/api/staff/snapshot").header("X-Rush-Public-Edge", "1"), eastOwner), 200)
                .at("/orders/0/pickupCode").asText()).isEqualTo(booked.at("/order/pickupCode").asText());
        assertThat(response(owner(get("/api/staff/snapshot").header("X-Rush-Public-Edge", "1"), westOwner), 200)
                .at("/orders").size()).isZero();
        mvc.perform(owner(post(packing).header("X-Rush-Public-Edge", "1"), westOwner)).andExpect(status().isNotFound());
        assertThat(response(owner(post(packing).header("X-Rush-Public-Edge", "1"), eastOwner), 200)
                .at("/order/status").asText()).isEqualTo("PACKED");
        assertThat(tenantCount(tenants.bySlug(east).schema(), "outbox_messages")).isZero();
        mvc.perform(owner(post("/api/auth/logout"), eastOwner)).andExpect(status().isNoContent());
        mvc.perform(owner(get("/api/owner/products"), eastOwner)).andExpect(status().isUnauthorized());
    }

    @Test
    void missingNativeReceiptCanOnlyBeReplacedByTheLatestSpokenConfirmationCode() throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String slug = "voice-consent-" + nonce;
        var owner = signup(slug);
        shop(owner, slug, "Voice consent fixture");
        String schema = tenants.bySlug(slug).schema();
        setClock(schema, "2031-02-09T09:32:00Z");
        response(owner(body(post("/api/owner/products"), Map.of("sku", "barfi", "itemName", "Barfi", "unit", "GRAMS",
                "pricePerUnitPaise", 90000, "stockQuantity", 5000)), owner), 200);
        String observed = "Ab12:CD3/" + "A1b2C3d4E5f6G7h8I9" + nonce;
        nativeCall("select-shop", observed, slug, null, 1, "", null);
        assertThat(contactlessTurn(observed, 1, "आधा किलो बर्फी मिल जाएगी।", null).at("/order").isNull()).isTrue();
        assertThat(contactlessTurn(observed, 2, "साढ़े चार कर देते हैं।", null).at("/quote/pickupTime").asText()).isEqualTo("16:30");
        assertThat(contactlessTurn(observed, 3, "नहीं, एक काम करो ढाई सौ ग्राम बना दो।", null)
                .at("/quote/pricePaise").asInt()).isEqualTo(22500);
        var shifted = contactlessTurn(observed, 4, "नहीं, एक काम करो छह बजे का कर दो।", null);
        assertThat(shifted.at("/quote/pickupTime").asText()).isEqualTo("18:00");
        var exactLiveAssent = contactlessTurn(observed, 5, "हाँ सर।", null);
        assertThat(exactLiveAssent.at("/order").isNull()).isTrue();
        assertThat(exactLiveAssent.at("/quote/id").asText()).isEqualTo(shifted.at("/quote/id").asText());
        assertThat(tenantCount(schema, "orders")).isZero();
        var hindiDigits = java.util.List.of("शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ");
        String spokenWords = confirmationCode(exactLiveAssent).chars().filter(Character::isDigit)
                .mapToObj(value -> hindiDigits.get(value - '0'))
                .collect(java.util.stream.Collectors.joining(" "));
        assertThat(contactlessTurn(observed, 6, "", null).at("/order").isNull()).isTrue();
        assertThat(tenantCount(schema, "orders")).isZero();
        var replayConfirmed = contactlessTurn(observed, 7, "हाँ " + spokenWords, null);
        assertThat(replayConfirmed.at("/order/status").asText()).isEqualTo("CONFIRMED");
        assertThat(replayConfirmed.at("/order/pickupTime").asText()).isEqualTo("18:00");
        assertThat(replayConfirmed.at("/order/pricePaise").asInt()).isEqualTo(22500);

        String call = "spoken-consent-" + nonce;
        nativeCall("select-shop", call, slug, null, 1, "", null);
        assertThat(contactlessTurn(call, 1, "हाँ १२३४", null).at("/order").isNull()).isTrue();
        var old = contactlessTurn(call, 2, "आधा किलो बर्फी, शाम 6 बजे", null);
        var repeat = contactlessTurn(call, 3, "हाँ सर", "latest-quote-id");
        assertThat(repeat.at("/quote/id").asText()).isEqualTo(old.at("/quote/id").asText());
        String oldCode = confirmationCode(repeat);
        var corrected = contactlessTurn(call, 4, "नहीं, ढाई सौ ग्राम", null);
        String correctedId = corrected.at("/quote/id").asText();
        assertThat(corrected.at("/quote/pricePaise").asInt()).isEqualTo(22500);
        assertThat(correctedId).isNotEqualTo(old.at("/quote/id").asText());
        var staleCode = contactlessTurn(call, 5, "हाँ " + oldCode, null);
        String newCode = confirmationCode(staleCode);
        assertThat(newCode).isNotEqualTo(oldCode);
        assertThat(staleCode.at("/quote/id").asText()).isEqualTo(correctedId);
        assertThat(confirmationCode(contactlessTurn(call, 6, "हाँ", null))).isEqualTo(newCode);
        assertThat(contactlessTurn(call, 7, newCode, null).at("/order").isNull()).isTrue();
        assertThat(tenantCount(schema, "orders")).isEqualTo(1);
        String devanagariDigits = newCode.chars().map(value -> Character.isDigit(value) ? value - '0' + '०' : value)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var fresh = contactlessTurn(call, 8, "हाँ " + devanagariDigits, null);
        assertThat(fresh.at("/order/status").asText()).isEqualTo("CONFIRMED");
        var replay = contactlessTurn(call, 8, "हाँ " + devanagariDigits, null);
        assertThat(replay.at("/order")).isEqualTo(fresh.at("/order"));
        assertThat(tenantCount(schema, "orders")).isEqualTo(2);
        assertThat(tenantCount(schema, "outbox_messages")).isZero();
        TenantContext.withSchema(schema, () -> {
            assertThat(db.queryForObject("SELECT COUNT(*) FROM voice_turns WHERE accepted_quote_id=? AND turn_id='turn-8'",
                    Integer.class, UUID.fromString(correctedId))).isEqualTo(1);
            return null;
        });
    }

    private String confirmationCode(JsonNode reply) {
        var parsed = java.util.regex.Pattern.compile("कहें: हाँ ([0-9](?: [0-9]){3})").matcher(reply.at("/replyText").asText());
        assertThat(parsed.find()).isTrue();
        return parsed.group(1);
    }

    private JsonNode contactlessTurn(String call, int sequence, String utterance, String token) throws Exception {
        var input = new HashMap<String, Object>(Map.of("event", "turn", "routed", true, "interactionId", call, "sequence", sequence,
                "pickupMode", "interaction", "language", "hi-IN", "utterance", utterance));
        if (token != null) input.put("confirmationQuoteId", token);
        return response(body(post("/api/voice/sarvam/turn").header("Authorization", "Bearer isolated-native-pickup-fixture"), input), 200);
    }

    @Test
    void typedCheckoutIsTenantBoundAndOnlyTheExplicitCurrentOfferReserves() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String one = "typed-one-" + suffix;
        String two = "typed-two-" + suffix;
        var firstOwner = signup(one);
        var secondOwner = signup(two);
        shop(firstOwner, one, "Text pickup one");
        shop(secondOwner, two, "Text pickup two");
        String first = tenants.bySlug(one).schema();
        String second = tenants.bySlug(two).schema();
        setClock(first);
        setClock(second);
        response(owner(body(post("/api/owner/products"), Map.of("sku", "barfi", "itemName", "Barfi", "unit", "GRAMS",
                "pricePerUnitPaise", 90000, "stockQuantity", 500)), firstOwner), 200);
        String endpoint = "/api/checkout/text";
        var turn = new HashMap<String, Object>(Map.of("sessionId", UUID.randomUUID(), "turnId", UUID.randomUUID(),
                "sequence", 1, "phone", "+12025550100", "customerName", "Typed fixture", "utterance", "250 grams of barfi at 18:30"));
        mvc.perform(body(post(endpoint), turn)).andExpect(status().isNotFound());
        mvc.perform(body(post(endpoint).header("X-Rush-Shop", one).header("Origin", "https://unrelated.example"), turn))
                .andExpect(status().isForbidden());
        var initial = response(body(post(endpoint).header("X-Rush-Shop", one), turn), 200);
        String original = initial.at("/quote/id").asText();
        assertThat(initial.at("/quote/pricePaise").asInt()).isEqualTo(22500);
        turn.put("confirmationQuoteId", original);
        nextTextTurn(turn, 2, "yes");
        assertThat(response(body(post(endpoint).header("X-Rush-Shop", one), turn), 200).at("/order").isNull()).isTrue();
        nextTextTurn(turn, 3, "no, 500 grams instead");
        var changed = response(body(post(endpoint).header("X-Rush-Shop", one), turn), 200);
        String current = changed.at("/quote/id").asText();
        assertThat(current).isNotEqualTo(original);
        assertThat(changed.at("/quote/pricePaise").asInt()).isEqualTo(45000);
        nextTextTurn(turn, 4, "");
        turn.put("confirm", true);
        var stale = response(body(post(endpoint).header("X-Rush-Shop", one), turn), 200);
        assertThat(stale.at("/quote/id").asText()).isEqualTo(current);
        assertThat(tenantCount(first, "orders")).isZero();
        nextTextTurn(turn, 5, "");
        turn.put("confirmationQuoteId", current);
        assertThat(response(body(post(endpoint).header("X-Rush-Shop", two), turn), 200).at("/order").isNull()).isTrue();
        var accepted = response(body(post(endpoint).header("X-Rush-Shop", one), turn), 200);
        assertThat(accepted.at("/order/status").asText()).isEqualTo("CONFIRMED");
        assertThat(accepted.at("/order/pickupTime").asText()).isEqualTo("18:30");
        turn.put("receiptOnly", true);
        assertThat(response(body(post(endpoint).header("X-Rush-Shop", one), turn), 200).at("/order")).isEqualTo(accepted.at("/order"));
        String pack = "/api/orders/" + accepted.at("/order/id").asText() + "/pack";
        assertThat(response(owner(post(pack), firstOwner), 200).at("/order/status").asText()).isEqualTo("PACKED");
        assertThat(tenantCount(first, "orders")).isEqualTo(1);
        assertThat(tenantCount(second, "orders")).isZero();
        assertThat(tenantCount(first, "outbox_messages")).isZero();
        assertThat(tenantCount(first, "notification_live_grant")).isZero();
        assertThat(tenantCount(second, "notification_live_grant")).isZero();
    }

    private static void nextTextTurn(Map<String, Object> previous, int sequence, String text) {
        previous.put("turnId", UUID.randomUUID());
        previous.put("sequence", sequence);
        previous.put("utterance", text);
    }

    @Test
    void ownerPhoneCodeIsOneUseAndInventoryApprovalIsBoundToTheExactHeldShopChange() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0,8);
        String slug = "owner-phone-shop-"+suffix;
        String otherSlug = "owner-phone-other-"+suffix;
        String shopName = "Morning Pantry "+suffix;
        var merchant = signup(slug);
        var second = signup(otherSlug);
        response(owner(body(post("/api/owner/shop"),Map.of("slug",slug,"displayName",shopName,"area","Indiranagar")),merchant),201);
        shop(second,otherSlug,"Other pantry "+suffix);
        String schema = tenants.bySlug(slug).schema();
        setClock(schema);
        var barfi = new HashMap<String, Object>(Map.of("sku","barfi","itemName","Barfi","unit","GRAMS",
                "pricePerUnitPaise",90000,"stockQuantity",1000));
        response(owner(body(post("/api/owner/products"),barfi),merchant),200);
        response(owner(body(post("/api/owner/products"),Map.of("sku","toast","itemName","Toast","unit","PIECE",
                "pricePerUnitPaise",6000,"stockQuantity",8)),second),200);
        var quote = response(owner(body(post("/api/quotes"),Map.of("sessionId","held-phone-fixture","customerName","Fixture customer",
                "phone","+12025550100","sku","barfi","quantity",250,"pickupTime","18:30")),merchant),200);
        assertThat(accept(merchant,"held-phone-fixture",quote,201).at("/order/status").asText()).isEqualTo("CONFIRMED");
        String codePath="/api/owner/phone/access";
        mvc.perform(post(codePath)).andExpect(status().isUnauthorized());
        mvc.perform(post(codePath).cookie(merchant.cookie())).andExpect(status().isForbidden());
        var issued=response(owner(post(codePath),merchant),200);
        String otp=issued.at("/accessCode").asText();
        assertThat(otp).matches("[1-9][0-9]{7}");
        String salted=db.queryForObject("SELECT code_hash FROM public.owner_phone_access WHERE tenant_id=?",String.class,tenants.bySlug(slug).id());
        assertThat(salted).startsWith("pbkdf2-sha256$600000$").doesNotContain(otp);
        String phoneCall=ownerInteraction(1);
        String tool="/api/voice/sarvam/owner";
        mvc.perform(body(post(tool),Map.of("event","list","interactionId",phoneCall))).andExpect(status().isUnauthorized());
        for (String malformed : java.util.List.of("aaaaaaaa/" + ".".repeat(26), "aaaaaaaa/b/" + "z".repeat(24), "aaaaaaaa/" + "z".repeat(25))) {
            mvc.perform(body(post(tool).header("Authorization","Bearer isolated-native-pickup-fixture"),
                    Map.of("event","list","interactionId",malformed))).andExpect(status().isBadRequest());
        }
        assertThat(ownerPhone(phoneCall.replace(':','x'),"list",Map.of()).at("/state").asText()).isEqualTo("AUTH_REQUIRED");
        assertThat(ownerPhone(phoneCall,"list",Map.of()).at("/state").asText()).isEqualTo("AUTH_REQUIRED");
        var authenticated=ownerPhone(phoneCall,"authenticate",Map.of("shopQuery",shopName+" Indiranagar","accessCode",otp,"language","hi-IN"));
        assertThat(authenticated.at("/state").asText()).isEqualTo("AUTHENTICATED");
        assertThat(authenticated.toString()).doesNotContain(otp,"token","contact");
        assertThat(ownerPhone(ownerInteraction(2),"authenticate",Map.of("shopSlug",slug,"accessCode",otp)).at("/state").asText())
                .isEqualTo("AUTH_REQUIRED");
        assertThat(ownerPhone(phoneCall,"list",Map.of()).at("/products").findValuesAsText("sku")).containsExactly("barfi");
        assertThat(ownerPhone(phoneCall,"lookup",Map.of("shopSlug",otherSlug,"sku","toast")).at("/state").asText()).isEqualTo("AUTH_REQUIRED");
        var available=ownerPhone(phoneCall,"lookup",Map.of("itemName","Barfi"));
        assertThat(available.at("/products/0/freeQuantity").asInt()).isEqualTo(750);
        var soldOut=ownerPhone(phoneCall,"stage",Map.of("sequence",1,"mutation","SET_STOCK","sku","barfi","stockQuantity",0,"language","hi-IN"));
        assertThat(soldOut.at("/replyText").asText()).contains("750 ग्राम से 0 ग्राम","250 ग्राम");
        assertThat(ownerPhone(phoneCall,"approve",Map.of("utterance","yes")).at("/state").asText()).isEqualTo("APPROVAL_REQUIRED");
        String approveOut="हाँ "+phoneApproval(soldOut);
        JsonNode changed=ownerPhone(phoneCall,"approve",Map.of("utterance",approveOut,"language","hi-IN"));
        assertThat(changed.at("/state").asText()).isEqualTo("APPLIED");
        assertThat(changed.at("/products/0/freeQuantity").asInt()).isZero();
        assertThat(changed.at("/products/0/stockQuantity").asInt()).isEqualTo(250);
        assertThat(ownerPhone(phoneCall,"approve",Map.of("utterance",approveOut,"language","hi-IN"))).isEqualTo(changed);
        assertThat(response(owner(get("/api/orders"),merchant),200).at("/0/status").asText()).isEqualTo("CONFIRMED");

        var stale=ownerPhone(phoneCall,"stage",Map.of("sequence",2,"mutation","ADD_STOCK","sku","barfi","stockQuantity",500));
        assertThat(ownerPhone(phoneCall,"stage",Map.of("sequence",2,"mutation","ADD_STOCK","sku","barfi","stockQuantity",500))).isEqualTo(stale);
        barfi.put("stockQuantity",550);
        response(owner(body(post("/api/owner/products"),barfi),merchant),200);
        assertThat(ownerPhone(phoneCall,"approve",Map.of("utterance","approve code "+phoneApproval(stale))).at("/state").asText()).isEqualTo("CHANGED");
        var addition=ownerPhone(phoneCall,"stage",Map.of("sequence",3,"mutation","ADD_STOCK","sku","barfi","stockQuantity",200));
        assertThat(ownerPhone(phoneCall,"approve",Map.of("utterance","approve code "+phoneApproval(addition)))
                .at("/products/0/freeQuantity").asInt()).isEqualTo(500);
        var price=ownerPhone(phoneCall,"stage",Map.of("sequence",4,"mutation","SET_PRICE","sku","barfi","pricePerUnitPaise",100000));
        assertThat(price.at("/replyText").asText()).contains("900 rupees per kilo to 1000 rupees per kilo");
        assertThat(ownerPhone(phoneCall,"approve",Map.of("utterance","approve code "+phoneApproval(price)))
                .at("/products/0/pricePerUnitPaise").asInt()).isEqualTo(100000);
        var freshItem=ownerPhone(phoneCall,"stage",Map.of("sequence",5,"mutation","ADD_ITEM","itemName","Lemon tart","unit","PIECE",
                "stockQuantity",12,"pricePerUnitPaise",8500));
        var added=ownerPhone(phoneCall,"approve",Map.of("utterance","approve code "+phoneApproval(freshItem)));
        assertThat(added.at("/products/0/unit").asText()).isEqualTo("PIECE");
        assertThat(added.at("/products/0/freeQuantity").asInt()).isEqualTo(12);
        assertThat(response(owner(get("/api/owner/products"),second),200).findValuesAsText("itemName")).containsExactly("Toast");
        String renewed=response(owner(post(codePath),merchant),200).at("/accessCode").asText();
        String wrong=renewed.equals("10000000")?"20000000":"10000000";
        for (int i=10;i<15;i++) ownerPhone(ownerInteraction(i),"authenticate",Map.of("shopSlug",slug,"accessCode",wrong));
        assertThat(ownerPhone(ownerInteraction(16),"authenticate",Map.of("shopSlug",slug,"accessCode",renewed)).at("/state").asText())
                .isEqualTo("ACCESS_LOCKED");
        assertThat(tenantCount(schema,"orders")).isEqualTo(1);
    }

    private JsonNode ownerPhone(String interaction,String event,Map<String,?> fields) throws Exception {
        var payload=new HashMap<String,Object>(fields);
        payload.put("event",event); payload.put("interactionId",interaction);
        payload.putIfAbsent("language","en-IN");
        return response(body(post("/api/voice/sarvam/owner").header("Authorization","Bearer isolated-native-pickup-fixture"),payload),200);
    }

    private static String ownerInteraction(int ordinal) {
        return "Ab12xCD3/OwnerPhoneFixtureID:2345"+String.format(java.util.Locale.ROOT,"%02d",ordinal);
    }

    private static String phoneApproval(JsonNode reply) {
        var challenge=java.util.regex.Pattern.compile("(?:approve code|हाँ) ([0-9](?: [0-9]){3})").matcher(reply.at("/replyText").asText());
        assertThat(challenge.find()).isTrue();
        return challenge.group(1);
    }

    @Test
    void guestCheckoutUsesOnlyItsMerchantCredentialAndNeverStoresAPhoneOrSendsANotice() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "guest-shop-" + suffix;
        String otherSlug = "guest-other-" + suffix;
        var merchant = signup(slug);
        var other = signup(otherSlug);
        shop(merchant, slug, "Guest pantry");
        shop(other, otherSlug, "Other pantry");
        String schema = tenants.bySlug(slug).schema();
        setClock(schema);
        response(owner(body(post("/api/owner/products"), Map.of("sku", "barfi", "itemName", "Barfi", "unit", "GRAMS",
                "pricePerUnitPaise", 90000, "stockQuantity", 1000)), merchant), 200);
        mvc.perform(post("/api/checkout/guest")).andExpect(status().isNotFound());
        mvc.perform(post("/api/checkout/guest").header("X-Rush-Shop", slug).header("Origin", "https://unrelated.example"))
                .andExpect(status().isForbidden());
        var issued = response(post("/api/checkout/guest").header("X-Rush-Shop", slug), 200);
        var separate = response(post("/api/checkout/guest").header("X-Rush-Shop", otherSlug), 200);
        String token = issued.at("/guestToken").asText();
        String session = issued.at("/sessionId").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(separate.at("/guestToken").asText());
        var turn = new HashMap<String, Object>(Map.of("sessionId", session, "turnId", UUID.randomUUID(),
                "sequence", 1, "guestToken", token, "utterance", "250 grams of barfi at 18:30"));
        String endpoint = "/api/checkout/text";
        mvc.perform(body(post(endpoint).header("X-Rush-Shop", otherSlug), turn)).andExpect(status().isUnauthorized());
        var quote = response(body(post(endpoint).header("X-Rush-Shop", slug), turn), 200);
        assertThat(quote.at("/quote/pricePaise").asInt()).isEqualTo(22500);
        assertThat(tenantCount(schema, "orders")).isZero();
        nextTextTurn(turn, 2, "yes");
        turn.put("confirmationQuoteId", quote.at("/quote/id").asText());
        assertThat(response(body(post(endpoint).header("X-Rush-Shop", slug), turn), 200).at("/order").isNull()).isTrue();
        nextTextTurn(turn, 3, "");
        turn.put("confirm", true);
        var confirmed = response(body(post(endpoint).header("X-Rush-Shop", slug), turn), 200);
        assertThat(confirmed.at("/order/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.at("/order/pickupCode").asText()).matches("[1-9][0-9]{7}");
        turn.put("receiptOnly", true);
        var forged = new HashMap<String, Object>(turn);
        forged.put("guestToken", separate.at("/guestToken").asText());
        mvc.perform(body(post(endpoint).header("X-Rush-Shop", slug), forged)).andExpect(status().isUnauthorized());
        assertThat(response(body(post(endpoint).header("X-Rush-Shop", slug), turn), 200).at("/order")).isEqualTo(confirmed.at("/order"));
        turn.put("receiptOnly", false);
        turn.put("confirm", false);
        nextTextTurn(turn, 4, "500 grams of barfi at 19:00");
        assertThat(response(body(post(endpoint).header("X-Rush-Shop", slug), turn), 200).at("/order")).isEqualTo(confirmed.at("/order"));
        String path = "/api/orders/" + confirmed.at("/order/id").asText() + "/pack";
        assertThat(response(owner(post(path), merchant), 200).at("/order/status").asText()).isEqualTo("PACKED");
        assertThat(tenantCount(schema, "orders")).isEqualTo(1);
        assertThat(tenantCount(schema, "outbox_messages")).isZero();
        TenantContext.withSchema(schema, () -> {
            assertThat(db.queryForObject("SELECT token_hash FROM guest_checkouts WHERE id=?::uuid", String.class, session))
                    .matches("[a-f0-9]{64}").isNotEqualTo(token);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM orders WHERE phone IS NULL AND customer_name='Guest' AND native_identity_kind='GUEST'", Integer.class))
                    .isEqualTo(1);
            db.update("UPDATE guest_checkouts SET expires_at=statement_timestamp()-interval '1 second' WHERE id=?::uuid", session);
            return null;
        });
        turn.put("receiptOnly", true);
        mvc.perform(body(post(endpoint).header("X-Rush-Shop", slug), turn)).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerSessionsSchemasBooksNativeTokensAndOutboxesRemainIndependent() throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String aSlug = "mithai-fixture-" + nonce;
        String bSlug = "bakery-fixture-" + nonce;
        int legacyBefore = db.queryForObject("SELECT COUNT(*) FROM public.orders", Integer.class);
        var a = signup("deepak-fixture-" + nonce);
        var b = signup("viraj-fixture-" + nonce);
        mvc.perform(owner(get("/api/orders"), a)).andExpect(status().isForbidden());
        shop(a, aSlug, "Deepak Mithai Bhandar");
        shop(b, bSlug, "Viraj's Bakery");
        assertThat(response(owner(get("/api/owner/products"), a), 200).size()).isZero();
        assertThat(response(owner(get("/api/owner/products"), b), 200).size()).isZero();

        var aBody = Map.of("sku", SHARED_SKU, "itemName", "Fixture Peda", "unit", "GRAMS", "pricePerUnitPaise", 120000, "stockQuantity", 1000);
        var bBody = Map.of("sku", SHARED_SKU, "itemName", "Fixture Cupcake", "unit", "PIECE", "pricePerUnitPaise", 8000, "stockQuantity", 12);
        mvc.perform(body(post("/api/owner/products"), aBody).cookie(a.cookie()).header("X-CSRF-Token", b.csrf()))
                .andExpect(status().isForbidden());
        response(owner(body(post("/api/owner/products"), aBody), a), 200);
        response(owner(body(post("/api/owner/products"), bBody), b), 200);
        JsonNode aMenu = response(get("/api/storefront/" + aSlug), 200);
        JsonNode bMenu = response(get("/api/storefront/" + bSlug), 200);
        assertThat(aMenu.at("/menu/items/0/pricePerKgPaise").asInt()).isEqualTo(120000);
        assertThat(aMenu.at("/menu/items/0/portions/0/grams").asInt()).isEqualTo(250);
        assertThat(aMenu.at("/menu/items/0/portions/0/pricePaise").asInt()).isEqualTo(30000);
        assertThat(aMenu.at("/menu/items/0/portions/1/pricePaise").asInt()).isEqualTo(60000);
        assertThat(bMenu.at("/menu/items/0/pricePerUnitPaise").asInt()).isEqualTo(8000);
        assertThat(bMenu.at("/menu/items/0/pricePerKgPaise").isMissingNode()).isTrue();
        assertThat(bMenu.at("/menu/items/0/portions/0/grams").isMissingNode()).isTrue();
        assertThat(bMenu.toString()).doesNotContain("owner_id", "schema_name", "counter", "phone");
        mvc.perform(get("/api/storefront/not-a-registered-shop")).andExpect(status().isNotFound());

        JsonNode orderA = order(a, 500, aMenu.at("/menu/items/0/portions/0/readyPickup").asText());
        JsonNode orderB = order(b, 2, bMenu.at("/menu/items/0/portions/0/readyPickup").asText());
        assertThat(orderA.at("/order/id").asInt()).isEqualTo(4821);
        assertThat(orderB.at("/order/id").asInt()).isEqualTo(4821);
        assertThat(orderA.at("/order/pricePaise").asInt()).isEqualTo(60000);
        assertThat(orderB.at("/order/pricePaise").asInt()).isEqualTo(16000);
        assertThat(orderB.at("/order/unit").asText()).isEqualTo("PIECE");
        assertThat(response(owner(get("/api/orders").header("X-Rush-Shop", bSlug), a), 200).at("/0/itemName").asText()).isEqualTo("Fixture Peda");
        assertThat(response(owner(get("/api/orders").header("X-Rush-Shop", aSlug), b), 200).at("/0/itemName").asText()).isEqualTo("Fixture Cupcake");
        assertThat(response(owner(get("/api/orders/4821/messages"), a), 200).size()).isEqualTo(1);
        assertThat(response(owner(get("/api/orders/4821/messages"), b), 200).size()).isEqualTo(1);
        var lateStock = new HashMap<>(bBody);
        lateStock.put("readyAt", Instant.parse(orderB.at("/order/pickupAt").asText()).toString());
        mvc.perform(owner(body(post("/api/owner/products"), lateStock), b)).andExpect(status().isConflict());
        assertThat(response(owner(get("/api/owner/products"), b), 200).at("/0/freeQuantity").asInt()).isEqualTo(10);
        mvc.perform(owner(body(post("/ops/demo/reset"), Map.of()).header("X-Staff-Token", "platform-operator-fixture"), a))
                .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public.orders", Integer.class)).isEqualTo(legacyBefore);

        String aKey = response(owner(post("/api/owner/shop/native-token"), a), 200).at("/token").asText();
        String bKey = response(owner(post("/api/owner/shop/native-token"), b), 200).at("/token").asText();
        assertThat(aKey).isNotEqualTo(bKey);
        assertThat(nativeScope(aKey, null)).isEqualTo(tenants.bySlug(aSlug).schema());
        assertThat(nativeScope(bKey, bSlug)).isEqualTo(tenants.bySlug(bSlug).schema());
        var mismatch = servletRequest(aKey, bSlug);
        var denied = new MockHttpServletResponse();
        filter.doFilter(mismatch, denied, (req, res) -> { throw new AssertionError("Wrong tenant was entered"); });
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(TenantContext.legacy()).isTrue();
        assertThat(TenantContext.nativeAuthorized()).isFalse();

        mvc.perform(owner(post("/api/auth/logout"), a)).andExpect(status().isNoContent());
        mvc.perform(get("/api/orders").cookie(a.cookie()).header("X-Staff-Token", "platform-legacy-fixture"))
                .andExpect(status().isUnauthorized());
        assertThat(response(owner(get("/api/orders"), b), 200).size()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public.rush_accounts WHERE preview_expires_at IS NULL"
                + " AND password_hash NOT LIKE 'pbkdf2-sha256$600000$%'", Integer.class)).isZero();
        provesPooledConnectionsReset(tenants.bySlug(aSlug).schema());
    }

    @Test
    void demoSessionsReuseAnIsolatedEditableShopWithoutUsingRealRegistrationSlots() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        int registrations=db.queryForObject("SELECT count(*) FROM public.rush_accounts WHERE preview_expires_at IS NULL",Integer.class);
        UUID key=UUID.randomUUID();
        mvc.perform(body(post("/api/auth/demo").header("Origin","https://unrelated.example"),Map.of("sessionKey",key)))
                .andExpect(status().isForbidden());
        var first=demo(key);
        var repeated=demo(key);
        var separate=demo(UUID.randomUUID());
        assertThat(first.slug()).isEqualTo(repeated.slug()).isNotEqualTo(separate.slug());
        assertThat(db.queryForObject("SELECT count(*) FROM public.rush_accounts WHERE preview_expires_at IS NULL",Integer.class)).isEqualTo(registrations);
        var early=response(owner(get("/api/owner/products"),first.credential()),200);
        assertThat(early.findValuesAsText("sku")).containsExactlyInAnyOrder("barfi","kaju-katli");
        assertThat(response(owner(get("/api/owner/pickup-hours"),first.credential()),200).at("/closesAt").asText()).isEqualTo("21:00");
        response(owner(body(post("/api/owner/products"),Map.of("sku","barfi","itemName","Barfi","unit","GRAMS",
                "pricePerUnitPaise",90000,"stockQuantity",1800)),first.credential()),200);
        assertThat(response(owner(get("/api/owner/products"),repeated.credential()),200).at("/0/freeQuantity").asInt()).isEqualTo(1800);
        assertThat(response(owner(get("/api/owner/products"),separate.credential()),200).at("/0/freeQuantity").asInt()).isEqualTo(3000);
        assertThat(response(owner(get("/api/staff/snapshot"),first.credential()),200).at("/orders").size()).isZero();
        mvc.perform(get("/api/storefront/"+first.slug())).andExpect(status().isNotFound());
        assertThat(response(get("/api/public/shops").param("query",first.slug()),200).at("/shops").size()).isZero();
        assertThat(nativeRouting.choose("fixture-preview-blocked",first.slug())).isNull();
        mvc.perform(owner(post("/api/owner/phone/access"),first.credential())).andExpect(status().isForbidden());
        mvc.perform(owner(post("/api/owner/whatsapp/link"),first.credential())).andExpect(status().isForbidden());
        mvc.perform(owner(post("/api/quotes"),first.credential())).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/auth/signup"),Map.of("username","not an email","password",PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(result -> assertThat(result.getResolvedException().getMessage()).contains("Username"));
        mvc.perform(body(post("/api/auth/signup"),Map.of("username","valid-"+suffix,"password","too-short")))
                .andExpect(status().isBadRequest()).andExpect(result -> assertThat(result.getResolvedException().getMessage()).contains("Password"));
        var normal=signup("valid-"+suffix);
        mvc.perform(owner(body(post("/api/auth/demo"),Map.of("sessionKey",UUID.randomUUID())),normal)).andExpect(status().isConflict());
        db.update("UPDATE public.rush_accounts SET preview_expires_at=statement_timestamp()-interval '1 second'"
                + " WHERE id=(SELECT owner_id FROM public.rush_tenants WHERE slug=?)",first.slug());
        previews.prune();
        assertThat(db.queryForObject("SELECT count(*) FROM public.rush_tenants WHERE slug=?",Integer.class,first.slug())).isZero();
        assertThat(response(owner(get("/api/auth/session"),normal),200).at("/demoPreview").asBoolean()).isFalse();
    }

    private PreviewAccess demo(UUID key) throws Exception {
        MvcResult result=mvc.perform(body(post("/api/auth/demo"),Map.of("sessionKey",key))).andExpect(status().isOk()).andReturn();
        String setCookie=result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("Secure","HttpOnly","SameSite=Strict");
        var data=json.readTree(result.getResponse().getContentAsByteArray());
        assertThat(data.at("/demoPreview").asBoolean()).isTrue();
        String token=setCookie.split(";",2)[0].split("=",2)[1];
        return new PreviewAccess(data.at("/shop/slug").asText(),new Credentials(new Cookie(OwnerAccounts.COOKIE,token),data.at("/csrfToken").asText()));
    }

    private record PreviewAccess(String slug,Credentials credential) {}

    private void provesPooledConnectionsReset(String shop) throws Exception {
        try (var ignored = TenantContext.enter(shop); Connection scoped = routed.getConnection()) {
            assertThat(scoped.getSchema()).isEqualTo(shop);
            scoped.setAutoCommit(false);
            // Closing on an exceptional path must roll back and reset the physical pooled connection.
            try (var statement = scoped.createStatement()) { statement.execute("SELECT 1"); }
            throw new IllegalStateException("Synthetic owner request failure");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("Synthetic owner request failure");
        }
        var borrowed = new ArrayList<Connection>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                Connection raw = pool.getConnection();
                borrowed.add(raw);
                assertThat(raw.getSchema()).isEqualTo("public");
            }
        } finally {
            for (var raw : borrowed) raw.close();
        }
    }

    private String nativeScope(String token, String slug) throws Exception {
        var actual = new AtomicReference<String>();
        filter.doFilter(servletRequest(token, slug), new MockHttpServletResponse(), (req, res) -> {
            assertThat(TenantContext.nativeAuthorized()).isTrue();
            actual.set(TenantContext.schema());
        });
        return actual.get();
    }

    private MockHttpServletRequest servletRequest(String token, String slug) {
        var req = new MockHttpServletRequest("POST", "/api/voice/sarvam/turn");
        req.addHeader("Authorization", "Bearer " + token);
        if (slug != null) req.addHeader("X-Rush-Shop", slug);
        return req;
    }

    private Credentials signup(String username) throws Exception {
        MvcResult result = mvc.perform(body(post("/api/auth/signup"), Map.of("username", username, "password", PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Strict");
        String token = cookie.split(";", 2)[0].split("=", 2)[1];
        return new Credentials(new Cookie(OwnerAccounts.COOKIE, token), json.readTree(result.getResponse().getContentAsByteArray()).at("/csrfToken").asText());
    }

    private void shop(Credentials owner, String slug, String displayName) throws Exception {
        response(owner(body(post("/api/owner/shop"), Map.of("slug", slug, "displayName", displayName)), owner), 201);
    }

    private JsonNode order(Credentials owner, int quantity, String pickup) throws Exception {
        var q = response(owner(body(post("/api/quotes"), Map.of("sessionId", "identical-buyer", "customerName", "Test buyer",
                "sku", SHARED_SKU, "phone", "+12025550101", "quantity", quantity, "pickupTime", pickup)), owner), 200);
        return response(owner(body(post("/api/quotes/" + q.at("/quote/id").asText() + "/accept"),
                Map.of("sessionId", "identical-buyer", "turnId", "identical-yes")), owner), 201);
    }

    private MockHttpServletRequestBuilder owner(MockHttpServletRequestBuilder request, Credentials owner) {
        return request.cookie(owner.cookie()).header("X-CSRF-Token", owner.csrf());
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Map<String, ?> value) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
    }
    private JsonNode response(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray());
    }
    private record Credentials(Cookie cookie, String csrf) {}
}
