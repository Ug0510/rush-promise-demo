package com.rushpromise.voice;

import com.rushpromise.booking.BookingService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.voice.SpeechIntent.Decision.*;
import static com.rushpromise.voice.VoiceTurnJournal.*;

@Service
@Profile("!worker")
class VoiceSessionService {
    private static final DateTimeFormatter SPOKEN_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private final BookingService booking;
    private final VoiceTurnJournal journal;

    VoiceSessionService(BookingService booking, VoiceTurnJournal journal) {
        this.booking = booking;
        this.journal = journal;
    }

    Reply turn(Turn input, Utterance source) {
        Access access = journal.claim(input);
        Session state = access.state();
        Ticket work = access.ticket();
        if (work == null) return access.ended() ? ended(state) : currentReply(state);
        if (!work.status().equals("PROCESSING")) return runBooking(work, state, "");

        SarvamSpeech.Transcript transcript;
        try {
            // Audio and the raw transcript stay in the request. No provider call holds a database lock.
            transcript = source.read();
        } catch (SarvamSpeech.SpeechUnavailable failure) {
            var failed = journal.completeSpeech(work, session -> new Decision(unavailable(session), null, false, true));
            return settled(failed, state);
        }
        String spoken = transcript.text();
        if (spoken.length() > 1500) {
            var failed = journal.completeSpeech(work, session -> new Decision(currentPrompt(session, ""), null, false, true));
            return settled(failed, state);
        }
        Settlement result;
        try {
            result = journal.completeSpeech(work, session -> {
                session.hindi = !"en-IN".equalsIgnoreCase(transcript.language());
                return apply(session, input, spoken);
            });
        } catch (DataAccessException | TransactionException ex) {
            // A failed acknowledgement could mean assent is already journaled; the recovery job will check.
            return uncertainReply(state, spoken);
        }
        if (result.ticket() == null) return settled(result, state);
        return runBooking(result.ticket(), state, spoken);
    }

    Reply end(String sessionId) {
        return ended(journal.end(sessionId));
    }

    Reply poll(String sessionId, String turnId, String phone) {
        Access access = journal.current(sessionId, turnId, phone);
        return access.ended() ? ended(access.state()) : currentReply(access.state());
    }

    @Scheduled(fixedDelayString = "${rush.voice.recovery-ms:2000}")
    void recoverAcceptedTurns() {
        for (Scope scope : journal.due()) {
            Ticket work = journal.recover(scope);
            if (work != null) runBooking(work, new Session(), "");
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void pruneOldSessions() {
        journal.prune();
    }

    private Reply runBooking(Ticket work, Session fallback, String transcript) {
        var accepted = new AcceptRequest(work.scope().sessionId(), work.turnId());
        Outcome outcome = work.status().equals("UNCERTAIN")
                ? booking.resolve(work.quoteId().toString(), accepted, null)
                : booking.accept(work.quoteId().toString(), accepted, null);
        try {
            var result = journal.completeBooking(work, state -> {
                if ("BOOKING_UNCERTAIN".equals(outcome.code())) {
                    return new Decision(uncertainReply(state, transcript), work.quoteId(), false, false);
                }
                if ("BOOKING_NOT_FOUND".equals(outcome.code())) {
                    state.quote = null;
                    return Decision.plain(localized(state, "COLLECTING", "",
                            "The earlier order wasn't booked. Ask for another quote when you're ready.",
                            "पिछला ऑर्डर बुक नहीं हुआ। तैयार हों तो फिर से समय पूछिए।", null, null));
                }
                return Decision.plain(processOutcome(state, transcript, outcome));
            });
            return settled(result, fallback);
        } catch (DataAccessException | TransactionException ex) {
            return uncertainReply(fallback, transcript);
        }
    }

    private Reply settled(Settlement result, Session prior) {
        if (result.reply() != null) return result.reply();
        return result.ended() ? ended(prior) : currentReply(prior);
    }

    private Reply ended(Session session) {
        return localized(session, "ENDED", "", "This voice session has ended.", "यह कॉल बंद हो चुकी है।", null, null);
    }

    private Reply unavailable(Session session) {
        var prompt = currentReply(session);
        String apology = session.hindi ? "आपकी बात सुन नहीं पाया। " : "I couldn't hear that. ";
        return new Reply(prompt.state(), "", apology + prompt.replyText(), prompt.language(), prompt.quote(), prompt.order());
    }

    private Decision apply(Session session, Turn input, String spoken) {
        if (spoken.isBlank()) {
            if (++session.silences >= 3) {
                session.quote = null;
                Reply goodbye = localized(session, "ENDED", "", "I'll end the call for now. Thank you.",
                        "अभी कॉल बंद कर रहा हूँ। धन्यवाद।", null, null);
                return new Decision(goodbye, null, true, false);
            }
            return Decision.plain(currentPrompt(session, spoken));
        }
        session.silences = 0;
        var intent = SpeechIntent.parse(spoken);
        if (intent.decision() == NO) {
            session.quote = null;
            return Decision.plain(localized(session, "COLLECTING", spoken,
                    "Okay, I haven't booked anything. Tell me what you'd like instead.",
                    "ठीक है, कुछ बुक नहीं हुआ। बताइए आपको क्या चाहिए।", null, null));
        }
        if (intent.decision() == YES) {
            if (session.quote == null || !session.quote.id().equals(input.confirmationQuoteId())) {
                return Decision.plain(currentPrompt(session, spoken));
            }
            return new Decision(uncertainReply(session, spoken), session.quote.id(), false, false);
        }
        if (intent.decision() == UNCLEAR) return Decision.plain(currentPrompt(session, spoken));

        if (intent.sku() != null) session.sku = intent.sku();
        if (intent.grams() != null) session.grams = intent.grams();
        if (intent.pickupTime() != null) session.pickupTime = intent.pickupTime();
        session.quote = null;
        if (session.sku == null || session.grams == null || session.pickupTime == null) {
            return Decision.plain(currentPrompt(session, spoken));
        }
        String name = input.customerName().isBlank() ? "Caller" : input.customerName();
        Outcome result = booking.quote(new QuoteRequest(input.sessionId(), name, input.phone(),
                session.sku, session.grams, session.pickupTime));
        return Decision.plain(processOutcome(session, spoken, result));
    }

    private Reply currentReply(Session session) {
        return session.last == null ? currentPrompt(session, "") : session.last;
    }

    private Reply processOutcome(Session session, String transcript, Outcome outcome) {
        session.quote = outcome.quote();
        if (outcome.order() != null) {
            var order = outcome.order();
            session.order = new SavedOrder(order.id(), order.itemName(), order.grams(), order.pricePaise(), order.pickupTime(), order.status());
            long number = session.order.id();
            boolean approval = "AWAITING_APPROVAL".equals(session.order.status());
            String time = spokenTime(session.order.pickupTime());
            return localized(session, session.order.status(), transcript,
                    approval ? "Request " + number + " is waiting for shop approval."
                            : "Confirmed. Your order number is " + number + ". Pickup at " + time + ".",
                    approval ? "नंबर " + number + " दुकान की मंज़ूरी का इंतज़ार कर रहा है।"
                            : "ऑर्डर पक्का है। आपका ऑर्डर नंबर " + number + " है। पिकअप " + time + " बजे है।",
                    null, order);
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
    static final class VoiceBusy extends RuntimeException {}
}
