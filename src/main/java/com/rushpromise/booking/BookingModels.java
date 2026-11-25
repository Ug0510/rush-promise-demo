package com.rushpromise.booking;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BookingModels {
    private BookingModels() {}

    public record QuoteRequest(String sessionId, String customerName, String phone, String sku,
                               int grams, String pickupTime) {}
    public record AcceptRequest(String sessionId, String turnId) {}
    public record CountRequest(Integer grams) {}
    public record ResetRequest(String preset, String phone) {}
    public record ClockRequest(String now) {}
    public record ProfileRequest(String displayName) {}
    public record ShopProfile(String displayName, Instant updatedAt) {}
    public record ShopMenu(String displayName, List<MenuItem> items) {}
    public record MenuItem(String sku, String itemName, int pricePerKgPaise, List<MenuPortion> portions) {}
    public record MenuPortion(int grams, int pricePaise, String readyPickup, String futurePickup) {}

    public record Availability(AvailabilityOption option, String reason) {}
    public record AvailabilityOption(String sku, String itemName, int grams, String pickupTime, Instant pickupAt,
                                     String batchId, String batchReadyTime, int batchFreeGrams, String batchStatus) {}

    public record Policy(String version, String source, Instant fetchedAt, Instant evaluatedAt,
                         boolean rushMode, int maxGrams, int approvalAboveGrams, int approvalHoldMinutes,
                         int leadMinutes, int ttlSeconds, int pickupGraceMinutes, Instant providerUpdatedAt) {
        public Policy(String version, String source, Instant fetchedAt, Instant evaluatedAt, boolean rushMode,
                      int maxGrams, int approvalAboveGrams, int approvalHoldMinutes, int leadMinutes,
                      int ttlSeconds, int pickupGraceMinutes) {
            this(version, source, fetchedAt, evaluatedAt, rushMode, maxGrams, approvalAboveGrams,
                    approvalHoldMinutes, leadMinutes, ttlSeconds, pickupGraceMinutes, null);
        }
    }

    public record Quote(UUID id, String sessionId, String sku, String itemName, int grams,
                        int pricePaise, String pickupTime, Instant pickupAt, String batchId,
                        boolean requiresApproval, Instant expiresAt, Policy policy) {}

    public record Order(long id, UUID runId, UUID acceptedQuoteId, String acceptedTurnId,
                        String customerName, String phone, String sku, String itemName, int grams,
                        int pricePaise, String pickupTime, Instant pickupAt, String batchId, String status,
                        Instant approvalExpiresAt, Policy policy, Instant createdAt, Instant packedAt,
                        Instant releasedAt, boolean returnToTray, boolean checkStock) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(@JsonIgnore int status, String code, String reason, Quote quote, Order order) {
        public static Outcome error(int status, String code, String reason) {
            return new Outcome(status, code, reason, null, null);
        }
    }
}
