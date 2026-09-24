package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Local HTTP review interface. Read views never mutate; every ruling appends an event. */
public final class Server {

    private final Store store;
    private final Path home;
    private final Path caseDir;
    private Javalin app;

    public Server(Store store, Path home, Path caseDir) {
        this.store = store;
        this.home = home;
        this.caseDir = caseDir;
    }

    /** Seed the bundled narrative up through S8 so the first page already shows real domain data. */
    public void seedIfEmpty() throws Exception {
        if (store.listBranches().isEmpty()) {
            new Replay(store, caseDir).full();
        }
    }

    public void start(String host, int port) {
        app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/web");
            cfg.http.defaultContentType = "application/json";
        });
        app.get("/api/state", this::state);
        app.get("/api/branches", this::branches);
        app.post("/api/branches/{id}/fork", this::fork);
        app.get("/api/events", this::events);
        app.get("/api/sources", this::sources);
        app.get("/api/derived", this::derived);
        app.get("/api/decisions", this::decisions);
        app.get("/api/candidates/{eventId}", this::candidates);
        app.post("/api/decide", this::decide);
        app.post("/api/undo", this::undo);
        app.post("/api/replay/step/{step}", this::replayStep);
        app.get("/api/export", this::exportBundle);
        app.post("/api/import", this::importBundle);
        app.post("/api/rebuild", this::rebuildFromBundle);
        app.exception(Store.ConflictException.class, (e, ctx) -> {
            ctx.status(409);
            ObjectNode body = Store.OM.createObjectNode();
            body.put("error", e.getMessage());
            body.put("serverRevision", e.serverRevision);
            body.set("diff", e.diff);
            ctx.json(body);
        });
        app.exception(Exception.class, (e, ctx) -> {
            store.rollback();
            ctx.status(400);
            ObjectNode err = Store.OM.createObjectNode();
            err.put("error", e.toString());
            ctx.json(err);
        });
        app.start(host, port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private String branch(Context ctx) {
        return ctx.queryParamAsClass("branch", String.class).getOrDefault("br-main");
    }

    private void state(Context ctx) throws Exception {
        String b = branch(ctx);
        ObjectNode out = Store.OM.createObjectNode();
        out.set("branch", store.branchRow(b));
        out.set("events", Store.OM.valueToTree(store.events(b)));
        out.set("derived", Store.OM.valueToTree(StoreEvents.activeDerived(store)));
        out.set("decisions", Store.OM.valueToTree(StoreEvents.decisions(store, b)));
        out.set("branches", Store.OM.valueToTree(store.listBranches()));
        out.put("ruleVersion", StoreEvents.RULE_VERSION);
        ctx.json(out);
    }

    private void branches(Context ctx) throws Exception {
        ctx.json(Store.OM.valueToTree(store.listBranches()));
    }

    private void fork(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        String name = ctx.queryParamAsClass("name", String.class).getOrDefault(id);
        int atSeq = ctx.queryParamAsClass("atSeq", Integer.class)
                .getOrDefault(store.branchRow(branch(ctx)).get("revision").asInt());
        String newId = store.fork(branch(ctx), name, atSeq);
        store.commit();
        ctx.json(Store.OM.readTree(Store.OM.writeValueAsString(Map.of("branchId", newId))));
    }

    private void events(Context ctx) throws Exception {
        ctx.json(Store.OM.readTree(Store.OM.writeValueAsString(store.events(branch(ctx)))));
    }

    private void sources(Context ctx) throws Exception {
        ctx.json(Store.OM.readTree(Store.OM.writeValueAsString(store.listSources())));
    }

    private void derived(Context ctx) throws Exception {
        ctx.json(Store.OM.valueToTree(StoreEvents.activeDerived(store)));
    }

    private void decisions(Context ctx) throws Exception {
        ctx.json(Store.OM.readTree(Store.OM.writeValueAsString(StoreEvents.decisions(store, branch(ctx)))));
    }

    private void candidates(Context ctx) throws Exception {
        String eventId = ctx.pathParam("eventId");
        for (var d : StoreEvents.activeDerived(store)) {
            if (eventId.equals(d.get("eventId").asText())) {
                // attach decomposition candidates for any physical matrix view
                JsonNode m = d.path("value").path("mueller").path("m");
                ObjectNode out = Store.OM.createObjectNode();
                out.set("estimate", d);
                if (m.isArray()) {
                    double[][] mm = Store.OM.treeToValue(m, double[][].class);
                    out.set("decompositions", Store.OM.valueToTree(Decomposition.all(mm)));
                } else {
                    out.putArray("decompositions");
                }
                ctx.json(out);
                return;
            }
        }
        ctx.status(404).result("{\"error\":\"unknown event\"}");
    }

    private void decide(Context ctx) throws Exception {
        JsonNode body = Store.OM.readTree(ctx.body());
        String b = body.path("branch").asText("br-main");
        int rev = body.path("baseRevision").asInt(-1);
        String id = StoreEvents.decide(store, b, body.path("verdict").asText(),
                body.path("targetKind").asText(), body.path("targetId").asText(),
                body.path("rationale").asText(""), rev);
        store.commit();
        ctx.result("{\"decisionId\":\"" + id + "\",\"revision\":" + store.branchRow(b).get("revision").asInt() + "}");
    }

    private void undo(Context ctx) throws Exception {
        JsonNode body = Store.OM.readTree(ctx.body());
        String b = body.path("branch").asText("br-main");
        int rev = body.path("baseRevision").asInt(-1);
        var ev = StoreEvents.undo(store, b, rev);
        store.commit();
        ctx.result("{\"eventId\":\"" + ev.eventId() + "\"}");
    }

    private void replayStep(Context ctx) throws Exception {
        String step = ctx.pathParam("step");
        Replay.StepResult result = new Replay(store, caseDir).step(step);
        store.commit();
        ctx.json(result);
    }

    private void exportBundle(Context ctx) throws Exception {
        ObjectNode bundle = Snapshot.exportBundle(store);
        Files.writeString(home.resolve("export-bundle.json"), Store.OM.writeValueAsString(bundle));
        ctx.contentType("application/json");
        ctx.result(Store.OM.writeValueAsString(bundle));
    }

    private void importBundle(Context ctx) throws Exception {
        JsonNode root = Store.OM.readTree(ctx.body());
        Snapshot.importBundle(store, (ObjectNode) root, home.resolve("blobs"));
        ctx.result("{\"imported\":true}");
    }

    /** Delete the local database (and blob store) then re-import the posted bundle. */
    private void rebuildFromBundle(Context ctx) throws Exception {
        JsonNode root = Store.OM.readTree(ctx.body());
        store.close();
        Files.deleteIfExists(home.resolve("polarlab.db"));
        try (var walk = Files.walk(home.resolve("blobs"))) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .filter(p -> !p.equals(home.resolve("blobs")))
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        }
        Db fresh = Db.open(home.toFile());
        Store rebuilt = new Store(fresh, home.resolve("blobs"));
        try {
            Snapshot.importBundle(rebuilt, (ObjectNode) root, home.resolve("blobs"));
        } catch (Exception e) {
            rebuilt.rollback();
            throw e;
        }
        ctx.result("{\"rebuilt\":true}");
    }
}
