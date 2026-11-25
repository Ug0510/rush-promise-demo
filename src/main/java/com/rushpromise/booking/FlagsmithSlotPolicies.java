package com.rushpromise.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.platform.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;

@Service
@Profile("!worker")
@DependsOnDatabaseInitialization
final class FlagsmithSlotPolicies implements SlotPolicyProvider {
    private static final Logger log = LoggerFactory.getLogger(FlagsmithSlotPolicies.class);
    private static final String REVISION_HEADER = "X-Flagsmith-Document-Updated-At";
    private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    private static final FlagsmithDocument.Profile FALLBACK = new FlagsmithDocument.Profile(
            "FALLBACK", "FALLBACK_POLICY", 2000, 30, 120, 20, 10);
    private static final String FALLBACK_DIGEST = "fallback-v1";

    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final boolean enabled;
    private final String key;
    private final URI endpoint;
    private final String environmentHash;
    private final Duration staleAge;
    private final Counter rejected;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final LinkedHashSet<String> rejectedDigests = new LinkedHashSet<>();
    private volatile Accepted active;
    private boolean fetchFailed;

    FlagsmithSlotPolicies(ObjectMapper json, JdbcTemplate db, MeterRegistry metrics,
                         @Value("${RUSH_SLOT_POLICY_ENABLED:false}") boolean enabled,
                         @Value("${rush.policy.flagsmith-key}") String key,
                         @Value("${rush.policy.flagsmith-url}") URI configuredUrl,
                         @Value("${rush.policy.refresh-ms}") long refreshMs) {
        this.json = json;
        this.db = db;
        this.enabled = enabled;
        this.key = key;
        this.endpoint = enabled && !key.isBlank() ? documentEndpoint(configuredUrl) : configuredUrl;
        this.environmentHash = FlagsmithDocument.digest(endpoint + "\n" + key);
        this.staleAge = Duration.ofMillis(refreshMs).multipliedBy(2);
        this.rejected = metrics.counter("rush.flagsmith.document.rejected");
    }

