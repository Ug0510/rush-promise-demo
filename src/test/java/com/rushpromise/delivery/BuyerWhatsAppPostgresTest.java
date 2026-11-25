package com.rushpromise.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.platform.TenantContext;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${RUSH_TEST_JDBC_URL}", "spring.datasource.username=${RUSH_TEST_JDBC_USER:shop_app}",
        "spring.datasource.password=${RUSH_TEST_JDBC_PASSWORD}", "RUSH_MERCHANT_DEMO_CLOCK=true",
        "RUSH_SARVAM_TOOL_TOKEN=buyer-pickup-fixture", "RUSH_BUYER_WHATSAPP_ENABLED=true",
        "TWILIO_ACCOUNT_SID=ACaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "TWILIO_AUTH_TOKEN=buyer-signature-fixture",
        "TWILIO_MSG_FROM=whatsapp:+12025550171", "RUSH_PUBLIC_BASE_URL=https://fixture.example",
        "RUSH_TWILIO_OWNER_LINK_URL=https://fixture.example/api/twilio/owner-link"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUSH_TEST_JDBC_URL", matches = "jdbc:postgresql:.*/rush_test_[A-Za-z0-9_]+(?:\\?.*)?")
class BuyerWhatsAppPostgresTest {
    private static final String SLUG = "buyer-pairing-fixture";
    private static final String ACCOUNT = "AC" + "a".repeat(32);
    private static final String TOKEN = "buyer-signature-fixture";
    private static final String WEBHOOK = "https://fixture.example/api/twilio/owner-link";
    private static final String SENDER = "+12025550172";
    private static final String OTHER = "+12025550173";
    private static final String SID = "SM" + "b".repeat(32);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BuyerWhatsApp buyers;

