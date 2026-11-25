package com.rushpromise.booking;

import com.rushpromise.platform.TenantContext;

import static com.rushpromise.booking.BookingModels.*;
import static com.rushpromise.booking.BookingStore.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("!worker")
public class BookingService {
    private static final SecureRandom PICKUP_CODES = new SecureRandom();
    private final BookingStore store;
    private final PolicyService policyService;
    private final TransactionTemplate transactions;
    private final Timer batchLockTime;
    private final SlotPolicyProvider slotPolicies;
    private final NativePickupIdentity nativePickup;
    private final GuestCheckouts guestCheckouts;

    BookingService(BookingStore store, PolicyService policyService, TransactionTemplate transactions, MeterRegistry metrics) {
        this(store, policyService, transactions, metrics, null, null, null);
    }

    @Autowired
    BookingService(BookingStore store, PolicyService policyService, TransactionTemplate transactions, MeterRegistry metrics,
                   @Nullable SlotPolicyProvider slotPolicies, NativePickupIdentity nativePickup, GuestCheckouts guestCheckouts) {
        this.store = store;
        this.policyService = policyService;
        this.transactions = transactions;
        this.batchLockTime = metrics.timer("rush.booking.batch.lock");
        this.slotPolicies = slotPolicies;
        this.nativePickup = nativePickup;
        this.guestCheckouts = guestCheckouts;
    }

    public Outcome quote(QuoteRequest request) {
        if (!validQuoteRequest(request) || !validPhone(request.phone())) return Outcome.error(400, "INVALID_REQUEST", null);
        return quote(request, null, NativeIdentityKind.CALLER);
    }

    public String nativePickupSubject(String sessionId, String providerSubject) {
        return nativePickup == null ? null : nativePickup.subject(sessionId, providerSubject);
    }

    public String nativeInteractionSubject(String sessionId) {
        return nativePickup == null ? null : nativePickup.interactionSubject(sessionId);
    }

    public Outcome quoteNativePickup(QuoteRequest request, String subject) {
        return quoteNativePickup(request, subject, NativeIdentityKind.CALLER);
    }

    public Outcome quoteNativePickup(QuoteRequest request, String subject, NativeIdentityKind kind) {
        if (!validQuoteRequest(request) || request.phone() != null || !NativePickupIdentity.isSubject(subject)
                || kind == null || kind == NativeIdentityKind.GUEST || !nativePickupAllowed(request.sessionId())
                || (kind == NativeIdentityKind.CALL && !subject.equals(nativeInteractionSubject(request.sessionId())))) {
            return Outcome.error(404, "NATIVE_PICKUP_UNAVAILABLE", null);
        }
        return quote(request, subject, kind);
    }

    public Outcome quoteGuestPickup(QuoteRequest request, String subject) {
        if (!validQuoteRequest(request) || request.phone() != null || subject == null || guestCheckouts == null
                || !subject.equals(guestCheckouts.subject(request.sessionId()))) {
            return Outcome.error(404, "GUEST_PICKUP_UNAVAILABLE", null);
        }
        return quote(request, subject, NativeIdentityKind.GUEST);
    }

