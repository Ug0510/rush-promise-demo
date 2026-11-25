package com.rushpromise.booking;

import com.rushpromise.booking.BookingModels.Policy;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String key;
    private final String environmentHash;
    private final JdbcTemplate db;
    private final URI url;
    private final long refreshMs;
    private volatile Snapshot last;
    private boolean lastRefreshFailed;

    public PolicyService(ObjectMapper json, JdbcTemplate db, @Value("${rush.policy.flagsmith-key}") String key,
                         @Value("${rush.policy.flagsmith-url}") URI url,
                         @Value("${rush.policy.refresh-ms}") long refreshMs) {
        if (!key.isBlank() && !"https".equals(url.getScheme()) && !"127.0.0.1".equals(url.getHost())) {
            throw new IllegalArgumentException("Flagsmith requires HTTPS or a local loopback URL");
        }
        this.json = json;
        this.db = db;
        this.environmentHash = fingerprint(url, key);
        this.key = key;
        this.url = url;
        this.refreshMs = refreshMs;
    }

    @PostConstruct
    void restoreOnStartup() {
        if (!key.isBlank()) restoreCache();
    }

    @Scheduled(fixedDelayString = "${rush.policy.refresh-ms}")
    public synchronized void refresh() {
        if (key.isBlank()) return;
        restoreCache();
        try {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3))
                    .header("X-Environment-Key", key).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Flagsmith returned HTTP " + response.statusCode());
            }
            JsonNode flags = json.readTree(response.body());
            if (!flags.isArray()) throw new IllegalStateException("Flagsmith returned an invalid document");
            for (JsonNode flag : flags) {
                if ("rush_mode".equals(flag.path("feature").path("name").asText())
                        && flag.path("enabled").isBoolean()) {
                    var fresh = new Snapshot(flag.path("enabled").asBoolean(), Instant.now());
                    last = fresh;
                    lastRefreshFailed = false;
                    saveCache(fresh);
                    return;
                }
            }
            throw new IllegalStateException("Flagsmith environment has no rush_mode flag");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!lastRefreshFailed) log.warn("Flagsmith policy refresh failed: {}", failure.getClass().getSimpleName());
            lastRefreshFailed = true;
        }
    }

    private void restoreCache() {
        if (last != null) return;
        try {
            last = db.query("SELECT rush_mode,fetched_at FROM flagsmith_policy_cache WHERE environment_hash = ?",
                    (row, index) -> new Snapshot(row.getBoolean("rush_mode"), BookingStore.at(row, "fetched_at")), environmentHash)
                    .stream().findFirst().orElse(null);
        } catch (DataAccessException failure) {
            log.warn("Flagsmith cached policy was unavailable");
        }
    }

    private void saveCache(Snapshot snapshot) {
        try {
            db.update("""
                    INSERT INTO flagsmith_policy_cache (environment_hash,rush_mode,fetched_at) VALUES (?,?,?)
                    ON CONFLICT (environment_hash) DO UPDATE SET rush_mode = EXCLUDED.rush_mode, fetched_at = EXCLUDED.fetched_at
                    WHERE flagsmith_policy_cache.fetched_at < EXCLUDED.fetched_at
                    """, environmentHash, snapshot.rush(), BookingStore.sqlTime(snapshot.fetchedAt()));
        } catch (DataAccessException failure) {
            log.warn("Flagsmith policy could not be persisted locally");
        }
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
        Snapshot observed = last;
        if (observed == null) return profile(true, "FALLBACK", null, now);
        String source = observed.fetchedAt().plusMillis(refreshMs * 2).isBefore(now) ? "CACHED" : "LIVE";
        return profile(observed.rush(), source, observed.fetchedAt(), now);
    }

    static Policy profile(boolean rush, String source, Instant fetchedAt, Instant now) {
        return new Policy(rush ? "v-rush-1" : "v-normal-1", source, fetchedAt, now,
                rush, 5000, rush ? 3000 : 4000, 15, rush ? 30 : 15, rush ? 120 : 180, rush ? 20 : 30);
    }

    private record Snapshot(boolean rush, Instant fetchedAt) {}
}
