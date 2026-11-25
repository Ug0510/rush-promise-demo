package com.rushpromise.voice;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.booking.BookingModels.Quote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.rushpromise.voice.VoiceSessionService.*;

@Repository
@Profile("!worker")
class VoiceTurnJournal {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final int leaseSeconds;

    VoiceTurnJournal(JdbcTemplate db, ObjectMapper json, TransactionTemplate transactions,
                     @Value("${rush.voice.lease-seconds:15}") int leaseSeconds) {
        if (leaseSeconds < 1) throw new IllegalArgumentException("Voice lease must be at least one second");
        this.db = db;
        this.json = json;
        this.transactions = transactions;
        this.leaseSeconds = leaseSeconds;
    }

    Access claim(Turn input) {
        return transactions.execute(transaction -> {
            UUID runId = lockRun();
            Scope scope = new Scope(runId, input.sessionId());
            SessionRow row = open(scope, fingerprint(scope, input.contactBinding()));
            var prior = row.state();
            if (row.ended() || prior.order != null) return new Access(null, prior, row.ended());
            TurnRow accepted = pending(scope);
            if (accepted != null) return new Access(reclaim(scope, accepted, prior.nativePickup), prior, false);

            TurnRow existing = findTurn(scope, input.turnId());
            if (existing != null && existing.sequence() != input.sequence()) {
                throw new IllegalArgumentException("Turn identifier was already used");
            }
            if (input.sequence() < row.maxSequence() || (input.sequence() == row.maxSequence() && existing == null)) {
                return new Access(null, prior, false);
            }
            if (existing != null) {
                return new Access(reclaim(scope, existing, input.pickupSubject() != null), prior, false);
            }
            var token = UUID.randomUUID();
            db.update("""
                    INSERT INTO voice_turns (run_id,session_id,turn_id,sequence,status,claim_token,lease_until)
                    VALUES (?,?,?,?,'PROCESSING',?,clock_timestamp() + make_interval(secs => ?))
                    """, runId, scope.sessionId(), input.turnId(), input.sequence(), token, leaseSeconds);
            db.update("UPDATE voice_sessions SET max_sequence=?,touched_at=CURRENT_TIMESTAMP WHERE run_id=? AND session_id=?",
                    input.sequence(), runId, scope.sessionId());
            return new Access(new Ticket(scope, input.turnId(), input.sequence(), token, "PROCESSING", null, input.pickupSubject() != null), prior, false);
        });
    }

    Settlement completeSpeech(Ticket ticket, Function<Session, Decision> apply) {
        return transactions.execute(transaction -> {
            Scope scope = ticket.scope();
            if (!lockRun().equals(scope.runId())) return new Settlement(null, null, true);
            SessionRow row = session(scope, true);
            if (row == null) return new Settlement(null, null, true);
            TurnRow turn = allowedTicket(ticket);
            if (turn == null) return new Settlement(null, row.ended() ? null : row.state().last, row.ended());
            if (row.ended() || ticket.sequence() != row.maxSequence() || pending(scope) != null) {
                setTerminal(ticket, "DROPPED");
                return new Settlement(null, row.ended() ? null : row.state().last, row.ended());
            }
            row.state().nativePickup = ticket.nativePickup();
            Decision decision = apply.apply(row.state());
            persist(scope, row.state(), decision.reply(), decision.ended());
            if (decision.acceptedQuoteId() == null) {
                setTerminal(ticket, decision.failed() ? "FAILED" : "DONE");
                return new Settlement(null, decision.reply(), decision.ended());
            }
            db.update("""
                    UPDATE voice_turns SET status='ACCEPTED', accepted_quote_id=?,
                      lease_until=clock_timestamp() + make_interval(secs => ?), updated_at=CURRENT_TIMESTAMP
                    WHERE run_id=? AND session_id=? AND turn_id=? AND claim_token=?
                    """, decision.acceptedQuoteId(), leaseSeconds, scope.runId(), scope.sessionId(), ticket.turnId(), ticket.token());
            var accepted = new Ticket(scope, ticket.turnId(), ticket.sequence(), ticket.token(), "ACCEPTED", decision.acceptedQuoteId(), ticket.nativePickup());
            return new Settlement(accepted, decision.reply(), false);
        });
    }

