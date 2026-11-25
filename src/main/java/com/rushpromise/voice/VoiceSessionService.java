package com.rushpromise.voice;

import com.rushpromise.booking.BookingService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.voice.SpeechIntent.Decision.*;

@Service
@Profile("!worker")
class VoiceSessionService {
    private static final int MAX_SESSIONS = 600;
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(25);
    private static final DateTimeFormatter SPOKEN_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final BookingService booking;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    VoiceSessionService(BookingService booking) {
        this.booking = booking;
    }

    Reply turn(Turn input, Utterance source) {
        Session session = session(input.sessionId());
        synchronized (session) {
            session.lastSeen = Instant.now();
            if (session.ended) {
                return localized(session, "ENDED", "", "This voice session has ended.",
                        "यह कॉल बंद हो चुकी है।", null, null);
            }
            if (session.phone != null && !session.phone.equals(input.phone())) {
                throw new IllegalArgumentException("Phone cannot change during an active order");
            }
            if (session.uncertain != null) return resolve(session, input);
            Cached previous = session.recent.get(input.turnId());
            if (previous != null) {
                if (previous.sequence() != input.sequence()) {
                    throw new IllegalArgumentException("Turn identifier was already used");
                }
                return currentReply(session);
            }
            if (input.sequence() <= session.sequence) return currentReply(session);
            if (session.order != null) return currentReply(session);

            SarvamSpeech.Transcript transcript = source.read();
            String spoken = transcript.text();
            if (spoken.length() > 1500) throw new IllegalArgumentException("Keep the voice turn short");
            session.hindi = !"en-IN".equalsIgnoreCase(transcript.language());
            session.phone = input.phone();
            Reply response;
            try {
                response = apply(session, input, spoken);
            } catch (DataAccessException ex) {
                response = localized(session, "UNAVAILABLE", spoken,
                        "I couldn't check the shop's stock right now. Please try in a moment.",
                        "अभी दुकान का स्टॉक नहीं देख पा रहा हूँ। कृपया थोड़ी देर में कोशिश करें।", null, null);
            }
            var cached = new Cached(input.sequence(), response);
            session.sequence = input.sequence();
            session.last = cached;
            session.recent.put(input.turnId(), cached);
            if (session.recent.size() > 16) session.recent.pollFirstEntry();
            return response;
        }
    }

    Reply end(String sessionId) {
        Session session = session(sessionId);
        synchronized (session) {
            session.ended = true;
            return localized(session, "ENDED", "", "This voice session has ended.",
                    "यह कॉल बंद हो चुकी है।", null, null);
        }
    }

    private synchronized Session session(String key) {
        Session existing = sessions.get(key);
        if (existing != null && existing.lastSeen.isAfter(Instant.now().minus(SESSION_LIFETIME))) return existing;
        Instant expiredBefore = Instant.now().minus(SESSION_LIFETIME);
        sessions.entrySet().removeIf(entry -> entry.getValue().lastSeen.isBefore(expiredBefore));
        if (sessions.size() >= MAX_SESSIONS) throw new VoiceBusy();
        return sessions.computeIfAbsent(key, ignored -> new Session());
    }

    private Reply apply(Session session, Turn input, String spoken) {
        if (spoken.isBlank()) return currentPrompt(session, spoken);
        var intent = SpeechIntent.parse(spoken);
        if (intent.decision() == NO) {
            session.quote = null;
            return localized(session, "COLLECTING", spoken,
                    "Okay, I haven't booked anything. Tell me what you'd like instead.",
                    "ठीक है, कुछ बुक नहीं हुआ। बताइए आपको क्या चाहिए।", null, null);
        }
        if (intent.decision() == YES) {
            if (session.quote == null || !session.quote.id().equals(input.confirmationQuoteId())) {
                return currentPrompt(session, spoken);
            }
            Quote accepted = session.quote;
            Outcome result = booking.accept(accepted.id().toString(),
                    new AcceptRequest(input.sessionId(), input.turnId()), null);
            if ("BOOKING_UNCERTAIN".equals(result.code())) {
                session.uncertain = new Pending(accepted.id(), input.turnId());
                return uncertainReply(session, spoken);
            }
            return processOutcome(session, spoken, result);
        }
        if (intent.decision() == UNCLEAR) return currentPrompt(session, spoken);

        if (intent.sku() != null) session.sku = intent.sku();
        if (intent.grams() != null) session.grams = intent.grams();
        if (intent.pickupTime() != null) session.pickupTime = intent.pickupTime();
        session.quote = null;
        if (session.sku == null || session.grams == null || session.pickupTime == null) {
            return currentPrompt(session, spoken);
        }
        String name = input.customerName().isBlank() ? "Caller" : input.customerName();
        Outcome result = booking.quote(new QuoteRequest(input.sessionId(), name, input.phone(),
                session.sku, session.grams, session.pickupTime));
        return processOutcome(session, spoken, result);
    }

