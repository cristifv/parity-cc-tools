package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.control.ServiceOps;
import parity.cctools.ops.Action;
import parity.cctools.ops.Pending;
import parity.cctools.ops.PendingActions;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.routing.SagaProjection;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operations (PLAN.md §4.6): every active saga across zones (parity-rs's, and this tool's service-control
 * sagas), blocked ones first with their resolve actions; a saga's own page follows it live by its id.
 */
@Controller
public class OperationsController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RsClient rs;
    private final CcToolsProperties properties;
    private final ServiceOps serviceOps;
    private final SagaProjection projection;
    private final Reviews reviews;
    private final PendingActions pendings;

    public OperationsController(RsClient rs, CcToolsProperties properties, ServiceOps serviceOps, SagaProjection projection,
            Reviews reviews, PendingActions pendings) {
        this.rs = rs;
        this.properties = properties;
        this.serviceOps = serviceOps;
        this.projection = projection;
        this.reviews = reviews;
        this.pendings = pendings;
    }

    /** A routing saga in the list, with the zone that answered. */
    public record Row(String zone, JsonNode saga, boolean blocked) {}

    @GetMapping("/operations")
    @Requires(Capability.ROUTING_READ)
    public String list(Model model) {
        List<Row> rows = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String zone : properties.zoneNames()) {
            try {
                rs.read(zone, RsRequests.listSagas("active", null, null, null, 200)).path("sagas")
                        .forEach(s -> rows.add(new Row(zone, s, "blocked".equals(s.path("state").asString()))));
            } catch (RsClient.ReadFailedException e) {
                errors.put(zone, Pages.code(e.answer()));
            }
        }
        rows.sort(Comparator.comparing((Row r) -> !r.blocked()).thenComparing(r -> r.saga().path("updated_at_ms").asLong(0),
                Comparator.reverseOrder()));
        model.addAttribute("rows", rows);
        model.addAttribute("errors", errors);
        model.addAttribute("serviceOps", serviceOps.open());
        return "pages/operations";
    }

    @GetMapping("/sagas/{zone}/{sagaId}")
    @Requires(Capability.ROUTING_READ)
    public String saga(@PathVariable String zone, @PathVariable String sagaId, Model model) {
        if (!Pages.uuid7(sagaId) || !properties.zones().containsKey(zone)) {
            return "redirect:/operations";
        }
        JsonNode saga = rs.read(zone, RsRequests.getSaga(sagaId)).path("saga");
        projection.seed(zone, saga);
        model.addAttribute("zone", zone);
        model.addAttribute("saga", saga);
        model.addAttribute("view", projection.get(sagaId));
        model.addAttribute("streamUrl", "/sagas/" + zone + "/" + sagaId + "/stream");
        model.addAttribute("fragmentUrl", "/sagas/" + zone + "/" + sagaId + "/fragment");
        return "pages/saga";
    }

    /** {@code action}: cancel, resolve (with {@code resolveAction} retry_record or accept_store), confirm_cutover. */
    @PostMapping("/sagas/{zone}/{sagaId}/review")
    @Requires(Capability.ROUTING_MOVE)
    public String review(@PathVariable String zone, @PathVariable String sagaId, @RequestParam String action,
            @RequestParam(required = false) String resolveAction, HttpServletRequest request, Model model) {
        if (!Pages.uuid7(sagaId) || !properties.zones().containsKey(zone)) {
            return "redirect:/operations";
        }
        JsonNode saga = rs.read(zone, RsRequests.getSaga(sagaId)).path("saga");
        String tenant = saga.path("tenant_id").asString(null);
        String expected = Pages.tenant(tenant) ? tenant : Pages.short8(sagaId);
        ObjectNode before = JSON.createObjectNode();
        before.put("kind", saga.path("kind").asString("?"));
        before.put("state", saga.path("state").asString("?"));
        ObjectNode after = before.deepCopy();
        Map<String, String> params = new LinkedHashMap<>();
        Action a;
        String title;
        switch (action) {
            case "cancel" -> {
                a = Action.SAGA_CANCEL;
                after.put("state", "cancelled (compensated)");
                title = "Cancel saga " + Pages.short8(sagaId);
            }
            case "resolve" -> {
                if (!"retry_record".equals(resolveAction) && !"accept_store".equals(resolveAction)) {
                    model.addAttribute("title", "Can't review this");
                    model.addAttribute("message", "Choose retry_record or accept_store.");
                    return "pages/problem";
                }
                a = Action.SAGA_RESOLVE;
                params.put("resolve_action", resolveAction);
                after.put("state", "resolved: " + resolveAction);
                title = "Resolve blocked saga " + Pages.short8(sagaId) + " (" + resolveAction + ")";
            }
            case "confirm_cutover" -> {
                a = Action.SAGA_CONFIRM_CUTOVER;
                after.put("state", "cutting_over");
                title = "Confirm the cutover of zone move " + Pages.short8(sagaId);
            }
            default -> {
                return "redirect:/sagas/" + zone + "/" + sagaId;
            }
        }
        Pending p = reviews.pending(a, RiskPolicy.Facts.none(), new Reviews.Target(zone, Pages.tenant(tenant) ? tenant : null, null, null,
                null, null), sagaId, expected, params, before, after, title, "/sagas/" + zone + "/" + sagaId);
        pendings.put(request.getSession(), p);
        return ReviewPage.render(model, p, null, null, Capability.ROUTING_MOVE);
    }
}
