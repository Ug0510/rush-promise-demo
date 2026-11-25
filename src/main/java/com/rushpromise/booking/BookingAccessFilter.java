package com.rushpromise.booking;

import com.rushpromise.platform.PlatformContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Profile("!worker")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BookingAccessFilter extends OncePerRequestFilter {
    static final String STAFF_ROLE_ATTRIBUTE = "com.rushpromise.booking.BookingAccessFilter.role";
    private static final Pattern FULFILLMENT = Pattern.compile("/api/orders/[0-9]+/(pack|short|return)|/api/batches/[^/]+/ready");
    private static final Pattern OWNER_ACTION = Pattern.compile("/api/orders/[0-9]+/(approve|reject|cancel)|/api/batches/[^/]+/unblock|/api/messages/[0-9a-fA-F-]+/(retry|resend)|/api/shop/profile");
    private final String staffToken;
    private final String ownerToken;
    private final String operatorToken;
    private final String bookingToken;

    public BookingAccessFilter(@Value("${rush.staff.token}") String staffToken,
                               @Value("${rush.owner.token}") String ownerToken,
                               @Value("${rush.operator.token}") String operatorToken,
                               @Value("${rush.booking.api-token}") String bookingToken) {
        List<String> enabled = List.of(staffToken, ownerToken, operatorToken, bookingToken).stream()
                .filter(token -> !token.isBlank()).toList();
        if (enabled.stream().distinct().count() != enabled.size()) {
            throw new IllegalArgumentException("Role and booking tokens must be distinct");
        }
        this.staffToken = staffToken;
        this.ownerToken = ownerToken;
        this.operatorToken = operatorToken;
        this.bookingToken = bookingToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean api = under(path, "/api");
        boolean ops = under(path, "/ops");
        if (!api && !ops) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        // Reject encoded and path-parameter variants before MVC normalizes path segments.
        if (path.contains("%") || path.contains(";") || path.contains("//")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String method = request.getMethod();
        if (under(path, "/api/auth") || ((method.equals("GET") || method.equals("HEAD")) && under(path, "/api/storefront"))) {
            chain.doFilter(request, response);
            return;
        }
        if (under(path, "/api/owner")) {
            if (!PlatformContextFilter.ownerAuthenticated(request)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                chain.doFilter(request, response);
            }
            return;
        }
        if (method.equals("POST") && (path.equals("/api/voice/browser/turn") || path.equals("/api/voice/sarvam/turn"))) {
            chain.doFilter(request, response);
            return;
        }
        boolean authenticatedOwner = PlatformContextFilter.ownerAuthenticated(request);
        if (!authenticatedOwner && under(path, "/api/quotes") && matches(bookingToken, request.getHeader("X-Booking-Token"))) {
            chain.doFilter(request, response);
            return;
        }
        if (authenticatedOwner && !PlatformContextFilter.ownerHasShop(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Role role = authenticatedOwner ? Role.OWNER : role(request.getHeader("X-Staff-Token"));
        if (role == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!allowed(method, path, role, ops)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        request.setAttribute(STAFF_ROLE_ATTRIBUTE, role.name());
        chain.doFilter(request, response);
    }

    private boolean allowed(String method, String path, Role role, boolean ops) {
        if (ops || under(path, "/api/demo")) return role == Role.OPERATOR;
        if (method.equals("GET") || method.equals("HEAD")) return true;
        if (OWNER_ACTION.matcher(path).matches()) return role == Role.OWNER;
        if (FULFILLMENT.matcher(path).matches() || under(path, "/api/quotes") || path.equals("/api/sim/turn")) {
            return role == Role.STAFF || role == Role.OWNER;
        }
        return false;
    }

    private Role role(String supplied) {
        if (matches(staffToken, supplied)) return Role.STAFF;
        if (matches(ownerToken, supplied)) return Role.OWNER;
        if (matches(operatorToken, supplied)) return Role.OPERATOR;
        return null;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean matches(String configured, String supplied) {
        return !configured.isBlank() && supplied != null
                && MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private enum Role { STAFF, OWNER, OPERATOR }
}
