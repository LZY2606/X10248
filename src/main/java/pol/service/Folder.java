package pol.service;

import pol.store.Database;
import pol.store.Event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds an ordered event prefix into a {@link State}. Pure with respect to the
 * given events (blob lookups aside), so replaying a historical prefix
 * reconstructs the exact historical view.
 */
public final class Folder {

    private final Database db;

    public Folder(Database db) {
        this.db = db;
    }

    @SuppressWarnings("unchecked")
    public State fold(String branch, int headSeq, List<Event> events) {
        State state = new State();
        state.branch = branch;
        state.headSeq = headSeq;

        Map<String, String> estimateToEvent = new LinkedHashMap<>();
        Map<String, Event> estimateEvents = new LinkedHashMap<>();

        for (Event e : events) {
            Map<String, Object> p = Json.read(e.payloadJson, Map.class);
            state.headEventId = e.id;
            addTimeline(state, e, p, false, null);

            if ("blob_registered".equals(e.type)) {
                State.Blob blob = new State.Blob();
                blob.id = str(p, "blobId");
                blob.sha256 = str(p, "sha256");
                blob.mediaType = str(p, "mediaType");
                blob.byteCount = p.get("byteCount") instanceof Number n ? n.longValue() : 0;
                blob.importedAt = e.createdAt;
                state.blobs.put(blob.id, blob);
            } else if ("source_imported".equals(e.type) || "source_revised".equals(e.type)) {
                applySource(state, e, p);
            } else if ("estimate_created".equals(e.type)) {
                State.Estimate est = toEstimate(e, p);
                state.estimates.put(est.id, est);
                estimateToEvent.put(est.id, e.id);
                estimateEvents.put(est.id, e);
            } else if ("decision".equals(e.type)) {
                applyDecision(state, e, p);
            } else if ("note".equals(e.type)) {
                state.notes.put(e.id, str(p, "text"));
            } else if ("invalidated".equals(e.type)) {
                addTimeline(state, e, p, true, null);
            }
        }

        propagateStaleness(state);
        return state;
    }

