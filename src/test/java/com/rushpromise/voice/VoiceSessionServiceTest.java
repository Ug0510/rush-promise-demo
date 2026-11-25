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

    @Test
    void readsPickupClockAndQuantityInNaturalHindi() {
        assertThat(VoiceConversation.hindiTime("19:30")).isEqualTo("शाम साढ़े सात बजे");
        assertThat(VoiceConversation.hindiTime("20:00")).isEqualTo("रात आठ बजे");
        assertThat(VoiceConversation.amount(2500, "GRAMS", true)).isEqualTo("ढाई किलो");
        assertThat(VoiceConversation.amount(2, "PIECE", true)).isEqualTo("2 पीस");
        assertThat(VoiceConversation.spokenItem("Chocolate brownie", true)).isEqualTo("चॉकलेट ब्राउनी");
        assertThat(VoiceConversation.spokenItem("Pistachio brownie", true)).isEqualTo("Pistachio brownie");
    }

}
