package com.rushpromise.delivery;

import com.rushpromise.platform.OwnerPhoneAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OwnerWhatsAppWebhookTest {
    private static final String URL = "https://fixture.example/api/twilio/owner-link";
    private static final String ACCOUNT = "AC" + "a".repeat(32);
    private static final String MESSAGE = "SM" + "b".repeat(32);
    private static final String TOKEN = "fixture-not-a-secret";
    private static final String SANDBOX = "whatsapp:+12025550171";
    private static final String SENDER = "+12025550172";
    private final OwnerWhatsApp links = mock(OwnerWhatsApp.class);
    private final OwnerPhoneAccess access = mock(OwnerPhoneAccess.class);
    private final OwnerWhatsAppWebhook webhook = new OwnerWhatsAppWebhook(links,
        new DeliverySettings("console", ACCOUNT, TOKEN, SANDBOX, "https://fixture.example", ""), access);

    OwnerWhatsAppWebhookTest() {
        when(links.enabled()).thenReturn(true);
        when(links.webhook()).thenReturn(URL);
    }

    @Test
    void rejectsTamperingBeforeResolvingASender() throws Exception {
        var request = signed("PIN");
        request.setParameter("From", "whatsapp:+12025550173");

        assertThatThrownBy(() -> webhook.inbound(request)).isInstanceOfSatisfying(
            ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(access);
    }

    @Test
    void anUnlinkedSenderNeverGetsAPhoneCode() throws Exception {
        when(links.firstInbound(MESSAGE)).thenReturn(true);

        assertThat(webhook.inbound(signed("PIN"))).isEqualTo("<Response/>");
        verifyNoInteractions(access);
    }

    @Test
    void verifiedHindiPinUsesTheBoundTenantAndReplayDoesNotReturnTheCode() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(links.firstInbound(MESSAGE)).thenReturn(true, false);
        when(links.reservePin(SENDER, "fixture-shop")).thenReturn(new OwnerWhatsApp.ThreadOwner(tenant, "fixture-shop"));
        when(access.issue(tenant)).thenReturn(new OwnerPhoneAccess.IssuedCode("12345678", Instant.now().plusSeconds(600)));

        assertThat(webhook.inbound(signed("पिन fixture-shop"))).contains("12345678", "10 minutes");
        assertThat(webhook.inbound(signed("पिन fixture-shop"))).isEqualTo("<Response/>");
        verify(access).issue(tenant);
    }

    private static MockHttpServletRequest signed(String body) throws Exception {
        var fields = new TreeMap<>(Map.of("AccountSid", ACCOUNT, "MessageSid", MESSAGE, "From", "whatsapp:" + SENDER,
            "To", SANDBOX, "Body", body));
        var request = new MockHttpServletRequest("POST", OwnerWhatsApp.INBOUND_PATH);
        StringBuilder canonical = new StringBuilder(URL);
        fields.forEach((name, value) -> {
            request.addParameter(name, value);
            canonical.append(name).append(value);
        });
        var signer = Mac.getInstance("HmacSHA1");
        signer.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        request.addHeader("X-Twilio-Signature",
            Base64.getEncoder().encodeToString(signer.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8))));
        return request;
    }
}
