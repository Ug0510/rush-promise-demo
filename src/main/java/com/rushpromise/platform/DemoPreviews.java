package com.rushpromise.platform;

import com.rushpromise.booking.BookingModels.PickupHoursInput;
import com.rushpromise.booking.BookingService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@Profile("!worker")
class DemoPreviews {
    private final OwnerAccounts accounts;
    private final TenantDirectory tenants;
    private final TenantInventory inventory;
    private final BookingService booking;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    DemoPreviews(OwnerAccounts accounts,TenantDirectory tenants,TenantInventory inventory,BookingService booking,
                 JdbcTemplate db,TransactionTemplate transactions) {
        this.accounts=accounts;
        this.tenants=tenants;
        this.inventory=inventory;
        this.booking=booking;
        this.db=db;
        this.transactions=transactions;
    }

    OwnerAccounts.Grant open(UUID sessionKey) {
        UUID id=accounts.reservePreview(sessionKey);
        if (tenants.forOwner(id)==null) {
            var sample=new TenantDirectory.ShopInput("Deepak Mithai Bhandar · Demo", "preview-"+id.toString().replace("-",""),
                    null,null,"Private sample inventory for this demo session.");
            try { tenants.onboard(id,sample,this::seed); }
            catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode()!=HttpStatus.CONFLICT) throw conflict;
                if (tenants.forOwner(id)==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Your demo shop is opening. Try again in a moment.");
            }
        }
        return accounts.grantPreview(id);
    }

    private void seed() {
        booking.updatePickupHours(new PickupHoursInput(false,"09:00","21:00"));
        inventory.save(new TenantInventory.ProductInput("kaju-katli","Kaju katli","GRAMS",120000,2000,null));
        inventory.save(new TenantInventory.ProductInput("barfi","Barfi","GRAMS",90000,3000,null));
    }

    @Scheduled(fixedDelay=300_000,initialDelay=300_000)
    void prune() {
        transactions.executeWithoutResult(status -> {
            db.execute("SET LOCAL lock_timeout='500ms'");
            db.execute("SELECT pg_advisory_xact_lock(24938440)");
            var expired=db.query("""
                    SELECT a.id,t.schema_name FROM public.rush_accounts a LEFT JOIN public.rush_tenants t ON t.owner_id=a.id
                    WHERE a.preview_expires_at < statement_timestamp() ORDER BY a.preview_expires_at LIMIT 4
                    """,(row,index)->new Expired(row.getObject(1,UUID.class),row.getString(2)));
            for (var preview:expired) {
                if (preview.schema()!=null) {
                    if (!preview.schema().matches("shop_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected preview schema");
                    db.execute("DROP SCHEMA "+preview.schema()+" CASCADE");
                    db.update("DELETE FROM public.rush_tenants WHERE owner_id=?",preview.account());
                }
                db.update("DELETE FROM public.rush_accounts WHERE id=? AND preview_expires_at<statement_timestamp()",preview.account());
            }
        });
    }

    private record Expired(UUID account,String schema) {}
}
