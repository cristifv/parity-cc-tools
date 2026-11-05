package parity.cctools.health;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.catalogue.Catalogues;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.k8s.KubeApi;
import parity.cctools.k8s.KubeApis;
import parity.cctools.k8s.KubeException;
import parity.cctools.obs.ToolMetrics;
import tools.jackson.databind.JsonNode;

/**
 * The platform health collector (PLAN.md §6.3): every {@code health.interval} (10 s; a display refresh,
 * never a decision) it lists each catalogue service's pods through its zone's Kubernetes API (phase,
 * readiness, restarts, last termination, node, rotation label), probes each pod's {@code /livez} and
 * {@code /readyz} on its management port (1 s timeout, at most 16 at once) and reads the listed metrics
 * from {@code /metrics} (4 MiB cap). A failed probe is shown with its status or error kind and not
 * retried until the next round. The snapshot lives in memory; the dashboard reads it.
 */
@Component
public class HealthCollector {

    private static final Logger log = LoggerFactory.getLogger(HealthCollector.class);

    private final Catalogues catalogues;
    private final KubeApis kube;
    private final JdbcClient db;
    private final CcToolsProperties.Health config;
    private final ToolMetrics metrics;
    private final Clock clock;
    private final HttpClient http;
    private volatile Health.Snapshot snapshot = Health.Snapshot.empty();

