package parity.cctools.web;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import parity.cctools.audit.AuditQuery;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.control.ServiceControl;
import parity.cctools.control.ServiceOps;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.routing.SagaProjection;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;

/**
 * One action's page (PLAN.md §4.3, §4.9): its live progress (SSE, with a polling fallback) and its
 * {@code op_id} timeline: the audit rows, the saga's steps, the service-control steps, the outcome. Also a
 * saga's stream by id (the operations page). Neither the stream nor the polling decides anything.
 */
@Controller
public class ProgressController {

    /** One line of the timeline. */
    public record Entry(Instant at, String source, String what, String detail) {}

    private final AuditQuery audit;
    private final ServiceOps serviceOps;
    private final ServiceControl control;
    private final SagaProjection projection;
    private final ProgressStreams streams;
    private final RsClient rs;
    private final CcToolsProperties properties;

    public ProgressController(AuditQuery audit, ServiceOps serviceOps, ServiceControl control, SagaProjection projection,
            ProgressStreams streams, RsClient rs, CcToolsProperties properties) {
        this.audit = audit;
        this.serviceOps = serviceOps;
        this.control = control;
        this.projection = projection;
        this.streams = streams;
        this.rs = rs;
        this.properties = properties;
    }

    /** The saga an action follows, its zone and whether it's this tool's own (service control). */
    record Primary(String sagaId, String zone, boolean service) {}

    Primary primary(List<AuditQuery.Row> rows) {
        for (AuditQuery.Row r : rows) {
            if (r.sagaId() != null && r.zone() != null) {
                return new Primary(r.sagaId(), r.zone(), r.service() != null);
            }
        }
        return null;
    }

    /** Puts the saga in this pod's projection if it isn't there (a page on another pod than the action's). */
    void ensureSeeded(Primary p) {
        if (projection.get(p.sagaId()) != null) {
            return;
        }
        if (p.service()) {
            control.publish(p.sagaId());
            return;
        }
        if (!properties.zones().containsKey(p.zone())) {
            return;
        }
        try {
            var r = rs.read(p.zone(), RsRequests.getSaga(p.sagaId()));
            if (r.has("saga")) {
                projection.seed(p.zone(), r.path("saga"));
            }
        } catch (RsClient.ReadFailedException e) {
            // Not known yet: its events will come.
        }
    }

    @GetMapping("/ops/{opId}")
    @Requires(Capability.AUDIT_READ)
    public String page(@PathVariable String opId, Model model) {
        if (!Pages.uuid7(opId)) {
            return "redirect:/audit";
        }
        List<AuditQuery.Row> rows = audit.byOpId(opId);
        Primary p = primary(rows);
        if (p != null) {
            ensureSeeded(p);
        }
        SagaProjection.View view = p == null ? null : projection.get(p.sagaId());
        List<Entry> timeline = new ArrayList<>();
        for (AuditQuery.Row r : rows) {
            timeline.add(new Entry(r.at(), "audit", r.action() + " " + r.phase() + (r.outcome() == null ? "" : ": " + r.outcome()),
                    r.errorCode() == null ? r.actorUsername() : r.errorCode() + (r.errorRule() == null ? "" : " (" + r.errorRule() + ")")));
        }
        if (view != null && !p.service()) {
            view.steps().forEach(s -> timeline.add(new Entry(Instant.ofEpochMilli(s.atMs()), "parity-rs", s.state(), s.detail())));
        }
        for (ServiceOps.Op o : serviceOps.byOpId(opId)) {
            serviceOps.steps(o.id()).forEach(s -> timeline.add(new Entry(s.at(), "service-control",
                    (s.fromState() == null ? "" : s.fromState() + " -> ") + s.toState(), s.detail())));
        }
        audit.outcomes(opId).forEach(o -> timeline.add(new Entry(o.at(), "outcome", o.outcome(), o.detail())));
        timeline.sort(Comparator.comparing(Entry::at));
        boolean notConfirmed = !rows.isEmpty() && rows.stream().filter(r -> "answered".equals(r.phase())).allMatch(r -> "not_confirmed".equals(r.outcome()));
        model.addAttribute("opId", opId);
        model.addAttribute("rows", rows);
        model.addAttribute("primary", p);
        model.addAttribute("view", view);
        model.addAttribute("timeline", timeline);
        model.addAttribute("retryable", notConfirmed && p != null && !p.service());
        model.addAttribute("streamUrl", "/ops/" + opId + "/stream");
        model.addAttribute("fragmentUrl", "/ops/" + opId + "/fragment");
        return "pages/op";
    }

    @GetMapping(path = "/ops/{opId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Requires(Capability.AUDIT_READ)
    public SseEmitter stream(@PathVariable String opId, HttpServletResponse response) {
        Primary p = Pages.uuid7(opId) ? primary(audit.byOpId(opId)) : null;
        response.setHeader("X-Accel-Buffering", "no");
        if (p == null) {
            SseEmitter e = new SseEmitter(1000L);
            e.complete();
            return e;
        }
        ensureSeeded(p);
        return streams.open(p.sagaId(), opId);
    }

    @GetMapping("/ops/{opId}/fragment")
    @Requires(Capability.AUDIT_READ)
    @ResponseBody
    public String fragment(@PathVariable String opId, HttpServletResponse response) {
        Primary p = Pages.uuid7(opId) ? primary(audit.byOpId(opId)) : null;
        if (p == null) {
            response.setStatus(286);
            return "";
        }
        ensureSeeded(p);
        return fragmentOf(p.sagaId(), opId, response);
    }

    @GetMapping(path = "/sagas/{zone}/{sagaId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Requires(Capability.ROUTING_READ)
    public SseEmitter sagaStream(@PathVariable String zone, @PathVariable String sagaId, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        if (!Pages.uuid7(sagaId) || !properties.zones().containsKey(zone)) {
            SseEmitter e = new SseEmitter(1000L);
            e.complete();
            return e;
        }
        ensureSeeded(new Primary(sagaId, zone, false));
        return streams.open(sagaId, null);
    }

    @GetMapping("/sagas/{zone}/{sagaId}/fragment")
    @Requires(Capability.ROUTING_READ)
    @ResponseBody
    public String sagaFragment(@PathVariable String zone, @PathVariable String sagaId, HttpServletResponse response) {
        if (!Pages.uuid7(sagaId) || !properties.zones().containsKey(zone)) {
            response.setStatus(286);
            return "";
        }
        ensureSeeded(new Primary(sagaId, zone, false));
        return fragmentOf(sagaId, null, response);
    }

    private String fragmentOf(String sagaId, String opId, HttpServletResponse response) {
        SagaProjection.View v = projection.get(sagaId);
        response.setContentType("text/html;charset=UTF-8");
        if (v == null) {
            return "<p class=\"muted\">Waiting for the saga's first event.</p>";
        }
        if (v.finished()) {
            // 286: HTMX stops polling.
            response.setStatus(286);
        }
        return streams.render(v, opId);
    }
}
