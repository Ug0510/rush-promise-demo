package com.rushpromise.voice;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.rushpromise.booking.BookingModels.NativeIdentityKind;
import com.rushpromise.booking.GuestCheckouts;
import com.rushpromise.platform.PlatformContextFilter;
import com.rushpromise.platform.SarvamShopRouting;
import com.rushpromise.platform.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import static com.rushpromise.booking.BookingModels.TEXT_CHECKOUT_SESSION_PREFIX;

@RestController
@Profile("!worker")
class VoiceController {
    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);
    private static final Pattern NATIVE_SUBJECT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern EMBEDDED_NATIVE_SUBJECT = Pattern.compile("(?<![0-9A-Fa-f])[0-9A-Fa-f]{64}(?![0-9A-Fa-f])");
    private static final Set<String> TOOL_FIELDS = Set.of(
            "event", "interactionId", "phone", "turnId", "sequence", "confirmationQuoteId", "customerName", "utterance", "language", "shopSlug", "routed", "pickupMode");
    private final SarvamSpeech speech;
    private final VoiceSessionService sessions;
    private final VoicePrompts prompts;
    private final VoiceConversation conversation;
    private final SarvamShopRouting routing;
    private final GuestCheckouts guestCheckouts;
    private final String sarvamBearer;
    private final String demoHandset;
    private final Semaphore browserSpeech = new Semaphore(6, true);

    VoiceController(SarvamSpeech speech, VoiceSessionService sessions, VoicePrompts prompts, VoiceConversation conversation,
                    SarvamShopRouting routing, GuestCheckouts guestCheckouts,
                    @Value("${RUSH_SARVAM_TOOL_TOKEN:}") String sarvamBearer,
                    @Value("${RUSH_DEMO_HANDSET:}") String demoHandset) {
        this.speech = speech;
        this.sessions = sessions;
        this.prompts = prompts;
        this.conversation = conversation;
        this.routing = routing;
        this.guestCheckouts = guestCheckouts;
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
        if (TenantContext.legacy() && !demoHandset.isBlank() && !demoHandset.equals(turn.phone())) {
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

    @PostMapping("/api/checkout/guest")
    ResponseEntity<GuestCheckouts.Credential> guestCheckout(HttpServletRequest servlet) {
        PlatformContextFilter.requireSameOrigin(servlet);
        return privateResponse(guestCheckouts.issue());
    }

    @PostMapping(path = "/api/checkout/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VoiceSessionService.Reply> textCheckout(@RequestBody TextTurn request, HttpServletRequest servlet) {
        if (TenantContext.legacy()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        PlatformContextFilter.requireSameOrigin(servlet);
        if (request.sessionId() == null || request.turnId() == null || (request.confirm() && request.confirmationQuoteId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the checkout details and current quote");
        }
        var currentOffer = request.confirm() ? request.confirmationQuoteId() : null;
        var input = textTurn(request, currentOffer);
        if (request.receiptOnly()) return privateResponse(receipt(input));
        String spoken = request.confirm() ? "yes" : request.utterance();
        if (spoken == null || spoken.isBlank() || spoken.length() > 1500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a pickup request");
        }
        String language = "hi-IN".equalsIgnoreCase(request.language()) ? "hi-IN" : "en-IN";
        return privateResponse(run(input, () -> new SarvamSpeech.Transcript(spoken, language)));
    }

    private VoiceSessionService.Turn textTurn(TextTurn request, UUID currentOffer) {
        String name = request.customerName() == null ? "" : request.customerName().strip();
        if (request.guestToken() == null && request.phone() != null) {
            return turn(TEXT_CHECKOUT_SESSION_PREFIX + request.sessionId(), request.turnId().toString(), request.sequence(),
                    currentOffer, name, request.phone());
        }
        if ((request.phone() != null && !request.phone().isBlank()) || request.sequence() < 1 || request.sequence() > 1000
                || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the guest checkout details");
        }
        String subject = guestCheckouts.authenticate(request.sessionId(), request.guestToken());
        return new VoiceSessionService.Turn(GuestCheckouts.SESSION_PREFIX + request.sessionId(), request.turnId().toString(),
                request.sequence(), currentOffer, name, null, subject, NativeIdentityKind.GUEST);
    }

    @PostMapping(path = "/api/voice/sarvam/turn", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> sarvamTurn(@RequestHeader(value = "Authorization", required = false) String auth,
                                                         @RequestBody SarvamTurn request) {
        boolean workspaceAuth = !sarvamBearer.isBlank() && matchesBearer(auth);
        if (!TenantContext.nativeAuthorized() && !workspaceAuth) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String language = "en-IN".equalsIgnoreCase(request.language()) ? "en-IN" : "hi-IN";
        if ("directory".equals(request.event())) {
            if (!workspaceAuth) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            return privateResponse(request.shopQuery() == null || request.shopQuery().isBlank()
                    ? selection("SHOP_SELECTION_REQUIRED", language)
                    : directoryReply(routing.resolveName(request.shopQuery()), language));
        }
        boolean selecting = "select-shop".equals(request.event());
        boolean readOnly = "start".equals(request.event()) || "menu".equals(request.event());
        if (readOnly && !request.routed() && (request.interactionId() == null || request.interactionId().isBlank())) {
            return nativeConversation(request, language, null, false, false);
        }
        String sessionId;
        try {
            sessionId = nativeSessionId(request.interactionId());
        } catch (ResponseStatusException invalid) {
            logNativeRejection("interaction", request);
            throw invalid;
        }
        if (selecting) {
            if (!workspaceAuth || !request.routed()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            String slug = request.shopSlug();
            String query = request.shopQuery();
            if ((query == null || query.isBlank()) && (slug == null || slug.isBlank())) query = request.utterance();
            if (query != null && !query.isBlank()) {
                var matches = routing.resolveName(query);
                if (matches.size() != 1) return privateResponse(directoryReply(matches, language));
                slug = matches.getFirst().slug();
            }
            var chosen = routing.choose(sessionId, slug);
            if (chosen == null) return privateResponse(selection("SHOP_SELECTION_REQUIRED", language));
            try (var ignored = TenantContext.enter(chosen.schema())) {
                var greeting = conversation.greet(language);
                String state = chosen.slug().equals(slug) ? "SHOP_SELECTED" : "SHOP_ALREADY_SELECTED";
                return privateResponse(new ShopChoiceReply(state, language, greeting.replyText(), greeting.shopName(), null));
            }
        }
        var pin = routing.route(sessionId);
        if (pin != null) {
            if (TenantContext.nativeAuthorized() && !pin.schema().equals(TenantContext.schema())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            try (var ignored = TenantContext.enter(pin.schema())) {
                return nativeConversation(request, language, sessionId, true, workspaceAuth && request.routed());
            }
        }
        if (request.routed()) return privateResponse(selection("SHOP_SELECTION_REQUIRED", language));
        return nativeConversation(request, language, sessionId, false, false);
    }

    private ShopChoiceReply directoryReply(List<SarvamShopRouting.Choice> choices, String language) {
        boolean hindi = "hi-IN".equals(language);
        var candidates = choices.stream().limit(5).toList();
        if (candidates.isEmpty()) return new ShopChoiceReply("SHOP_NOT_FOUND", language,
                hindi ? "मुझे यह दुकान नहीं मिली। कृपया दुकान का पूरा नाम और इलाका बताइए।"
                      : "I couldn't find that shop. Please say the full shop name and area.", null, candidates);
        if (candidates.size() == 1) return new ShopChoiceReply("SHOP_MATCH", language,
                hindi ? "यह दुकान मिली। क्या आपने इसी दुकान का नाम कहा था?" : "I found one shop. Is this the one you meant?", null, candidates);
        String names = String.join(" / ", candidates.stream().map(SarvamShopRouting.Choice::spokenLabel).toList());
        String label = hindi ? "इस नाम की एक से ज़्यादा दुकानें हैं। कृपया इलाका या दुकान के लिंक में दिया नाम बताइए। "
                             : "More than one shop matches. Please say the area or the name shown in the shop's link. ";
        return new ShopChoiceReply("SHOP_AMBIGUOUS", language, label + names, null, candidates);
    }

    private ShopChoiceReply selection(String state, String language) {
        List<SarvamShopRouting.Choice> shops = routing.choices();
        String names = String.join(" / ", shops.stream().map(SarvamShopRouting.Choice::displayName).toList());
        boolean hindi = "hi-IN".equals(language);
        String reply = shops.isEmpty() ? (hindi ? "अभी दुकानें उपलब्ध नहीं हैं।" : "The shops aren't available right now.")
                : hindi ? "नमस्ते, यह Rush Promise है। आप किस दुकान से ऑर्डर करना चाहेंगे? " + names + "।"
                : "Hello, this is Rush Promise. Which shop would you like to order from? " + names + ".";
        return new ShopChoiceReply(state, language, reply, null, shops);
    }

    private ResponseEntity<?> nativeConversation(SarvamTurn request, String language, String sessionId,
                                                 boolean pinned, boolean acceptNativeSubject) {
        if ("start".equals(request.event())) return privateResponse(conversation.greet(language));
        if ("menu".equals(request.event())) return privateResponse(conversation.menu(language));
        if ("end".equals(request.event())) {
            try {
                return privateResponse(sessions.end(sessionId));
            } catch (VoiceSessionService.VoiceBusy ex) {
                throw busy();
            }
        }
        String input = request.utterance() == null ? "" : request.utterance().strip();
        String utterance = input.length() > 1500 ? "" : input;
        if ("interaction".equals(request.pickupMode())) {
            if (pinned && acceptNativeSubject) return nativePickup(request, sessionId, null, utterance, language);
            return privateResponse(conversation.previewWithoutIdentity(utterance, language));
        }
        if (request.pickupMode() != null && !request.pickupMode().isBlank()) {
            return privateResponse(conversation.previewWithoutIdentity(utterance, language));
        }
        if (pinned) {
            String rawSubject = request.phone();
            String subject = rawSubject == null ? "" : rawSubject.strip();
            boolean nativeSubject = NATIVE_SUBJECT.matcher(subject).matches();
            if (!acceptNativeSubject || !nativeSubject) {
                String shape = subject.isEmpty() ? "missing" : nativeSubject ? "opaque"
                        : validPhone(normalizePhone(subject)) ? "phone-shaped" : "other";
                log.info("Pinned Sarvam turn is read-only: nativeSource={} subjectShape={}", acceptNativeSubject, shape);
                if ("other".equals(shape)) describeNativeSubjectShape(rawSubject, subject);
                return privateResponse(conversation.previewWithoutIdentity(utterance, language));
            }
            return nativePickup(request, sessionId, subject, utterance, language);
        }
        String phone = normalizePhone(request.phone());
        if (!validPhone(phone)) return privateResponse(conversation.previewWithoutIdentity(utterance, language));
        if (request.sequence() == null) {
            logNativeRejection("sequence", request);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing turn sequence");
        }
        String turnId = request.turnId();
        if (turnId == null || turnId.isBlank()) turnId = "turn-" + request.sequence();
        VoiceSessionService.Turn turn;
        try {
            turn = turn(sessionId, turnId, request.sequence(), request.confirmationQuoteId(),
                    request.customerName() == null ? "" : request.customerName(), phone);
        } catch (ResponseStatusException invalid) {
            logNativeRejection("turn", request);
            throw invalid;
        }
        if ("poll".equals(request.event())) return privateResponse(receipt(turn));
        return privateResponse(run(turn, () -> new SarvamSpeech.Transcript(utterance, language)));
    }

    private static void describeNativeSubjectShape(String raw, String subject) {
        boolean quoted = subject.length() >= 2 && (subject.startsWith("\"") && subject.endsWith("\"")
                || subject.startsWith("'") && subject.endsWith("'"));
        boolean template = subject.contains("{{") || subject.contains("${") || subject.startsWith("@");
        boolean envelope = subject.startsWith("{") && subject.endsWith("}") || subject.startsWith("[") && subject.endsWith("]");
        log.info("Sarvam subject shape: length={} stripped={} quoted={} template={} envelope={} hasHex64={} ascii={} punctuation={}",
                raw.length(), raw.length() - subject.length(), quoted, template, envelope,
                EMBEDDED_NATIVE_SUBJECT.matcher(subject).find(), subject.chars().allMatch(ch -> ch < 128),
                punctuationClasses(subject));
    }

    private ResponseEntity<VoiceSessionService.Reply> nativePickup(SarvamTurn request, String sessionId, String subject,
                                                                   String utterance, String language) {
        Integer sequence = request.sequence();
        if (sequence == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing turn sequence");
        String turnId = request.turnId();
        if (turnId == null || turnId.isBlank()) turnId = "turn-" + sequence;
        String name = request.customerName() == null ? "" : request.customerName().strip();
        if (!identifier(turnId, 100) || sequence < 1 || sequence > 1000 || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid native pickup turn");
        }
        try {
            boolean interaction = "interaction".equals(request.pickupMode());
            if ("poll".equals(request.event())) {
                return privateResponse(interaction ? sessions.nativeInteractionPoll(sessionId, turnId)
                        : sessions.nativePickupPoll(sessionId, turnId, subject));
            }
            VoiceSessionService.Utterance spoken = () -> new SarvamSpeech.Transcript(utterance, language);
            return privateResponse(interaction ? sessions.nativeInteractionTurn(sessionId, turnId, sequence, request.confirmationQuoteId(), name, spoken)
                    : sessions.nativePickupTurn(sessionId, turnId, sequence, request.confirmationQuoteId(), name, subject, spoken));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (VoiceSessionService.VoiceBusy ex) {
            throw busy();
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Void> malformedToolBody(HttpMessageNotReadableException invalid) {
        Throwable cause = invalid.getCause();
        String field = "unknown";
        if (cause instanceof JsonMappingException mapping) {
            field = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(name -> name != null && TOOL_FIELDS.contains(name)).findFirst().orElse("unknown");
        }
        log.warn("Voice tool body rejected: format=json field={}", field);
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }

    private static void logNativeRejection(String reason, SarvamTurn request) {
        String id = request.interactionId();
        String idClass = id == null || id.isBlank() ? "missing" : identifier(id, 110) ? "simple"
                : nativeSlash(id) ? "slash" : "unsupported";
        String number = normalizePhone(request.phone());
        String phoneClass = number.isBlank() ? "missing" : validPhone(number) ? "valid" : "invalid";
        Integer sequence = request.sequence();
        String sequenceClass = sequence == null ? "missing" : sequence >= 1 && sequence <= 1000 ? "valid" : "invalid";
        log.warn("Sarvam voice tool rejected: reason={} interaction={} phone={} sequence={}",
                reason, idClass, phoneClass, sequenceClass);
        if ("unsupported".equals(idClass)) {
            int slash = (int) id.chars().filter(ch -> ch == '/').count();
            int safeSegments = (int) java.util.Arrays.stream(id.split("/", -1))
                    .filter(VoiceController::nativeSegment).count();
            log.warn("Sarvam interaction shape: length={} slashes={} safeSegments={} ascii={} whitespace={} control={} punctuation={}",
                    id.length(), slash, safeSegments, id.chars().allMatch(ch -> ch < 128),
                    id.chars().anyMatch(Character::isWhitespace), id.chars().anyMatch(Character::isISOControl),
                    punctuationClasses(id));
        }
    }

    private static String punctuationClasses(String input) {
        var categories = new java.util.ArrayList<String>();
        if (input.indexOf('"') >= 0 || input.indexOf('\'') >= 0) categories.add("quote");
        if (input.indexOf('{') >= 0 || input.indexOf('}') >= 0) categories.add("brace");
        if (input.indexOf('[') >= 0 || input.indexOf(']') >= 0) categories.add("bracket");
        if (input.indexOf('<') >= 0 || input.indexOf('>') >= 0) categories.add("angle");
        if (input.indexOf('@') >= 0) categories.add("at");
        if (input.indexOf('$') >= 0) categories.add("dollar");
        if (input.indexOf('%') >= 0) categories.add("percent");
        if (input.indexOf('.') >= 0) categories.add("dot");
        if (input.indexOf(':') >= 0) categories.add("colon");
        if (input.indexOf('\\') >= 0) categories.add("backslash");
        if (input.indexOf('|') >= 0) categories.add("pipe");
        if (input.indexOf('=') >= 0) categories.add("equals");
        if (input.indexOf('+') >= 0) categories.add("plus");
        if (input.indexOf('#') >= 0) categories.add("hash");
        return categories.isEmpty() ? "none" : String.join(",", categories);
    }

    private VoiceSessionService.Reply receipt(VoiceSessionService.Turn turn) {
        try {
            return sessions.poll(turn.sessionId(), turn.turnId(), turn.contactBinding());
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
                || !validPhone(normalizedPhone)) {
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

    private static boolean validPhone(String phone) {
        return phone.matches("\\+[1-9][0-9]{7,14}");
    }

    static boolean nativeSlash(String id) {
        if (id == null || id.length() > 110) return false;
        String[] segments = id.split("/", -1);
        return segments.length == 2 && nativeSegment(segments[0]) && nativeSegment(segments[1]);
    }

    private static boolean nativeSegment(String segment) {
        return identifier(segment, 110) && segment.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static String nativeSessionId(String interactionId) {
        if (identifier(interactionId, 110)) return "sarvam:" + interactionId;
        if (nativeSlash(interactionId)) {
            // Preserve both provider segments, including case, without introducing slash into booking identifiers.
            return "sarvam64:" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(interactionId.getBytes(StandardCharsets.US_ASCII));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid interaction identifier");
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
    record TextTurn(UUID sessionId, UUID turnId, int sequence, UUID confirmationQuoteId, String customerName, String phone,
                    String guestToken, String utterance, String language, boolean confirm, boolean receiptOnly) {}
    record SarvamTurn(String event, String interactionId, String turnId, Integer sequence,
                      @JsonDeserialize(using = OptionalNativeQuoteId.class) UUID confirmationQuoteId,
                      String customerName, String phone, String utterance, String language, String shopSlug, boolean routed,
                      String pickupMode, String shopQuery) {
        SarvamTurn(String event, String interactionId, String turnId, Integer sequence, UUID confirmationQuoteId,
                   String customerName, String phone, String utterance, String language, String shopSlug, boolean routed, String pickupMode) {
            this(event, interactionId, turnId, sequence, confirmationQuoteId, customerName, phone, utterance, language, shopSlug, routed, pickupMode, null);
        }
        SarvamTurn(String event, String interactionId, String turnId, Integer sequence, UUID confirmationQuoteId,
                   String customerName, String phone, String utterance, String language, String shopSlug, boolean routed) {
            this(event, interactionId, turnId, sequence, confirmationQuoteId, customerName, phone, utterance, language, shopSlug, routed, null);
        }
        SarvamTurn(String event, String interactionId, String turnId, Integer sequence, UUID confirmationQuoteId,
                   String customerName, String phone, String utterance, String language) {
            this(event, interactionId, turnId, sequence, confirmationQuoteId, customerName, phone, utterance, language, null, false);
        }
    }

    record ShopChoiceReply(String state, String language, String replyText, String shopName,
                           List<SarvamShopRouting.Choice> shops) {}

    static final class OptionalNativeQuoteId extends StdDeserializer<UUID> {
        private static final Pattern QUOTE_ID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

        OptionalNativeQuoteId() { super(UUID.class); }

        @Override
        public UUID deserialize(JsonParser input, DeserializationContext context) throws IOException {
            if (!input.hasToken(JsonToken.VALUE_STRING)) {
                input.skipChildren();
                return null;
            }
            String supplied = input.getText();
            return QUOTE_ID.matcher(supplied).matches() ? UUID.fromString(supplied) : null;
        }
    }
}
