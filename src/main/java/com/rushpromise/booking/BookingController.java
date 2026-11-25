package com.rushpromise.booking;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.booking.BookingStore.at;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("!worker")
public class BookingController {
    private final BookingService booking;
    private final ShopService shop;
    private final BookingStore store;
    private final PolicyService policies;

    BookingController(BookingService booking, ShopService shop, BookingStore store, PolicyService policies) {
        this.booking = booking;
        this.shop = shop;
        this.store = store;
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
        return store.db.query("SELECT id,kind,status,created_at,updated_at FROM outbox_messages WHERE order_id = ? ORDER BY created_at,id",
                (row, index) -> Map.of("id", row.getString("id"), "kind", row.getString("kind"), "status", row.getString("status"),
                        "createdAt", at(row, "created_at"), "updatedAt", at(row, "updated_at")), id);
    }

    @GetMapping({"/api/stock", "/api/availability"})
    Map<String, Object> stock() {
        return shop.stock();
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
