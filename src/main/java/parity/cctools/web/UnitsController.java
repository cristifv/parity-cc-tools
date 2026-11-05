package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.ops.Action;
import parity.cctools.ops.Pending;
import parity.cctools.ops.PendingActions;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.ops.RoutingActions;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deployment units and rollouts (PLAN.md §4.4, §4.5): per zone, every unit's role, release, readiness,
 * health and tenant count, with drain, release (a failed unit) and start-a-rollout (an empty unit running
 * a release); rollouts with their rings and advance (typed: the next ring's name, {@code expected_ring}),
 * stop, resume and roll back.
 */
@Controller
public class UnitsController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RsClient rs;
    private final Reviews reviews;
    private final PendingActions pendings;
    private final TenantsController tenants;

    public UnitsController(RsClient rs, Reviews reviews, PendingActions pendings, TenantsController tenants) {
        this.rs = rs;
        this.reviews = reviews;
        this.pendings = pendings;
        this.tenants = tenants;
    }

    @GetMapping("/units")
    @Requires(Capability.ROUTING_READ)
    public String units(@RequestParam(required = false) String zone, Model model) {
        String z = tenants.zoneOr(zone);
        model.addAttribute("zone", z);
        model.addAttribute("units", tenants.units(z));
        model.addAttribute("rollouts", sagas(z, "rollout", "active"));
        model.addAttribute("drains", sagas(z, "drain", "active"));
        return "pages/units";
    }

    @GetMapping("/rollouts")
    @Requires(Capability.ROUTING_READ)
    public String rollouts(@RequestParam(required = false) String zone, Model model) {
        String z = tenants.zoneOr(zone);
        model.addAttribute("zone", z);
        List<JsonNode> all = new ArrayList<>(sagas(z, "rollout", "active"));
        all.addAll(sagas(z, "rollout", "finished"));
        model.addAttribute("rollouts", all);
        return "pages/rollouts";
    }

    @GetMapping("/rollouts/{zone}/{sagaId}")
    @Requires(Capability.ROUTING_READ)
    public String rollout(@PathVariable String zone, @PathVariable String sagaId, Model model) {
        String z = tenants.zoneOr(zone);
        if (!Pages.uuid7(sagaId)) {
            return "redirect:/rollouts";
        }
        JsonNode saga = rs.read(z, RsRequests.getSaga(sagaId)).path("saga");
        model.addAttribute("zone", z);
        model.addAttribute("saga", saga);
        model.addAttribute("rings", rings(saga));
        model.addAttribute("nextRing", nextRing(saga));
        return "pages/rollout";
    }

    List<JsonNode> sagas(String zone, String kind, String state) {
        List<JsonNode> out = new ArrayList<>();
        try {
            rs.read(zone, RsRequests.listSagas(state, kind, null, null, 50)).path("sagas").forEach(out::add);
        } catch (RsClient.ReadFailedException e) {
            // Shown as an empty list; the page can be reloaded.
        }
        return out;
    }

    /** A rollout's ring names, from its plan (parity-rs's saga shape for rollouts is open in 0.16.0: read tolerantly). */
    static List<String> rings(JsonNode saga) {
        List<String> out = new ArrayList<>();
        JsonNode rings = saga.path("plan").path("rings");
        if (!rings.isArray()) {
            rings = saga.path("after").path("plan").path("rings");
        }
        rings.forEach(r -> out.add(r.path("name").asString("?")));
        return out;
    }

    /** The next ring: {@code ring} (1-based, the current one) + 1; null when none is known or after the last. */
    static Map<String, Object> nextRing(JsonNode saga) {
        List<String> names = rings(saga);
        int current = saga.path("ring").asInt(saga.path("progress").path("ring").asInt(0));
        int next = current + 1;
        if (next < 1 || next > names.size()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", next);
        m.put("name", names.get(next - 1));
        return m;
    }

    /** {@code action}: drain, release, start_rollout. */
    @PostMapping("/units/{zone}/{unit}/review")
    @Requires(Capability.ROUTING_ROLLOUT)
    public String unitReview(@PathVariable String zone, @PathVariable String unit, @RequestParam String action,
            @RequestParam(required = false) String toUnit, @RequestParam(defaultValue = "false") boolean allowReleaseChange,
            @RequestParam(required = false) String release, @RequestParam(required = false) String rings,
            @RequestParam(required = false) Integer maxParallel, HttpServletRequest request, Model model) {
        String z = tenants.zoneOr(zone);
        if (!Pages.place(unit)) {
            return "redirect:/units";
        }
        JsonNode u = tenants.units(z).stream().filter(x -> unit.equals(x.path("unit").asString())).findFirst()
                .orElse(JSON.createObjectNode());
        ObjectNode before = JSON.createObjectNode();
        before.put("unit", unit);
        before.put("role", u.path("role").asString("unknown"));
        before.put("release", u.path("release").asString("-"));
        before.put("tenant_count", u.path("tenant_count").asInt(0));
        ObjectNode after = before.deepCopy();
        Map<String, String> params = new LinkedHashMap<>();
        Action a;
        String title;
        boolean liveWithTenants = "live".equals(u.path("role").asString()) && u.path("tenant_count").asInt(0) > 0;
        switch (action) {
            case "drain" -> {
                a = Action.UNIT_DRAIN;
                if (Pages.place(toUnit)) {
                    params.put("to_unit", toUnit);
                }
                params.put("allow_release_change", Boolean.toString(allowReleaseChange));
                after.put("role", "draining");
                after.put("tenant_count", 0);
                title = "Drain unit " + unit + (Pages.place(toUnit) ? " into " + toUnit : "");
            }
            case "release" -> {
                a = Action.UNIT_RELEASE;
                after.put("role", "empty");
                title = "Release failed unit " + unit;
            }
            case "start_rollout" -> {
                a = Action.ROLLOUT_START;
                String rel = release == null ? "" : release.strip();
                List<RsRequests.Ring> rs2 = RoutingActions.rings(rings);
                if (!Pages.RELEASE.matcher(rel).matches() || rs2.isEmpty() || rs2.size() > 16) {
                    model.addAttribute("title", "Can't review this");
                    model.addAttribute("message", "Give the release and 1 to 16 rings (name:test;name:percent=10).");
                    return "pages/problem";
                }
                params.put("rings", rings);
                if (maxParallel != null && maxParallel >= 1 && maxParallel <= 100) {
                    params.put("max_parallel", Integer.toString(maxParallel));
                }
                after.put("role", "canary");
                after.put("release", rel);
                after.put("rings", rings);
                title = "Start a rollout of " + rel + " on unit " + unit;
                release = rel;
            }
            default -> {
                return "redirect:/units?zone=" + z;
            }
        }
        Pending p = reviews.pending(a, new RiskPolicy.Facts(false, liveWithTenants, false, false),
                new Reviews.Target(z, null, unit, null, null, a == Action.ROLLOUT_START ? release : null), null, unit, params, before,
                after, title, "/units?zone=" + z);
        pendings.put(request.getSession(), p);
        return ReviewPage.render(model, p, null, null, Capability.ROUTING_ROLLOUT);
    }

    /** {@code action}: advance, stop, resume, rollback. */
    @PostMapping("/rollouts/{zone}/{sagaId}/review")
    @Requires(Capability.ROUTING_ROLLOUT)
    public String rolloutReview(@PathVariable String zone, @PathVariable String sagaId, @RequestParam String action,
            HttpServletRequest request, Model model) {
        String z = tenants.zoneOr(zone);
        if (!Pages.uuid7(sagaId)) {
            return "redirect:/rollouts";
        }
        JsonNode saga = rs.read(z, RsRequests.getSaga(sagaId)).path("saga");
        String unit = saga.path("unit").asString(saga.path("plan").path("unit").asString(null));
        String expected = Pages.place(unit) ? unit : Pages.short8(sagaId);
        ObjectNode before = JSON.createObjectNode();
        before.put("state", saga.path("state").asString("?"));
        before.put("ring", saga.path("ring").asInt(0));
        ObjectNode after = before.deepCopy();
        Map<String, String> params = new LinkedHashMap<>();
        Action a;
        String title;
        switch (action) {
            case "advance" -> {
                Map<String, Object> next = nextRing(saga);
                if (next == null) {
                    model.addAttribute("title", "Can't advance");
                    model.addAttribute("message", "No next ring is known for this rollout.");
                    return "pages/problem";
                }
                a = Action.ROLLOUT_ADVANCE;
                params.put("expected_ring", next.get("index").toString());
                expected = next.get("name").toString();
                after.put("ring", (Integer) next.get("index"));
                title = "Advance the rollout to ring " + expected;
            }
            case "stop" -> {
                a = Action.ROLLOUT_STOP;
                after.put("state", "paused");
                title = "Stop (pause) the rollout";
            }
            case "resume" -> {
                a = Action.ROLLOUT_RESUME;
                after.put("state", "ring_moving");
                title = "Resume the rollout";
            }
            case "rollback" -> {
                a = Action.ROLLOUT_ROLLBACK;
                after.put("state", "rolling_back");
                title = "Roll back the rollout (every tenant of the canary unit back to the live unit)";
            }
            default -> {
                return "redirect:/rollouts/" + z + "/" + sagaId;
            }
        }
        Pending p = reviews.pending(a, RiskPolicy.Facts.none(), new Reviews.Target(z, null, Pages.place(unit) ? unit : null, null, null, null),
                sagaId, expected, params, before, after, title, "/rollouts/" + z + "/" + sagaId);
        pendings.put(request.getSession(), p);
        return ReviewPage.render(model, p, null, null, Capability.ROUTING_ROLLOUT);
    }
}
