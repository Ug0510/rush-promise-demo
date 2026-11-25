package com.rushpromise.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    static final Duration PREVIEW_TTL = Duration.ofHours(2);
    private static final int ITERATIONS = 600_000;
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate db;
    private final TenantDirectory tenants;
    private final String dummy;
    private final int maxAccounts;

    OwnerAccounts(JdbcTemplate db, TenantDirectory tenants, @Value("${RUSH_MAX_OWNER_ACCOUNTS:30}") int maxAccounts) {
        if (maxAccounts < 2 || maxAccounts > 1000) throw new IllegalArgumentException("Use an owner account limit from 2 to 1,000");
        this.db = db;
        this.tenants = tenants;
        this.dummy = hashPassword(randomToken(24));
        this.maxAccounts = maxAccounts;
    }

    @Transactional
    public Grant signup(Credentials input) {
        String username = username(input.username());
        String password = input.password();
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain 12 to 128 characters");
        }
        String passwordHash = hashPassword(password);
        db.execute("SELECT pg_advisory_xact_lock(24938439)");
        if (accountCount() >= maxAccounts) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Registration has reached its current limit");
        }
        try {
            UUID id = db.queryForObject("INSERT INTO public.rush_accounts (username,password_hash) VALUES (?,?) RETURNING id",
                    UUID.class, username, passwordHash);
            return grant(id, username);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a different username");
        }
    }

    Grant login(Credentials input) {
        String username = username(input.username());
        String password = input.password();
        Account account = db.query("SELECT id, password_hash FROM public.rush_accounts WHERE username=? AND preview_expires_at IS NULL",
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
                SELECT a.id,a.username,s.csrf_token,a.preview_expires_at IS NOT NULL AS preview FROM public.rush_web_sessions s
                JOIN public.rush_accounts a ON a.id=s.account_id WHERE s.token_hash=?
                  AND s.revoked_at IS NULL AND s.expires_at>statement_timestamp()
                  AND (a.preview_expires_at IS NULL OR a.preview_expires_at>statement_timestamp())
                """, (row, index) -> new Principal(row.getObject("id", UUID.class), row.getString("username"),
                    row.getString("csrf_token"), null, row.getBoolean("preview")), tokenHash(token)).stream().findFirst().orElse(null);
        return principal == null ? null : new Principal(principal.accountId(), principal.username(), principal.csrf(),
                tenants.forOwner(principal.accountId()), principal.demoPreview());
    }

    void revoke(String token) {
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
            db.update("UPDATE public.rush_web_sessions SET revoked_at=statement_timestamp() WHERE token_hash=? AND revoked_at IS NULL", tokenHash(token));
        }
    }

    private Grant grant(UUID id, String username) {
        return grant(id,username,false);
    }

    Grant grantPreview(UUID id) { return grant(id,"Demo owner",true); }

    private Grant grant(UUID id, String username, boolean preview) {
        String token = randomToken(32);
        String csrf = randomToken(24);
        db.update("INSERT INTO public.rush_web_sessions (token_hash,account_id,csrf_token,expires_at) VALUES (?,?,?,statement_timestamp()+make_interval(secs => ?))",
                tokenHash(token), id, csrf, (preview ? PREVIEW_TTL : SESSION_TTL).toSeconds());
        return new Grant(token, new Principal(id, username, csrf, tenants.forOwner(id),preview));
    }

    @Transactional
    public UUID reservePreview(UUID key) {
        if (key == null || key.version() != 4) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Start again from the demo button");
        db.execute("SELECT pg_advisory_xact_lock(24938440)");
        String hash = tokenHash(key.toString());
        var previous = db.query("SELECT id FROM public.rush_accounts WHERE preview_key_hash=? AND preview_expires_at>statement_timestamp()",
                (row,index)->row.getObject(1,UUID.class),hash).stream().findFirst().orElse(null);
        if (previous != null) return previous;
        Integer active = db.queryForObject("SELECT count(*) FROM public.rush_accounts WHERE preview_expires_at IS NOT NULL",Integer.class);
        if (active != null && active >= 12) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Demo shops are busy. Try again in a few minutes.");
        db.update("UPDATE public.rush_accounts SET preview_key_hash=NULL WHERE preview_key_hash=? AND preview_expires_at<=statement_timestamp()",hash);
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO public.rush_accounts(id,username,password_hash,preview_key_hash,preview_expires_at)
                VALUES (?,?,'preview-disabled',?,statement_timestamp()+make_interval(secs=>?))
                """,id,"preview-"+id.toString().replace("-",""),hash,PREVIEW_TTL.toSeconds());
        return id;
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
        return db.queryForObject("SELECT COUNT(*) FROM public.rush_accounts WHERE preview_expires_at IS NULL", Integer.class);
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

    String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + ENCODE.encodeToString(salt) + "$" + ENCODE.encodeToString(derive(password, salt, ITERATIONS));
    }

    static boolean matchesPassword(String password, String encoded) {
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must contain 3 to 40 letters, digits, periods, underscores or hyphens and start with a letter or digit");
        }
        return value;
    }

    record Credentials(String username, String password) {}
    record Grant(String token, Principal principal) {}
    record Principal(UUID accountId, String username, String csrf, TenantDirectory.Shop shop, boolean demoPreview) {
        SessionView view() { return new SessionView(demoPreview ? "Demo owner" : username, shop == null ? null : shop.view(), csrf, demoPreview); }
    }
    record SessionView(String username, TenantDirectory.ShopView shop, String csrfToken, boolean demoPreview) {}
    private record Account(UUID id, String hash) {}
}
