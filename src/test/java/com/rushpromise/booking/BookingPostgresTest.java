package com.rushpromise.booking;

import static com.rushpromise.booking.BookingModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

@SpringBootTest(properties = {
        "spring.datasource.url=${RUSH_TEST_JDBC_URL}",
        "spring.datasource.username=${RUSH_TEST_JDBC_USER:shop_app}",
        "spring.datasource.password=${RUSH_TEST_JDBC_PASSWORD}",
        "rush.demo.date=2031-02-09",
        "rush.approvals.sweep-ms=600000",
        "rush.staff.token=test-role-staff",
        "rush.owner.token=test-role-owner",
        "rush.operator.token=test-role-operator",
        "rush.booking.api-token=test-role-booking"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "RUSH_TEST_JDBC_URL", matches = "jdbc:postgresql:.*/rush_test_[A-Za-z0-9_]+(?:\\?.*)?")
class BookingPostgresTest {
    @Autowired BookingService booking;
    @Autowired BookingStore store;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ShopService shop;
    @Autowired JdbcTemplate db;
    @Autowired MeterRegistry metrics;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean PolicyService policy;

    @BeforeEach
    void reset() {
        normal();
        assertThat(shop.reset(null).status()).isEqualTo(200);
    }

    @Test
    void d01AndD02KeepTheFixtureDateStockAndOutboxAtomic() {
        var first = quote(1, "kaju-katli", 500, "18:30");
        assertThat(first.pickupAt()).isEqualTo(Instant.parse("2031-02-09T13:00:00Z"));
        assertThat(first.pricePaise()).isEqualTo(60_000);
        assertThat(amount("A")).isEqualTo(500);
        var countedAt = db.queryForObject("SELECT counter_counted_at FROM batches WHERE id='A'", java.time.OffsetDateTime.class);
        var order = accept(first).order();
        assertThat(order.id()).isEqualTo(4821);
        assertThat(amount("A")).isZero();
        assertThat(count("SELECT counter_grams_counted FROM batches WHERE id='A'")).isEqualTo(2000);
        assertThat(db.queryForObject("SELECT counter_counted_at FROM batches WHERE id='A'", java.time.OffsetDateTime.class)).isEqualTo(countedAt);
        assertThat(used("18:30")).isEqualTo(1);
        var alternative = makeQuote(2, "kaju-katli", 500, "18:30");
        assertThat(alternative.reason()).isEqualTo("OUT_OF_STOCK");
        assertThat(alternative.quote().pickupTime()).isEqualTo("19:00");
        assertThat(alternative.quote().batchId()).isEqualTo("B");
        assertThat(shop.action(4821, "pack", null).status()).isEqualTo(200);
        assertThat(shop.action(4821, "pack", null).status()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE status='QUEUED'")).isEqualTo(2);
        var incident = shop.reset(new ResetRequest("incident", phone(1)));
        assertThat(incident.order().id()).isEqualTo(4821);
        assertThat(incident.order().runId()).isNotEqualTo(order.runId());
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE status='DELIVERED' AND kind='CONFIRMED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE status='QUEUED'")).isZero();
        shop.reset(null);
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        assertThat(amount("A")).isEqualTo(500);
        assertThat(accept(quote(3, "kaju-katli", 500, "18:30")).order().id()).isEqualTo(4821);
    }

    @Test
    void d03PolicyRequotePersistsTheRejectionAndProtectsPriorOrder() {
        var original = accept(quote(1, "kaju-katli", 500, "18:30")).order();
        var old = quote(2, "barfi", 4000, "20:00");
        assertThat(old.requiresApproval()).isFalse();
        rush();
        var change = accept(old);
        assertThat(change.status()).isEqualTo(409);
        assertThat(change.code()).isEqualTo("TERMS_CHANGED");
        assertThat(change.quote().requiresApproval()).isTrue();
        assertThat(change.quote().pricePaise()).isEqualTo(360_000);
        assertThat(accept(old)).isEqualTo(change);
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        var pending = accept(change.quote()).order();
        assertThat(pending.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(original.policy().version()).isEqualTo("v-normal-1");
        assertThat(original.policy().pickupGraceMinutes()).isEqualTo(30);
        assertThat(pending.policy().version()).isEqualTo("v-rush-1");
        assertThat(pending.policy().pickupGraceMinutes()).isEqualTo(20);
        assertThat(shop.orders()).containsExactly(original, pending);
    }

    @Test
    void genuinePriceChangeRequotesButUnrelatedTtlChangeDoesNot() {
        var old = quote(1, "kaju-katli", 500, "18:30");
        db.update("UPDATE items SET price_per_kg_paise=130000 WHERE sku='kaju-katli'");
        var result = accept(old);
        assertThat(result.code()).isEqualTo("TERMS_CHANGED");
        assertThat(result.quote().pricePaise()).isEqualTo(65_000);
        Policy prior = policy.current();
        when(policy.current()).thenReturn(new Policy(prior.version(), prior.source(), prior.fetchedAt(), prior.evaluatedAt(),
                prior.rushMode(), prior.maxGrams(), prior.approvalAboveGrams(), prior.approvalHoldMinutes(), prior.leadMinutes(), 240, prior.pickupGraceMinutes()));
        assertThat(accept(result.quote()).status()).isEqualTo(201);
    }

    @Test
    void c04LastStockRaceHasOneWinnerAcrossFiftyIndependentRuns() throws Exception {
        for (int repetition = 0; repetition < 50; repetition++) {
            shop.reset(null);
            db.update("UPDATE pickup_slots SET capacity=2 WHERE (pickup_at AT TIME ZONE 'Asia/Kolkata')::time=TIME '18:30'");
            var candidates = List.of(quote(1, "kaju-katli", 500, "18:30"), quote(2, "kaju-katli", 500, "18:30"));
            var outcomes = race(2, i -> accept(candidates.get(i)));
            assertThat(outcomes).extracting(Outcome::status).containsExactlyInAnyOrder(201, 409);
            var loser = outcomes.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
            assertThat(loser.reason()).isEqualTo("OUT_OF_STOCK");
            assertThat(loser.quote().pickupTime()).isEqualTo("19:00");
            assertThat(loser.quote().batchId()).isEqualTo("B");
            assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM booking_keys WHERE status=201")).isEqualTo(1);
            assertThat(amount("A")).isZero();
            assertThat(used("18:30")).isEqualTo(1);
        }
    }

    @Test
    void c05AndC06SlotRacesRollBackStockAndOfferAnotherEligibleSlot() throws Exception {
        db.update("UPDATE batches SET phone_grams_free=1500, phone_grams_total=1500 WHERE id='A'");
        var candidates = List.of(quote(1, "kaju-katli", 500, "18:30"), quote(2, "kaju-katli", 500, "18:30"));
        var outcomes = race(2, i -> accept(candidates.get(i)));
        var lost = outcomes.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
        assertThat(lost.reason()).isEqualTo("SLOT_FULL");
        assertThat(lost.quote().pickupTime()).isEqualTo("19:00");
        assertThat(amount("A")).isEqualTo(1000);
        assertThat(accept(quote(3, "kaju-katli", 500, "19:00")).status()).isEqualTo(201);
        var lostAlternative = accept(lost.quote());
        assertThat(lostAlternative.reason()).isEqualTo("SLOT_FULL");
        assertThat(lostAlternative.quote().pickupTime()).isEqualTo("19:30");
        assertThat(amount("A")).isEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(2);
    }

    @Test
    void c07TwoClientKeysOnOneQuoteReplayTheSameOrder() throws Exception {
        var q = quote(1, "kaju-katli", 500, "18:30");
        var outcomes = race(2, i -> booking.accept(q.id().toString(), new AcceptRequest(q.sessionId(), "turn"), "key-" + i));
        assertThat(outcomes).extracting(Outcome::status).containsExactlyInAnyOrder(201, 200);
        assertThat(outcomes).allSatisfy(result -> assertThat(result.order().id()).isEqualTo(4821));
        assertThat(booking.resolve(q.id().toString(), new AcceptRequest(q.sessionId(), "turn"), "key-1").order().id()).isEqualTo(4821);
        var other = quote(2, "kaju-katli", 500, "18:30");
        assertThat(booking.accept(other.id().toString(), new AcceptRequest(other.sessionId(), "turn"), "key-1").code()).isEqualTo("KEY_REUSED");
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isEqualTo(1);
    }

    @Test
    void c08LostAcknowledgementResolvesDurableOutcomeWithoutBlindRetry() {
        var quote = quote(1, "kaju-katli", 500, "18:30");
        var yes = new AcceptRequest(quote.sessionId(), "yes");
        var before = new TransactionTemplate(transactionManager) {
            @Override public <T> T execute(TransactionCallback<T> job) {
                throw new CannotCreateTransactionException("Simulated connection unavailable before commit");
            }
        };
        var unreachable = new BookingService(store, policy, before, metrics);
        assertThat(unreachable.accept(quote.id().toString(), yes, null).code()).isEqualTo("BOOKING_UNCERTAIN");
        assertThat(booking.resolve(quote.id().toString(), yes, null).status()).isEqualTo(404);
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        var after = new TransactionTemplate(transactionManager) {
            @Override public <T> T execute(TransactionCallback<T> job) {
                super.execute(job);
                throw new DataAccessResourceFailureException("Simulated response loss just after real commit");
            }
        };
        var lostResponse = new BookingService(store, policy, after, metrics);
        assertThat(lostResponse.accept(quote.id().toString(), yes, null).code()).isEqualTo("BOOKING_UNCERTAIN");
        assertThat(booking.resolve(quote.id().toString(), yes, null).order().id()).isEqualTo(4821);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='CONFIRMED'")).isEqualTo(1);
    }

    @Test
    void obsoleteReadbackCannotBookAndSameTurnCanResolveSavedOrder() {
        var old = quote(1, "kaju-katli", 500, "18:30");
        var corrected = quote(1, "kaju-katli", 250, "18:30");
        assertThat(accept(old).code()).isEqualTo("STALE_ACCEPTANCE");
        assertThat(amount("A")).isEqualTo(500);
        var booked = accept(corrected).order();
        assertThat(booked.acceptedQuoteId()).isEqualTo(corrected.id());
        assertThat(booked.acceptedTurnId()).isEqualTo("yes");
        assertThat(booking.resolve(corrected.id().toString(), new AcceptRequest(corrected.sessionId(), "yes"), null).order()).isEqualTo(booked);
        assertThat(amount("A")).isEqualTo(250);
    }

    @Test
    void c11ConcurrentPackingCreatesOneReadyNotice() throws Exception {
        var order = accept(quote(1, "kaju-katli", 500, "18:30")).order();
        var outcomes = race(2, i -> shop.action(order.id(), "pack", null));
        assertThat(outcomes).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='READY'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_events WHERE kind='PACKED'")).isEqualTo(1);
    }

    @Test
    void f08BoundsAndApprovalActionsUseOneCoherentProfile() {
        assertThat(quote(1, "barfi", 3000, "20:00").requiresApproval()).isFalse();
        assertThat(quote(2, "barfi", 4000, "20:00").requiresApproval()).isFalse();
        assertThat(quote(3, "barfi", 5000, "20:00").requiresApproval()).isTrue();
        assertThat(makeQuote(4, "barfi", 5001, "20:00").code()).isEqualTo("TOO_LARGE");
        rush();
        assertThat(quote(1, "barfi", 3000, "20:00").requiresApproval()).isFalse();
        assertThat(quote(2, "barfi", 4000, "20:00").requiresApproval()).isTrue();
        assertThat(quote(3, "barfi", 5000, "20:00").requiresApproval()).isTrue();
        assertThat(makeQuote(4, "barfi", 5001, "20:00").code()).isEqualTo("TOO_LARGE");
        var approved = accept(quote(2, "barfi", 4000, "20:00")).order();
        assertThat(shop.action(approved.id(), "approve", null).order().status()).isEqualTo("CONFIRMED");
        var rejected = accept(quote(3, "barfi", 4000, "20:00")).order();
        assertThat(shop.action(rejected.id(), "reject", null).order().status()).isEqualTo("REJECTED");
        assertThat(shop.action(rejected.id(), "reject", null).status()).isEqualTo(409);
        assertThat(amount("G")).isEqualTo(4000);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind IN ('APPROVED','REJECTED')")).isEqualTo(2);
    }

    @Test
    void f09F22PlannedTrayMustMeetPackBufferAndStaffReadyGate() {
        var first = accept(quote(1, "kaju-katli", 500, "18:30"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(quote(2, "kaju-katli", 500, "18:30").pickupTime()).isEqualTo("19:00");
        rush();
        var q = quote(2, "kaju-katli", 500, "18:30");
        assertThat(q.pickupTime()).isEqualTo("19:00");
        var next = accept(q).order();
        assertThat(next.batchId()).isEqualTo("B");
        assertThat(shop.action(next.id(), "pack", null).reason()).isEqualTo("BATCH_NOT_READY");
        assertThat(shop.batchAction("B", "ready", null).status()).isEqualTo(409);
        shop.changeClock("18:45");
        assertThat(shop.batchAction("B", "ready", null).status()).isEqualTo(200);
        assertThat(shop.action(next.id(), "pack", null).status()).isEqualTo(200);
        shop.reset(null);
        accept(quote(1, "kaju-katli", 500, "18:30"));
        q = quote(2, "kaju-katli", 500, "19:00");
        db.update("UPDATE batches SET ready_at=ready_at + INTERVAL '5 minutes' WHERE id='B'");
        assertThat(accept(q).reason()).isEqualTo("OUT_OF_STOCK");
        assertThat(amount("B")).isEqualTo(1000);
        shop.reset(null);
        shop.changeClock("18:00");
        var exactBoundary = quote(3, "kaju-katli", 500, "18:30");
        assertThat(exactBoundary.pickupTime()).isEqualTo("18:30");
        assertThat(accept(exactBoundary).status()).isEqualTo(201);
    }

    @Test
    void f11FlagPolicyRestoresLastSuccessfulFetchAndDefaultsRushOnColdOutage() throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = provider.getAddress().getPort();
        provider.createContext("/flags", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Environment-Key")).isEqualTo("fake-flagsmith-key");
            byte[] response = "[{\"feature\":{\"name\":\"rush_mode\"},\"enabled\":false}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        provider.start();
        URI url = URI.create("http://127.0.0.1:" + port + "/flags");
        PolicyService primary = new PolicyService(json, db, "fake-flagsmith-key", url, 50);
        try {
            primary.refresh();
            assertThat(primary.current().rushMode()).isFalse();
        } finally {
            provider.stop(0);
        }
        assertThat(Duration.between(primary.current().fetchedAt(), Instant.now())).isLessThan(Duration.ofSeconds(3));
        Instant staleFetch = Instant.now().minus(Duration.ofMinutes(6));
        db.update("UPDATE flagsmith_policy_cache SET fetched_at=? WHERE fetched_at=(SELECT max(fetched_at) FROM flagsmith_policy_cache)",
                staleFetch.atOffset(java.time.ZoneOffset.UTC));
        PolicyService restarted = new PolicyService(json, db, "fake-flagsmith-key", url, 60_000);
        restarted.restoreOnStartup();
        restarted.refresh();
        Policy restored = restarted.current();
        assertThat(restored.source()).isEqualTo("CACHED");
        assertThat(Duration.between(restored.fetchedAt(), Instant.now()).toMinutes()).isEqualTo(6);
        assertThat(Duration.between(restored.fetchedAt(), staleFetch).abs()).isLessThan(Duration.ofMillis(1));
        assertThat(restarted.current().fetchedAt()).isEqualTo(restored.fetchedAt());
        assertThat(restored.rushMode()).isFalse();
        PolicyService cold = new PolicyService(json, db, "different-fake-key", url, 50);
        cold.refresh();
        assertThat(cold.current().source()).isEqualTo("FALLBACK");
        assertThat(cold.current().rushMode()).isTrue();
    }

    @Test
    void f16F24SeparateQuoteExpiryFromLeadTime() {
        var old = quote(1, "kaju-katli", 500, "18:30");
        shop.changeClock("17:48:10");
        var result = accept(old);
        assertThat(result.status()).isEqualTo(410);
        assertThat(result.quote()).isNotNull();
        assertThat(amount("A")).isEqualTo(500);
        shop.changeClock("16:14");
        db.update("UPDATE batches SET ready_at=TIMESTAMPTZ '2031-02-09 16:15+05:30' WHERE id='A'");
        db.update("INSERT INTO pickup_slots (pickup_at,capacity) VALUES (TIMESTAMPTZ '2031-02-09 16:30+05:30',1)");
        var q = quote(2, "kaju-katli", 500, "16:30");
        assertThat(q.expiresAt()).isEqualTo(Instant.parse("2031-02-09T10:47:00Z"));
        shop.changeClock("16:16");
        result = accept(q);
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("CONFLICT");
        assertThat(result.reason()).isEqualTo("TOO_SOON");
        assertThat(result.quote().pickupTime()).isEqualTo("18:00");
        assertThat(amount("A")).isEqualTo(500);
        assertThat(used("16:30")).isZero();
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isZero();
    }

    @Test
    void f19F20TimeAdvancesAndApprovalAndExpiryReleaseOnce() throws Exception {
        rush();
        var before = accept(quote(1, "barfi", 4000, "20:00")).order();
        shop.changeClock("17:59:59.999");
        race(2, i -> { if (i == 0) shop.expireApprovals(); else shop.action(before.id(), "approve", null); return i; });
        assertThat(shop.orders().getFirst().status()).isEqualTo("CONFIRMED");
        shop.reset(null);
        var after = accept(quote(1, "barfi", 4000, "20:00")).order();
        shop.changeClock("18:00:00.001");
        var results = race(2, i -> {
            if (i == 1) return shop.action(after.id(), "approve", null);
            shop.expireApprovals();
            return Outcome.error(200, "SWEEP", null);
        });
        assertThat(results.get(1).reason()).isEqualTo("APPROVAL_EXPIRED");
        shop.expireApprovals();
        assertThat(shop.orders().getFirst().status()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(amount("G")).isEqualTo(8000);
        assertThat(used("20:00")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='APPROVAL_EXPIRED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='AWAITING_APPROVAL' AND status='SUPPRESSED'")).isEqualTo(1);
        shop.reset(null);
        var noSweep = accept(quote(1, "barfi", 4000, "20:00")).order();
        shop.changeClock("18:01");
        assertThat(shop.orders().getFirst().status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(shop.action(noSweep.id(), "approve", null).reason()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(shop.orders().getFirst().releasedAt()).isNotNull();
        assertThat(amount("G")).isEqualTo(8000);
        assertThat(used("20:00")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='APPROVAL_EXPIRED'")).isEqualTo(1);
    }

    @Test
    void advancingDemoAndWallClocksExpireApprovalHolds() throws InterruptedException {
        rush();
        var order = accept(quote(1, "barfi", 4000, "20:00")).order();
        shop.changeClock("17:59:59.950");
        db.update("UPDATE shop_state SET demo_started_at=statement_timestamp() WHERE id=1");
        Thread.sleep(Duration.ofMillis(80));
        shop.expireApprovals();
        assertThat(shop.orders().getFirst().status()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(shop.orders().getFirst().releasedAt()).isAfter(order.approvalExpiresAt());
        shop.reset(null);
        order = accept(quote(1, "barfi", 4000, "20:00")).order();
        db.update("UPDATE shop_state SET demo_now=NULL,demo_started_at=NULL WHERE id=1");
        db.update("UPDATE orders SET approval_expires_at=statement_timestamp() + INTERVAL '50 milliseconds' WHERE id=?", order.id());
        Thread.sleep(Duration.ofMillis(80));
        shop.expireApprovals();
        assertThat(shop.orders().getFirst().status()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(shop.orders().getFirst().releasedAt()).isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void f21SamePhoneCanHoldAtMostOnePendingApproval() throws Exception {
        rush();
        List<Quote> candidates = List.of(quote(1, "barfi", 4000, "20:00"),
                booking.quote(new QuoteRequest("same-phone", "Buyer", phone(1), "barfi", 4000, "20:30")).quote());
        var results = race(2, i -> accept(candidates.get(i)));
        assertThat(results).extracting(Outcome::code).containsExactlyInAnyOrder("AWAITING_APPROVAL", "PENDING_APPROVAL_LIMIT");
        assertThat(amount("G")).isEqualTo(4000);
        assertThat(used("20:00") + used("20:30")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE status='AWAITING_APPROVAL'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='AWAITING_APPROVAL'")).isEqualTo(1);
        var next = booking.quote(new QuoteRequest("same-phone-later", "Buyer", phone(1), "barfi", 4000, "20:30")).quote();
        assertThat(accept(next).code()).isEqualTo("PENDING_APPROVAL_LIMIT");
        assertThat(amount("G")).isEqualTo(4000);
        assertThat(used("20:00") + used("20:30")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isEqualTo(1);
    }

    @Test
    void f23PackedCancellationDoesNotReturnGramsUntilPhysicalReturn() {
        var order = accept(quote(1, "kaju-katli", 500, "18:30")).order();
        shop.action(order.id(), "pack", null);
        var cancelled = shop.action(order.id(), "cancel", null).order();
        assertThat(cancelled.returnToTray()).isTrue();
        assertThat(used("18:30")).isZero();
        assertThat(amount("A")).isZero();
        assertThat(quote(2, "kaju-katli", 500, "18:30").batchId()).isEqualTo("B");
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='READY' AND status='SUPPRESSED'")).isEqualTo(1);
        assertThat(shop.action(order.id(), "return", null).status()).isEqualTo(200);
        assertThat(shop.action(order.id(), "return", null).status()).isEqualTo(409);
        assertThat(amount("A")).isEqualTo(500);
        assertThat(quote(2, "kaju-katli", 500, "18:30").batchId()).isEqualTo("A");
    }

    @Test
    void c12F25F26ShortageBlocksBatchAndRecountRespectsRemainingHolds() {
        db.update("UPDATE batches SET phone_grams_free=1500, phone_grams_total=1500 WHERE id='A'");
        var first = accept(quote(1, "kaju-katli", 500, "18:30")).order();
        var other = accept(quote(2, "kaju-katli", 500, "19:30")).order();
        assertThat(shop.action(first.id(), "short", 300).order().status()).isEqualTo("SHORT");
        assertThat(shop.action(other.id(), "pack", null).reason()).isEqualTo("BATCH_BLOCKED");
        assertThat(shop.action(first.id(), "cancel", null).status()).isEqualTo(200);
        assertThat(shop.action(first.id(), "pack", null).status()).isEqualTo(409);
        assertThat(amount("A")).isEqualTo(500);
        assertThat(shop.batchAction("A", "unblock", null).status()).isEqualTo(400);
        assertThat(shop.batchAction("A", "unblock", 300).code()).isEqualTo("COUNT_BELOW_HOLDS");
        assertThat(amount("A")).isZero();
        assertThat(shop.orders().getLast().checkStock()).isTrue();
        assertThat(shop.batchAction("A", "unblock", 800).status()).isEqualTo(200);
        assertThat(amount("A")).isEqualTo(300);
        assertThat(shop.orders().getLast().checkStock()).isFalse();
    }

    @Test
    void f26PackingAndShortageRaceCannotEmitReadyAfterBlock() throws Exception {
        for (int repetition = 0; repetition < 12; repetition++) {
            shop.reset(null);
            db.update("UPDATE batches SET phone_grams_free=1500, phone_grams_total=1500 WHERE id='A'");
            var a = accept(quote(1, "kaju-katli", 500, "18:30")).order();
            var b = accept(quote(2, "kaju-katli", 500, "19:30")).order();
            var results = race(2, i -> i == 0 ? shop.action(a.id(), "short", 300) : shop.action(b.id(), "pack", null));
            assertThat(results.getFirst().status()).isEqualTo(200);
            if (results.getLast().status() == 200) {
                assertThat(db.queryForList("SELECT kind FROM order_events WHERE kind IN ('PACKED','SHORT') ORDER BY id", String.class))
                        .containsExactly("PACKED", "SHORT");
            } else {
                assertThat(results.getLast().reason()).isEqualTo("BATCH_BLOCKED");
                assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='READY'")).isZero();
            }
        }
    }

    @Test
    void l01RealPostgresRejectsOversellUnderTwoHundredBuyers() throws Exception {
        var quotes = stormQuotes();
        var lockTimer = metrics.get("rush.booking.batch.lock").timer();
        double lockBefore = lockTimer.totalTime(TimeUnit.MILLISECONDS);
        long countBefore = lockTimer.count();
        long start = System.nanoTime();
        var first = race(200, i -> {
            long started = System.nanoTime();
            return new TimedOutcome(accept(quotes.get(i)), System.nanoTime() - started);
        });
        long drained = System.nanoTime() - start;
        assertThat(first.stream().filter(result -> result.outcome().status() == 201).count()).isEqualTo(20);
        assertThat(first.stream().filter(result -> result.outcome().status() == 409).count()).isEqualTo(180);
        var samples = first.stream().mapToLong(TimedOutcome::elapsedNanos).sorted().toArray();
        System.out.printf("L01: 200 buyers, 20 won, 180 conflicted, batch lock mean %.2f ms incl. DB RTT, p50 %.2f ms, p95 %.2f ms, booking drain %.2f ms%n",
                (lockTimer.totalTime(TimeUnit.MILLISECONDS) - lockBefore) / (lockTimer.count() - countBefore),
                samples[99] / 1_000_000.0, samples[189] / 1_000_000.0, drained / 1_000_000.0);
        assertStormState();
    }

    @Test
    void l02SixHundredInterleavedRequestsStillCreateTwentyOrders() throws Exception {
        var quotes = stormQuotes();
        var attempts = race(600, i -> accept(quotes.get(i % 200)));
        assertThat(attempts.stream().filter(result -> result.status() == 201).count()).isEqualTo(20);
        assertThat(attempts.stream().filter(result -> result.status() == 200).count()).isEqualTo(40);
        assertThat(attempts.stream().filter(result -> result.status() == 409).count()).isEqualTo(540);
        assertStormState();
    }

    private List<Quote> stormQuotes() {
        db.update("UPDATE batches SET phone_grams_free=10000, phone_grams_total=10000 WHERE id='A'");
        db.update("UPDATE pickup_slots SET capacity=1000 WHERE (pickup_at AT TIME ZONE 'Asia/Kolkata')::time=TIME '18:30'");
        return IntStream.range(0, 200).mapToObj(i -> quote(i, "kaju-katli", 500, "18:30")).toList();
    }

    private void assertStormState() {
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isEqualTo(20);
        assertThat(amount("A")).isZero();
        assertThat(used("18:30")).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM batches WHERE phone_grams_free<0 OR phone_grams_free>phone_grams_total")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pickup_slots WHERE used<0 OR used>capacity")).isZero();
    }

    @Test
    void o06StaffOwnerOperatorAndBookingCredentialsAreSeparate() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/4821/approve")).andExpect(status().isUnauthorized());
        mvc.perform(post("/ops/demo/reset")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/4821/approve").header("X-Staff-Token", "test-role-staff")).andExpect(status().isForbidden());
        mvc.perform(post("/ops/demo/reset").header("X-Staff-Token", "test-role-owner")).andExpect(status().isForbidden());
        mvc.perform(post("/api/demo/reset").header("X-Staff-Token", "test-role-owner")).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").header("X-Booking-Token", "test-role-booking")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/4821/pack").header("X-Staff-Token", "test-role-operator")).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff/session").header("X-Staff-Token", "test-role-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(post("/ops/demo/reset").header("X-Staff-Token", "test-role-operator")).andExpect(status().isOk());
    }

    private void normal() { when(policy.current()).thenReturn(policy(false)); }
    private void rush() { when(policy.current()).thenReturn(policy(true)); }
    private Policy policy(boolean rush) {
        Instant now = Instant.parse("2031-02-09T12:15:00Z");
        return PolicyService.profile(rush, "LIVE", now, now);
    }
    private Outcome makeQuote(int buyer, String sku, int grams, String pickup) {
        return booking.quote(new QuoteRequest("buyer-" + buyer, "Buyer", phone(buyer), sku, grams, pickup));
    }
    private Quote quote(int buyer, String sku, int grams, String pickup) {
        var result = makeQuote(buyer, sku, grams, pickup);
        assertThat(result.status()).isEqualTo(200);
        return result.quote();
    }
    private Outcome accept(Quote quote) {
        return booking.accept(quote.id().toString(), new AcceptRequest(quote.sessionId(), "yes"), null);
    }
    private String phone(int buyer) { return String.format("+1202555%04d", 100 + buyer); }
    private int count(String query) { return db.queryForObject(query, Integer.class); }
    private int amount(String id) { return db.queryForObject("SELECT phone_grams_free FROM batches WHERE id=?", Integer.class, id); }
    private int used(String pickup) {
        return db.queryForObject("SELECT used FROM pickup_slots WHERE (pickup_at AT TIME ZONE 'Asia/Kolkata')::time=?::time", Integer.class, pickup);
    }
    private <T> List<T> race(int size, IntFunction<T> job) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, size).mapToObj(i -> pool.submit((Callable<T>) () -> { start.await(); return job.apply(i); })).toList();
            start.countDown();
            var results = new ArrayList<T>(size);
            for (var future : futures) results.add(future.get(35, TimeUnit.SECONDS));
            return results;
        }
    }
    private record TimedOutcome(Outcome outcome, long elapsedNanos) {}
}
