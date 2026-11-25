package com.rushpromise.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
@Profile("!worker")
public class SarvamShopRouting {
    private static final List<String> SHOPS = List.of("deepak-mithai-bhandar", "virajs-bakery");
    private final JdbcTemplate db;
    private final TenantDirectory tenants;

    SarvamShopRouting(JdbcTemplate db, TenantDirectory tenants) {
        this.db = db;
        this.tenants = tenants;
    }

    public List<Choice> choices() {
        return SHOPS.stream().map(tenants::bySlug).filter(Objects::nonNull)
                .map(shop -> new Choice(shop.slug(), shop.displayName())).toList();
    }

    public Route route(String interaction) {
        return db.query("""
                SELECT tenant.slug, tenant.schema_name
                FROM public.sarvam_shop_routes route JOIN public.rush_tenants tenant ON tenant.id=route.tenant_id
                WHERE route.call_hash=? AND tenant.state='ACTIVE'
                """, (row, index) -> new Route(row.getString("slug"), row.getString("schema_name")), digest(interaction))
                .stream().findFirst().orElse(null);
    }

    public Route choose(String interaction, String slug) {
        if (slug == null || !SHOPS.contains(slug)) return null;
        var shop = tenants.bySlug(slug);
        if (shop == null) return null;
        db.update("INSERT INTO public.sarvam_shop_routes (call_hash,tenant_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                digest(interaction), shop.id());
        return route(interaction);
    }

    @Scheduled(fixedDelay = 3_600_000)
    void prune() {
        db.update("DELETE FROM public.sarvam_shop_routes WHERE selected_at < statement_timestamp() - INTERVAL '30 days'");
    }

    private static String digest(String interaction) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(interaction.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record Choice(String slug, String displayName) {}
    public record Route(String slug, String schema) {}
}
