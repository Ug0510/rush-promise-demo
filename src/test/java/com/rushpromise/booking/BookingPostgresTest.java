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
import org.springframework.http.MediaType;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    @MockitoBean SlotPolicyProvider slotPolicies;

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
        assertThat(amount("B")).isEqualTo(1000);
        assertThat(count("SELECT SUM(used)::int FROM pickup_slots")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isZero();
        assertThat(count("SELECT COUNT(*) FROM booking_keys")).isZero();
        assertThat(count("SELECT COUNT(*) FROM booking_sessions")).isZero();
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
    void fs04LocksActualOccupancyAndPersistsChangedTermsBeforeFreshAssent() throws Exception {
        when(slotPolicies.capture()).thenReturn(slotBundle("rush-live-digest", true, "LIVE", 120, 15));
        assertThat(shop.setupPolicyFixture().code()).isEqualTo("POLICY_FIXTURE_READY");
        db.update("DELETE FROM orders WHERE id=-4");
        db.update("UPDATE batches SET phone_grams_total=3500 WHERE id='G-policy-seats'");
        db.update("UPDATE pickup_slots SET used=3 WHERE (pickup_at AT TIME ZONE 'Asia/Kolkata')::time=TIME '19:30'");
        var waiting = quote(1, "barfi", 2500, "19:30");
        assertThat(waiting.requiresApproval()).isFalse();
        assertThat(waiting.policy().slot().used()).isEqualTo(3);
        assertThat(waiting.policy().slot().profile()).isEqualTo("RUSH");
        var earlier = quote(2, "kaju-katli", 500, "19:30");
        var earlierChangedSlot = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var competitor = threads.submit(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
                var result = accept(earlier);
                earlierChangedSlot.countDown();
                try {
                    if (!allowCommit.await(4, TimeUnit.SECONDS)) throw new AssertionError("Competitor not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return result;
            }));
            try {
                assertThat(earlierChangedSlot.await(3, TimeUnit.SECONDS)).isTrue();
                var assent = threads.submit(() -> accept(waiting));
                boolean observedDatabaseWait = false;
                Instant expires = Instant.now().plusSeconds(2);
                while (Instant.now().isBefore(expires) && !observedDatabaseWait) {
                    observedDatabaseWait = count("""
                            SELECT COUNT(*) FROM pg_stat_activity
                            WHERE datname=current_database() AND pid<>pg_backend_pid()
                              AND query LIKE 'SELECT used,capacity FROM pickup_slots%FOR UPDATE%'
                              AND cardinality(pg_blocking_pids(pid))>0
                            """) > 0;
                    if (!observedDatabaseWait) Thread.sleep(10);
                }
                assertThat(observedDatabaseWait).isTrue();
                allowCommit.countDown();
                assertThat(competitor.get(3, TimeUnit.SECONDS).status()).isEqualTo(201);
                var change = assent.get(3, TimeUnit.SECONDS);
                assertThat(change.code()).isEqualTo("TERMS_CHANGED");
                assertThat(change.quote().policy().slot().used()).isEqualTo(4);
                assertThat(change.quote().policy().slot().profile()).isEqualTo("BUSY_SLOT");
                assertThat(change.quote().policy().slot().reasonCode()).isEqualTo("SLOT_NEAR_CAPACITY");
                assertThat(change.quote().policy().slot().why()).contains("80%", "2.5 kg", "2 kg");
                assertThat(change.quote().policy().slot().whyHindi()).contains("80 प्रतिशत", "2.5 किलो", "2 किलो");
                assertThat(accept(waiting)).isEqualTo(change);
                assertThat(used("19:30")).isEqualTo(4);
                assertThat(amount("G")).isEqualTo(6500);
                assertThat(shop.orders()).hasSize(1);
                var held = accept(change.quote()).order();
                assertThat(held.status()).isEqualTo("AWAITING_APPROVAL");
                assertThat(held.policy().slot()).isEqualTo(change.quote().policy().slot());
                assertThat(used("19:30")).isEqualTo(5);
            } finally {
                allowCommit.countDown();
            }
        }
    }

    @Test
    void fs07TwoSavedSlotsShareOneDigestWithDifferentApprovalAndKeepEarlierOrder() throws Exception {
        when(slotPolicies.capture()).thenReturn(slotBundle("normal-revision", false, "LIVE", 180, 15));
        var earlier = accept(quote(1, "kaju-katli", 500, "18:30")).order();
        mvc.perform(post("/ops/demo/policy-fixture").header("X-Staff-Token", "test-role-operator"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("POLICY_FIXTURE_READY"));
        assertThat(shop.setupPolicyFixture().code()).isEqualTo("POLICY_FIXTURE_PRESENT");
        assertThat(count("""
                SELECT COUNT(*) FROM demo_policy_seats s JOIN orders o ON o.id=s.order_id
                JOIN quotes q ON q.id=o.accepted_quote_id WHERE o.status='CONFIRMED' AND o.grams=s.grams
                    AND o.pickup_at=s.pickup_at AND q.batch_id=s.batch_id AND o.batch_id=s.batch_id
                """)).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE order_id<0")).isZero();
        assertThat(amount("G-policy-seats")).isZero();
        assertThat(amount("G")).isEqualTo(6500);
        var oldBarfi = quote(2, "barfi", 4000, "20:00");
        assertThat(oldBarfi.requiresApproval()).isFalse();
        when(slotPolicies.capture()).thenReturn(slotBundle("same-rush-revision", true, "LIVE", 120, 15));
        mvc.perform(get("/api/policy").header("X-Staff-Token", "test-role-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.digest").value("same-rush-revision"))
                .andExpect(jsonPath("$.slot").doesNotExist());
        var changed = accept(oldBarfi);
        assertThat(changed.code()).isEqualTo("TERMS_CHANGED");
        assertThat(changed.quote().policy().slot().reasonCode()).isEqualTo("RUSH_HOUR");
        assertThat(changed.quote().policy().slot().why()).contains("Rush mode", "3 kg");
        assertThat(accept(changed.quote()).order().status()).isEqualTo("AWAITING_APPROVAL");
        var busy = quote(3, "barfi", 2500, "19:30");
        var lighter = quote(3, "barfi", 2500, "20:00");
        assertThat(busy.pricePaise()).isEqualTo(225000);
        assertThat(busy.requiresApproval()).isTrue();
        assertThat(lighter.requiresApproval()).isFalse();
        assertThat(busy.policy().slot().digest()).isEqualTo(lighter.policy().slot().digest());
        assertThat(busy.policy().slot().used()).isEqualTo(4);
        assertThat(busy.policy().slot().capacity()).isEqualTo(5);
        assertThat(lighter.policy().slot().used()).isEqualTo(5);
        assertThat(lighter.policy().slot().capacity()).isEqualTo(10);
        assertThat(busy.policy().slot().terms().approvalRequired()).isTrue();
        assertThat(lighter.policy().slot().terms().approvalRequired()).isFalse();
        assertThat(store.write(busy.policy())).doesNotContain("customerName", "phone", "sessionId", "transcript");
        int quoteCount = count("SELECT COUNT(*) FROM quotes");
        mvc.perform(get("/api/policy/pickup-options").param("sku", "barfi").param("grams", "2500")
                        .header("X-Staff-Token", "test-role-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.options[0].requiresApproval").value(true))
                .andExpect(jsonPath("$.options[1].requiresApproval").value(false));
        mvc.perform(get("/api/policy/saved-comparisons").header("X-Staff-Token", "test-role-staff")).andExpect(status().isForbidden());
        mvc.perform(get("/api/policy/saved-comparisons").header("X-Staff-Token", "test-role-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].options[0].policy.slot.why").value(busy.policy().slot().why()))
                .andExpect(jsonPath("$[0].options[1].policy.slot.why").value(lighter.policy().slot().why()));
        assertThat(count("SELECT COUNT(*) FROM quotes")).isEqualTo(quoteCount);
        var finalOrder = accept(lighter).order();
        assertThat(finalOrder.status()).isEqualTo("CONFIRMED");
        assertThat(finalOrder.approvalExpiresAt()).isNull();
        assertThat(used("19:30")).isEqualTo(4);
        assertThat(used("20:00")).isEqualTo(6);
        assertThat(amount("G")).isZero();
        assertThat(shop.orders().getFirst()).isEqualTo(earlier);
    }

    @Test
    void fs04MaterialTermsIgnoreDigestAndTtlAndHoldCannotOutlastPickupCutoff() {
        when(slotPolicies.capture()).thenReturn(slotBundle("old", true, "LIVE", 120, 15));
        var previous = quote(1, "barfi", 2500, "20:00");
        when(slotPolicies.capture()).thenReturn(slotBundle("metadata-only", true, "LIVE", 120, 15));
        var booked = accept(previous).order();
        assertThat(booked.status()).isEqualTo("CONFIRMED");
        assertThat(booked.policy().slot().digest()).isEqualTo("metadata-only");
        var oldTtl = quote(2, "barfi", 2500, "20:00");
        when(slotPolicies.capture()).thenReturn(slotBundle("new-ttl", true, "LIVE", 360, 15));
        assertThat(accept(oldTtl).order().status()).isEqualTo("CONFIRMED");
        shop.reset(null);
        when(slotPolicies.capture()).thenReturn(slotBundle("cached-long-hold", true, "CACHED", 120, 60));
        var full = makeQuote(3, "barfi", 6000, "18:15");
        assertThat(full.code()).isEqualTo("TOO_LARGE");
        var held = accept(quote(3, "barfi", 4000, "18:15")).order();
        assertThat(held.approvalExpiresAt()).isEqualTo(held.pickupAt().minusSeconds(15 * 60));
        assertThat(held.policy().slot().why()).contains("last accepted", "was on");
    }

    @Test
    void fsF08BoundaryProfilesUseGramCapsAndBackedSlotAvailability() {
        when(slotPolicies.capture()).thenReturn(slotBundle("standard", false, "LIVE", 180, 15));
        assertThat(shop.setupPolicyFixture().status()).isEqualTo(200);
        assertApprovalBoundaries(List.of(3000, 4000), List.of(5000));
        when(slotPolicies.capture()).thenReturn(slotBundle("rush", true, "LIVE", 120, 15));
        assertApprovalBoundaries(List.of(2500, 3000), List.of(4000, 5000));
        db.update("UPDATE pickup_slots SET capacity=5 WHERE (pickup_at AT TIME ZONE 'Asia/Kolkata')::time=TIME '20:00'");
        assertApprovalBoundaries(List.of(2000), List.of(2500, 3000));
        when(slotPolicies.capture()).thenReturn(slotBundle("local-fallback", false, "FALLBACK", 120, 10));
        assertApprovalBoundaries(List.of(2000), List.of(2500));
        var fallback = quote(4, "barfi", 2500, "20:00");
        assertThat(fallback.policy().source()).isEqualTo("FALLBACK");
        assertThat(fallback.policy().slot().reasonCode()).isEqualTo("FALLBACK_POLICY");
        assertThat(fallback.policy().slot().why()).contains("Backup pickup rules");
        assertThat(fallback.policy().approvalHoldMinutes()).isEqualTo(10);

        var hold = accept(fallback).order();
        assertThat(hold.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(shop.action(hold.id(), "approve", null).order().status()).isEqualTo("CONFIRMED");
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='APPROVED'")).isEqualTo(1);
        var later = accept(quote(5, "barfi", 2500, "19:30")).order();
        assertThat(shop.action(later.id(), "reject", null).order().status()).isEqualTo("REJECTED");
        assertThat(shop.action(later.id(), "reject", null).status()).isEqualTo(409);
        assertThat(used("19:30")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='REJECTED'")).isEqualTo(1);
    }

    private void assertApprovalBoundaries(List<Integer> confirmable, List<Integer> approvalNeeded) {
        for (int quantity : confirmable) assertThat(quote(1, "barfi", quantity, "20:00").requiresApproval()).isFalse();
        for (int quantity : approvalNeeded) assertThat(quote(1, "barfi", quantity, "20:00").requiresApproval()).isTrue();
        assertThat(makeQuote(1, "barfi", 6000, "20:00").code()).isEqualTo("TOO_LARGE");
    }

    private SlotPolicyProvider.Snapshot slotBundle(String digest, boolean rush, String source, int ttl, int hold) {
        return (grams, used, capacity) -> {
            boolean busy = 100L * used / capacity >= 80;
            boolean backup = "FALLBACK".equals(source);
            String profile = backup ? "FALLBACK" : busy ? "BUSY_SLOT" : rush ? "RUSH" : "NORMAL";
            String reason = backup ? "FALLBACK_POLICY" : busy ? "SLOT_NEAR_CAPACITY" : rush ? "RUSH_HOUR" : "STANDARD";
            int threshold = backup || busy ? 2000 : rush ? 3000 : 4000;
            return new Policy(digest, source, Instant.EPOCH, Instant.EPOCH, rush, 5000, threshold, busy || backup ? 10 : hold,
                    rush || busy || backup ? 30 : 15, ttl, rush || busy || backup ? 20 : 30)
                    .withSlot(SlotPolicyProvider.audit(digest, profile, reason, grams, threshold, used, capacity));
        };
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
    void c05SequentialLastSlotConflictKeepsTheWaitingBuyersStockFree() {
        db.update("UPDATE batches SET phone_grams_free=1000, phone_grams_total=1000 WHERE id='A'");
        var lata = quote(1, "kaju-katli", 500, "18:30");
        var earlier = accept(quote(2, "barfi", 1000, "18:30"));
        assertThat(earlier.status()).isEqualTo(201);
        var later = accept(lata);
        assertThat(later.status()).isEqualTo(409);
        assertThat(later.reason()).isEqualTo("SLOT_FULL");
        assertThat(later.quote().pickupTime()).isEqualTo("19:00");
        assertThat(amount("A")).isEqualTo(1000);
        assertThat(used("18:30")).isEqualTo(1);
        assertThat(shop.orders()).containsExactly(earlier.order());
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isEqualTo(1);
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
    void hostedRevisionPreventsAStaleOffReadFromAcceptingOldBarfiTerms() throws Exception {
        var officialEndpoint = URI.create("https://api.flagsmith.com/api/v1/flags/");
        assertThat(PolicyService.versionedEndpoint(URI.create("https://edge.api.flagsmith.com/api/v1/flags/"))).isEqualTo(officialEndpoint);
        assertThat(PolicyService.versionedEndpoint(URI.create("https://api.flagsmith.com/api/v1/flags/"))).isEqualTo(officialEndpoint);
        Instant originalRevision = Instant.parse("2031-02-09T08:00:00.000001Z");
        Instant rushRevision = originalRevision.plusSeconds(10);
        Instant refreshedRushRevision = rushRevision.plusSeconds(5);
        Instant intentionalOff = refreshedRushRevision.plusSeconds(5);
        var upstream = new AtomicReference<>(new FlagFixture(false, originalRevision));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/flags/", exchange -> {
            FlagFixture observed = upstream.get();
            byte[] response = flagList(observed.enabled()).getBytes(StandardCharsets.UTF_8);
            String revision = java.math.BigDecimal.valueOf(observed.revision().getEpochSecond())
                    .add(java.math.BigDecimal.valueOf(observed.revision().getNano(), 9)).toPlainString();
            exchange.getResponseHeaders().add("X-Flagsmith-Document-Updated-At", revision);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        URI localDocument = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/flags/");
        try {
            var primary = new PolicyService(json, db, "synthetic-revision-key", localDocument, 5000);
            primary.refresh();
            when(policy.current()).thenAnswer(ignored -> primary.current());
            assertThat(primary.current().providerUpdatedAt()).isEqualTo(originalRevision);
            var existing = accept(quote(1, "kaju-katli", 500, "18:30")).order();
            var quoted = quote(2, "barfi", 4000, "20:00");
            assertThat(quoted.requiresApproval()).isFalse();

            upstream.set(new FlagFixture(true, rushRevision));
            primary.refresh();
            var observedOn = primary.current();
            assertThat(observedOn.rushMode()).isTrue();
            assertThat(observedOn.approvalAboveGrams()).isEqualTo(3000);
            assertThat(observedOn.providerUpdatedAt()).isEqualTo(rushRevision);
            upstream.set(new FlagFixture(false, originalRevision));
            primary.refresh();
            assertThat(primary.current().rushMode()).isTrue();
            assertThat(primary.current().fetchedAt()).isEqualTo(observedOn.fetchedAt());
            upstream.set(new FlagFixture(false, rushRevision));
            primary.refresh();
            assertThat(primary.current().fetchedAt()).isEqualTo(observedOn.fetchedAt());

            var restarted = new PolicyService(json, db, "synthetic-revision-key", localDocument, 5000);
            restarted.restoreOnStartup();
            restarted.refresh();
            assertThat(restarted.current().providerUpdatedAt()).isEqualTo(rushRevision);
            assertThat(restarted.current().rushMode()).isTrue();
            when(policy.current()).thenAnswer(ignored -> restarted.current());
            var refused = accept(quoted);
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.code()).isEqualTo("TERMS_CHANGED");
            assertThat(refused.quote().requiresApproval()).isTrue();
            assertThat(shop.orders()).containsExactly(existing);
            assertThat(accept(quoted)).isEqualTo(refused);

            upstream.set(new FlagFixture(true, refreshedRushRevision));
            restarted.refresh();
            var pending = accept(refused.quote()).order();
            assertThat(pending.status()).isEqualTo("AWAITING_APPROVAL");
            assertThat(pending.policy().providerUpdatedAt()).isEqualTo(refreshedRushRevision);
            assertThat(shop.orders().getFirst()).isEqualTo(existing);
            upstream.set(new FlagFixture(false, intentionalOff));
            restarted.refresh();
            assertThat(restarted.current().rushMode()).isFalse();
            assertThat(restarted.current().providerUpdatedAt()).isEqualTo(intentionalOff);
            Instant clearedFetch = restarted.current().fetchedAt();
            upstream.set(new FlagFixture(true, refreshedRushRevision));
            restarted.refresh();
            assertThat(restarted.current().rushMode()).isFalse();
            assertThat(restarted.current().fetchedAt()).isEqualTo(clearedFetch);
        } finally {
            server.stop(0);
        }
    }

    private static String flagList(boolean enabled) {
        return """
                [{"feature":{"name":"rush_mode"},"enabled":%s,"feature_segment":{"id":1}},
                 {"feature":{"name":"rush_mode"},"enabled":%s,"feature_segment":null}]
                """.formatted(!enabled, enabled);
    }

    private record FlagFixture(boolean enabled, Instant revision) {}

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
        assertThat(count("SELECT COUNT(*) FROM batches WHERE id='A' AND blocked")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE order_id=4821 AND kind='SHORT'")).isEqualTo(1);
        assertThat(shop.action(first.id(), "pack", null).status()).isEqualTo(409);
        assertThat(shop.action(other.id(), "pack", null).reason()).isEqualTo("BATCH_BLOCKED");
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='READY'")).isZero();
        assertThat(shop.action(first.id(), "cancel", null).status()).isEqualTo(200);
        assertThat(shop.action(first.id(), "pack", null).status()).isEqualTo(409);
        assertThat(shop.action(first.id(), "cancel", null).status()).isEqualTo(409);
        assertThat(used("18:30")).isZero();
        assertThat(used("19:30")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE released_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE order_id=4821 AND kind='CANCELLED'")).isEqualTo(1);
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
        String resend = "/api/messages/" + UUID.randomUUID() + "/resend";
        mvc.perform(post(resend)).andExpect(status().isUnauthorized());
        mvc.perform(post(resend).header("X-Staff-Token", "test-role-staff")).andExpect(status().isForbidden());
        mvc.perform(post(resend).header("X-Staff-Token", "test-role-operator")).andExpect(status().isForbidden());
        mvc.perform(post(resend).header("X-Staff-Token", "test-role-owner"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTICE_UNAVAILABLE"));
        mvc.perform(get("/api/staff/session").header("X-Staff-Token", "test-role-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(post("/ops/demo/reset").header("X-Staff-Token", "test-role-operator")).andExpect(status().isOk());
    }

    @Test
    void c10ProtectedSnapshotRefetchIncludesExactlyOneNewOrderAndCurrentDelivery() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/staff/snapshot")).andExpect(status().isUnauthorized());
        var staffView = "/api/staff/snapshot";
        mvc.perform(get(staffView).header("X-Staff-Token", "test-role-staff"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(0));
        var order = accept(quote(1, "kaju-katli", 500, "18:30")).order();
        assertThat(shop.action(order.id(), "pack", null).status()).isEqualTo(200);
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(staffView).header("X-Staff-Token", "test-role-staff"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(1))
                    .andExpect(jsonPath("$.orders[0].id").value(4821))
                    .andExpect(jsonPath("$.orders[0].status").value("PACKED"))
                    .andExpect(jsonPath("$.stock.batches[0].phoneGramsFree").value(0))
                    .andExpect(jsonPath("$.deliveries['4821'].length()").value(2))
                    .andExpect(jsonPath("$.deliveries['4821'][1].kind").value("READY"))
                    .andExpect(jsonPath("$.deliveries['4821'][1].status").value("QUEUED"));
        }
    }

    @Test
    void nextPickupProjectionSharesQuoteEligibilityAndWritesNothing() throws Exception {
        var route = "/api/availability/next?sku=kaju-katli&grams=500";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).header("X-Staff-Token", "test-role-staff"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.option.pickupTime").value("18:00"))
                .andExpect(jsonPath("$.option.batchId").value("A"))
                .andExpect(jsonPath("$.option.batchFreeGrams").value(500));
        rush();
        Availability tooSoon = booking.nextAvailability("kaju-katli", 500, "18:00");
        assertThat(tooSoon.reason()).isEqualTo("TOO_SOON");
        assertThat(tooSoon.option().pickupTime()).isEqualTo("18:15");
        db.update("UPDATE pickup_slots SET used=capacity WHERE (pickup_at AT TIME ZONE 'Asia/Kolkata')::time=TIME '18:30'");
        Availability lastSlot = booking.nextAvailability("kaju-katli", 500, "18:30");
        assertThat(lastSlot.reason()).isEqualTo("SLOT_FULL");
        assertThat(lastSlot.option().pickupTime()).isEqualTo("19:00");
        db.update("UPDATE batches SET blocked=TRUE WHERE id='A'");
        Availability fallback = booking.nextAvailability("kaju-katli", 500, "18:30");
        assertThat(fallback.reason()).isEqualTo("OUT_OF_STOCK");
        assertThat(fallback.option().batchId()).isEqualTo("B");
        assertThat(fallback.option().batchReadyTime()).isEqualTo("18:45");
        assertThat(fallback.option().batchFreeGrams()).isEqualTo(1000);
        assertThat(fallback.option().batchStatus()).isEqualTo("PLANNED");
        db.update("UPDATE batches SET ready_at=ready_at+INTERVAL '5 minutes' WHERE id='B'");
        assertThat(booking.nextAvailability("kaju-katli", 500, "18:30").option().pickupTime()).isEqualTo("19:30");
        db.update("UPDATE batches SET blocked=TRUE WHERE id='B'");
        var unavailable = booking.nextAvailability("kaju-katli", 500, "18:30");
        assertThat(unavailable.option()).isNull();
        assertThat(unavailable.reason()).isEqualTo("NONE_AVAILABLE");
        assertThat(count("SELECT COUNT(*) FROM quotes")).isZero();
        assertThat(count("SELECT COUNT(*) FROM booking_sessions")).isZero();
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isZero();
    }

    @Test
    void shopProfileIsOwnerOnlyAndPersistsAcrossDemoResetAndNewMessages() throws Exception {
        String previous = shop.profile().displayName();
        String route = "/api/shop/profile";
        String change = "{\"displayName\":\"  अनन्या मिठाई  \"}";
        try {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(change)).andExpect(status().isUnauthorized());
            mvc.perform(post(route).header("X-Staff-Token", "test-role-staff").contentType(MediaType.APPLICATION_JSON).content(change))
                    .andExpect(status().isForbidden());
            mvc.perform(post(route).header("X-Staff-Token", "test-role-operator").contentType(MediaType.APPLICATION_JSON).content(change))
                    .andExpect(status().isForbidden());
            mvc.perform(post(route).header("X-Staff-Token", "test-role-owner").contentType(MediaType.APPLICATION_JSON).content(change))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("अनन्या मिठाई"));
            mvc.perform(post(route).header("X-Staff-Token", "test-role-owner").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"  \"}"))
                    .andExpect(status().isBadRequest());
            assertThat(shop.reset(null).status()).isEqualTo(200);
            assertThat(shop.profile().displayName()).isEqualTo("अनन्या मिठाई");
            assertThat(booking.catalog().displayName()).isEqualTo("अनन्या मिठाई");
            assertThat(accept(quote(1, "kaju-katli", 500, "18:30")).status()).isEqualTo(201);
            assertThat(db.queryForObject("SELECT body FROM outbox_messages WHERE kind='CONFIRMED'", String.class))
                    .startsWith("अनन्या मिठाई #4821");
            mvc.perform(get("/api/staff/snapshot").header("X-Staff-Token", "test-role-staff"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.shop.displayName").value("अनन्या मिठाई"));
        } finally {
            shop.updateProfile(previous);
        }
    }

    @Test
    void catalogQuotesNoContactAndReportsRealPortionsAndWeighedTrayWindowsWithoutWrites() throws Exception {
        mvc.perform(get("/api/shop/menu")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shop/menu").header("X-Staff-Token", "test-role-staff"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        var initial = booking.catalog();
        var kaju = initial.items().stream().filter(item -> item.sku().equals("kaju-katli")).findFirst().orElseThrow();
        var barfi = initial.items().stream().filter(item -> item.sku().equals("barfi")).findFirst().orElseThrow();
        assertThat(kaju.pricePerKgPaise()).isEqualTo(120_000);
        assertThat(kaju.portions()).containsExactly(new MenuPortion(250, 30_000, "18:00", "19:00"), new MenuPortion(500, 60_000, "18:00", "19:00"));
        assertThat(barfi.pricePerKgPaise()).isEqualTo(90_000);
        assertThat(barfi.portions()).containsExactly(new MenuPortion(250, 22_500, "18:00", null), new MenuPortion(500, 45_000, "18:00", null));
        db.update("UPDATE batches SET blocked=TRUE WHERE id='A'");
        var blocked = booking.catalog().items().stream().filter(item -> item.sku().equals("kaju-katli")).findFirst().orElseThrow();
        assertThat(blocked.portions()).containsExactly(new MenuPortion(250, 30_000, null, "19:00"), new MenuPortion(500, 60_000, null, "19:00"));
        db.update("UPDATE batches SET phone_grams_free=0 WHERE id='B'");
        var exhausted = booking.catalog().items().stream().filter(item -> item.sku().equals("kaju-katli")).findFirst().orElseThrow();
        assertThat(exhausted.portions()).containsExactly(new MenuPortion(250, 30_000, null, null), new MenuPortion(500, 60_000, null, null));
        assertThat(json.writeValueAsString(initial)).doesNotContain("counter", "batchId", "phone", "customerName", "runId", "quoteId");
        assertThat(count("SELECT COUNT(*) FROM quotes")).isZero();
        assertThat(count("SELECT COUNT(*) FROM booking_sessions")).isZero();
        assertThat(count("SELECT COUNT(*) FROM voice_sessions")).isZero();
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isZero();
        assertThat(used("19:00")).isZero();
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
