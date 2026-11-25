package com.rushpromise.delivery;

record DeliveryResult(String status, String providerSid, String error) {
    static DeliveryResult unknown(String reason) {
        return new DeliveryResult("UNKNOWN", null, reason);
    }

    static DeliveryResult providerStatus(String status, String sid, String error) {
        return switch (status) {
            case "accepted", "queued", "sending", "sent", "scheduled" ->
                new DeliveryResult("SUBMITTED", sid, null);
            case "delivered", "read" -> new DeliveryResult("DELIVERED", sid, null);
            case "failed", "undelivered", "canceled" ->
                new DeliveryResult("FAILED", sid, error == null ? "PROVIDER_REJECTED" : error);
            default -> null;
        };
    }

    static boolean canAdvance(String previous, String next) {
        if (previous.equals(next) || previous.equals("DELIVERED") || previous.equals("SIMULATED")) {
            return false;
        }
        return switch (next) {
            case "DELIVERED" -> true;
            case "SUBMITTED" -> previous.equals("SENDING") || previous.equals("UNKNOWN");
            case "FAILED" -> previous.equals("SENDING") || previous.equals("UNKNOWN") || previous.equals("SUBMITTED");
            default -> previous.equals("SENDING");
        };
    }
}