    @PostConstruct
    void restore() {
        if (!enabled || key.isBlank()) return;
        try {
            active = cached();
        } catch (RuntimeException failure) {
            log.warn("Flagsmith slot policy cache could not be restored: {}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${rush.policy.refresh-ms}")
    synchronized void refresh() {
        if (!enabled || key.isBlank()) return;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(7))
                    .header("X-Environment-Key", key).header("Accept", "application/json")
                    .header("Cache-Control", "no-cache").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Flagsmith document response was not successful");
                byte[] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
                if (bytes.length > MAX_DOCUMENT_BYTES) throw new IOException("Flagsmith document is too large");
                accept(new String(bytes, StandardCharsets.UTF_8), response.headers().firstValue(REVISION_HEADER).orElse(""), Instant.now());
            }
            fetchFailed = false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!fetchFailed) log.warn("Flagsmith slot policy refresh failed: {}", failure.getClass().getSimpleName());
            fetchFailed = true;
        }
    }

    private void accept(String body, String version, Instant fetchedAt) {
        FlagsmithDocument next;
        try {
            next = FlagsmithDocument.read(json, body, version);
        } catch (IOException | RuntimeException invalid) {
            reject(FlagsmithDocument.digest(body), "invalid document");
            return;
        }
        var current = active;
        if (current != null && olderOrConflicting(next, current.document())) {
            reject(next.digest(), "older or inconsistent revision");
            return;
        }
        int saved = db.update("""
                INSERT INTO public.flagsmith_slot_policy_cache(environment_hash, revision, digest, document, fetched_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (environment_hash) DO UPDATE SET revision=EXCLUDED.revision, digest=EXCLUDED.digest,
                    document=EXCLUDED.document, fetched_at=EXCLUDED.fetched_at
                WHERE EXCLUDED.revision > flagsmith_slot_policy_cache.revision
                   OR (EXCLUDED.revision = flagsmith_slot_policy_cache.revision
                       AND EXCLUDED.digest = flagsmith_slot_policy_cache.digest
                       AND EXCLUDED.fetched_at > flagsmith_slot_policy_cache.fetched_at)
                """, environmentHash, BookingStore.sqlTime(next.revision()), next.digest(), next.projection(), BookingStore.sqlTime(fetchedAt));
        Accepted accepted = saved == 1 ? new Accepted(next, fetchedAt) : cached();
        if (accepted == null) throw new IllegalStateException("Accepted policy cache unavailable");
        if (olderOrConflicting(next, accepted.document())) reject(next.digest(), "superseded database revision");
        active = accepted;
        if (current == null || !current.document().digest().equals(accepted.document().digest())) {
            log.info("Flagsmith slot policy active digest={} providerRevision={} rush={}", accepted.document().digest(),
                    accepted.document().revision(), accepted.document().rush());
        }
    }

    private Accepted cached() {
        return db.query("""
                SELECT revision, digest, document::text, fetched_at FROM public.flagsmith_slot_policy_cache WHERE environment_hash=?
                """, (row, index) -> {
            try {
                var document = FlagsmithDocument.read(json, row.getString("document"), null);
                if (!document.digest().equals(row.getString("digest")) || !document.revision().equals(BookingStore.at(row, "revision"))) {
                    throw new IllegalStateException("Flagsmith cached policy did not match its revision");
                }
                return new Accepted(document, BookingStore.at(row, "fetched_at"));
            } catch (IOException invalid) {
                throw new IllegalStateException("Flagsmith cached policy was invalid");
            }
        }, environmentHash).stream().findFirst().orElse(null);
    }

    private static boolean olderOrConflicting(FlagsmithDocument next, FlagsmithDocument previous) {
        return next.revision().isBefore(previous.revision())
                || (next.revision().equals(previous.revision()) && !next.digest().equals(previous.digest()));
    }

    private void reject(String digest, String cause) {
        if (!rejectedDigests.add(digest)) return;
        if (rejectedDigests.size() > 64) rejectedDigests.remove(rejectedDigests.iterator().next());
        rejected.increment();
        log.warn("Flagsmith slot policy rejected digest={} reason={}", digest, cause);
    }

    @Override
    public Snapshot capture() {
        if (!enabled) return null;
        var captured = active;
        String identity = "pickup-policy:" + TenantContext.schema();
        return (grams, used, capacity) -> {
            Instant evaluatedAt = Instant.now();
            if (capacity <= 0 || used < 0 || used > capacity) throw new IllegalArgumentException("Invalid pickup slot load");
            if (captured != null) {
                try {
                    var document = captured.document();
                    var profile = document.evaluate(identity, (int) (100L * used / capacity));
                    String source = captured.fetchedAt().plus(staleAge).isBefore(evaluatedAt) ? "CACHED" : "LIVE";
                    return policy(profile, document.rush(), source, captured.fetchedAt(), document.revision(), document.digest(),
                            evaluatedAt, grams, used, capacity);
                } catch (RuntimeException failure) {
                    log.warn("Flagsmith slot evaluation could not use digest={}", captured.document().digest());
                }
            }
            return policy(FALLBACK, false, "FALLBACK", null, null, FALLBACK_DIGEST, evaluatedAt, grams, used, capacity);
        };
    }

    private static BookingModels.Policy policy(FlagsmithDocument.Profile profile, boolean rush, String source,
                                              Instant fetchedAt, Instant revision, String digest, Instant evaluatedAt,
                                              int grams, int used, int capacity) {
        return new BookingModels.Policy(digest.substring(0, Math.min(12, digest.length())), source, fetchedAt, evaluatedAt,
                rush, 5000, profile.threshold(), profile.hold(), profile.lead(), profile.ttl(), profile.grace(), revision)
                .withSlot(SlotPolicyProvider.audit(digest, profile.name(), profile.reason(), grams, profile.threshold(), used, capacity));
    }

    static URI documentEndpoint(URI configured) {
        String host = configured.getHost();
        if ("api.flagsmith.com".equals(host) || "edge.api.flagsmith.com".equals(host)) {
            return URI.create("https://api.flagsmith.com/api/v1/environment-document/");
        }
        if (host == null || configured.getUserInfo() != null || configured.getQuery() != null || configured.getFragment() != null
                || (!"https".equals(configured.getScheme()) && !("http".equals(configured.getScheme()) && "127.0.0.1".equals(host)))) {
            throw new IllegalArgumentException("Flagsmith document endpoint requires HTTPS or loopback");
        }
        return URI.create(configured.toString().replaceFirst("/(?:flags|environment-document)/?$", "/")
                .replaceFirst("/*$", "/") + "environment-document/");
    }

    private record Accepted(FlagsmithDocument document, Instant fetchedAt) {}
}