    Settlement completeBooking(Ticket ticket, Function<Session, Decision> apply) {
        return transactions.execute(transaction -> {
            Scope scope = ticket.scope();
            if (!lockRun().equals(scope.runId())) return new Settlement(null, null, true);
            SessionRow row = session(scope, true);
            if (row == null) return new Settlement(null, null, true);
            TurnRow turn = allowedTicket(ticket);
            if (turn == null) return new Settlement(null, row.state().last, row.ended());
            Decision decision = apply.apply(row.state());
            persist(scope, row.state(), decision.reply(), row.ended());
            if (decision.acceptedQuoteId() != null) {
                db.update("""
                        UPDATE voice_turns SET status='UNCERTAIN', claim_token=NULL,
                          lease_until=clock_timestamp() + INTERVAL '2 seconds', updated_at=CURRENT_TIMESTAMP
                        WHERE run_id=? AND session_id=? AND turn_id=? AND claim_token=?
                        """, scope.runId(), scope.sessionId(), ticket.turnId(), ticket.token());
            } else {
                setTerminal(ticket, "DONE");
            }
            return new Settlement(null, decision.reply(), false);
        });
    }

    Access current(String sessionId, String turnId, String phone) {
        return transactions.execute(transaction -> {
            Scope scope = new Scope(lockRun(), sessionId);
            SessionRow row = session(scope, false);
            if (row == null) return new Access(null, new Session(), false);
            checkContact(row, fingerprint(scope, phone));
            if (turnId != null && findTurn(scope, turnId) == null) return new Access(null, new Session(), row.ended());
            return new Access(null, row.state(), row.ended());
        });
    }

    Session end(String sessionId) {
        return transactions.execute(transaction -> {
            Scope scope = new Scope(lockRun(), sessionId);
            SessionRow row = open(scope, null);
            db.update("UPDATE voice_sessions SET ended=TRUE,touched_at=CURRENT_TIMESTAMP WHERE run_id=? AND session_id=?",
                    scope.runId(), scope.sessionId());
            return row.state();
        });
    }

    List<Scope> due() {
        return db.query("""
                SELECT DISTINCT v.run_id,v.session_id FROM voice_turns v JOIN shop_state s ON s.run_id=v.run_id
                WHERE v.status IN ('ACCEPTED','UNCERTAIN') AND COALESCE(v.lease_until,'-infinity')<=clock_timestamp()
                ORDER BY v.run_id,v.session_id LIMIT 24
                """, (r, n) -> new Scope(r.getObject("run_id", UUID.class), r.getString("session_id")));
    }

    Ticket recover(Scope scope) {
        return transactions.execute(transaction -> {
            if (!lockRun().equals(scope.runId())) return null;
            var row = session(scope, true);
            if (row == null) return null;
            TurnRow accepted = pending(scope);
            return accepted == null ? null : reclaim(scope, accepted, row.state().nativePickup);
        });
    }

    void prune() {
        transactions.executeWithoutResult(transaction -> {
            UUID run = lockRun();
            db.update("""
                    DELETE FROM voice_sessions s WHERE s.run_id=? AND s.touched_at<CURRENT_TIMESTAMP-INTERVAL '25 minutes'
                      AND NOT EXISTS (SELECT 1 FROM guest_checkouts g WHERE 'guest:'||g.id::text=s.session_id AND g.run_id=s.run_id
                                     AND g.expires_at>CURRENT_TIMESTAMP)
                      AND NOT EXISTS (SELECT 1 FROM voice_turns t WHERE t.run_id=s.run_id AND t.session_id=s.session_id
                                     AND t.status IN ('ACCEPTED','UNCERTAIN'))
                    """, run);
            db.update("""
                    DELETE FROM guest_checkouts g WHERE g.expires_at<CURRENT_TIMESTAMP
                      AND NOT EXISTS (SELECT 1 FROM voice_sessions s WHERE 'guest:'||g.id::text=s.session_id AND g.run_id=s.run_id)
                    """);
        });
    }

    private Ticket reclaim(Scope scope, TurnRow row, boolean nativePickup) {
        UUID token = UUID.randomUUID();
        int matched = db.update("""
                UPDATE voice_turns SET claim_token=?,lease_until=clock_timestamp() + make_interval(secs => ?),updated_at=CURRENT_TIMESTAMP
                WHERE run_id=? AND session_id=? AND turn_id=? AND status IN ('PROCESSING','ACCEPTED','UNCERTAIN')
                  AND COALESCE(lease_until,'-infinity')<=clock_timestamp()
                """, token, leaseSeconds, scope.runId(), scope.sessionId(), row.turnId());
        return matched == 0 ? null : new Ticket(scope, row.turnId(), row.sequence(), token, row.status(), row.quoteId(), nativePickup);
    }

    private TurnRow allowedTicket(Ticket ticket) {
        Scope scope = ticket.scope();
        return first("""
                SELECT * FROM voice_turns WHERE run_id=? AND session_id=? AND turn_id=? AND claim_token=?
                    AND status=? AND lease_until>clock_timestamp() FOR UPDATE
                """, this::turnRow, scope.runId(), scope.sessionId(), ticket.turnId(), ticket.token(), ticket.status());
    }

