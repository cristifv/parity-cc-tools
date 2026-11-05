package parity.cctools.control;

import io.micrometer.core.instrument.Gauge;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import parity.cctools.audit.AuditLog;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.catalogue.Catalogue.Driver;
import parity.cctools.catalogue.Catalogues;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.health.HealthCollector;
import parity.cctools.health.PrometheusText;
import parity.cctools.k8s.KubeApi;
import parity.cctools.k8s.KubeApis;
import parity.cctools.k8s.KubeException;
import parity.cctools.lifecycle.Drainable;
import parity.cctools.mi.OpensipsMi;
import parity.cctools.mi.OpensipsMis;
import parity.cctools.obs.ToolMetrics;
import parity.cctools.routing.SagaProjection;
import parity.cctools.security.Operator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Service control (PLAN.md §6.4): disable and enable as sagas run by this tool, persisted in Postgres so
 * any pod resumes them after a restart.
 * <ul>
 *   <li>{@link #start} and {@link #cancel} change the saga's row (the operator's action, audited by the
 *       caller); one open saga per target (a unique index);</li>
 *   <li>each evaluation locks the saga's row ({@code FOR UPDATE SKIP LOCKED}: one pod at a time, no lease,
 *       no timeout; a pod that dies drops its lock with its connection), observes the facts, asks the
 *       driver ({@link Drivers}), sets the desired absolute value when it isn't in place, and moves the
 *       saga only when an observed fact allows it (compare-and-set on its version);</li>
 *   <li>evaluations are woken by a start, a cancel, a Kubernetes watch event ({@link KubeWatchers}), and
 *       every {@code control.refresh-interval} (a timer that only refreshes the observation).</li>
 * </ul>
 */
@Component
public final class ServiceControl implements Drainable {

    private static final Logger log = LoggerFactory.getLogger(ServiceControl.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What a saga acts on. {@code instance}: a pod, a media node's name, or {@code *} for the workload. */
    public record Target(String zone, Driver driver, String service, String instance, String namespace) {}

    /** {@code sagaId} when started; else a refusal {@code code} (target_busy, already_stopped, ...) and detail. */
    public record Result(String sagaId, String code, String detail) {
        public boolean started() {
            return sagaId != null && code == null;
        }

        static Result refused(String code, String detail) {
            return new Result(null, code, detail);
        }
    }

    private final ServiceOps ops;
    private final Catalogues catalogues;
    private final KubeApis kube;
    private final OpensipsMis mis;
    private final TransactionTemplate tx;
    private final SagaProjection projection;
    private final AuditLog audit;
    private final HealthCollector health;
    private final CcToolsProperties.Control config;
    private final KubeWatchers watchers;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("service-control")
            .daemon(true).unstarted(r));
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<Driver, AtomicInteger> active = new HashMap<>();
    private final Map<String, CcToolsProperties.Opensips> opensips = new HashMap<>();
    private volatile boolean started;

    public ServiceControl(ServiceOps ops, Catalogues catalogues, KubeApis kube, OpensipsMis mis, TransactionTemplate tx,
            SagaProjection projection, AuditLog audit, HealthCollector health, CcToolsProperties properties, ToolMetrics metrics) {
        this.ops = ops;
        this.catalogues = catalogues;
        this.kube = kube;
        this.mis = mis;
        this.tx = tx;
        this.projection = projection;
        this.audit = audit;
        this.health = health;
        this.config = properties.control();
        this.watchers = new KubeWatchers(kube, this::wakeNamespace);
        properties.zones().forEach((zone, z) -> {
            if (z.opensips() != null) {
                opensips.put(zone, z.opensips());
            }
        });
        for (Driver d : Driver.values()) {
            AtomicInteger n = new AtomicInteger();
            active.put(d, n);
            Gauge.builder("parity_cc_tools_service_ops_active", n, AtomicInteger::get).tag("driver", d.wire())
                    .register(metrics.registry());
        }
    }

