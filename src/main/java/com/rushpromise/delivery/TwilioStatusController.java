package com.rushpromise.delivery;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("!worker")
class TwilioStatusController {
    private final DeliverySettings settings;
    private final OutboxRepository outbox;
    private final OwnerWhatsApp owners;

    TwilioStatusController(DeliverySettings settings, OutboxRepository outbox, OwnerWhatsApp owners) {
        this.settings = settings;
        this.outbox = outbox;
        this.owners = owners;
    }

    @PostMapping(path = "/webhooks/twilio/status", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void status(HttpServletRequest request) {
        String signature = request.getHeader("X-Twilio-Signature");
        String query = request.getQueryString();
        if (!settings.callbackConfigured() || signature == null || query == null
                || !query.matches("attempt=[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        UUID attempt = UUID.fromString(query.substring("attempt=".length()));
        Map<String, String[]> form = new TreeMap<>(request.getParameterMap());
        String[] attemptValues = form.remove("attempt");
        if (attemptValues == null || attemptValues.length != 1
                || !validSignature(settings.statusUrl(attempt), form, settings.authToken(), signature)
                || !settings.accountSid().equals(read(form, "AccountSid"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        String sid = read(form, "MessageSid");
        String messageStatus = read(form, "MessageStatus");
        if (!TwilioWhatsApp.validSid(sid) || messageStatus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        String code = read(form, "ErrorCode");
        String error = code != null && code.matches("\\d{3,8}") ? "TWILIO_ERROR_" + code : null;
        DeliveryResult update = DeliveryResult.providerStatus(messageStatus, sid, error);
        if (update != null && !owners.observe(attempt, update)) {
            outbox.observe(attempt, update);
        }
    }

    private static String read(Map<String, String[]> form, String name) {
        String[] values = form.get(name);
        return values != null && values.length == 1 ? values[0] : null;
    }

    static boolean validSignature(String url, Map<String, String[]> form, String token, String signature) {
        StringBuilder payload = new StringBuilder(url);
        new TreeMap<>(form).forEach((key, values) -> Arrays.stream(values).sorted().distinct()
            .forEach(value -> payload.append(key).append(value)));
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] expected = hmac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, Base64.getDecoder().decode(signature));
        } catch (IllegalArgumentException exception) {
            return false;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot validate the Twilio signature", exception);
        }
    }
}
