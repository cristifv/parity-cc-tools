package parity.cctools.security;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.filter.OncePerRequestFilter;
import parity.cctools.obs.ToolMetrics;

/**
 * A disabled operator, a removed {@code ops} role or a session ended in Keycloak stops working within one
 * access-token lifetime (5 min, PLAN.md §2): at most every {@code session-check-interval}, a signed-in
 * request asks the authorized-client manager for the session's tokens, which refreshes them once the
 * access token expired. A refresh Keycloak refuses (invalid_grant), or a new access token without the
 * role or from another issuer, ends the session here: invalidated, sent to the login-refused page.
 * The check is a refresh by time; the refusal comes from Keycloak's answer.
 */
public class SessionRevalidation extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionRevalidation.class);
    static final String CHECKED_AT = SessionRevalidation.class.getName() + ".checked_at";
    static final String TOKEN_SEEN = SessionRevalidation.class.getName() + ".token_seen";

    private final OAuth2AuthorizedClientManager clients;
    private final String registrationId;
    private final String issuer;
    private final String requiredRole;
    private final String rolesClaim;
    private final Duration interval;
    private final Clock clock;
    private final ToolMetrics metrics;

    public SessionRevalidation(OAuth2AuthorizedClientManager clients, String registrationId, String issuer, String requiredRole,
            String rolesClaim, Duration interval, Clock clock, ToolMetrics metrics) {
        this.clients = clients;
        this.registrationId = registrationId;
        this.issuer = issuer;
        this.requiredRole = requiredRole;
        this.rolesClaim = rolesClaim;
        this.interval = interval;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (a instanceof OAuth2AuthenticationToken token && session != null && due(session)) {
            if (!stillValid(token, request, response, session)) {
                revoke(request, response, session);
                return;
            }
            session.setAttribute(CHECKED_AT, clock.millis());
        }
        chain.doFilter(request, response);
    }

    private boolean due(HttpSession session) {
        Object at = session.getAttribute(CHECKED_AT);
        return !(at instanceof Long ms) || clock.millis() - ms >= interval.toMillis();
    }

    private boolean stillValid(OAuth2AuthenticationToken token, HttpServletRequest request, HttpServletResponse response,
            HttpSession session) {
        OAuth2AuthorizedClient client;
        try {
            client = clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(token)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response).build());
        } catch (OAuth2AuthorizationException e) {
            return false;
        } catch (RuntimeException e) {
            // Keycloak unreachable or the session's tokens unreadable: fail closed for an admin surface.
            return false;
        }
        if (client == null || client.getAccessToken() == null) {
            return false;
        }
        String value = client.getAccessToken().getTokenValue();
        if (Integer.valueOf(value.hashCode()).equals(session.getAttribute(TOKEN_SEEN))) {
            return true;
        }
        // A new access token (a refresh): it came straight from the token endpoint over TLS to this
        // confidential client, so its claims are read without a second signature check.
        try {
            Map<String, Object> claims = JWTParser.parse(value).getJWTClaimsSet().getClaims();
            if (!issuer.equals(String.valueOf(claims.get("iss"))) || !Operator.roles(claims, rolesClaim).contains(requiredRole)) {
                return false;
            }
        } catch (java.text.ParseException e) {
            return false;
        }
        session.setAttribute(TOKEN_SEEN, value.hashCode());
        return true;
    }

    private void revoke(HttpServletRequest request, HttpServletResponse response, HttpSession session) throws IOException {
        metrics.login("session_revoked");
        log.atWarn().addKeyValue("event", "auth.login_refused").addKeyValue("reason", "session_revoked")
                .log("the ops session ended: its tokens can't be refreshed or lost the role");
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalid) {
            // Nothing to end.
        }
        SecurityContextHolder.clearContext();
        String target = request.getContextPath() + "/login-refused?reason=session_revoked";
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", target);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        response.sendRedirect(target);
    }
}
