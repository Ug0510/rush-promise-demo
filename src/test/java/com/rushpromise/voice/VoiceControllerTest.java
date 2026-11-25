package com.rushpromise.voice;

import com.rushpromise.delivery.BuyerWhatsApp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.booking.GuestCheckouts;
import com.rushpromise.platform.SarvamShopRouting;
import com.rushpromise.platform.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceControllerTest {
    private static final String PHONE = "+919000000001";
    private static final String NATIVE_SUBJECT = "0123456789abcdef".repeat(4);
    private static final String BEARER = "test-authorized-only";
    private static final MockMultipartFile AUDIO = new MockMultipartFile(
            "audio", "turn.webm", "audio/webm", new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3});
    private final SarvamSpeech speech = mock(SarvamSpeech.class);
    private final VoiceSessionService sessions = mock(VoiceSessionService.class);
    private final VoiceConversation conversation = mock(VoiceConversation.class);
    private final SarvamShopRouting routing = mock(SarvamShopRouting.class);
    private final VoiceController controller = new VoiceController(speech, sessions, new VoicePrompts(), conversation,
            routing, mock(GuestCheckouts.class), mock(BuyerWhatsApp.class), BEARER, PHONE);

    @Test
    void bookingResultsHaveDistinctSavedAudioWhenSpeechSynthesisFails() {
        when(speech.configured()).thenReturn(true);
        when(speech.synthesize(anyString(), eq("hi-IN"))).thenThrow(new SarvamSpeech.SpeechUnavailable());
        var booked = new VoiceSessionService.SavedOrder(4821, "Kaju katli", 500, 60000, "18:30", "CONFIRMED");
        var approval = new VoiceSessionService.SavedOrder(4822, "Barfi", 4000, 360000, "20:00", "AWAITING_APPROVAL");
        when(sessions.turn(any(), any())).thenReturn(
                new VoiceSessionService.Reply("CONFIRMED", "हाँ", "Order 4821 is confirmed.", "hi-IN", null, booked),
                new VoiceSessionService.Reply("AWAITING_APPROVAL", "हाँ", "Waiting for shop approval.", "hi-IN", null, approval),
                new VoiceSessionService.Reply("CHECKING_BOOKING", "हाँ", "Checking if your order was saved.", "hi-IN", null, null));
        var confirmedResult = browser(false, AUDIO);
        var approvalResult = browser(false, AUDIO);
        var uncertainResult = browser(false, AUDIO);
        assertThat(confirmedResult.turn().order()).isEqualTo(booked);
        assertThat(approvalResult.turn().order()).isEqualTo(approval);
        assertThat(uncertainResult.turn().order()).isNull();
        for (var result : new VoiceController.BrowserReply[]{confirmedResult, approvalResult, uncertainResult}) {
            assertThat(result.audioFallback()).isTrue();
            assertWave(result.audioWavBase64());
        }
        assertThat(confirmedResult.audioWavBase64()).isNotEqualTo(approvalResult.audioWavBase64());
        verify(speech, times(2)).synthesize(anyString(), eq("hi-IN"));
    }

    @Test
    void receiptPollRequiresTheSameScopeAndDoesNotReadAudioOrCallSarvam() {
        UUID session = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        when(sessions.poll("browser:" + session, turn.toString(), PHONE))
                .thenReturn(new VoiceSessionService.Reply("CHECKING_BOOKING", "", "Checking", "hi-IN", null, null));
        var receipt = controller.browserTurn(session, turn, 1, null, "Tester", PHONE, true, null);
        assertThat(receipt.getBody().turn().state()).isEqualTo("CHECKING_BOOKING");
        assertWave(receipt.getBody().audioWavBase64());
        verifyNoInteractions(speech);
        assertThatThrownBy(() -> controller.browserTurn(session, turn, 1, null, "Tester", "+919000000002", true, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.sarvamTurn("Bearer wrong",
                new VoiceController.SarvamTurn("poll", session.toString(), null, 1, null, null, PHONE, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }

    @Test
    void saturationReturnsSavedBusyLineWithoutAnotherProviderRequest() throws Exception {
        when(speech.configured()).thenReturn(true);
        var entered = new CountDownLatch(6);
        var release = new CountDownLatch(1);
        when(speech.transcribe(any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test speech did not unblock");
            return new SarvamSpeech.Transcript("", "hi-IN");
        });
        when(speech.synthesize(anyString(), anyString())).thenReturn("unused");
        when(sessions.turn(any(), any())).thenAnswer(call -> {
            call.<VoiceSessionService.Utterance>getArgument(1).read();
            return new VoiceSessionService.Reply("COLLECTING", "", "Say the item", "hi-IN", null, null);
        });
        var executor = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) executor.submit(() -> browser(false, AUDIO));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var busy = controller.browserTurn(UUID.randomUUID(), UUID.randomUUID(), 1, null, "Tester", PHONE, false, AUDIO);
            assertThat(busy.getStatusCode().value()).isEqualTo(429);
            assertThat(busy.getBody().turn().state()).isEqualTo("BUSY");
            assertWave(busy.getBody().audioWavBase64());
            verify(speech, times(6)).transcribe(any());
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(4, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void startupAndMenuNeedBearerButNoCallerIdentityOrBookingJournal() {
        var greeting = new VoiceConversation.Greeting("Deepak Mithai Bhandar", "hi-IN", "नमस्ते।");
        when(conversation.greet("hi-IN")).thenReturn(greeting);
        when(conversation.menu("hi-IN")).thenReturn(greeting);
        for (String event : new String[]{ "start", "menu" }) {
            var request = new VoiceController.SarvamTurn(event, null, null, null, null, null, null, null, null);
            assertThat(controller.sarvamTurn("Bearer " + BEARER, request).getBody()).isEqualTo(greeting);
        }
        verifyNoInteractions(sessions, speech);
    }

    @Test
    void authenticNativeSlashInteractionIdentifierStaysStableAcrossRetriesAndEnd() {
        String interaction = "Ab12:CD3/A1b2C3d4E5f6G7h8I9j0KLMNOP";
        String session = "sarvam64:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(interaction.getBytes(StandardCharsets.US_ASCII));
        var quoteRequest = new VoiceController.SarvamTurn(null, interaction, null, 1, null,
                "Tester", PHONE, "आधा किलो काजू कतली, साढ़े छह बजे।", null);
        when(sessions.turn(any(), any())).thenReturn(
                new VoiceSessionService.Reply("COLLECTING", "", "Tell me more.", "hi-IN", null, null));
        assertThat(controller.sarvamTurn("Bearer " + BEARER, quoteRequest).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.sarvamTurn("Bearer " + BEARER, quoteRequest).getStatusCode().value()).isEqualTo(200);
        verify(sessions, times(2)).turn(argThat(turn -> turn.sessionId().equals(session) && turn.turnId().equals("turn-1")), any());
        controller.sarvamTurn("Bearer " + BEARER,
                new VoiceController.SarvamTurn("end", interaction, null, 0, null, null, null, null, null));
        verify(sessions).end(session);
        for (String malformed : new String[]{ "one/../two", "one/..", "{{interaction_id}}" }) {
            assertThatThrownBy(() -> controller.sarvamTurn("Bearer " + BEARER,
                    new VoiceController.SarvamTurn(null, malformed, null, 1, null, null, PHONE, "", null)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
    }

    @Test
    void aRoutedCallMustSelectAndItsOriginalShopWinsAnyLaterRetry() {
        String interaction = "Ab12:CD3/A1b2C3d4E5f6G7h8I9j0KLMNOP";
        String schema = "shop_" + "1".repeat(32);
        String deepak = "deepak-mithai-bhandar";
        String bakery = "virajs-bakery";
        var route = new SarvamShopRouting.Route(bakery, schema);
        when(routing.choices()).thenReturn(List.of(new SarvamShopRouting.Choice(bakery, "Viraj's Bakery")));
        var turn = new VoiceController.SarvamTurn(null, interaction, null, 1, null, null, NATIVE_SUBJECT, "yes", "en-IN", deepak, true);
        var ask = (VoiceController.ShopChoiceReply) controller.sarvamTurn("Bearer " + BEARER, turn).getBody();
        assertThat(ask.state()).isEqualTo("SHOP_SELECTION_REQUIRED");
        verifyNoInteractions(sessions, speech);

        when(routing.choose(anyString(), eq(deepak))).thenReturn(route);
        when(conversation.greet("hi-IN")).thenAnswer(call -> {
            assertThat(TenantContext.schema()).isEqualTo(schema);
            return new VoiceConversation.Greeting("Viraj's Bakery", "hi-IN", "नमस्ते।");
        });
        var change = new VoiceController.SarvamTurn("select-shop", interaction, null, null, null,
                null, null, null, "hi-IN", deepak, true);
        var original = (VoiceController.ShopChoiceReply) controller.sarvamTurn("Bearer " + BEARER, change).getBody();
        assertThat(original.state()).isEqualTo("SHOP_ALREADY_SELECTED");
        assertThat(original.shopName()).isEqualTo("Viraj's Bakery");

        when(routing.route(anyString())).thenReturn(route);
        when(sessions.nativePickupTurn(anyString(), eq("turn-1"), eq(1), isNull(), eq(""), eq(NATIVE_SUBJECT), any())).thenAnswer(call -> {
            assertThat(TenantContext.schema()).isEqualTo(schema);
            return new VoiceSessionService.Reply("COLLECTING", "yes", "Tell me what you'd like.", "en-IN", null, null);
        });
        assertThat(controller.sarvamTurn("Bearer " + BEARER, turn).getBody()).isInstanceOf(VoiceSessionService.Reply.class);
        assertThat(TenantContext.legacy()).isTrue();
    }

    @Test
    void absentOrSelfDeclaredContactStaysReadOnlyInAPinnedNativeCall() {
        String interaction = "Ab12:CD3/A1b2C3d4E5f6G7h8I9j0KLMNOP";
        String schema = "shop_" + "3".repeat(32);
        when(routing.route(anyString())).thenReturn(new SarvamShopRouting.Route("deepak-mithai-bhandar", schema));
        when(conversation.previewWithoutIdentity(anyString(), eq("hi-IN"))).thenAnswer(call -> {
            assertThat(TenantContext.schema()).isEqualTo(schema);
            return new VoiceSessionService.Reply("INFORMATION", "", "यह कीमत की जानकारी है।", "hi-IN", null, null);
        });
        for (String unverified : new String[]{null, PHONE, "0123", "ABCDEF01".repeat(8)}) {
            var request = new VoiceController.SarvamTurn(null, interaction, null, 2, UUID.randomUUID(), null,
                    unverified, "बर्फी की कीमत क्या है?", "hi-IN", null, true);
            var answer = (VoiceSessionService.Reply) controller.sarvamTurn("Bearer " + BEARER, request).getBody();
            assertThat(answer.state()).isEqualTo("INFORMATION");
            assertThat(answer.order()).isNull();
        }
        verifyNoInteractions(sessions, speech);
    }

    @Test
    void nativeQuoteVariableIsExactAndAnInventedValueDoesNotBecomeConsent() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        UUID id = UUID.randomUUID();
        var assigned = mapper.readValue("{\"confirmationQuoteId\":\"" + id + "\"}", VoiceController.SarvamTurn.class);
        assertThat(assigned.confirmationQuoteId()).isEqualTo(id);
        for (String supplied : new String[]{"\"latest-quote-id\"", "\"\"", "\"1-2-3-4-5\"", "{}", "null"}) {
            var invalid = mapper.readValue("{\"confirmationQuoteId\":" + supplied + "}", VoiceController.SarvamTurn.class);
            assertThat(invalid.confirmationQuoteId()).isNull();
        }
        var offer = new VoiceSessionService.Offer(id, "Barfi", 250, 22500, "18:00", false, Instant.EPOCH, "GRAMS", null);
        var quote = new VoiceSessionService.Reply("AWAITING_CONFIRMATION", "", "ढाई सौ ग्राम। पक्का कर दूँ?", "hi-IN", offer, null);
        var info = new VoiceSessionService.Reply("INFORMATION", "", "900 रुपये प्रति किलो।", "hi-IN", offer, null);
        var changed = new VoiceSessionService.Reply("CLARIFY_QUANTITY", "", "वज़न फिर बताइए।", "hi-IN", null, null);
        assertThat(mapper.valueToTree(quote).get("confirmationToken").asText()).isEqualTo(id.toString());
        assertThat(mapper.valueToTree(info).get("confirmationToken").asText()).isEmpty();
        assertThat(mapper.valueToTree(changed).get("confirmationToken").asText()).isEmpty();
    }

    @Test
    void callBoundPickupNeedsTheAuthenticatedPinnedModeAndNoPhone() {
        String interaction = "Ab12:CD3/A1b2C3d4E5f6G7h8I9j0KLMNOP";
        String schema = "shop_" + "4".repeat(32);
        String slug = "deepak-mithai-bhandar";
        var request = new VoiceController.SarvamTurn(null, interaction, null, 1, null, null, null,
                "ढाई सौ ग्राम बर्फी", "hi-IN", slug, true, "interaction");
        when(routing.choices()).thenReturn(List.of(new SarvamShopRouting.Choice(slug, "Deepak Mithai Bhandar")));
        assertThat(controller.sarvamTurn("Bearer " + BEARER, request).getBody()).isInstanceOf(VoiceController.ShopChoiceReply.class);
        verifyNoInteractions(sessions);
        when(routing.route(anyString())).thenReturn(new SarvamShopRouting.Route(slug, schema));
        when(sessions.nativeInteractionTurn(anyString(), eq("turn-1"), eq(1), isNull(), eq(""), any())).thenAnswer(call -> {
            assertThat(TenantContext.schema()).isEqualTo(schema);
            return new VoiceSessionService.Reply("COLLECTING", "", "पिकअप का समय?", "hi-IN", null, null);
        });
        assertThat(controller.sarvamTurn("Bearer " + BEARER, request).getBody()).isInstanceOf(VoiceSessionService.Reply.class);
        verify(sessions).nativeInteractionTurn(anyString(), eq("turn-1"), eq(1), isNull(), eq(""), any());
        assertThatThrownBy(() -> controller.sarvamTurn("Bearer wrong", request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void circuitOpensOnThreeConsecutiveFailuresAndAHealthyUpstreamResetsIt() {
        var circuit = new SarvamSpeech.ProviderCircuit();
        for (int i = 0; i < 3; i++) {
            assertThat(circuit.allow()).isTrue();
            circuit.failed();
        }
        assertThat(circuit.allow()).isFalse();
        circuit.healthy();
        assertThat(circuit.allow()).isTrue();
    }

    private VoiceController.BrowserReply browser(boolean receipt, MockMultipartFile audio) {
        return controller.browserTurn(UUID.randomUUID(), UUID.randomUUID(), 1, null,
                "Synthetic tester", PHONE, receipt, audio).getBody();
    }

    private static void assertWave(String audio) {
        byte[] decoded = Base64.getDecoder().decode(audio);
        assertThat(new String(decoded, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
    }
}
