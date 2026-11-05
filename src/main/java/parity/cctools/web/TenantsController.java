package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
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
import parity.cctools.audit.AuditQuery;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.obs.Ids;
import parity.cctools.ops.Action;
import parity.cctools.ops.Pending;
import parity.cctools.ops.PendingActions;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.routing.RsTransport;
import parity.cctools.security.Capability;
import parity.cctools.security.CurrentOperator;
import parity.cctools.security.Operator;
import parity.cctools.security.Requires;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tenants (PLAN.md §4.1-4.3): the list, a tenant's detail, and the move wizard. parity-rs decides
 * everything: every move is first a dry run ({@code validate_move}, under the action's own op_id), whose
 * plan, diff and warnings the review shows; the confirmed command carries the {@code expected_version}
 * the screen showed. Zone moves are refused in v1 ({@code zone_copy_unavailable}): the screen keeps the
 * form, disabled, with parity-rs's own explanation.
 */
@Controller
public class TenantsController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCREEN_REASON = "dry run for the zone-move screen; changes nothing";

    private final RsClient rs;
    private final CcToolsProperties properties;
    private final AuditQuery audit;
    private final Reviews reviews;
    private final PendingActions pendings;
    private final CurrentOperator operators;
    private final Clock clock;

    public TenantsController(RsClient rs, CcToolsProperties properties, AuditQuery audit, Reviews reviews, PendingActions pendings,
            CurrentOperator operators, Clock clock) {
        this.rs = rs;
        this.properties = properties;
        this.audit = audit;
        this.reviews = reviews;
        this.pendings = pendings;
        this.operators = operators;
        this.clock = clock;
    }

    String zoneOr(String zone) {
        return zone != null && properties.zones().containsKey(zone) ? zone : properties.zoneNames().get(0);
    }

    @GetMapping("/")
    @Requires(Capability.ROUTING_READ)
    public String home() {
        return "redirect:/tenants";
    }

    @GetMapping("/tenants")
    @Requires(Capability.ROUTING_READ)
    public String list(@RequestParam(required = false) String zone, @RequestParam(required = false) String unit,
            @RequestParam(required = false) String tier, @RequestParam(required = false) String after,
            @RequestParam(required = false) String q, Model model) {
        String z = zoneOr(zone);
        model.addAttribute("zone", z);
        model.addAttribute("unit", unit);
        model.addAttribute("tier", tier);
        model.addAttribute("q", q);
        if (q != null && !q.isBlank()) {
            String t = q.strip().toLowerCase(java.util.Locale.ROOT);
            if (Pages.tenant(t)) {
                return "redirect:/tenants/" + z + "/" + t;
            }
            model.addAttribute("error", "A tenant id is a DNS label: lowercase letters, digits and hyphens.");
        }
        JsonNode r = rs.read(z, RsRequests.listRoutes(Pages.tenant(after) ? after : null, properties.routing().pageSize(), null,
                Pages.place(unit) ? unit : null, tier != null && List.of("small", "mid", "enterprise").contains(tier) ? tier : null));
        List<JsonNode> routes = new ArrayList<>();
        r.path("routes").forEach(routes::add);
        model.addAttribute("routes", routes);
        model.addAttribute("nextAfter", r.path("next_after").asString(null));
        return "pages/tenants";
    }

    @GetMapping("/tenants/{zone}/{tenant}")
    @Requires(Capability.ROUTING_READ)
    public String detail(@PathVariable String zone, @PathVariable String tenant, Model model) {
        String z = zoneOr(zone);
        if (!Pages.tenant(tenant)) {
            return "redirect:/tenants";
        }
        JsonNode route = rs.read(z, RsRequests.getRoute(tenant)).path("route");
        model.addAttribute("zone", z);
        model.addAttribute("route", route);
        model.addAttribute("units", units(z));
        List<JsonNode> sagas = new ArrayList<>();
        try {
            rs.read(z, RsRequests.listSagas(null, null, tenant, null, 20)).path("sagas").forEach(sagas::add);
        } catch (RsClient.ReadFailedException e) {
            model.addAttribute("sagasError", Pages.code(e.answer()));
        }
        model.addAttribute("sagas", sagas);
        model.addAttribute("audit", audit.forTenant(tenant, 20));
        model.addAttribute("zoneMove", zoneMoveExplanation(z, route));
        return "pages/tenant";
    }

    /** The units of a zone (for the move form), from get_topology. */
    List<JsonNode> units(String zone) {
        List<JsonNode> out = new ArrayList<>();
        try {
            JsonNode t = rs.read(zone, RsRequests.getTopology());
            JsonNode units = t.path("zones").path(zone).path("units");
            if (units.isArray()) {
                units.forEach(out::add);
            } else {
                for (var e : units.properties()) {
                    ObjectNode u = e.getValue().isObject() ? ((ObjectNode) e.getValue()).deepCopy() : JSON.createObjectNode();
                    u.put("unit", e.getKey());
                    out.add(u);
                }
            }
        } catch (RsClient.ReadFailedException e) {
            // The form then takes a unit name typed in.
        }
        return out;
    }

    /** Why the zone move is unavailable, from parity-rs's own dry run (v1: zone_copy_unavailable). */
    String zoneMoveExplanation(String zone, JsonNode route) {
        Operator op = operators.get();
        JsonNode placement = route.path("placement");
        String target = placement.path("failover_zone").asString(null);
        if (op == null || target == null || !Pages.place(target) || !route.has("version")) {
            return "No other zone is known for this tenant (no failover zone): a zone move can't be planned.";
        }
        ObjectNode body = RsRequests.validateMove(new RsRequests.Op(Ids.newUuid7(), op.actor(), SCREEN_REASON, null), route.path("tenant_id").asString(),
                route.path("version").asLong(), null, target, null, false);
        ObjectNode request = rs.request(body, rs.newId("validate_move"));
        rs.mustFit(request);
        RsClient.Answer a = rs.call(zone, RsTransport.Topic.OPS, request);
        if (a.ok()) {
            return null;
        }
        if ("zone_copy_unavailable".equals(a.rule())) {
            return "Zone moves and home-zone switches aren't available yet: parity-rs answers zone_copy_unavailable"
                    + " (the zone copier isn't built; v1 has one zone).";
        }
        return "parity-rs refuses a zone move now: " + Pages.code(a) + ".";
    }

    /**
     * The wizard's review step: {@code action} is move, move_zone, failover, pin, unpin or cancel. A move
     * runs parity-rs's dry run first; the review shows its plan, diff and warnings.
     */
    @PostMapping("/tenants/{zone}/{tenant}/review")
    @Requires(Capability.ROUTING_MOVE)
    public String review(@PathVariable String zone, @PathVariable String tenant, @RequestParam String action,
            @RequestParam(required = false) String toUnit, @RequestParam(required = false) String toZone,
            @RequestParam(required = false) String mediaTrack, @RequestParam(required = false) String failoverZone,
            @RequestParam(defaultValue = "false") boolean force, @RequestParam(required = false) Integer pinHours,
            @RequestParam(defaultValue = "") String reason, @RequestParam(defaultValue = "") String ticket,
            HttpServletRequest request, Model model) {
        String z = zoneOr(zone);
        if (!Pages.tenant(tenant)) {
            return "redirect:/tenants";
        }
        Operator op = operators.require();
        JsonNode route = rs.read(z, RsRequests.getRoute(tenant)).path("route");
        long version = route.path("version").asLong(0);
        JsonNode placement = route.path("placement");
        ObjectNode before = JSON.createObjectNode();
        before.set("placement", placement);
        before.put("version", version);
        before.set("pin", route.path("pin"));
        ObjectNode after = before.deepCopy();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("expected_version", Long.toString(version));
        Action a;
        String sagaId = null;
        String title;
        switch (action) {
            case "move" -> {
                if (!Pages.place(toUnit)) {
                    return back(z, tenant, "Choose the unit to move to.", model);
                }
                a = Action.TENANT_MOVE;
                params.put("to_unit", toUnit);
                if (Pages.place(mediaTrack)) {
                    params.put("media_track", mediaTrack);
                }
                params.put("force", Boolean.toString(force));
                if (after.path("placement").isObject()) {
                    ((ObjectNode) after.path("placement")).put("deployment_unit", toUnit);
                }
                title = "Move " + tenant + " to unit " + toUnit;
            }
            case "move_zone" -> {
                if (!Pages.place(toZone)) {
                    return back(z, tenant, "Choose the zone to move to.", model);
                }
                a = Action.TENANT_MOVE_ZONE;
                params.put("to_zone", toZone);
                if (Pages.place(toUnit)) {
                    params.put("to_unit", toUnit);
                }
                params.put("cutover", "on_confirm");
                title = "Move " + tenant + " to zone " + toZone + " (home-zone switch)";
            }
            case "failover" -> {
                a = Action.TENANT_SET_FAILOVER;
                String fz = Pages.place(failoverZone) ? failoverZone : "";
                params.put("failover_zone", fz);
                if (after.path("placement").isObject()) {
                    ((ObjectNode) after.path("placement")).put("failover_zone", fz.isEmpty() ? "-" : fz);
                }
                title = fz.isEmpty() ? "Clear " + tenant + "'s failover zone" : "Set " + tenant + "'s failover zone to " + fz;
            }
            case "pin" -> {
                a = Action.TENANT_PIN;
                int hours = pinHours == null ? 24 : Math.max(1, Math.min(24 * 30, pinHours));
                long until = clock.millis() + hours * 3_600_000L;
                params.put("until_ms", Long.toString(until));
                after.putObject("pin").put("until_ms", until);
                title = "Pin " + tenant + " for " + hours + " h (moves and rollouts skip it)";
            }
            case "unpin" -> {
                a = Action.TENANT_UNPIN;
                after.putNull("pin");
                title = "Unpin " + tenant;
            }
            case "cancel" -> {
                a = Action.SAGA_CANCEL;
                sagaId = route.path("active_saga").path("saga_id").asString(null);
                if (!Pages.uuid7(sagaId)) {
                    return back(z, tenant, "The tenant has no active saga to cancel.", model);
                }
                before.set("active_saga", route.path("active_saga"));
                after.put("active_saga", "cancelled (compensated by parity-rs)");
                title = "Cancel " + tenant + "'s " + route.path("active_saga").path("kind").asString("") + " saga";
            }
            default -> {
                return back(z, tenant, "Unknown action.", model);
            }
        }
        Pending p = reviews.pending(a, new RiskPolicy.Facts(force, false, false, false), Reviews.Target.tenant(z, tenant), sagaId,
                tenant, params, before, after, title, "/tenants/" + z + "/" + tenant);
        p = p.withForm(new Pending.Form(reason, ticket, "", force));
        JsonNode dryRun = null;
        String error = null;
        boolean closed = false;
        if (a == Action.TENANT_MOVE || a == Action.TENANT_MOVE_ZONE) {
            String why = reason.strip();
            if (why.length() < 10 || why.length() > 500) {
                return back(z, tenant, "Give the reason (10 to 500 characters) before the dry run.", model);
            }
            ObjectNode body = RsRequests.validateMove(new RsRequests.Op(p.opId(), op.actor(), why, Pages.blank(ticket)), tenant, version,
                    a == Action.TENANT_MOVE ? toUnit : null, a == Action.TENANT_MOVE_ZONE ? toZone : null,
                    Pages.place(mediaTrack) ? mediaTrack : null, force);
            ObjectNode req = rs.request(body, rs.newId("validate_move"));
            rs.mustFit(req);
            RsClient.Answer ans = rs.call(z, RsTransport.Topic.OPS, req);
            if (ans.ok()) {
                dryRun = ans.result();
                JsonNode planAfter = ans.result().path("plan").path("after");
                if (planAfter.isObject()) {
                    ObjectNode after2 = before.deepCopy();
                    after2.set("placement", planAfter);
                    p = new Pending(p.opId(), p.sagaId(), p.requestId(), p.action(), p.risk(), p.forceRequired(), p.zone(), p.tenantId(),
                            p.unit(), p.service(), p.instance(), p.release(), p.expectedConfirmation(), p.params(), p.beforeJson(),
                            after2.toString(), p.title(), p.returnPath(), p.createdAtMs(), p.form());
                }
            } else {
                closed = true;
                error = "zone_copy_unavailable".equals(ans.rule())
                        ? "parity-rs refuses zone moves in v1 (zone_copy_unavailable): the zone copier isn't built yet."
                        : "parity-rs's dry run refused it: " + Pages.code(ans) + (ans.message() == null ? "" : " (" + ans.message() + ")");
            }
        }
        if (!closed) {
            pendings.put(request.getSession(), p);
        }
        return ReviewPage.render(model, p, error, dryRun, Capability.ROUTING_MOVE, closed);
    }

    private String back(String zone, String tenant, String error, Model model) {
        model.addAttribute("title", "Can't review this");
        model.addAttribute("message", error);
        model.addAttribute("back", "/tenants/" + zone + "/" + tenant);
        return "pages/problem";
    }
}
