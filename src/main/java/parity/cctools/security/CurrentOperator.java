package parity.cctools.security;

import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import parity.cctools.config.CcToolsProperties;

/** The operator of the current request, from the session's OIDC login (never from a request parameter). */
@Component
public class CurrentOperator {

    private final String rolesClaim;

    public CurrentOperator(CcToolsProperties properties) {
        this.rolesClaim = properties.identity().rolesClaim();
    }

    /** The operator, or {@link ForbiddenException} when the request has no ops login. */
    public Operator require() {
        Operator o = get();
        if (o == null) {
            throw new ForbiddenException("no ops login");
        }
        return o;
    }

    /** The operator, or null. */
    public Operator get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        return of(user.getClaims(), rolesClaim);
    }

    public static Operator of(Map<String, Object> claims, String rolesClaim) {
        var roles = Operator.roles(claims, rolesClaim);
        Object name = claims.get("preferred_username");
        Instant authTime = OpsTokenChecks.instant(claims.get("auth_time"));
        return new Operator(String.valueOf(claims.get("sub")), name == null ? "unknown" : name.toString(), roles,
                authTime, Operator.strings(claims.get("amr")), RoleCapabilities.of(roles));
    }
}
