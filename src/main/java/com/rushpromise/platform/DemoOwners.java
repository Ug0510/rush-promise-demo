package com.rushpromise.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Component
@Profile("demo & !worker")
class DemoOwners implements ApplicationRunner {
    private static final Map<String, String> SHOPS = Map.of(
            "deepak-owner", "Deepak Mithai Bhandar", "viraj-owner", "Viraj's Bakery");
    private final ObjectMapper json;
    private final OwnerAccounts accounts;
    private final TenantDirectory tenants;
    private final TenantInventory inventory;
    private final String file;
    private final String bundle;

    DemoOwners(ObjectMapper json, OwnerAccounts accounts, TenantDirectory tenants, TenantInventory inventory,
               @Value("${RUSH_DEMO_OWNER_FILE:}") String file, @Value("${RUSH_DEMO_OWNER_BUNDLE:}") String bundle) {
        this.json = json;
        this.accounts = accounts;
        this.tenants = tenants;
        this.inventory = inventory;
        this.file = file;
        this.bundle = bundle;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (file.isBlank() && bundle.isBlank()) return;
        var supplied = readOwners();
        if (supplied.owners() == null || supplied.owners().size() != SHOPS.size()) invalid();
        var names = new HashSet<String>();
        for (var entry : supplied.owners()) {
            String expectedSlug = "deepak-owner".equals(entry.username()) ? "deepak-mithai-bhandar" : "virajs-bakery";
            if (!names.add(entry.username()) || !entry.displayName().equals(SHOPS.get(entry.username()))
                    || !expectedSlug.equals(entry.slug())) invalid();
        }
        for (var entry : supplied.owners()) provision(entry);
    }

    private void provision(Entry entry) {
        var owner = accounts.ensureDemoAccount(new OwnerAccounts.Credentials(entry.username(), entry.password()));
        var shop = tenants.forOwner(owner);
        if (shop == null) {
            shop = tenants.onboard(owner, new TenantDirectory.ShopInput(entry.displayName(), entry.slug(), null, null,
                    "Demo pickup. Confirm the time before visiting."));
        }
        if (!shop.slug().equals(entry.slug())) throw new IllegalStateException("A demo owner is linked to a different shop");
        tenants.markFixture(shop);
        TenantContext.withSchema(shop.schema(), () -> {
            inventory.seedDefaultPickupHours();
            List<TenantInventory.ProductInput> sample = "deepak-owner".equals(entry.username())
                    ? List.of(new TenantInventory.ProductInput("kaju-katli", "Kaju katli", "GRAMS", 120_000, 2000, null),
                              new TenantInventory.ProductInput("barfi", "Barfi", "GRAMS", 90_000, 3000, null))
                    : List.of(new TenantInventory.ProductInput("vanilla-cupcake", "Vanilla cupcake", "PIECE", 7500, 24, null),
                              new TenantInventory.ProductInput("chocolate-brownie", "Chocolate brownie", "PIECE", 9500, 18, null));
            var saved = inventory.products().stream().map(TenantInventory.Product::sku).toList();
            sample.stream().filter(product -> !saved.contains(product.sku())).forEach(inventory::save);
            return null;
        });
    }

    private OwnerFile readOwners() {
        if (!file.isBlank() && !bundle.isBlank()) return invalid();
        try {
            if (file.isBlank()) return json.readValue(Base64.getDecoder().decode(bundle), OwnerFile.class);
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > 10_000
                    || Files.getPosixFilePermissions(path).stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                    || permission.name().startsWith("OTHERS_"))) return invalid();
            return json.readValue(Files.readAllBytes(path), OwnerFile.class);
        } catch (Exception failure) {
            return invalid();
        }
    }

    private static <T> T invalid() {
        throw new IllegalStateException("Supply the protected handoff for these two demo owners");
    }

    private record OwnerFile(List<Entry> owners) {}
    private record Entry(String username, String password, String displayName, String slug) {}
}
