package com.rushpromise.platform;

import com.rushpromise.booking.BookingModels.ShopMenu;
import com.rushpromise.booking.BookingModels.PickupHours;
import com.rushpromise.booking.BookingModels.PickupHoursInput;
import com.rushpromise.booking.BookingModels.PickupPreview;
import com.rushpromise.booking.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.rushpromise.platform.PlatformContextFilter.*;

@RestController
@Profile("!worker")
class PlatformController {
    private final OwnerAccounts accounts;
    private final TenantDirectory tenants;
    private final BookingService bookings;
    private final TenantInventory inventory;
    private final OwnerPhoneAccess ownerPhone;
    private final DemoPreviews previews;
    private final boolean signupEnabled;
    private final String publicPhone;
    private final boolean voiceAvailable;

    PlatformController(OwnerAccounts accounts, TenantDirectory tenants, BookingService bookings, TenantInventory inventory,
                       OwnerPhoneAccess ownerPhone, DemoPreviews previews,
                       @Value("${RUSH_SIGNUP_ENABLED:true}") boolean signupEnabled,
                       @Value("${RUSH_PUBLIC_PHONE:}") String publicPhone,
                       @Value("${RUSH_PUBLIC_VOICE_AVAILABLE:true}") boolean voiceAvailable) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.bookings = bookings;
        this.inventory = inventory;
        this.ownerPhone = ownerPhone;
        this.previews = previews;
        this.signupEnabled = signupEnabled;
        if (!publicPhone.isBlank() && !publicPhone.matches("\\+[1-9][0-9]{7,14}")) {
            throw new IllegalArgumentException("RUSH_PUBLIC_PHONE must be an E.164 number");
        }
        this.publicPhone = publicPhone.isBlank() ? null : publicPhone;
        this.voiceAvailable = voiceAvailable;
    }

    @PostMapping("/api/auth/signup")
    ResponseEntity<OwnerAccounts.SessionView> signup(@RequestBody OwnerAccounts.Credentials input, HttpServletRequest request) {
        if (!signupEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        requireSameOrigin(request);
        return setSession(accounts.signup(input), request, HttpStatus.CREATED);
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<OwnerAccounts.SessionView> login(@RequestBody OwnerAccounts.Credentials input, HttpServletRequest request) {
        requireSameOrigin(request);
        return setSession(accounts.login(input), request, HttpStatus.OK);
    }

    @PostMapping("/api/auth/demo")
    ResponseEntity<OwnerAccounts.SessionView> demo(@RequestBody DemoRequest input, HttpServletRequest request) {
        requireSameOrigin(request);
        var current=principal(request);
        if (current!=null) {
            if (current.demoPreview() && current.shop()!=null) return ResponseEntity.ok(current.view());
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Sign out of your shop before opening a separate demo");
        }
        return setSession(previews.open(input.sessionKey()),request,HttpStatus.OK);
    }

    @GetMapping("/api/auth/options")
    Map<String, Boolean> options() { return Map.of("signupEnabled", signupEnabled, "demoAvailable", true); }

    @GetMapping("/api/public/shops")
    TenantDirectory.DirectoryView directory(@RequestParam(required = false) String query) { return tenants.storefrontDirectory(query); }

    @GetMapping("/api/public/site")
    PublicSite site() { return new PublicSite(voiceAvailable ? publicPhone : null, voiceAvailable); }

    @GetMapping("/api/auth/session")
    OwnerAccounts.SessionView session(HttpServletRequest request) {
        return requireOwner(request).view();
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        accounts.revoke(cookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO)).build();
    }

    @PostMapping("/api/owner/shop")
    ResponseEntity<TenantDirectory.ShopView> onboard(@RequestBody OnboardInput input, HttpServletRequest request) {
        var user = requireOwner(request);
        var details = new TenantDirectory.ShopInput(input.displayName(), input.slug(), input.area(), input.hours(), input.pickupInstructions());
        PickupHoursInput hours = input.pickupHours();
        if (hours != null) {
            try { BookingService.validatePickupHours(hours); }
            catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(tenants.onboard(user.accountId(), details,
                hours == null ? null : () -> bookings.updatePickupHours(hours)).view());
    }

    @PostMapping("/api/owner/shop/details")
    TenantDirectory.ShopView updateDetails(@RequestBody TenantDirectory.ShopInput input, HttpServletRequest request) {
        return tenants.updateDetails(requireShop(request), input).view();
    }

    @PostMapping("/api/owner/shop/settings")
    @Transactional
    public SettingsView updateSettings(@RequestBody SettingsInput input, HttpServletRequest request) {
        var owned = requireShop(request);
        var hours = saveHours(input.pickupHours());
        var details = new TenantDirectory.ShopInput(input.displayName(), null, input.area(), input.hours(), input.pickupInstructions());
        return new SettingsView(tenants.updateDetails(owned, details).view(), hours);
    }

    @PostMapping("/api/owner/shop/native-token")
    Map<String, String> nativeToken(HttpServletRequest request) {
        return Map.of("token", accounts.issueNativeToken(requireShop(request)));
    }

    @PostMapping("/api/owner/phone/access")
    OwnerPhoneAccess.IssuedCode ownerPhoneCode(HttpServletRequest request) {
        return ownerPhone.issue(requireShop(request).id());
    }

    @GetMapping("/api/owner/products")
    List<TenantInventory.Product> products(HttpServletRequest request) {
        requireShop(request);
        return inventory.products();
    }

    @PostMapping("/api/owner/products")
    TenantInventory.Product saveProduct(@RequestBody TenantInventory.ProductInput product, HttpServletRequest request) {
        requireShop(request);
        return inventory.save(product);
    }

    @GetMapping("/api/owner/pickup-hours")
    PickupHours pickupHours(HttpServletRequest request) {
        requireShop(request);
        return bookings.pickupHours();
    }

    @PostMapping("/api/owner/pickup-hours")
    PickupHours updatePickupHours(@RequestBody PickupHoursInput hours, HttpServletRequest request) {
        requireShop(request);
        return saveHours(hours);
    }

    private PickupHours saveHours(PickupHoursInput hours) {
        try {
            return bookings.updatePickupHours(hours);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @GetMapping("/api/storefront/{slug}")
    PublicStorefront storefront(@RequestAttribute(STOREFRONT) TenantDirectory.Shop shop,
                                @RequestParam(required = false) String sku, @RequestParam(required = false) Integer quantity,
                                @RequestParam(required = false) String from) {
        ShopMenu menu = bookings.catalog();
        var options = sku == null || quantity == null ? List.<PickupPreview>of() : bookings.availablePickups(sku, quantity, from, 6);
        return new PublicStorefront(new TenantDirectory.ShopView(shop.slug(), menu.displayName(), shop.area(), shop.hours(), shop.pickupInstructions(), shop.demoFixture()),
                menu, bookings.pickupHours(), options);
    }

    private ResponseEntity<OwnerAccounts.SessionView> setSession(OwnerAccounts.Grant grant, HttpServletRequest request, HttpStatus status) {
        accounts.revoke(cookie(request));
        Duration duration=grant.principal().demoPreview()?OwnerAccounts.PREVIEW_TTL:OwnerAccounts.SESSION_TTL;
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, sessionCookie(grant.token(), duration))
                .body(grant.principal().view());
    }

    private static String sessionCookie(String value, Duration age) {
        return ResponseCookie.from(OwnerAccounts.COOKIE, value).httpOnly(true).secure(true).sameSite("Strict")
                .path("/").maxAge(age).build().toString();
    }

    private static OwnerAccounts.Principal requireOwner(HttpServletRequest request) {
        var user = principal(request);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to your owner account");
        return user;
    }

    private static TenantDirectory.Shop requireShop(HttpServletRequest request) {
        var shop = requireOwner(request).shop();
        if (shop == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Set up your shop first");
        return shop;
    }

    record PublicStorefront(TenantDirectory.ShopView shop, ShopMenu menu, PickupHours pickupHours, List<PickupPreview> availablePickups) {}
    record PublicSite(String phone, boolean voiceAvailable) {}
    record DemoRequest(java.util.UUID sessionKey) {}
    record OnboardInput(String displayName, String slug, String area, String hours, String pickupInstructions, PickupHoursInput pickupHours) {}
    record SettingsInput(String displayName, String area, String hours, String pickupInstructions, PickupHoursInput pickupHours) {}
    record SettingsView(TenantDirectory.ShopView shop, PickupHours pickupHours) {}
}
