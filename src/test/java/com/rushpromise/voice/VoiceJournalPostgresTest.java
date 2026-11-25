package com.rushpromise.voice;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.voice.VoiceSessionService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.booking.BookingService;
import com.rushpromise.booking.PolicyService;
import com.rushpromise.booking.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(properties = {
        "spring.datasource.url=${RUSH_TEST_JDBC_URL}",
        "spring.datasource.username=${RUSH_TEST_JDBC_USER:shop_app}",
        "spring.datasource.password=${RUSH_TEST_JDBC_PASSWORD}",
        "rush.demo.date=2031-02-09",
        "rush.voice.recovery-ms=600000"
})
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "RUSH_TEST_JDBC_URL", matches = "jdbc:postgresql:.*/rush_test_[A-Za-z0-9_]+(?:\\?.*)?")
class VoiceJournalPostgresTest {
    private static final String PHONE = "+12025550101";
    private static final String SESSION = "browser:voice-fixture";
    private static final String LATA = "Lata";
    private static final String KAJU = "एक किलो काजू कतली, नहीं आधा किलो, साढ़े छह बजे।";

    @Autowired VoiceSessionService service;
    @Autowired VoiceTurnJournal journal;
    @Autowired BookingService booking;
    @Autowired ShopService shop;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate transactions;
    @MockitoBean PolicyService policy;

    @BeforeEach
    void reset() {
        flags(false);
        assertThat(shop.reset(null).status()).isEqualTo(200);
    }

    @Test
    void replaysTheCurrentPromptAfterTermsChangeAndRejectsLateYes() {
        var first = speak(1, null, "चार किलो बर्फी रात 8 बजे");
        assertThat(first.quote().requiresApproval()).isFalse();
        var silence = speak(2, first.quote().id(), "");
        assertThat(silence.quote().id()).isEqualTo(first.quote().id());
        flags(true);
        var replaced = speak(3, first.quote().id(), "हाँ");
        assertThat(replaced.quote().requiresApproval()).isTrue();
        assertThat(replaced.quote().id()).isNotEqualTo(first.quote().id());
        assertThat(restarted().poll(SESSION, "turn-1", PHONE).quote()).isEqualTo(replaced.quote());
        assertThat(speak(4, first.quote().id(), "हाँ").quote()).isEqualTo(replaced.quote());
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        var accepted = speak(5, replaced.quote().id(), "हाँ");
        assertThat(accepted.order().status()).isEqualTo("AWAITING_APPROVAL");
        var replay = restarted().turn(input(5, replaced.quote().id()), () -> { throw new AssertionError("Replayed turn contacted STT"); });
        assertThat(replay.order()).isEqualTo(accepted.order());
    }

    @Test
    void acceptedYesIsRecoveredWithoutAnyCallerRetryAfterProcessLoss() {
        var quote = speak(1, null, KAJU).quote();
        markAccepted(2, quote.id());
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        expireLease(2);
        restarted().recoverAcceptedTurns();
        var result = restarted().poll(SESSION, "turn-2", PHONE);
        assertThat(result.order().id()).isEqualTo(4821);
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='CONFIRMED'")).isEqualTo(1);
    }

