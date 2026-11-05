package parity.cctools.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The capability check, in one place (PLAN.md §7): before any controller method runs, its
 * {@link Requires} capability must be the operator's; a method with neither {@link Requires} nor
 * {@link PublicEndpoint} is refused (fail closed: 403), whatever Spring Security let through.
 */
public class CapabilityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityInterceptor.class);

    private final CurrentOperator operators;

    public CapabilityInterceptor(CurrentOperator operators) {
        this.operators = operators;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (method.hasMethodAnnotation(PublicEndpoint.class)) {
            return true;
        }
        Requires requires = method.getMethodAnnotation(Requires.class);
        if (requires == null) {
            log.atWarn().addKeyValue("event", "auth.login_refused").addKeyValue("reason", "no_capability_declared")
                    .addKeyValue("op", request.getMethod() + " " + method.getMethod().getName())
                    .log("a handler declares no capability: refused");
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        Operator op = operators.get();
        if (op == null || !op.can(requires.value())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        request.setAttribute(Operator.class.getName(), op);
        return true;
    }
}
