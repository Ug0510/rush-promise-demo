package com.rushpromise.delivery;

import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
record DeliverySettings(String mode, String accountSid, String authToken, String from, String callbackBase,
                        String liveRecipient) {
    DeliverySettings(@Value("${NOTIFIER:console}") String mode,
                     @Value("${TWILIO_ACCOUNT_SID:}") String accountSid,
                     @Value("${TWILIO_AUTH_TOKEN:}") String authToken,
                     @Value("${TWILIO_MSG_FROM:}") String from,
                     @Value("${RUSH_PUBLIC_BASE_URL:}") String callbackBase,
                     @Value("${RUSH_DEMO_HANDSET:}") String liveRecipient) {
        if (!mode.equals("console") && !mode.equals("twilio")) {
            throw new IllegalArgumentException("NOTIFIER must be console or twilio");
        }
        this.mode = mode;
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.from = from;
        this.callbackBase = callbackBase;
        this.liveRecipient = liveRecipient;
    }

    boolean live() {
        return mode.equals("twilio");
    }

    boolean callbackConfigured() {
        if (!accountSid.matches("AC[0-9a-fA-F]{32}") || authToken.isBlank() || callbackBase.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(callbackBase);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && (uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    void requireLiveConfiguration() {
        if (!callbackConfigured() || !from.matches("whatsapp:\\+[1-9]\\d{7,14}")
            || !liveRecipient.matches("\\+[1-9]\\d{7,14}")) {
            throw new IllegalStateException("Twilio mode requires credentials, an HTTPS callback and the enrolled recipient");
        }
    }

    String statusUrl(UUID attemptId) {
        return callbackBase.replaceFirst("/$", "") + "/webhooks/twilio/status?attempt=" + attemptId;
    }

    @Override
    public String toString() {
        return "DeliverySettings[mode=" + mode + "]";
    }
}
