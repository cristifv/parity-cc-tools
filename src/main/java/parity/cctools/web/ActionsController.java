package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.audit.AuditQuery;
import parity.cctools.audit.AuditUnavailableException;
import parity.cctools.ops.Action;
import parity.cctools.ops.ActionRefusedException;
import parity.cctools.ops.ActionService;
import parity.cctools.ops.Pending;
import parity.cctools.ops.PendingActions;
import parity.cctools.ops.StepUpRequiredException;
import parity.cctools.security.Capability;
import parity.cctools.security.CurrentOperator;
import parity.cctools.security.ForbiddenException;
import parity.cctools.security.Operator;
import parity.cctools.security.Requires;
import parity.cctools.security.SecurityConfig;
import parity.cctools.security.StepUpResolver;

/**
 * Confirming an action (PLAN.md §3, §4.3): the review page posts here with the {@code op_id} it was given
 * and the operator's inputs (reason, ticket, typed name, force). One confirm endpoint per capability, so
 * each declares what it needs; the pending action must be of that capability. A High action with a stale
 * login goes to the ops realm first ({@code max_age}) and comes back to its resume page with the inputs
 * kept; nothing is sent before.
 */
@Controller
public class ActionsController {

    private final PendingActions pendings;
    private final ActionService actions;
    private final CurrentOperator operators;
    private final AuditQuery audit;
    private final Clock clock;
    private final String loginPath;

    public ActionsController(PendingActions pendings, ActionService actions, CurrentOperator operators, AuditQuery audit, Clock clock,
            parity.cctools.config.CcToolsProperties properties) {
        this.pendings = pendings;
        this.actions = actions;
        this.operators = operators;
        this.audit = audit;
        this.clock = clock;
        this.loginPath = StepUpResolver.BASE_URI + "/" + properties.identity().registrationId();
    }

    @PostMapping("/routing/actions/{opId}/confirm")
    @Requires(Capability.ROUTING_MOVE)
    public String confirmRouting(@PathVariable String opId, @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String ticket, @RequestParam(defaultValue = "") String typed,
            @RequestParam(defaultValue = "false") boolean force, HttpServletRequest request, Model model) {
        return confirm(Capability.ROUTING_MOVE, opId, new Pending.Form(reason, ticket, typed, force), request, model);
    }

    @PostMapping("/rollout/actions/{opId}/confirm")
    @Requires(Capability.ROUTING_ROLLOUT)
    public String confirmRollout(@PathVariable String opId, @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String ticket, @RequestParam(defaultValue = "") String typed,
            @RequestParam(defaultValue = "false") boolean force, HttpServletRequest request, Model model) {
        return confirm(Capability.ROUTING_ROLLOUT, opId, new Pending.Form(reason, ticket, typed, force), request, model);
    }

    @PostMapping("/service/actions/{opId}/confirm")
    @Requires(Capability.SERVICE_CONTROL)
    public String confirmService(@PathVariable String opId, @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String ticket, @RequestParam(defaultValue = "") String typed,
            @RequestParam(defaultValue = "false") boolean force, HttpServletRequest request, Model model) {
        return confirm(Capability.SERVICE_CONTROL, opId, new Pending.Form(reason, ticket, typed, force), request, model);
    }

    @GetMapping("/routing/actions/{opId}/resume")
    @Requires(Capability.ROUTING_MOVE)
    public String resumeRouting(@PathVariable String opId, HttpSession session, Model model) {
        return resume(Capability.ROUTING_MOVE, opId, session, model);
    }

    @GetMapping("/rollout/actions/{opId}/resume")
    @Requires(Capability.ROUTING_ROLLOUT)
    public String resumeRollout(@PathVariable String opId, HttpSession session, Model model) {
        return resume(Capability.ROUTING_ROLLOUT, opId, session, model);
    }

    @GetMapping("/service/actions/{opId}/resume")
    @Requires(Capability.SERVICE_CONTROL)
    public String resumeService(@PathVariable String opId, HttpSession session, Model model) {
        return resume(Capability.SERVICE_CONTROL, opId, session, model);
    }

