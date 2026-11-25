package com.rushpromise.voice;

import com.rushpromise.booking.BookingService;
import com.rushpromise.booking.ShopService;
import com.rushpromise.platform.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.rushpromise.booking.BookingModels.*;

@Component
@Profile("!worker")
class VoiceConversation {
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final List<String> HINDI_HOURS = List.of("बारह", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ", "दस", "ग्यारह");
    private static final Pattern ENGLISH_REQUEST = Pattern.compile(
            "^(english|angrezi)|(?:speak|switch|say|talk|in|use) (?:to |in )?(?:english|angrezi)|(?:इंग्लिश|इंग्लिश़|अंग्रेज़ी|अंग्रेजी|इंग्लीश|इंग्लिश्) में");
    private static final Pattern HINDI_REQUEST = Pattern.compile(
            "^(hindi|हिंदी|हिन्दी)|(?:speak|switch|say|talk|in|use) (?:to |in )?hindi|(?:हिंदी|हिन्दी) में|hindi mein");
    private static final List<String> PRICE = List.of("price", "cost", "price of", "rate", "how much is", "how much for",
            "daam", "kitne ka", "kitne ki", "kitna lagega", "keemat", "दाम", "कीमत", "कितने का", "कितने की",
            "कितने रुपये", "कितना लगेगा", "कितना पड़ेगा", "कितने का पड़ेगा", "रेट");
    private static final List<String> MENU = List.of("what sweets", "what can i order", "what do you have", "which sweets", "list sweets",
            "menu", "do you have", "have any", "available sweets", "kya kya", "kya mil", "sweet options", "मेन्यू",
            "क्या क्या", "क्या है", "कौन सी मिठाई", "कौन कौन सी", "क्या मिलेगा", "क्या मिलता", "विकल्प");
    private static final List<String> HOURS = List.of("opening hours", "business hours", "store hours", "pickup hours", "when are you open",
            "दुकान कब खुलती है", "दुकान कब बंद होती है", "पिकअप के घंटे", "खुलने का समय", "बंद होने का समय");
    private static final List<String> PICKUP = List.of("when can", "pickup options", "pickup times", "what time can", "what times",
            "which time", "pickup available", "available at", "earliest", "kab milega", "kab le", "pickup kab", "कब मिलेगा",
            "कब ले", "पिकअप कब", "किस समय", "कौन सा समय", "और कोई टाइम", "कितने बजे मिल", "बजे मिल सकता");
    private static final Pattern HAS_WEIGHT = Pattern.compile("(?:क्या|can i|could i|is there).*(?:मिल सक|available|in stock)");
    private static final Pattern EXACT_GRAMS = Pattern.compile("^(?:सिर्फ़?|केवल|only|exactly) [\\p{L}\\p{M}\\p{N}]+ (?:ग्राम|grams?|g)(?: चाहिए| please)?$");
    private static final List<String> AMOUNT = List.of("which sizes", "what sizes", "what weights", "how many grams", "how much is available",
            "how much can i get", "quantity available", "which quantities", "kitna milega", "कितना मिल सकता", "कितना मिलेगा",
            "कौन से वज़न", "कौन से वजन", "कितना स्टॉक", "कितनी मात्रा", "वज़न उपलब्ध", "वजन उपलब्ध");

    private final BookingService booking;
    private final ShopService shop;

    VoiceConversation(BookingService booking, ShopService shop) {
        this.booking = booking;
        this.shop = shop;
    }

    Greeting greet(String language) {
        String shopName = shop.profile().displayName();
        boolean hindi = !"en-IN".equals(language);
        String greeting = hindi ? "नमस्ते, मैं " + shopName + " से बात कर रहा हूँ। बताइए, आपको क्या चाहिए?"
                : "Hello, this is " + shopName + ". What would you like for pickup?";
        return new Greeting(shopName, hindi ? "hi-IN" : "en-IN", greeting);
    }

    Greeting menu(String language) {
        ShopMenu menu = booking.catalog();
        boolean hindi = !"en-IN".equals(language);
        return new Greeting(menu.displayName(), hindi ? "hi-IN" : "en-IN", listMenu(menu.items(), hindi));
    }

    static Query classify(String spoken) {
        String text = Normalizer.normalize(spoken, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
        String language = ENGLISH_REQUEST.matcher(text).find() ? "en-IN"
                : HINDI_REQUEST.matcher(text).find() ? "hi-IN" : null;
        var parsed = SpeechIntent.parse(spoken);
        Kind kind = has(text, HOURS) ? Kind.HOURS : has(text, PICKUP) ? Kind.PICKUP : has(text, AMOUNT) || HAS_WEIGHT.matcher(text).find() ? Kind.AMOUNT
                : has(text, PRICE) ? Kind.PRICE : has(text, MENU) ? Kind.MENU
                : language != null && parsed.decision() != SpeechIntent.Decision.ORDER ? Kind.LANGUAGE : Kind.BOOKING;
        return new Query(kind, language, parsed.sku(), parsed.grams(), parsed.pickupTime(), spoken);
    }

    VoiceSessionService.Reply previewWithoutIdentity(String spoken, String defaultLanguage) {
        var question = classify(spoken);
        String language = question.language() == null ? defaultLanguage : question.language();
        boolean hindi = "hi-IN".equals(language);
        if (question.kind() != Kind.BOOKING) {
            return informational("INFORMATION", answer(question, hindi, null, null), language);
        }
        var words = orderWords(spoken, null);
        var intent = words.parsed();
        if (words.ambiguousWeight()) {
            return informational("CLARIFY_QUANTITY", weightClarification(intent.grams(), hindi), language);
        }
        if (intent.decision() == SpeechIntent.Decision.YES) {
            return informational("PREVIEW_ONLY", hindi ? "बुकिंग अभी नहीं हुई। मैं कीमत और समय बता सकता हूँ। बुकिंग के लिए Rush Promise में इस दुकान का वॉइस चेकआउट खोलें।"
                    : "Nothing was booked. I can still check prices and pickup times. To book, open this shop’s voice checkout in Rush Promise.", language);
        }
        if (intent.sku() != null && intent.grams() != null && intent.pickupTime() != null) {
            var offer = booking.previewPickup(intent.sku(), intent.grams(), intent.pickupTime());
            if (offer != null) {
                Instant now = booking.currentShopTime();
                String text = hindi ? amount(offer.quantity(), offer.unit(), true) + " " + spokenItem(offer.itemName(), true)
                        + ", " + money(offer.pricePaise()) + " रुपये। " + pickupLabel(offer.pickupTime(), offer.pickupAt(), now, true)
                        + " पिकअप मिल सकता है।" + (offer.requiresApproval() ? " इसके लिए दुकान की मंज़ूरी चाहिए।" : "")
                        : amount(offer.quantity(), offer.unit(), false) + " of " + offer.itemName() + " costs "
                        + money(offer.pricePaise()) + " rupees. Pickup may be available " + pickupLabel(offer.pickupTime(), offer.pickupAt(), now, false) + "."
                        + (offer.requiresApproval() ? " This needs shop approval." : "");
                return informational("PREVIEW_ONLY", text, language);
            }
        }
        String help = hindi ? "मैं कीमत, स्टॉक और पिकअप का समय बता सकता हूँ। मिठाई, मात्रा और समय बताइए। "
                : "I can check prices, stock and pickup times. Tell me the item, amount and time. ";
        return informational("INFORMATION", help + menu(language).replyText(), language);
    }

    private static VoiceSessionService.Reply informational(String state, String text, String language) {
        return new VoiceSessionService.Reply(state, "", text, language, null, null);
    }

    OrderWords orderWords(String spoken, String currentSku) {
        var parsed = SpeechIntent.parse(spoken);
        boolean exactGrams = EXACT_GRAMS.matcher(SpeechIntent.words(spoken)).matches();
        if (TenantContext.legacy() || parsed.decision() == SpeechIntent.Decision.YES
                || parsed.decision() == SpeechIntent.Decision.NO || parsed.decision() == SpeechIntent.Decision.REPEAT) {
            return new OrderWords(parsed, "GRAMS", false, exactGrams);
        }
        var items = booking.catalog().items();
        var selected = findItem(items, spoken, parsed.sku());
        var current = selected == null ? bySku(items, currentSku) : selected;
        String unit = current == null ? "GRAMS" : current.unit();
        Integer quantity = "PIECE".equals(unit) ? pieceCount(spoken, current) : parsed.grams();
        String sku = selected == null ? null : selected.sku();
        var decision = sku != null || quantity != null || parsed.pickupTime() != null
                ? SpeechIntent.Decision.ORDER : SpeechIntent.Decision.UNCLEAR;
        boolean wrongUnit = current != null && "PIECE".equals(unit) && parsed.grams() != null && quantity == null;
        return new OrderWords(new SpeechIntent.Intent(decision, sku, quantity, parsed.pickupTime()), unit, wrongUnit, exactGrams);
    }

    private static MenuItem findItem(List<MenuItem> items, String spoken, String recognizedSku) {
        String words = " " + SpeechIntent.words(spoken) + " ";
        MenuItem selected = null;
        int end = -1;
        int longest = 0;
        for (MenuItem item : items) {
            String name = SpeechIntent.words(item.itemName());
            if (name.isEmpty()) continue;
            String hindiName = SpeechIntent.words(spokenItem(item.itemName(), true));
            int start = Math.max(words.lastIndexOf(" " + name + " "), words.lastIndexOf(" " + name + "s "));
            int translated = words.lastIndexOf(" " + hindiName + " ");
            if (translated > start) {
                name = hindiName;
                start = translated;
            }
            int nextEnd = start + name.length();
            if (start >= 0 && (nextEnd > end || nextEnd == end && name.length() > longest)) {
                selected = item;
                end = nextEnd;
                longest = name.length();
            }
        }
        if (selected != null) return selected;
        var alias = bySku(items, recognizedSku);
        return alias != null && !spokenItem(alias.itemName(), true).equals(alias.itemName()) ? alias : null;
    }

    private static MenuItem bySku(List<MenuItem> items, String sku) {
        return items.stream().filter(item -> item.sku().equals(sku)).findFirst().orElse(null);
    }

    String answer(Query question, boolean hindi, String currentSku, Integer currentGrams) {
        return answerWithChoices(question, hindi, currentSku, currentGrams).text();
    }

    Answer answerWithChoices(Query question, boolean hindi, String currentSku, Integer currentGrams) {
        if (question.kind() == Kind.LANGUAGE) {
            return new Answer(hindi ? "अब हिंदी में बात करेंगे।" : "I’ll continue in English.", null);
        }
        if (question.kind() == Kind.HOURS) return new Answer(hours(hindi), null);
        ShopMenu menu = booking.catalog();
        MenuItem requestedItem = findItem(menu.items(), question.spoken(), question.sku());
        MenuItem item = requestedItem == null ? bySku(menu.items(), currentSku) : requestedItem;
        if (question.kind() == Kind.MENU) {
            return new Answer(listMenu(requestedItem == null ? menu.items() : List.of(requestedItem), hindi), null);
        }
        if (item == null) return new Answer(hindi ? "किस मिठाई के लिए?" : "Which item?", null);
        boolean pieces = "PIECE".equals(item.unit());
        Integer requested = pieces ? pieceCount(question.spoken(), item) : question.grams();
        if (question.kind() == Kind.PRICE) {
            String name = itemName(item, hindi);
            if (requested == null) {
                String unitPrice = money(item.pricePerUnitPaise());
                return new Answer(hindi ? name + " " + unitPrice + (pieces ? " रुपये प्रति पीस।" : " रुपये प्रति किलो।")
                        : name + " is ₹" + unitPrice + (pieces ? " per piece." : " per kilo."), null);
            }
            String price = money((long) item.pricePerUnitPaise() * requested / (pieces ? 1 : 1000));
            return new Answer(hindi ? amount(requested, item.unit(), true) + " " + name + ", " + price + " रुपये।"
                    : amount(requested, item.unit(), false) + " of " + name + " is ₹" + price + ".", null);
        }
        if (question.kind() == Kind.AMOUNT && requested == null) {
            List<String> portions = item.portions().stream().filter(VoiceConversation::availablePortion)
                    .map(portion -> amount(portion.quantity(), portion.unit(), hindi)).limit(3).toList();
            if (portions.isEmpty()) return new Answer(hindi ? "यह अभी उपलब्ध नहीं है।" : "This item is currently unavailable.", null);
            return new Answer(String.join(hindi ? " या " : " or ", portions) + (hindi ? " उपलब्ध है।" : " available."), null);
        }
        if (question.kind() == Kind.AMOUNT) {
            boolean available = !booking.availablePickups(item.sku(), requested, null, 1).isEmpty();
            String size = amount(requested, item.unit(), hindi);
            return new Answer(hindi ? size + (available ? " उपलब्ध है।" : " अभी उपलब्ध नहीं है।")
                    : size + (available ? " is available." : " is currently unavailable."), null);
        }
        int quantity = requested != null ? requested : requestedItem == null && currentGrams != null ? currentGrams : pieces ? 1 : 500;
        Instant requestedAt = SpeechIntent.tomorrow(question.spoken()) ? requestedTomorrow(question.pickupTime()) : null;
        return availableTimes(item.sku(), quantity, question.pickupTime(), requestedAt, hindi);
    }

    Answer availableTimes(String sku, int quantity, String start, Instant requestedAt, boolean hindi) {
        String effectiveStart = start == null && requestedAt != null ? "00:00" : start;
        List<PickupPreview> options = booking.availablePickups(sku, quantity, effectiveStart, requestedAt, 3);
        boolean outside = start != null && outsideHours(start, booking.pickupHours());
        String prefix = outside ? hours(hindi) + " " : "";
        if (options.isEmpty()) return new Answer(prefix + (hindi ? "इस मात्रा के लिए अभी पिकअप का समय नहीं है।"
                : "There’s no pickup time available for that amount."), new PickupChoices(sku, options));
        Instant now = booking.currentShopTime();
        var first = options.getFirst();
        var times = options.stream().map(option -> pickupLabel(option.pickupTime(), option.pickupAt(), now, hindi)).toList();
        String item = amount(quantity, first.unit(), hindi) + " " + spokenItem(first.itemName(), hindi);
        String text = hindi ? item + " के लिए " + String.join(" या ", times) + " पिकअप मिल सकता है।"
                : "For " + item + ", pickup is available " + String.join(", ", times) + ".";
        return new Answer(prefix + text, new PickupChoices(sku, options));
    }

    Instant requestedTomorrow(String pickupTime) {
        var now = booking.currentShopTime().atZone(SHOP_ZONE);
        var time = pickupTime == null ? LocalTime.MIDNIGHT : LocalTime.parse(pickupTime);
        return now.toLocalDate().plusDays(1).atTime(time).atZone(SHOP_ZONE).toInstant();
    }

    String hours(boolean hindi) {
        var hours = booking.pickupHours();
        if (hours == null || hours.allDay()) return hindi ? "पिकअप का समय पूरे दिन खुला है।" : "Pickup hours are all day.";
        return hindi ? "पिकअप का समय " + hindiTime(hours.opensAt()) + " से " + hindiTime(hours.closesAt()) + " तक है।"
                : "Pickup hours are " + time(hours.opensAt()) + " to " + time(hours.closesAt()) + " IST.";
    }

    private static boolean outsideHours(String proposed, PickupHours hours) {
        if (hours == null || hours.allDay()) return false;
        return proposed.compareTo(hours.opensAt()) < 0 || proposed.compareTo(hours.closesAt()) > 0;
    }

    static String pickupLabel(String pickupTime, Instant pickupAt, Instant now, boolean hindi) {
        String clock = hindi ? hindiTime(pickupTime) : time(pickupTime);
        if (pickupAt == null || now == null) return clock;
        var day = pickupAt.atZone(SHOP_ZONE).toLocalDate();
        var currentDay = now.atZone(SHOP_ZONE).toLocalDate();
        if (day.equals(currentDay)) return clock;
        if (day.equals(currentDay.plusDays(1))) return hindi ? "कल " + clock : "tomorrow at " + clock;
        String date = day.format(DateTimeFormatter.ofPattern("d MMMM", hindi ? Locale.forLanguageTag("hi-IN") : Locale.ENGLISH));
        return hindi ? date + " को " + clock : date + " at " + clock;
    }

    static String amount(int count, String unit, boolean hindi) {
        if ("PIECE".equals(unit)) return count + (hindi ? " पीस" : count == 1 ? " piece" : " pieces");
        if (hindi) return switch (count) {
            case 250 -> "ढाई सौ ग्राम";
            case 500 -> "आधा किलो";
            case 1000 -> "एक किलो";
            case 1500 -> "डेढ़ किलो";
            case 2000 -> "दो किलो";
            case 2500 -> "ढाई किलो";
            case 3000 -> "तीन किलो";
            case 4000 -> "चार किलो";
            case 5000 -> "पाँच किलो";
            default -> count + " ग्राम";
        };
        return count + " grams";
    }

    static String weightClarification(int grams, boolean hindi) {
        return hindi ? "मैंने " + grams + " ग्राम सुना। कृपया वज़न और उसकी इकाई फिर बताइए। अगर यही मात्रा है, तो सिर्फ "
                + grams + " ग्राम कहिए।"
                : "I heard " + grams + " grams. Please repeat the amount and unit. If that's what you meant, say exactly " + grams + " grams.";
    }

    private static String listMenu(List<MenuItem> items, boolean hindi) {
        List<String> names = items.stream().filter(item -> item.portions().stream().anyMatch(VoiceConversation::availablePortion))
                .map(item -> itemName(item, hindi)).limit(4).toList();
        if (names.isEmpty()) return hindi ? "अभी पिकअप के लिए कोई आइटम उपलब्ध नहीं है।" : "Nothing is currently available for pickup.";
        return String.join(hindi ? " और " : ", ", names) + (hindi ? " उपलब्ध है।" : " available.");
    }

    private static boolean availablePortion(MenuPortion portion) {
        return portion.readyPickup() != null || portion.futurePickup() != null;
    }

    private static boolean has(String text, List<String> choices) {
        String words = " " + text.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip() + " ";
        return choices.stream().anyMatch(phrase -> words.contains(" " + phrase + " "));
    }

    private static String itemName(MenuItem item, boolean hindi) {
        return spokenItem(item.itemName(), hindi);
    }

    private static Integer pieceCount(String spoken, MenuItem item) {
        Integer quantity = SpeechIntent.pieces(spoken, item.itemName());
        return quantity != null ? quantity : SpeechIntent.pieces(spoken, spokenItem(item.itemName(), true));
    }

    static String spokenItem(String name, boolean hindi) {
        if (!hindi) return name;
        return switch (SpeechIntent.words(name)) {
            case "kaju katli", "kaaju katli" -> "काजू कतली";
            case "barfi", "burfi" -> "बर्फी";
            case "vanilla cupcake" -> "वनीला कपकेक";
            case "chocolate cupcake" -> "चॉकलेट कपकेक";
            case "chocolate brownie" -> "चॉकलेट ब्राउनी";
            default -> name;
        };
    }

    private static String money(long paise) {
        return BigDecimal.valueOf(paise, 2).stripTrailingZeros().toPlainString();
    }

    private static String time(String time) {
        return LocalTime.parse(time).format(CLOCK);
    }

    static String hindiTime(String value) {
        LocalTime time = LocalTime.parse(value);
        int hour = time.getHour();
        String period = hour < 4 || hour >= 20 ? "रात" : hour < 12 ? "सुबह" : hour < 16 ? "दोपहर" : "शाम";
        String name = HINDI_HOURS.get(hour % 12);
        String clock = switch (time.getMinute()) {
            case 0 -> name + " बजे";
            case 15 -> "सवा " + name + " बजे";
            case 30 -> (hour % 12 == 1 ? "डेढ़" : hour % 12 == 2 ? "ढाई" : "साढ़े " + name) + " बजे";
            case 45 -> "पौने " + HINDI_HOURS.get((hour + 1) % 12) + " बजे";
            default -> name + " बजकर " + time.getMinute() + " मिनट पर";
        };
        return period + " " + clock;
    }

    enum Kind { BOOKING, LANGUAGE, MENU, PRICE, AMOUNT, PICKUP, HOURS }
    record Answer(String text, PickupChoices choices) {}
    record PickupChoices(String sku, List<PickupPreview> options) {}
    record Query(Kind kind, String language, String sku, Integer grams, String pickupTime, String spoken) {}
    record OrderWords(SpeechIntent.Intent parsed, String unit, boolean wrongUnit, boolean exactGrams) {
        boolean ambiguousWeight() {
            return "GRAMS".equals(unit) && parsed.grams() != null && parsed.grams() > 0 && parsed.grams() < 10 && !exactGrams;
        }
    }
    record Greeting(String shopName, String language, String replyText) {}
}
