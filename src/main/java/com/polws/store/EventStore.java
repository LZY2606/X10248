package com.polws.store;

import com.polws.db.Db;
import com.polws.domain.Hashing;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only event ledger with per-branch heads and hash-chain fingerprints.
 * Branch heads provide optimistic concurrency; a stale commit raises {@link ConflictException}
 * and is itself recorded as a rejected CONFLICT_DENIED event so both sides remain auditable.
 */
public final class EventStore {
    private final Db db;

    public EventStore(Db db) {
        this.db = db;
    }

    public static final class Event {
        public long seq;
        public String branch;
        public String at;
        public String type;
        public Map<String, Object> payload;
        public String prevHash;
        public String hash;
        public long branchSeq;
    }

    public List<String> branches() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT name FROM branch ORDER BY created_seq, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public long branchHead(String branch) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT head_seq FROM branch WHERE name = ?")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown branch: " + branch);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String branchHeadHash(String branch) {
        long head = branchHead(branch);
        if (head == 0) {
            return "GENESIS";
        }
        List<Event> es = events(branch);
        return es.get(es.size() - 1).hash;
    }

    public void createBranchIfAbsent(String name, String parent, Long forkPoint) {
        if (branches().contains(name)) {
            return;
        }
        // event seq is autoincrement global; allocate a synthetic marker via direct insert below
        long seq = nextGlobalSeq();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO branch(name, head_seq, created_seq, parent_branch, fork_point) VALUES (?, 0, ?, ?, ?)")) {
            ps.setString(1, name);
            ps.setLong(2, seq);
            if (parent == null) {
                ps.setNull(3, java.sql.Types.VARCHAR);
            } else {
                ps.setString(3, parent);
            }
            if (forkPoint == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, forkPoint);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long nextGlobalSeq() {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO event(branch, at, type, payload_json, prev_hash, hash) VALUES ('', ?, 'SEQ_SKIP', '{}', NULL, ?)");
             ) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, Hashing.sha256Hex("skip-" + Instant.now().toEpochMilli() + "-" + Math.random()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                return id;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Append an event to a branch.
     *
     * @param expectedHead caller's last seen branch head; conflict if it changed in the meantime
     */
    @SuppressWarnings("unchecked")
    public Event append(String branch, String type, Map<String, Object> payload, Long expectedHead) {
        if (!branches().contains(branch)) {
            createBranchIfAbsent(branch, null, null);
        }
        long head = branchHead(branch);
        if (expectedHead != null && expectedHead != head) {
            Map<String, Object> theirs = events(branch).get((int) head - 1).payload;
            Map<String, Object> denied = new LinkedHashMap<>(payload);
            denied.put("conflict", true);
            denied.put("expectedHead", expectedHead);
            denied.put("actualHead", head);
            // record the rejected save on the branch history (does not mutate original data)
            appendInternal(branch, "CONFLICT_DENIED", denied);
            throw new ConflictException(branch, expectedHead, head, payload, theirs, denied);
        }
        return appendInternal(branch, type, payload);
    }

    private Event appendInternal(String branch, String type, Map<String, Object> payload) {
        long branchSeq = branchHead(branch) + 1;
        String prev = branchHeadHash(branch);
        String at = Instant.now().toString();
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("branch", branch);
        chain.put("branchSeq", branchSeq);
        chain.put("type", type);
        chain.put("at", at);
        chain.put("prevHash", prev);
        chain.put("payload", payload);
        String hash = Hashing.shortFingerprint(chain);

        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO event(branch, at, type, payload_json, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, branch);
            ps.setString(2, at);
            ps.setString(3, type);
            ps.setString(4, J.write(payload));
            ps.setString(5, prev);
            ps.setString(6, hash);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                long globalSeq = rs.getLong(1);
                try (PreparedStatement up = db.conn().prepareStatement(
                        "UPDATE branch SET head_seq = ? WHERE name = ?")) {
                    up.setLong(1, branchSeq);
                    up.setString(2, branch);
                    up.executeUpdate();
                }
                Event e = new Event();
                e.seq = globalSeq;
                e.branchSeq = branchSeq;
                e.branch = branch;
                e.at = at;
                e.type = type;
                e.payload = payload;
                e.prevHash = prev;
                e.hash = hash;
                return e;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Event> events(String branch) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT seq, at, type, payload_json, prev_hash, hash FROM event WHERE branch = ? AND type <> 'SEQ_SKIP' ORDER BY seq")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                long branchSeq = 0;
                while (rs.next()) {
                    Event e = new Event();
                    e.seq = rs.getLong(1);
                    e.at = rs.getString(2);
                    e.type = rs.getString(3);
                    e.payload = J.read(rs.getString(4), Map.class);
                    e.prevHash = rs.getString(5);
                    e.hash = rs.getString(6);
                    e.branchSeq = ++branchSeq;
                    out.add(e);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return out;
    }
}
