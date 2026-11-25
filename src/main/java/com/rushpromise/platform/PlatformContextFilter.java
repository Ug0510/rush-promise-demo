package com.rushpromise.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Profile("!worker")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class PlatformContextFilter extends OncePerRequestFilter {
    static final String PRINCIPAL = "com.rushpromise.platform.owner";
    static final String STOREFRONT = "com.rushpromise.platform.storefront";
    private final OwnerAccounts accounts;
    private final TenantDirectory tenants;

    PlatformContextFilter(OwnerAccounts accounts, TenantDirectory tenants) {
        this.accounts = accounts;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean get = request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        if (get && path.startsWith("/api/storefront/")) {
            var shop = tenants.bySlug(path.substring("/api/storefront/".length()));
            if (shop == null) response.sendError(HttpServletResponse.SC_NOT_FOUND);
            else {
                request.setAttribute(STOREFRONT, shop);
                proceed(shop.schema(), request, response, chain);
            }
            return;
        }
        String buyerSlug = request.getHeader("X-Rush-Shop");
        if (path.equals("/api/voice/browser/turn") && buyerSlug != null) {
            var shop = tenants.bySlug(buyerSlug);
            if (shop == null) response.sendError(HttpServletResponse.SC_NOT_FOUND);
            else proceed(shop.schema(), request, response, chain);
            return;
        }
        if (path.equals("/api/voice/sarvam/turn")) {
            String bearer = request.getHeader("Authorization");
            if (bearer != null && bearer.startsWith("Bearer rp_shop_")) {
                var owned = accounts.nativeShop(bearer);
                if (owned == null) response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                else if (buyerSlug != null && !owned.slug().equals(buyerSlug)) response.sendError(HttpServletResponse.SC_FORBIDDEN);
                else {
                    try (var ignored = TenantContext.enterNative(owned.schema())) {
                        chain.doFilter(request, response);
                    }
                }
            } else if (buyerSlug != null) response.sendError(HttpServletResponse.SC_FORBIDDEN);
            else chain.doFilter(request, response);
            return;
        }
        if (!path.startsWith("/api/") && !path.startsWith("/ops/")) {
            chain.doFilter(request, response);
            return;
        }
        String ownerCookie = cookie(request);
        var principal = accounts.resolve(ownerCookie);
        boolean authRoute = path.startsWith("/api/auth/");
        if (authRoute || ownerCookie != null) response.setHeader("Cache-Control", "no-store");
        if (principal == null) {
            if (ownerCookie != null && !authRoute) response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            else chain.doFilter(request, response);
            return;
        }
        request.setAttribute(PRINCIPAL, principal);
        boolean publicAuth = path.equals("/api/auth/signup") || path.equals("/api/auth/login");
        if (!get && !publicAuth && !equal(principal.csrf(), request.getHeader("X-CSRF-Token"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String schema = principal.shop() == null ? TenantContext.LEGACY : principal.shop().schema();
        proceed(schema, request, response, chain);
    }

    private static void proceed(String schema, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try (var ignored = TenantContext.enter(schema)) {
            chain.doFilter(request, response);
        }
    }

    static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (OwnerAccounts.COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    static OwnerAccounts.Principal principal(HttpServletRequest request) {
        return (OwnerAccounts.Principal) request.getAttribute(PRINCIPAL);
    }

    public static boolean ownerAuthenticated(HttpServletRequest request) {
        return principal(request) != null;
    }

    public static boolean ownerHasShop(HttpServletRequest request) {
        var principal = principal(request);
        return principal != null && principal.shop() != null;
    }

    static boolean equal(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