    private void setTerminal(Ticket ticket, String status) {
        Scope scope = ticket.scope();
        db.update("""
                UPDATE voice_turns SET status=?, claim_token=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE run_id=? AND session_id=? AND turn_id=? AND claim_token=?
                """, status, scope.runId(), scope.sessionId(), ticket.turnId(), ticket.token());
    }

    private void persist(Scope scope, Session state, Reply response, boolean ended) {
        state.last = response == null ? null : new Reply(response.state(), "", response.replyText(), response.language(), response.quote(), response.order());
        db.update("UPDATE voice_sessions SET state=?::jsonb,ended=?,touched_at=CURRENT_TIMESTAMP WHERE run_id=? AND session_id=?",
                write(state), ended, scope.runId(), scope.sessionId());
    }

    private SessionRow open(Scope scope, String fingerprint) {
        SessionRow row = session(scope, true);
        if (row == null) {
            db.execute("SELECT pg_advisory_xact_lock(2384922)");
            row = session(scope, true);
            if (row == null) {
                Integer live = db.queryForObject("""
                        SELECT COUNT(*) FROM voice_sessions WHERE run_id=? AND touched_at>CURRENT_TIMESTAMP-INTERVAL '25 minutes'
                        """, Integer.class, scope.runId());
                if (live != null && live >= 600) throw new VoiceBusy();
                db.update("INSERT INTO voice_sessions (run_id,session_id,contact_fingerprint) VALUES (?,?,?)",
                        scope.runId(), scope.sessionId(), fingerprint);
                return new SessionRow(new Session(), 0, false, fingerprint);
            }
        }
        if (fingerprint != null) checkContact(row, fingerprint);
        return row;
    }

    private static void checkContact(SessionRow row, String fingerprint) {
        if (row.fingerprint() != null && !row.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException("Phone cannot change during an active order");
        }
    }

    private SessionRow session(Scope scope, boolean lock) {
        return first("SELECT * FROM voice_sessions WHERE run_id=? AND session_id=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new SessionRow(read(r.getString("state")), r.getInt("max_sequence"), r.getBoolean("ended"), r.getString("contact_fingerprint")),
                scope.runId(), scope.sessionId());
    }

    private TurnRow pending(Scope scope) {
        return first("SELECT * FROM voice_turns WHERE run_id=? AND session_id=? AND status IN ('ACCEPTED','UNCERTAIN')",
                this::turnRow, scope.runId(), scope.sessionId());
    }

    private TurnRow findTurn(Scope scope, String id) {
        return first("SELECT * FROM voice_turns WHERE run_id=? AND session_id=? AND turn_id=?", this::turnRow, scope.runId(), scope.sessionId(), id);
    }

    private TurnRow turnRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new TurnRow(r.getString("turn_id"), r.getInt("sequence"), r.getString("status"), r.getObject("accepted_quote_id", UUID.class));
    }

    private UUID lockRun() {
        // This is the same shop-run lock held by booking transactions and held exclusively by fixture resets.
        db.execute("SELECT pg_advisory_xact_lock_shared(2384921)");
        return db.queryForObject("SELECT run_id FROM shop_state WHERE id=1", UUID.class);
    }

    private <T> T first(String query, RowMapper<T> mapper, Object... args) {
        return db.query(query, mapper, args).stream().findFirst().orElse(null);
    }

    private Session read(String stored) {
        try { return json.readValue(stored, Session.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not read voice state", ex); }
    }

    private String write(Session state) {
        try { return json.writeValueAsString(state); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not store voice state", ex); }
    }

    private static String fingerprint(Scope scope, String phone) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((scope.runId() + "\n" + scope.sessionId() + "\n" + phone).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Scope(UUID runId, String sessionId) {}
    record Ticket(Scope scope, String turnId, int sequence, UUID token, String status, UUID quoteId, boolean nativePickup) {}
    record Access(Ticket ticket, Session state, boolean ended) {}
    record Settlement(Ticket ticket, Reply reply, boolean ended) {}
    record Decision(Reply reply, UUID acceptedQuoteId, boolean ended, boolean failed) {
        static Decision plain(Reply reply) { return new Decision(reply, null, false, false); }
    }
    private record SessionRow(Session state, int maxSequence, boolean ended, String fingerprint) {}
    private record TurnRow(String turnId, int sequence, String status, UUID quoteId) {}

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static final class Session {
        String sku;
        Integer grams;
        String unit;
        String pickupTime;
        Instant pickupAt;
        VoiceConversation.PickupChoices pickupChoices;
        Quote quote;
        NativeConfirmation nativeConfirmation;
        Set<String> usedNativeConfirmationCodes = new HashSet<>();
        SavedOrder order;
        Reply last;
        int silences;
        int unclearTurns;
        boolean hindi = true;
        boolean languageChosen;
        boolean nativePickup;
    }
    record NativeConfirmation(UUID quoteId, String code) {}
}
