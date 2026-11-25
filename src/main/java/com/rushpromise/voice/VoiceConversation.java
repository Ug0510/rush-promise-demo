package com.rushpromise.voice;

import com.rushpromise.booking.BookingService;
import com.rushpromise.booking.ShopService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.rushpromise.booking.BookingModels.*;

@Component
@Profile("!worker")
class VoiceConversation {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
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
    private static final List<String> PICKUP = List.of("when can", "pickup options", "pickup times", "what time can", "what times",
            "which time", "pickup available", "available at", "earliest", "kab milega", "kab le", "pickup kab", "कब मिलेगा",
            "कब ले", "पिकअप कब", "किस समय", "कौन सा समय", "और कोई टाइम", "कितने बजे मिल", "बजे मिल सकता");
    private static final Pattern HAS_WEIGHT = Pattern.compile("(?:क्या|can i|could i|is there).*(?:मिल सक|available|in stock)");
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
        Kind kind = has(text, PICKUP) ? Kind.PICKUP : has(text, AMOUNT) || HAS_WEIGHT.matcher(text).find() ? Kind.AMOUNT
                : has(text, PRICE) ? Kind.PRICE : has(text, MENU) ? Kind.MENU
                : language != null && parsed.decision() != SpeechIntent.Decision.ORDER ? Kind.LANGUAGE : Kind.BOOKING;
        return new Query(kind, language, parsed.sku(), parsed.grams(), parsed.pickupTime());
    }

    String answer(Query question, boolean hindi, String currentSku, Integer currentGrams) {
        if (question.kind() == Kind.LANGUAGE) {
            return hindi ? "बिल्कुल, अब हम हिंदी में बात करेंगे।" : "Of course. I’ll continue in English.";
        }
        ShopMenu menu = booking.catalog();
        String chosenSku = question.sku() != null ? question.sku() : currentSku;
        MenuItem item = menu.items().stream().filter(candidate -> candidate.sku().equals(chosenSku)).findFirst().orElse(null);
        if (question.kind() == Kind.MENU || item == null) {
            if (question.sku() != null && item != null) return listMenu(List.of(item), hindi);
            return listMenu(menu.items(), hindi);
        }
        Integer requested = question.grams() != null ? question.grams() : currentGrams;
        if (question.kind() == Kind.AMOUNT && question.grams() == null) return listMenu(List.of(item), hindi);
        if (question.kind() == Kind.PRICE && question.grams() == null) {
            String unit = money(item.pricePerKgPaise());
            return hindi ? itemName(item, true) + " " + unit + " रुपये प्रति किलो है। " + listMenu(List.of(item), true)
                    : item.itemName() + " costs " + unit + " rupees per kilo. " + listMenu(List.of(item), false);
        }
        int grams = requested == null ? 500 : requested;
        Availability readback = booking.nextAvailability(item.sku(), grams, question.pickupTime());
        if (readback.option() == null) {
            if ("UNSUPPORTED_WEIGHT".equals(readback.reason()) || "TOO_LARGE".equals(readback.reason())) {
                return hindi ? "यह वज़न अभी नहीं ले सकते। " + listMenu(List.of(item), true)
                        : "That weight isn't an option. " + listMenu(List.of(item), false);
            }
            return hindi ? "इस वज़न के लिए अभी पिकअप नहीं मिल रहा। " + listMenu(List.of(item), true)
                    : "No pickup is available for that weight. " + listMenu(List.of(item), false);
        }
        var option = readback.option();
        String price = money(Math.toIntExact((long) item.pricePerKgPaise() * grams / 1000));
        String time = time(option.pickupTime());
        String name = itemName(item, hindi);
        return hindi ? grams + " ग्राम " + name + " के " + price + " रुपये हैं। पिकअप " + time + " बजे मिल सकता है।"
                : grams + " grams of " + name + " costs " + price + " rupees. The earliest eligible pickup is " + time + ".";
    }

    private String listMenu(List<MenuItem> items, boolean hindi) {
        var lines = new ArrayList<String>();
        for (MenuItem item : items.stream().limit(4).toList()) {
            var portions = item.portions().stream().filter(portion -> portion.readyPickup() != null || portion.futurePickup() != null)
                    .limit(3).map(portion -> {
                        String pickup = portion.readyPickup() == null ? portion.futurePickup()
                                : portion.futurePickup() == null || portion.readyPickup().compareTo(portion.futurePickup()) <= 0
                                ? portion.readyPickup() : portion.futurePickup();
                        boolean future = !pickup.equals(portion.readyPickup());
                        if (hindi) return portion.grams() + " ग्राम " + money(portion.pricePaise())
                                + " रुपये, " + (future ? "अगले बैच से " : "") + time(pickup) + " बजे";
                        return portion.grams() + " grams for " + money(portion.pricePaise()) + " rupees"
                                + (future ? " from the next tray at " : ", pickup at ") + time(pickup);
                    }).toList();
            if (!portions.isEmpty()) lines.add(itemName(item, hindi) + (hindi ? " के विकल्प। " : " options. ")
                    + String.join(hindi ? "। " : ". ", portions));
        }
        if (lines.isEmpty()) return hindi ? "अभी इस मिठाई का पिकअप उपलब्ध नहीं है।"
                : "There are no eligible pickups for those sweets right now.";
        return String.join(hindi ? "। " : ". ", lines) + (hindi ? "।" : ".");
    }

    private static boolean has(String text, List<String> choices) {
        String words = " " + text.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip() + " ";
        return choices.stream().anyMatch(phrase -> words.contains(" " + phrase + " "));
    }

    private static String itemName(MenuItem item, boolean hindi) {
        if (!hindi) return item.itemName();
        return switch (item.sku()) {
            case "kaju-katli" -> "काजू कतली";
            case "barfi" -> "बर्फी";
            default -> item.itemName();
        };
    }

    private static String money(int paise) {
        return BigDecimal.valueOf(paise, 2).stripTrailingZeros().toPlainString();
    }

    private static String time(String time) {
        return LocalTime.parse(time).format(CLOCK);
    }

    enum Kind { BOOKING, LANGUAGE, MENU, PRICE, AMOUNT, PICKUP }
    record Query(Kind kind, String language, String sku, Integer grams, String pickupTime) {}
    record Greeting(String shopName, String language, String replyText) {}
}
