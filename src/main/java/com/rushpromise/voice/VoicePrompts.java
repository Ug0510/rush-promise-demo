package com.rushpromise.voice;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
class VoicePrompts {
    private static final Map<String, String> NAMES = Map.of(
            "CONFIRMED", "confirmed", "AWAITING_APPROVAL", "approval", "CHECKING_BOOKING", "checking", "BUSY", "busy");
    private final Map<String, String> prompts = new HashMap<>();

    VoicePrompts() {
        for (String name : NAMES.values()) {
            for (String language : new String[]{"hi", "en"}) {
                String key = name + "-" + language;
                try (var input = new ClassPathResource("voice-prompts/" + key + ".wav").getInputStream()) {
                    prompts.put(key, Base64.getEncoder().encodeToString(input.readAllBytes()));
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot load saved speech prompt " + key, failure);
                }
            }
        }
    }

    String audio(String state, String language) {
        String name = NAMES.get(state);
        return name == null ? null : prompts.get(name + ("en-IN".equals(language) ? "-en" : "-hi"));
    }
}
