package com.rushpromise.voice;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private static final String BEARER = "test-authorized-only";
    private static final MockMultipartFile AUDIO = new MockMultipartFile(
            "audio", "turn.webm", "audio/webm", new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3});
    private final SarvamSpeech speech = mock(SarvamSpeech.class);
    private final VoiceSessionService sessions = mock(VoiceSessionService.class);
    private final VoiceController controller = new VoiceController(speech, sessions, new VoicePrompts(), BEARER, PHONE);

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
                new VoiceController.SarvamTurn("poll", session.toString(), null, 1, null, null, PHONE, null)))
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