    public HealthCollector(Catalogues catalogues, KubeApis kube, JdbcClient db, CcToolsProperties properties, ToolMetrics metrics,
            Clock clock) {
        this.catalogues = catalogues;
        this.kube = kube;
        this.db = db;
        this.config = properties.health();
        this.metrics = metrics;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(config.probeTimeout()).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Health.Snapshot snapshot() {
        return snapshot;
    }

    @Scheduled(fixedDelayString = "${parity-cc-tools.health.interval}", initialDelayString = "1s")
    public void scheduled() {
        if (config.enabled()) {
            collect();
        }
    }

    /** One round; returns the new snapshot. */
    public Health.Snapshot collect() {
        long started = System.nanoTime();
        Set<String> stopped = stoppedServices();
        List<Health.Service> services = new ArrayList<>();
        int instances = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore slots = new Semaphore(Math.max(1, config.parallelism()));
            for (String zone : catalogues.zones()) {
                Catalogue c = catalogues.zone(zone).orElseThrow();
                KubeApi api = kube.zone(zone).orElse(null);
                for (Catalogue.ServiceDef s : c.services()) {
                    boolean onPurpose = stopped.contains(zone + "|" + s.name());
                    if (api == null) {
                        services.add(new Health.Service(zone, s.name(), Health.State.UNKNOWN, Map.of(), List.of(),
                                "no Kubernetes API configured for this zone", s.callPath()));
                        continue;
                    }
                    List<JsonNode> pods;
                    try {
                        pods = api.pods(s.namespace(), s.selector());
                    } catch (KubeException e) {
                        services.add(new Health.Service(zone, s.name(), Health.State.UNKNOWN, Map.of(), List.of(),
                                "the pods can't be listed (" + e.code() + ")", s.callPath()));
                        continue;
                    }
                    List<Future<Health.Instance>> futures = new ArrayList<>();
                    for (JsonNode pod : pods) {
                        futures.add(pool.submit(() -> {
                            slots.acquire();
                            try {
                                return instance(zone, s, pod);
                            } finally {
                                slots.release();
                            }
                        }));
                    }
                    List<Health.Instance> list = new ArrayList<>();
                    for (Future<Health.Instance> f : futures) {
                        try {
                            list.add(f.get());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return snapshot;
                        } catch (java.util.concurrent.ExecutionException e) {
                            // A probe task can't fail (it catches its own errors); skip it.
                        }
                    }
                    list.sort(Comparator.comparingInt((Health.Instance i) -> -i.state().severity()).thenComparing(Health.Instance::pod));
                    instances += list.size();
                    services.add(new Health.Service(zone, s.name(), Health.worst(list, onPurpose), Health.counts(list), list,
                            null, s.callPath()));
                }
            }
        }
        services.sort(Comparator.comparingInt((Health.Service x) -> -x.worst().severity()).thenComparing(Health.Service::zone)
                .thenComparing(Health.Service::service));
        Health.Snapshot snap = new Health.Snapshot(clock.instant(), List.copyOf(services));
        snapshot = snap;
        log.atDebug().addKeyValue("event", "health.collected").addKeyValue("instances", instances)
                .addKeyValue("services", services.size()).addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0)
                .log("health snapshot taken");
        return snap;
    }

    private Set<String> stoppedServices() {
        try {
            return Set.copyOf(db.sql("SELECT zone || '|' || service FROM service_op WHERE outcome IS NULL AND driver = 'k8s-scale'")
                    .query(String.class).list());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private Health.Instance instance(String zone, Catalogue.ServiceDef s, JsonNode pod) {
        JsonNode meta = pod.path("metadata");
        JsonNode status = pod.path("status");
        String name = meta.path("name").asString("?");
        String phase = status.path("phase").asString("Unknown");
        boolean ready = false;
        for (JsonNode c : status.path("conditions")) {
            if ("Ready".equals(c.path("type").asString()) && "True".equals(c.path("status").asString())) {
                ready = true;
            }
        }
        int restarts = 0;
        String lastTermination = null;
        for (JsonNode cs : status.path("containerStatuses")) {
            restarts += cs.path("restartCount").asInt(0);
            String reason = cs.path("lastState").path("terminated").path("reason").asString(null);
            if (reason != null) {
                lastTermination = reason;
            }
        }
        String ip = status.path("podIP").asString(null);
        String rotation = meta.path("labels").path(KubeApi.ROTATION_LABEL).asString(null);
        boolean draining = "out".equals(rotation);
        Health.Probe livez;
        Health.Probe readyz;
        Map<String, Double> values = Map.of();
        if (ip == null || !"Running".equals(phase)) {
            livez = new Health.Probe(null, "no_pod_ip");
            readyz = new Health.Probe(null, "no_pod_ip");
        } else {
            livez = probe(zone, s, base(ip, s.port()) + s.livez(), "livez");
            readyz = probe(zone, s, base(ip, s.port()) + s.readyz(), "readyz");
            if (!s.metrics().isEmpty() && livez.answered()) {
                values = metrics(zone, s, base(ip, s.port()) + s.metricsPath());
            }
        }
        Health.State state = Health.derive(phase, ready, livez, readyz, draining);
        return new Health.Instance(zone, s.name(), name, status.path("hostIP").asString(pod.path("spec").path("nodeName").asString(null)),
                ip, phase, ready, restarts, lastTermination, livez, readyz, values, rotation, draining, state);
    }

    static String base(String ip, int port) {
        return "http://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port;
    }

    private Health.Probe probe(String zone, Catalogue.ServiceDef s, String url, String which) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(config.probeTimeout()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() != 200) {
                failed(zone, s, which, Integer.toString(r.statusCode()), null);
            }
            return new Health.Probe(r.statusCode(), null);
        } catch (IOException | IllegalArgumentException e) {
            failed(zone, s, which, null, e.getClass().getName());
            return new Health.Probe(null, kind(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Health.Probe(null, "interrupted");
        }
    }

    private Map<String, Double> metrics(String zone, Catalogue.ServiceDef s, String url) {
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(config.probeTimeout().multipliedBy(2))
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    return Map.of();
                }
                byte[] bytes = in.readNBytes(config.metricsMaxBytes());
                return PrometheusText.pick(new String(bytes, StandardCharsets.UTF_8), s.metrics());
            }
        } catch (IOException | IllegalArgumentException e) {
            failed(zone, s, "metrics", null, e.getClass().getName());
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    private void failed(String zone, Catalogue.ServiceDef s, String probe, String httpStatus, String errorKind) {
        metrics.probeFailure(s.name());
        log.atWarn().addKeyValue("event", "health.probe_failed").addKeyValue("zone", zone).addKeyValue("service_name", s.name())
                .addKeyValue("probe", probe).addKeyValue("status", httpStatus).addKeyValue("error_kind", errorKind)
                .log("a health probe failed");
    }

    static String kind(Exception e) {
        if (e instanceof java.net.http.HttpConnectTimeoutException || e instanceof java.net.http.HttpTimeoutException) {
            return "timeout";
        }
        if (e instanceof java.net.ConnectException) {
            return "refused";
        }
        return "unreachable";
    }

    /** The probe timeout (the dashboard says it). */
    public Duration probeTimeout() {
        return config.probeTimeout();
    }
}
