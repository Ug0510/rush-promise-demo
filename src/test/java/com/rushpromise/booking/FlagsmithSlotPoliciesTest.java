package com.rushpromise.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlagsmithSlotPoliciesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void pinnedSdkEvaluatesHostedProfilesAndChangedThreshold() throws Exception {
        var fixture = fixture();
        var normal = read(fixture);
        assertThat(normal.evaluate("synthetic", 79)).extracting(FlagsmithDocument.Profile::name, FlagsmithDocument.Profile::threshold)
                .containsExactly("NORMAL", 4000);
        assertThat(normal.evaluate("synthetic", 80)).extracting(FlagsmithDocument.Profile::name, FlagsmithDocument.Profile::threshold)
                .containsExactly("BUSY_SLOT", 2000);
        rush(fixture, true);
        var rush = read(fixture);
        assertThat(rush.evaluate("synthetic", 79)).extracting(FlagsmithDocument.Profile::name, FlagsmithDocument.Profile::threshold)
                .containsExactly("RUSH", 3000);
        assertThat(rush.evaluate("synthetic", 80).name()).isEqualTo("BUSY_SLOT");

        ((ObjectNode) fixture.at("/project/segments/0/rules/0/conditions/0")).put("value", "85");
        var changed = read(fixture);
        assertThat(changed.evaluate("synthetic", 82).name()).isEqualTo("RUSH");
        assertThat(changed.evaluate("synthetic", 85).name()).isEqualTo("BUSY_SLOT");
        assertThat(changed.digest()).isNotEqualTo(rush.digest());
        assertThat(read((ObjectNode) json.readTree(changed.projection())).digest()).isEqualTo(changed.digest());
    }

    @Test
    void rejectsBadProfileValuesMissingRulesAndWrongPrecedence() throws Exception {
        for (String variant : new String[]{"approval_above_g", "hold_min", "reason_code", "quote_ttl_s"}) {
            var fixture = fixture();
            var flag = (ObjectNode) fixture.at("/project/segments/1/feature_states/0");
            var config = (ObjectNode) json.readTree(flag.get("feature_state_value").textValue());
            switch (variant) {
                case "approval_above_g" -> config.put(variant, 9000);
                case "hold_min" -> config.put(variant, "ten");
                case "reason_code" -> config.put(variant, "SLOT_NEAR_CAPACITY");
                case "quote_ttl_s" -> config.remove(variant);
                default -> throw new AssertionError(variant);
            }
            flag.put("feature_state_value", config.toString());
            assertThatThrownBy(() -> read(fixture)).isInstanceOf(IllegalArgumentException.class);
        }
        var wrongPriority = fixture();
        ((ObjectNode) wrongPriority.at("/project/segments/0/feature_states/0/feature_segment")).put("priority", 5);
        assertThatThrownBy(() -> read(wrongPriority)).hasMessageContaining("higher priority");
        var wrongRule = fixture();
        ((ObjectNode) wrongRule.at("/project/segments/0/rules/0/conditions/0")).put("value", "0");
        assertThatThrownBy(() -> read(wrongRule)).hasMessageContaining("threshold");
    }

    @Test
    void refreshMakesOnlyDocumentRequestAndCapturedPoliciesStayAtomic() throws Exception {
        var wire = new AtomicReference<>(fixture());
        var requests = new AtomicInteger();
        var observedHeader = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/environment-document/", exchange -> {
            requests.incrementAndGet();
            observedHeader.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("X-Environment-Key"));
            var document = wire.get();
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            Instant revision = Instant.parse(document.get("updated_at").textValue());
            String header = BigDecimal.valueOf(revision.getEpochSecond()).add(BigDecimal.valueOf(revision.getNano(), 9)).toPlainString();
            exchange.getResponseHeaders().set("X-Flagsmith-Document-Updated-At", header);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        try {
            var database = mock(JdbcTemplate.class);
            when(database.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
            var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/flags/");
            var service = new FlagsmithSlotPolicies(json, database, meters, true, "local-fixture-key", url, 60_000);
            assertThat(service.capture().evaluate(2500, 3, 5).source()).isEqualTo("FALLBACK");
            assertThat(requests).hasValue(0);
            service.refresh();
            var captured = service.capture();
            var normal = captured.evaluate(2500, 3, 5);
            assertThat(normal.source()).isEqualTo("LIVE");
            assertThat(normal.approvalAboveGrams()).isEqualTo(4000);
            assertThat(observedHeader).hasValue("GET /api/v1/environment-document/ local-fixture-key");

            var next = wire.get().deepCopy();
            next.put("updated_at", Instant.parse(next.get("updated_at").textValue()).plusSeconds(1).toString());
            rush(next, true);
            wire.set(next);
            service.refresh();
            var rush = service.capture().evaluate(2500, 3, 5);
            assertThat(rush.approvalAboveGrams()).isEqualTo(3000);
            assertThat(rush.version()).isNotEqualTo(normal.version());
            for (int i = 0; i < 20; i++) {
                assertThat(captured.evaluate(2500, 3, 5).version()).isEqualTo(normal.version());
                assertThat(service.capture().evaluate(2500, 4, 5).approvalAboveGrams()).isEqualTo(2000);
            }
            assertThat(requests).hasValue(2);

            var invalid = next.deepCopy();
            invalid.put("updated_at", Instant.parse(next.get("updated_at").textValue()).plusSeconds(1).toString());
            ((ObjectNode) invalid.at("/feature_states/1")).put("feature_state_value", "{}");
            wire.set(invalid);
            service.refresh();
            service.refresh();
            wire.set(fixture());
            service.refresh();
            var retained = service.capture().evaluate(2500, 3, 5);
            assertThat(retained.version()).isEqualTo(rush.version());
            assertThat(retained.fetchedAt()).isEqualTo(rush.fetchedAt());
            assertThat(meters.get("rush.flagsmith.document.rejected").counter().count()).isEqualTo(2);
        } finally {
            meters.close();
            server.stop(0);
        }
    }

    private FlagsmithDocument read(ObjectNode fixture) throws Exception {
        return FlagsmithDocument.read(json, fixture.toString(), null);
    }

    private ObjectNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/flagsmith/env-rules-v1.json")) {
            return (ObjectNode) json.readTree(input);
        }
    }

    private static void rush(ObjectNode fixture, boolean enabled) {
        ((ObjectNode) fixture.at("/feature_states/0")).put("enabled", enabled);
    }
}
