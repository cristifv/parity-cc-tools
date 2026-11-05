package parity.cctools.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The ops realm's token rules (PLAN.md §2), on every ID token at login and again when the session's
 * tokens are refreshed:
 * <ul>
 *   <li>{@code iss} is exactly the ops realm (a token of the tenant realm {@code parity} is refused);</li>
 *   <li>{@code aud} holds this client; not expired; a subject;</li>
 *   <li>the {@code ops} realm role;</li>
 *   <li>OTP evidence: {@code amr} holds {@code otp} (the realm's browser flow requires it; the AMR mapper
 *       puts it in the token). A token without it (a password-only login) is refused.</li>
 * </ul>
 */
public final class OpsTokenChecks {

    /** Why a token was refused (log attrs.reason, the login-refused page). */
    public enum Refusal {
        WRONG_ISSUER("wrong_issuer"),
        WRONG_AUDIENCE("wrong_audience"),
        EXPIRED("expired"),
        NO_SUBJECT("no_subject"),
        NO_OPS_ROLE("no_ops_role"),
        NO_OTP("no_otp");

        private final String code;

        Refusal(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final String issuer;
    private final String clientId;
    private final String requiredRole;
    private final String requiredAmr;
    private final String rolesClaim;

    public OpsTokenChecks(String issuer, String clientId, String requiredRole, String requiredAmr, String rolesClaim) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.requiredRole = requiredRole;
        this.requiredAmr = requiredAmr;
        this.rolesClaim = rolesClaim;
    }

    /** Null when the claims pass; else the first rule they break. */
    public Refusal check(Map<String, Object> claims, Instant now) {
        Object iss = claims.get("iss");
        if (!(iss != null && issuer.equals(iss.toString()))) {
            return Refusal.WRONG_ISSUER;
        }
        if (!audience(claims.get("aud")).contains(clientId)) {
            return Refusal.WRONG_AUDIENCE;
        }
        Instant exp = instant(claims.get("exp"));
        if (exp == null || !exp.isAfter(now)) {
            return Refusal.EXPIRED;
        }
        Object sub = claims.get("sub");
        if (sub == null || sub.toString().isBlank()) {
            return Refusal.NO_SUBJECT;
        }
        if (!Operator.roles(claims, rolesClaim).contains(requiredRole)) {
            return Refusal.NO_OPS_ROLE;
        }
        if (!Operator.strings(claims.get("amr")).contains(requiredAmr)) {
            return Refusal.NO_OTP;
        }
        return null;
    }

    /** The same rules as a JWT validator, for the ID token decoder. */
    public OAuth2TokenValidator<Jwt> validator(java.time.Clock clock) {
        return jwt -> {
            Refusal r = check(jwt.getClaims(), clock.instant());
            return r == null ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(r.code(), "the token isn't an ops login", null));
        };
    }

    static List<String> audience(Object aud) {
        if (aud instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return aud == null ? List.of() : List.of(aud.toString());
    }

    static Instant instant(Object v) {
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        return null;
    }
}
