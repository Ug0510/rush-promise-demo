package com.rushpromise.voice;

import com.rushpromise.platform.OwnerPhoneInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
@Profile("!worker")
class OwnerPhoneController {
    private final OwnerPhoneInventory inventory;
    private final byte[] bearer;

    OwnerPhoneController(OwnerPhoneInventory inventory, @Value("${RUSH_SARVAM_TOOL_TOKEN:}") String token) {
        this.inventory = inventory;
        bearer = token.isBlank() ? new byte[0] : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path="/api/voice/sarvam/owner",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OwnerPhoneInventory.Reply> owner(@RequestBody OwnerPhoneInventory.Command command,
                                                       @RequestHeader(name="Authorization",required=false) String authorization) {
        if (bearer.length==0 || authorization==null || !MessageDigest.isEqual(bearer,authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String interaction = command.interactionId();
        if (interaction == null || interaction.length() != 35 || interaction.charAt(8) != '/' || !VoiceController.nativeSlash(interaction)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A provider interaction is required");
        }
        String pin = "authenticate".equals(command.event()) ? digits(command.accessCode()) : null;
        String consent = "approve".equals(command.event()) ? approval(command.utterance()) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inventory.handle(hash(interaction),command,pin,consent));
    }

    private static String digits(String input) {
        if (input==null || input.length()>32 || input.codePoints().anyMatch(c->!Character.isWhitespace(c) && Character.digit(c,10)<0)) return null;
        var result=new StringBuilder(8);
        input.codePoints().filter(c->!Character.isWhitespace(c)).forEach(c->result.append(Character.digit(c,10)));
        return result.toString();
    }

    private static String approval(String actualTurn) {
        if (actualTurn==null || actualTurn.length()>160) return null;
        String normalized = actualTurn.strip().replaceFirst("(?i)^approve code\\b", "confirm code");
        return SpeechIntent.confirmationCode(normalized);
    }

    private static String hash(String interaction) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(interaction.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Provider binding unavailable",impossible); }
    }
}
