package com.rushpromise.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

final class TwilioWhatsApp {
    private final DeliverySettings settings;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http;

    TwilioWhatsApp(DeliverySettings settings, ObjectMapper json) {
        this(settings, json, URI.create("https://api.twilio.com/2010-04-01/Accounts/"
            + settings.accountSid() + "/Messages.json"));
    }

    TwilioWhatsApp(DeliverySettings settings, ObjectMapper json, URI endpoint) {
        this.settings = settings;
        this.json = json;
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    DeliveryResult send(OutboxRepository.Dispatch dispatch) {
        if (!settings.liveRecipient().equals(dispatch.recipient())
            || !(dispatch.kind().equals("CONFIRMED") || dispatch.kind().equals("READY"))) {
            throw new IllegalArgumentException("Dispatch outside the authorized handset and message kinds");
        }
        return sendMessage(dispatch);
    }

    DeliveryResult sendOwner(OutboxRepository.Dispatch dispatch) {
        if (!"NEW_ORDER".equals(dispatch.kind()) || !dispatch.recipient().matches("\\+[1-9][0-9]{7,14}")) {
            throw new IllegalArgumentException("Invalid verified owner dispatch");
        }
        return sendMessage(dispatch);
    }

    DeliveryResult sendBuyer(OutboxRepository.Dispatch dispatch) {
        if (!"PICKUP_CONFIRMATION".equals(dispatch.kind()) || !dispatch.recipient().matches("\\+[1-9][0-9]{7,14}")) {
            throw new IllegalArgumentException("Invalid verified buyer pickup dispatch");
        }
        return sendMessage(dispatch);
    }

    private DeliveryResult sendMessage(OutboxRepository.Dispatch dispatch) {
        String credentials = settings.accountSid() + ":" + settings.authToken();
        String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        String form = "From=" + encode(settings.from()) + "&To=" + encode("whatsapp:" + dispatch.recipient())
            + "&Body=" + encode(dispatch.body()) + "&StatusCallback=" + encode(settings.statusUrl(dispatch.attemptId()));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(9))
            .header("Authorization", "Basic " + auth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 429) {
                return new DeliveryResult("FAILED_RETRYABLE", null, "RATE_LIMITED");
            }
            if (code >= 400 && code < 500 && code != 408) {
                return new DeliveryResult("FAILED", null, "TWILIO_HTTP_" + code);
            }
            if (code < 200 || code >= 300) {
                return DeliveryResult.unknown("TWILIO_HTTP_" + code);
            }
            JsonNode fields = json.readTree(response.body());
            String sid = fields.path("sid").asText();
            if (!validSid(sid)) {
                return DeliveryResult.unknown("TWILIO_MISSING_MESSAGE_SID");
            }
            var result = DeliveryResult.providerStatus(fields.path("status").asText(), sid, null);
            return result == null ? new DeliveryResult("SUBMITTED", sid, null) : result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryResult.unknown("TWILIO_INTERRUPTED");
        } catch (IOException exception) {
            return DeliveryResult.unknown("TWILIO_RESPONSE_UNCERTAIN");
        }
    }

    static boolean validSid(String sid) {
        return sid != null && sid.matches("(SM|MM)[0-9a-fA-F]{32}");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