    /** Resends a routing action that wasn't confirmed (same ids); the original action's capability is checked. */
    @PostMapping("/ops/{opId}/retry")
    @Requires(Capability.ROUTING_READ)
    public String retry(@PathVariable String opId, HttpServletRequest request, Model model) {
        Operator op = operators.require();
        var rows = audit.byOpId(opId);
        AuditQuery.Row original = rows.stream().filter(r -> "requested".equals(r.phase()) && r.request() != null).findFirst().orElse(null);
        boolean settled = rows.stream().anyMatch(r -> "answered".equals(r.phase()) && !"not_confirmed".equals(r.outcome()));
        Action action = original == null ? null : action(original.action());
        if (original == null || settled || action == null || !action.routing()) {
            model.addAttribute("title", "Nothing to retry");
            model.addAttribute("message", "This action was answered, or isn't a parity-rs action.");
            return "pages/problem";
        }
        try {
            actions.retry(op, new ActionService.AuditQueryView(original.opId(), original.sagaId(), action, original.zone(),
                    original.tenantId(), original.unit(), original.release(), original.confirmKind(), original.request()),
                    Pages.clientIp(request));
        } catch (StepUpRequiredException e) {
            HttpSession session = request.getSession();
            session.setAttribute(SecurityConfig.AFTER_LOGIN, "/ops/" + opId);
            session.setAttribute(SecurityConfig.STEP_UP, true);
            return "redirect:" + loginPath + "?" + StepUpResolver.STEP_UP_PARAM + "=1";
        }
        return "redirect:/ops/" + opId;
    }

    static Action action(String wire) {
        for (Action a : Action.values()) {
            if (a.wire().equals(wire)) {
                return a;
            }
        }
        return null;
    }

    private String confirm(Capability area, String opId, Pending.Form form, HttpServletRequest request, Model model) {
        Operator op = operators.require();
        HttpSession session = request.getSession();
        Pending p = Pages.uuid7(opId) ? pendings.get(session, opId, clock.millis()) : null;
        if (p == null) {
            model.addAttribute("title", "Confirmation expired");
            model.addAttribute("message", ActionRefusedException.Reason.STALE_FORM.message());
            return "pages/problem";
        }
        if (p.action().capability() != area) {
            throw new ForbiddenException("the action isn't of this confirm endpoint's capability");
        }
        try {
            actions.check(op, p, form);
        } catch (ActionRefusedException e) {
            return ReviewPage.render(model, p.withForm(form), e.reason().message(), null, area);
        } catch (StepUpRequiredException e) {
            pendings.put(session, p.withForm(form));
            session.setAttribute(SecurityConfig.AFTER_LOGIN, "/" + ReviewPage.area(area) + "/actions/" + opId + "/resume");
            session.setAttribute(SecurityConfig.STEP_UP, true);
            return "redirect:" + loginPath + "?" + StepUpResolver.STEP_UP_PARAM + "=1";
        }
        pendings.take(session, opId, clock.millis());
        ActionService.Result r;
        try {
            r = actions.confirm(op, p, form, Pages.clientIp(request));
        } catch (AuditUnavailableException e) {
            pendings.put(session, p.withForm(form));
            throw e;
        }
        if (r.status() == parity.cctools.routing.RsClient.Status.ERROR) {
            model.addAttribute("result", r);
            return ReviewPage.render(model, p.withForm(form), "Refused: " + r.code() + (r.rule() == null ? "" : " (" + r.rule() + ")")
                    + (r.message() == null || r.message().isBlank() ? "" : ": " + r.message()), null, area, true);
        }
        return "redirect:/ops/" + p.opId();
    }

    private String resume(Capability area, String opId, HttpSession session, Model model) {
        Pending p = Pages.uuid7(opId) ? pendings.get(session, opId, clock.millis()) : null;
        if (p == null || p.action().capability() != area) {
            model.addAttribute("title", "Confirmation expired");
            model.addAttribute("message", ActionRefusedException.Reason.STALE_FORM.message());
            return "pages/problem";
        }
        model.addAttribute("resumed", true);
        return ReviewPage.render(model, p, null, null, area);
    }
}
