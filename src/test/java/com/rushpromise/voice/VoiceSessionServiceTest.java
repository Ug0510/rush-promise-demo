package com.rushpromise.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceSessionServiceTest {
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

}
