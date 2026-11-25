package com.rushpromise.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Profile("!worker")
public class OwnerPhoneAccess {
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate db;
    private final OwnerAccounts accounts;
    private final TenantDirectory tenants;

    OwnerPhoneAccess(JdbcTemplate db, OwnerAccounts accounts, TenantDirectory tenants) {
        this.db = db;
        this.accounts = accounts;
        this.tenants = tenants;
    }

    public record IssuedCode(String accessCode, Instant expiresAt) {}
    record Session(UUID tenantId, String slug, String schema, String shopName) {}
    record Authentication(String state, Session session) {}
    private record AccessState(String codeHash, boolean codeLive, boolean locked) {}

    @Transactional
    public IssuedCode issue(UUID verifiedTenantId) {
        String code = Integer.toString(10_000_000 + random.nextInt(90_000_000));
        var expires = db.query("""
                INSERT INTO public.owner_phone_access AS access (tenant_id,code_hash,code_expires_at,issue_window_at,issued_count)
                SELECT id,?,statement_timestamp()+interval '10 minutes',statement_timestamp(),1
                FROM public.rush_tenants WHERE id=? AND state='ACTIVE'
                  AND EXISTS (SELECT 1 FROM public.rush_accounts a WHERE a.id=owner_id AND a.preview_expires_at IS NULL)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    code_hash=EXCLUDED.code_hash,code_expires_at=EXCLUDED.code_expires_at,
                    issue_window_at=CASE WHEN access.issue_window_at>statement_timestamp()-interval '10 minutes'
                                         THEN access.issue_window_at ELSE statement_timestamp() END,
                    issued_count=CASE WHEN access.issue_window_at>statement_timestamp()-interval '10 minutes'
                                      THEN access.issued_count+1 ELSE 1 END
                WHERE access.issue_window_at<=statement_timestamp()-interval '10 minutes' OR access.issued_count<3
                RETURNING code_expires_at
                """, (row,index)->row.getObject(1,OffsetDateTime.class).toInstant(), accounts.hashPassword(code), verifiedTenantId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Try generating a phone code again in ten minutes"));
        return new IssuedCode(code, expires);
    }

    @Transactional
    public Authentication authenticate(String callHash, String slug, String query, String submittedCode) {
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,16))", (row,index)->true, callHash);
        Session existing = session(callHash);
        var shop = resolve(slug, query);
        if (existing != null) {
            if ((slug == null || slug.isBlank()) && (query == null || query.isBlank()) || shop != null && existing.tenantId().equals(shop.id())) {
                return new Authentication("AUTHENTICATED", existing);
            }
            return new Authentication("AUTH_REQUIRED", null);
        }
        if (shop == null) return new Authentication("AUTH_REQUIRED", null);
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM public.owner_phone_sessions WHERE call_hash=?)",
                Boolean.class, callHash))) return new Authentication("AUTH_REQUIRED", null);
        var access = db.query("""
                SELECT code_hash,code_expires_at>statement_timestamp(),locked_until>statement_timestamp()
                FROM public.owner_phone_access WHERE tenant_id=? FOR UPDATE
                """, (row,index)->new AccessState(row.getString(1),row.getBoolean(2),row.getBoolean(3)),shop.id())
                .stream().findFirst().orElse(null);
        if (access == null) return new Authentication("AUTH_REQUIRED", null);
        if (access.locked()) return new Authentication("ACCESS_LOCKED", null);
        String digits = submittedCode == null ? "" : submittedCode;
        if (!digits.matches("[0-9]{8}") || !access.codeLive() || access.codeHash() == null
                || !OwnerAccounts.matchesPassword(digits, access.codeHash())) {
            recordFailure(shop.id());
            return new Authentication("AUTH_REQUIRED", null);
        }
        db.update("""
                UPDATE public.owner_phone_access SET code_hash=NULL,code_expires_at=NULL,attempt_window_at=NULL,failed_count=0,locked_until=NULL
                WHERE tenant_id=?
                """, shop.id());
        db.update("""
                INSERT INTO public.owner_phone_sessions (call_hash,tenant_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '12 minutes')
                ON CONFLICT (call_hash) DO NOTHING
                """, callHash,shop.id());
        Session authenticated = session(callHash);
        return authenticated != null && authenticated.tenantId().equals(shop.id())
                ? new Authentication("AUTHENTICATED", authenticated) : new Authentication("AUTH_REQUIRED", null);
    }

    Session session(String callHash) { return findSession(callHash, false); }
    Session lockSession(String callHash) { return findSession(callHash, true); }

    private Session findSession(String callHash, boolean lock) {
        return db.query("""
                SELECT s.tenant_id,t.slug,t.schema_name,t.display_name FROM public.owner_phone_sessions s
                JOIN public.rush_tenants t ON t.id=s.tenant_id
                WHERE s.call_hash=? AND s.expires_at>statement_timestamp() AND t.state='ACTIVE'
                """ + (lock ? " FOR UPDATE OF s" : ""), (row,index)->new Session(row.getObject(1,UUID.class),row.getString(2),
                row.getString(3),row.getString(4)),callHash).stream().findFirst().orElse(null);
    }

    private TenantDirectory.Shop resolve(String slug, String query) {
        if (query == null || query.isBlank()) return tenants.bySlug(slug);
        var choices = tenants.exactVoiceChoices(query);
        if (choices.size() != 1 || slug != null && !slug.isBlank() && !slug.equals(choices.getFirst().slug())) return null;
        return tenants.bySlug(choices.getFirst().slug());
    }

    private void recordFailure(UUID tenant) {
        db.update("""
                UPDATE public.owner_phone_access SET
                  failed_count=CASE WHEN attempt_window_at>statement_timestamp()-interval '15 minutes' THEN failed_count+1 ELSE 1 END,
                  locked_until=CASE WHEN attempt_window_at>statement_timestamp()-interval '15 minutes' AND failed_count>=4
                                    THEN statement_timestamp()+interval '15 minutes' ELSE NULL END,
                  attempt_window_at=CASE WHEN attempt_window_at>statement_timestamp()-interval '15 minutes'
                                         THEN attempt_window_at ELSE statement_timestamp() END
                WHERE tenant_id=?
                """,tenant);
    }

    @Scheduled(fixedDelay=900_000)
    void prune() {
        db.update("UPDATE public.owner_phone_access SET code_hash=NULL,code_expires_at=NULL WHERE code_expires_at<statement_timestamp()");
        db.update("DELETE FROM public.owner_phone_sessions WHERE expires_at<statement_timestamp()-interval '1 day'");
    }
}
