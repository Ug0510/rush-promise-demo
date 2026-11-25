package com.rushpromise.voice;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rushpromise.booking.BookingService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.voice.SpeechIntent.Decision.*;
import static com.rushpromise.voice.VoiceTurnJournal.*;

@Service
@Profile("!worker")
class VoiceSessionService {
    private final BookingService booking;
    private final VoiceTurnJournal journal;
    private final VoiceConversation conversation;
    private final SecureRandom confirmationRandom = new SecureRandom();

    VoiceSessionService(BookingService booking, VoiceTurnJournal journal, VoiceConversation conversation) {
        this.booking = booking;
        this.journal = journal;
        this.conversation = conversation;
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
        var question = VoiceConversation.classify(spoken);
        Settlement result;
        try {
            result = journal.completeSpeech(work, session -> {
                if (question.language() != null) {
                    session.hindi = "hi-IN".equals(question.language());
                    session.languageChosen = true;
                } else if (!session.languageChosen && !spoken.isBlank()) {
                    if (session.last == null) session.hindi = !"en-IN".equalsIgnoreCase(transcript.language());
                    session.languageChosen = true;
                }
                return apply(session, input, spoken, question);
            });
        } catch (DataAccessException | TransactionException ex) {
            // A failed acknowledgement could mean assent is already journaled; the recovery job will check.
            return uncertainReply(state, spoken);
        }
        if (result.ticket() == null) return settled(result, state);
        return runBooking(result.ticket(), state, spoken);
    }

    Reply nativePickupTurn(String sessionId, String turnId, int sequence, UUID confirmationQuoteId,
                           String customerName, String providerSubject, Utterance source) {
        String bound = nativePickupSubject(sessionId, providerSubject);
        return turn(new Turn(sessionId, turnId, sequence, confirmationQuoteId,
                customerName == null ? "" : customerName.strip(), null, bound), source);
    }

    Reply nativePickupPoll(String sessionId, String turnId, String providerSubject) {
        return poll(sessionId, turnId, contactBinding(nativePickupSubject(sessionId, providerSubject), NativeIdentityKind.CALLER));
    }

    Reply nativeInteractionTurn(String sessionId, String turnId, int sequence, UUID confirmationQuoteId,
                                String customerName, Utterance source) {
        String bound = interactionPickupSubject(sessionId);
        return turn(new Turn(sessionId, turnId, sequence, confirmationQuoteId, customerName, null, bound,
                NativeIdentityKind.CALL), source);
    }

    Reply nativeInteractionPoll(String sessionId, String turnId) {
        return poll(sessionId, turnId, contactBinding(interactionPickupSubject(sessionId), NativeIdentityKind.CALL));
    }

    private String interactionPickupSubject(String sessionId) {
        String bound = booking.nativeInteractionSubject(sessionId);
        if (bound == null) throw new IllegalArgumentException("Native call pickup identity is unavailable");
        return bound;
    }

