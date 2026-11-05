package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import parity.cctools.audit.AuditUnavailableException;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.routing.RsClient;
import parity.cctools.security.CurrentOperator;
import parity.cctools.security.ForbiddenException;

/** What every page has (the operator, the zones, the contracts version) and the error pages. */
@ControllerAdvice
public class PageAdvice {

    private final CurrentOperator operators;
    private final CcToolsProperties properties;

    public PageAdvice(CurrentOperator operators, CcToolsProperties properties) {
        this.operators = operators;
        this.properties = properties;
    }

    @ModelAttribute
    void common(Model model, HttpServletRequest request) {
        model.addAttribute("operator", operators.get());
        model.addAttribute("zones", properties.zoneNames());
        model.addAttribute("toolVersion", properties.version());
        model.addAttribute("path", request.getRequestURI());
    }

    @ExceptionHandler(ForbiddenException.class)
    String forbidden(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        model.addAttribute("title", "Not allowed");
        model.addAttribute("message", "Your role doesn't allow this.");
        return "pages/problem";
    }

    @ExceptionHandler(AuditUnavailableException.class)
    String auditDown(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        model.addAttribute("title", "Audit log unavailable");
        model.addAttribute("message", "The audit row couldn't be written, so nothing was sent. Try again when the database is back.");
        return "pages/problem";
    }

    @ExceptionHandler(RsClient.ReadFailedException.class)
    String readFailed(RsClient.ReadFailedException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        model.addAttribute("title", "parity-rs didn't answer");
        model.addAttribute("message", "parity-rs answered " + Pages.code(e.answer()) + ". The page can be reloaded.");
        return "pages/problem";
    }
}
