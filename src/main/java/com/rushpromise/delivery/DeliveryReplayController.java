package com.rushpromise.delivery;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@Profile("!worker")
class DeliveryReplayController {
    private final OutboxRepository outbox;

    DeliveryReplayController(OutboxRepository outbox) { this.outbox = outbox; }

    @PostMapping("/api/messages/{id}/{action:retry|resend}")
    ResponseEntity<Map<String, String>> replay(@PathVariable UUID id, @PathVariable String action,
                                              @RequestBody(required = false) Review review) {
        var result = outbox.requestReplay(id, "resend".equals(action), review != null && review.reviewed());
        return ResponseEntity.status(result.status()).body(Map.of("code", result.code()));
    }

    record Review(boolean reviewed) {}
}
