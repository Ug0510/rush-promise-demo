package com.rushpromise.booking;

import com.rushpromise.booking.BookingModels.Policy;
import com.rushpromise.platform.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
@DependsOnDatabaseInitialization
@Profile("!worker")
public class PolicyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private static final URI HOSTED_FLAGS = URI.create("https://api.flagsmith.com/api/v1/flags/");
    private static final String REVISION_HEADER = "X-Flagsmith-Document-Updated-At";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String key;
    private final String environmentHash;
    private final JdbcTemplate db;
    private final URI url;
    private final long refreshMs;
    @Value("${RUSH_SLOT_POLICY_ENABLED:false}")
    private boolean slotPolicyEnabled;
    private volatile Snapshot last;
    private boolean lastRefreshFailed;
    private boolean sawUnversionedNormal;

    public PolicyService(ObjectMapper json, JdbcTemplate db, @Value("${rush.policy.flagsmith-key}") String key,
                         @Value("${rush.policy.flagsmith-url}") URI url,
                         @Value("${rush.policy.refresh-ms}") long refreshMs) {
        this.url = versionedEndpoint(url);
        if (!key.isBlank() && !"https".equals(this.url.getScheme()) && !"127.0.0.1".equals(this.url.getHost())) {
            throw new IllegalArgumentException("Flagsmith requires HTTPS or a local loopback URL");
        }
        this.json = json;
        this.db = db;
        this.environmentHash = fingerprint(this.url, key);
        this.key = key;
        this.refreshMs = refreshMs;
    }

    @PostConstruct
    void restoreOnStartup() {
        if (!slotPolicyEnabled && !key.isBlank()) restoreCache();
    }

    @Scheduled(fixedDelayString = "${rush.policy.refresh-ms}")
    public synchronized void refresh() {
        if (slotPolicyEnabled || key.isBlank()) return;
        restoreCache();
        try {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3))
                    .header("X-Environment-Key", key).header("Accept", "application/json")
                    .header("Cache-Control", "no-cache").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Flagsmith returned HTTP " + response.statusCode());
            Snapshot next = readFlags(json.readTree(response.body()), response.headers().firstValue(REVISION_HEADER).orElse(null));
            Snapshot previous = last;
            if (isStale(next, previous)) {
                log.warn("Ignoring older or inconsistent Flagsmith state at upstream revision {}", next.providerUpdatedAt());
                return;
            }
            if (next.providerUpdatedAt() == null && previous != null && previous.rush() && !next.rush() && !sawUnversionedNormal) {
                sawUnversionedNormal = true;
                return;
            }
            sawUnversionedNormal = false;
            last = saveCache(next);
            if (previous == null || previous.rush() != last.rush()) {
                log.info("Flagsmith rush policy {} at upstream revision {}", last.rush() ? "ON" : "OFF", last.providerUpdatedAt());
            }
            lastRefreshFailed = false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            sawUnversionedNormal = false;
            if (!lastRefreshFailed) log.warn("Flagsmith policy refresh failed: {}", failure.getClass().getSimpleName());
            lastRefreshFailed = true;
        }
    }

    private Snapshot readFlags(JsonNode document, String headerRevision) {
        Instant revision = headerRevision == null ? null : parseRevision(headerRevision);
        if (HOSTED_FLAGS.equals(url) && revision == null) {
            throw new IllegalStateException("Hosted Flagsmith must return its document revision header");
        }
        JsonNode flags = document;
        if (document.isObject()) {
            Instant documentRevision = Instant.parse(document.path("updated_at").asText());
            if (revision != null && !revision.equals(documentRevision)) {
                throw new IllegalStateException("Flagsmith returned inconsistent document revisions");
            }
            revision = documentRevision;
            flags = document.path("feature_states");
        }
        if (!flags.isArray()) throw new IllegalStateException("Flagsmith returned an invalid document");
        for (JsonNode flag : flags) {
            if ("rush_mode".equals(flag.path("feature").path("name").asText()) && flag.path("enabled").isBoolean()
                    && !flag.hasNonNull("feature_segment")) {
                return new Snapshot(flag.path("enabled").asBoolean(), Instant.now(), revision);
            }
        }
        throw new IllegalStateException("Flagsmith environment has no default rush_mode flag");
    }

    private static boolean isStale(Snapshot next, Snapshot previous) {
        if (previous == null || previous.providerUpdatedAt() == null) return false;
        if (next.providerUpdatedAt() == null || next.providerUpdatedAt().isBefore(previous.providerUpdatedAt())) return true;
        return next.providerUpdatedAt().equals(previous.providerUpdatedAt()) && next.rush() != previous.rush();
    }

    static URI versionedEndpoint(URI configured) {
        String host = configured.getHost();
        String path = configured.getPath();
        if ("https".equals(configured.getScheme()) && ("edge.api.flagsmith.com".equals(host) || "api.flagsmith.com".equals(host))
                && ("/api/v1/flags/".equals(path) || "/api/v1/flags".equals(path))) return HOSTED_FLAGS;
        return configured;
    }

    private static Instant parseRevision(String raw) {
        BigDecimal timestamp = new BigDecimal(raw);
        long seconds = timestamp.setScale(0, RoundingMode.FLOOR).longValueExact();
        long nanos = timestamp.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValueExact();
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private void restoreCache() {
        if (last != null) return;
        try {
            last = fromCache();
        } catch (DataAccessException failure) {
            log.warn("Flagsmith cached policy was unavailable");
        }
    }

    private Snapshot fromCache() {
        return db.query("SELECT rush_mode,fetched_at,provider_updated_at FROM flagsmith_policy_cache WHERE environment_hash = ?",
                (row, index) -> new Snapshot(row.getBoolean("rush_mode"), BookingStore.at(row, "fetched_at"),
                        BookingStore.at(row, "provider_updated_at")), environmentHash).stream().findFirst().orElse(null);
    }

    private Snapshot saveCache(Snapshot snapshot) {
        try {
            int changed = db.update("""
                    INSERT INTO flagsmith_policy_cache (environment_hash,rush_mode,fetched_at,provider_updated_at) VALUES (?,?,?,?)
                    ON CONFLICT (environment_hash) DO UPDATE SET rush_mode = EXCLUDED.rush_mode, fetched_at = EXCLUDED.fetched_at,
                        provider_updated_at = EXCLUDED.provider_updated_at
                    WHERE COALESCE(EXCLUDED.provider_updated_at,'-infinity') > COALESCE(flagsmith_policy_cache.provider_updated_at,'-infinity')
                       OR (EXCLUDED.provider_updated_at IS NOT DISTINCT FROM flagsmith_policy_cache.provider_updated_at
                           AND (EXCLUDED.provider_updated_at IS NULL OR EXCLUDED.rush_mode = flagsmith_policy_cache.rush_mode)
                           AND flagsmith_policy_cache.fetched_at < EXCLUDED.fetched_at)
                    """, environmentHash, snapshot.rush(), BookingStore.sqlTime(snapshot.fetchedAt()), BookingStore.sqlTime(snapshot.providerUpdatedAt()));
            if (changed == 0) {
                Snapshot winner = fromCache();
                if (winner != null) return winner;
            }
        } catch (DataAccessException failure) {
            log.warn("Flagsmith policy could not be persisted locally");
        }
        return snapshot;
    }

    private static String fingerprint(URI url, String key) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((url + "\n" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public Policy current() {
        Instant now = Instant.now();
        if (!TenantContext.legacy()) return profile(false, "SHOP_DEFAULT", null, now);
        Snapshot observed = last;
        if (observed == null) return profile(true, "FALLBACK", null, now);
        String source = observed.fetchedAt().plusMillis(refreshMs * 2).isBefore(now) ? "CACHED" : "LIVE";
        return profile(observed.rush(), source, observed.fetchedAt(), now, observed.providerUpdatedAt());
    }

    static Policy profile(boolean rush, String source, Instant fetchedAt, Instant now) {
        return profile(rush, source, fetchedAt, now, null);
    }

    private static Policy profile(boolean rush, String source, Instant fetchedAt, Instant now, Instant providerUpdatedAt) {
        return new Policy(rush ? "v-rush-1" : "v-normal-1", source, fetchedAt, now,
                rush, 5000, rush ? 3000 : 4000, 15, rush ? 30 : 15, rush ? 120 : 180, rush ? 20 : 30, providerUpdatedAt);
    }

    private record Snapshot(boolean rush, Instant fetchedAt, Instant providerUpdatedAt) {}
}
