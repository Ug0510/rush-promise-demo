package com.rushpromise.voice;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class SpeechIntent {
    private static final Set<String> YES = Set.of("जी", "ठीक है", "theek hai", "thik hai", "confirm",
            "हाँ कर दो", "हां कर दो", "haan kar do", "हाँ पक्का", "हां पक्का", "haan pakka",
            "yes confirm", "yes please confirm", "पक्का", "pakka", "कर दो", "कर दीजिए", "हाँ कर दीजिए", "हां कर दीजिए",
            "हाँ कन्फर्म है", "हां कन्फर्म है", "हाँ कंफर्म है", "हां कंफर्म है");
    private static final Pattern POLITE_YES = Pattern.compile(
            "(?:yes(?: please| sir| ma am| madam)?|(?:haan|han|ha)(?: ji| sir| madam)?|(?:हाँ|हां|हा)(?: जी| सर| मैडम)?|जी (?:हाँ|हां))");
    private static final Set<String> NO = Set.of("no", "no thanks", "नहीं", "नही", "न", "ना", "nahi",
            "nahin", "nahi chahiye", "नहीं चाहिए", "मत करो", "cancel", "रहने दो", "rehne do");
    private static final Set<String> REPEAT = Set.of("repeat", "please repeat", "dobara", "dubara", "phir se",
            "दोबारा", "फिर से", "दोहराइए", "दोबारा बताइए");
    private static final Pattern CONFIRM_CODE = Pattern.compile(
            "^(?:confirm code|yes code|हाँ कोड|हां कोड|हाँ सर|हां सर|हाँ|हां|haan|पक्का कोड|कन्फर्म कोड|कंफर्म कोड) (.+)$");
    private static final String[] ENGLISH_DIGITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
    private static final String[] HINDI_DIGITS = {"शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ"};
    private static final String[] HINGLISH_DIGITS = {"shunya", "ek", "do", "teen", "chaar", "paanch", "chhe", "saat", "aath", "nau"};
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
            Map.entry("दो सौ पचास", "250"), Map.entry("do sau pachas", "250"), Map.entry("do sau pachaas", "250"),
            Map.entry("paanch sau", "500"), Map.entry("पाँच सौ", "500"), Map.entry("पांच सौ", "500"),
            Map.entry("five hundred", "500"), Map.entry("two hundred fifty", "250"), Map.entry("two hundred and fifty", "250"));
    private static final String BOUNDARY = "[\\p{L}\\p{M}\\p{N}]";
    private static final Pattern QUANTITY = Pattern.compile("(?<!" + BOUNDARY + ")"
            + "(two hundred (?:and )?fifty|five hundred|do sau pacha?as|दो सौ पचास|(?:dhai|paanch) sau|(?:ढाई|पाँच|पांच) सौ|"
            + "aadha|adha|aada|आधा|आधे|half|paav|pav|पाव|ek|एक|one|do|दो|two|teen|तीन|three|"
            + "char|chaar|चार|four|paanch|पाँच|पांच|five|dedh|डेढ़|डेढ|dhai|ढाई|\\d{1,5}(?:\\.\\d{1,3})?)"
            + "\\s*(kilograms?|kilos?|kgs?|किलोग्राम|किलो|केजी|grams?|gms?|g|ग्राम)(?!" + BOUNDARY + ")");
    private static final Pattern ITEM = Pattern.compile("काजू(?:\\s*कतली)?|kaju(?:[ -]*katli)?|"
            + "kaaju(?:[ -]*katli)?|cashew katli|बर्फ़ी|बर्फी|burfi|barfi");
    private static final String PIECE_COUNT = "(\\d{1,5}|one|two|three|four|five|ek|do|teen|char|chaar|paanch|एक|दो|तीन|चार|पाँच|पांच)";
    private static final Pattern PIECES = Pattern.compile("(?<!" + BOUNDARY + ")" + PIECE_COUNT
            + "\\s*(?:pieces?|units?|pc?s|पीस|नग)(?!" + BOUNDARY + ")");
    private static final String SIX = "chhe|chhah|chah|che|six|छह|छः|छे|छ";
    private static final String SEVEN = "saat|sat|seven|सात";
    private static final String EIGHT = "aath|ath|eight|आठ";
    private static final String NINE = "nau|nine|नौ";
    private static final String OTHER_HOURS = "one|ek|एक|two|do|दो|three|teen|तीन|four|chaar|char|चार|five|paanch|पाँच|पांच|ten|das|दस|eleven|gyarah|ग्यारह|twelve|barah|बारह";
    private static final Pattern PERIOD = Pattern.compile("(?<!" + BOUNDARY + ")(morning|subah|सुबह|सवेरे|afternoon|दोपहर|evening|shaam|शाम|night|रात)(?!" + BOUNDARY + ")");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)"
            + "\\s*(pm|am|पीएम|एएम)?(?!\\d)|(?<!\\d)([1-9]|1[0-2])\\s*(pm|am|पीएम|एएम|baje|बजे)(?!" + BOUNDARY + ")");
    private static final Pattern NAMED_CLOCK = Pattern.compile("(?<!" + BOUNDARY + ")"
            + "(?:(saade|sadhe|sade|साढ़े|साढ़े|साढे|half past)\\s+)?"
            + "(" + SIX + "|" + SEVEN + "|" + EIGHT + "|" + NINE + "|" + OTHER_HOURS + ")"
            + "(?:\\s+(thirty|तीस|baje|बजे|pm|पीएम))?(?!" + BOUNDARY + ")");

    enum Decision { YES, NO, REPEAT, ORDER, UNCLEAR }

    record Intent(Decision decision, String sku, Integer grams, String pickupTime) {}

    static String confirmationCode(String utterance) {
        if (questionMark(utterance)) return null;
        var candidate = CONFIRM_CODE.matcher(words(utterance));
        if (!candidate.matches()) return null;
        var code = new StringBuilder(4);
        for (String token : candidate.group(1).split(" ")) {
            if (token.matches("[0-9]{1,4}")) code.append(token);
            else {
                int digit = confirmationDigit(token);
                if (digit < 0) return null;
                code.append(digit);
            }
        }
        return code.length() == 4 ? code.toString() : null;
    }

    private static int confirmationDigit(String word) {
        if (word.equals("पांच")) return 5;
        if (word.equals("छः")) return 6;
        for (int digit = 0; digit < 10; digit++) {
            if (word.equals(ENGLISH_DIGITS[digit]) || word.equals(HINDI_DIGITS[digit]) || word.equals(HINGLISH_DIGITS[digit])) return digit;
        }
        return -1;
    }

    static Intent parse(String utterance) {
        String text = asciiDigits(Normalizer.normalize(utterance, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll("[\\p{Cf}]", "");
        String words = text.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
        if (!questionMark(text) && (YES.contains(words) || POLITE_YES.matcher(words).matches())) {
            return new Intent(Decision.YES, null, null, null);
        }
        if (NO.contains(words)) return new Intent(Decision.NO, null, null, null);
        if (REPEAT.contains(words)) return new Intent(Decision.REPEAT, null, null, null);

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
            if (marker == null || "baje".equals(marker) || "बजे".equals(marker)) {
                marker = clockTime && clocks.group(1).startsWith("0") ? "am" : nearbyPeriod(text, clocks.start(), clocks.end(), hours);
            }
            pickup = clock(hours, minutes, marker);
            lastTimeAt = clocks.start();
        }
        var namedClocks = NAMED_CLOCK.matcher(text);
        while (namedClocks.find()) {
            boolean half = namedClocks.group(1) != null || Set.of("thirty", "तीस").contains(
                    namedClocks.group(3) == null ? "" : namedClocks.group(3));
            if (namedClocks.start() < lastTimeAt || (!half && namedClocks.group(3) == null)) continue;
            String hour = namedClocks.group(2);
            int hours = namedHour(hour);
            String suffix = namedClocks.group(3);
            String marker = "pm".equals(suffix) || "पीएम".equals(suffix) ? "pm" : nearbyPeriod(text, namedClocks.start(), namedClocks.end(), hours);
            pickup = clock(hours, half ? 30 : 0, marker);
        }
        return new Intent(item != null || grams != null || pickup != null ? Decision.ORDER : Decision.UNCLEAR,
                item, grams, pickup);
    }

    private static boolean questionMark(String utterance) {
        return utterance.indexOf('?') >= 0 || utterance.indexOf('？') >= 0 || utterance.indexOf('؟') >= 0;
    }

    static Integer pieces(String spoken, String itemName) {
        String text = words(spoken);
        var quantity = PIECES.matcher(text);
        Integer count = null;
        while (quantity.find()) count = count(quantity.group(1));
        if (count != null) return count;
        String item = itemName == null ? "" : words(itemName);
        if (!item.isEmpty()) {
            var followedByItem = Pattern.compile("(?<!" + BOUNDARY + ")" + PIECE_COUNT + "\\s+(?:of\\s+)?"
                    + Pattern.quote(item) + "s?(?!" + BOUNDARY + ")").matcher(text);
            while (followedByItem.find()) count = count(followedByItem.group(1));
        }
        var simple = Pattern.compile("^(?:(?:actually|instead|नहीं|बस) )?" + PIECE_COUNT + "(?: please| चाहिए)?$").matcher(text);
        return count != null ? count : simple.matches() ? count(simple.group(1)) : null;
    }

    static String words(String spoken) {
        return asciiDigits(Normalizer.normalize(spoken, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll("[\\p{Cf}]", "").replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
    }

    static Integer pickupPosition(String spoken) {
        String text = words(spoken).replaceFirst(" (?:one|option|वाला|वाली)(?: कर (?:दो|दीजिए))?$", "");
        return switch (text) {
            case "first", "the first", "पहला", "पहली" -> 0;
            case "second", "the second", "दूसरा", "दूसरी" -> 1;
            case "third", "the third", "तीसरा", "तीसरी" -> 2;
            default -> null;
        };
    }

    static boolean tomorrow(String spoken) {
        String text = " " + words(spoken) + " ";
        int next = Math.max(text.lastIndexOf(" tomorrow "), text.lastIndexOf(" कल "));
        int today = Math.max(text.lastIndexOf(" today "), text.lastIndexOf(" आज "));
        return next > today;
    }

    private static int namedHour(String hour) {
        if (hour.matches(SIX)) return 6;
        if (hour.matches(SEVEN)) return 7;
        if (hour.matches(EIGHT)) return 8;
        if (hour.matches(NINE)) return 9;
        return switch (hour) {
            case "one", "ek", "एक" -> 1;
            case "two", "do", "दो" -> 2;
            case "three", "teen", "तीन" -> 3;
            case "four", "chaar", "char", "चार" -> 4;
            case "five", "paanch", "पाँच", "पांच" -> 5;
            case "ten", "das", "दस" -> 10;
            case "eleven", "gyarah", "ग्यारह" -> 11;
            default -> 12;
        };
    }

    private static String nearbyPeriod(String text, int start, int end, int hour) {
        String nearest = null;
        var matches = PERIOD.matcher(text).region(0, start);
        while (matches.find()) nearest = matches.group();
        var following = PERIOD.matcher(text.substring(end).stripLeading());
        if (following.lookingAt()) nearest = following.group();
        return switch (nearest == null ? "" : nearest) {
            case "morning", "subah", "सुबह", "सवेरे" -> "am";
            case "night", "रात" -> hour == 12 ? "am" : "pm";
            case "" -> null;
            default -> "pm";
        };
    }

    private static int count(String value) {
        return Integer.parseInt(QUANTITY_WORDS.getOrDefault(value, value));
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
