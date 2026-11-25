package com.rushpromise.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUSH_DELIVERY_TEST_DB_URL", matches = ".+")
class OutboxPostgresTest {
    private static final String NUMBER = "+12025550100";
    private static final String SID = "SM11111111111111111111111111111111";
    private String schema;
    private JdbcClient database;
    private JdbcClient admin;
    private OutboxRepository outbox;
    private TransactionTemplate transaction;
    private UUID run;

    @BeforeEach
    void prepare() {
        String url = System.getenv("RUSH_DELIVERY_TEST_DB_URL");
        String user = System.getenv("RUSH_DELIVERY_TEST_DB_USER");
        String password = System.getenv("RUSH_DELIVERY_TEST_DB_PASSWORD");
        schema = "delivery_test_" + UUID.randomUUID().toString().replace("-", "");
        var base = new DriverManagerDataSource(url, user, password);
        admin = JdbcClient.create(base);
        Flyway.configure().dataSource(base).schemas(schema).defaultSchema(schema).load().migrate();
        var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            user, password);
        database = JdbcClient.create(scoped);
        outbox = new OutboxRepository(database);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(scoped));
        run = UUID.randomUUID();
        database.sql("DELETE FROM shop_state").update();
        database.sql("INSERT INTO shop_state VALUES (1,:run,now())").param("run",run).update();
        database.sql("INSERT INTO items (sku,name,price_per_kg_paise) VALUES ('test','Test',100000) ON CONFLICT DO NOTHING").update();
        database.sql("""
            INSERT INTO batches (id,sku,ready_at,status,phone_grams_total,phone_grams_free,counter_grams_counted)
            VALUES ('test','test',now(),'READY',1000,1000,0) ON CONFLICT DO NOTHING
            """).update();
        database.sql("INSERT INTO pickup_slots VALUES ('2026-11-25T18:30:00Z',1,0) ON CONFLICT DO NOTHING").update();
    }

    @AfterEach
    void clean() {
        if (admin != null) {
            admin.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }

    @Test
    void twoWorkersOnlyClaimOneRowAndAnExpiredClaimIsSafeToRequeue() throws Exception {
        order(1,"CONFIRMED");
        var id = message(1,"CONFIRMED");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> outbox.claim(false, ""));
            var second = threads.submit(() -> outbox.claim(false, ""));
            assertNotEquals(first.get() == null, second.get() == null);
        }
        assertEquals(1,outbox.pending(false));
        database.sql("UPDATE outbox_messages SET lease_expires_at=now()-interval '1 second'").update();
        recover();
        var job = begin();
        assertEquals(id,job.messageId());
        assertEquals(1,attempts());
    }

    @Test
    void sendingLeaseBecomesUnknownAndEarlyDeliveredCannotRegress() {
        order(1,"PACKED");
        var confirmed = message(1,"CONFIRMED");
        var ready = message(1,"READY");
        var job = begin();
        assertEquals(confirmed,job.messageId());
        database.sql("UPDATE outbox_messages SET lease_expires_at=now()-interval '1 second' WHERE status='SENDING'").update();
        recover();
        assertEquals("UNKNOWN",state(confirmed));
        assertNull(outbox.claim(false,""));
        database.sql("UPDATE outbox_messages SET updated_at=now()-interval '3 minutes' WHERE status='UNKNOWN'").update();
        recover();
        assertEquals("NEEDS_REVIEW",state(confirmed));
        observe(job,new DeliveryResult("DELIVERED",SID,null));
        observe(job,new DeliveryResult("SUBMITTED",SID,null));
        assertEquals("DELIVERED",state(confirmed));
        assertEquals(ready,begin().messageId());
        assertEquals(2,attempts());
    }

    @Test
    void boundedRateLimitRetriesAndOneScopedLiveOrderGrant() {
        order(1,"CONFIRMED");
        order(2,"CONFIRMED");
        var first = message(1,"CONFIRMED");
        var second = message(2,"CONFIRMED");
        assertNull(outbox.claim(true,NUMBER));
        database.sql("INSERT INTO notification_live_grant (run_id,order_id,approval_ref) VALUES (:run,1,'case-C09')")
            .param("run",run).update();
        assertNull(outbox.claim(true,"+12025550101"));
        for (int i=0; i<5; i++) {
            var claim = outbox.claim(true,NUMBER);
            assertEquals(first,claim.messageId());
            var job = transaction.execute(s -> outbox.begin(claim,true,NUMBER));
            assertTrue(outbox.stillCurrent(job,true));
            observe(job,new DeliveryResult("FAILED_RETRYABLE",null,"RATE_LIMITED"));
            assertEquals(i < 4 ? "FAILED_RETRYABLE" : "FAILED",state(first));
            database.sql("UPDATE outbox_messages SET next_attempt_at=now() WHERE id=:id").param("id",first).update();
        }
        assertNull(outbox.claim(true,NUMBER));
        assertEquals("QUEUED",state(second));
        assertEquals("RETRY_EXHAUSTED",database.sql("SELECT last_error FROM outbox_messages WHERE id=:id")
            .param("id",first).query(String.class).single());
        assertEquals(5,attempts());
    }

    @Test
    void stateChangeSuppressesStaleMessageAndOldRunCallbacks() {
        order(1,"CANCELLED");
        var ready = message(1,"READY");
        var claimed = outbox.claim(false,"");
        assertNull(transaction.execute(s -> outbox.begin(claimed,false,"")));
        assertEquals("SUPPRESSED",state(ready));
        assertEquals(0,attempts());
        order(2,"CONFIRMED");
        var confirmed = message(2,"CONFIRMED");
        var job = begin();
        database.sql("UPDATE shop_state SET run_id=gen_random_uuid()").update();
        assertFalse(outbox.stillCurrent(job,false));
        observe(job,new DeliveryResult("DELIVERED",SID,null));
        assertEquals("SENDING",state(confirmed));
        assertNull(outbox.claim(false,""));
    }

    @Test
    void o02ReplayTwiceOnlyRequeuesAnEligibleNoticeAndRechecksProviderEvidence() {
        order(1,"CONFIRMED");
        UUID retry = message(1,"CONFIRMED");
        observe(begin(), new DeliveryResult("FAILED_RETRYABLE", null, "RATE_LIMITED"));
        database.sql("UPDATE outbox_messages SET next_attempt_at=now()+interval '15 minutes' WHERE id=:id")
            .param("id", retry).update();
        order(2,"CONFIRMED");
        UUID review = message(2,"CONFIRMED");
        var firstReview = begin();
        observe(firstReview, DeliveryResult.unknown("TWILIO_HTTP_503"));
        ageUnknown();
        order(3,"CONFIRMED");
        UUID permanent = message(3,"CONFIRMED");
        observe(begin(), new DeliveryResult("FAILED", null, "TWILIO_HTTP_400"));
        order(4,"CONFIRMED");
        UUID withEvidence = message(4,"CONFIRMED");
        var confirmed = begin();
        observe(confirmed, new DeliveryResult("SUBMITTED", SID, null));
        database.sql("UPDATE outbox_messages SET status='NEEDS_REVIEW' WHERE id=:id").param("id", withEvidence).update();
        order(5,"CONFIRMED");
        UUID granted = message(5,"CONFIRMED");
        database.sql("INSERT INTO notification_live_grant(run_id,order_id,approval_ref) VALUES (:run,5,'isolated-fixture')")
            .param("run", run).update();
        database.sql("UPDATE outbox_messages SET status='NEEDS_REVIEW' WHERE id=:id").param("id", granted).update();

        assertEquals("REVIEW_REQUIRED", replay(review, true, false).code());
        assertEquals(202, replay(retry, false, true).status());
        assertEquals(202, replay(review, true, true).status());
        assertEquals(409, replay(retry, false, true).status());
        assertEquals(409, replay(review, true, true).status());
        assertEquals(409, replay(permanent, false, true).status());
        assertEquals("PROVIDER_EVIDENCE_FOUND", replay(withEvidence, true, true).code());
        assertEquals(409, replay(withEvidence, true, true).status());
        assertEquals(404, replay(granted, true, true).status());
        assertEquals("QUEUED", state(retry));
        assertEquals("QUEUED", state(review));
        assertEquals("FAILED", state(permanent));
        assertEquals("SUBMITTED", state(withEvidence));
        assertEquals("NEEDS_REVIEW", state(granted));
        assertEquals(4, attempts());
    }

    @Test
    void c09OlderCallbackSuppressesOnlyTheNewAttemptBeforeDispatch() {
        for (int number = 1; number <= 2; number++) {
            order(number,"CONFIRMED");
            UUID id = message(number,"CONFIRMED");
            var initial = begin();
            observe(initial, DeliveryResult.unknown("TWILIO_RESPONSE_UNCERTAIN"));
            ageUnknown();
            assertEquals("NEEDS_REVIEW", state(id));
            assertEquals(202, replay(id, true, true).status());
            var retry = begin();
            assertEquals(id, retry.messageId());
            assertNotEquals(initial.attemptId(), retry.attemptId());
            String latest = number == 1 ? "SUBMITTED" : "DELIVERED";
            var sid = "SM" + "0".repeat(31) + number;
            observe(initial, new DeliveryResult(latest, sid, null));
            assertFalse(outbox.stillCurrent(retry, false));
            observe(retry, new DeliveryResult("SUPPRESSED", null, "ORDER_STATE_CHANGED"));
            assertEquals(latest, state(id));
            assertEquals("SUPPRESSED", database.sql("SELECT status FROM notification_attempts WHERE id=:id")
                .param("id", retry.attemptId()).query(String.class).single());
            assertEquals(409, replay(id, true, true).status());
        }
        assertEquals(4, attempts());
    }

    private OutboxRepository.Replay replay(UUID id, boolean resend, boolean reviewed) {
        return transaction.execute(s -> outbox.requestReplay(id, resend, reviewed));
    }

    private void ageUnknown() {
        database.sql("UPDATE outbox_messages SET updated_at=now()-interval '3 minutes' WHERE status='UNKNOWN'").update();
        recover();
    }

    private OutboxRepository.Dispatch begin() {
        var claim = outbox.claim(false,"");
        return transaction.execute(s -> outbox.begin(claim,false,""));
    }

    private void recover() {
        transaction.executeWithoutResult(s -> outbox.recoverLeases());
    }

    private void observe(OutboxRepository.Dispatch job, DeliveryResult result) {
        transaction.executeWithoutResult(s -> outbox.observe(job.attemptId(),result));
    }

    private String state(UUID id) {
        return database.sql("SELECT status FROM outbox_messages WHERE id=:id").param("id",id).query(String.class).single();
    }

    private int attempts() {
        return database.sql("SELECT count(*) FROM notification_attempts").query(Integer.class).single();
    }

    private UUID message(long order,String kind) {
        UUID id=UUID.randomUUID();
        database.sql("""
            INSERT INTO outbox_messages (id,order_id,run_id,kind,status,recipient,body)
            VALUES (:id,:order,:run,:kind,'QUEUED',:phone,'Demo fixture')
            """).param("id",id).param("order",order).param("run",run).param("kind",kind).param("phone",NUMBER).update();
        return id;
    }

    private void order(long id,String state) {
        UUID quote=UUID.randomUUID();
        String session="delivery-it-"+id;
        database.sql("INSERT INTO booking_sessions (id,run_id,phone) VALUES (:id,:run,:phone)")
            .param("id",session).param("run",run).param("phone",NUMBER).update();
        database.sql("""
            INSERT INTO quotes (id,run_id,session_id,customer_name,phone,sku,item_name,grams,price_paise,
                pickup_at,batch_id,requires_approval,expires_at,policy)
            VALUES (:id,:run,:session,'Test',:phone,'test','Test',500,50000,
                '2026-11-25T18:30:00Z','test',false,now()+interval '1 hour','{}')
            """).param("id",quote).param("run",run).param("session",session).param("phone",NUMBER).update();
        database.sql("""
            INSERT INTO orders (id,run_id,accepted_quote_id,accepted_turn_id,customer_name,phone,sku,item_name,
                grams,price_paise,pickup_at,batch_id,status,policy,created_at)
            VALUES (:id,:run,:quote,'yes','Test',:phone,'test','Test',500,50000,
                '2026-11-25T18:30:00Z','test',:status,'{}',now())
            """).param("id",id).param("run",run).param("quote",quote).param("phone",NUMBER).param("status",state).update();
    }
}