    @SuppressWarnings("unchecked")
    private void applySource(State state, Event e, Map<String, Object> p) {
        State.Source source = new State.Source();
        source.id = e.id;
        source.kind = str(p, "sourceKind");
        source.label = str(p, "label");
        source.materialId = str(p, "materialId");
        source.blobId = str(p, "blobId");
        source.version = str(p, "version");
        source.revisionOfId = e.revisionOfId;
        source.directSourceId = e.directSourceId;
        source.createdAt = e.createdAt;
        source.actor = e.actor;
        source.unit = str(p, "unit");
        source.coordinateBasis = str(p, "coordinateBasis");
        source.conventionFingerprint = str(p, "fingerprint");
        Object attrs = p.get("attributes");
        if (attrs instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> en : map.entrySet()) {
                source.attributes.put(String.valueOf(en.getKey()), String.valueOf(en.getValue()));
            }
        }
        Object diags = p.get("diagnostics");
        if (diags instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> dm) {
                    State.Diagnostic d = new State.Diagnostic();
                    d.code = str(dm, "code");
                    d.message = str(dm, "message");
                    d.location = str(dm, "location");
                    source.diagnostics.add(d);
                }
            }
        }
        if (e.revisionOfId != null) {
            State.Source old = state.sources.get(e.revisionOfId);
            if (old != null) {
                old.active = false;
            }
        }
        if ("source_revised".equals(e.type)) {
            String supersedes = str(p, "supersedes");
            if (supersedes != null) {
                State.Source old = state.sources.get(supersedes);
                if (old != null) {
                    old.active = false;
                }
            }
        }
        state.sources.put(source.id, source);
    }

    @SuppressWarnings("unchecked")
    private State.Estimate toEstimate(Event e, Map<String, Object> p) {
        State.Estimate est = new State.Estimate();
        est.id = e.id;
        est.createdEventId = e.id;
        est.materialId = str(p, "materialId");
        est.seq = e.seq;
        est.label = str(p, "label");
        est.conventionFingerprint = str(p, "fingerprint");
        est.stokesOrder = str(p, "stokesOrder");
        est.rotationSign = str(p, "rotationSign");
        est.conditionNumber = numOrInf(p, "conditionNumber");
        est.singularValues = toDoubles(p.get("singularValues"));
        est.rank = intVal(p, "rank");
        est.nullity = intVal(p, "nullity");
        est.unique = Boolean.TRUE.equals(p.get("unique"));
        est.nearSingular = Boolean.TRUE.equals(p.get("nearSingular"));
        est.designThreshold = doubleVal(p, "designThreshold");
        est.residualNorm = doubleVal(p, "residualNorm");
        est.mueller = toMatrix(p.get("mueller"));
        est.elementSigma = toMatrix(p.get("elementSigma"));
        est.nullspace = toMatrixRows(p.get("nullspace"));
        est.sourceIds = new ArrayList<>(e.sources());
        est.createdAt = e.createdAt;
        return est;
    }

    private void applyDecision(State state, Event e, Map<String, Object> p) {
        String estimateId = str(p, "estimateId");
        String decision = str(p, "decision");
        boolean reverted = Boolean.TRUE.equals(p.get("revert"));
        State.Estimate est = state.estimates.get(estimateId);
        State.Ruling ruling = new State.Ruling();
        ruling.eventId = e.id;
        ruling.estimateId = estimateId;
        ruling.decision = decision;
        ruling.note = str(p, "note");
        ruling.at = e.createdAt;
        ruling.actor = e.actor;
        ruling.reverted = reverted;
        state.rulings.add(ruling);
        if (est != null) {
            if (reverted) {
                State.Ruling previous = previousUnreverted(state, estimateId, e.id);
                if (previous != null) {
                    previous.reverted = true;
                }
                State.Ruling before = latestRulingBefore(state, estimateId, e.id);
                est.decision = before == null ? "unreviewed" : before.decision;
                est.decisionEventId = before == null ? null : before.eventId;
                est.decisionNote = before == null ? null : before.note;
                est.decisionAt = before == null ? null : before.at;
            } else {
                est.decision = decision;
                est.decisionEventId = e.id;
                est.decisionNote = str(p, "note");
                est.decisionAt = e.createdAt;
            }
        }
    }

    private State.Ruling previousUnreverted(State state, String estimateId, String untilEvent) {
        State.Ruling found = null;
        for (State.Ruling r : state.rulings) {
            if (r.eventId.equals(untilEvent)) {
                break;
            }
            if (r.estimateId.equals(estimateId) && !r.reverted) {
                found = r;
            }
        }
        return found;
    }

    private State.Ruling latestRulingBefore(State state, String estimateId, String untilEvent) {
        return previousUnreverted(state, estimateId, untilEvent);
    }

    /**
     * Dependency-based invalidation, scoped to the affected material and source
     * kind. Global sources (dark, reference, instrument, covariance) invalidate
     * all estimates; material-local sources only invalidate that material. A
     * revision never globally or silently replaces an old conclusion; it marks
     * the affected estimates stale with a precise reason.
     */
    private void propagateStaleness(State state) {
        Map<String, State.Source> byId = state.sources;
        Map<String, Integer> sourceOrdinal = new LinkedHashMap<>();
        int ordinal = 0;
        for (String id : state.sources.keySet()) {
            sourceOrdinal.put(id, ordinal++);
        }

        for (State.Estimate est : state.estimates.values()) {
            String reason = null;
            String newestUnused = null;
            int newestUnusedOrdinal = -1;
            for (State.Source s : byId.values()) {
                boolean inScope = s.materialId == null || s.materialId.isBlank()
                        || s.materialId.equals(est.materialId);
                if (!inScope) {
                    continue;
                }
                boolean used = est.sourceIds.contains(s.id);
                State.Source referenced = resolveReference(byId, est.sourceIds, s.kind, est.materialId);
                if (referenced != null && referenced.id.equals(s.id)) {
                    continue;
                }
                if (!used) {
                    Integer o = sourceOrdinal.get(s.id);
                    if (o != null && o > newestUnusedOrdinal) {
                        newestUnusedOrdinal = o;
                        newestUnused = s.id;
                    }
                }
            }
            if (newestUnused != null) {
                State.Source ns = byId.get(newestUnused);
                reason = "stale: newer " + ns.kind
                        + (ns.version != null ? " v" + ns.version : "")
                        + " (" + newestUnused.substring(0, 8)
                        + ") is not part of this estimate; recompute to include it";
            }
            est.stale = reason != null;
            est.staleReason = reason;
        }
    }

    private State.Source resolveReference(Map<String, State.Source> byId, List<String> ids,
                                          String kind, String materialId) {
        State.Source found = null;
        for (String id : ids) {
            State.Source s = byId.get(id);
            if (s == null) {
                continue;
            }
            if (s.kind.equals(kind) && (s.materialId == null || s.materialId.isBlank()
                    || s.materialId.equals(materialId))) {
                found = s;
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private void addTimeline(State state, Event e, Map<String, Object> p, boolean special, String extra) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", Integer.parseInt(e.seq));
        item.put("id", e.id);
        item.put("type", e.type);
        item.put("actor", e.actor);
        item.put("at", e.createdAt);
        item.put("parentId", e.parentId);
        item.put("fingerprint", e.fingerprint);
        item.put("directSourceId", e.directSourceId);
        item.put("revisionOfId", e.revisionOfId);
        item.put("sources", e.sources());
        item.put("payload", p);
        if (extra != null) {
            item.put("extra", extra);
        }
        state.timeline.add(item);
    }

    private static String str(Map<?, ?> p, String key) {
        Object v = p.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Map<?, ?> p, String key) {
        return p.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleVal(Map<?, ?> p, String key) {
        return p.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    private static double numOrInf(Map<?, ?> p, String key) {
        Object v = p.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.POSITIVE_INFINITY;
    }

    private static double[] toDoubles(Object o) {
        if (!(o instanceof List<?> list)) {
            return new double[0];
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i) instanceof Number n ? n.doubleValue() : 0;
        }
        return out;
    }

    private static double[][] toMatrix(Object o) {
        if (!(o instanceof List<?> rows)) {
            return null;
        }
        double[][] out = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof List<?> cols) {
                out[i] = new double[cols.size()];
                for (int j = 0; j < cols.size(); j++) {
                    out[i][j] = cols.get(j) instanceof Number n ? n.doubleValue() : 0;
                }
            }
        }
        return out;
    }

    private static double[][] toMatrixRows(Object o) {
        return toMatrix(o);
    }
}