    private Outcome quote(QuoteRequest request, String subject, NativeIdentityKind kind) {
        var captured = capturePolicies();
        return transactions.execute(transaction -> {
            Shop shop = store.shop(true);
            var item = item(request.sku());
            if (item == null) return Outcome.error(404, "ITEM_NOT_FOUND", null);
            String invalid = invalidQuantity(request.sku(), item, request.grams(), captured);
            if (invalid != null) return Outcome.error(422, invalid, null);
            var pickup = requestedTime(shop, request.pickupTime(), request.requestedPickupAt());
            if (pickup == null) return Outcome.error(400, "INVALID_PICKUP_DATE", null);
            store.db.update("INSERT INTO booking_sessions (id,run_id,phone,native_pickup_subject,native_identity_kind) VALUES (?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                    request.sessionId(), shop.runId(), request.phone(), subject, kind.name());
            var session = session(request.sessionId());
            if (!Objects.equals(session.phone(), request.phone()) || !Objects.equals(session.nativeSubject(), subject) || session.kind() != kind) {
                return Outcome.error(409, "SESSION_MISMATCH", null);
            }
            if (alreadyBookedPickup(shop, session)) return Outcome.error(409, "CALL_ALREADY_BOOKED", null);
            var match = replacement(shop, request, item, captured, pickup, kind);
            if (match == null) {
                store.db.update("UPDATE booking_sessions SET latest_quote_id = NULL WHERE id = ?", request.sessionId());
                if (kind.singlePickup() && findOption(shop, captured, item, request.sku(), request.grams(), pickup, null) != null) {
                    return Outcome.error(409, "CALL_APPROVAL_UNAVAILABLE", null);
                }
                String constraint = scheduleReason(shop, pickup);
                return Outcome.error(409, "CONFLICT", constraint == null ? "NONE_AVAILABLE" : constraint);
            }
            String reason = pickup.equals(match.pickupAt()) ? null : alternativeReason(shop, captured, item, request, pickup, kind);
            return new Outcome(200, "QUOTED", reason, match, null);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PickupPreview previewPickup(String sku, int quantity, String pickupTime) {
        if (!validPickupTime(pickupTime)) return null;
        var options = availablePickups(sku, quantity, pickupTime, 1);
        return options.isEmpty() ? null : options.getFirst();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PickupPreview> availablePickups(String sku, int quantity, String start, int limit) {
        return availablePickups(sku, quantity, start, null, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PickupPreview> availablePickups(String sku, int quantity, String start, Instant requestedPickupAt, int limit) {
        if (!validIdentifier(sku) || (start != null && !validPickupTime(start)) || limit < 1) return List.of();
        var captured = capturePolicies();
        var item = readItem(sku, false);
        if (item == null || invalidQuantity(sku, item, quantity, captured) != null) return List.of();
        var shop = store.shop();
        var requested = start == null ? shop.now() : requestedTime(shop, start, requestedPickupAt);
        if (requested == null) return List.of();
        return findOptions(shop, captured, item, sku, quantity, requested, null, Math.min(limit, 12)).stream()
                .map(option -> new PickupPreview(item.name(), quantity, item.unit(), price(item, quantity), shopTime(option.pickupAt()),
                        requiresApproval(item, quantity, option.policy()), option.pickupAt())).toList();
    }

    public PickupHours pickupHours() { return store.pickupHours(); }

    public Instant currentShopTime() { return store.shop().now(); }

    @Transactional
    public PickupHours updatePickupHours(PickupHoursInput input) {
        if (TenantContext.legacy()) throw new IllegalArgumentException("Choose pickup hours");
        validatePickupHours(input);
        LocalTime opensAt = input.allDay() ? null : halfHour(input.opensAt());
        LocalTime closesAt = input.allDay() ? null : halfHour(input.closesAt());
        store.db.update("UPDATE shop_state SET pickup_all_day=?,pickup_opens_at=?,pickup_closes_at=? WHERE id=1", input.allDay(), opensAt, closesAt);
        return store.pickupHours();
    }

    public static void validatePickupHours(PickupHoursInput input) {
        if (input == null || input.allDay() == null) throw new IllegalArgumentException("Choose pickup hours");
        if (!input.allDay() && halfHour(input.opensAt()).isAfter(halfHour(input.closesAt()))) {
            throw new IllegalArgumentException("Closing time must be at or after opening time");
        }
    }

    private static LocalTime halfHour(String value) {
        if (!validPickupTime(value) || !(value.endsWith(":00") || value.endsWith(":30"))) {
            throw new IllegalArgumentException("Choose a time on the hour or half hour");
        }
        return LocalTime.parse(value);
    }

    public Availability nextAvailability(String sku, int grams, String pickupTime) {
        if (!validIdentifier(sku) || (pickupTime != null && !validPickupTime(pickupTime))) {
            return new Availability(null, "INVALID_REQUEST");
        }
        var captured = capturePolicies();
        return transactions.execute(transaction -> {
            var shop = store.shop();
            var item = item(sku);
            if (item == null) return new Availability(null, "ITEM_NOT_FOUND");
            String invalid = invalidQuantity(sku, item, grams, captured);
            if (invalid != null) return new Availability(null, invalid);
            Instant requested = pickupTime == null ? shop.now() : requestedTime(shop, pickupTime);
            var option = findOption(shop, captured, item, sku, grams, requested, null);
            String constraint = scheduleReason(shop, requested);
            if (option == null) return new Availability(null, pickupTime != null && constraint != null ? constraint : "NONE_AVAILABLE");
            String reason = pickupTime == null || option.pickupAt().equals(requested) ? null
                    : availabilityReason(shop, captured, item, sku, grams, requested);
            var view = new AvailabilityOption(sku, item.name(), grams, shopTime(option.pickupAt()), option.pickupAt(),
                    option.batchId(), shopTime(option.batchReadyAt()), option.freeGrams(), option.batchStatus(), item.unit());
            return new Availability(view, reason);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShopMenu catalog() {
        var captured = capturePolicies();
        var shop = store.shop();
        List<CatalogItem> menu = store.db.query("SELECT sku,name,price_per_kg_paise,sale_unit FROM items ORDER BY name",
                (row, index) -> new CatalogItem(row.getString("sku"), new Item(row.getString("name"), row.getInt("price_per_kg_paise"), row.getString("sale_unit"))));
        List<MenuItem> items = menu.stream().map(entry -> {
            boolean pieces = "PIECE".equals(entry.item().unit());
            List<MenuPortion> portions = (pieces ? List.of(1, 6) : List.of(250, 500)).stream().map(grams -> {
                var ready = findOption(shop, captured, entry.item(), entry.sku(), grams, shop.now(), true);
                var future = findOption(shop, captured, entry.item(), entry.sku(), grams, shop.now(), false);
                return new MenuPortion(pieces ? null : grams, price(entry.item(), grams),
                        ready == null ? null : shopTime(ready.pickupAt()), future == null ? null : shopTime(future.pickupAt()),
                        grams, entry.item().unit());
            }).toList();
            return new MenuItem(entry.sku(), entry.item().name(), pieces ? null : entry.item().pricePerUnitPaise(), portions,
                    entry.item().unit(), entry.item().pricePerUnitPaise());
        }).toList();
        return new ShopMenu(store.profile().displayName(), items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PickupPolicyComparison pickupPolicies(String sku, int grams, List<String> times) {
        if (!validIdentifier(sku) || grams < 1 || times.isEmpty() || times.size() > 4
                || times.stream().anyMatch(time -> !validPickupTime(time))) return null;
        var captured = capturePolicies();
        var shop = store.shop();
        var item = store.find("SELECT name,price_per_kg_paise,sale_unit FROM items WHERE sku=?",
                (row, index) -> new Item(row.getString("name"), row.getInt("price_per_kg_paise"), row.getString("sale_unit")), sku);
        if (item == null || invalidQuantity(sku, item, grams, captured) != null || !captured.applies(item)) return null;
        var options = times.stream().distinct().map(time -> {
            Instant requested = requestedTime(shop, time);
            var option = findOption(shop, captured, item, sku, grams, requested, null);
            if (option == null || !option.pickupAt().equals(requested)) return new PickupPolicyOption(time, null, null);
            boolean approval = requiresApproval(item, grams, option.policy());
            return new PickupPolicyOption(time, approval, withTerms(option.policy(), item, sku, grams, requested, approval));
        }).toList();
        String digest = options.stream().map(PickupPolicyOption::policy).filter(java.util.Objects::nonNull)
                .map(Policy::slot).filter(java.util.Objects::nonNull).map(SlotAudit::digest).findFirst().orElse(null);
        return new PickupPolicyComparison(digest, sku, item.name(), grams, price(item, grams), options);
    }

    @Transactional(readOnly = true)
    public List<PickupPolicyComparison> savedPolicyComparisons() {
        return store.db.query("""
                WITH recent AS (
                    SELECT session_id,sku,item_name,grams,price_paise,pickup_at,requires_approval,policy,
                        COALESCE((policy->>'evaluatedAt')::timestamptz,expires_at) AS observed_at
                    FROM quotes WHERE policy->'slot' IS NOT NULL
                    ORDER BY observed_at DESC LIMIT 100
                ), pairs AS (
                    SELECT DISTINCT ON (a.session_id,a.sku,a.grams,a.pickup_at,b.pickup_at,a.policy#>>'{slot,digest}')
                        a.sku,a.item_name,a.grams,a.price_paise,a.policy#>>'{slot,digest}' AS digest,
                        a.pickup_at AS a_pickup,a.requires_approval AS a_approval,a.policy AS a_policy,
                        b.pickup_at AS b_pickup,b.requires_approval AS b_approval,b.policy AS b_policy,
                        greatest(a.observed_at,b.observed_at) AS latest
                    FROM recent a JOIN recent b ON a.session_id=b.session_id AND a.sku=b.sku AND a.grams=b.grams
                        AND a.item_name=b.item_name AND a.price_paise=b.price_paise AND a.pickup_at<b.pickup_at
                        AND a.requires_approval<>b.requires_approval AND a.policy#>>'{slot,digest}'=b.policy#>>'{slot,digest}'
                    ORDER BY a.session_id,a.sku,a.grams,a.pickup_at,b.pickup_at,a.policy#>>'{slot,digest}',latest DESC
                ) SELECT * FROM pairs ORDER BY latest DESC LIMIT 4
                """, (row, index) -> new PickupPolicyComparison(row.getString("digest"), row.getString("sku"),
                row.getString("item_name"), row.getInt("grams"), row.getInt("price_paise"), List.of(
                new PickupPolicyOption(shopTime(at(row, "a_pickup")), row.getBoolean("a_approval"), store.read(row.getString("a_policy"), Policy.class)),
                new PickupPolicyOption(shopTime(at(row, "b_pickup")), row.getBoolean("b_approval"), store.read(row.getString("b_policy"), Policy.class)))));
    }

    public Outcome accept(String quoteId, AcceptRequest request, String idempotencyKey) {
        return accept(quoteId, request, idempotencyKey, false);
    }

    public Outcome acceptNativePickup(String quoteId, AcceptRequest request) {
        return accept(quoteId, request, null, true);
    }

    private Outcome accept(String quoteId, AcceptRequest request, String idempotencyKey, boolean contactless) {
        UUID id = parseId(quoteId);
        if (id == null || request == null || !validIdentifier(request.sessionId()) || !validIdentifier(request.turnId())
                || (idempotencyKey != null && !validIdentifier(idempotencyKey))) {
            return Outcome.error(400, "INVALID_REQUEST", null);
        }
        String derivedKey = "call:" + request.sessionId() + ":quote:" + id;
        String key = idempotencyKey == null ? derivedKey : "client:" + idempotencyKey;
        try {
            return transactions.execute(transaction -> acceptLocked(id, request, key, contactless));
        } catch (DataAccessException | TransactionException failure) {
            // A lost commit acknowledgement cannot tell us which outcome was durable. Resolve through the same key.
            return Outcome.error(503, "BOOKING_UNCERTAIN", null);
        }
    }

    public Outcome resolve(String quoteId, AcceptRequest request, String idempotencyKey) {
        return resolve(quoteId, request, idempotencyKey, false);
    }

    public Outcome resolveNativePickup(String quoteId, AcceptRequest request) {
        return resolve(quoteId, request, null, true);
    }

    private Outcome resolve(String quoteId, AcceptRequest request, String idempotencyKey, boolean contactless) {
        UUID id = parseId(quoteId);
        if (id == null || request == null || !validIdentifier(request.sessionId()) || !validIdentifier(request.turnId())
                || (idempotencyKey != null && !validIdentifier(idempotencyKey))) {
            return Outcome.error(400, "INVALID_REQUEST", null);
        }
        String key = idempotencyKey == null ? "call:" + request.sessionId() + ":quote:" + id : "client:" + idempotencyKey;
        try {
            return transactions.execute(transaction -> {
                var shop = store.shop();
                lockKey(key);
                if (!canReadQuote(store.quote(id), request.sessionId(), contactless)) return Outcome.error(404, "BOOKING_NOT_FOUND", null);
                var saved = replay(shop, key, id, request.sessionId());
                return saved == null ? Outcome.error(404, "BOOKING_NOT_FOUND", null) : saved;
            });
        } catch (DataAccessException | TransactionException failure) {
            return Outcome.error(503, "BOOKING_UNCERTAIN", null);
        }
    }

    private Outcome acceptLocked(UUID quoteId, AcceptRequest request, String key, boolean contactless) {
        Shop shop = store.shop(true);
        lockKey(key);
        var quoteRow = store.quote(quoteId);
        if (!canReadQuote(quoteRow, request.sessionId(), contactless)) return Outcome.error(404, "QUOTE_NOT_FOUND", null);
        var replay = replay(shop, key, quoteId, request.sessionId());
        if (replay != null) return replay;
        var session = session(request.sessionId());
        if (session == null || quoteRow == null || !quoteRow.quote().sessionId().equals(request.sessionId())) {
            return Outcome.error(404, "QUOTE_NOT_FOUND", null);
        }
        var quote = quoteRow.quote();
        var existingOrder = store.find("SELECT * FROM orders WHERE accepted_quote_id = ?", store::orderRow, quoteId);
        if (existingOrder != null) return decide(shop, key, quoteId, request, new Outcome(200, existingOrder.status(), null, null, existingOrder));
        if (!quoteId.equals(session.latestQuoteId())) {
            return decide(shop, key, quoteId, request, Outcome.error(409, "STALE_ACCEPTANCE", null));
        }
        if (alreadyBookedPickup(shop, session)) return decide(shop, key, quoteId, request, Outcome.error(409, "CALL_ALREADY_BOOKED", null));
        // One capture is reused even when a changed slot requires a replacement quote.
        var captured = capturePolicies();
        var item = item(quote.sku());
        var quoteRequest = new QuoteRequest(quote.sessionId(), quoteRow.name(), quoteRow.phone(), quote.sku(), quote.grams(), quote.pickupTime(), quote.pickupAt());
        if (!quote.expiresAt().isAfter(shop.now())) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 410, "EXPIRED", null));
        }
        String constraint = scheduleReason(shop, quote.pickupAt());
        if (constraint != null) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 409, "CONFLICT", constraint));
        }
        if (captured.applies(item) || requiresApproval(item, quote.grams(), captured.general())) {
            // Match the established phone → tray → slot lock order. A later slot read can introduce approval.
            lockKey(contactless ? "approval:native:" + session.nativeSubject() : "approval:" + quoteRow.phone());
        }
        var batch = batchLockTime.record(() -> store.find("SELECT phone_grams_free, ready_at, blocked FROM batches WHERE id = ? FOR UPDATE",
                (r, n) -> new Batch(r.getInt("phone_grams_free"), at(r, "ready_at"), r.getBoolean("blocked")), quote.batchId()));
        if (batch == null || batch.blocked() || batch.free() < quote.grams() || batch.readyAt().isAfter(packReadyCutoff(quote.pickupAt()))) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 409, "CONFLICT", "OUT_OF_STOCK"));
        }
        var slot = readSlot(quote.pickupAt(), true);
        if (slot == null || !slot.available()) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 409, "CONFLICT", "SLOT_FULL"));
        }
        shop = store.shop();
        if (!quote.expiresAt().isAfter(shop.now())) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 410, "EXPIRED", null));
        }
        Policy computed = captured.evaluate(item, quote.grams(), slot);
        boolean approval = requiresApproval(item, quote.grams(), computed);
        if (!eligibleTime(shop, quote.pickupAt(), computed, approval)) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 409, "CONFLICT", "TOO_SOON"));
        }
        if (approval && session.kind().singlePickup()) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(),
                    409, "CALL_APPROVAL_UNAVAILABLE", "CALL_APPROVAL_UNAVAILABLE"));
        }
        if (changedTerms(item, quote, computed, approval)) {
            return decide(shop, key, quoteId, request, requote(shop, quoteRequest, item, captured, quote, session.kind(), 409, "TERMS_CHANGED", null));
        }
        if (approval && hasPending(shop.runId(), session)) {
            return decide(shop, key, quoteId, request, Outcome.error(409, "PENDING_APPROVAL_LIMIT", null));
        }
        Policy current = withTerms(computed, item, quote.sku(), quote.grams(), quote.pickupAt(), approval);
        var order = reserve(shop, quoteRow, request.turnId(), current, session);
        return decide(shop, key, quoteId, request, new Outcome(201, order.status(), null, null, order));
    }

    private boolean canReadQuote(QuoteRow row, String sessionId, boolean contactless) {
        if (!contactless) return row == null || row.phone() != null;
        return row != null && row.phone() == null && row.quote().sessionId().equals(sessionId)
                && (nativePickupAllowed(sessionId) || guestCheckouts != null && guestCheckouts.subject(sessionId) != null);
    }

    private boolean nativePickupAllowed(String sessionId) {
        return nativePickup != null && nativePickup.validRoute(sessionId);
    }

    private boolean hasPending(UUID run, Session session) {
        String identity = session.nativeSubject() == null ? "phone" : "native_pickup_subject";
        String value = session.nativeSubject() == null ? session.phone() : session.nativeSubject();
        return Boolean.TRUE.equals(store.db.queryForObject("SELECT EXISTS (SELECT 1 FROM orders WHERE run_id=? AND "
                + identity + "=? AND status='AWAITING_APPROVAL')", Boolean.class, run, value));
    }

    private boolean alreadyBookedPickup(Shop shop, Session session) {
        return session.kind().singlePickup() && Boolean.TRUE.equals(store.db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM orders WHERE run_id=? AND native_identity_kind=? AND native_pickup_subject=?)
                """, Boolean.class, shop.runId(), session.kind().name(), session.nativeSubject()));
    }

    private static boolean changedTerms(Item item, Quote quote, Policy current, boolean approval) {
        return !item.unit().equals(quote.unit()) || !item.name().equals(quote.itemName()) || price(item, quote.grams()) != quote.pricePaise()
                || approval != quote.requiresApproval()
                || (approval && current.approvalHoldMinutes() != quote.policy().approvalHoldMinutes())
                || (current.slot() == null && quote.policy().slot() == null && current.pickupGraceMinutes() != quote.policy().pickupGraceMinutes());
    }

    private Order reserve(Shop shop, QuoteRow quoteRow, String turnId, Policy policy, Session session) {
        var quote = quoteRow.quote();
        store.db.update("UPDATE batches SET phone_grams_free = phone_grams_free - ? WHERE id = ?", quote.grams(), quote.batchId());
        store.db.update("UPDATE pickup_slots SET used = used + 1 WHERE pickup_at = ?", sqlTime(quote.pickupAt()));
        String status = quote.requiresApproval() ? "AWAITING_APPROVAL" : "CONFIRMED";
        Instant deadline = null;
        if (quote.requiresApproval()) {
            Instant proposed = shop.now().plus(policy.approvalHoldMinutes(), ChronoUnit.MINUTES);
            Instant cutoff = packReadyCutoff(quote.pickupAt());
            deadline = proposed.isBefore(cutoff) ? proposed : cutoff;
        }
        String pickupCode = session.nativeSubject() == null ? null : nextPickupCode();
        long number = store.db.queryForObject("""
                INSERT INTO orders (id,run_id,accepted_quote_id,accepted_turn_id,customer_name,phone,sku,item_name,grams,price_paise,
                    pickup_at,batch_id,status,approval_expires_at,policy,created_at,sale_unit,native_pickup_subject,pickup_code,native_identity_kind)
                VALUES (nextval('order_number_seq'),?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?) RETURNING id
                """, Long.class, shop.runId(), quote.id(), turnId, quoteRow.name(), quoteRow.phone(), quote.sku(), quote.itemName(),
                quote.grams(), quote.pricePaise(), sqlTime(quote.pickupAt()), quote.batchId(), status,
                sqlTime(deadline), store.write(policy), sqlTime(shop.now()), quote.unit(), session.nativeSubject(), pickupCode, session.kind().name());
        var order = store.order(number);
        store.event(order, status, shop.now());
        store.message(order, status);
        return order;
    }

    private String nextPickupCode() {
        lockKey("native-pickup-code:" + TenantContext.schema());
        for (int tries = 0; tries < 8; tries++) {
            String code = Integer.toString(PICKUP_CODES.nextInt(90_000_000) + 10_000_000);
            if (!Boolean.TRUE.equals(store.db.queryForObject("SELECT EXISTS (SELECT 1 FROM orders WHERE pickup_code=?)", Boolean.class, code))) return code;
        }
        throw new IllegalStateException("Could not allocate a pickup code");
    }

    private Outcome requote(Shop shop, QuoteRequest request, Item item, CapturedPolicies captured, Quote original, NativeIdentityKind kind,
                            int status, String code, String reason) {
        var fresh = replacement(shop, request, item, captured, original.pickupAt(), kind);
        if (fresh == null) store.db.update("UPDATE booking_sessions SET latest_quote_id = NULL WHERE id = ?", request.sessionId());
        return new Outcome(status, code, reason == null && fresh == null ? "NONE_AVAILABLE" : reason, fresh, null);
    }

    private Quote replacement(Shop shop, QuoteRequest request, Item item, CapturedPolicies captured, Instant requested, NativeIdentityKind kind) {
        var options = findOptions(shop, captured, item, request.sku(), request.grams(), requested, null, 1, !kind.singlePickup());
        var option = options.isEmpty() ? null : options.getFirst();
        if (option == null) return null;
        if (shop.hours() != null) store.db.update("INSERT INTO pickup_slots(pickup_at,capacity) VALUES (?,12) ON CONFLICT DO NOTHING", sqlTime(option.pickupAt()));
        boolean approval = requiresApproval(item, request.grams(), option.policy());
        Policy policy = withTerms(option.policy(), item, request.sku(), request.grams(), option.pickupAt(), approval);
        var quote = new Quote(UUID.randomUUID(), request.sessionId(), request.sku(), item.name(), request.grams(),
                price(item, request.grams()), shopTime(option.pickupAt()), option.pickupAt(), option.batchId(),
                approval, shop.now().plusSeconds(policy.ttlSeconds()), policy, item.unit());
        store.db.update("""
                INSERT INTO quotes (id,run_id,session_id,customer_name,phone,sku,item_name,grams,price_paise,pickup_at,batch_id,
                    requires_approval,expires_at,policy,sale_unit) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """, quote.id(), shop.runId(), quote.sessionId(), request.customerName().strip(), request.phone(), quote.sku(),
                quote.itemName(), quote.grams(), quote.pricePaise(), sqlTime(quote.pickupAt()), quote.batchId(),
                quote.requiresApproval(), sqlTime(quote.expiresAt()), store.write(quote.policy()), quote.unit());
        store.db.update("UPDATE booking_sessions SET latest_quote_id = ? WHERE id = ?", quote.id(), quote.sessionId());
        return quote;
    }

    private Option findOption(Shop shop, CapturedPolicies captured, Item item, String sku, int grams, Instant requested, Boolean readyNow) {
        var options = findOptions(shop, captured, item, sku, grams, requested, readyNow, 1);
        return options.isEmpty() ? null : options.getFirst();
    }

    private List<Option> findOptions(Shop shop, CapturedPolicies captured, Item item, String sku, int grams, Instant requested,
                                     Boolean readyNow, int limit) {
        return findOptions(shop, captured, item, sku, grams, requested, readyNow, limit, true);
    }

    private List<Option> findOptions(Shop shop, CapturedPolicies captured, Item item, String sku, int grams, Instant requested,
                                     Boolean readyNow, int limit, boolean allowApproval) {
        Instant earliest = captured.applies(item) ? shop.now() : shop.now().plus(captured.general().leadMinutes(), ChronoUnit.MINUTES);
        Instant from = requested.isAfter(earliest) ? requested : earliest;
        var args = new ArrayList<Object>();
        String slotRows = "SELECT pickup_at,used,capacity FROM pickup_slots";
        var hours = shop.hours();
        if (hours != null) {
            slotRows = """
                    SELECT agenda.pickup_at,COALESCE(existing.used,0) AS used,COALESCE(existing.capacity,12) AS capacity
                    FROM (SELECT (?::date+days.offset_days) AS local_day FROM (VALUES (0),(1)) days(offset_days)) d
                    CROSS JOIN LATERAL generate_series((d.local_day+?::time) AT TIME ZONE 'Asia/Kolkata',
                        (d.local_day+?::time) AT TIME ZONE 'Asia/Kolkata',INTERVAL '30 minutes') agenda(pickup_at)
                    LEFT JOIN pickup_slots existing USING (pickup_at)
                    """;
            args.add(shop.now().atZone(SHOP_ZONE).toLocalDate());
            args.add(hours.allDay() ? LocalTime.MIDNIGHT : LocalTime.parse(hours.opensAt()));
            args.add(hours.allDay() ? LocalTime.of(23, 30) : LocalTime.parse(hours.closesAt()));
        }
        java.util.Collections.addAll(args, sku, PACK_BUFFER_MINUTES, sqlTime(from), grams, readyNow, sqlTime(shop.now()), readyNow);
        var candidates = store.db.query("""
                SELECT DISTINCT ON (s.pickup_at) s.pickup_at, s.used, s.capacity, b.id, b.ready_at, b.phone_grams_free, b.status
                FROM (%s) s JOIN batches b
                  ON b.sku = ? AND b.ready_at <= s.pickup_at - make_interval(mins => ?)
                  WHERE s.pickup_at >= ? AND s.used < s.capacity AND NOT b.blocked AND b.phone_grams_free >= ?
                    AND (?::boolean IS NULL OR (b.status='READY' AND b.ready_at <= ?) = ?::boolean)
                  ORDER BY s.pickup_at, b.ready_at, b.id
                """.formatted(slotRows), (r, n) -> new Candidate(at(r, "pickup_at"), r.getString("id"), at(r, "ready_at"),
                r.getInt("phone_grams_free"), r.getString("status"), new Slot(r.getInt("used"), r.getInt("capacity"))), args.toArray());
        var choices = new ArrayList<Option>(limit);
        for (var candidate : candidates) {
            Policy policy = captured.evaluate(item, grams, candidate.slot());
            boolean approval = requiresApproval(item, grams, policy);
            if ((allowApproval || !approval) && eligibleTime(shop, candidate.pickupAt(), policy, approval)) {
                choices.add(new Option(candidate.pickupAt(), candidate.batchId(), candidate.batchReadyAt(), candidate.freeGrams(), candidate.batchStatus(), policy));
                if (choices.size() == limit) break;
            }
        }
        return choices;
    }

    private static String scheduleReason(Shop shop, Instant pickup) {
        var hours = shop.hours();
        if (hours == null) return null;
        LocalTime at = pickup.atZone(SHOP_ZONE).toLocalTime();
        if (!hours.allDay() && (at.isBefore(LocalTime.parse(hours.opensAt())) || at.isAfter(LocalTime.parse(hours.closesAt())))) {
            return "OUTSIDE_HOURS";
        }
        return at.getMinute() % 30 != 0 || at.getSecond() != 0 || at.getNano() != 0 ? "PICKUP_INTERVAL" : null;
    }

    private static boolean eligibleTime(Shop shop, Instant pickup, Policy policy, boolean approval) {
        return !pickup.isBefore(shop.now().plus(policy.leadMinutes(), ChronoUnit.MINUTES))
                && (!approval || packReadyCutoff(pickup).isAfter(shop.now()));
    }

    private Slot readSlot(Instant pickup, boolean locked) {
        String query = "SELECT used,capacity FROM pickup_slots WHERE pickup_at=?" + (locked ? " FOR UPDATE" : "");
        return store.find(query, (r, n) -> new Slot(r.getInt("used"), r.getInt("capacity")), sqlTime(pickup));
    }

    private Instant requestedTime(Shop shop, String pickupTime) {
        return requestedTime(shop, pickupTime, null);
    }

    private Instant requestedTime(Shop shop, String pickupTime, Instant requestedPickupAt) {
        var today = shop.now().atZone(SHOP_ZONE).toLocalDate();
        var local = LocalTime.parse(pickupTime);
        if (requestedPickupAt == null) return today.atTime(local).atZone(SHOP_ZONE).toInstant();
        var requested = requestedPickupAt.atZone(SHOP_ZONE);
        if (!requested.toLocalTime().equals(local) || requested.toLocalDate().isBefore(today)
                || requested.toLocalDate().isAfter(today.plusDays(1))) return null;
        return requestedPickupAt;
    }

    private String alternativeReason(Shop shop, CapturedPolicies captured, Item item, QuoteRequest request, Instant requested, NativeIdentityKind kind) {
        String constraint = scheduleReason(shop, requested);
        if (constraint != null) return constraint;
        if (kind.singlePickup()) {
            var requestedOption = findOption(shop, captured, item, request.sku(), request.grams(), requested, null);
            if (requestedOption != null && requestedOption.pickupAt().equals(requested)
                    && requiresApproval(item, request.grams(), requestedOption.policy())) return "CALL_APPROVAL_UNAVAILABLE";
        }
        return availabilityReason(shop, captured, item, request.sku(), request.grams(), requested);
    }

    private String availabilityReason(Shop shop, CapturedPolicies captured, Item item, String sku, int grams, Instant requested) {
        String constraint = scheduleReason(shop, requested);
        if (constraint != null) return constraint;
        var slot = readSlot(requested, false);
        var policy = slot == null || !slot.available() ? captured.general() : captured.evaluate(item, grams, slot);
        if ((slot == null || slot.available()) && !eligibleTime(shop, requested, policy, requiresApproval(item, grams, policy))) return "TOO_SOON";
        boolean stock = Boolean.TRUE.equals(store.db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM batches WHERE sku = ? AND NOT blocked AND phone_grams_free >= ? AND ready_at <= ?)
                """, Boolean.class, sku, grams, sqlTime(packReadyCutoff(requested))));
        return stock ? "SLOT_FULL" : "OUT_OF_STOCK";
    }

    private CapturedPolicies capturePolicies() {
        var snapshot = slotPolicies != null && (TenantContext.legacy() || store.hostedPolicyDemoShop()) ? slotPolicies.capture() : null;
        return new CapturedPolicies(policyService.current(), snapshot);
    }

    Policy shopPolicy() {
        var captured = capturePolicies();
        return captured.snapshot() == null ? captured.general() : captured.snapshot().evaluate(250, 0, 1).withSlot(null);
    }

    boolean slotPolicyEnabled() {
        return slotPolicies != null && TenantContext.legacy() && slotPolicies.capture() != null;
    }

    private static Policy withTerms(Policy selected, Item item, String sku, int quantity, Instant pickup, boolean approval) {
        SlotAudit slot = selected.slot();
        if (slot == null) return selected;
        var terms = new PolicyTerms(sku, quantity, item.unit(), price(item, quantity), pickup,
                approval, approval ? selected.approvalHoldMinutes() : null);
        if (!"CACHED".equals(selected.source())) return selected.withSlot(slot.withTerms(terms));
        boolean rushWasOn = "RUSH_HOUR".equals(slot.reasonCode());
        String english = rushWasOn ? slot.why().replace("Rush mode is on", "Rush mode was on in the last accepted rules")
                : "Using the last accepted pickup rules. " + slot.why();
        String hindi = rushWasOn ? slot.whyHindi().replace("व्यस्त समय के नियम लागू हैं", "पिछली बार मिले व्यस्त समय के नियम लागू हैं")
                : "पिछले मान्य पिकअप नियम लागू हैं। " + slot.whyHindi();
        return selected.withSlot(new SlotAudit(slot.digest(), slot.profile(), slot.reasonCode(), slot.used(), slot.capacity(),
                english, hindi, terms));
    }

    private Outcome replay(Shop shop, String key, UUID id, String sessionId) {
        return store.find("SELECT quote_id,request_session,status,outcome FROM booking_keys WHERE run_id = ? AND key = ?", (r, n) -> {
            if (!id.equals(r.getObject("quote_id", UUID.class)) || !sessionId.equals(r.getString("request_session"))) {
                return Outcome.error(409, "KEY_REUSED", null);
            }
            var original = store.read(r.getString("outcome"), Outcome.class);
            int status = r.getInt("status");
            return new Outcome(status == 201 ? 200 : status, original.code(), original.reason(), original.quote(), original.order());
        }, shop.runId(), key);
    }

    private Outcome decide(Shop shop, String key, UUID quoteId, AcceptRequest request, Outcome outcome) {
        store.db.update("""
                INSERT INTO booking_keys (run_id,key,quote_id,request_session,request_turn,status,outcome) VALUES (?,?,?,?,?,?,?::jsonb)
                """, shop.runId(), key, quoteId, request.sessionId(), request.turnId(), outcome.status(), store.write(outcome));
        return outcome;
    }

    private void lockKey(String key) {
        store.db.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 2384921))", r -> {}, key);
    }

    private Session session(String sessionId) {
        return store.find("SELECT phone,latest_quote_id,native_pickup_subject,native_identity_kind FROM booking_sessions WHERE id = ? FOR UPDATE",
                (r, n) -> new Session(r.getString("phone"), r.getObject("latest_quote_id", UUID.class), r.getString("native_pickup_subject"),
                        NativeIdentityKind.valueOf(r.getString("native_identity_kind"))), sessionId);
    }

    private Item item(String sku) {
        return readItem(sku, true);
    }

    private Item readItem(String sku, boolean lock) {
        String query = "SELECT name,price_per_kg_paise,sale_unit FROM items WHERE sku=?" + (lock ? " FOR SHARE" : "");
        return store.find(query, (r, n) -> new Item(r.getString("name"), r.getInt("price_per_kg_paise"), r.getString("sale_unit")), sku);
    }

    private static int price(Item item, int grams) {
        long scaled = (long) item.pricePerUnitPaise() * grams;
        return Math.toIntExact("PIECE".equals(item.unit()) ? scaled : Math.max(1, (scaled + 500) / 1000));
    }

    private static Instant packReadyCutoff(Instant pickupAt) {
        return pickupAt.minus(PACK_BUFFER_MINUTES, ChronoUnit.MINUTES);
    }

    private static boolean validQuoteRequest(QuoteRequest request) {
        return request != null && validIdentifier(request.sessionId())
                && request.customerName() != null && !request.customerName().isBlank() && request.customerName().length() <= 100
                && validIdentifier(request.sku()) && validPickupTime(request.pickupTime()) && request.grams() > 0;
    }

    private static boolean validPickupTime(String value) {
        return value != null && value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
    }

    static boolean validPhone(String value) {
        return value != null && value.matches("\\+[1-9][0-9]{7,14}");
    }

    private static String invalidQuantity(String sku, Item item, int grams, CapturedPolicies captured) {
        boolean pieces = "PIECE".equals(item.unit());
        int cap = pieces ? 1_000 : Math.min(5_000, captured.applies(item) ? 5_000 : captured.general().maxGrams());
        if (grams > cap) return "TOO_LARGE";
        if (grams < 1) return "UNSUPPORTED_WEIGHT";
        return !TenantContext.legacy() || pieces || grams == 250 || grams == 500 || (grams >= 1_000 && grams % 1_000 == 0)
                || (captured.applies(item) && sku.equals("barfi") && grams == 2_500)
                ? null : "UNSUPPORTED_WEIGHT";
    }

    private static boolean requiresApproval(Item item, int quantity, Policy policy) {
        return !"PIECE".equals(item.unit()) && quantity > policy.approvalAboveGrams();
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,160}");
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return null;
        }
    }

    private record Item(String name, int pricePerUnitPaise, String unit) {}
    private record CatalogItem(String sku, Item item) {}
    private record Batch(int free, Instant readyAt, boolean blocked) {}
    private record Slot(int used, int capacity) {
        boolean available() { return used < capacity; }
    }
    private record Candidate(Instant pickupAt, String batchId, Instant batchReadyAt, int freeGrams, String batchStatus, Slot slot) {}
    private record Option(Instant pickupAt, String batchId, Instant batchReadyAt, int freeGrams, String batchStatus, Policy policy) {}
    private record CapturedPolicies(Policy general, SlotPolicyProvider.Snapshot snapshot) {
        boolean applies(Item item) { return snapshot != null && "GRAMS".equals(item.unit()); }
        Policy evaluate(Item item, int grams, Slot slot) {
            return applies(item) ? snapshot.evaluate(grams, slot.used(), slot.capacity()) : general;
        }
    }
    private record Session(String phone, UUID latestQuoteId, String nativeSubject, NativeIdentityKind kind) {}
}