    private String nativePickupSubject(String sessionId, String providerSubject) {
        String bound = booking.nativePickupSubject(sessionId, providerSubject);
        if (bound == null) throw new IllegalArgumentException("Native pickup identity is unavailable");
        return bound;
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
        String quoteId = work.quoteId().toString();
        boolean resolving = work.status().equals("UNCERTAIN");
        Outcome outcome = work.nativePickup()
                ? (resolving ? booking.resolveNativePickup(quoteId, accepted) : booking.acceptNativePickup(quoteId, accepted))
                : (resolving ? booking.resolve(quoteId, accepted, null) : booking.accept(quoteId, accepted, null));
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

    private Decision apply(Session session, Turn input, String spoken, VoiceConversation.Query question) {
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
        boolean nativePickup = input.pickupSubject() != null && input.identityKind() != NativeIdentityKind.GUEST;
        String spokenCode = nativePickup ? SpeechIntent.confirmationCode(spoken) : null;
        if (spokenCode != null) {
            if (!awaitingConfirmation(session)) return Decision.plain(currentPrompt(session, spoken));
            var challenge = session.nativeConfirmation;
            if (challenge != null && challenge.quoteId().equals(session.quote.id()) && challenge.code().equals(spokenCode)) {
                return acceptCurrentQuote(session, spoken);
            }
            return Decision.plain(nativeConfirmationPrompt(session, spoken));
        }
        if (question.kind() != VoiceConversation.Kind.BOOKING) {
            session.unclearTurns = 0;
            session.nativeConfirmation = null;
            var answer = conversation.answerWithChoices(question, session.hindi, session.sku, session.grams);
            if (answer.choices() != null) session.pickupChoices = answer.choices();
            return Decision.plain(render("INFORMATION", spoken, answer.text(), session.hindi ? "hi-IN" : "en-IN", session.quote, null));
        }
        Integer position = SpeechIntent.pickupPosition(spoken);
        if (position != null && session.pickupChoices != null && position < session.pickupChoices.options().size()) {
            var selected = session.pickupChoices.options().get(position);
            session.sku = session.pickupChoices.sku();
            session.grams = selected.quantity();
            session.unit = selected.unit();
            session.pickupTime = selected.pickupTime();
            session.pickupAt = selected.pickupAt();
            return quoteSelection(session, input, spoken);
        }
        var words = conversation.orderWords(spoken, session.sku);
        var intent = words.parsed();
        if (words.wrongUnit()) {
            session.quote = null;
            session.grams = null;
            session.pickupChoices = null;
            if (intent.sku() != null) session.sku = intent.sku();
            session.unit = words.unit();
            applyRequestedTime(session, intent.pickupTime(), spoken);
            return Decision.plain(localized(session, "COLLECTING", spoken, "That item is sold by the piece. How many pieces would you like?",
                    "यह आइटम पीस में बिकता है। आपको कितने पीस चाहिए?", null, null));
        }
        if (words.ambiguousWeight()) {
            session.quote = null;
            session.grams = null;
            session.pickupChoices = null;
            if (intent.sku() != null) session.sku = intent.sku();
            applyRequestedTime(session, intent.pickupTime(), spoken);
            String language = session.hindi ? "hi-IN" : "en-IN";
            return Decision.plain(render("CLARIFY_QUANTITY", spoken, VoiceConversation.weightClarification(intent.grams(), session.hindi), language, null, null));
        }
        if (intent.decision() == UNCLEAR) {
            if (++session.unclearTurns < 2) return Decision.plain(unavailable(session));
            session.quote = null;
            Reply handoff = localized(session, "HANDOFF", spoken, "Please contact the shop so a person can help with your order.",
                    "कृपया दुकान से संपर्क करें ताकि कोई आपके ऑर्डर में मदद कर सके।", null, null);
            return new Decision(handoff, null, true, false);
        }
        session.unclearTurns = 0;
        if (intent.decision() == REPEAT) return Decision.plain(currentPrompt(session, spoken));
        if (intent.decision() == NO) {
            session.quote = null;
            session.pickupChoices = null;
            return Decision.plain(localized(session, "COLLECTING", spoken,
                    "Okay, I haven't booked anything. Tell me what you'd like instead.",
                    "ठीक है, कुछ बुक नहीं हुआ। बताइए आपको क्या चाहिए।", null, null));
        }
        if (intent.decision() == YES) {
            if (!awaitingConfirmation(session)) return Decision.plain(currentPrompt(session, spoken));
            if (session.quote.id().equals(input.confirmationQuoteId())) return acceptCurrentQuote(session, spoken);
            return Decision.plain(nativePickup ? nativeConfirmationPrompt(session, spoken) : currentPrompt(session, spoken));
        }

        if (intent.sku() != null) {
            if (session.unit != null && !session.unit.equals(words.unit())) session.grams = null;
            session.sku = intent.sku();
            session.unit = words.unit();
        }
        if (intent.grams() != null) session.grams = intent.grams();
        applyRequestedTime(session, intent.pickupTime(), spoken);
        return quoteSelection(session, input, spoken);
    }

    private static boolean awaitingConfirmation(Session session) {
        return session.quote != null && session.last != null && "AWAITING_CONFIRMATION".equals(session.last.state());
    }

    private Decision acceptCurrentQuote(Session session, String spoken) {
        return new Decision(uncertainReply(session, spoken), session.quote.id(), false, false);
    }

    private Reply nativeConfirmationPrompt(Session session, String spoken) {
        if (session.nativeConfirmation == null || !session.nativeConfirmation.quoteId().equals(session.quote.id())) {
            String code;
            do { code = Integer.toString(1000 + confirmationRandom.nextInt(9000)); }
            while (!session.usedNativeConfirmationCodes.add(code));
            session.nativeConfirmation = new NativeConfirmation(session.quote.id(), code);
        }
        String digits = String.join(" ", session.nativeConfirmation.code().split(""));
        var offer = readback(session, spoken, "QUOTED", null);
        String instruction = session.hindi ? " पिकअप पक्का करने के लिए कहें: हाँ " + digits + "।"
                : " To book this pickup, say: confirm code " + digits + ".";
        return new Reply(offer.state(), offer.transcript(), offer.replyText() + instruction, offer.language(), offer.quote(), null);
    }

    private void applyRequestedTime(Session session, String time, String spoken) {
        if (time == null) return;
        session.pickupTime = time;
        session.pickupAt = SpeechIntent.tomorrow(spoken) ? conversation.requestedTomorrow(time) : null;
    }

    private Decision quoteSelection(Session session, Turn input, String spoken) {
        session.quote = null;
        session.pickupChoices = null;
        if (session.sku == null || session.grams == null || session.pickupTime == null) {
            return Decision.plain(currentPrompt(session, spoken));
        }
        boolean guest = input.identityKind() == NativeIdentityKind.GUEST;
        String name = input.customerName().isBlank() ? guest ? "Guest" : "Caller" : input.customerName();
        var request = new QuoteRequest(input.sessionId(), name, input.phone(), session.sku, session.grams, session.pickupTime, session.pickupAt);
        Outcome result;
        if (guest) result = booking.quoteGuestPickup(request, input.pickupSubject());
        else if (input.pickupSubject() == null) result = booking.quote(request);
        else result = booking.quoteNativePickup(request, input.pickupSubject(), input.identityKind());
        return Decision.plain(processOutcome(session, spoken, result));
    }

    private Reply currentReply(Session session) {
        return session.last == null ? currentPrompt(session, "") : session.last;
    }

    private Reply processOutcome(Session session, String transcript, Outcome outcome) {
        session.quote = outcome.quote();
        if (outcome.order() != null) {
            var order = outcome.order();
            session.order = savedOrder(order);
            long number = session.order.id();
            boolean approval = "AWAITING_APPROVAL".equals(session.order.status());
            var now = booking.currentShopTime();
            String time = VoiceConversation.pickupLabel(order.pickupTime(), order.pickupAt(), now, false);
            if (order.pickupCode() != null) {
                String code = String.join(" ", order.pickupCode().split(""));
                String english = approval ? "Request " + number + " needs shop approval. Reference code " + code + ". Check with the shop before you travel."
                        : "Order " + number + " is confirmed for " + time + " pickup. Give the shop this pickup code: " + code + ".";
                String hindi = approval ? "अनुरोध नंबर " + number + " के लिए दुकान की मंज़ूरी चाहिए। संदर्भ कोड " + code + " है। आने से पहले दुकान से पूछ लें।"
                        : "ऑर्डर नंबर " + number + " पक्का है। " + VoiceConversation.pickupLabel(order.pickupTime(), order.pickupAt(), now, true)
                                + " ले लीजिए। पिकअप कोड " + code + "।";
                return localized(session, session.order.status(), transcript, english, hindi, null, order);
            }
            return localized(session, session.order.status(), transcript,
                    approval ? "Request " + number + " is waiting for shop approval."
                            : "Confirmed. Your order number is " + number + ". Pickup at " + time + ".",
                    approval ? "नंबर " + number + " दुकान की मंज़ूरी का इंतज़ार कर रहा है।"
                            : "ऑर्डर पक्का है। आपका ऑर्डर नंबर " + number + " है। "
                                    + VoiceConversation.pickupLabel(order.pickupTime(), order.pickupAt(), now, true) + " पिकअप कर लीजिए।",
                    null, order);
        }
        if (session.quote != null) {
            session.sku = session.quote.sku();
            session.grams = session.quote.grams();
            session.unit = session.quote.unit();
            if (outcome.reason() == null || session.pickupTime == null) {
                session.pickupTime = session.quote.pickupTime();
                session.pickupAt = session.quote.pickupAt();
            }
            return readback(session, transcript, outcome.code(), outcome.reason());
        }
        if ("OUTSIDE_HOURS".equals(outcome.reason()) || "OUTSIDE_HOURS".equals(outcome.code())) {
            return localized(session, "COLLECTING", transcript,
                    conversation.hours(false) + " No other pickup is available for this amount.",
                    conversation.hours(true) + " इस मात्रा के लिए दूसरा समय उपलब्ध नहीं है।", null, null);
        }
        if ("CALL_APPROVAL_UNAVAILABLE".equals(outcome.reason()) || "CALL_APPROVAL_UNAVAILABLE".equals(outcome.code())) {
            return localized(session, "COLLECTING", transcript,
                    "That pickup needs shop approval. Please check with the shop.",
                    "इसके लिए दुकान की मंज़ूरी चाहिए। कृपया दुकान से पूछें।", null, null);
        }
        return switch (outcome.code()) {
            case "UNSUPPORTED_WEIGHT" -> unsupportedWeight(session, transcript);
            case "TOO_LARGE" -> localized(session, "COLLECTING", transcript,
                    "PIECE".equals(session.unit) ? "That many pieces aren't available. Ask for a smaller amount."
                            : "That weight isn't available. Try 250 grams, 500 grams, or a whole kilo amount.",
                    "PIECE".equals(session.unit) ? "इतने पीस उपलब्ध नहीं हैं। कम पीस बताइए।"
                            : "यह वज़न उपलब्ध नहीं है। ढाई सौ ग्राम, आधा किलो, या पूरे किलो का वज़न बताइए।", null, null);
            case "PENDING_APPROVAL_LIMIT" -> localized(session, "COLLECTING", transcript,
                    "This number already has an order awaiting shop approval.",
                    "इस नंबर पर एक ऑर्डर पहले से दुकान की मंज़ूरी का इंतज़ार कर रहा है।", null, null);
            case "CALL_ALREADY_BOOKED" -> localized(session, "ENDED", transcript,
                    "A pickup has already been booked on this call.", "इस कॉल पर एक पिकअप पहले ही बुक है।", null, null);
            default -> localized(session, "COLLECTING", transcript,
                    "I don't have an available box for that request. Tell me another item, amount, or time.",
                    "इस ऑर्डर के लिए डिब्बा उपलब्ध नहीं है। कोई और मिठाई, वज़न या समय बताइए।", null, null);
        };
    }

    private Reply unsupportedWeight(Session session, String transcript) {
        if (session.grams != null && session.grams < 1000) {
            return localized(session, "COLLECTING", transcript, "Would you like 250 grams or 500 grams?",
                    "आप 250 ग्राम लेंगे या 500 ग्राम?", null, null);
        }
        return localized(session, "COLLECTING", transcript, "Please choose a whole-kilo weight up to five kilos.",
                "कृपया पाँच किलो तक पूरे किलो में वज़न बताइए।", null, null);
    }

    private Reply currentPrompt(Session session, String transcript) {
        if (session.quote != null) return readback(session, transcript, "QUOTED", null);
        if (session.sku == null) return localized(session, "COLLECTING", transcript,
                "Tell me which sweet you'd like, or ask what is available.",
                "बताइए आपको कौन सी मिठाई चाहिए, या पूछिए अभी क्या मिल सकता है।", null, null);
        if (session.grams == null) return localized(session, "COLLECTING", transcript,
                "PIECE".equals(session.unit) ? "How many pieces would you like?" : "How much would you like?",
                "PIECE".equals(session.unit) ? "आपको कितने पीस चाहिए?" : "आपको कितना वज़न चाहिए?", null, null);
        if (session.pickupTime == null) {
            var choices = conversation.availableTimes(session.sku, session.grams, null, null, session.hindi);
            session.pickupChoices = choices.choices();
            return render("COLLECTING", transcript, choices.text(), session.hindi ? "hi-IN" : "en-IN", null, null);
        }
        return localized(session, "COLLECTING", transcript,
                "Tell me the item, amount and pickup time again.", "मिठाई, वज़न और पिकअप का समय फिर से बताइए।",
                null, null);
    }

    private Reply readback(Session session, String transcript, String code, String reason) {
        Quote quote = session.quote;
        var now = booking.currentShopTime();
        String time = VoiceConversation.pickupLabel(quote.pickupTime(), quote.pickupAt(), now, false);
        String localTime = VoiceConversation.pickupLabel(quote.pickupTime(), quote.pickupAt(), now, true);
        String amount = java.math.BigDecimal.valueOf(quote.pricePaise(), 2).stripTrailingZeros().toPlainString();
        String englishLead = readbackLead(code, reason, false);
        String hindiLead = readbackLead(code, reason, true);
        String englishWhy = approvalWhy(quote, false);
        String hindiWhy = approvalWhy(quote, true);
        String ask = quote.requiresApproval() ? "Request it?" : "Shall I confirm?";
        String askHindi = quote.requiresApproval() ? "अनुरोध भेज दूँ?" : "पक्का कर दूँ?";
        String english = englishLead + VoiceConversation.amount(quote.grams(), quote.unit(), false) + " of " + quote.itemName()
                + ", ₹" + amount + ", " + time + ". " + englishWhy + ask;
        String item = VoiceConversation.spokenItem(quote.itemName(), true);
        String hindi = hindiLead + VoiceConversation.amount(quote.grams(), quote.unit(), true) + " " + item
                + ", " + amount + " रुपये, " + localTime + "। " + hindiWhy + askHindi;
        return localized(session, "AWAITING_CONFIRMATION", transcript, english, hindi, quote, null);
    }

    private String readbackLead(String code, String reason, boolean hindi) {
        if ("OUTSIDE_HOURS".equals(reason)) return conversation.hours(hindi) + " ";
        if ("CALL_APPROVAL_UNAVAILABLE".equals(reason)) return hindi ? "उस समय दुकान की मंज़ूरी चाहिए। दूसरा समय है। "
                : "That time needs shop approval. Here’s another time. ";
        if ("PICKUP_INTERVAL".equals(reason)) return hindi ? "पिकअप के समय आधे घंटे के अंतर पर हैं। " : "Pickups use half-hour slots. ";
        if ("TERMS_CHANGED".equals(code)) return hindi ? "शर्तें बदल गई हैं। " : "The terms changed. ";
        if (reason != null) return hindi ? "एक दूसरा समय है। " : "Here’s another time. ";
        return "";
    }

    private static String approvalWhy(Quote quote, boolean hindi) {
        if (!quote.requiresApproval()) return "";
        var slot = quote.policy().slot();
        if (slot != null && "SLOT_NEAR_CAPACITY".equals(slot.reasonCode()) && slot.capacity() > 0) {
            long load = 100L * slot.used() / slot.capacity();
            return hindi ? load + " प्रतिशत जगह भरी है, इसलिए दुकान की मंज़ूरी चाहिए। "
                    : "This slot is " + load + "% full and needs shop approval. ";
        }
        if (slot != null && "RUSH_HOUR".equals(slot.reasonCode())) {
            String limit = java.math.BigDecimal.valueOf(quote.policy().approvalAboveGrams(), 3).stripTrailingZeros().toPlainString();
            return hindi ? "व्यस्त समय की " + limit + " किलो सीमा से ऊपर दुकान की मंज़ूरी चाहिए। "
                    : "Above the " + limit + " kg rush limit needs shop approval. ";
        }
        return hindi ? "दुकान की मंज़ूरी चाहिए। " : "Shop approval is needed. ";
    }

    private static Reply uncertainReply(Session session, String transcript) {
        return localized(session, "CHECKING_BOOKING", transcript,
                "I'm checking whether your order was saved. Please wait before ordering again.",
                "मैं देख रहा हूँ कि आपका ऑर्डर दर्ज हुआ या नहीं। दोबारा ऑर्डर देने से पहले रुकिए।", null, null);
    }

    private static RecordedDecision decision(Policy policy) {
        var slot = policy == null ? null : policy.slot();
        return slot == null ? null : new RecordedDecision(slot.profile(), slot.digest(), policy.source(), policy.fetchedAt());
    }

    private static SavedOrder savedOrder(Order order) {
        return new SavedOrder(order.id(), order.itemName(), order.grams(), order.pricePaise(), order.pickupTime(),
                order.status(), order.unit(), decision(order.policy()), order.pickupCode(), order.pickupAt());
    }

    private static Reply localized(Session session, String state, String transcript,
                                   String english, String hindi, Quote quote, Order order) {
        return render(state, transcript, session.hindi ? hindi : english, session.hindi ? "hi-IN" : "en-IN", quote, order);
    }

    private static Reply render(String state, String transcript, String text, String language, Quote quote, Order order) {
        return new Reply(state, transcript, text, language,
                quote == null ? null : new Offer(quote.id(), quote.itemName(), quote.grams(), quote.pricePaise(),
                        quote.pickupTime(), quote.requiresApproval(), quote.expiresAt(), quote.unit(), decision(quote.policy()), quote.pickupAt()),
                order == null ? null : savedOrder(order));
    }

    @FunctionalInterface
    interface Utterance {
        SarvamSpeech.Transcript read();
    }

    record Turn(String sessionId, String turnId, int sequence, UUID confirmationQuoteId, String customerName, String phone,
                String pickupSubject, NativeIdentityKind identityKind) {
        Turn(String sessionId, String turnId, int sequence, UUID confirmationQuoteId, String customerName, String phone) {
            this(sessionId, turnId, sequence, confirmationQuoteId, customerName, phone, null, null);
        }
        Turn(String sessionId, String turnId, int sequence, UUID confirmationQuoteId, String customerName, String phone, String pickupSubject) {
            this(sessionId, turnId, sequence, confirmationQuoteId, customerName, phone, pickupSubject, NativeIdentityKind.CALLER);
        }
        String contactBinding() { return pickupSubject == null ? phone : VoiceSessionService.contactBinding(pickupSubject, identityKind); }
    }
    private static String contactBinding(String subject, NativeIdentityKind kind) {
        // Keep prior verified-caller journals readable while giving per-call pickup a distinct scope.
        return switch (kind) {
            case CALL -> "native-call-pickup:" + subject;
            case GUEST -> "guest-pickup:" + subject;
            case CALLER -> "native-pickup:" + subject;
        };
    }
    record RecordedDecision(String profile, String digest, String source, Instant fetchedAt) {}
    record Offer(UUID id, String itemName, int grams, int pricePaise, String pickupTime, boolean requiresApproval, Instant expiresAt,
                 String unit, RecordedDecision decision, Instant pickupAt) {
        Offer(UUID id, String itemName, int grams, int pricePaise, String pickupTime, boolean requiresApproval, Instant expiresAt,
              String unit, RecordedDecision decision) {
            this(id, itemName, grams, pricePaise, pickupTime, requiresApproval, expiresAt, unit, decision, null);
        }
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int quantity() { return grams; }
    }
    record SavedOrder(long id, String itemName, int grams, int pricePaise, String pickupTime, String status, String unit,
                      RecordedDecision decision, @JsonInclude(JsonInclude.Include.NON_NULL) String pickupCode, Instant pickupAt) {
        SavedOrder(long id, String itemName, int grams, int pricePaise, String pickupTime, String status) {
            this(id, itemName, grams, pricePaise, pickupTime, status, "GRAMS", null, null, null);
        }
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int quantity() { return grams; }
    }
    record Reply(String state, String transcript, String replyText, String language, Offer quote, SavedOrder order) {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String confirmationToken() {
            return quote != null && "AWAITING_CONFIRMATION".equals(state) ? quote.id().toString() : "";
        }
    }
    static final class VoiceBusy extends RuntimeException {}
}
