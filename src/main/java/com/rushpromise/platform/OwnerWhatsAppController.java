package com.rushpromise.platform;

import com.rushpromise.delivery.OwnerWhatsApp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@Profile("!worker")
class OwnerWhatsAppController {
    private final OwnerWhatsApp whatsapp;

    OwnerWhatsAppController(OwnerWhatsApp whatsapp) { this.whatsapp = whatsapp; }

    @GetMapping("/api/owner/whatsapp")
    OwnerWhatsApp.View status(HttpServletRequest request) { return whatsapp.status(tenant(request)); }

    @PostMapping("/api/owner/whatsapp/link")
    OwnerWhatsApp.LinkCode link(HttpServletRequest request) {
        PlatformContextFilter.requireSameOrigin(request);
        return whatsapp.issue(tenant(request));
    }

    @PostMapping("/api/owner/whatsapp/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(HttpServletRequest request) {
        PlatformContextFilter.requireSameOrigin(request);
        whatsapp.unlink(tenant(request));
    }

    private static UUID tenant(HttpServletRequest request) {
        var owner = PlatformContextFilter.principal(request);
        if (owner == null || owner.shop() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to your shop");
        }
        return owner.shop().id();
    }
}
