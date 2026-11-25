package com.rushpromise.platform;

import com.rushpromise.booking.BookingModels.ShopMenu;
import com.rushpromise.booking.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
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
    private final boolean signupEnabled;

    PlatformController(OwnerAccounts accounts, TenantDirectory tenants, BookingService bookings, TenantInventory inventory,
                       @Value("${RUSH_SIGNUP_ENABLED:true}") boolean signupEnabled) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.bookings = bookings;
        this.inventory = inventory;
        this.signupEnabled = signupEnabled;
    }

    @PostMapping("/api/auth/signup")
    ResponseEntity<OwnerAccounts.SessionView> signup(@RequestBody OwnerAccounts.Credentials input, HttpServletRequest request) {
        if (!signupEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        sameOrigin(request);
        return setSession(accounts.signup(input), request, HttpStatus.CREATED);
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<OwnerAccounts.SessionView> login(@RequestBody OwnerAccounts.Credentials input, HttpServletRequest request) {
        sameOrigin(request);
        return setSession(accounts.login(input), request, HttpStatus.OK);
    }

    @GetMapping("/api/auth/options")
    Map<String, Boolean> options() { return Map.of("signupEnabled", signupEnabled); }

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
    ResponseEntity<TenantDirectory.ShopView> onboard(@RequestBody TenantDirectory.ShopInput input, HttpServletRequest request) {
        var user = requireOwner(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(tenants.onboard(user.accountId(), input).view());
    }

    @PostMapping("/api/owner/shop/details")
    TenantDirectory.ShopView updateDetails(@RequestBody TenantDirectory.ShopInput input, HttpServletRequest request) {
        return tenants.updateDetails(requireShop(request), input).view();
    }

    @PostMapping("/api/owner/shop/native-token")
    Map<String, String> nativeToken(HttpServletRequest request) {
        return Map.of("token", accounts.issueNativeToken(requireShop(request)));
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

    @GetMapping("/api/storefront/{slug}")
    PublicStorefront storefront(@RequestAttribute(STOREFRONT) TenantDirectory.Shop shop) {
        ShopMenu menu = bookings.catalog();
        return new PublicStorefront(new TenantDirectory.ShopView(shop.slug(), menu.displayName(), shop.area(), shop.hours(), shop.pickupInstructions(), shop.demoFixture()), menu);
    }

    private ResponseEntity<OwnerAccounts.SessionView> setSession(OwnerAccounts.Grant grant, HttpServletRequest request, HttpStatus status) {
        accounts.revoke(cookie(request));
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, sessionCookie(grant.token(), OwnerAccounts.SESSION_TTL))
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

    private static void sameOrigin(HttpServletRequest request) {
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        try {
            URI host = URI.create(origin);
            int port = host.getPort() == -1 ? ("https".equals(host.getScheme()) ? 443 : 80) : host.getPort();
            if (request.getScheme().equalsIgnoreCase(host.getScheme()) && request.getServerName().equalsIgnoreCase(host.getHost())
                    && request.getServerPort() == port) return;
        } catch (IllegalArgumentException ignored) {
            // Fall through to the same rejection for malformed origins.
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    record PublicStorefront(TenantDirectory.ShopView shop, ShopMenu menu) {}
}