    private Reply resolve(Session session, Turn input) {
        Pending pending = session.uncertain;
        Outcome resolved = booking.resolve(pending.quoteId().toString(),
                new AcceptRequest(input.sessionId(), pending.turnId()), null);
        if ("BOOKING_UNCERTAIN".equals(resolved.code())) return session.last.reply();
        session.uncertain = null;
        Reply reply;
        if ("BOOKING_NOT_FOUND".equals(resolved.code())) {
            session.quote = null;
            reply = localized(session, "COLLECTING", "",
                    "The earlier order wasn't booked. Ask for another quote when you're ready.",
                    "पिछला ऑर्डर बुक नहीं हुआ। तैयार हों तो फिर से समय पूछिए।", null, null);
        } else {
            reply = processOutcome(session, "", resolved);
        }
        session.sequence = Math.max(session.sequence, input.sequence());
        session.last = new Cached(input.sequence(), reply);
        session.recent.put(input.turnId(), session.last);
        return reply;
    }

    private Reply processOutcome(Session session, String transcript, Outcome outcome) {
        session.quote = outcome.quote();
        if (outcome.order() != null) {
            session.order = outcome.order();
            long number = session.order.id();
            boolean approval = "AWAITING_APPROVAL".equals(session.order.status());
            String time = spokenTime(session.order.pickupTime());
            return localized(session, session.order.status(), transcript,
                    approval ? "Request " + number + " is waiting for shop approval."
                            : "Confirmed. Your order number is " + number + ". Pickup at " + time + ".",
                    approval ? "नंबर " + number + " दुकान की मंज़ूरी का इंतज़ार कर रहा है।"
                            : "ऑर्डर पक्का है। आपका ऑर्डर नंबर " + number + " है। पिकअप " + time + " बजे है।",
                    null, session.order);
        }
        if (session.quote != null) {
            session.sku = session.quote.sku();
            session.grams = session.quote.grams();
            session.pickupTime = session.quote.pickupTime();
            return readback(session, transcript, outcome.code(), outcome.reason());
        }
        return switch (outcome.code()) {
            case "UNSUPPORTED_WEIGHT", "TOO_LARGE" -> localized(session, "COLLECTING", transcript,
                    "That weight isn't available. Try 250 grams, 500 grams, or a whole kilo amount.",
                    "यह वज़न उपलब्ध नहीं है। ढाई सौ ग्राम, आधा किलो, या पूरे किलो का वज़न बताइए।", null, null);
            case "PENDING_APPROVAL_LIMIT" -> localized(session, "COLLECTING", transcript,
                    "This number already has an order awaiting shop approval.",
                    "इस नंबर पर एक ऑर्डर पहले से दुकान की मंज़ूरी का इंतज़ार कर रहा है।", null, null);
            default -> localized(session, "COLLECTING", transcript,
                    "I don't have an available box for that request. Tell me another item, amount, or time.",
                    "इस ऑर्डर के लिए डिब्बा उपलब्ध नहीं है। कोई और मिठाई, वज़न या समय बताइए।", null, null);
        };
    }

    private Reply currentReply(Session session) {
        return session.last == null ? currentPrompt(session, "") : session.last.reply();
    }

