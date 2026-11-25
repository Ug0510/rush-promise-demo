package com.rushpromise.booking;

import static com.rushpromise.booking.BookingModels.*;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@Profile("!worker")
public class BookingController {
    private final BookingService booking;
    private final ShopService shop;
    private final StaffEvents staffEvents;
    private final PolicyService policies;

    BookingController(BookingService booking, ShopService shop, StaffEvents staffEvents, PolicyService policies) {
        this.booking = booking;
        this.shop = shop;
        this.staffEvents = staffEvents;
        this.policies = policies;
    }

    @PostMapping("/api/quotes")
    ResponseEntity<Outcome> quote(@RequestBody QuoteRequest request) {
        return response(booking.quote(request));
    }

    @PostMapping("/api/quotes/{id}/accept")
    ResponseEntity<Outcome> accept(@PathVariable String id, @RequestBody AcceptRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return response(booking.accept(id, request, key));
    }

    @PostMapping("/api/quotes/{id}/resolve")
    ResponseEntity<Outcome> resolve(@PathVariable String id, @RequestBody AcceptRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return response(booking.resolve(id, request, key));
    }

    @GetMapping("/api/orders")
    List<Order> orders() {
        return shop.orders();
    }

    @GetMapping("/api/orders/{id}/messages")
    List<Map<String, Object>> messages(@PathVariable long id) {
        return shop.messages(id);
    }

    @GetMapping("/api/staff/snapshot")
    Map<String, Object> snapshot() {
        return shop.snapshot(policies.current());
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events() {
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(staffEvents.connect());
    }

    @GetMapping({"/api/stock", "/api/availability"})
    Map<String, Object> stock() {
        return shop.stock();
    }

    @GetMapping("/api/availability/next")
    ResponseEntity<Availability> nextAvailability(@RequestParam String sku, @RequestParam int grams,
                                                   @RequestParam(required = false) String pickupTime) {
        Availability result = booking.nextAvailability(sku, grams, pickupTime);
        String reason = result.reason();
        int status = switch (reason == null ? "" : reason) {
            case "INVALID_REQUEST" -> 400;
            case "ITEM_NOT_FOUND" -> 404;
            case "TOO_LARGE", "UNSUPPORTED_WEIGHT" -> 422;
            default -> 200;
        };
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/api/shop/profile")
    ShopProfile profile() {
        return shop.profile();
    }

    @PostMapping("/api/shop/profile")
    ShopProfile updateProfile(@RequestBody ProfileRequest request) {
        try {
            return shop.updateProfile(request.displayName());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @GetMapping("/api/shop/menu")
    ShopMenu menu() {
        return booking.catalog();
    }

    @GetMapping("/api/staff/session")
    Map<String, String> session(@RequestAttribute(BookingAccessFilter.STAFF_ROLE_ATTRIBUTE) String role) {
        return Map.of("role", role);
    }

    @GetMapping("/api/policy")
    Policy policy() {
        return policies.current();
    }

    @PostMapping("/api/orders/{id}/{action}")
    ResponseEntity<Outcome> action(@PathVariable long id, @PathVariable String action,
                                   @RequestBody(required = false) CountRequest request) {
        return response(shop.action(id, action, request == null ? null : request.grams()));
    }

    @PostMapping("/api/batches/{id}/{action}")
    ResponseEntity<Outcome> batchAction(@PathVariable String id, @PathVariable String action,
                                        @RequestBody(required = false) CountRequest request) {
        return response(shop.batchAction(id, action, request == null ? null : request.grams()));
    }

    @PostMapping({"/ops/demo/reset", "/api/demo/reset"})
    ResponseEntity<Outcome> reset(@RequestBody(required = false) ResetRequest request) {
        return response(shop.reset(request));
    }

    @PostMapping("/api/demo/clock")
    ResponseEntity<Outcome> clock(@RequestBody ClockRequest request) {
        return response(shop.changeClock(request.now()));
    }

    private static ResponseEntity<Outcome> response(Outcome result) {
        return ResponseEntity.status(result.status()).body(result);
    }
}
