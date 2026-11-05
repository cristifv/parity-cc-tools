package parity.cctools.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A session lives at most {@code parity-cc-tools.session.absolute-timeout} (8 h, PLAN.md §2) whatever its
 * use; the 30-minute idle limit is Spring Session's. An older session is invalidated before anything
 * reads it.
 */
public class SessionAbsoluteTimeout extends OncePerRequestFilter {

    private final long maxAgeMillis;

    public SessionAbsoluteTimeout(Duration maxAge) {
        this.maxAgeMillis = maxAge.toMillis();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                if (System.currentTimeMillis() - session.getCreationTime() > maxAgeMillis) {
                    session.invalidate();
                }
            } catch (IllegalStateException alreadyInvalid) {
                // Nothing to end.
            }
        }
        chain.doFilter(request, response);
    }
}
