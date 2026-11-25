package com.rushpromise.delivery;

import com.rushpromise.platform.OwnerPhoneAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

@RestController
@Profile("!worker")
class OwnerWhatsAppWebhook {
    private static final Pattern LINK = Pattern.compile("LINK (RP[0-9A-F]{14})");
    private static final Pattern PIN = Pattern.compile("(?:PIN|पिन)(?: ([a-z0-9]+(?:-[a-z0-9]+)*))?", Pattern.CASE_INSENSITIVE);
    private final OwnerWhatsApp links;
    private final DeliverySettings settings;
    private final OwnerPhoneAccess access;

    OwnerWhatsAppWebhook(OwnerWhatsApp links, DeliverySettings settings, OwnerPhoneAccess access) {
        this.links = links;
        this.settings = settings;
        this.access = access;
    }

    @PostMapping(path = OwnerWhatsApp.INBOUND_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_XML_VALUE)
    String inbound(HttpServletRequest request) {
        if (!links.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        String signature = request.getHeader("X-Twilio-Signature");
        Map<String, String[]> parameters = new TreeMap<>(request.getParameterMap());
        if (request.getQueryString() != null || signature == null
                || !TwilioStatusController.validSignature(links.webhook(), parameters, settings.authToken(), signature)
                || !settings.accountSid().equals(single(parameters, "AccountSid"))
                || !settings.from().equals(single(parameters, "To"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String sid = single(parameters, "MessageSid");
        String from = single(parameters, "From");
        String body = single(parameters, "Body");
        if (!TwilioWhatsApp.validSid(sid) || from == null || !from.matches("whatsapp:\\+[1-9][0-9]{7,14}")
                || body == null || body.length() > 1600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!links.firstInbound(sid)) return response(null);
        String recipient = from.substring("whatsapp:".length());
        String command = body.strip().replaceAll("\\s+", " ");
        var link = LINK.matcher(command.toUpperCase(Locale.ROOT));
        if (link.matches()) {
            return response(links.link(link.group(1), recipient)
                ? "WhatsApp linked for new orders. Send PIN to get a one-time owner call code. A message from you keeps the 24-hour conversation open."
                : "This link code could not be used. Generate a new code in your shop settings.");
        }

        links.refreshWindow(recipient);
        var pin = PIN.matcher(command);
        if (!pin.matches()) return response(null);
        String slug = pin.group(1) == null ? "" : pin.group(1).toLowerCase(Locale.ROOT);
        var owner = links.reservePin(recipient, slug);
        if (owner == null) return response(null);
        try {
            var issued = access.issue(owner.tenantId());
            return response("Your one-time owner call code is " + issued.accessCode() + ". It expires in 10 minutes.");
        } catch (ResponseStatusException unavailable) {
            return response("A new owner code is unavailable right now. Try again in a few minutes.");
        }
    }

    private static String single(Map<String,String[]> fields, String key) {
        String[] entries = fields.get(key);
        return entries != null && entries.length == 1 ? entries[0] : null;
    }

    private static String response(String text) {
        // The only variable inserted in a response is the numeric, short-lived phone code.
        return text == null ? "<Response/>" : "<Response><Message>" + text + "</Message></Response>";
    }
}
