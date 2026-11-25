package com.rushpromise.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@RestController
@Profile("!worker")
class VoiceController {
    private final SarvamSpeech speech;
    private final VoiceSessionService sessions;
    private final VoicePrompts prompts;
    private final String sarvamBearer;
    private final String demoHandset;
    private final Semaphore browserSpeech = new Semaphore(6, true);

    VoiceController(SarvamSpeech speech, VoiceSessionService sessions, VoicePrompts prompts,
                    @Value("${RUSH_SARVAM_TOOL_TOKEN:}") String sarvamBearer,
                    @Value("${RUSH_DEMO_HANDSET:}") String demoHandset) {
        this.speech = speech;
        this.sessions = sessions;
        this.prompts = prompts;
        this.sarvamBearer = sarvamBearer;
        this.demoHandset = demoHandset;
    }

    @PostMapping(path = "/api/voice/browser/turn", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<BrowserReply> browserTurn(@RequestParam UUID sessionId,
                                             @RequestParam UUID turnId,
                                             @RequestParam int sequence,
                                             @RequestParam(required = false) UUID confirmationQuoteId,
                                             @RequestParam String customerName,
                                             @RequestParam String phone,
                                             @RequestParam(defaultValue = "false") boolean receiptOnly,
                                             @RequestPart(value = "audio", required = false) MultipartFile audio) {
        var turn = turn("browser:" + sessionId, turnId.toString(), sequence, confirmationQuoteId, customerName, phone);
        if (!demoHandset.isBlank() && !demoHandset.equals(turn.phone())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This demo uses the enrolled handset");
        }
        if (!receiptOnly && audio == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An audio recording is required for a new turn");
        }
        if (!receiptOnly && !speech.configured()) throw unavailable();
        if (receiptOnly) {
            var reply = receipt(turn);
            return privateResponse(new BrowserReply(reply, prompts.audio(reply.state(), reply.language()), true));
        }
        if (!browserSpeech.tryAcquire()) {
            var reply = new VoiceSessionService.Reply("BUSY", "", "Voice is busy. Please retry this turn.", "hi-IN", null, null);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                    .body(new BrowserReply(reply, prompts.audio("BUSY", "hi-IN"), true));
        }
        try {
            var reply = run(turn, () -> speech.transcribe(audio));
            if ("CHECKING_BOOKING".equals(reply.state())) {
                return privateResponse(new BrowserReply(reply, prompts.audio(reply.state(), reply.language()), true));
            }
            try {
                return privateResponse(new BrowserReply(reply, speech.synthesize(reply.replyText(), reply.language()), false));
            } catch (SarvamSpeech.SpeechUnavailable ignored) {
                return privateResponse(new BrowserReply(reply, prompts.audio(reply.state(), reply.language()), true));
            }
        } finally {
            browserSpeech.release();
        }
    }

    @PostMapping(path = "/api/voice/sarvam/turn", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VoiceSessionService.Reply> sarvamTurn(@RequestHeader(value = "Authorization", required = false) String auth,
                                                         @RequestBody SarvamTurn request) {
        if (sarvamBearer.isBlank() || !matchesBearer(auth)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!identifier(request.interactionId(), 110)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid interaction identifier");
        }
        String sessionId = "sarvam:" + request.interactionId();
        if ("end".equals(request.event())) {
            try {
                return privateResponse(sessions.end(sessionId));
            } catch (VoiceSessionService.VoiceBusy ex) {
                throw busy();
            }
        }
        String phone = normalizePhone(request.phone());
        String turnId = request.turnId();
        if (turnId == null || turnId.isBlank()) turnId = "turn-" + request.sequence();
        var turn = turn(sessionId, turnId, request.sequence(), request.confirmationQuoteId(),
                request.customerName() == null ? "" : request.customerName(), phone);
        if ("poll".equals(request.event())) return privateResponse(receipt(turn));
        String utterance = request.utterance() == null ? "" : request.utterance().strip();
        return privateResponse(run(turn, () -> new SarvamSpeech.Transcript(utterance, "hi-IN")));
    }

    private VoiceSessionService.Reply receipt(VoiceSessionService.Turn turn) {
        try {
            return sessions.poll(turn.sessionId(), turn.turnId(), turn.phone());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private VoiceSessionService.Reply run(VoiceSessionService.Turn turn, VoiceSessionService.Utterance source) {
        try {
            return sessions.turn(turn, source);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (SarvamSpeech.SpeechUnavailable ex) {
            throw unavailable();
        } catch (VoiceSessionService.VoiceBusy ex) {
            throw busy();
        }
    }

    private boolean matchesBearer(String header) {
        return header != null && MessageDigest.isEqual(
                ("Bearer " + sarvamBearer).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }

    private static VoiceSessionService.Turn turn(String id, String turnId, int sequence, UUID quoteId, String name, String phone) {
        String normalizedPhone = normalizePhone(phone);
        String customerName = name.strip();
        if (!identifier(turnId, 100) || sequence < 1 || sequence > 1000 || customerName.length() > 100
                || !normalizedPhone.matches("\\+[1-9][0-9]{7,14}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the order name, phone and turn details");
        }
        return new VoiceSessionService.Turn(id, turnId, sequence, quoteId, customerName, normalizedPhone);
    }

    private static String normalizePhone(String value) {
        String phone = value == null ? "" : value.replaceAll("[ ()-]", "");
        if (phone.matches("[6-9][0-9]{9}")) return "+91" + phone;
        if (phone.matches("91[6-9][0-9]{9}")) return "+" + phone;
        return phone;
    }

    private static boolean identifier(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Za-z0-9._:-]+");
    }

    private static <T> ResponseEntity<T> privateResponse(T response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Speech is temporarily unavailable.");
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Voice is busy. Please try again shortly.");
    }

    record BrowserReply(VoiceSessionService.Reply turn, String audioWavBase64, boolean audioFallback) {}
    record SarvamTurn(String event, String interactionId, String turnId, int sequence, UUID confirmationQuoteId,
                      String customerName, String phone, String utterance) {}
}
