package com.rushpromise.booking;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BookingModels {
    private BookingModels() {}

    public record QuoteRequest(String sessionId, String customerName, String phone, String sku,
                               @JsonAlias("quantity") int grams, String pickupTime) {}
    public record AcceptRequest(String sessionId, String turnId) {}
    public record CountRequest(Integer grams) {}
    public record ResetRequest(String preset, String phone) {}
    public record ClockRequest(String now) {}
    public record ProfileRequest(String displayName) {}
    public record ShopProfile(String displayName, Instant updatedAt) {}
    public record ShopMenu(String displayName, List<MenuItem> items) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MenuItem(String sku, String itemName, Integer pricePerKgPaise, List<MenuPortion> portions,
                           String unit, int pricePerUnitPaise) {}
    public record MenuPortion(@JsonInclude(JsonInclude.Include.NON_NULL) Integer grams, int pricePaise,
                              String readyPickup, String futurePickup, int quantity, String unit) {
        public MenuPortion(int grams, int pricePaise, String readyPickup, String futurePickup) {
            this(grams, pricePaise, readyPickup, futurePickup, grams, "GRAMS");
        }
    }

    public record Availability(AvailabilityOption option, String reason) {}
    public record AvailabilityOption(String sku, String itemName, int grams, String pickupTime, Instant pickupAt,
                                     String batchId, String batchReadyTime, int batchFreeGrams, String batchStatus, String unit) {}

    public record Policy(String version, String source, Instant fetchedAt, Instant evaluatedAt,
                         boolean rushMode, int maxGrams, int approvalAboveGrams, int approvalHoldMinutes,
                         int leadMinutes, int ttlSeconds, int pickupGraceMinutes, Instant providerUpdatedAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) SlotAudit slot,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String digest) {
        public Policy(String version, String source, Instant fetchedAt, Instant evaluatedAt, boolean rushMode,
                      int maxGrams, int approvalAboveGrams, int approvalHoldMinutes, int leadMinutes,
                      int ttlSeconds, int pickupGraceMinutes) {
            this(version, source, fetchedAt, evaluatedAt, rushMode, maxGrams, approvalAboveGrams,
                    approvalHoldMinutes, leadMinutes, ttlSeconds, pickupGraceMinutes, null, null, null);
        }

        public Policy(String version, String source, Instant fetchedAt, Instant evaluatedAt, boolean rushMode,
                      int maxGrams, int approvalAboveGrams, int approvalHoldMinutes, int leadMinutes,
                      int ttlSeconds, int pickupGraceMinutes, Instant providerUpdatedAt) {
            this(version, source, fetchedAt, evaluatedAt, rushMode, maxGrams, approvalAboveGrams,
                    approvalHoldMinutes, leadMinutes, ttlSeconds, pickupGraceMinutes, providerUpdatedAt, null, null);
        }

        public Policy(String version, String source, Instant fetchedAt, Instant evaluatedAt, boolean rushMode,
                      int maxGrams, int approvalAboveGrams, int approvalHoldMinutes, int leadMinutes,
                      int ttlSeconds, int pickupGraceMinutes, Instant providerUpdatedAt, SlotAudit slot) {
            this(version, source, fetchedAt, evaluatedAt, rushMode, maxGrams, approvalAboveGrams,
                    approvalHoldMinutes, leadMinutes, ttlSeconds, pickupGraceMinutes, providerUpdatedAt, slot,
                    slot == null ? null : slot.digest());
        }

        public Policy withSlot(SlotAudit audit) {
            return new Policy(version, source, fetchedAt, evaluatedAt, rushMode, maxGrams, approvalAboveGrams,
                    approvalHoldMinutes, leadMinutes, ttlSeconds, pickupGraceMinutes, providerUpdatedAt, audit,
                    audit == null ? digest : audit.digest());
        }
    }

    public record SlotAudit(String digest, String profile, String reasonCode, int used, int capacity, String why,
                            String whyHindi, @JsonInclude(JsonInclude.Include.NON_NULL) PolicyTerms terms) {
        public SlotAudit(String digest, String profile, String reasonCode, int used, int capacity, String why, String whyHindi) {
            this(digest, profile, reasonCode, used, capacity, why, whyHindi, null);
        }

        public SlotAudit withTerms(PolicyTerms value) {
            return new SlotAudit(digest, profile, reasonCode, used, capacity, why, whyHindi, value);
        }
    }
    public record PolicyTerms(String sku, int quantity, String unit, int pricePaise, Instant pickupAt,
                              boolean approvalRequired, Integer holdMinutes) {}

    public record PickupPolicyOption(String pickupTime, Boolean requiresApproval, Policy policy) {}
    public record PickupPolicyComparison(String digest, String sku, String itemName, int grams, int pricePaise,
                                         List<PickupPolicyOption> options) {}

    public record Quote(UUID id, String sessionId, String sku, String itemName, int grams,
                        int pricePaise, String pickupTime, Instant pickupAt, String batchId,
                        boolean requiresApproval, Instant expiresAt, Policy policy, String unit) {
        public Quote { if (unit == null) unit = "GRAMS"; }

        public Quote(UUID id, String sessionId, String sku, String itemName, int grams, int pricePaise,
                     String pickupTime, Instant pickupAt, String batchId, boolean requiresApproval, Instant expiresAt, Policy policy) {
            this(id, sessionId, sku, itemName, grams, pricePaise, pickupTime, pickupAt, batchId, requiresApproval, expiresAt, policy, "GRAMS");
        }
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) public int quantity() { return grams; }
    }

    public record Order(long id, UUID runId, UUID acceptedQuoteId, String acceptedTurnId,
                        String customerName, String phone, String sku, String itemName, int grams,
                        int pricePaise, String pickupTime, Instant pickupAt, String batchId, String status,
                        Instant approvalExpiresAt, Policy policy, Instant createdAt, Instant packedAt,
                        Instant releasedAt, boolean returnToTray, boolean checkStock, String unit) {
        public Order { if (unit == null) unit = "GRAMS"; }

        public Order(long id, UUID runId, UUID acceptedQuoteId, String acceptedTurnId, String customerName,
                     String phone, String sku, String itemName, int grams, int pricePaise, String pickupTime,
                     Instant pickupAt, String batchId, String status, Instant approvalExpiresAt, Policy policy,
                     Instant createdAt, Instant packedAt, Instant releasedAt, boolean returnToTray, boolean checkStock) {
            this(id, runId, acceptedQuoteId, acceptedTurnId, customerName, phone, sku, itemName, grams, pricePaise,
                    pickupTime, pickupAt, batchId, status, approvalExpiresAt, policy, createdAt, packedAt, releasedAt,
                    returnToTray, checkStock, "GRAMS");
        }
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) public int quantity() { return grams; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(@JsonIgnore int status, String code, String reason, Quote quote, Order order) {
        public static Outcome error(int status, String code, String reason) {
            return new Outcome(status, code, reason, null, null);
        }
    }
}
