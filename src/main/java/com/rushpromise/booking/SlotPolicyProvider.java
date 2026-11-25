package com.rushpromise.booking;

import java.math.BigDecimal;

import static com.rushpromise.booking.BookingModels.Policy;
import static com.rushpromise.booking.BookingModels.SlotAudit;

public interface SlotPolicyProvider {
    // A null snapshot means this opt-in integration is disabled. An enabled cold start supplies a fallback snapshot.
    Snapshot capture();

    @FunctionalInterface
    interface Snapshot {
        Policy evaluate(int orderGrams, int slotUsed, int slotCapacity);
    }

    static SlotAudit audit(String digest, String profile, String reasonCode, int grams, int threshold, int used, int capacity) {
        long load = 100L * used / capacity;
        String english = switch (reasonCode) {
            case "SLOT_NEAR_CAPACITY" -> "This pickup is " + load + "% full";
            case "RUSH_HOUR" -> "Rush mode is on";
            case "STANDARD" -> "Standard pickup rules apply";
            case "FALLBACK_POLICY" -> "Backup pickup rules apply";
            case "VERIFIED_REGULAR" -> "Verified regular customer rules apply";
            default -> throw new IllegalArgumentException("Unknown pickup policy reason");
        };
        String hindi = switch (reasonCode) {
            case "SLOT_NEAR_CAPACITY" -> "इस समय काफ़ी ऑर्डर हैं। " + load + " प्रतिशत जगह भर गई है";
            case "RUSH_HOUR" -> "व्यस्त समय के नियम लागू हैं";
            case "STANDARD" -> "सामान्य पिकअप नियम लागू हैं";
            case "FALLBACK_POLICY" -> "फ़िलहाल हम एहतियात वाले पिकअप नियम अपना रहे हैं";
            case "VERIFIED_REGULAR" -> "सत्यापित नियमित ग्राहक के नियम लागू हैं";
            default -> throw new IllegalArgumentException("Unknown pickup policy reason");
        };
        String amount = kilograms(grams);
        String limit = kilograms(threshold);
        boolean approval = grams > threshold;
        String why = english + ". " + amount + " kg " + (approval ? "exceeds" : "is within")
                + " the " + limit + " kg approval threshold.";
        String whyHindi = approval ? hindi + "। " + amount + " किलो का ऑर्डर " + limit
                + " किलो की सीमा से ज़्यादा है। इसके लिए दुकान की मंज़ूरी चाहिए।"
                : hindi + "। " + amount + " किलो का ऑर्डर " + limit + " किलो की सीमा के भीतर है।";
        return new SlotAudit(digest, profile, reasonCode, used, capacity, why, whyHindi);
    }

    private static String kilograms(int grams) {
        return BigDecimal.valueOf(grams, 3).stripTrailingZeros().toPlainString();
    }
}
