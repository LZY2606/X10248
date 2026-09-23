package pol.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import pol.service.ArchiveService;
import pol.service.EstimationService;
import pol.service.ImportService;
import pol.service.Json;
import pol.service.Room;
import pol.service.State;
import pol.store.Database;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Javalin HTTP API and static page. */
public final class Api {

    private final Room room;
    private final ImportService importer;
    private final EstimationService estimator;
    private final ArchiveService archive;

    public Api(Room room) {
        this.room = room;
        this.importer = new ImportService(room);
        this.estimator = new EstimationService(room);
        this.archive = new ArchiveService(room);
    }

    @SuppressWarnings("unchecked")
    public Javalin configure(Javalin app) {
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.get("/api/branches", ctx -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Database.BranchRow b : room.branches()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", b.name());
                m.put("headSeq", b.headSeq());
                m.put("headEventId", b.headEventId());
                m.put("parentBranch", b.parentBranch());
                m.put("createdAt", b.createdAt());
                m.put("description", b.description());
                out.add(m);
            }
            ctx.json(Map.of("branches", out));
        });

        app.get("/api/state", ctx -> {
            String branch = ctx.queryParamAsClass("branch", String.class).get();
            int seq = ctx.queryParam("seq") == null ? -1
                    : Integer.parseInt(ctx.queryParam("seq"));
            ctx.json(room.stateAt(branch, seq));
        });

        app.post("/api/branches", ctx -> {
            Map<String, Object> body = body(ctx);
            String from = (String) body.get("fromBranch");
            String name = (String) body.get("name");
            String actor = actor(body);
            String description = (String) body.getOrDefault("description", "");
            if (from != null && !from.isBlank()) {
                room.forkBranch(from, name, actor, description);
            } else {
                room.ensureBranch(name, actor, description);
            }
            ctx.json(Map.of("branch", name));
        });

        app.post("/api/move-head", ctx -> {
            Map<String, Object> body = body(ctx);
            String branch = (String) body.get("branch");
            int target = ((Number) body.get("seq")).intValue();
            room.moveHead(branch, target, actor(body), (String) body.getOrDefault("note", ""));
            ctx.json(room.state(branch));
        });

        app.post("/api/import", ctx -> {
            Map<String, Object> body = body(ctx);
            String branch = (String) body.get("branch");
            int expected = intOr(body, "expectedHead", -1);
            String materialId = (String) body.get("materialId");
            String content = (String) body.getOrDefault("content", "");
            String sourceKind = (String) body.get("sourceKind");
            Map<String, String> attributes = new LinkedHashMap<>();
            Object a = body.get("attributes");
            if (a instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> en : map.entrySet()) {
                    attributes.put(String.valueOf(en.getKey()), String.valueOf(en.getValue()));
                }
            }
            ImportService.Imported imported = importer.importSource(branch, sourceKind,
                    (String) body.get("label"), materialId,
                    (String) body.getOrDefault("mediaType", "text/csv"),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    String.valueOf(body.getOrDefault("version", "1")),
                    (String) body.get("revisionOf"),
                    (String) body.get("directSourceId"),
                    attributes, expected, actor(body));
            ctx.json(Map.of("blobId", imported.blobId, "eventId", imported.eventId,
                    "diagnostics", imported.diagnostics));
        });

        app.post("/api/estimate", ctx -> {
            Map<String, Object> body = body(ctx);
            String branch = (String) body.get("branch");
            int expected = intOr(body, "expectedHead", -1);
            Map<String, Object> result = estimator.estimate(branch,
                    (String) body.get("materialId"),
                    (String) body.getOrDefault("label", "Mueller estimate"),
                    expected, actor(body));
            pol.store.Event ev = (pol.store.Event) result.get("event");
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("eventId", ev.id);
            resp.put("seq", ev.seq);
            resp.put("payload", result.get("payload"));
            resp.put("state", room.state(branch));
            ctx.json(resp);
        });

        app.post("/api/decision", ctx -> {
            Map<String, Object> body = body(ctx);
            String branch = (String) body.get("branch");
            int expected = intOr(body, "expectedHead", -1);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("estimateId", body.get("estimateId"));
            payload.put("decision", body.get("decision"));
            payload.put("note", body.getOrDefault("note", ""));
            payload.put("revert", Boolean.TRUE.equals(body.get("revert")));
            var ev = room.append(branch, "decision", payload,
                    List.of((String) body.get("estimateId")), actor(body), expected,
                    null, null);
            ctx.json(Map.of("eventId", ev.id, "state", room.state(branch)));
        });

        app.get("/api/archive", ctx -> {
            ctx.contentType("application/json");
            ctx.result(Json.pretty(archive.exportAll()));
        });

        app.post("/api/archive/import", ctx -> {
            Map<String, Object> body = Json.read(ctx.body(), Map.class);
            boolean replace = Boolean.TRUE.equals(body.get("replace"));
            @SuppressWarnings("unchecked")
            Map<String, Object> archiveData = (Map<String, Object>) body.get("archive");
            archive.importAll(archiveData, replace);
            ctx.json(Map.of("ok", true, "branches", room.branches().size()));
        });

        app.get("/api/blob", ctx -> {
            String id = ctx.queryParam("id");
            byte[] data = room.database().getBlob(id);
            if (data == null) {
                ctx.status(404).result("blob not found");
                return;
            }
            ctx.contentType("text/plain; charset=utf-8");
            ctx.result(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        });

        app.get("/api/invalidation", ctx -> {
            String branch = ctx.queryParam("branch");
            String changedSourceId = ctx.queryParam("changedSourceId");
            ctx.json(invalidation(branch, changedSourceId));
        });

        app.exception(Room.Conflict.class, (ex, ctx) -> {
            ctx.status(409);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "version-conflict");
            m.put("message", ex.getMessage());
            m.put("expectedHead", ex.expected);
            m.put("actualHead", ex.actual);
            m.put("divergence", ex.divergence);
            ctx.json(m);
        });
        app.exception(IllegalArgumentException.class, (ex, ctx) -> {
            ctx.status(400).json(Map.of("error", "bad-request", "message", ex.getMessage()));
        });

        return app;
    }

    private Map<String, Object> invalidation(String branch, String changedSourceId) {
        State current = room.state(branch);
        State.Source changed = current.sources.get(changedSourceId);
        List<Map<String, Object>> affected = new ArrayList<>();
        List<Map<String, Object>> unaffected = new ArrayList<>();
        for (State.Estimate est : current.estimates.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("estimateId", est.id);
            m.put("materialId", est.materialId);
            m.put("label", est.label);
            m.put("seq", est.seq);
            boolean inScope = changed == null || changed.materialId == null
                    || changed.materialId.isBlank()
                    || changed.materialId.equals(est.materialId);
            (inScope ? affected : unaffected).add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changedSourceId", changedSourceId);
        out.put("changedKind", changed == null ? null : changed.kind);
        out.put("scope", changed == null ? "unknown"
                : (changed.materialId == null || changed.materialId.isBlank()
                ? "global-all-materials" : "material:" + changed.materialId));
        out.put("affectedEstimates", affected);
        out.put("unaffectedEstimates", unaffected);
        return out;
    }

    private static Map<String, Object> body(Context ctx) {
        return Json.read(ctx.body(), Map.class);
    }

    private static String actor(Map<String, Object> body) {
        return (String) body.getOrDefault("actor", "reviewer");
    }

    private static int intOr(Map<String, Object> body, String key, int dflt) {
        Object v = body.get(key);
        return v instanceof Number n ? n.intValue() : dflt;
    }
}
