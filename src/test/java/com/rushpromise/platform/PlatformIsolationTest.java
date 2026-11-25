package com.rushpromise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${RUSH_TEST_JDBC_URL}",
        "spring.datasource.username=${RUSH_TEST_JDBC_USER:shop_app}",
        "spring.datasource.password=${RUSH_TEST_JDBC_PASSWORD}",
        "RUSH_SIGNUP_ENABLED=true",
        "RUSH_STAFF_TOKEN=platform-legacy-fixture",
        "RUSH_OPERATOR_TOKEN=platform-operator-fixture"
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
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public.rush_accounts WHERE password_hash NOT LIKE 'pbkdf2-sha256$600000$%'", Integer.class)).isZero();
        provesPooledConnectionsReset(tenants.bySlug(aSlug).schema());
    }

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
