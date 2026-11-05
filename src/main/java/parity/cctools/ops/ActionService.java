package parity.cctools.ops;

import java.time.Clock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import parity.cctools.audit.AuditLog;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.control.ServiceControl;
import parity.cctools.obs.ToolMetrics;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.routing.RsTransport;
import parity.cctools.routing.SagaProjection;
import parity.cctools.security.ForbiddenException;
import parity.cctools.security.Operator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Running a confirmed action (PLAN.md §3, §5), the one path every write takes:
 * <ol>
 *   <li>the operator's capability, re-checked at the moment of sending;</li>
 *   <li>the confirmation: a reason (10-500), the typed name for Medium and High, {@code force} where needed;</li>
 *   <li>for High, a fresh login ({@code auth_time} within the step-up age), else {@link StepUpRequiredException};</li>
 *   <li>the command built and checked against the contract's schema (a failure is a bug: nothing written);</li>
 *   <li>the {@code requested} audit row; <b>if it can't be written, nothing is sent</b> (fail closed);</li>
 *   <li>sent (parity-rs on the zone's ops topic, or this tool's service control);</li>
 *   <li>the {@code answered} row: ok, error (code, rule) or not_confirmed (no answer in time: the page
 *       follows the saga's events; a retry resends the same ids).</li>
 * </ol>
 */
@Component
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TICKET = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,99}$");

    /** How an action was answered; {@code sagaId} is what the progress page follows. */
    public record Result(RsClient.Status status, String code, String rule, String message, String opId, String sagaId,
            String zone, JsonNode result) {
        public boolean ok() {
            return status == RsClient.Status.OK;
        }

        public boolean notConfirmed() {
            return status == RsClient.Status.NOT_CONFIRMED || status == RsClient.Status.NOT_SENT;
        }
    }

    private final AuditLog audit;
    private final RsClient rs;
    private final ServiceControl control;
    private final SagaProjection projection;
    private final ToolMetrics metrics;
    private final Clock clock;
    private final CcToolsProperties.Identity identity;

    public ActionService(AuditLog audit, RsClient rs, ServiceControl control, SagaProjection projection, ToolMetrics metrics,
            Clock clock, CcToolsProperties properties) {
        this.audit = audit;
        this.rs = rs;
        this.control = control;
        this.projection = projection;
        this.metrics = metrics;
        this.clock = clock;
        this.identity = properties.identity();
    }

    /** The checks before anything is written (1-3); throws on the first that fails. */
    public void check(Operator op, Pending p, Pending.Form f) {
        if (!op.can(p.action().capability())) {
            throw new ForbiddenException("the operator lacks the action's capability");
        }
        String reason = f.reason() == null ? "" : f.reason().strip();
        if (reason.length() < 10 || reason.length() > 500) {
            throw refused(p, ActionRefusedException.Reason.REASON_LENGTH);
        }
        if (f.ticket() != null && !f.ticket().isBlank() && !TICKET.matcher(f.ticket().strip()).matches()) {
            throw refused(p, ActionRefusedException.Reason.TICKET_FORMAT);
        }
        if (p.risk().typed() && (f.typed() == null || !f.typed().strip().equals(p.expectedConfirmation()))) {
            throw refused(p, ActionRefusedException.Reason.TYPED_CONFIRMATION);
        }
        if (p.forceRequired() && !f.force()) {
            throw refused(p, ActionRefusedException.Reason.FORCE_REQUIRED);
        }
        if (p.risk().stepUp() && !op.freshLogin(clock.instant(), identity.stepUpMaxAge())) {
            log.atInfo().addKeyValue("event", "auth.step_up_required").addKeyValue("op_id", p.opId())
                    .addKeyValue("action", p.action().wire()).addKeyValue("actor_sub", op.sub())
                    .addKeyValue("max_age_s", identity.stepUpMaxAge().toSeconds()).log("a high-risk action needs a fresh login");
            throw new StepUpRequiredException(java.util.UUID.fromString(p.opId()));
        }
    }

    private ActionRefusedException refused(Pending p, ActionRefusedException.Reason r) {
        log.atInfo().addKeyValue("event", "action.refused").addKeyValue("op_id", p.opId()).addKeyValue("action", p.action().wire())
                .addKeyValue("reason", r.code()).log("an action was refused before it was sent");
        metrics.action(p.action().wire(), "refused_" + r.code());
        return new ActionRefusedException(r);
    }

    /** Runs a pending action the operator confirmed with {@code f}. */
    public Result confirm(Operator op, Pending p, Pending.Form f, String clientIp) {
        check(op, p, f);
        String confirmKind = p.risk() == Risk.HIGH ? "typed_step_up" : p.risk() == Risk.MEDIUM ? "typed" : "reason";
        if (p.action().routing()) {
            ObjectNode request = rs.request(RoutingActions.body(p, op, f), p.requestId());
            rs.mustFit(request);
            AuditLog.Requested row = row(op, p, f, clientIp, confirmKind, request);
            audit.requested(row);
            requestedLog(op, p);
            RsClient.Answer a = rs.call(p.zone(), RsTransport.Topic.OPS, request);
            return answered(row, p, a);
        }
        ObjectNode request = serviceRequest(p, f);
        AuditLog.Requested row = row(op, p, f, clientIp, confirmKind, request);
        audit.requested(row);
        requestedLog(op, p);
        ServiceControl.Result r = switch (p.action()) {
            case SERVICE_OP_CANCEL -> control.cancel(p.sagaId(), p.opId(), op);
            default -> control.start(new ServiceControl.Target(p.zone(), Catalogue.Driver.ofWire(p.param("driver")), p.service(),
                    p.instance(), p.param("namespace")), disable(p.action()), p.opId(), p.sagaId(), op, f.reason().strip(), f.force());
        };
        RsClient.Answer a = r.started()
                ? new RsClient.Answer(RsClient.Status.OK, JSON.createObjectNode().put("saga_id", r.sagaId()), null, null, null, null,
                        p.zone(), p.requestId())
                : new RsClient.Answer(RsClient.Status.ERROR, null, "conflict", r.detail(), r.code(), null, p.zone(), p.requestId());
        return answered(row, p, a);
    }

    static boolean disable(Action a) {
        return a == Action.SERVICE_CORDON || a == Action.MEDIA_NODE_OUT || a == Action.SERVICE_STOP;
    }

    private void requestedLog(Operator op, Pending p) {
        log.atInfo().addKeyValue("event", "action.requested").addKeyValue("op_id", p.opId()).addKeyValue("saga_id", p.sagaId())
                .addKeyValue("zone", p.zone()).addKeyValue("tenant_id", p.tenantId()).addKeyValue("action", p.action().wire())
                .addKeyValue("actor_sub", op.sub()).addKeyValue("risk", p.risk().label()).log("an operator confirmed an action");
    }

    private Result answered(AuditLog.Requested row, Pending p, RsClient.Answer a) {
        switch (a.status()) {
            case OK -> audit.answered(row, AuditLog.Outcome.OK, null, null);
            case ERROR -> audit.answered(row, AuditLog.Outcome.ERROR, safeCode(a.code()), safeCode(a.rule()));
            default -> audit.answered(row, AuditLog.Outcome.NOT_CONFIRMED, null, null);
        }
        String sagaId = p.sagaId();
        if (a.ok() && a.result() != null && a.result().hasNonNull("saga_id")) {
            sagaId = a.result().path("saga_id").asString(sagaId);
        }
        if (a.status() == RsClient.Status.OK || a.status() == RsClient.Status.ERROR) {
            metrics.action(p.action().wire(), a.ok() ? "ok" : "error");
            (a.ok() ? log.atInfo() : log.atWarn()).addKeyValue("event", "action.answered").addKeyValue("op_id", p.opId())
                    .addKeyValue("saga_id", sagaId).addKeyValue("zone", a.zone()).addKeyValue("action", p.action().wire())
                    .addKeyValue("outcome", a.ok() ? "ok" : "refused").addKeyValue("error_code", a.ok() ? null : safeCode(a.code()))
                    .addKeyValue("rule", a.rule()).log("the action was answered");
        } else {
            metrics.action(p.action().wire(), "not_confirmed");
            metrics.notConfirmed();
            log.atWarn().addKeyValue("event", "action.not_confirmed").addKeyValue("op_id", p.opId()).addKeyValue("saga_id", sagaId)
                    .addKeyValue("zone", a.zone()).addKeyValue("action", p.action().wire())
                    .addKeyValue("sent", a.status() != RsClient.Status.NOT_SENT)
                    .log("no answer in time: following the saga's events");
        }
        if (p.action().routing() && RoutingActions.followsSaga(p.action()) && sagaId != null && a.status() != RsClient.Status.ERROR) {
            seed(a.zone(), sagaId);
        }
        return new Result(a.status(), a.code(), a.rule(), a.message(), p.opId(), sagaId, a.zone(), a.result());
    }

    /** Reads the saga once so the progress page has a start; its events move it from then on. */
    public void seed(String zone, String sagaId) {
        if (projection.get(sagaId) != null) {
            return;
        }
        Thread.ofVirtual().name("saga-seed").start(() -> {
            try {
                JsonNode r = rs.read(zone, RsRequests.getSaga(sagaId));
                if (r.has("saga")) {
                    projection.seed(zone, r.path("saga"));
                }
            } catch (RuntimeException e) {
                // Not known yet (parity-rs answers before its first event) or not reachable: events follow.
            }
        });
    }

    private static String safeCode(String c) {
        return c != null && c.matches("^[a-z][a-z0-9_]{0,63}$") ? c : c == null ? null : "internal";
    }

    private AuditLog.Requested row(Operator op, Pending p, Pending.Form f, String clientIp, String confirmKind, JsonNode request) {
        return new AuditLog.Requested(p.opId(), p.requestId(), p.sagaId(), op, clientIp, p.action().wire(), p.zone(), p.tenantId(),
                p.unit(), p.service(), p.instance(), p.release(), f.reason().strip(), RoutingActions.blankToNull(f.ticket()),
                confirmKind, parse(p.beforeJson()), parse(p.afterJson()), request);
    }

    private static ObjectNode serviceRequest(Pending p, Pending.Form f) {
        ObjectNode n = JSON.createObjectNode();
        n.put("action", p.action().wire());
        n.put("zone", p.zone());
        n.put("service", p.service());
        n.put("instance", p.instance());
        n.put("driver", p.param("driver"));
        n.put("namespace", p.param("namespace"));
        n.put("saga_id", p.sagaId());
        n.put("force", f.force());
        return n;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Resends a routing action that wasn't confirmed: the same body (the same {@code id}, {@code op_id} and
     * {@code saga_id}: parity-rs returns the same saga), audited again as a {@code retry}. High actions need
     * a fresh login again.
     */
    public Result retry(Operator op, AuditQueryView original, String clientIp) {
        Action action = original.action();
        if (!op.can(action.capability())) {
            throw new ForbiddenException("the operator lacks the action's capability");
        }
        Risk risk = original.confirmKind() != null && original.confirmKind().endsWith("step_up") ? Risk.HIGH : Risk.MEDIUM;
        if (risk == Risk.HIGH && !op.freshLogin(clock.instant(), identity.stepUpMaxAge())) {
            log.atInfo().addKeyValue("event", "auth.step_up_required").addKeyValue("op_id", original.opId())
                    .addKeyValue("action", action.wire()).addKeyValue("actor_sub", op.sub())
                    .addKeyValue("max_age_s", identity.stepUpMaxAge().toSeconds()).log("a high-risk retry needs a fresh login");
            throw new StepUpRequiredException(java.util.UUID.fromString(original.opId()));
        }
        ObjectNode request = (ObjectNode) original.request();
        rs.mustFit(request);
        Pending p = new Pending(original.opId(), original.sagaId(), request.path("id").asString(), action, risk, false,
                original.zone(), original.tenantId(), original.unit(), null, null, original.release(), null, null, null, null,
                null, null, clock.millis(), Pending.Form.empty());
        AuditLog.Requested row = new AuditLog.Requested(p.opId(), p.requestId(), p.sagaId(), op, clientIp, action.wire(), p.zone(),
                p.tenantId(), p.unit(), null, null, p.release(), "retry of an action that was not confirmed", null, "retry", null, null,
                request);
        audit.requested(row);
        requestedLog(op, p);
        return answered(row, p, rs.call(p.zone(), RsTransport.Topic.OPS, request));
    }

    /** What a retry needs from the original requested row. */
    public record AuditQueryView(String opId, String sagaId, Action action, String zone, String tenantId, String unit,
            String release, String confirmKind, JsonNode request) {}
}