    private Reply currentPrompt(Session session, String transcript) {
        if (session.quote != null) return readback(session, transcript, "QUOTED", null);
        if (session.sku == null) return localized(session, "COLLECTING", transcript,
                "Which sweet would you like, kaju katli or barfi?",
                "आपको कौन सी मिठाई चाहिए, काजू कतली या बर्फी?", null, null);
        if (session.grams == null) return localized(session, "COLLECTING", transcript,
                "How much would you like?", "आपको कितना वज़न चाहिए?", null, null);
        if (session.pickupTime == null) return localized(session, "COLLECTING", transcript,
                "What time would you like to pick it up?", "आप कितने बजे पिकअप करेंगे?", null, null);
        return localized(session, "COLLECTING", transcript,
                "Tell me the item, amount and pickup time again.", "मिठाई, वज़न और पिकअप का समय फिर से बताइए।",
                null, null);
    }

    private Reply readback(Session session, String transcript, String code, String reason) {
        Quote quote = session.quote;
        String time = spokenTime(quote.pickupTime());
        String amount = java.math.BigDecimal.valueOf(quote.pricePaise(), 2).stripTrailingZeros().toPlainString();
        String englishLead = "TERMS_CHANGED".equals(code) ? "The shop's terms changed. "
                : reason != null ? "There's a different pickup available. " : "";
        String hindiLead = "TERMS_CHANGED".equals(code) ? "दुकान की शर्तें बदल गई हैं। "
                : reason != null ? "एक दूसरा पिकअप समय उपलब्ध है। " : "";
        String english = englishLead + quote.grams() + " grams of " + quote.itemName() + ", " + amount
                + " rupees, pickup at " + time + ". "
                + (quote.requiresApproval() ? "This needs shop approval. " : "") + "Say yes to confirm.";
        String item = "barfi".equals(quote.sku()) ? "बर्फी" : "काजू कतली";
        String hindi = hindiLead + quote.grams() + " ग्राम " + item + ", " + amount + " रुपये, पिकअप "
                + time + " बजे। " + (quote.requiresApproval() ? "इसके लिए दुकान की मंज़ूरी चाहिए। " : "")
                + "पक्का करने के लिए हाँ कहिए।";
        return localized(session, "AWAITING_CONFIRMATION", transcript, english, hindi, quote, null);
    }

    private static Reply uncertainReply(Session session, String transcript) {
        return localized(session, "CHECKING_BOOKING", transcript,
                "I'm checking whether your order was saved. Please wait before ordering again.",
                "मैं देख रहा हूँ कि आपका ऑर्डर दर्ज हुआ या नहीं। दोबारा ऑर्डर देने से पहले रुकिए।", null, null);
    }

    private static String spokenTime(String time) {
        return LocalTime.parse(time).format(SPOKEN_TIME);
    }

    private static Reply localized(Session session, String state, String transcript,
                                   String english, String hindi, Quote quote, Order order) {
        return render(state, transcript, session.hindi ? hindi : english, session.hindi ? "hi-IN" : "en-IN", quote, order);
    }

    private static Reply render(String state, String transcript, String text, String language, Quote quote, Order order) {
        return new Reply(state, transcript, text, language,
                quote == null ? null : new Offer(quote.id(), quote.itemName(), quote.grams(), quote.pricePaise(),
                        quote.pickupTime(), quote.requiresApproval(), quote.expiresAt()),
                order == null ? null : new SavedOrder(order.id(), order.itemName(), order.grams(),
                        order.pricePaise(), order.pickupTime(), order.status()));
    }

    @FunctionalInterface
    interface Utterance {
        SarvamSpeech.Transcript read();
    }

    record Turn(String sessionId, String turnId, int sequence, UUID confirmationQuoteId, String customerName, String phone) {}
    record Offer(UUID id, String itemName, int grams, int pricePaise, String pickupTime, boolean requiresApproval, Instant expiresAt) {}
    record SavedOrder(long id, String itemName, int grams, int pricePaise, String pickupTime, String status) {}
    record Reply(String state, String transcript, String replyText, String language, Offer quote, SavedOrder order) {}
    private record Pending(UUID quoteId, String turnId) {}
    private record Cached(int sequence, Reply reply) {}
    static final class VoiceBusy extends RuntimeException {}

    private static final class Session {
        private volatile Instant lastSeen = Instant.now();
        private final LinkedHashMap<String, Cached> recent = new LinkedHashMap<>();
        private int sequence;
        private String phone;
        private String sku;
        private Integer grams;
        private String pickupTime;
        private Quote quote;
        private Order order;
        private Pending uncertain;
        private boolean hindi = true;
        private boolean ended;
        private Cached last;
    }
}