    /** At start: every open saga (any pod's) is evaluated again: this is the resume after a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void resume() {
        started = true;
        if (config.enabled()) {
            refresh();
        }
    }

    /** The timer: refreshes every open saga's observation (the fact decides, not the time). */
    @Scheduled(fixedDelayString = "${parity-cc-tools.control.refresh-interval}", initialDelayString = "${parity-cc-tools.control.refresh-interval}")
    public void refresh() {
        if (!started || !config.enabled()) {
            return;
        }
        List<ServiceOps.Op> open;
        try {
            open = ops.open();
        } catch (RuntimeException e) {
            return;
        }
        Map<Driver, Integer> counts = new HashMap<>();
        Set<String> watch = new TreeSet<>();
        for (ServiceOps.Op o : open) {
            counts.merge(o.driver(), 1, Integer::sum);
            wake(o.id());
            if (o.driver() == Driver.K8S_ROTATION) {
                watch.add(o.zone() + "|" + o.namespace() + "|endpointslices");
            } else if (o.driver() == Driver.K8S_SCALE) {
                catalogues.zone(o.zone()).flatMap(c -> c.service(o.service()))
                        .ifPresent(s -> watch.add(o.zone() + "|" + o.namespace() + "|" + KubeWatchers.plural(s.kind())));
            } else if (o.driver() == Driver.MEDIA_NODE) {
                // An opensips SBC node that restarts comes back with every entry active (persistent_state 0):
                // its pod's restart or readiness change wakes the held sagas, which read back and set it again.
                CcToolsProperties.Opensips sbc = opensips.get(o.zone());
                if (sbc != null && sbc.podNamespace() != null && !sbc.podNamespace().isBlank() && sbc.podSelector() != null
                        && !sbc.podSelector().isBlank()) {
                    watch.add(o.zone() + "|" + sbc.podNamespace() + "|" + OPENSIPS_PODS + "|" + sbc.podSelector());
                }
            }
        }
        active.forEach((d, n) -> n.set(counts.getOrDefault(d, 0)));
        watchers.ensure(watch);
    }

    /** The watch of a zone's opensips pods (KubeWatchers' resource name for it: a pods watch). */
    static final String OPENSIPS_PODS = "pods";

    private void wakeNamespace(String zone, String namespace, String resource) {
        CcToolsProperties.Opensips sbc = opensips.get(zone);
        boolean opensipsPods = OPENSIPS_PODS.equals(resource) && sbc != null && namespace.equals(sbc.podNamespace());
        try {
            for (ServiceOps.Op o : ops.open()) {
                if (o.zone().equals(zone) && (o.namespace().equals(namespace) || (opensipsPods && o.driver() == Driver.MEDIA_NODE))) {
                    wake(o.id());
                }
            }
        } catch (RuntimeException e) {
            // The next refresh wakes them.
        }
    }

