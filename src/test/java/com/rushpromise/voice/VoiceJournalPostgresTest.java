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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.booking.BookingService;
import com.rushpromise.booking.PolicyService;
import com.rushpromise.booking.ShopService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
        "rush.voice.recovery-ms=600000",
        "RUSH_SARVAM_TOOL_TOKEN=native-tool-fixture-only"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "RUSH_TEST_JDBC_URL", matches = "jdbc:postgresql:.*/rush_test_[A-Za-z0-9_]+(?:\\?.*)?")
class VoiceJournalPostgresTest {
    private static final String PHONE = "+12025550101";
    private static final String SESSION = "browser:voice-fixture";
    private static final String LATA = "Lata";
    private static final String KAJU = "एक किलो काजू कतली, नहीं आधा किलो, साढ़े छह बजे।";

    @Autowired VoiceSessionService service;
    @Autowired VoiceConversation conversation;
    @Autowired VoiceTurnJournal journal;
    @Autowired BookingService booking;
    @Autowired ShopService shop;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @MockitoBean PolicyService policy;

    @BeforeEach
    void reset() {
        flags(false);
        assertThat(shop.reset(null).status()).isEqualTo(200);
    }

    @Test
    void menuAndPriceKeepCurrentQuoteAndExplicitLanguageSurvivesRestart() {
        var initial = speak(1, null, KAJU).quote();
        var english = speak(2, initial.id(), "English please");
        assertThat(english.language()).isEqualTo("en-IN");
        assertThat(english.quote().id()).isEqualTo(initial.id());
        var quoteAgain = restarted().turn(input(3, initial.id()),
                () -> new SarvamSpeech.Transcript("yes, what is the price of 500 grams of kaju katli?", "hi-IN"));
        assertThat(quoteAgain.replyText()).contains("600 rupees").contains("Say yes");
        assertThat(quoteAgain.language()).isEqualTo("en-IN");
        assertThat(quoteAgain.quote().id()).isEqualTo(initial.id());
        var catalog = speak(4, initial.id(), "What sweets do you have?");
        assertThat(catalog.quote().id()).isEqualTo(initial.id());
        assertThat(catalog.replyText()).contains("Kaju katli").contains("Barfi");
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        var finalYes = speak(5, initial.id(), "yes");
        assertThat(finalYes.order().status()).isEqualTo("CONFIRMED");
        assertThat(finalYes.language()).isEqualTo("en-IN");
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
        var waiting = new VoiceSessionService(losingConnection, journal, conversation)
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

    @Test
    void nativeToolResolvesMixedCorrectionsAndPersistsOnlyAnExplicitCurrentAssent() throws Exception {
        assertThat(nativeTurn(1, "haan", null).at("/state").asText()).isEqualTo("COLLECTING");
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        JsonNode kilo = nativeTurn(2, "Ek kilo kaju katli saat baje", null);
        assertOffer(kilo, 1000, 120_000, "19:00");
        JsonNode half = nativeTurn(3, "actually aadha kilo", null);
        assertOffer(half, 500, 60_000, "19:00");
        JsonNode newTime = nativeTurn(4, "saade saat kar do", null);
        assertOffer(newTime, 500, 60_000, "19:30");
        String oldQuoteId = newTime.at("/quote/id").asText();
        JsonNode mixed = nativeTurn(5, "haan, but change to one kilo", oldQuoteId);
        assertOffer(mixed, 1000, 120_000, "19:30");
        String latestQuoteId = mixed.at("/quote/id").asText();
        assertThat(latestQuoteId).isNotEqualTo(oldQuoteId);
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();

        // An interrupted playback leaves no completed-quote ID; an old read-back ID is stale in the journal.
        assertThat(nativeTurn(6, "haan", null).at("/quote/id").asText()).isEqualTo(latestQuoteId);
        assertThat(nativeTurn(7, "haan", oldQuoteId).at("/quote/id").asText()).isEqualTo(latestQuoteId);
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        JsonNode saved = nativeTurn(8, "haan", latestQuoteId);
        assertThat(saved.at("/order/id").asInt()).isEqualTo(4821);
        assertThat(saved.at("/order/grams").asInt()).isEqualTo(1000);
        assertThat(saved.at("/order/pickupTime").asText()).isEqualTo("19:30");
        assertThat(nativeTurn(8, "haan", latestQuoteId).at("/order")).isEqualTo(saved.at("/order"));
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM booking_keys WHERE status=201")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_messages WHERE kind='CONFIRMED'")).isEqualTo(1);
    }

    @Test
    void unsupportedWeightRepeatsAndUnusableSpeechNeverReserveStock() throws Exception {
        JsonNode unsupported = nativeTurn(1, "350 gram kaju katli saade chhe baje", null);
        assertThat(unsupported.at("/quote").isNull()).isTrue();
        assertThat(unsupported.at("/replyText").asText()).contains("250", "500");
        assertThat(count("SELECT COUNT(*) FROM quotes")).isZero();
        JsonNode draft = nativeTurn(2, "500 gram", null);
        assertOffer(draft, 500, 60_000, "18:30");
        String id = draft.at("/quote/id").asText();
        assertThat(nativeTurn(3, "dobara", null).at("/quote/id").asText()).isEqualTo(id);
        assertThat(count("SELECT COUNT(*) FROM quotes")).isEqualTo(1);
        assertThat(nativeTurn(4, "hmm what", null).at("/quote/id").asText()).isEqualTo(id);
        assertThat(nativeTurn(5, "ba da", null).at("/state").asText()).isEqualTo("HANDOFF");
        assertThat(nativeTurn(6, "haan", id).at("/state").asText()).isEqualTo("ENDED");
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
        assertThat(count("SELECT phone_grams_free FROM batches WHERE id='A'")).isEqualTo(500);
    }

    @Test
    void demoResetStartsANewVoiceRunAndRemovesAllOldPromptState() {
        var oldQuote = speak(1, null, KAJU).quote();
        assertThat(count("SELECT COUNT(*) FROM voice_sessions")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM voice_turns")).isEqualTo(1);
        shop.reset(null);
        assertThat(count("SELECT COUNT(*) FROM voice_sessions")).isZero();
        assertThat(count("SELECT COUNT(*) FROM voice_turns")).isZero();
        assertThat(restarted().turn(input(2, oldQuote.id()), () -> spoken("हाँ")).order()).isNull();
        assertThat(speak(3, null, KAJU).quote().id()).isNotEqualTo(oldQuote.id());
    }

    private JsonNode nativeTurn(int sequence, String utterance, String confirmationQuote) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "turn");
        body.put("interactionId", "sarvam-local-fixture");
        body.put("turnId", "native-" + sequence);
        body.put("sequence", sequence);
        body.put("customerName", "Native fixture");
        body.put("phone", PHONE);
        body.put("utterance", utterance);
        if (confirmationQuote != null) body.put("confirmationQuoteId", confirmationQuote);
        String reply = mvc.perform(post("/api/voice/sarvam/turn")
                        .header("Authorization", "Bearer native-tool-fixture-only")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(reply);
    }

    private static void assertOffer(JsonNode reply, int grams, int paise, String pickupTime) {
        assertThat(reply.at("/state").asText()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(reply.at("/quote/itemName").asText()).isEqualTo("Kaju katli");
        assertThat(reply.at("/quote/grams").asInt()).isEqualTo(grams);
        assertThat(reply.at("/quote/pricePaise").asInt()).isEqualTo(paise);
        assertThat(reply.at("/quote/pickupTime").asText()).isEqualTo(pickupTime);
        assertThat(reply.at("/order").isNull()).isTrue();
    }

    private VoiceSessionService restarted() {
        return new VoiceSessionService(booking, new VoiceTurnJournal(db, json, transactions, 15), conversation);
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