    @Test
    void signedOptInBindsOneCommittedPickupCodeAndOneProviderAttempt() throws Exception {
        var auth = mvc.perform(body(post("/api/auth/signup"), Map.of("username", SLUG, "password", "buyer-only-test-password123")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String[] sessionCookie = auth.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2);
        Cookie cookie = new Cookie(sessionCookie[0], sessionCookie[1]);
        String csrf = json.readTree(auth.getContentAsByteArray()).get("csrfToken").asText();
        response(body(post("/api/owner/shop").cookie(cookie).header("X-CSRF-Token", csrf),
                Map.of("slug", SLUG, "displayName", "Buyer Fixture")), 201);
        response(body(post("/api/owner/products").cookie(cookie).header("X-CSRF-Token", csrf),
                Map.of("sku", "barfi", "itemName", "Barfi", "unit", "GRAMS", "pricePerUnitPaise", 90000, "stockQuantity", 3500)), 200);
        String schema = db.queryForObject("SELECT schema_name FROM public.rush_tenants WHERE slug=?", String.class, SLUG);
        TenantContext.withSchema(schema, () -> db.update("UPDATE shop_state SET demo_now='2031-02-09T12:15:00Z',demo_started_at=NULL"));

        String call = "20261125/" + "abc:defghijklmnopqrstuvwxy";
        voice(call, "select-shop", Map.of("shopSlug", SLUG));
        var offer = voice(call, "buyer-whatsapp", Map.of());
        assertThat(offer.get("state").asText()).isEqualTo("BUYER_WHATSAPP_LINK_REQUIRED");
        var challenge = Pattern.compile("PICKUP [1-9][0-9]{7}").matcher(offer.get("replyText").asText());
        assertThat(challenge.find()).isTrue();
        String command = challenge.group();
        assertThat(voice(call, "buyer-whatsapp", Map.of())).isEqualTo(offer);
        inbound(command, SID, OTHER, false, 403);
        assertThat(voice(call, "buyer-whatsapp", Map.of())).isEqualTo(offer);
        inbound(command, SID, SENDER, true, 200);
        inbound(command, "SM" + "c".repeat(32), OTHER, true, 200);
        assertThat(voice(call, "buyer-whatsapp", Map.of()).get("state").asText()).isEqualTo("BUYER_WHATSAPP_LINKED");

        var quoted = voice(call, "turn", Map.of("sequence", 1, "utterance", "500 grams barfi at 18:00"));
        String quoteId = quoted.at("/quote/id").asText();
        var yes = Map.of("sequence", 2, "utterance", "yes", "confirmationQuoteId", quoteId);
        var confirmed = voice(call, "turn", yes);
        assertThat(confirmed.get("state").asText()).isEqualTo("CONFIRMED");
        String pickup = confirmed.at("/order/pickupCode").asText();
        assertThat(pickup).matches("[1-9][0-9]{7}");
        assertThat(confirmed.get("replyText").asText()).contains("queued a WhatsApp");
        var replay = voice(call, "turn", yes);
        assertThat(replay.get("order")).isEqualTo(confirmed.get("order"));
        assertThat(replay.get("replyText")).isEqualTo(confirmed.get("replyText"));
        assertThat(voice(call, "buyer-whatsapp", Map.of()).get("state").asText()).isEqualTo("BUYER_WHATSAPP_ALREADY_BOOKED");
        assertThat(db.queryForObject("SELECT count(*) FROM public.rush_buyer_pickup_messages", Integer.class)).isEqualTo(1);
        assertThat(TenantContext.withSchema(schema, () -> db.queryForObject("SELECT count(*) FROM outbox_messages", Integer.class))).isZero();

        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<String> sent = new AtomicReference<>();
        var fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/messages", exchange -> {
            submissions.incrementAndGet();
            sent.set(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            byte[] response = ("{\"sid\":\"" + SID + "\",\"status\":\"queued\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        fake.start();
        try (var ignored = TenantContext.enter(schema)) {
            var dispatch = buyers.claim();
            assertThat(dispatch).isNotNull();
            assertThat(dispatch.recipient()).isEqualTo(SENDER);
            assertThat(buyers.stillPermitted(dispatch)).isTrue();
            var settings = new DeliverySettings("console", ACCOUNT, TOKEN, "whatsapp:+12025550171", "https://fixture.example", "");
            var twilio = new TwilioWhatsApp(settings, json, URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/messages"));
            var result = twilio.sendBuyer(dispatch);
            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(buyers.observe(dispatch.attemptId(), result)).isTrue();
            assertThat(buyers.claim()).isNull();
            assertThat(buyers.observe(dispatch.attemptId(), new DeliveryResult("DELIVERED", SID, null))).isTrue();
            assertThat(buyers.claim()).isNull();
            assertThat(submissions).hasValue(1);
            assertThat(sent.get()).contains("To=whatsapp:" + SENDER, "Pickup code: " + pickup).doesNotContain(OTHER);
        } finally {
            fake.stop(0);
        }

        String afterHangup = selectedCall();
        String afterHangupCommand = linkCommand(afterHangup);
        assertThat(voice(afterHangup, "turn", yes(voice(afterHangup, "turn",
                Map.of("sequence", 1, "utterance", "500 grams barfi at 18:30")))).get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(messageCount()).isEqualTo(1);
        inbound(afterHangupCommand, "SM" + "d".repeat(32), OTHER, true, 200);
        assertThat(messageCount()).isEqualTo(2);

        String racingCall = selectedCall();
        String racingCommand = linkCommand(racingCall);
        var racingYes = yes(voice(racingCall, "turn", Map.of("sequence", 1, "utterance", "500 grams barfi at 19:00")));
        var pairing = CompletableFuture.runAsync(() -> {
            try { inbound(racingCommand, "SM" + "e".repeat(32), SENDER, true, 200); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        assertThat(voice(racingCall, "turn", racingYes).get("state").asText()).isEqualTo("CONFIRMED");
        pairing.join();
        voice(racingCall, "turn", racingYes);
        assertThat(messageCount()).isEqualTo(3);

        String newlyAsked = selectedCall();
        var fresh = voice(newlyAsked, "turn", yes(voice(newlyAsked, "turn",
                Map.of("sequence", 1, "utterance", "500 grams barfi at 19:30"))));
        assertThat(fresh.get("state").asText()).isEqualTo("CONFIRMED");
        long newNumber = fresh.at("/order/id").asLong();
        TenantContext.withSchema(schema, () -> db.update("UPDATE orders SET created_at=statement_timestamp() WHERE id=?", newNumber));
        inbound(linkCommand(newlyAsked), "SM" + "f".repeat(32), SENDER, true, 200);
        assertThat(messageCount()).isEqualTo(4);
        assertThat(voice(newlyAsked, "buyer-whatsapp", Map.of()).get("state").asText()).isEqualTo("BUYER_WHATSAPP_QUEUED");
    }

    private int messageCount() {
        return db.queryForObject("SELECT count(*) FROM public.rush_buyer_pickup_messages", Integer.class);
    }

    private String selectedCall() throws Exception {
        String value = UUID.randomUUID().toString().replace("-", "");
        String call = "20261125/" + value.substring(0, 12) + ":" + value.substring(12, 25);
        voice(call, "select-shop", Map.of("shopSlug", SLUG));
        return call;
    }

    private String linkCommand(String call) throws Exception {
        var offer = voice(call, "buyer-whatsapp", Map.of());
        assertThat(offer.get("state").asText()).isEqualTo("BUYER_WHATSAPP_LINK_REQUIRED");
        var code = Pattern.compile("PICKUP [1-9][0-9]{7}").matcher(offer.get("replyText").asText());
        assertThat(code.find()).isTrue();
        return code.group();
    }

    private static Map<String, Object> yes(JsonNode quote) {
        return Map.of("sequence", 2, "utterance", "yes", "confirmationQuoteId", quote.at("/quote/id").asText());
    }

    private JsonNode voice(String call, String event, Map<String, ?> fields) throws Exception {
        var request = new HashMap<String, Object>(fields);
        request.putAll(Map.of("event", event, "language", "en-IN", "interactionId", call, "pickupMode", "interaction", "routed", true));
        return response(body(post("/api/voice/sarvam/turn").header("Authorization", "Bearer buyer-pickup-fixture"), request), 200);
    }

    private void inbound(String command, String sid, String buyer, boolean signed, int expected) throws Exception {
        var form = new TreeMap<>(Map.of("AccountSid", ACCOUNT, "MessageSid", sid, "Body", command,
                "From", "whatsapp:" + buyer, "To", "whatsapp:+12025550171"));
        var request = post("/api/twilio/owner-link").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach((key, value) -> request.param(key, value));
        if (signed) {
            var payload = new StringBuilder(WEBHOOK);
            form.forEach((key, value) -> payload.append(key).append(value));
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            request.header("X-Twilio-Signature", Base64.getEncoder().encodeToString(mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8))));
        }
        var response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
        if (expected == 200) assertThat(response.getContentAsString()).isEqualTo("<Response/>");
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Map<String, ?> value) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray());
    }
}
