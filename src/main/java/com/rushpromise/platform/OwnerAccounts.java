package com.rushpromise.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
@Profile("!worker")
class OwnerAccounts {
    static final String COOKIE = "rush_owner";
    static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final int ITERATIONS = 600_000;
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate db;
    private final TenantDirectory tenants;
    private final String dummy;

    OwnerAccounts(JdbcTemplate db, TenantDirectory tenants) {
        this.db = db;
        this.tenants = tenants;
        this.dummy = hashPassword(randomToken(24));
    }

    Grant signup(Credentials input) {
        String username = username(input.username());
        String password = input.password();
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a password of 12 to 128 characters");
        }
        try {
            UUID id = db.queryForObject("INSERT INTO public.rush_accounts (username,password_hash) VALUES (?,?) RETURNING id",
                    UUID.class, username, hashPassword(password));
            return grant(id, username);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a different username");
        }
    }

    Grant login(Credentials input) {
        String username = username(input.username());
        String password = input.password();
        Account account = db.query("SELECT id, password_hash FROM public.rush_accounts WHERE username=?",
                (row, index) -> new Account(row.getObject("id", UUID.class), row.getString("password_hash")), username)
                .stream().findFirst().orElse(null);
        if (password == null || password.length() > 128 || !matchesPassword(password, account == null ? dummy : account.hash()) || account == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Username or password is incorrect");
        }
        return grant(account.id(), username);
    }

    Principal resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        var principal = db.query("""
                SELECT a.id,a.username,s.csrf_token FROM public.rush_web_sessions s
                JOIN public.rush_accounts a ON a.id=s.account_id WHERE s.token_hash=?
                  AND s.revoked_at IS NULL AND s.expires_at>statement_timestamp()
                """, (row, index) -> new Principal(row.getObject("id", UUID.class), row.getString("username"),
                    row.getString("csrf_token"), null), tokenHash(token)).stream().findFirst().orElse(null);
        return principal == null ? null : new Principal(principal.accountId(), principal.username(), principal.csrf(), tenants.forOwner(principal.accountId()));
    }

    void revoke(String token) {
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
            db.update("UPDATE public.rush_web_sessions SET revoked_at=statement_timestamp() WHERE token_hash=? AND revoked_at IS NULL", tokenHash(token));
        }
    }

    private Grant grant(UUID id, String username) {
        String token = randomToken(32);
        String csrf = randomToken(24);
        db.update("INSERT INTO public.rush_web_sessions (token_hash,account_id,csrf_token,expires_at) VALUES (?,?,?,statement_timestamp()+make_interval(secs => ?))",
                tokenHash(token), id, csrf, SESSION_TTL.toSeconds());
        return new Grant(token, new Principal(id, username, csrf, tenants.forOwner(id)));
    }

    UUID ensureDemoAccount(Credentials input) {
        String user = username(input.username());
        String password = input.password();
        if (password == null || password.length() < 24 || password.length() > 128) {
            throw new IllegalArgumentException("Demo-owner credentials are invalid");
        }
        db.update("INSERT INTO public.rush_accounts (username,password_hash) VALUES (?,?) ON CONFLICT (username) DO NOTHING",
                user, hashPassword(password));
        var account = db.queryForObject("SELECT id,password_hash FROM public.rush_accounts WHERE username=?",
                (row, index) -> new Account(row.getObject("id", UUID.class), row.getString("password_hash")), user);
        if (account == null || !matchesPassword(password, account.hash())) {
            throw new IllegalStateException("Stored demo-owner credential differs from the supplied handoff");
        }
        return account.id();
    }

    int accountCount() {
        return db.queryForObject("SELECT COUNT(*) FROM public.rush_accounts", Integer.class);
    }

    String issueNativeToken(TenantDirectory.Shop shop) {
        String token = "rp_shop_" + randomToken(32);
        db.update("""
                INSERT INTO public.rush_native_bindings (tenant_id,token_hash) VALUES (?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,created_at=statement_timestamp()
                """, shop.id(), tokenHash(token));
        return token;
    }

    TenantDirectory.Shop nativeShop(String authorization) {
        if (authorization == null || !authorization.matches("Bearer rp_shop_[A-Za-z0-9_-]{43}")) return null;
        String slug = db.query("""
                SELECT t.slug FROM public.rush_native_bindings b JOIN public.rush_tenants t ON t.id=b.tenant_id
                WHERE b.token_hash=? AND t.state='ACTIVE'
                """, (row, index) -> row.getString("slug"), tokenHash(authorization.substring(7))).stream().findFirst().orElse(null);
        return slug == null ? null : tenants.bySlug(slug);
    }

    private String randomToken(int bytes) {
        byte[] token = new byte[bytes];
        random.nextBytes(token);
        return ENCODE.encodeToString(token);
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + ENCODE.encodeToString(salt) + "$" + ENCODE.encodeToString(derive(password, salt, ITERATIONS));
    }

    private static boolean matchesPassword(String password, String encoded) {
        String[] split = encoded.split("\\$");
        return MessageDigest.isEqual(DECODE.decode(split[3]), derive(password, DECODE.decode(split[2]), Integer.parseInt(split[1])));
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("Password hashing unavailable", failure); }
        finally { spec.clearPassword(); }
    }

    private static String tokenHash(String raw) {
        try { return ENCODE.encodeToString(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException("Token hashing unavailable", impossible); }
    }

    private static String username(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{2,39}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use 3 to 40 letters, digits, periods, underscores or hyphens for the username");
        }
        return value;
    }

    record Credentials(String username, String password) {}
    record Grant(String token, Principal principal) {}
    record Principal(UUID accountId, String username, String csrf, TenantDirectory.Shop shop) {
        SessionView view() { return new SessionView(username, shop == null ? null : shop.view(), csrf); }
    }
    record SessionView(String username, TenantDirectory.ShopView shop, String csrfToken) {}
    private record Account(UUID id, String hash) {}
}
