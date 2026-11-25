package com.rushpromise.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@Profile("!worker")
public class OwnerPhoneInventory {
    private final JdbcTemplate db;
    private final OwnerPhoneAccess access;
    private final TenantInventory inventory;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    OwnerPhoneInventory(JdbcTemplate db, OwnerPhoneAccess access, TenantInventory inventory,
                        TransactionTemplate transactions, ObjectMapper json) {
        this.db = db;
        this.access = access;
        this.inventory = inventory;
        this.transactions = transactions;
        this.json = json;
    }

    public record Command(String event, String interactionId, String shopSlug, String shopQuery, String accessCode,
                          String language, String sku, String itemName, String unit, Integer stockQuantity,
                          Integer pricePerUnitPaise, String mutation, Integer sequence, String utterance) {}
    public record Reply(String state, String language, String shopName, String replyText, List<TenantInventory.Product> products) {}
    enum Action { SET_STOCK, ADD_STOCK, SET_PRICE, ADD_ITEM }
    record Proposal(Action mutation, String sku, String itemName, String unit, int stock, int paise, TenantInventory.PhoneStock before) {}
    private record Stored(int sequence, String challenge, Proposal proposal, boolean live, String state, Reply outcome) {}

    public Reply handle(String callHash, Command command, String accessCode, String approvalCode) {
        boolean hindi = !"en-IN".equalsIgnoreCase(command.language());
        if ("authenticate".equals(command.event())) {
            var result = access.authenticate(callHash, command.shopSlug(), command.shopQuery(), accessCode);
            if (result.session() != null) return answer("AUTHENTICATED", hindi, result.session(),
                    "Shop access confirmed. You can ask for stock, prices, or a change.",
                    "दुकान की पहचान हो गई। स्टॉक या कीमत पूछिए, या बदलाव बताइए।", List.of());
            return authResponse(hindi, result.state());
        }
        var bound = access.session(callHash);
        if (bound == null || command.shopSlug() != null && !command.shopSlug().isBlank() && !bound.slug().equals(command.shopSlug())) {
            return authResponse(hindi,"AUTH_REQUIRED");
        }
        return TenantContext.withSchema(bound.schema(), () -> transactions.execute(status -> {
            var session = access.lockSession(callHash);
            if (session == null || !session.tenantId().equals(bound.tenantId())) return authResponse(hindi,"AUTH_REQUIRED");
            return switch (command.event() == null ? "" : command.event()) {
                case "list" -> showProducts(inventory.products(), hindi, session);
                case "lookup" -> showProducts(matching(command), hindi, session);
                case "stage" -> stage(callHash, command, hindi, session);
                case "approve" -> approve(callHash, approvalCode, hindi, session);
                case "cancel", "end" -> cancel(callHash, "end".equals(command.event()), hindi, session);
                default -> answer("INVALID", hindi, session, "Ask for stock or price, or describe a change.",
                        "स्टॉक या कीमत पूछिए, या बदलाव बताइए।", List.of());
            };
        }));
    }

    private Reply stage(String call, Command command, boolean hindi, OwnerPhoneAccess.Session shop) {
        if (command.sequence() == null || command.sequence() < 1 || command.sequence() > 1000) return invalid(hindi,shop);
        var known = stored("sequence=?", call, command.sequence());
        if (known != null) {
            if (known.outcome() != null) return known.outcome();
            if (known.live() && "PENDING".equals(known.state())) return readback(known.proposal(),known.challenge(),hindi,shop,false);
            return changed(hindi,shop);
        }
        Integer latest = db.queryForObject("SELECT COALESCE(max(sequence),0) FROM public.owner_phone_mutations WHERE call_hash=?",Integer.class,call);
        if (latest != null && command.sequence() <= latest) return changed(hindi,shop);
        Proposal proposal = proposal(command);
        if (proposal == null) return invalid(hindi,shop);
        db.update("UPDATE public.owner_phone_mutations SET state='CANCELLED' WHERE call_hash=? AND state='PENDING'",call);
        String challenge;
        do { challenge = Integer.toString(1000 + random.nextInt(9000)); }
        while (stored("challenge=?",call,challenge) != null);
        db.update("""
                INSERT INTO public.owner_phone_mutations(call_hash,sequence,challenge,proposed,expires_at,state)
                VALUES (?,?,?,?::jsonb,statement_timestamp()+interval '2 minutes','PENDING')
                """,call,command.sequence(),challenge,write(proposal));
        return readback(proposal,challenge,hindi,shop,false);
    }

