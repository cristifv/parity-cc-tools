package parity.cctools.k8s;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.lifecycle.ConfigurationRefusedException;
import parity.cctools.obs.ToolMetrics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One zone's Kubernetes API over {@code java.net.http} (PLAN.md §0: no client library): the pod's service
 * account token (read from its file on every call: projected tokens rotate) and the cluster CA from the
 * standard paths; a plain URL in tests. Only what PLAN.md §6.4's RBAC grants: list/get/watch pods,
 * endpointslices, deployments, statefulsets; patch a pod's {@code parity.io/rotation} label; get/patch the
 * scale subresource. Nothing is cached on disk.
 */
public class KubeApi {

    private static final Logger log = LoggerFactory.getLogger(KubeApi.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String ROTATION_LABEL = "parity.io/rotation";

    private final String zone;
    private final URI api;
    private final String tokenFile;
    private final Duration timeout;
    private final Duration watchTimeout;
    private final HttpClient http;
    private final ToolMetrics metrics;

    public KubeApi(String zone, CcToolsProperties.Kubernetes k, ToolMetrics metrics) {
        this.zone = zone;
        this.api = k.apiUrl();
        this.tokenFile = k.tokenFile();
        this.timeout = k.requestTimeout();
        this.watchTimeout = k.watchTimeout();
        this.metrics = metrics;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER);
        if ("https".equals(api.getScheme()) && k.caFile() != null && !k.caFile().isBlank() && Files.exists(Path.of(k.caFile()))) {
            b.sslContext(trusting(Path.of(k.caFile())));
        }
        this.http = b.build();
    }

    public String zone() {
        return zone;
    }

    private static SSLContext trusting(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigurationRefusedException("the Kubernetes CA file can't be read");
        }
    }

    private HttpRequest.Builder request(String path, Duration t) {
        HttpRequest.Builder b = HttpRequest.newBuilder(api.resolve(path)).timeout(t).header("Accept", "application/json");
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path p = Path.of(tokenFile);
            if (Files.isReadable(p)) {
                try {
                    b.header("Authorization", "Bearer " + Files.readString(p, StandardCharsets.UTF_8).strip());
                } catch (IOException e) {
                    throw new KubeException(0, e);
                }
            }
        }
        return b;
    }

    /** GET a path; its JSON, or {@link KubeException}. */
    public JsonNode get(String path, String op) {
        return send(request(path, timeout).GET().build(), op);
    }

    /** A JSON merge patch. */
    public JsonNode patch(String path, String mergePatch, String op) {
        return send(request(path, timeout).header("Content-Type", "application/merge-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mergePatch)).build(), op);
    }

    private JsonNode send(HttpRequest req, String op) {
        HttpResponse<byte[]> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            failed(op, 0, e);
            throw new KubeException(0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KubeException(0, e);
        }
        if (r.statusCode() / 100 != 2) {
            KubeException e = new KubeException(r.statusCode(), null);
            failed(op, r.statusCode(), e);
            throw e;
        }
        try {
            return JSON.readTree(r.body());
        } catch (RuntimeException e) {
            failed(op, r.statusCode(), e);
            throw new KubeException(r.statusCode(), e);
        }
    }

    private void failed(String op, int status, Exception e) {
        metrics.k8sFailure(op);
        log.atWarn().addKeyValue("event", "k8s.call_failed").addKeyValue("op", op).addKeyValue("zone", zone)
                .addKeyValue("error_code", status == 0 ? "unreachable" : "http_" + status)
                .addKeyValue("error_kind", e.getClass().getName()).log("a Kubernetes API call failed");
    }

    static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public List<JsonNode> pods(String namespace, String selector) {
        return items(get("/api/v1/namespaces/" + namespace + "/pods?labelSelector=" + q(selector), "pods.list"));
    }

    /** The pod, or null when it doesn't exist. */
    public JsonNode pod(String namespace, String name) {
        try {
            return get("/api/v1/namespaces/" + namespace + "/pods/" + name, "pods.get");
        } catch (KubeException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** Sets the pod's rotation label ({@code in} or {@code out}): an absolute value, so a repeat changes nothing. */
    public JsonNode setRotation(String namespace, String pod, String value) {
        String patch = "{\"metadata\":{\"labels\":{\"" + ROTATION_LABEL + "\":\"" + ("out".equals(value) ? "out" : "in") + "\"}}}";
        return patch("/api/v1/namespaces/" + namespace + "/pods/" + pod, patch, "pods.patch_rotation");
    }

    public List<JsonNode> endpointSlices(String namespace, String service) {
        return items(get("/apis/discovery.k8s.io/v1/namespaces/" + namespace + "/endpointslices?labelSelector="
                + q("kubernetes.io/service-name=" + service), "endpointslices.list"));
    }

    static String plural(String kind) {
        return "statefulset".equalsIgnoreCase(kind) ? "statefulsets" : "deployments";
    }

    /** The workload, or null when it doesn't exist. */
    public JsonNode workload(String namespace, String kind, String name) {
        try {
            return get("/apis/apps/v1/namespaces/" + namespace + "/" + plural(kind) + "/" + name, plural(kind) + ".get");
        } catch (KubeException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    public JsonNode scale(String namespace, String kind, String name) {
        return get("/apis/apps/v1/namespaces/" + namespace + "/" + plural(kind) + "/" + name + "/scale", plural(kind) + ".scale_get");
    }

    /** Sets the replica count through the scale subresource: an absolute value. */
    public JsonNode setReplicas(String namespace, String kind, String name, int replicas) {
        return patch("/apis/apps/v1/namespaces/" + namespace + "/" + plural(kind) + "/" + name + "/scale",
                "{\"spec\":{\"replicas\":" + replicas + "}}", plural(kind) + ".scale_patch");
    }

    /**
     * Watches a list path ({@code ...?labelSelector=...}) from {@code resourceVersion}: each event's object
     * goes to {@code onEvent} until the server ends the watch ({@code watch-timeout}) or the call fails;
     * the caller lists and watches again (a list refreshes; the watch only wakes).
     */
    public void watch(String listPath, String resourceVersion, Consumer<JsonNode> onEvent, String op) {
        String sep = listPath.contains("?") ? "&" : "?";
        String path = listPath + sep + "watch=true&allowWatchBookmarks=true&timeoutSeconds=" + watchTimeout.toSeconds()
                + (resourceVersion == null ? "" : "&resourceVersion=" + q(resourceVersion));
        HttpRequest req = request(path, watchTimeout.plusSeconds(10)).GET().build();
        HttpResponse<Stream<String>> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            failed(op, 0, e);
            throw new KubeException(0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KubeException(0, e);
        }
        if (r.statusCode() / 100 != 2) {
            r.body().close();
            KubeException e = new KubeException(r.statusCode(), null);
            failed(op, r.statusCode(), e);
            throw e;
        }
        try (Stream<String> lines = r.body()) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    onEvent.accept(JSON.readTree(line));
                } catch (RuntimeException e) {
                    // One unreadable line: the next list refreshes anyway.
                }
            });
        }
    }

    static List<JsonNode> items(JsonNode list) {
        List<JsonNode> out = new ArrayList<>();
        list.path("items").forEach(out::add);
        return out;
    }

    /** The list's resourceVersion (where a watch starts). */
    public static String resourceVersion(JsonNode list) {
        return list.path("metadata").path("resourceVersion").asString(null);
    }
}
