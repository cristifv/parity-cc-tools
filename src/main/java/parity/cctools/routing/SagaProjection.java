package parity.cctools.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * This pod's in-memory view of the sagas its pages follow (PLAN.md §6.2): seeded by {@code get_saga},
 * then moved by the saga's events in {@code seq} order (an event at or below the seen {@code seq} is
 * dropped; a gap reads the saga again); service-control sagas are put here by this tool. It decides
 * nothing: the pages render it. Bounded: the oldest finished sagas go first.
 */
public class SagaProjection {

    private static final Logger log = LoggerFactory.getLogger(SagaProjection.class);
    static final int MAX_SAGAS = 5_000;
    static final int MAX_EVENT_IDS = 20_000;

    /** One step of a saga, as shown. */
    public record Step(String state, long atMs, String detail) {
        private static final java.time.format.DateTimeFormatter HMS =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC);

        /** The step's time of day, UTC (the pages show it). */
        public String time() {
            return HMS.format(java.time.Instant.ofEpochMilli(atMs));
        }
    }

    /** A saga as the pages show it. {@code source}: {@code parity-rs} or {@code service}. */
    public record View(String sagaId, String zone, String kind, String tenantId, String opId, String parentSagaId,
            String state, String outcome, long seq, List<Step> steps, Integer done, Integer failed, Integer total,
            String detail, long updatedAtMs, String source, JsonNode saga) {

        public View {
            steps = List.copyOf(steps);
        }

        public boolean finished() {
            return outcome != null;
        }

        public boolean blocked() {
            return "blocked".equals(state) && outcome == null;
        }
    }

    private final Map<String, View> sagas = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<View>>> watchers = new ConcurrentHashMap<>();
    private final List<Consumer<View>> finishedListeners = new CopyOnWriteArrayList<>();
    private final Set<String> seenEvents = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_EVENT_IDS;
        }
    });
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Resync resync = (zone, sagaId) -> { };

    /** How a gap is filled: read the saga again (set by the wiring: get_saga on the zone). */
    public interface Resync {
        void request(String zone, String sagaId);
    }

    public void setResync(Resync resync) {
        this.resync = resync;
    }

    public View get(String sagaId) {
        return sagaId == null ? null : sagas.get(sagaId);
    }

    /** Every saga seen, newest first (the operations page adds parity-rs's own list). */
    public List<View> all() {
        List<View> out = new ArrayList<>(sagas.values());
        out.sort((a, b) -> Long.compare(b.updatedAtMs(), a.updatedAtMs()));
        return out;
    }

    /** Called with a saga's view each time it changes, until the returned handle runs. */
    public Runnable watch(String sagaId, Consumer<View> watcher) {
        watchers.computeIfAbsent(sagaId, k -> new CopyOnWriteArrayList<>()).add(watcher);
        return () -> {
            List<Consumer<View>> l = watchers.get(sagaId);
            if (l != null) {
                l.remove(watcher);
                if (l.isEmpty()) {
                    watchers.remove(sagaId, l);
                }
            }
        };
    }

    /** Called once per saga when it is first seen finished on this pod. */
    public void onFinished(Consumer<View> listener) {
        finishedListeners.add(listener);
    }

    /** The saga as parity-rs's {@code get_saga} returned it: replaces the view unless that is newer. */
    public View seed(String zone, JsonNode saga) {
        String id = saga.path("saga_id").asString(null);
        if (id == null) {
            return null;
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode s : saga.path("steps")) {
            steps.add(new Step(s.path("name").asString(""), s.path("at_ms").asLong(0), s.path("detail").asString(null)));
        }
        String outcome = saga.hasNonNull("outcome") ? saga.path("outcome").asString() : null;
        JsonNode progress = saga.path("progress");
        View v = new View(id, saga.path("zone").asString(zone), saga.path("kind").asString("unknown"),
                text(saga, "tenant_id"), text(saga, "op_id"), text(saga, "parent_saga_id"), saga.path("state").asString("accepted"),
                outcome, saga.path("seq").asLong(0), steps, intOrNull(progress, "done"), intOrNull(progress, "failed"),
                intOrNull(progress, "total"), text(saga, "detail"), saga.path("updated_at_ms").asLong(System.currentTimeMillis()),
                "parity-rs", saga);
        return replaceIfNotOlder(v);
    }

    /** A service-control saga's new state (this tool's own). */
    public void put(View v) {
        replaceIfNotOlder(v);
    }

    /** An event of {@code tenant.routing.events.<zone>}; saga events move their saga. */
    public void apply(RsTransport.Event e) {
        JsonNode b = e.body();
        String type = b.path("type").asString("");
        if (!type.startsWith("saga.")) {
            return;
        }
        String eventId = b.path("event_id").asString("");
        lock.lock();
        try {
            if (!eventId.isEmpty() && !seenEvents.add(eventId)) {
                return;
            }
        } finally {
            lock.unlock();
        }
        String sagaId = b.path("saga_id").asString(null);
        long seq = b.path("seq").asLong(0);
        if (sagaId == null || seq < 1) {
            return;
        }
        View old = sagas.get(sagaId);
        if (old != null && seq <= old.seq()) {
            return;
        }
        if ((old != null && seq > old.seq() + 1) || (old == null && seq > 1 && watchers.containsKey(sagaId))) {
            log.atInfo().addKeyValue("event", "rs.saga_resynced").addKeyValue("saga_id", sagaId)
                    .addKeyValue("seen_seq", old == null ? 0 : old.seq()).addKeyValue("event_seq", seq)
                    .addKeyValue("zone", e.zone()).log("a gap in a saga's events: reading it again");
            resync.request(e.zone(), sagaId);
        }
        long at = b.path("at_ms").asLong(System.currentTimeMillis());
        List<Step> steps = old == null ? new ArrayList<>() : new ArrayList<>(old.steps());
        String state = old == null ? "accepted" : old.state();
        String outcome = old == null ? null : old.outcome();
        String detail = text(b, "detail");
        Integer done = old == null ? null : old.done();
        Integer failed = old == null ? null : old.failed();
        Integer total = old == null ? null : old.total();
        switch (type) {
            case "saga.accepted" -> {
                state = "accepted";
                steps.add(new Step("accepted", at, null));
            }
            case "saga.state_changed" -> {
                state = b.path("to_state").asString(state);
                steps.add(new Step(state, at, detail));
                JsonNode p = b.path("progress");
                if (p.isObject()) {
                    done = intOrNull(p, "done");
                    failed = intOrNull(p, "failed");
                    total = intOrNull(p, "total");
                }
            }
            case "saga.blocked" -> {
                state = "blocked";
                detail = text(b, "reason");
                steps.add(new Step("blocked", at, detail));
            }
            case "saga.finished" -> {
                outcome = b.path("outcome").asString("done");
                state = outcome;
                steps.add(new Step(state, at, detail));
            }
            default -> {
                return;
            }
        }
        View v = new View(sagaId, b.path("zone").asString(e.zone()), b.path("kind").asString(old == null ? "unknown" : old.kind()),
                text(b, "tenant_id") != null ? text(b, "tenant_id") : old == null ? null : old.tenantId(),
                text(b, "op_id") != null ? text(b, "op_id") : old == null ? null : old.opId(),
                text(b, "parent_saga_id") != null ? text(b, "parent_saga_id") : old == null ? null : old.parentSagaId(),
                state, outcome, seq, steps, done, failed, total, detail != null ? detail : old == null ? null : old.detail(), at,
                "parity-rs", old == null ? null : old.saga());
        replaceIfNotOlder(v);
    }

    private View replaceIfNotOlder(View v) {
        View[] result = {v};
        boolean[] replaced = {true};
        boolean[] newlyFinished = {false};
        sagas.compute(v.sagaId(), (k, old) -> {
            if (old != null && old.seq() > v.seq()) {
                result[0] = old;
                replaced[0] = false;
                return old;
            }
            newlyFinished[0] = v.finished() && (old == null || !old.finished());
            return v;
        });
        if (replaced[0]) {
            List<Consumer<View>> l = watchers.get(v.sagaId());
            if (l != null) {
                l.forEach(w -> w.accept(v));
            }
            if (newlyFinished[0]) {
                finishedListeners.forEach(f -> f.accept(v));
            }
            trim();
        }
        return result[0];
    }

    private void trim() {
        if (sagas.size() <= MAX_SAGAS) {
            return;
        }
        sagas.values().stream().filter(View::finished).filter(v -> !watchers.containsKey(v.sagaId()))
                .sorted((a, b) -> Long.compare(a.updatedAtMs(), b.updatedAtMs()))
                .limit(sagas.size() - MAX_SAGAS + 100L).map(View::sagaId).toList().forEach(sagas::remove);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && n.path(field).isString() ? n.path(field).asString() : null;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) && n.path(field).isNumber() ? n.path(field).asInt() : null;
    }
}