    @Test
    void recoveryAfterRealBookingCommitReplaysSameNumberAndOutbox() {
        var quote = speak(1, null, KAJU).quote();
        markAccepted(2, quote.id());
        assertThat(booking.accept(quote.id().toString(), new AcceptRequest(SESSION, "turn-2"), null).status()).isEqualTo(201);
        expireLease(2);
        restarted().recoverAcceptedTurns();
        restarted().recoverAcceptedTurns();
        assertThat(restarted().poll(SESSION, "turn-2", PHONE).order().id()).isEqualTo(4821);
        assertThat(count("SELECT COUNT(*) FROM booking_keys WHERE status=201")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='CONFIRMED'")).isEqualTo(1);
    }

    @Test
    void uncertainAcceptRechecksItsKeyAndNeverAsksAgainIfTheOrderCommitted() {
        var quote = speak(1, null, KAJU).quote();
        var assent = new AcceptRequest(SESSION, "turn-2");
        var losingConnection = mock(BookingService.class, delegatesTo(booking));
        doAnswer(invocation -> {
            assertThat(booking.accept(quote.id().toString(), assent, null).status()).isEqualTo(201);
            return Outcome.error(503, "BOOKING_UNCERTAIN", null);
        }).when(losingConnection).accept(eq(quote.id().toString()), eq(assent), isNull());
        var waiting = new VoiceSessionService(losingConnection, journal)
                .turn(input(2, quote.id()), () -> spoken("हाँ"));
        assertThat(waiting.state()).isEqualTo("CHECKING_BOOKING");
        assertThat(restarted().poll(SESSION, "turn-2", PHONE).state()).isEqualTo("CHECKING_BOOKING");
        expireLease(2);
        restarted().recoverAcceptedTurns();
        assertThat(restarted().poll(SESSION, "turn-2", PHONE).order().id()).isEqualTo(4821);
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages")).isEqualTo(1);
    }

    @Test
    void recoveryAfterRealTermsDecisionReplaysNewQuoteInsteadOfOldAssent() {
        var quote = speak(1, null, "चार किलो बर्फी रात 8 बजे").quote();
        markAccepted(2, quote.id());
        flags(true);
        var durable = booking.accept(quote.id().toString(), new AcceptRequest(SESSION, "turn-2"), null);
        assertThat(durable.code()).isEqualTo("TERMS_CHANGED");
        expireLease(2);
        var resumed = restarted();
        resumed.recoverAcceptedTurns();
        assertThat(resumed.poll(SESSION, "turn-1", PHONE).quote().id()).isEqualTo(durable.quote().id());
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        assertThat(resumed.turn(input(3, durable.quote().id()), () -> spoken("हाँ")).order().status())
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void duplicateAndReclaimedSpeechCannotPublishLateProcessorResult() throws Exception {
        var first = speak(1, null, KAJU).quote();
        var input = input(2, first.id());
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var providerCalls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = executor.submit(() -> service.turn(input, () -> {
                providerCalls.incrementAndGet();
                entered.countDown();
                await(resume);
                return spoken("हाँ");
            }));
            try {
                await(entered);
                assertThat(restarted().turn(input, () -> { throw new AssertionError("Duplicate called STT"); }).quote()).isEqualTo(first);
                expireLease(2);
                var fresh = restarted().turn(input, () -> { providerCalls.incrementAndGet(); return spoken("नहीं, 250 ग्राम"); });
                assertThat(fresh.quote().grams()).isEqualTo(250);
                resume.countDown();
                assertThat(stale.get(10, TimeUnit.SECONDS).quote()).isEqualTo(fresh.quote());
                assertThat(providerCalls).hasValue(2);
                assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
                assertThat(count("SELECT COUNT(*) FROM voice_turns WHERE sequence=2")).isEqualTo(1);
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void higherSequenceWinsAndHangingUpBeforeAssentDropsLateResult() throws Exception {
        var first = speak(1, null, KAJU).quote();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = executor.submit(() -> service.turn(input(2, first.id()), () -> {
                entered.countDown();
                await(resume);
                return spoken("हाँ");
            }));
            try {
                await(entered);
                var latest = speak(3, first.id(), "नहीं, 250 ग्राम");
                resume.countDown();
                assertThat(stale.get(10, TimeUnit.SECONDS).quote()).isEqualTo(latest.quote());
                assertThat(count("SELECT COUNT(*) FROM voice_turns WHERE sequence=2 AND status='DROPPED'")).isEqualTo(1);
                assertThat(service.end(SESSION).state()).isEqualTo("ENDED");
                assertThat(restarted().turn(input(4, latest.quote().id()), () -> { throw new AssertionError(); }).state()).isEqualTo("ENDED");
            } finally {
                resume.countDown();
            }
            assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        }
    }

    @Test
    void hangupWhileSpeechProviderRunsPreventsTheResultFromBooking() throws Exception {
        var quote = speak(1, null, KAJU).quote();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var processing = executor.submit(() -> service.turn(input(2, quote.id()), () -> {
                entered.countDown();
                await(resume);
                return spoken("हाँ");
            }));
            try {
                await(entered);
                service.end(SESSION);
                resume.countDown();
                assertThat(processing.get(10, TimeUnit.SECONDS).state()).isEqualTo("ENDED");
                assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
                assertThat(count("SELECT COUNT(*) FROM voice_turns WHERE sequence=2 AND status='DROPPED'")).isEqualTo(1);
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void hangingUpAfterAssentKeepsAuthorizedRecoveryAndSilenceEndsWithoutHoldingStock() {
        var quote = speak(1, null, KAJU).quote();
        markAccepted(2, quote.id());
        service.end(SESSION);
        expireLease(2);
        restarted().recoverAcceptedTurns();
        assertThat(booking.resolve(quote.id().toString(), new AcceptRequest(SESSION, "turn-2"), null).order().id()).isEqualTo(4821);
        assertThat(restarted().poll(SESSION, "turn-2", PHONE).state()).isEqualTo("ENDED");
        shop.reset(null);
        var draft = speak(1, null, KAJU).quote();
        assertThat(speak(2, draft.id(), "").quote().id()).isEqualTo(draft.id());
        assertThat(speak(3, draft.id(), "").quote().id()).isEqualTo(draft.id());
        assertThat(speak(4, draft.id(), "").state()).isEqualTo("ENDED");
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    }

    @Test
    void droppedSpeechCanBeResentButJournalKeepsNoRawAudioTranscriptOrContact() {
        var claim = journal.claim(input(1, null));
        assertThat(claim.ticket()).isNotNull();
        expireLease(1);
        var replay = restarted().turn(input(1, null), () -> spoken(KAJU));
        assertThat(replay.quote().grams()).isEqualTo(500);
        assertThat(replay.transcript()).isEqualTo(KAJU);
        String stored = db.queryForObject("SELECT state::text FROM voice_sessions WHERE session_id=?", String.class, SESSION);
        assertThat(stored).doesNotContain(KAJU, PHONE, LATA);
        assertThat(restarted().poll(SESSION, "turn-1", PHONE).transcript()).isEmpty();
        var failed = service.turn(input(2, replay.quote().id()), () -> { throw new SarvamSpeech.SpeechUnavailable(); });
        assertThat(failed.quote()).isEqualTo(replay.quote());
        assertThat(count("SELECT COUNT(*) FROM voice_turns WHERE sequence=2 AND status='FAILED'")).isEqualTo(1);
    }

    private VoiceSessionService restarted() {
        return new VoiceSessionService(booking, new VoiceTurnJournal(db, json, transactions, 15));
    }

    private void markAccepted(int sequence, UUID quote) {
        var access = journal.claim(input(sequence, quote));
        var checkpoint = journal.completeSpeech(access.ticket(), session -> new VoiceTurnJournal.Decision(
                new Reply("CHECKING_BOOKING", "हाँ", "Checking booking", "hi-IN", null, null), quote, false, false));
        assertThat(checkpoint.ticket().status()).isEqualTo("ACCEPTED");
    }

    private void expireLease(int sequence) {
        db.update("UPDATE voice_turns SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE session_id=? AND sequence=?", SESSION, sequence);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for competing turn");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void flags(boolean rush) {
        Instant time = Instant.parse("2031-02-09T12:15:00Z");
        when(policy.current()).thenReturn(new Policy(rush ? "rush" : "normal", "LIVE", time, time, rush,
                5000, rush ? 3000 : 4000, 15, rush ? 30 : 15, rush ? 120 : 180, rush ? 20 : 30));
    }

    private Reply speak(int sequence, UUID quote, String text) { return service.turn(input(sequence, quote), () -> spoken(text)); }
    private static SarvamSpeech.Transcript spoken(String text) { return new SarvamSpeech.Transcript(text, "hi-IN"); }
    private static Turn input(int sequence, UUID quote) { return new Turn(SESSION, "turn-" + sequence, sequence, quote, LATA, PHONE); }
    private int count(String sql) { return db.queryForObject(sql, Integer.class); }
}