    private Proposal proposal(Command command) {
        Action action;
        try { action = Action.valueOf(command.mutation() == null ? "" : command.mutation()); }
        catch (IllegalArgumentException invalid) { return null; }
        Integer stock = command.stockQuantity(), price = command.pricePerUnitPaise();
        if (action == Action.ADD_ITEM) {
            String name;
            try { name = TenantDirectory.clean(command.itemName(),70,true); }
            catch (ResponseStatusException invalid) { return null; }
            if (!validUnit(command.unit()) || !validStock(stock,command.unit()) || !validPrice(price)) return null;
            return new Proposal(action,TenantInventory.generatedSku(name),name,command.unit(),stock,price,null);
        }
        var matches = matching(command);
        if (matches.size()!=1) return null;
        var current = inventory.phoneStock(matches.getFirst().sku());
        if (current == null) return null;
        var item = current.product();
        if (action == Action.SET_PRICE) {
            if (!validPrice(price)) return null;
            return new Proposal(action,item.sku(),item.itemName(),item.unit(),item.freeQuantity(),price,current);
        }
        if (!current.hasTray() || current.blocked() || !validStock(stock,item.unit()) || action == Action.ADD_STOCK && stock==0) return null;
        int newFree = action==Action.ADD_STOCK ? item.freeQuantity()+stock : stock;
        if (!validStock(newFree+current.held(),item.unit())) return null;
        return new Proposal(action,item.sku(),item.itemName(),item.unit(),newFree,item.pricePerUnitPaise(),current);
    }

    private Reply approve(String call, String code, boolean hindi, OwnerPhoneAccess.Session shop) {
        var row = code == null ? null : stored("challenge=?",call,code);
        if (row != null && row.outcome() != null) return row.outcome();
        if (row == null || !row.live() || !"PENDING".equals(row.state())) return answer("APPROVAL_REQUIRED",hindi,shop,
                "No change was made. Say the current exact approval phrase, or ask for a new change.",
                "कोई बदलाव नहीं हुआ। मंज़ूरी के लिए मौजूदा वाक्य और नंबर कहिए, या नया बदलाव बताइए।",List.of());
        var proposed = row.proposal();
        var current = inventory.phoneStock(proposed.sku());
        boolean applicable = switch (proposed.mutation()) {
            case ADD_ITEM -> current == null;
            case SET_PRICE -> current != null && current.product().itemName().equals(proposed.itemName())
                    && current.product().unit().equals(proposed.unit())
                    && current.product().pricePerUnitPaise() == proposed.before().product().pricePerUnitPaise();
            case SET_STOCK, ADD_STOCK -> Objects.equals(current,proposed.before());
        };
        Reply outcome;
        if (!applicable) outcome = changed(hindi,shop);
        else {
            var updated = switch (proposed.mutation()) {
                case ADD_ITEM -> inventory.save(new TenantInventory.ProductInput(proposed.sku(),proposed.itemName(),proposed.unit(),
                        proposed.paise(),proposed.stock(),null));
                case SET_PRICE -> inventory.changePhonePrice(current,proposed.paise());
                case SET_STOCK, ADD_STOCK -> inventory.changePhoneFreeStock(current,proposed.stock());
            };
            var spoken = readback(proposed,null,hindi,shop,true);
            outcome = new Reply(spoken.state(),spoken.language(),spoken.shopName(),spoken.replyText(),List.of(updated));
        }
        db.update("UPDATE public.owner_phone_mutations SET state=?,outcome=?::jsonb WHERE call_hash=? AND sequence=?",
                outcome.state(),write(outcome),call,row.sequence());
        return outcome;
    }

    private Reply cancel(String call, boolean end, boolean hindi, OwnerPhoneAccess.Session shop) {
        db.update("UPDATE public.owner_phone_mutations SET state='CANCELLED' WHERE call_hash=? AND state='PENDING'",call);
        if (end) db.update("UPDATE public.owner_phone_sessions SET expires_at=statement_timestamp() WHERE call_hash=?",call);
        return answer("CANCELLED",hindi,shop,"Pending changes cancelled.","लंबित बदलाव रद्द कर दिए।",List.of());
    }

    private List<TenantInventory.Product> matching(Command command) {
        var products = inventory.products();
        if (command.sku() != null && !command.sku().isBlank()) return products.stream().filter(p -> p.sku().equals(command.sku().strip())).toList();
        if (command.itemName() == null || command.itemName().isBlank()) return List.of();
        String name = itemKey(command.itemName());
        var exact = products.stream().filter(product -> itemKey(product.itemName()).equals(name)).toList();
        if (!exact.isEmpty()) return exact;
        String alias = switch (name) {
            case "बर्फी", "बर्फ़ी", "बरफी", "बरफ़ी", "burfi" -> "barfi";
            case "काजू कतली", "काजू कटली", "kaaju katli" -> "kaju-katli";
            default -> "";
        };
        return products.stream().filter(product -> product.sku().equals(alias)).toList();
    }

    private static String itemKey(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ");
    }

    private Stored stored(String predicate, String call, Object value) {
        return db.query("""
                SELECT sequence,challenge,proposed::text,expires_at>statement_timestamp(),state,outcome::text
                FROM public.owner_phone_mutations WHERE call_hash=? AND
                """ + predicate, (r,index)->new Stored(r.getInt(1),r.getString(2),read(r.getString(3),Proposal.class),r.getBoolean(4),
                r.getString(5),read(r.getString(6),Reply.class)),call,value).stream().findFirst().orElse(null);
    }

