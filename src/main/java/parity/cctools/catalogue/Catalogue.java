package parity.cctools.catalogue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import parity.cctools.lifecycle.ConfigurationRefusedException;

/**
 * The service catalogue (PLAN.md §6.3): the services the health dashboard shows and service control may
 * touch, as {@code parity-cc-tools.services} and {@code parity-cc-tools.media-nodes} in a YAML file that
 * parity-deploy renders (a ConfigMap, imported through {@code spring.config.import}). Nothing outside it
 * can be disabled: a service, an instance or a media node is looked up here first.
 */
public final class Catalogue {

    /** What a service's pods are run by. */
    public enum Kind { DEPLOYMENT, STATEFULSET }

    /** The service-control drivers (PLAN.md §6.4). */
    public enum Driver {
        K8S_ROTATION("k8s-rotation"),
        MEDIA_NODE("media-node"),
        K8S_SCALE("k8s-scale");

        private final String wire;

        Driver(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Driver ofWire(String s) {
            for (Driver d : values()) {
                if (d.wire.equals(s)) {
                    return d;
                }
            }
            throw new IllegalArgumentException("unknown driver");
        }
    }

    /** One metric to show: a Prometheus name, optionally filtered by labels (exact values). */
    public record MetricDef(String name, Map<String, String> labels, String title) {
        public MetricDef {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    /**
     * A service: its pods ({@code namespace} + {@code selector}), the workload that runs them, the
     * Kubernetes Service whose EndpointSlices show rotation, its management port and paths, the metrics
     * to show, the drivers allowed, and whether it is on the call path (stopping it needs force).
     */
    public record ServiceDef(
            String name,
            String namespace,
            String selector,
            Kind kind,
            String workload,
            String k8sService,
            int port,
            String livez,
            String readyz,
            String metricsPath,
            List<MetricDef> metrics,
            Set<Driver> drivers,
            boolean callPath,
            Integer defaultReplicas) {

        public ServiceDef {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            drivers = drivers == null ? Set.of() : Set.copyOf(drivers);
            livez = livez == null ? "/livez" : livez;
            readyz = readyz == null ? "/readyz" : readyz;
            metricsPath = metricsPath == null ? "/metrics" : metricsPath;
            kind = kind == null ? Kind.DEPLOYMENT : kind;
        }

        public boolean allows(Driver d) {
            return drivers.contains(d);
        }
    }

    /** One opensips dispatcher destination: its set and address (as {@code ds_list} prints it). */
    public record DispatcherTarget(int set, String address) {}

    /** Where a media node's active-call gauge is read: pods, port, path, metric (+ label filter). */
    public record GaugeDef(String podSelector, int port, String path, String metric, Map<String, String> labels) {
        public GaugeDef {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            path = path == null ? "/metrics" : path;
        }
    }

    /**
     * A media node (a freeswitch + parity-ms + telephony-controller set on one host): its pods, the
     * dispatcher destinations that send it calls, and the gauge that reaches 0 once its calls ended.
     */
    public record MediaNodeDef(String name, String namespace, String podSelector, List<DispatcherTarget> dispatcher,
            GaugeDef gauge, boolean callPath) {
        public MediaNodeDef {
            dispatcher = dispatcher == null ? List.of() : List.copyOf(dispatcher);
        }
    }

    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern SELECTOR = Pattern.compile("^[A-Za-z0-9./_-]+(=|==|!=)[A-Za-z0-9._-]*(,[A-Za-z0-9./_-]+(=|==|!=)[A-Za-z0-9._-]*)*$");

    private final Map<String, ServiceDef> services = new LinkedHashMap<>();
    private final Map<String, MediaNodeDef> mediaNodes = new LinkedHashMap<>();

    public Catalogue(List<ServiceDef> services, List<MediaNodeDef> mediaNodes) {
        for (ServiceDef s : services) {
            check(s.name());
            check(s.namespace());
            if (s.selector() == null || !SELECTOR.matcher(s.selector()).matches()) {
                throw new ConfigurationRefusedException("a catalogue service has no valid label selector");
            }
            if (s.port() < 1 || s.port() > 65535) {
                throw new ConfigurationRefusedException("a catalogue service has no valid management port");
            }
            if (s.allows(Driver.K8S_SCALE) && (s.workload() == null || !NAME.matcher(s.workload()).matches())) {
                throw new ConfigurationRefusedException("a catalogue service with k8s-scale needs its workload name");
            }
            if (s.allows(Driver.K8S_ROTATION) && (s.k8sService() == null || !NAME.matcher(s.k8sService()).matches())) {
                throw new ConfigurationRefusedException("a catalogue service with k8s-rotation needs its Kubernetes Service name");
            }
            if (s.allows(Driver.MEDIA_NODE)) {
                throw new ConfigurationRefusedException("media-node is a driver of parity-cc-tools.media-nodes, not of a service");
            }
            if (this.services.putIfAbsent(s.name(), s) != null) {
                throw new ConfigurationRefusedException("two catalogue services share a name");
            }
        }
        for (MediaNodeDef m : mediaNodes) {
            check(m.name());
            check(m.namespace());
            if (m.podSelector() == null || !SELECTOR.matcher(m.podSelector()).matches()) {
                throw new ConfigurationRefusedException("a media node has no valid pod selector");
            }
            if (m.dispatcher().isEmpty()) {
                throw new ConfigurationRefusedException("a media node lists no dispatcher destination");
            }
            if (m.gauge() == null || m.gauge().metric() == null || m.gauge().podSelector() == null
                    || !SELECTOR.matcher(m.gauge().podSelector()).matches()) {
                throw new ConfigurationRefusedException("a media node has no active-call gauge");
            }
            if (this.mediaNodes.putIfAbsent(m.name(), m) != null) {
                throw new ConfigurationRefusedException("two media nodes share a name");
            }
        }
    }

    private static void check(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ConfigurationRefusedException("a catalogue name or namespace isn't a DNS label");
        }
    }

    public List<ServiceDef> services() {
        return List.copyOf(services.values());
    }

    public List<MediaNodeDef> mediaNodes() {
        return List.copyOf(mediaNodes.values());
    }

    public Optional<ServiceDef> service(String name) {
        return Optional.ofNullable(name == null ? null : services.get(name));
    }

    public Optional<MediaNodeDef> mediaNode(String name) {
        return Optional.ofNullable(name == null ? null : mediaNodes.get(name));
    }

    /** The namespaces the catalogue names (the Kubernetes RBAC request lists them). */
    public Set<String> namespaces() {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        services.values().forEach(s -> out.add(s.namespace()));
        mediaNodes.values().forEach(m -> out.add(m.namespace()));
        return out;
    }
}
