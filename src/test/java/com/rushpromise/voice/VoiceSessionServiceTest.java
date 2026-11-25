package com.rushpromise.voice;

import com.rushpromise.booking.BookingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.rushpromise.booking.BookingModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceSessionServiceTest {
    private static final String PHONE = "+919000000001";
    private static final String SESSION = "browser:" + UUID.randomUUID();

    @Test
    void takesLatestExplicitWeightInHindiAndHinglish() {
        for (String audio : new String[]{
                "Ek kilo kaju katli… actually aadha kilo, saade chhe baje",
                "एक किलो काजू कतली, नहीं आधा किलो, साढ़े छह बजे।",
                "१ किलो काजू कतली, असल में ५०० ग्राम, ६:३० बजे।"
        }) {
            var intent = SpeechIntent.parse(audio);
            assertThat(intent.sku()).isEqualTo("kaju-katli");
            assertThat(intent.grams()).isEqualTo(500);
            assertThat(intent.pickupTime()).isEqualTo("18:30");
        }
        assertThat(SpeechIntent.parse("No, 250 grams").decision()).isEqualTo(SpeechIntent.Decision.ORDER);
        assertThat(SpeechIntent.parse("हाँ!").decision()).isEqualTo(SpeechIntent.Decision.YES);
        assertThat(SpeechIntent.parse("नहीं!").decision()).isEqualTo(SpeechIntent.Decision.NO);
    }

    @Test
    void changedTermsRequireFreshAssentAndOldTurnReplayReturnsCurrentPrompt() {
        BookingService booking = mock(BookingService.class);
        VoiceSessionService calls = new VoiceSessionService(booking);
        Quote original = quote(false);
        Quote replacement = quote(true);
        when(booking.quote(any())).thenReturn(new Outcome(200, "QUOTED", null, original, null));
        when(booking.accept(eq(original.id().toString()), any(), isNull()))
                .thenReturn(new Outcome(409, "TERMS_CHANGED", null, replacement, null));

        var first = turn(calls, 1, null, "चार किलो बर्फी रात 8 बजे");
        assertThat(first.quote().id()).isEqualTo(original.id());
        assertThat(first.quote().requiresApproval()).isFalse();
        assertThat(turn(calls, 2, null, "हाँ").quote().id()).isEqualTo(original.id());
        verify(booking, never()).accept(anyString(), any(), any());

        var changed = turn(calls, 3, original.id(), "हाँ");
        assertThat(changed.quote().id()).isEqualTo(replacement.id());
        assertThat(changed.quote().requiresApproval()).isTrue();
        assertThat(changed.order()).isNull();
        assertThat(turn(calls, 3, original.id(), "हाँ").quote().id()).isEqualTo(replacement.id());
        assertThat(turn(calls, 4, original.id(), "हाँ").quote().id()).isEqualTo(replacement.id());
        verify(booking, times(1)).accept(anyString(), any(), any());

        when(booking.accept(eq(replacement.id().toString()), any(), isNull()))
                .thenReturn(Outcome.error(409, "CONFLICT", "NONE_AVAILABLE"));
        turn(calls, 5, replacement.id(), "हाँ");
        verify(booking).accept(eq(replacement.id().toString()), eq(new AcceptRequest(SESSION, "test-5")), isNull());
    }

    private static Quote quote(boolean approval) {
        return new Quote(UUID.randomUUID(), SESSION, "barfi", "Barfi", 4000, 360000,
                "20:00", Instant.now(), "G", approval, Instant.now().plusSeconds(120), null);
    }

    private static VoiceSessionService.Reply turn(VoiceSessionService service, int sequence, UUID quote, String words) {
        var input = new VoiceSessionService.Turn(SESSION, "test-" + sequence, sequence, quote, "Demo tester", PHONE);
        return service.turn(input, () -> new SarvamSpeech.Transcript(words, "hi-IN"));
    }
}