    /** Queues one evaluation of the saga (at most one queued per saga). */
    public void wake(String sagaId) {
        if (runner.isShutdown()) {
            return;
        }
        if (queued.add(sagaId)) {
            try {
                runner.execute(() -> {
                queued.remove(sagaId);
                try {
                    evaluate(sagaId);
                } catch (RuntimeException e) {
                    log.atWarn().addKeyValue("event", "k8s.call_failed").addKeyValue("op", "service_op.evaluate")
                            .addKeyValue("saga_id", sagaId).addKeyValue("error_kind", e.getClass().getName())
                            .log("a service-control evaluation failed; the next refresh tries again");
                }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Draining: the next pod's start resumes it.
                queued.remove(sagaId);
            }
        }
    }

    /**
     * Starts a disable or an enable saga. An enable of a target whose disable saga is holding releases that
     * saga (outcome {@code released}) and starts from what it recorded. Nothing is applied here: the first
     * evaluation does it.
     */
    public Result start(Target t, boolean disable, String opId, String sagaId, Operator actor, String reason, boolean force) {
        Catalogue c = catalogues.zone(t.zone()).orElse(null);
        if (c == null) {
            return Result.refused("unknown_zone", "the zone isn't configured");
        }
        Result[] out = new Result[1];
        try {
            tx.executeWithoutResult(status -> {
                var open = ops.openFor(t.zone(), t.service(), t.instance());
                ObjectNode recorded = JSON.createObjectNode();
                if (disable) {
                    if (open.isPresent()) {
                        out[0] = Result.refused("target_busy", "a service-control saga is already open on this target");
                        return;
                    }
                    if (t.driver() == Driver.K8S_SCALE) {
                        Catalogue.ServiceDef s = c.service(t.service()).orElseThrow();
                        KubeApi api = kube.zone(t.zone()).orElse(null);
                        if (api == null) {
                            out[0] = Result.refused("no_kubernetes", "no Kubernetes API is configured for this zone");
                            return;
                        }
                        int replicas = api.scale(s.namespace(), s.kind().name(), s.workload()).path("spec").path("replicas").asInt(0);
                        if (replicas == 0) {
                            out[0] = Result.refused("already_stopped", "the workload runs no replica");
                            return;
                        }
                        recorded.put("replicas", replicas);
                    }
                } else {
                    if (open.isPresent()) {
                        ServiceOps.Op o = open.get();
                        if (!o.disable() || !Drivers.holding(o.driver()).equals(o.state())) {
                            out[0] = Result.refused("target_busy", "a service-control saga is still running on this target");
                            return;
                        }
                        if (!ops.transition(o, o.state(), "released", "operator:enable", opId, actor.sub(),
                                "released by an enable saga", o.observed())) {
                            out[0] = Result.refused("target_busy", "the holding saga moved; try again");
                            return;
                        }
                        finished(o, "released", "released by an enable saga");
                        if (o.recorded() != null && o.recorded().has("replicas")) {
                            recorded.put("replicas", o.recorded().path("replicas").asInt());
                        }
                    }
                    if (t.driver() == Driver.K8S_SCALE && !recorded.has("replicas")) {
                        Integer def = c.service(t.service()).map(Catalogue.ServiceDef::defaultReplicas).orElse(null);
                        recorded.put("replicas", def == null ? 1 : def);
                    }
                }
                String state = Drivers.start(t.driver(), disable);
                ServiceOps.Op op = new ServiceOps.Op(sagaId, opId, t.zone(), t.driver(), disable ? "disable" : "enable", t.service(),
                        t.instance(), t.namespace(), state, null, recorded, null, actor.sub(), actor.username(), reason, force, 1, 1,
                        null, null, null, null);
                ops.insert(op, "operator:" + (disable ? "disable" : "enable"));
                log.atInfo().addKeyValue("event", "service_op.state_changed").addKeyValue("op_id", opId).addKeyValue("saga_id", sagaId)
                        .addKeyValue("zone", t.zone()).addKeyValue("service_name", t.service()).addKeyValue("driver", t.driver().wire())
                        .addKeyValue("instance", t.instance()).addKeyValue("from_state", "requested").addKeyValue("to_state", state)
                        .addKeyValue("cause", "operator:" + (disable ? "disable" : "enable")).log("a service-control saga started");
                out[0] = new Result(sagaId, null, null);
            });
        } catch (DuplicateKeyException e) {
            return Result.refused("target_busy", "a service-control saga is already open on this target");
        } catch (KubeException e) {
            return Result.refused("kubernetes_" + e.code(), "the Kubernetes API refused or didn't answer");
        }
        if (out[0] != null && out[0].started()) {
            publish(sagaId);
            wake(sagaId);
        }
        return out[0];
    }

    /** Cancels a disabling saga: it reverts to the state before it ({@code reverting}, then {@code cancelled}). */
    public Result cancel(String sagaId, String opId, Operator actor) {
        Result[] out = new Result[1];
        tx.executeWithoutResult(status -> {
            var o = ops.lockOpen(sagaId).orElse(null);
            if (o == null) {
                out[0] = Result.refused("not_open", "the saga has finished or is being evaluated; try again");
                return;
            }
            if (!Drivers.cancellable(o.driver(), o.state())) {
                out[0] = Result.refused("not_cancellable", "only a saga still disabling can be cancelled; enable the target instead");
                return;
            }
            if (!ops.transition(o, Drivers.REVERTING, null, "operator:cancel", opId, actor.sub(), "cancelled by an operator", o.observed())) {
                out[0] = Result.refused("not_open", "the saga moved; try again");
                return;
            }
            log.atInfo().addKeyValue("event", "service_op.state_changed").addKeyValue("op_id", opId).addKeyValue("saga_id", sagaId)
                    .addKeyValue("zone", o.zone()).addKeyValue("service_name", o.service()).addKeyValue("driver", o.driver().wire())
                    .addKeyValue("from_state", o.state()).addKeyValue("to_state", Drivers.REVERTING).addKeyValue("cause", "operator:cancel")
                    .log("a service-control saga is being cancelled");
            out[0] = new Result(sagaId, null, null);
        });
        if (out[0].started()) {
            publish(sagaId);
            wake(sagaId);
        }
        return out[0];
    }

    /** One evaluation: observe, decide, apply what isn't in place, move on an observed fact. */
    void evaluate(String sagaId) {
        boolean[] again = {false};
        tx.executeWithoutResult(status -> {
            ServiceOps.Op o = ops.lockOpen(sagaId).orElse(null);
            if (o == null) {
                return;
            }
            Catalogue c = catalogues.zone(o.zone()).orElse(null);
            KubeApi api = kube.zone(o.zone()).orElse(null);
            ObjectNode observed = JSON.createObjectNode();
            Drivers.Decision d;
            try {
                d = decide(o, c, api, observed);
            } catch (KubeException e) {
                if (e.definitive() && e.status() != 404) {
                    finish(o, "failed", "the Kubernetes API refused it (" + e.code() + ")", observed);
                }
                return;
            }
            if (d.apply()) {
                try {
                    apply(o, c, api, d.desired(), observed);
                    again[0] = true;
                    if (Drivers.holding(o.driver()).equals(o.state())) {
                        log.atWarn().addKeyValue("event", "service_op.reapplied").addKeyValue("op_id", o.opId())
                                .addKeyValue("saga_id", o.id()).addKeyValue("zone", o.zone()).addKeyValue("service_name", o.service())
                                .addKeyValue("driver", o.driver().wire()).addKeyValue("what", d.detail())
                                .log("a holding saga's setting was changed behind it: set again");
                    }
                } catch (KubeException e) {
                    if (e.definitive()) {
                        finish(o, "failed", "the Kubernetes API refused the change (" + e.code() + ")", observed);
                    }
                    return;
                } catch (OpensipsMi.MiException e) {
                    ops.observed(o, observed, "an opensips SBC node's management interface refused or didn't answer: waiting");
                    return;
                }
            }
            if (d.moves()) {
                String to = d.toState() != null ? d.toState() : o.state();
                if (ops.transition(o, to, d.outcome(), "event:observed", o.opId(), null, d.detail(), observed)) {
                    log.atInfo().addKeyValue("event", "service_op.state_changed").addKeyValue("op_id", o.opId())
                            .addKeyValue("saga_id", o.id()).addKeyValue("zone", o.zone()).addKeyValue("service_name", o.service())
                            .addKeyValue("driver", o.driver().wire()).addKeyValue("instance", o.instance())
                            .addKeyValue("from_state", o.state()).addKeyValue("to_state", to)
                            .addKeyValue("cause", "internal:observed").log("a service-control saga moved");
                    if (d.outcome() != null) {
                        finished(o, d.outcome(), d.detail());
                    }
                    again[0] = d.outcome() == null;
                }
            } else if (o.observed() == null || !o.observed().equals(observed) || !java.util.Objects.equals(o.detail(), d.detail())) {
                ops.observed(o, observed, d.detail());
            }
        });
        publish(sagaId);
        if (again[0]) {
            wake(sagaId);
        }
    }

    private void finish(ServiceOps.Op o, String outcome, String detail, JsonNode observed) {
        if (ops.transition(o, o.state(), outcome, "internal:refused", o.opId(), null, detail, observed)) {
            finished(o, outcome, detail);
        }
    }

    private void finished(ServiceOps.Op o, String outcome, String detail) {
        log.atInfo().addKeyValue("event", "service_op.finished").addKeyValue("op_id", o.opId()).addKeyValue("saga_id", o.id())
                .addKeyValue("zone", o.zone()).addKeyValue("service_name", o.service()).addKeyValue("driver", o.driver().wire())
                .addKeyValue("saga_outcome", outcome).log("a service-control saga ended");
        if (audit.outcome(o.id(), o.opId(), "service", o.zone(), outcome, detail)) {
            log.atInfo().addKeyValue("event", "action.outcome").addKeyValue("op_id", o.opId()).addKeyValue("saga_id", o.id())
                    .addKeyValue("zone", o.zone()).addKeyValue("saga_outcome", outcome).log("an action's saga ended");
        }
    }

    private Drivers.Decision decide(ServiceOps.Op o, Catalogue c, KubeApi api, ObjectNode observed) {
        if (c == null) {
            return new Drivers.Decision(null, false, null, "failed", "the zone isn't configured any more");
        }
        return switch (o.driver()) {
            case K8S_ROTATION -> decideRotation(o, c, api, observed);
            case MEDIA_NODE -> decideMedia(o, c, api, observed);
            case K8S_SCALE -> decideScale(o, c, api, observed);
        };
    }

    private Drivers.Decision decideRotation(ServiceOps.Op o, Catalogue c, KubeApi api, ObjectNode observed) {
        Catalogue.ServiceDef s = c.service(o.service()).orElse(null);
        if (s == null || api == null) {
            return new Drivers.Decision(null, false, null, "failed", "the service or the zone's Kubernetes API isn't configured");
        }
        Drivers.RotationObs obs = observeRotation(api, s, o.instance());
        observed.put("pod_exists", obs.podExists()).put("label", obs.label()).put("in_endpoints", obs.inEndpoints())
                .put("endpoint_ready", obs.endpointReady());
        return Drivers.rotation(o.state(), obs);
    }

    private Drivers.Decision decideMedia(ServiceOps.Op o, Catalogue c, KubeApi api, ObjectNode observed) {
        Catalogue.MediaNodeDef m = c.mediaNode(o.instance()).orElse(null);
        List<OpensipsMi> sbcs = mis.zone(o.zone());
        if (m == null || sbcs.isEmpty()) {
            return new Drivers.Decision(null, false, null, "failed", "the media node or the zone's opensips SBC nodes aren't configured");
        }
        Drivers.MediaObs obs = observeMedia(api, sbcs, m);
        Set<String> dests = new TreeSet<>();
        for (OpensipsMi sbc : sbcs) {
            m.dispatcher().forEach(x -> dests.add(sbc.name() + "|" + OpensipsMi.key(x.set(), x.address())));
        }
        ObjectNode ds = observed.putObject("dispatcher");
        if (obs.dispatcher() != null) {
            dests.forEach(k -> ds.put(k, obs.dispatcher().getOrDefault(k, "missing")));
        } else {
            observed.put("dispatcher_unreachable", true);
        }
        ObjectNode labels = observed.putObject("labels");
        obs.podLabels().forEach(labels::put);
        if (obs.activeCalls() != null) {
            observed.put("active_calls", obs.activeCalls());
        }
        return Drivers.media(o.state(), obs, dests);
    }

    private static Drivers.Decision decideScale(ServiceOps.Op o, Catalogue c, KubeApi api, ObjectNode observed) {
        Catalogue.ServiceDef s = c.service(o.service()).orElse(null);
        if (s == null || api == null) {
            return new Drivers.Decision(null, false, null, "failed", "the service or the zone's Kubernetes API isn't configured");
        }
        Drivers.ScaleObs obs = observeScale(api, s);
        observed.put("exists", obs.exists()).put("spec_replicas", obs.specReplicas()).put("replicas", obs.statusReplicas())
                .put("ready_replicas", obs.readyReplicas());
        int recorded = o.recorded() == null ? 1 : o.recorded().path("replicas").asInt(1);
        return Drivers.scale(o.state(), obs, recorded);
    }

    static Drivers.RotationObs observeRotation(KubeApi api, Catalogue.ServiceDef s, String pod) {
        JsonNode p = api.pod(s.namespace(), pod);
        if (p == null) {
            return new Drivers.RotationObs(false, null, false, false);
        }
        String label = p.path("metadata").path("labels").path(KubeApi.ROTATION_LABEL).asString(null);
        String ip = p.path("status").path("podIP").asString(null);
        boolean in = false;
        boolean ready = false;
        for (JsonNode slice : api.endpointSlices(s.namespace(), s.k8sService())) {
            for (JsonNode ep : slice.path("endpoints")) {
                boolean match = pod.equals(ep.path("targetRef").path("name").asString(null));
                for (JsonNode a : ep.path("addresses")) {
                    match |= ip != null && ip.equals(a.asString());
                }
                if (match) {
                    in = true;
                    ready |= !ep.path("conditions").path("ready").isBoolean() || ep.path("conditions").path("ready").asBoolean();
                }
            }
        }
        return new Drivers.RotationObs(true, label, in, ready);
    }

    /** Every SBC node's dispatcher (all of them, or null when one doesn't answer), the node's labels and gauge. */
    private Drivers.MediaObs observeMedia(KubeApi api, List<OpensipsMi> sbcs, Catalogue.MediaNodeDef m) {
        Map<String, String> ds = new LinkedHashMap<>();
        try {
            for (OpensipsMi sbc : sbcs) {
                for (var e : sbc.dsList().entrySet()) {
                    ds.put(sbc.name() + "|" + e.getKey(), e.getValue().state());
                }
            }
        } catch (OpensipsMi.MiException e) {
            ds = null;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        Double calls = null;
        if (api != null) {
            for (JsonNode p : api.pods(m.namespace(), m.podSelector())) {
                labels.put(p.path("metadata").path("name").asString("?"),
                        p.path("metadata").path("labels").path(KubeApi.ROTATION_LABEL).asString("in"));
            }
            calls = gauge(api, m);
        }
        return new Drivers.MediaObs(ds, labels, calls);
    }

    /** The node's active-call gauge, summed over its gauge pods; null when no pod answers. */
    private Double gauge(KubeApi api, Catalogue.MediaNodeDef m) {
        Catalogue.GaugeDef g = m.gauge();
        Double sum = null;
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(health.probeTimeout()).build();
        for (JsonNode p : api.pods(m.namespace(), g.podSelector())) {
            String ip = p.path("status").path("podIP").asString(null);
            if (ip == null) {
                continue;
            }
            try {
                var r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + g.port() + g.path()))
                        .timeout(health.probeTimeout().multipliedBy(2)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    return null;
                }
                Map<String, Double> v = PrometheusText.pick(r.body(), List.of(new Catalogue.MetricDef(g.metric(), g.labels(), "calls")));
                sum = (sum == null ? 0 : sum) + v.getOrDefault("calls", 0.0);
            } catch (java.io.IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return sum;
    }

    static Drivers.ScaleObs observeScale(KubeApi api, Catalogue.ServiceDef s) {
        JsonNode w = api.workload(s.namespace(), s.kind().name(), s.workload());
        if (w == null) {
            return new Drivers.ScaleObs(false, 0, 0, 0);
        }
        return new Drivers.ScaleObs(true, w.path("spec").path("replicas").asInt(0), w.path("status").path("replicas").asInt(0),
                w.path("status").path("readyReplicas").asInt(0));
    }

    private void apply(ServiceOps.Op o, Catalogue c, KubeApi api, Drivers.Desired want, ObjectNode observed) {
        switch (o.driver()) {
            case K8S_ROTATION -> api.setRotation(o.namespace(), o.instance(), want.label());
            case MEDIA_NODE -> {
                // Every SBC node of the zone: the dispatcher entries this node has there.
                Catalogue.MediaNodeDef m = c.mediaNode(o.instance()).orElseThrow();
                String wantState = "i".equals(want.dispatcher()) ? "inactive" : "active";
                JsonNode ds = observed.path("dispatcher");
                for (OpensipsMi sbc : mis.zone(o.zone())) {
                    for (Catalogue.DispatcherTarget t : m.dispatcher()) {
                        if (!wantState.equals(ds.path(sbc.name() + "|" + OpensipsMi.key(t.set(), t.address())).asString(""))) {
                            sbc.dsSetState(want.dispatcher(), t.set(), t.address());
                        }
                    }
                }
                if (api != null) {
                    observed.path("labels").properties().forEach(e -> {
                        if (!want.label().equals(e.getValue().asString(""))) {
                            api.setRotation(m.namespace(), e.getKey(), want.label());
                        }
                    });
                }
            }
            case K8S_SCALE -> {
                Catalogue.ServiceDef s = c.service(o.service()).orElseThrow();
                api.setReplicas(s.namespace(), s.kind().name(), s.workload(), want.replicas());
            }
            default -> throw new IllegalStateException("unknown driver");
        }
    }

    /** The saga's view for the pages (and their SSE streams). */
    public void publish(String sagaId) {
        ops.find(sagaId).ifPresent(o -> {
            List<SagaProjection.Step> steps = ops.steps(o.id()).stream()
                    .map(s -> new SagaProjection.Step(s.toState(), s.at().toEpochMilli(), s.detail())).toList();
            projection.put(new SagaProjection.View(o.id(), o.zone(), o.driver().wire(), null, o.opId(), null, o.state(), o.outcome(),
                    o.seq(), steps, null, null, null, o.detail(), o.updatedAt().toEpochMilli(), "service", o.observed()));
        });
    }

    @Override
    public boolean drain(Duration timeout) throws InterruptedException {
        started = false;
        watchers.ensure(Set.of());
        runner.shutdown();
        return runner.awaitTermination(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
