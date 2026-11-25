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

    @Test
    void preservesAnUnclearSingleDigitGramCorrectionForClarification() {
        var conversation = new VoiceConversation(null, null);
        var recognized = conversation.orderWords("चार ग्राम कर दीजिए।", "barfi");
        assertThat(recognized.parsed().grams()).isEqualTo(4);
        assertThat(recognized.ambiguousWeight()).isTrue();
        assertThat(VoiceConversation.weightClarification(recognized.parsed().grams(), true)).contains("4 ग्राम", "इकाई");
        var explicitKilograms = conversation.orderWords("चार किलो कर दीजिए।", "barfi");
        assertThat(explicitKilograms.parsed().grams()).isEqualTo(4000);
        assertThat(explicitKilograms.ambiguousWeight()).isFalse();
        assertThat(conversation.orderWords("सिर्फ़ चार ग्राम चाहिए।", "barfi").ambiguousWeight()).isFalse();
        for (String correction : new String[]{"दो सौ पचास ग्राम कर दीजिए।", "ढाई सौ ग्राम।", "do sau pachaas gram"}) {
            var amount = conversation.orderWords(correction, "barfi");
            assertThat(amount.parsed().grams()).isEqualTo(250);
            assertThat(amount.ambiguousWeight()).isFalse();
        }
    }

    @Test
    void recognisesExactLiveAssentPhrasesWithoutEatingCorrections() {
        for (String assent : new String[]{"कर दो।", "हां कन्फर्म है।", "हाँ, कंफर्म है।", "हाँ सर", "हाँ जी", "जी हाँ", "हाँ मैडम", "yes please", "yes sir"}) {
            assertThat(SpeechIntent.parse(assent).decision()).isEqualTo(SpeechIntent.Decision.YES);
        }
        assertThat(SpeechIntent.parse("हाँ, ढाई सौ ग्राम कर दो।").decision()).isEqualTo(SpeechIntent.Decision.ORDER);
        assertThat(SpeechIntent.parse("आठ बजे कर दो।").decision()).isEqualTo(SpeechIntent.Decision.ORDER);
        assertThat(SpeechIntent.parse("अभी कन्फर्म नहीं है।").decision()).isNotEqualTo(SpeechIntent.Decision.YES);
        assertThat(SpeechIntent.parse("हाँ सर?").decision()).isNotEqualTo(SpeechIntent.Decision.YES);
        assertThat(SpeechIntent.parse("yes sir if the pickup is today").decision()).isNotEqualTo(SpeechIntent.Decision.YES);
        assertThat(SpeechIntent.parse("yes sir, change it to 250 grams").decision()).isEqualTo(SpeechIntent.Decision.ORDER);
        assertThat(SpeechIntent.confirmationCode("हाँ ४ २ ८ १।")).isEqualTo("4281");
        assertThat(SpeechIntent.confirmationCode("हाँ चार दो आठ एक")).isEqualTo("4281");
        assertThat(SpeechIntent.confirmationCode("Confirm code four two eight one")).isEqualTo("4281");
        assertThat(SpeechIntent.confirmationCode("confirm code 4281 if the pickup is today")).isNull();
        assertThat(SpeechIntent.confirmationCode("हाँ 4281?")).isNull();
    }

    @Test
    void recognisesTomorrowMorningAndAnExplicitSecondOption() {
        for (String request : new String[]{"tomorrow 09:00", "कल सुबह नौ बजे", "कल नौ बजे सुबह"}) {
            assertThat(SpeechIntent.tomorrow(request)).isTrue();
            assertThat(SpeechIntent.parse(request).pickupTime()).isEqualTo("09:00");
        }
        assertThat(SpeechIntent.parse("आज रात नौ बजे").pickupTime()).isEqualTo("21:00");
        assertThat(SpeechIntent.pickupPosition("दूसरा वाला कर दीजिए।")).isEqualTo(1);
        assertThat(SpeechIntent.pickupPosition("ढाई सौ ग्राम कर दीजिए।")).isNull();
    }

}
