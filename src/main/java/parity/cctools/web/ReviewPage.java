package parity.cctools.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ui.Model;
import parity.cctools.ops.Pending;
import parity.cctools.security.Capability;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The review page of a pending action: the before/after diff, the dry run, the confirmation form. */
final class ReviewPage {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One line of the diff: a field, its value before and after, and whether it changes. */
    record Line(String field, String before, String after, boolean changed) {}

    private ReviewPage() {}

    static String area(Capability c) {
        return switch (c) {
            case ROUTING_ROLLOUT -> "rollout";
            case SERVICE_CONTROL -> "service";
            default -> "routing";
        };
    }

    static String render(Model m, Pending p, String error, JsonNode dryRun, Capability area) {
        return render(m, p, error, dryRun, area, false);
    }

    /** {@code closed}: the action ran (or was refused by its backend): no form, a link to its progress. */
    static String render(Model m, Pending p, String error, JsonNode dryRun, Capability area, boolean closed) {
        m.addAttribute("pending", p);
        m.addAttribute("form", p.form());
        m.addAttribute("error", error);
        m.addAttribute("dryRun", dryRun);
        m.addAttribute("warnings", warnings(dryRun));
        m.addAttribute("lines", diff(p.beforeJson(), p.afterJson()));
        m.addAttribute("confirmUrl", "/" + area(area) + "/actions/" + p.opId() + "/confirm");
        m.addAttribute("closed", closed);
        return "pages/review";
    }

    static List<String> warnings(JsonNode dryRun) {
        List<String> out = new ArrayList<>();
        if (dryRun != null) {
            dryRun.path("plan").path("warnings").forEach(w -> out.add(w.isString() ? w.asString() : w.toString()));
        }
        return out;
    }

    static List<Line> diff(String beforeJson, String afterJson) {
        Map<String, String> b = flat(beforeJson);
        Map<String, String> a = flat(afterJson);
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        List<Line> out = new ArrayList<>();
        for (String k : keys) {
            String bv = b.getOrDefault(k, "");
            String av = a.containsKey(k) ? a.get(k) : bv;
            out.add(new Line(k, bv, av, !bv.equals(av)));
        }
        return out;
    }

    static Map<String, String> flat(String json) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode n = JSON.readTree(json);
        for (var e : n.properties()) {
            JsonNode v = e.getValue();
            out.put(e.getKey(), v.isNull() ? "-" : v.isValueNode() ? v.asString() : v.toString());
        }
        return out;
    }
}