    private Reply showProducts(List<TenantInventory.Product> matches, boolean hindi, OwnerPhoneAccess.Session shop) {
        if (matches.isEmpty()) return answer("NO_MATCH",hindi,shop,"No matching item. Say its exact menu name.",
                "यह आइटम नहीं मिला। मेनू में दिया पूरा नाम बताइए।",List.of());
        var shown = matches.stream().limit(10).toList();
        String description = String.join(". ",shown.stream().map(p->p.itemName()+", "+quantity(p.freeQuantity(),p.unit(),hindi)
                +(hindi ? " उपलब्ध, " : " available, ")+price(p.pricePerUnitPaise(),p.unit(),hindi)).toList()) + ".";
        if (matches.size()>shown.size()) description += hindi ? " किसी और आइटम का नाम पूछ सकते हैं।" : " Ask for another item by name.";
        return answer("PRODUCTS",hindi,shop,description,description,shown);
    }

    private Reply readback(Proposal draft, String challenge, boolean hindi, OwnerPhoneAccess.Session shop, boolean applied) {
        String name = draft.itemName();
        String qty = quantity(draft.stock(),draft.unit(),hindi);
        String cost = price(draft.paise(),draft.unit(),hindi);
        String description = switch (draft.mutation()) {
            case SET_PRICE -> name + (hindi ? " की कीमत " : " price: ") + price(draft.before().product().pricePerUnitPaise(),draft.unit(),hindi)
                    + (hindi ? " से " : " to ") + cost;
            case SET_STOCK, ADD_STOCK -> name + (hindi ? " नए ऑर्डर के लिए " : " available for new orders: ")
                    + quantity(draft.before().product().freeQuantity(),draft.unit(),hindi) + (hindi ? " से " : " to ") + qty
                    + (hindi ? "। पहले से बुक " : ". Keep ") + quantity(draft.before().held(),draft.unit(),hindi)
                    + (hindi ? " अलग रहेगा" : " reserved");
            case ADD_ITEM -> (hindi ? "नया आइटम " : "New item ") + name + ", " + qty + ", " + cost;
        };
        if (applied) description += hindi ? "। बदलाव सहेज दिया गया।" : ". Change saved.";
        else {
            String digits = String.join(" ",challenge.split(""));
            description += hindi ? "। मंज़ूरी देने के लिए कहें: हाँ " + digits + "।" : ". To approve, say: approve code " + digits + ".";
        }
        return answer(applied ? "APPLIED" : "APPROVAL_REQUIRED",hindi,shop,description,description,List.of());
    }

    private static boolean validUnit(String unit) { return "GRAMS".equals(unit) || "PIECE".equals(unit); }
    private static boolean validStock(Integer quantity,String unit) { return quantity!=null && quantity>=0 && quantity<=("PIECE".equals(unit)?10_000:100_000); }
    private static boolean validPrice(Integer price) { return price!=null && price>0 && price<=1_000_000; }

    private static String quantity(int count,String unit,boolean hindi) {
        return count + ("PIECE".equals(unit) ? (hindi ? " पीस" : count==1 ? " piece" : " pieces") : (hindi ? " ग्राम" : " grams"));
    }

    private static String price(int paise,String unit,boolean hindi) {
        String value=BigDecimal.valueOf(paise,2).stripTrailingZeros().toPlainString();
        return hindi ? value + ("PIECE".equals(unit) ? " रुपये प्रति पीस" : " रुपये प्रति किलो")
                : value + ("PIECE".equals(unit) ? " rupees per piece" : " rupees per kilo");
    }

    private static Reply changed(boolean hindi,OwnerPhoneAccess.Session shop) {
        return answer("CHANGED",hindi,shop,"The offer expired or the inventory changed. Ask for a fresh readback.",
                "समय समाप्त हो गया या स्टॉक बदल गया। नए बदलाव के लिए फिर से बताइए।",List.of());
    }

    private static Reply invalid(boolean hindi,OwnerPhoneAccess.Session shop) {
        return answer("INVALID",hindi,shop,"Check the exact item name, pickup quantity, unit and price.",
                "आइटम का पूरा नाम, पिकअप मात्रा, यूनिट और कीमत बताइए।",List.of());
    }

    private static Reply authResponse(boolean hindi,String state) {
        if ("ACCESS_LOCKED".equals(state)) return answer(state,hindi,null,"Too many attempts. Try again later.",
                "बहुत बार कोशिश हुई। कुछ समय बाद फिर कोशिश करें।",List.of());
        return answer(state,hindi,null,"Owner access needs the exact shop and a current one-time code.",
                "मालिक की पहचान के लिए दुकान का पूरा नाम और नया एक बार इस्तेमाल वाला कोड चाहिए।",List.of());
    }

    private static Reply answer(String state, boolean hindi, OwnerPhoneAccess.Session shop, String english, String localized,
                                List<TenantInventory.Product> products) {
        return new Reply(state,hindi?"hi-IN":"en-IN",shop==null?null:shop.shopName(),hindi?localized:english,products);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not save inventory approval",failure); }
    }

    private <T> T read(String value,Class<T> type) {
        if (value==null) return null;
        try { return json.readValue(value,type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not read inventory approval",failure); }
    }
}
