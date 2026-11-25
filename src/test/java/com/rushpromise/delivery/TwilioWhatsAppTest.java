package com.rushpromise.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TwilioWhatsAppTest {
    private static final String ACCOUNT = "AC00000000000000000000000000000000";
    private static final String MESSAGE = "SM00000000000000000000000000000000";
    private static final String TOKEN = "local-test-token";
    private static final String TO = "+12025550100";
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final DeliverySettings settings = new DeliverySettings(
        "twilio",ACCOUNT,TOKEN,"whatsapp:+12025550199","https://demo.example.test",TO);

    @Test
    void classifiesProviderResultsAndSendsOnlyToEnrolledHandset() throws Exception {
        var response = new java.util.concurrent.atomic.AtomicInteger(503);
        var form = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/messages", req -> {
            form.set(new String(req.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var body = ("{\"sid\":\""+MESSAGE+"\",\"status\":\"queued\"}").getBytes(StandardCharsets.UTF_8);
            req.sendResponseHeaders(response.get(),body.length);
            try (var output=req.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var twilio = new TwilioWhatsApp(settings,new ObjectMapper(),
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/messages"));
            var dispatch = new OutboxRepository.Dispatch(UUID.randomUUID(),UUID.randomUUID(),ATTEMPT,"READY",TO,"Pickup ready");
            assertEquals("UNKNOWN",twilio.send(dispatch).status());
            response.set(429);
            assertEquals("FAILED_RETRYABLE",twilio.send(dispatch).status());
            response.set(400);
            assertEquals("FAILED",twilio.send(dispatch).status());
            response.set(201);
            assertEquals(new DeliveryResult("SUBMITTED",MESSAGE,null),twilio.send(dispatch));
            assertTrue(form.get().contains("To=whatsapp%3A%2B12025550100"));
            assertTrue(form.get().contains("StatusCallback=https%3A%2F%2Fdemo.example.test%2Fwebhooks%2Ftwilio%2Fstatus%3Fattempt%3D"));
            assertThrows(IllegalArgumentException.class,() -> twilio.send(new OutboxRepository.Dispatch(
                dispatch.messageId(),dispatch.runId(),ATTEMPT,"READY","+12025550101","Pickup ready")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlySignedCallbackCanAdvanceTheAttempt() throws Exception {
        var params = Map.of("AccountSid", new String[]{ACCOUNT}, "MessageSid", new String[]{MESSAGE},
                            "MessageStatus", new String[]{"delivered"});
        String payload = settings.statusUrl(ATTEMPT) + "AccountSid" + ACCOUNT + "MessageSid" + MESSAGE
            + "MessageStatusdelivered";
        var hmac = javax.crypto.Mac.getInstance("HmacSHA1");
        hmac.init(new javax.crypto.spec.SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
        String signature = java.util.Base64.getEncoder().encodeToString(hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        assertTrue(TwilioStatusController.validSignature(settings.statusUrl(ATTEMPT),params,TOKEN,signature));
        assertFalse(TwilioStatusController.validSignature(settings.statusUrl(ATTEMPT)+"x",params,TOKEN,signature));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setQueryString("attempt="+ATTEMPT);
        params.forEach(request::setParameter);
        request.setParameter("attempt",ATTEMPT.toString());
        var outbox = mock(OutboxRepository.class);
        var controller = new TwilioStatusController(settings, outbox, org.mockito.Mockito.mock(OwnerWhatsApp.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> controller.status(request));
        verifyNoInteractions(outbox);
        request.addHeader("X-Twilio-Signature",signature);
        controller.status(request);
        verify(outbox).observe(ATTEMPT,new DeliveryResult("DELIVERED",MESSAGE,null));
    }
}
