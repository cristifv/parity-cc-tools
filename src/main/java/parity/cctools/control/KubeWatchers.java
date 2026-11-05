package parity.cctools.control;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.k8s.KubeApi;
import parity.cctools.k8s.KubeApis;
import parity.cctools.k8s.KubeException;

/**
 * Kubernetes watches that wake the service-control sagas of a namespace (PLAN.md §6.4: confirmation comes
 * from the watch): one per (zone, namespace, resource) an open saga needs, from a list's
 * {@code resourceVersion}; every event wakes that namespace's open sagas, which then observe for
 * themselves. A watch the server ends is listed and watched again; one that fails backs off (at most
 * 5 s). The watch only wakes: the evaluation decides.
 */
final class KubeWatchers {

    /** Called on every watch event (bookmarks excepted) of a watched (zone, namespace, resource). */
    interface Wake {
        void wake(String zone, String namespace, String resource);
    }

    private final KubeApis kube;
    private final Wake wake;
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    KubeWatchers(KubeApis kube, Wake wake) {
        this.kube = kube;
        this.wake = wake;
    }

    static String plural(Catalogue.Kind kind) {
        return kind == Catalogue.Kind.STATEFULSET ? "statefulsets" : "deployments";
    }

    /** Keeps exactly these watches ({@code zone|namespace|resource|selector}, selector optional) running. */
    void ensure(Set<String> wanted) {
        for (var e : java.util.List.copyOf(running.entrySet())) {
            if (!wanted.contains(e.getKey())) {
                e.getValue().set(false);
                running.remove(e.getKey(), e.getValue());
            }
        }
        for (String key : wanted) {
            String[] p = key.split("\\|", 4);
            KubeApi api = kube.zone(p[0]).orElse(null);
            if (api == null || p.length < 3) {
                continue;
            }
            String selector = p.length == 4 ? p[3] : null;
            running.computeIfAbsent(key, k -> {
                AtomicBoolean flag = new AtomicBoolean(true);
                Thread.ofVirtual().name("k8s-watch-" + p[1] + "-" + p[2]).start(() -> loop(api, p[0], p[1], p[2], selector, flag));
                return flag;
            });
        }
    }

    private void loop(KubeApi api, String zone, String namespace, String resource, String selector, AtomicBoolean flag) {
        String base = switch (resource) {
            case "endpointslices" -> "/apis/discovery.k8s.io/v1/namespaces/" + namespace + "/endpointslices";
            case "statefulsets", "deployments" -> "/apis/apps/v1/namespaces/" + namespace + "/" + resource;
            default -> "/api/v1/namespaces/" + namespace + "/pods";
        };
        String path = selector == null ? base
                : base + "?labelSelector=" + java.net.URLEncoder.encode(selector, java.nio.charset.StandardCharsets.UTF_8);
        Duration backoff = Duration.ofMillis(500);
        while (flag.get()) {
            long started = System.nanoTime();
            try {
                String rv = KubeApi.resourceVersion(api.get(path, resource + ".list"));
                api.watch(path, rv, ev -> {
                    if (!"BOOKMARK".equals(ev.path("type").asString(""))) {
                        wake.wake(zone, namespace, resource);
                    }
                }, resource + ".watch");
                backoff = Duration.ofMillis(500);
            } catch (KubeException e) {
                backoff = backoff.multipliedBy(2).compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : backoff.multipliedBy(2);
            }
            // A watch that ended at once (a proxy, a stand-in) isn't re-opened in a tight loop.
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            long pause = Math.max(backoff.toMillis() - elapsedMs, elapsedMs < 1000 ? 1000 - elapsedMs : 0);
            if (pause > 0 && flag.get()) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
