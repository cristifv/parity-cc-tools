package parity.cctools.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Kubernetes API stand-in (an HTTP double) for one namespace's pods, Services (as EndpointSlices),
 * deployments and their scale subresource, as much as parity-cc-tools uses. A Service's endpoints are its
 * pods matching its selector, {@code parity.io/rotation: in} included (the platform request), unless
 * {@code freezeEndpoints}: then they keep their last value (the endpoints controller is "slow"); a
 * deployment's status follows its spec unless {@code freezeWorkloads}. The same server answers the pods'
 * {@code /livez}, {@code /readyz} and {@code /metrics} (every pod's IP is 127.0.0.1). Every PATCH is
 * recorded.
 */
public final class KubeStandIn implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    final Map<String, ObjectNode> pods = new ConcurrentHashMap<>();
    final Map<String, Map<String, String>> services = new ConcurrentHashMap<>();
    final Map<String, ObjectNode> deployments = new ConcurrentHashMap<>();
    private final Map<String, ArrayNode> frozen = new ConcurrentHashMap<>();
    public final List<String> patches = new CopyOnWriteArrayList<>();
    public volatile boolean freezeEndpoints;
    public volatile boolean freezeWorkloads;
    public volatile String metricsText = "telephony_controller_sessions 0\n";
    private final AtomicLong version = new AtomicLong(100);

    public KubeStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    public void pod(String name, Map<String, String> labels, boolean ready) {
        ObjectNode p = JSON.createObjectNode();
        ObjectNode meta = p.putObject("metadata");
        meta.put("name", name).put("namespace", "parity-app").put("resourceVersion", Long.toString(version.incrementAndGet()));
        ObjectNode l = meta.putObject("labels");
        labels.forEach(l::put);
        ObjectNode st = p.putObject("status");
        st.put("phase", "Running").put("podIP", "127.0.0.1").put("hostIP", "10.0.0.9");
        st.putArray("conditions").addObject().put("type", "Ready").put("status", ready ? "True" : "False");
        st.putArray("containerStatuses").addObject().put("name", "app").put("restartCount", 0);
        pods.put(name, p);
    }

    public void removePod(String name) {
        pods.remove(name);
        version.incrementAndGet();
    }

    /** A pod restarts: its restart count goes up and its resourceVersion moves (a watch sees it). */
    public void restartPod(String name) {
        ObjectNode p = pods.get(name);
        ObjectNode cs = (ObjectNode) p.path("status").path("containerStatuses").get(0);
        cs.put("restartCount", cs.path("restartCount").asInt() + 1);
        ((ObjectNode) p.path("metadata")).put("resourceVersion", Long.toString(version.incrementAndGet()));
    }

    public void service(String name, Map<String, String> selector) {
        services.put(name, Map.copyOf(selector));
    }

    public void deployment(String name, int replicas) {
        ObjectNode d = JSON.createObjectNode();
        d.putObject("metadata").put("name", name).put("namespace", "parity-app");
        d.putObject("spec").put("replicas", replicas);
        d.putObject("status").put("replicas", replicas).put("readyReplicas", replicas);
        deployments.put(name, d);
    }

    public String label(String pod, String key) {
        ObjectNode p = pods.get(pod);
        return p == null ? null : p.path("metadata").path("labels").path(key).asString(null);
    }

    public int specReplicas(String deployment) {
        return deployments.get(deployment).path("spec").path("replicas").asInt();
    }

    /** The workloads catch up with their spec (when frozen). */
    public void settleWorkloads() {
        for (ObjectNode d : deployments.values()) {
            int n = d.path("spec").path("replicas").asInt();
            ((ObjectNode) d.path("status")).put("replicas", n).put("readyReplicas", n);
        }
    }

    public void unfreezeEndpoints() {
        frozen.clear();
        freezeEndpoints = false;
    }

    static boolean matches(JsonNode pod, Map<String, String> selector) {
        JsonNode labels = pod.path("metadata").path("labels");
        for (var e : selector.entrySet()) {
            if (!e.getValue().equals(labels.path(e.getKey()).asString(null))) {
                return false;
            }
        }
        return true;
    }

    static Map<String, String> selector(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) {
            return m;
        }
        for (String part : s.split(",", -1)) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                m.put(kv[0], kv[1]);
            }
        }
        return m;
    }

    ArrayNode slices(String service) {
        if (freezeEndpoints && frozen.containsKey(service)) {
            return frozen.get(service).deepCopy();
        }
        ArrayNode items = JSON.createArrayNode();
        Map<String, String> sel = services.get(service);
        if (sel != null) {
            ObjectNode slice = items.addObject();
            slice.putObject("metadata").put("name", service + "-abc").putObject("labels").put("kubernetes.io/service-name", service);
            ArrayNode eps = slice.putArray("endpoints");
            for (ObjectNode p : pods.values()) {
                if (matches(p, sel)) {
                    ObjectNode ep = eps.addObject();
                    ep.putArray("addresses").add("10.42.0." + Math.abs(p.path("metadata").path("name").asString().hashCode() % 250));
                    ep.putObject("conditions").put("ready", true);
                    ep.putObject("targetRef").put("kind", "Pod").put("name", p.path("metadata").path("name").asString());
                }
            }
        }
        if (freezeEndpoints) {
            frozen.putIfAbsent(service, items.deepCopy());
        }
        return items;
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String query = x.getRequestURI().getRawQuery();
        Map<String, String> q = new LinkedHashMap<>();
        if (query != null) {
            for (String kv : query.split("&", -1)) {
                String[] p = kv.split("=", 2);
                q.put(p[0], p.length == 2 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
        }
        String method = x.getRequestMethod();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (path.equals("/livez") || path.equals("/readyz")) {
                send(x, 200, "{\"status\":\"UP\"}");
                return;
            }
            if (path.equals("/metrics")) {
                send(x, 200, metricsText);
                return;
            }
            if ("true".equals(q.get("watch"))) {
                // One MODIFIED event when anything changed since the watch's resourceVersion (the client then
                // observes for itself), else nothing; the watch ends at once and the client lists again.
                long from = q.get("resourceVersion") == null ? version.get() : Long.parseLong(q.get("resourceVersion"));
                send(x, 200, version.get() > from ? "{\"type\":\"MODIFIED\",\"object\":{}}\n" : "");
                return;
            }
            String[] seg = path.split("/", -1);
            if (path.startsWith("/api/v1/namespaces/") && seg.length >= 6 && seg[5].equals("pods")) {
                if (seg.length == 6) {
                    ArrayNode items = JSON.createArrayNode();
                    Map<String, String> sel = selector(q.get("labelSelector"));
                    pods.values().stream().filter(p -> matches(p, sel)).forEach(p -> items.add(p.deepCopy()));
                    send(x, 200, list(items));
                    return;
                }
                ObjectNode p = pods.get(seg[6]);
                if (p == null) {
                    send(x, 404, "{\"kind\":\"Status\",\"code\":404}");
                    return;
                }
                if ("PATCH".equals(method)) {
                    patches.add("pod " + seg[6] + " " + body);
                    JsonNode labels = JSON.readTree(body).path("metadata").path("labels");
                    ObjectNode l = (ObjectNode) p.path("metadata").path("labels");
                    for (var e : labels.properties()) {
                        l.put(e.getKey(), e.getValue().asString());
                    }
                    ((ObjectNode) p.path("metadata")).put("resourceVersion", Long.toString(version.incrementAndGet()));
                }
                send(x, 200, p.toString());
                return;
            }
            if (path.startsWith("/apis/discovery.k8s.io/v1/namespaces/") && path.endsWith("/endpointslices")) {
                String svc = selector(q.get("labelSelector")).get("kubernetes.io/service-name");
                send(x, 200, list(svc == null ? JSON.createArrayNode() : slices(svc)));
                return;
            }
            if (path.startsWith("/apis/apps/v1/namespaces/") && seg.length >= 7 && seg[6].equals("deployments")) {
                if (seg.length == 7) {
                    ArrayNode items = JSON.createArrayNode();
                    deployments.values().forEach(d -> items.add(d.deepCopy()));
                    send(x, 200, list(items));
                    return;
                }
                ObjectNode d = deployments.get(seg[7]);
                if (d == null) {
                    send(x, 404, "{\"kind\":\"Status\",\"code\":404}");
                    return;
                }
                if (seg.length == 9 && seg[8].equals("scale")) {
                    if ("PATCH".equals(method)) {
                        patches.add("scale " + seg[7] + " " + body);
                        int n = JSON.readTree(body).path("spec").path("replicas").asInt();
                        ((ObjectNode) d.path("spec")).put("replicas", n);
                        if (!freezeWorkloads) {
                            settleWorkloads();
                        }
                    }
                    ObjectNode scale = JSON.createObjectNode();
                    scale.putObject("spec").put("replicas", d.path("spec").path("replicas").asInt());
                    scale.putObject("status").put("replicas", d.path("status").path("replicas").asInt());
                    send(x, 200, scale.toString());
                    return;
                }
                send(x, 200, d.toString());
                return;
            }
            send(x, 404, "{\"kind\":\"Status\",\"code\":404}");
        } catch (RuntimeException e) {
            send(x, 500, "{}");
        }
    }

    private String list(ArrayNode items) {
        ObjectNode l = JSON.createObjectNode();
        l.putObject("metadata").put("resourceVersion", Long.toString(version.get()));
        l.set("items", items);
        return l.toString();
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
        }
        x.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
