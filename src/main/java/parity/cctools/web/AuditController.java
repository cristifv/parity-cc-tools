package parity.cctools.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.audit.AuditQuery;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;

/** The audit screen (PLAN.md §4.9): search by actor, action, tenant, unit, zone, service, op_id and time; a row's diff. */
@Controller
public class AuditController {

    private final AuditQuery audit;

    public AuditController(AuditQuery audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    @Requires(Capability.AUDIT_READ)
    public String search(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
            @RequestParam(required = false) String tenant, @RequestParam(required = false) String unit,
            @RequestParam(required = false) String zone, @RequestParam(required = false) String service,
            @RequestParam(required = false) String opId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, Model model) {
        AuditQuery.Filter f = new AuditQuery.Filter(Pages.blank(actor), Pages.blank(action), Pages.blank(tenant), Pages.blank(unit),
                Pages.blank(zone), Pages.blank(service), Pages.blank(opId), day(from, false), day(to, true));
        model.addAttribute("filter", f);
        model.addAttribute("fromText", from);
        model.addAttribute("toText", to);
        model.addAttribute("rows", audit.search(f, 200));
        return "pages/audit";
    }

    @GetMapping("/audit/rows/{id}")
    @Requires(Capability.AUDIT_READ)
    public String row(@PathVariable String id, Model model) {
        var r = audit.one(id).orElse(null);
        if (r == null) {
            return "redirect:/audit";
        }
        model.addAttribute("row", r);
        model.addAttribute("lines", ReviewPage.diff(r.before() == null ? null : r.before().toString(),
                r.after() == null ? null : r.after().toString()));
        return "pages/audit-row";
    }

    /** A {@code yyyy-mm-dd} day (UTC): its start, or for {@code to} the next day's start. */
    static Instant day(String s, boolean end) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(s.strip());
            return (end ? d.plusDays(1) : d).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
