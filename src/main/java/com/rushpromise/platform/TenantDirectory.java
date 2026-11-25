package com.rushpromise.platform;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TenantDirectory {
    private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);
    private static final String FIELDS = "id, owner_id, slug, schema_name, display_name, area, hours, pickup_instructions, demo_fixture, state";
    private static final int PUBLIC_LIMIT = 12;
    private final JdbcTemplate db;
    private final DataSource dataSource;

    TenantDirectory(JdbcTemplate db, DataSource dataSource) {
        this.db = db;
        this.dataSource = dataSource;
    }

    Shop forOwner(UUID owner) {
        return one("SELECT " + FIELDS + " FROM public.rush_tenants WHERE owner_id=? AND state='ACTIVE'", owner);
    }

    Shop bySlug(String slug) {
        if (!validSlug(slug)) return null;
        return one("SELECT " + FIELDS + " FROM public.rush_tenants WHERE slug=? AND state='ACTIVE'"
                + " AND EXISTS (SELECT 1 FROM public.rush_accounts a WHERE a.id=owner_id AND a.preview_expires_at IS NULL)", slug);
    }

    DirectoryView storefrontDirectory(String query) {
        String key = routingKey(query);
        var matches = publicShops().stream()
                .filter(shop -> key.isEmpty() || routingKey(shop.displayName()).contains(key)
                        || routingKey(shop.area()).contains(key) || routingKey(shop.slug()).contains(key))
                .toList();
        return new DirectoryView(matches.stream().limit(PUBLIC_LIMIT).toList(), matches.size() > PUBLIC_LIMIT);
    }

    List<PublicShop> exactVoiceChoices(String query) {
        String key = routingKey(query);
        if (key.isEmpty()) return List.of();
        return publicShops().stream().filter(shop -> key.equals(routingKey(shop.slug())) || key.equals(routingKey(shop.displayName()))
                || shop.area() != null && key.equals(routingKey(shop.displayName() + " " + shop.area())))
                .toList();
    }

    List<DemoShop> demoShops() {
        return db.query("""
                SELECT t.slug,t.display_name,a.username FROM public.rush_tenants t
                JOIN public.rush_accounts a ON a.id=t.owner_id
                WHERE t.state='ACTIVE' AND t.demo_fixture AND a.preview_expires_at IS NULL
                  AND ((t.slug='deepak-mithai-bhandar' AND a.username='deepak-owner')
                    OR (t.slug='virajs-bakery' AND a.username='viraj-owner'))
                ORDER BY t.slug
                """, (row,index)->new DemoShop(row.getString(1),row.getString(2),row.getString(3)));
    }

    private List<PublicShop> publicShops() {
        return db.query("""
                SELECT slug,display_name,area FROM public.rush_tenants WHERE state='ACTIVE'
                  AND EXISTS (SELECT 1 FROM public.rush_accounts a WHERE a.id=owner_id AND a.preview_expires_at IS NULL)
                ORDER BY lower(display_name),slug
                """, (row, index) -> new PublicShop(row.getString("slug"), row.getString("display_name"), row.getString("area")));
    }

    private static String routingKey(String text) {
        if (text == null || text.length() > 220) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("['’]", "").replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
    }

    public List<String> activeSchemas() {
        return db.queryForList("""
                SELECT schema_name FROM public.rush_tenants WHERE state='ACTIVE'
                  AND EXISTS (SELECT 1 FROM public.rush_accounts a WHERE a.id=owner_id AND a.preview_expires_at IS NULL)
                ORDER BY schema_name
                """, String.class);
    }

    Shop onboard(UUID ownerId, ShopInput input) {
        return onboard(ownerId, input, null);
    }

    Shop onboard(UUID ownerId, ShopInput input, Runnable configureHours) {
        String displayName = clean(input.displayName(), 70, true);
        String area = clean(input.area(), 100, false);
        String hours = clean(input.hours(), 100, false);
        String instructions = clean(input.pickupInstructions(), 240, false);
        UUID id = UUID.randomUUID();
        String slug = input.slug() == null || input.slug().isBlank() ? defaultSlug(displayName, id) : input.slug().strip().toLowerCase(Locale.ROOT);
        if (!validSlug(slug)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use letters, digits and hyphens for the shop link");

        Shop reserved = one("SELECT " + FIELDS + " FROM public.rush_tenants WHERE owner_id=?", ownerId);
        if (reserved != null && reserved.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This account already has a shop");
        if (reserved == null) {
            String schema = "shop_" + id.toString().replace("-", "");
            try {
                db.update("""
                        INSERT INTO public.rush_tenants (id,owner_id,slug,schema_name,display_name,area,hours,pickup_instructions,state)
                        VALUES (?,?,?,?,?,?,?,?,'PROVISIONING')
                        """, id, ownerId, slug, schema, displayName, area, hours, instructions);
            } catch (DataIntegrityViolationException duplicate) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This shop link is already in use or setup is underway");
            }
            reserved = one("SELECT " + FIELDS + " FROM public.rush_tenants WHERE owner_id=?", ownerId);
        } else {
            if (!reserved.slug().equals(slug) && input.slug() != null && !input.slug().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish setting up the shop link you already chose");
            }
            int claimed = db.update("""
                    UPDATE public.rush_tenants SET state='PROVISIONING',provisioning_started_at=statement_timestamp()
                    WHERE id=? AND (state='FAILED' OR (state='PROVISIONING' AND provisioning_started_at < statement_timestamp()-INTERVAL '3 minutes'))
                    """, reserved.id());
            if (claimed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Shop setup is already underway");
        }

        var shop = reserved;
        try {
            Boolean allowed = db.queryForObject("SELECT has_database_privilege(current_database(), 'CREATE')", Boolean.class);
            if (!Boolean.TRUE.equals(allowed)) throw new IllegalStateException("Database role cannot provision a tenant schema");
            Flyway.configure().dataSource(dataSource).schemas(shop.schema()).defaultSchema(shop.schema()).load().migrate();
            TenantContext.withSchema(shop.schema(), () -> {
                db.update("UPDATE shop_state SET display_name=? WHERE id=1", displayName);
                if (configureHours != null) configureHours.run();
                return null;
            });
            db.update("""
                    UPDATE public.rush_tenants SET display_name=?,area=?,hours=?,pickup_instructions=?,state='ACTIVE'
                    WHERE id=?
                    """, displayName, area, hours, instructions, shop.id());
            return forOwner(ownerId);
        } catch (RuntimeException failure) {
            db.update("UPDATE public.rush_tenants SET state='FAILED' WHERE id=? AND state<>'ACTIVE'", shop.id());
            log.warn("Shop provisioning failed: {}", failure.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shop setup could not finish. Please try again later.");
        }
    }

    @Transactional
    public Shop updateDetails(Shop owned, ShopInput input) {
        String displayName = clean(input.displayName(), 70, true);
        String area = clean(input.area(), 100, false);
        String hours = clean(input.hours(), 100, false);
        String instructions = clean(input.pickupInstructions(), 240, false);
        db.update("UPDATE shop_state SET display_name=?,profile_updated_at=statement_timestamp() WHERE id=1", displayName);
        db.update("UPDATE public.rush_tenants SET display_name=?,area=?,hours=?,pickup_instructions=? WHERE id=? AND owner_id=?",
                displayName, area, hours, instructions, owned.id(), owned.owner());
        return forOwner(owned.owner());
    }

    void markFixture(Shop shop) {
        db.update("UPDATE public.rush_tenants SET demo_fixture=TRUE WHERE id=? AND owner_id=?", shop.id(), shop.owner());
    }

    private Shop one(String sql, Object value) {
        return db.query(sql, mapper(), value).stream().findFirst().orElse(null);
    }

    private static RowMapper<Shop> mapper() {
        return (row, index) -> new Shop(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getString("slug"), row.getString("schema_name"), row.getString("display_name"), row.getString("area"),
                row.getString("hours"), row.getString("pickup_instructions"), row.getBoolean("demo_fixture"), "ACTIVE".equals(row.getString("state")));
    }

    static String clean(String value, int max, boolean required) {
        String text = value == null ? "" : value.strip();
        if ((required && text.isBlank()) || text.codePointCount(0, text.length()) > max
                || text.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the shop profile fields");
        }
        return text.isEmpty() ? null : text;
    }

    private static boolean validSlug(String slug) {
        return slug != null && slug.length() <= 55 && slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private static String defaultSlug(String name, UUID id) {
        String text = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (text.isEmpty()) text = "shop";
        if (text.length() > 44) text = text.substring(0, 44).replaceAll("-$", "");
        return text + "-" + id.toString().substring(0, 8);
    }

    record Shop(UUID id, UUID owner, String slug, String schema, String displayName, String area,
                String hours, String pickupInstructions, boolean demoFixture, boolean active) {
        ShopView view() { return new ShopView(slug, displayName, area, hours, pickupInstructions, demoFixture); }
    }

    public record ShopInput(String displayName, String slug, String area, String hours, String pickupInstructions) {}
    public record ShopView(String slug, String displayName, String area, String hours, String pickupInstructions, boolean demoFixture) {}
    public record PublicShop(String slug, String displayName, String area) {}
    public record DirectoryView(List<PublicShop> shops, boolean truncated) {}
    public record DemoShop(String slug, String displayName, String username) {}
}
