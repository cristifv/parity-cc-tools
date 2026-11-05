package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.catalogue.Catalogue.Driver;
import parity.cctools.catalogue.Catalogues;
import parity.cctools.control.Drivers;
import parity.cctools.control.ServiceOps;
import parity.cctools.health.Health;
import parity.cctools.health.HealthCollector;
import parity.cctools.k8s.KubeApi;
import parity.cctools.k8s.KubeApis;
import parity.cctools.k8s.KubeException;
import parity.cctools.mi.OpensipsMi;
import parity.cctools.mi.OpensipsMis;
import parity.cctools.ops.Action;
import parity.cctools.ops.Pending;
import parity.cctools.ops.PendingActions;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Services (PLAN.md §4.7, §6.4): every catalogue service instance and media node with its rotation state
 * and open saga; cordon / uncordon an instance, take a media node out of / back into rotation (its
 * dispatcher entries on every opensips SBC node of the zone), stop / start a whole service, cancel a
 * disabling saga. The
 * review shows what will be touched and what else is ready; the guards (last ready instance, last media
 * node in rotation, a call-path service) raise the tier to High and ask for {@code force}.
 */
@Controller
public class ServicesController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Catalogues catalogues;
    private final HealthCollector health;
    private final ServiceOps ops;
    private final KubeApis kube;
    private final OpensipsMis mis;
    private final Reviews reviews;
    private final PendingActions pendings;
    private final parity.cctools.control.ServiceControl control;

    public ServicesController(Catalogues catalogues, HealthCollector health, ServiceOps ops, KubeApis kube, OpensipsMis mis,
            Reviews reviews, PendingActions pendings, parity.cctools.control.ServiceControl control) {
        this.control = control;
        this.catalogues = catalogues;
        this.health = health;
        this.ops = ops;
        this.kube = kube;
        this.mis = mis;
        this.reviews = reviews;
        this.pendings = pendings;
    }

    /** One media node on the page: its definition, the dispatcher states read now, its open saga. */
    public record MediaRow(String zone, Catalogue.MediaNodeDef node, Map<String, String> dispatcher, String error, ServiceOps.Op op) {}

    /** One service on the page: definition, health instances, open sagas by instance. */
    public record ServiceRow(String zone, Catalogue.ServiceDef def, Health.Service health, Map<String, ServiceOps.Op> ops) {}

    @GetMapping("/services")
    @Requires(Capability.HEALTH_READ)
    public String list(Model model) {
        Health.Snapshot snap = health.snapshot();
        List<ServiceOps.Op> open = ops.open();
        List<ServiceRow> services = new ArrayList<>();
        List<MediaRow> media = new ArrayList<>();
        for (String zone : catalogues.zones()) {
            Catalogue c = catalogues.zone(zone).orElseThrow();
            for (Catalogue.ServiceDef s : c.services()) {
                Health.Service h = snap.services().stream().filter(x -> x.zone().equals(zone) && x.service().equals(s.name())).findFirst()
                        .orElse(null);
                Map<String, ServiceOps.Op> byInstance = new LinkedHashMap<>();
                open.stream().filter(o -> o.zone().equals(zone) && o.service().equals(s.name())).forEach(o -> byInstance.put(o.instance(), o));
                services.add(new ServiceRow(zone, s, h, byInstance));
            }
            Map<String, Map<String, OpensipsMi.Destination>> bySbc = new LinkedHashMap<>();
            String error = null;
            List<OpensipsMi> sbcs = mis.zone(zone);
            if (!c.mediaNodes().isEmpty()) {
                if (sbcs.isEmpty()) {
                    error = "no opensips SBC node is configured for this zone";
                }
                for (OpensipsMi sbc : sbcs) {
                    try {
                        bySbc.put(sbc.name(), sbc.dsList());
                    } catch (OpensipsMi.MiException e) {
                        error = "an opensips SBC node's management interface didn't answer (" + sbc.name() + ")";
                    }
                }
            }
            for (Catalogue.MediaNodeDef m : c.mediaNodes()) {
                Map<String, String> states = new LinkedHashMap<>();
                for (OpensipsMi sbc : sbcs) {
                    Map<String, OpensipsMi.Destination> ds = bySbc.get(sbc.name());
                    for (Catalogue.DispatcherTarget t : m.dispatcher()) {
                        String k = OpensipsMi.key(t.set(), t.address());
                        states.put(sbc.name() + " " + k, ds == null ? "unknown" : ds.containsKey(k) ? ds.get(k).state() : "missing");
                    }
                }
                ServiceOps.Op op = open.stream().filter(o -> o.zone().equals(zone) && o.driver() == Driver.MEDIA_NODE
                        && o.instance().equals(m.name())).findFirst().orElse(null);
                if (op != null && op.disable() && states.values().stream().anyMatch("active"::equals)) {
                    // A read-back that doesn't match the held state (an SBC node restarted): the saga sets it again.
                    control.wake(op.id());
                }
                media.add(new MediaRow(zone, m, states, error, op));
            }
        }
        model.addAttribute("services", services);
        model.addAttribute("media", media);
        model.addAttribute("recent", ops.recent(20));
        model.addAttribute("snapshotAt", snap.at());
        return "pages/services";
    }

    /** {@code action}: cordon, uncordon, stop, start, media_out, media_in, cancel. */
    @PostMapping("/services/review")
    @Requires(Capability.SERVICE_CONTROL)
    public String review(@RequestParam String zone, @RequestParam String action, @RequestParam(required = false) String service,
            @RequestParam(required = false) String instance, @RequestParam(required = false) String sagaId, HttpServletRequest request,
            Model model) {
        Catalogue c = catalogues.zone(zone).orElse(null);
        if (c == null) {
            return problem(model, "Unknown zone.");
        }
        KubeApi api = kube.zone(zone).orElse(null);
        ObjectNode before = JSON.createObjectNode();
        ObjectNode after = JSON.createObjectNode();
        Map<String, String> params = new LinkedHashMap<>();
        Action a;
        String title;
        String expected;
        String namespace;
        String target;
        Driver driver;
        boolean lastReady = false;
        boolean callPath = false;
        String serviceName = service;
        String targetZone = zone;
        try {
            switch (action) {
                case "cordon", "uncordon" -> {
                    Catalogue.ServiceDef s = c.service(service).orElse(null);
                    if (s == null || !s.allows(Driver.K8S_ROTATION) || api == null || instance == null || !Pages.K8S_NAME.matcher(instance).matches()) {
                        return problem(model, "This service can't be cordoned here (not in the catalogue with k8s-rotation, or no Kubernetes API).");
                    }
                    List<JsonNode> pods = api.pods(s.namespace(), s.selector());
                    JsonNode pod = pods.stream().filter(p -> instance.equals(p.path("metadata").path("name").asString())).findFirst().orElse(null);
                    if (pod == null) {
                        return problem(model, "That pod isn't one of the service's pods.");
                    }
                    long readyOthers = pods.stream().filter(p -> !instance.equals(p.path("metadata").path("name").asString()))
                            .filter(ServicesController::ready).filter(p -> !"out".equals(rotation(p))).count();
                    before.put("pod", instance).put("rotation", rotation(pod) == null ? "in" : rotation(pod)).put("ready_others", readyOthers);
                    after.put("pod", instance).put("rotation", "cordon".equals(action) ? "out" : "in").put("ready_others", readyOthers);
                    a = "cordon".equals(action) ? Action.SERVICE_CORDON : Action.SERVICE_UNCORDON;
                    lastReady = "cordon".equals(action) && readyOthers == 0;
                    title = ("cordon".equals(action) ? "Cordon " : "Uncordon ") + instance + " (" + s.name() + ")";
                    expected = instance;
                    namespace = s.namespace();
                    target = instance;
                    driver = Driver.K8S_ROTATION;
                }
                case "stop", "start" -> {
                    Catalogue.ServiceDef s = c.service(service).orElse(null);
                    if (s == null || !s.allows(Driver.K8S_SCALE) || api == null) {
                        return problem(model, "This service can't be stopped here (not in the catalogue with k8s-scale, or no Kubernetes API).");
                    }
                    int replicas = api.scale(s.namespace(), s.kind().name(), s.workload()).path("spec").path("replicas").asInt(0);
                    before.put("workload", s.workload()).put("replicas", replicas).put("call_path", s.callPath());
                    after.put("workload", s.workload()).put("replicas", "stop".equals(action) ? 0 : Math.max(1, replicas)).put("call_path", s.callPath());
                    a = "stop".equals(action) ? Action.SERVICE_STOP : Action.SERVICE_START;
                    callPath = s.callPath();
                    title = ("stop".equals(action) ? "Stop the whole service " : "Start the service ") + s.name();
                    expected = s.name();
                    namespace = s.namespace();
                    target = "*";
                    driver = Driver.K8S_SCALE;
                }
                case "media_out", "media_in" -> {
                    Catalogue.MediaNodeDef m = c.mediaNode(instance).orElse(null);
                    if (m == null || mis.zone(zone).isEmpty()) {
                        return problem(model, "That media node isn't in the catalogue, or the zone has no opensips SBC node configured.");
                    }
                    List<ServiceOps.Op> open = ops.open();
                    long inRotationOthers = c.mediaNodes().stream().filter(x -> !x.name().equals(m.name()))
                            .filter(x -> open.stream().noneMatch(o -> o.zone().equals(zone) && o.driver() == Driver.MEDIA_NODE
                                    && o.instance().equals(x.name()) && o.disable())).count();
                    before.put("node", m.name()).put("dispatcher", "media_out".equals(action) ? "active" : "inactive")
                            .put("in_rotation_others", inRotationOthers).put("sbc_nodes", mis.zone(zone).size());
                    after.put("node", m.name()).put("dispatcher", "media_out".equals(action) ? "inactive, then drained to 0 calls" : "active")
                            .put("in_rotation_others", inRotationOthers).put("sbc_nodes", mis.zone(zone).size());
                    a = "media_out".equals(action) ? Action.MEDIA_NODE_OUT : Action.MEDIA_NODE_IN;
                    lastReady = "media_out".equals(action) && inRotationOthers == 0;
                    callPath = true;
                    title = ("media_out".equals(action) ? "Take media node " : "Put media node ") + m.name()
                            + ("media_out".equals(action) ? " out of rotation" : " back into rotation") + " on every SBC node";
                    expected = m.name();
                    namespace = m.namespace();
                    target = m.name();
                    driver = Driver.MEDIA_NODE;
                    serviceName = "media-node";
                }
                case "cancel" -> {
                    ServiceOps.Op o = ops.find(sagaId).orElse(null);
                    if (o == null || !o.open() || !Drivers.cancellable(o.driver(), o.state())) {
                        return problem(model, "Only a saga that is still disabling can be cancelled; enable the target instead.");
                    }
                    before.put("state", o.state());
                    after.put("state", "reverting, then cancelled");
                    a = Action.SERVICE_OP_CANCEL;
                    title = "Cancel " + o.direction() + " of " + o.service() + " " + o.instance();
                    expected = "*".equals(o.instance()) ? o.service() : o.instance();
                    namespace = o.namespace();
                    target = o.instance();
                    driver = o.driver();
                    serviceName = o.service();
                    targetZone = o.zone();
                }
                default -> {
                    return "redirect:/services";
                }
            }
        } catch (KubeException e) {
            return problem(model, "The Kubernetes API didn't answer (" + e.code() + "): nothing was changed.");
        }
        params.put("driver", driver.wire());
        params.put("namespace", namespace);
        Pending p = reviews.pending(a, new RiskPolicy.Facts(false, false, lastReady, callPath),
                new Reviews.Target(targetZone, null, null, serviceName, target, null), "cancel".equals(action) ? sagaId : null, expected, params,
                before, after, title, "/services");
        pendings.put(request.getSession(), p);
        return ReviewPage.render(model, p, null, null, Capability.SERVICE_CONTROL);
    }

    static boolean ready(JsonNode pod) {
        for (JsonNode c : pod.path("status").path("conditions")) {
            if ("Ready".equals(c.path("type").asString()) && "True".equals(c.path("status").asString())) {
                return true;
            }
        }
        return false;
    }

    static String rotation(JsonNode pod) {
        return pod.path("metadata").path("labels").path(KubeApi.ROTATION_LABEL).asString(null);
    }

    private static String problem(Model model, String message) {
        model.addAttribute("title", "Can't review this");
        model.addAttribute("message", message);
        model.addAttribute("back", "/services");
        return "pages/problem";
    }
}
