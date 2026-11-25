package com.rushpromise.voice;

import com.rushpromise.booking.BookingService;
import com.rushpromise.booking.ShopService;
import com.rushpromise.platform.TenantContext;
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
        return new Query(kind, language, parsed.sku(), parsed.grams(), parsed.pickupTime(), spoken);
    }

    OrderWords orderWords(String spoken, String currentSku) {
        var parsed = SpeechIntent.parse(spoken);
        if (TenantContext.legacy() || parsed.decision() == SpeechIntent.Decision.YES
                || parsed.decision() == SpeechIntent.Decision.NO || parsed.decision() == SpeechIntent.Decision.REPEAT) {
            return new OrderWords(parsed, "GRAMS", false);
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
        return new OrderWords(new SpeechIntent.Intent(decision, sku, quantity, parsed.pickupTime()), unit, wrongUnit);
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
        if (question.kind() == Kind.LANGUAGE) {
            return hindi ? "बिल्कुल, अब हम हिंदी में बात करेंगे।" : "Of course. I’ll continue in English.";
        }
        ShopMenu menu = booking.catalog();
        MenuItem requestedItem = findItem(menu.items(), question.spoken(), question.sku());
        MenuItem item = requestedItem == null ? bySku(menu.items(), currentSku) : requestedItem;
        if (question.kind() == Kind.MENU || item == null) {
            return listMenu(requestedItem == null ? menu.items() : List.of(requestedItem), hindi);
        }
        boolean pieces = "PIECE".equals(item.unit());
        Integer requested = pieces ? pieceCount(question.spoken(), item) : question.grams();
        if (question.kind() == Kind.AMOUNT && requested == null) return listMenu(List.of(item), hindi);
        if (question.kind() == Kind.PRICE && requested == null) {
            String price = money(item.pricePerUnitPaise());
            return hindi ? itemName(item, true) + " " + price + (pieces ? " रुपये प्रति पीस है। " : " रुपये प्रति किलो है। ")
                    + listMenu(List.of(item), true)
                    : item.itemName() + " costs " + price + (pieces ? " rupees per piece. " : " rupees per kilo. ")
                    + listMenu(List.of(item), false);
        }
        int quantity = requested != null ? requested : requestedItem == null && currentGrams != null ? currentGrams : pieces ? 1 : 500;
        Availability readback = booking.nextAvailability(item.sku(), quantity, question.pickupTime());
        if (readback.option() == null) {
            return hindi ? "इस मात्रा के लिए अभी पिकअप नहीं मिल रहा। " + listMenu(List.of(item), true)
                    : "No pickup is available for that amount. " + listMenu(List.of(item), false);
        }
        var option = readback.option();
        String price = money(Math.toIntExact((long) item.pricePerUnitPaise() * quantity / (pieces ? 1 : 1000)));
        String pickup = time(option.pickupTime());
        String name = itemName(item, hindi);
        return hindi ? amount(quantity, item.unit(), true) + " " + name + " के " + price + " रुपये हैं। "
                + hindiTime(option.pickupTime()) + " पिकअप मिल सकता है।"
                : amount(quantity, item.unit(), false) + " of " + name + " costs " + price + " rupees. The earliest eligible pickup is " + pickup + ".";
    }

    static String amount(int count, String unit, boolean hindi) {
        if ("PIECE".equals(unit)) return count + (hindi ? " पीस" : count == 1 ? " piece" : " pieces");
        if (hindi) return switch (count) {
            case 250 -> "पाव किलो";
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

    private String listMenu(List<MenuItem> items, boolean hindi) {
        var lines = new ArrayList<String>();
        for (MenuItem item : items.stream().limit(4).toList()) {
            var portions = item.portions().stream().filter(portion -> portion.readyPickup() != null || portion.futurePickup() != null)
                    .limit(3).map(portion -> {
                        String pickup = portion.readyPickup() == null ? portion.futurePickup()
                                : portion.futurePickup() == null || portion.readyPickup().compareTo(portion.futurePickup()) <= 0
                                ? portion.readyPickup() : portion.futurePickup();
                        boolean future = !pickup.equals(portion.readyPickup());
                        if (hindi) return amount(portion.quantity(), portion.unit(), true) + " " + money(portion.pricePaise())
                                + " रुपये, " + (future ? "अगले बैच से " : "") + hindiTime(pickup);
                        return amount(portion.quantity(), portion.unit(), false) + " for " + money(portion.pricePaise()) + " rupees"
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

    private static String money(int paise) {
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

    enum Kind { BOOKING, LANGUAGE, MENU, PRICE, AMOUNT, PICKUP }
    record Query(Kind kind, String language, String sku, Integer grams, String pickupTime, String spoken) {}
    record OrderWords(SpeechIntent.Intent parsed, String unit, boolean wrongUnit) {}
    record Greeting(String shopName, String language, String replyText) {}
}
