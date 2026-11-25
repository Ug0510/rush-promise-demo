package com.rushpromise.booking;

import com.rushpromise.platform.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Profile("!worker")
public class GuestCheckouts {
    public static final String SESSION_PREFIX = "guest:";
    private final SecureRandom random = new SecureRandom();
    private final JdbcClient database;

    GuestCheckouts(JdbcClient database) { this.database = database; }

    public record Credential(UUID sessionId, String guestToken) {}

    @Transactional
    public Credential issue() {
        if (TenantContext.legacy()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        database.sql("SELECT pg_advisory_xact_lock(hashtextextended('rush-guest-checkouts:'||current_schema(),0))")
                .query((result, index) -> Boolean.TRUE).single();
        var id = UUID.randomUUID();
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        int created = database.sql("""
                INSERT INTO guest_checkouts (id,run_id,token_hash)
                SELECT :id, run_id, :hash FROM shop_state WHERE id=1
                  AND (SELECT count(*) FROM guest_checkouts WHERE expires_at>statement_timestamp())<600
                """).param("id", id).param("hash", hash(token)).update();
        if (created == 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "This shop has too many open checkouts");
        return new Credential(id, token);
    }

    public String authenticate(UUID id, String token) {
        if (!TenantContext.legacy() && id != null && token != null && token.matches("[A-Za-z0-9_-]{43}")) {
            String stored = findHash(id, true);
            if (stored != null && MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII), hash(token).getBytes(StandardCharsets.US_ASCII))) {
                return stored;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Open or resume this shop’s guest checkout");
    }

    String subject(String sessionId) {
        if (TenantContext.legacy() || sessionId == null || !sessionId.startsWith(SESSION_PREFIX)) return null;
        try { return findHash(UUID.fromString(sessionId.substring(SESSION_PREFIX.length())), false); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private String findHash(UUID id, boolean active) {
        return database.sql("""
                SELECT g.token_hash FROM guest_checkouts g JOIN shop_state s ON s.run_id=g.run_id
                WHERE g.id=:id AND (NOT :active OR g.expires_at>statement_timestamp())
                """).param("id", id).param("active", active).query(String.class).optional().orElse(null);
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Guest checkout hashing unavailable", impossible); }
    }
}
