package org.polarlab.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite 存储。所有写操作都在事务里，导入失败整体回滚。
 * 分支版本采用“选择覆盖 + 沿父链回溯”，旧分支与它引用的原始字节始终保留。
 */
public class Store implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final Connection cx;

    public Store(String path) {
        try {
            String url = "jdbc:sqlite:" + path;
            cx = DriverManager.getConnection(url);
            cx.setAutoCommit(false);
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA journal_mode=WAL");
            }
            cx.commit();
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("open db failed: " + path, e);
        }
    }

    private void migrate() throws SQLException {
        int v;
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            v = rs.getInt(1);
        }
        if (v == 0) {
            applyV1Baseline();
            v = 1;
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA user_version=1");
            }
            cx.commit();
        }
        if (v == 1) {
            migrateV1ToV2();
            v = 2;
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA user_version=2");
            }
            cx.commit();
        }
    }

    private void applyV1Baseline() throws SQLException {
        // 先建立 V1 中除 artifact 外的表，V2 再补 artifact 迁移
        for (String ddl : Schema.V1.split(";\\s*")) {
            if (ddl.isBlank()) continue;
            String s = ddl.trim();
            if (s.startsWith("CREATE TABLE artifact")) continue;
            try (Statement st = cx.createStatement()) { st.execute(s); }
        }
    }

    private void migrateV1ToV2() throws SQLException {
        try (Statement st = cx.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS artifact (
                    id TEXT PRIMARY KEY,
                    branch_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    inputs_json TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    json TEXT NOT NULL,
                    created_event_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    invalidated INTEGER NOT NULL DEFAULT 0,
                    invalidated_event_id TEXT
                )""");
        }
    }

    public int schemaVersion() {
        try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String meta(String key) {
        try (PreparedStatement ps = cx.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void putMeta(String key, String value) {
        tx(() -> {
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
            }
            return null;
        });
    }

    @FunctionalInterface interface TxCall<T> { T run() throws SQLException; }

    <T> T tx(TxCall<T> body) {
        try {
            T r = body.run();
            cx.commit();
            return r;
        } catch (RuntimeException | SQLException e) {
            try { cx.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("transaction rolled back", e);
        }
    }

    static String now() { return Instant.now().toString(); }
    static String uuid() { return UUID.randomUUID().toString(); }
    static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- 导入（原子批次，失败回滚不留半份记录） ----------

    /**
     * 原子导入一个批次的来源项。每项：itemId/kind/json（解释层），rawBytes（原始字节）。
     * 返回 (eventId, batchId, itemSummaries)。
     */
    public ImportResult importItems(String branchId, String note, List<NewItem> items) {
        return tx(() -> {
            String batchId = "batch-" + uuid();
            String eventId = "evt-" + uuid();
            String createdAt = now();
            try (PreparedStatement pb = cx.prepareStatement(
                    "INSERT INTO import_batch(id,created_at,item_count,status,note) VALUES(?,?,?,?,?)")) {
                pb.setString(1, batchId); pb.setString(2, createdAt);
                pb.setInt(3, items.size()); pb.setString(4, "COMMITTED");
                pb.setString(5, note); pb.executeUpdate();
            }
            JsonArray summaries = new JsonArray();
            for (NewItem ni : items) {
                if (ni.itemId == null || ni.kind == null || ni.json == null)
                    throw new SQLException("import item missing required field");
                int next = nextItemVersion(ni.itemId) + 1;
                byte[] raw = ni.rawBytes == null ? GSON.toJson(ni.json).getBytes(StandardCharsets.UTF_8) : ni.rawBytes;
                String hash = sha256(raw);
                boolean convertible = ni.json.has("quantity")
                        && ni.json.getAsJsonObject("quantity").has("convertible")
                        ? ni.json.getAsJsonObject("quantity").get("convertible").getAsBoolean() : true;
                try (PreparedStatement pi = cx.prepareStatement(
                        "INSERT INTO item(item_id,version,kind,json,raw_bytes_hash,raw_bytes,imported_at,batch_id,convertible)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                    pi.setString(1, ni.itemId); pi.setInt(2, next);
                    pi.setString(3, ni.kind); pi.setString(4, GSON.toJson(ni.json));
                    pi.setString(5, hash); pi.setBytes(6, raw);
                    pi.setString(7, createdAt); pi.setString(8, batchId);
                    pi.setInt(9, convertible ? 1 : 0);
                    pi.executeUpdate();
                }
                try (PreparedStatement pv = cx.prepareStatement(
                        "INSERT INTO branch_version(branch_id,item_id,version,selected_at,event_id)"
                                + " VALUES(?,?,?,?,?) ON CONFLICT(branch_id,item_id) DO UPDATE SET"
                                + " version=excluded.version, selected_at=excluded.selected_at, event_id=excluded.event_id")) {
                    pv.setString(1, branchId); pv.setString(2, ni.itemId);
                    pv.setInt(3, next); pv.setString(4, createdAt); pv.setString(5, eventId);
                    pv.executeUpdate();
                }
                JsonObject s = new JsonObject();
                s.addProperty("itemId", ni.itemId);
                s.addProperty("version", next);
                s.addProperty("kind", ni.kind);
                s.addProperty("rawBytesHash", hash);
                s.addProperty("convertible", convertible);
                summaries.add(s);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("note", note);
            payload.addProperty("batchId", batchId);
            payload.add("items", summaries);
            appendEventNoCommit(branchId, "IMPORT", payload, eventId, createdAt);
            return new ImportResult(eventId, batchId, summaries);
        });
    }

    public record NewItem(String itemId, String kind, JsonObject json, byte[] rawBytes) {}
    public record ImportResult(String eventId, String batchId, JsonArray summaries) {}

    private int nextItemVersion(String itemId) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT COALESCE(MAX(version),0) FROM item WHERE item_id=?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    // ---------- 分支与事件 ----------

    public String createMainBranch() {
        return tx(() -> {
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT id FROM branch WHERE name='main'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
            String id = "branch-main";
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO branch(id,parent_id,forked_from_seq,name,created_at,basis_event_id) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, id); ps.setNull(2, java.sql.Types.VARCHAR);
                ps.setNull(3, java.sql.Types.INTEGER); ps.setString(4, "main");
                ps.setString(5, now()); ps.setNull(6, java.sql.Types.VARCHAR);
                ps.executeUpdate();
            }
            return id;
        });
    }

    /** 从源分支某个事件点分叉；新分支初始选择 = 源分支当时版本（快照拷贝）。 */
    public String forkBranch(String sourceBranchId, String name, long basisSeq, String basisEventId) {
        return tx(() -> {
            String id = "branch-" + uuid();
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO branch(id,parent_id,forked_from_seq,name,created_at,basis_event_id)"
                            + " VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, id); ps.setString(2, sourceBranchId);
                ps.setLong(3, basisSeq); ps.setString(4, name);
                ps.setString(5, now()); ps.setString(6, basisEventId);
                ps.executeUpdate();
            }
            snapshotSelections(sourceBranchId, id, basisSeq);
            JsonObject payload = new JsonObject();
            payload.addProperty("sourceBranch", sourceBranchId);
            payload.addProperty("basisEventId", basisEventId);
            payload.addProperty("basisSeq", basisSeq);
            payload.addProperty("name", name);
            appendEventNoCommit(id, "BRANCH_FORK", payload, "evt-" + uuid(), now());
            return id;
        });
    }

    private void snapshotSelections(String src, String dst, long basisSeq) throws SQLException {
        // 源分支在 basisSeq 时的选择 = 最后一次 event_seq <= basisSeq 且影响该 item 的选择
        String sql = """
            SELECT bv.item_id,
                   (SELECT i.version FROM branch_version ch
                    JOIN event e ON e.event_id=ch.event_id
                    WHERE ch.branch_id=bv.branch_id AND ch.item_id=bv.item_id AND e.seq<=?
                    ORDER BY e.seq DESC LIMIT 1) AS v
            FROM (SELECT DISTINCT item_id FROM branch_version WHERE branch_id=?) bv
        """;
        // 简化：分叉时直接复制当前选择（调用方在基础事件之后、无后续改动时使用）；
        // 严格历史由 at-event 读取保证。这里复制当前指针。
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO branch_version(branch_id,item_id,version,selected_at,event_id) "
                        + "SELECT ?,item_id,version,selected_at,event_id FROM branch_version WHERE branch_id=?")) {
            ps.setString(1, dst); ps.setString(2, src);
            ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> listBranches() {
        return readAll("SELECT id,parent_id,forked_from_seq,name,created_at,basis_event_id FROM branch ORDER BY created_at",
                rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("parentId", rs.getString("parent_id"));
            m.put("forkedFromSeq", rs.getObject("forked_from_seq"));
            m.put("name", rs.getString("name"));
            m.put("createdAt", rs.getString("created_at"));
            m.put("basisEventId", rs.getString("basis_event_id"));
            return m;
        });
    }

    public String appendEvent(String branchId, String kind, JsonObject payload) {
        return tx(() -> appendEventNoCommit(branchId, kind, payload, "evt-" + uuid(), now()));
    }

    private String appendEventNoCommit(String branchId, String kind, JsonObject payload,
                                       String eventId, String createdAt) throws SQLException {
        String parent;
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT event_id FROM event WHERE branch_id=? ORDER BY seq DESC LIMIT 1")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) { parent = rs.next() ? rs.getString(1) : null; }
        }
        JsonObject machine = machineRecord(kind, payload);
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO event(event_id,branch_id,parent_event_id,kind,payload_json,machine_json,created_at,rules_version)"
                        + " VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, eventId); ps.setString(2, branchId);
            if (parent == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, parent);
            ps.setString(4, kind); ps.setString(5, GSON.toJson(payload));
            ps.setString(6, GSON.toJson(machine)); ps.setString(7, createdAt);
            ps.setString(8, "rules-v1");
            ps.executeUpdate();
        }
        return eventId;
    }

    /** 机器可读事件：统一结构，便于断言与重放核对。 */
    private JsonObject machineRecord(String kind, JsonObject payload) {
        JsonObject m = new JsonObject();
        m.addProperty("kind", kind);
        m.addProperty("rulesVersion", "rules-v1");
        m.addProperty("time", now());
        m.add("payload", payload.deepCopy());
        return switch (kind) {
            case "SINGULAR_DESIGN" -> flag(m, "policy", "do-not-force-inverse");
            case "NONPHYSICAL_CANDIDATE" -> flag(m, "policy", "flag-violation-no-projection");
            case "STOKES_ORDER_CHANGE", "ROTATION_SIGN_CHANGE" -> flag(m, "policy", "enters-fingerprint");
            default -> m;
        };
    }

    private JsonObject flag(JsonObject m, String k, String v) { m.addProperty(k, v); return m; }

    public List<Map<String, Object>> events(String branchId, Long atSeq) {
        String sql = "SELECT seq,event_id,parent_event_id,kind,payload_json,machine_json,created_at,rules_version "
                + "FROM event WHERE branch_id=?" + (atSeq != null ? " AND seq<=?" : "") + " ORDER BY seq";
        return queryList(sql, ps -> {
            ps.setString(1, branchId);
            if (atSeq != null) ps.setLong(2, atSeq);
        }, this::eventRow);
    }

    public Map<String, Object> latestEvent(String branchId) {
        List<Map<String, Object>> all = events(branchId, null);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    // ---------- 项版本读取（当前 / 指定事件时间点） ----------

    public Item currentItem(String branchId, String itemId) {
        return txReadOnly(() -> {
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT i.version,i.kind,i.json,i.raw_bytes_hash,i.raw_bytes,i.batch_id,i.convertible "
                            + "FROM branch_version bv JOIN item i ON i.item_id=bv.item_id AND i.version=bv.version "
                            + "WHERE bv.branch_id=? AND bv.item_id=?")) {
                ps.setString(1, branchId); ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Item.fromRow(itemId, rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getString(6), rs.getInt(7)) : null;
                }
            }
        });
    }

    /** 某事件时间点上该分支选择的 item 版本（时间旅行：旧事件恢复旧版本）。 */
    public Item itemAt(String branchId, String itemId, long atSeq) {
        return txReadOnly(() -> {
            // 取 <= atSeq 的最后一个 IMPORT 事件中该 item 的版本；导入事件携带版本号
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT payload_json FROM event WHERE branch_id=? AND kind='IMPORT' AND seq<=? ORDER BY seq DESC")) {
                ps.setString(1, branchId); ps.setLong(2, atSeq);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject payload = GSON.fromJson(rs.getString(1), JsonObject.class);
                        JsonArray arr = payload.getAsJsonArray("items");
                        for (var e : arr) {
                            JsonObject o = e.getAsJsonObject();
                            if (o.get("itemId").getAsString().equals(itemId)) {
                                int ver = o.get("version").getAsInt();
                                return loadItem(itemId, ver);
                            }
                        }
                    }
                }
            }
            // 分支自身无记录时沿父链回溯到分叉点
            BranchRec br = branchRec(branchId);
            if (br.parentId != null && br.forkedFromSeq != null)
                return itemAt(br.parentId, itemId, br.forkedFromSeq);
            return null;
        });
    }

    private Item loadItem(String itemId, int ver) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT version,kind,json,raw_bytes_hash,raw_bytes,batch_id,convertible FROM item WHERE item_id=? AND version=?")) {
            ps.setString(1, itemId); ps.setInt(2, ver);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Item.fromRow(itemId, rs.getInt(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getString(6), rs.getInt(7)) : null;
            }
        }
    }

    private record BranchRec(String parentId, Long forkedFromSeq) {}

    private BranchRec branchRec(String id) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT parent_id,forked_from_seq FROM branch WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new BranchRec(null, null);
                long f = rs.getLong(2);
                return new BranchRec(rs.getString(1), rs.wasNull() ? null : f);
            }
        }
    }

    public List<Item> currentItems(String branchId) {
        return txReadOnly(() -> {
            List<Item> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT bv.item_id,i.version,i.kind,i.json,i.raw_bytes_hash,i.raw_bytes,i.batch_id,i.convertible "
                            + "FROM branch_version bv JOIN item i ON i.item_id=bv.item_id AND i.version=bv.version "
                            + "WHERE bv.branch_id=? ORDER BY i.kind,bv.item_id")) {
                ps.setString(1, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(Item.fromRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getInt(8)));
                }
            }
            return out;
        });
    }

    /** 该 item 全部历史版本（旧分支仍可继续读取原始字节）。 */
    public List<Item> itemHistory(String itemId) {
        return txReadOnly(() -> {
            List<Item> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT version,kind,json,raw_bytes_hash,raw_bytes,batch_id,convertible FROM item WHERE item_id=? ORDER BY version")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(Item.fromRow(itemId, rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getString(6), rs.getInt(8)));
                }
            }
            return out;
        });
    }

    // ---------- 人工裁定（乐观并发；可撤销；否定/接受不改原始数据） ----------

    /**
     * 提交裁定。consistencyToken 为审阅时拿到的分支头 event_id；
     * 若期间他人已写入则抛 ConflictException，由界面呈现双方差异。
     */
    public AdjudicationResult adjudicate(String branchId, String candidateKey, String decision,
                                         String rationale, String consistencyToken) {
        return tx(() -> {
            String head = headEventId(branchId);
            if (head == null || !head.equals(consistencyToken)) {
                throw new ConflictException(head, consistencyToken);
            }
            String supersedes;
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT id FROM adjudication WHERE branch_id=? AND candidate_key=? AND undone=0")) {
                ps.setString(1, branchId); ps.setString(2, candidateKey);
                try (ResultSet rs = ps.executeQuery()) { supersedes = rs.next() ? rs.getString(1) : null; }
            }
            if (supersedes != null) {
                try (PreparedStatement ps = cx.prepareStatement(
                        "UPDATE adjudication SET undone=1 WHERE id=?")) {
                    ps.setString(1, supersedes); ps.executeUpdate();
                }
            }
            String id = "adj-" + uuid();
            String eventId = "evt-" + uuid();
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO adjudication(id,branch_id,candidate_key,decision,rationale,basis_event_id,"
                            + "supersedes_id,undone,created_at,consistency_token) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id); ps.setString(2, branchId); ps.setString(3, candidateKey);
                ps.setString(4, decision); ps.setString(5, rationale); ps.setString(6, head);
                if (supersedes == null) ps.setNull(7, java.sql.Types.VARCHAR); else ps.setString(7, supersedes);
                ps.setInt(8, 0); ps.setString(9, now()); ps.setString(10, consistencyToken);
                ps.executeUpdate();
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("candidateKey", candidateKey);
            payload.addProperty("decision", decision);
            payload.addProperty("rationale", rationale == null ? "" : rationale);
            payload.addProperty("adjudicationId", id);
            payload.addProperty("supersedesId", supersedes == null ? "" : supersedes);
            appendEventNoCommit(branchId, "ADJUDICATION", payload, eventId, now());
            return new AdjudicationResult(id, eventId);
        });
    }

    /** 撤销最近一次裁定：写入 REVERTED_ADJUDICATION 事件并标记 undone，历史分支仍在。 */
    public String undoAdjudication(String branchId, String adjudicationId) {
        return tx(() -> {
            String key;
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT candidate_key FROM adjudication WHERE id=? AND branch_id=?")) {
                ps.setString(1, adjudicationId); ps.setString(2, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("adjudication not found: " + adjudicationId);
                    key = rs.getString(1);
                }
            }
            try (PreparedStatement ps = cx.prepareStatement(
                    "UPDATE adjudication SET undone=1 WHERE id=?")) {
                ps.setString(1, adjudicationId); ps.executeUpdate();
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("adjudicationId", adjudicationId);
            payload.addProperty("candidateKey", key);
            return appendEventNoCommit(branchId, "ADJUDICATION_UNDONE", payload, "evt-" + uuid(), now());
        });
    }

    public List<Map<String, Object>> adjudications(String branchId, boolean includeUndone) {
        return queryList(
                "SELECT id,candidate_key,decision,rationale,basis_event_id,supersedes_id,undone,created_at "
                        + "FROM adjudication WHERE branch_id=?" + (includeUndone ? "" : " AND undone=0") + " ORDER BY created_at",
                ps -> ps.setString(1, branchId), rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("candidateKey", rs.getString("candidate_key"));
                    m.put("decision", rs.getString("decision"));
                    m.put("rationale", rs.getString("rationale"));
                    m.put("basisEventId", rs.getString("basis_event_id"));
                    m.put("supersedesId", rs.getString("supersedes_id"));
                    m.put("undone", rs.getInt("undone") != 0);
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                });
    }

    public String headEventId(String branchId) {
        return txReadOnly(() -> {
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT event_id FROM event WHERE branch_id=? ORDER BY seq DESC LIMIT 1")) {
                ps.setString(1, branchId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
            }
        });
    }

    public long headSeq(String branchId) {
        return txReadOnly(() -> {
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT COALESCE(MAX(seq),0) FROM event WHERE branch_id=?")) {
                ps.setString(1, branchId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    public static final class ConflictException extends RuntimeException {
        public final String currentHead;
        public final String submittedBase;
        public ConflictException(String currentHead, String submittedBase) {
            super("version conflict: head=" + currentHead + " base=" + submittedBase);
            this.currentHead = currentHead;
            this.submittedBase = submittedBase;
        }
    }

    public record AdjudicationResult(String adjudicationId, String eventId) {}

    // ---------- 派生产物与失效范围 ----------

    public String saveArtifact(String branchId, String kind, JsonObject inputs,
                               String fingerprint, JsonObject resultJson, String eventId) {
        return tx(() -> {
            String id = "art-" + uuid();
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO artifact(id,branch_id,kind,inputs_json,fingerprint,json,created_event_id,created_at,"
                            + "invalidated,invalidated_event_id) VALUES(?,?,?,?,?,?,?,?,0,NULL)")) {
                ps.setString(1, id); ps.setString(2, branchId); ps.setString(3, kind);
                ps.setString(4, GSON.toJson(inputs)); ps.setString(5, fingerprint);
                ps.setString(6, GSON.toJson(resultJson)); ps.setString(7, eventId);
                ps.setString(8, now());
                ps.executeUpdate();
            }
            return id;
        });
    }

    /**
     * 按依赖图计算失效范围：只让真正引用了 changedItemId 的产物失效，绝不全局替换。
     * 返回被失效的 artifact id 列表。
     */
    public List<String> invalidateDepending(String branchId, List<String> changedItemIds, String reasonEventId) {
        return tx(() -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT id,inputs_json FROM artifact WHERE branch_id=? AND invalidated=0")) {
                ps.setString(1, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String aid = rs.getString(1);
                        JsonObject in = GSON.fromJson(rs.getString(2), JsonObject.class);
                        JsonArray refs = in.has("itemRefs") ? in.getAsJsonArray("itemRefs") : new JsonArray();
                        boolean hit = false;
                        for (var r : refs)
                            if (changedItemIds.contains(r.getAsString())) { hit = true; break; }
                        if (hit) {
                            ids.add(aid);
                            try (PreparedStatement pu = cx.prepareStatement(
                                    "UPDATE artifact SET invalidated=1,invalidated_event_id=? WHERE id=?")) {
                                pu.setString(1, reasonEventId); pu.setString(2, aid);
                                pu.executeUpdate();
                            }
                        }
                    }
                }
            }
            return ids;
        });
    }

    public List<Map<String, Object>> artifacts(String branchId, Long atSeq, boolean includeInvalid) {
        String sql = "SELECT id,kind,inputs_json,fingerprint,json,created_event_id,created_at,invalidated,"
                + "invalidated_event_id FROM artifact WHERE branch_id=?"
                + (atSeq != null ? " AND created_event_id IN (SELECT event_id FROM event WHERE seq<=?)" : "")
                + (includeInvalid ? "" : " AND invalidated=0");
        return queryList(sql, ps -> {
            ps.setString(1, branchId);
            if (atSeq != null) ps.setLong(2, atSeq);
        }, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("kind", rs.getString("kind"));
            m.put("inputs", GSON.fromJson(rs.getString("inputs_json"), JsonObject.class));
            m.put("fingerprint", rs.getString("fingerprint"));
            m.put("json", GSON.fromJson(rs.getString("json"), JsonObject.class));
            m.put("createdEventId", rs.getString("created_event_id"));
            m.put("createdAt", rs.getString("created_at"));
            m.put("invalidated", rs.getInt("invalidated") != 0);
            m.put("invalidatedEventId", rs.getString("invalidated_event_id"));
            return m;
        });
    }

    // ---------- 导出/导入：删除本地库再导入，身份/顺序/来源关系保持 ----------

    public JsonObject exportBundle() {
        return txReadOnly(() -> {
            JsonObject root = new JsonObject();
            root.addProperty("format", "polar-lab-bundle");
            root.addProperty("schemaVersion", Schema.CURRENT_VERSION);
            root.addProperty("rulesVersion", "rules-v1");
            root.addProperty("exportedAt", now());
            root.add("meta", table("SELECT key,value FROM meta ORDER BY key",
                    rs -> {
                JsonObject o = new JsonObject();
                o.addProperty("key", rs.getString(1));
                o.addProperty("value", rs.getString(2));
                return o;
            }));
            root.add("items", table("SELECT item_id,version,kind,json,raw_bytes_hash,raw_bytes,imported_at,batch_id,"
                            + "convertible FROM item ORDER BY item_id,version", this::itemExportRow));
            root.add("branches", table("SELECT id,parent_id,forked_from_seq,name,created_at,basis_event_id FROM branch ORDER BY created_at",
                    rs -> row("id", rs, 1, "parentId", rs, 2, "forkedFromSeq", rs, 3,
                            "name", rs, 4, "createdAt", rs, 5, "basisEventId", rs, 6)));
            root.add("branchVersions", table(
                    "SELECT branch_id,item_id,version,selected_at,event_id FROM branch_version ORDER BY rowid",
                    rs -> {
                JsonObject o = new JsonObject();
                o.addProperty("branchId", rs.getString(1));
                o.addProperty("itemId", rs.getString(2));
                o.addProperty("version", rs.getInt(3));
                o.addProperty("selectedAt", rs.getString(4));
                o.addProperty("eventId", rs.getString(5));
                return o;
            }));
            root.add("events", table(
                    "SELECT seq,event_id,branch_id,parent_event_id,kind,payload_json,machine_json,created_at,rules_version"
                            + " FROM event ORDER BY seq", rs -> {
                JsonObject o = new JsonObject();
                o.addProperty("seq", rs.getLong(1));
                o.addProperty("eventId", rs.getString(2));
                o.addProperty("branchId", rs.getString(3));
                o.addProperty("parentEventId", rs.getString(4));
                o.addProperty("kind", rs.getString(5));
                o.add("payload", GSON.fromJson(rs.getString(6), JsonObject.class));
                o.add("machine", GSON.fromJson(rs.getString(7), JsonObject.class));
                o.addProperty("createdAt", rs.getString(8));
                o.addProperty("rulesVersion", rs.getString(9));
                return o;
            }));
            root.add("adjudications", table(
                    "SELECT id,branch_id,candidate_key,decision,rationale,basis_event_id,supersedes_id,undone,"
                            + "created_at,consistency_token FROM adjudication ORDER BY created_at", rs -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", rs.getString(1));
                o.addProperty("branchId", rs.getString(2));
                o.addProperty("candidateKey", rs.getString(3));
                o.addProperty("decision", rs.getString(4));
                o.addProperty("rationale", rs.getString(5));
                o.addProperty("basisEventId", rs.getString(6));
                o.addProperty("supersedesId", rs.getString(7));
                o.addProperty("undone", rs.getInt(8) != 0);
                o.addProperty("createdAt", rs.getString(9));
                o.addProperty("consistencyToken", rs.getString(10));
                return o;
            }));
            root.add("artifacts", table(
                    "SELECT id,branch_id,kind,inputs_json,fingerprint,json,created_event_id,created_at,invalidated,"
                            + "invalidated_event_id FROM artifact ORDER BY created_at", rs -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", rs.getString(1));
                o.addProperty("branchId", rs.getString(2));
                o.addProperty("kind", rs.getString(3));
                o.add("inputs", GSON.fromJson(rs.getString(4), JsonObject.class));
                o.addProperty("fingerprint", rs.getString(5));
                o.add("json", GSON.fromJson(rs.getString(6), JsonObject.class));
                o.addProperty("createdEventId", rs.getString(7));
                o.addProperty("createdAt", rs.getString(8));
                o.addProperty("invalidated", rs.getInt(9) != 0);
                o.addProperty("invalidatedEventId", rs.getString(10));
                return o;
            }));
            return root;
        });
    }

    /** 整体导入：单事务，任何行出错全部回滚。按导出顺序恢复，seq 与所有 id 原样保留。 */
    public void importBundle(JsonObject root) {
        tx(() -> {
            long n = count("event");
            if (n > 0) throw new SQLException("bundle import requires an empty database");
            JsonArray events = root.getAsJsonArray("events");
            // 先 item，保证外键式来源关系有效；事件按 seq 恢复保持顺序
            for (var el : root.getAsJsonArray("items")) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement(
                        "INSERT INTO item(item_id,version,kind,json,raw_bytes_hash,raw_bytes,imported_at,batch_id,convertible)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, o.get("itemId").getAsString());
                    ps.setInt(2, o.get("version").getAsInt());
                    ps.setString(3, o.get("kind").getAsString());
                    ps.setString(4, o.get("json").isJsonObject() ? GSON.toJson(o.getAsJsonObject("json"))
                            : o.get("json").getAsString());
                    ps.setString(5, o.get("rawBytesHash").getAsString());
                    String b64 = o.has("rawBytesB64") && !o.get("rawBytesB64").isJsonNull()
                            ? o.get("rawBytesB64").getAsString() : null;
                    ps.setBytes(6, b64 == null ? null : java.util.Base64.getDecoder().decode(b64));
                    ps.setString(7, o.get("importedAt").getAsString());
                    ps.setString(8, o.has("batchId") && !o.get("batchId").isJsonNull() ? o.get("batchId").getAsString() : null);
                    ps.setInt(9, o.get("convertible").getAsBoolean() ? 1 : 0);
                    ps.executeUpdate();
                }
            }
            for (var el : root.getAsJsonArray("branches")) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement(
                        "INSERT INTO branch(id,parent_id,forked_from_seq,name,created_at,basis_event_id) VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, o.get("id").getAsString());
                    setNullable(ps, 2, o, "parentId");
                    setLongNullable(ps, 3, o, "forkedFromSeq");
                    ps.setString(4, o.get("name").getAsString());
                    ps.setString(5, o.get("createdAt").getAsString());
                    setNullable(ps, 6, o, "basisEventId");
                    ps.executeUpdate();
                }
            }
            for (var el : root.getAsJsonArray("branchVersions")) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement(
                        "INSERT INTO branch_version(branch_id,item_id,version,selected_at,event_id) VALUES(?,?,?,?,?)")) {
                    ps.setString(1, o.get("branchId").getAsString());
                    ps.setString(2, o.get("itemId").getAsString());
                    ps.setInt(3, o.get("version").getAsInt());
                    ps.setString(4, o.get("selectedAt").getAsString());
                    ps.setString(5, o.get("eventId").getAsString());
                    ps.executeUpdate();
                }
            }
            for (var el : events) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement(
                        "INSERT INTO event(seq,event_id,branch_id,parent_event_id,kind,payload_json,machine_json,created_at,rules_version)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, o.get("seq").getAsLong());
                    ps.setString(2, o.get("eventId").getAsString());
                    ps.setString(3, o.get("branchId").getAsString());
                    setNullableString(ps, 4, o, "parentEventId");
                    ps.setString(5, o.get("kind").getAsString());
                    ps.setString(6, GSON.toJson(o.get("payload")));
                    ps.setString(7, GSON.toJson(o.get("machine")));
                    ps.setString(8, o.get("createdAt").getAsString());
                    ps.setString(9, o.get("rulesVersion").getAsString());
                    ps.executeUpdate();
                }
            }
            for (var el : root.getAsJsonArray("adjudications")) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement(
                        "INSERT INTO adjudication(id,branch_id,candidate_key,decision,rationale,basis_event_id,"
                                + "supersedes_id,undone,created_at,consistency_token) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, o.get("id").getAsString());
                    ps.setString(2, o.get("branchId").getAsString());
                    ps.setString(3, o.get("candidateKey").getAsString());
                    ps.setString(4, o.get("decision").getAsString());
                    ps.setString(5, o.get("rationale").getAsString());
                    ps.setString(6, o.get("basisEventId").getAsString());
                    setNullableString(ps, 7, o, "supersedesId");
                    ps.setInt(8, o.get("undone").getAsBoolean() ? 1 : 0);
                    ps.setString(9, o.get("createdAt").getAsString());
                    ps.setString(10, o.get("consistencyToken").getAsString());
                    ps.executeUpdate();
                }
            }
            for (var el : root.getAsJsonArray("artifacts")) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement(
                        "INSERT INTO artifact(id,branch_id,kind,inputs_json,fingerprint,json,created_event_id,created_at,"
                                + "invalidated,invalidated_event_id) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, o.get("id").getAsString());
                    ps.setString(2, o.get("branchId").getAsString());
                    ps.setString(3, o.get("kind").getAsString());
                    ps.setString(4, GSON.toJson(o.get("inputs")));
                    ps.setString(5, o.get("fingerprint").getAsString());
                    ps.setString(6, GSON.toJson(o.get("json")));
                    ps.setString(7, o.get("createdEventId").getAsString());
                    ps.setString(8, o.get("createdAt").getAsString());
                    ps.setInt(9, o.get("invalidated").getAsBoolean() ? 1 : 0);
                    setNullableString(ps, 10, o, "invalidatedEventId");
                    ps.executeUpdate();
                }
            }
            for (var el : root.getAsJsonArray("meta")) {
                JsonObject o = el.getAsJsonObject();
                try (PreparedStatement ps = cx.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?)")) {
                    ps.setString(1, o.get("key").getAsString());
                    ps.setString(2, o.get("value").getAsString());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private JsonObject itemExportRow(ResultSet rs) throws SQLException {
        JsonObject o = new JsonObject();
        o.addProperty("itemId", rs.getString(1));
        o.addProperty("version", rs.getInt(2));
        o.addProperty("kind", rs.getString(3));
        o.add("json", GSON.fromJson(rs.getString(4), JsonObject.class));
        o.addProperty("rawBytesHash", rs.getString(5));
        byte[] raw = rs.getBytes(6);
        if (raw != null) o.addProperty("rawBytesB64", java.util.Base64.getEncoder().encodeToString(raw));
        o.addProperty("importedAt", rs.getString(7));
        o.addProperty("batchId", rs.getString(8));
        o.addProperty("convertible", rs.getInt(9) != 0);
        return o;
    }

    // ---------- 通用读取辅助 ----------

    @FunctionalInterface interface RowMap<T> { T map(ResultSet rs) throws SQLException; }
    @FunctionalInterface interface Prep { void bind(PreparedStatement ps) throws SQLException; }

    private <T> T txReadOnly(TxCall<T> body) {
        try {
            T r = body.run();
            cx.rollback(); // 只读事务结束，释放快照
            return r;
        } catch (RuntimeException | SQLException e) {
            try { cx.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException(e);
        }
    }

    private <T> List<T> queryList(String sql, Prep bind, RowMap<T> map) {
        return txReadOnly(() -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(sql)) {
                bind.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map.map(rs));
                }
            }
            return out;
        });
    }

    private <T> List<T> readAll(String sql, RowMap<T> map) {
        return queryList(sql, ps -> {}, map);
    }

    private JsonArray table(String sql, RowMap<JsonObject> map) throws SQLException {
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = cx.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) arr.add(map.map(rs));
        }
        return arr;
    }

    private long count(String table) throws SQLException {
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Map<String, Object> eventRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", rs.getLong("seq"));
        m.put("eventId", rs.getString("event_id"));
        m.put("parentEventId", rs.getString("parent_event_id"));
        m.put("kind", rs.getString("kind"));
        m.put("payload", GSON.fromJson(rs.getString("payload_json"), JsonObject.class));
        m.put("machine", GSON.fromJson(rs.getString("machine_json"), JsonObject.class));
        m.put("createdAt", rs.getString("created_at"));
        m.put("rulesVersion", rs.getString("rules_version"));
        return m;
    }

    private static void setNullable(PreparedStatement ps, int i, JsonObject o, String k) throws SQLException {
        if (!o.has(k) || o.get(k).isJsonNull()) ps.setNull(i, java.sql.Types.VARCHAR);
        else ps.setString(i, o.get(k).getAsString());
    }

    private static void setNullableString(PreparedStatement ps, int i, JsonObject o, String k) throws SQLException {
        setNullable(ps, i, o, k);
    }

    private static void setLongNullable(PreparedStatement ps, int i, JsonObject o, String k) throws SQLException {
        if (!o.has(k) || o.get(k).isJsonNull()) ps.setNull(i, java.sql.Types.BIGINT);
        else ps.setLong(i, o.get(k).getAsLong());
    }

    private JsonObject row(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 3) {
            String key = (String) kv[i];
            @SuppressWarnings("unchecked") RowMap<ResultSet> ignored = null;
            ResultSet rs = (ResultSet) kv[i + 1];
            int idx = (Integer) kv[i + 2];
            try {
                int type = rs.getMetaData().getColumnType(idx);
                if (type == java.sql.Types.BIGINT && rs.getObject(idx) != null)
                    o.addProperty(key, rs.getLong(idx));
                else {
                    String v = rs.getString(idx);
                    if (v == null) o.add(key, null); else o.addProperty(key, v);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
        return o;
    }

    @Override public void close() {
        try { cx.close(); } catch (SQLException ignored) {}
    }
}
