package com.rushpromise.voice;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class SpeechIntent {
    private static final Set<String> YES = Set.of("yes", "haan", "han", "ha", "हाँ", "हां", "हा",
            "जी हाँ", "जी हां", "जी", "ठीक है", "theek hai", "thik hai", "yes please", "confirm",
            "हाँ कर दो", "हां कर दो", "haan kar do", "हाँ पक्का", "हां पक्का", "haan pakka",
            "yes confirm", "पक्का", "pakka");
    private static final Set<String> NO = Set.of("no", "no thanks", "नहीं", "नही", "न", "ना", "nahi",
            "nahin", "nahi chahiye", "नहीं चाहिए", "मत करो", "cancel", "रहने दो", "rehne do");
    private static final Map<String, String> QUANTITY_WORDS = Map.ofEntries(
            Map.entry("aadha", "0.5"), Map.entry("adha", "0.5"), Map.entry("aada", "0.5"),
            Map.entry("आधा", "0.5"), Map.entry("आधे", "0.5"), Map.entry("half", "0.5"),
            Map.entry("paav", "0.25"), Map.entry("pav", "0.25"), Map.entry("पाव", "0.25"),
            Map.entry("ek", "1"), Map.entry("एक", "1"), Map.entry("one", "1"),
            Map.entry("do", "2"), Map.entry("दो", "2"), Map.entry("two", "2"),
            Map.entry("teen", "3"), Map.entry("तीन", "3"), Map.entry("three", "3"),
            Map.entry("char", "4"), Map.entry("chaar", "4"), Map.entry("चार", "4"), Map.entry("four", "4"),
            Map.entry("paanch", "5"), Map.entry("पाँच", "5"), Map.entry("पांच", "5"), Map.entry("five", "5"),
            Map.entry("dedh", "1.5"), Map.entry("डेढ़", "1.5"), Map.entry("डेढ", "1.5"),
            Map.entry("dhai", "2.5"), Map.entry("ढाई", "2.5"),
            Map.entry("dhai sau", "250"), Map.entry("ढाई सौ", "250"),
            Map.entry("paanch sau", "500"), Map.entry("पाँच सौ", "500"), Map.entry("पांच सौ", "500"),
            Map.entry("five hundred", "500"), Map.entry("two hundred fifty", "250"));
    private static final String BOUNDARY = "[\\p{L}\\p{M}\\p{N}]";
    private static final Pattern QUANTITY = Pattern.compile("(?<!" + BOUNDARY + ")"
            + "(two hundred fifty|five hundred|(?:dhai|paanch) sau|(?:ढाई|पाँच|पांच) सौ|"
            + "aadha|adha|aada|आधा|आधे|half|paav|pav|पाव|ek|एक|one|do|दो|two|teen|तीन|three|"
            + "char|chaar|चार|four|paanch|पाँच|पांच|five|dedh|डेढ़|डेढ|dhai|ढाई|\\d{1,5}(?:\\.\\d{1,3})?)"
            + "\\s*(kilograms?|kilos?|kgs?|किलोग्राम|किलो|केजी|grams?|gms?|g|ग्राम)(?!" + BOUNDARY + ")");
    private static final Pattern ITEM = Pattern.compile("काजू(?:\\s*कतली)?|kaju(?:[ -]*katli)?|"
            + "kaaju(?:[ -]*katli)?|cashew katli|बर्फ़ी|बर्फी|burfi|barfi");
    private static final String SIX = "chhe|chhah|chah|che|six|छह|छः|छे|छ";
    private static final String SEVEN = "saat|sat|seven|सात";
    private static final String EIGHT = "aath|ath|eight|आठ";
    private static final String NINE = "nau|nine|नौ";
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)"
            + "\\s*(pm|am|पीएम|एएम)?(?!\\d)|(?<!\\d)([1-9]|1[0-2])\\s*(pm|am|पीएम|एएम|baje|बजे)(?!" + BOUNDARY + ")");
    private static final Pattern NAMED_CLOCK = Pattern.compile("(?<!" + BOUNDARY + ")"
            + "(?:(saade|sadhe|sade|साढ़े|साढ़े|साढे|half past)\\s+)?"
            + "(" + SIX + "|" + SEVEN + "|" + EIGHT + "|" + NINE + ")"
            + "(?:\\s+(thirty|तीस|baje|बजे|pm|पीएम))?(?!" + BOUNDARY + ")");

    enum Decision { YES, NO, ORDER, UNCLEAR }

    record Intent(Decision decision, String sku, Integer grams, String pickupTime) {}

    static Intent parse(String utterance) {
        String text = asciiDigits(Normalizer.normalize(utterance, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll("[\\p{Cf}]", "");
        String words = text.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
        if (YES.contains(words)) return new Intent(Decision.YES, null, null, null);
        if (NO.contains(words)) return new Intent(Decision.NO, null, null, null);

        String item = null;
        var items = ITEM.matcher(text);
        while (items.find()) {
            String match = items.group();
            item = match.contains("बर्फ") || match.endsWith("rfi") ? "barfi" : "kaju-katli";
        }
        Integer grams = null;
        var quantities = QUANTITY.matcher(text);
        while (quantities.find()) {
            String amount = quantities.group(1);
            String unit = quantities.group(2);
            boolean kilos = unit.startsWith("k") || unit.startsWith("कि") || unit.equals("केजी");
            try {
                grams = new BigDecimal(QUANTITY_WORDS.getOrDefault(amount, amount))
                        .movePointRight(kilos ? 3 : 0).intValueExact();
            } catch (ArithmeticException ex) {
                grams = null;
            }
        }

        String pickup = null;
        int lastTimeAt = -1;
        var clocks = CLOCK.matcher(text);
        while (clocks.find()) {
            boolean clockTime = clocks.group(1) != null;
            int hours = Integer.parseInt(clocks.group(clockTime ? 1 : 4));
            int minutes = clockTime ? Integer.parseInt(clocks.group(2)) : 0;
            String marker = clocks.group(clockTime ? 3 : 5);
            pickup = clock(hours, minutes, marker);
            lastTimeAt = clocks.start();
        }
        var namedClocks = NAMED_CLOCK.matcher(text);
        while (namedClocks.find()) {
            boolean half = namedClocks.group(1) != null || Set.of("thirty", "तीस").contains(
                    namedClocks.group(3) == null ? "" : namedClocks.group(3));
            if (namedClocks.start() < lastTimeAt || (!half && namedClocks.group(3) == null)) continue;
            String hour = namedClocks.group(2);
            int hours = hour.matches(SIX) ? 18 : hour.matches(SEVEN) ? 19 : hour.matches(EIGHT) ? 20 : 21;
            pickup = clock(hours, half ? 30 : 0, null);
        }
        return new Intent(item != null || grams != null || pickup != null ? Decision.ORDER : Decision.UNCLEAR,
                item, grams, pickup);
    }

    private static String clock(int hours, int minutes, String marker) {
        if ("am".equals(marker) || "एएम".equals(marker)) {
            if (hours == 12) hours = 0;
        } else if (hours >= 1 && hours <= 11) {
            hours += 12;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private static String asciiDigits(String text) {
        var result = new StringBuilder();
        text.codePoints().forEach(code -> {
            int digit = Character.digit(code, 10);
            result.appendCodePoint(digit < 0 ? code : '0' + digit);
        });
        return result.toString();
    }
}
