package com.rushpromise.booking;

import com.rushpromise.platform.SarvamShopRouting;
import com.rushpromise.platform.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
@Profile("!worker")
class NativePickupIdentity {
    private final SarvamShopRouting routing;
    private final byte[] key;

    NativePickupIdentity(SarvamShopRouting routing, @Value("${RUSH_SARVAM_TOOL_TOKEN:}") String token) {
        this.routing = routing;
        this.key = token.getBytes(StandardCharsets.UTF_8);
    }

    boolean validRoute(String sessionId) {
        if (key.length == 0 || TenantContext.legacy() || sessionId == null
                || !(sessionId.startsWith("sarvam:") || sessionId.startsWith("sarvam64:"))) return false;
        var route = routing.route(sessionId);
        return route != null && route.schema().equals(TenantContext.schema());
    }

    String subject(String sessionId, String providerSubject) {
        if (!isSubject(providerSubject) || !validRoute(sessionId)) return null;
        return hmac("rush-promise/native-pickup/sarvam/v1", providerSubject);
    }

    String interactionSubject(String sessionId) {
        return validRoute(sessionId) ? hmac("rush-promise/native-pickup/sarvam-interaction/v1", sessionId) : null;
    }

    private String hmac(String purpose, String subject) {
        String context = purpose + "\n" + TenantContext.schema() + "\n" + subject;
        try {
            var hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(context.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Native pickup identity cannot be bound", failure);
        }
    }

    static boolean isSubject(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
