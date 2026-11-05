package parity.cctools.security;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import parity.cctools.config.CcToolsProperties;

/**
 * OIDC back-channel logout (PLAN.md §2): Keycloak posts a signed logout token when an operator's session
 * ends there (logout, an admin's "sign out", a disabled account); every session of that operator in
 * Postgres is deleted, on whichever pod. The token must be signed by the ops realm, name it as issuer
 * exactly, this client as audience, carry the back-channel logout event and no nonce.
 */
@RestController
public class BackchannelLogout {

    private static final Logger log = LoggerFactory.getLogger(BackchannelLogout.class);
    static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";

    private final OpsClientRegistrations registrations;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final CcToolsProperties.Identity identity;
    private final Clock clock;
    private volatile NimbusJwtDecoder decoder;

    public BackchannelLogout(OpsClientRegistrations registrations, FindByIndexNameSessionRepository<? extends Session> sessions,
            CcToolsProperties properties, Clock clock) {
        this.registrations = registrations;
        this.sessions = sessions;
        this.identity = properties.identity();
        this.clock = clock;
    }

    @PostMapping(path = "/backchannel-logout", consumes = "application/x-www-form-urlencoded")
    @PublicEndpoint("Keycloak's server-to-server call; it authenticates with its signed logout token")
    public ResponseEntity<Void> logout(@RequestParam("logout_token") String logoutToken) {
        String sub = subject(logoutToken);
        if (sub == null) {
            log.atWarn().addKeyValue("event", "auth.login_refused").addKeyValue("reason", "bad_logout_token")
                    .log("a back-channel logout token was refused");
            return ResponseEntity.badRequest().build();
        }
        Map<String, ? extends Session> found = sessions.findByPrincipalName(sub);
        for (String id : List.copyOf(found.keySet())) {
            sessions.deleteById(id);
        }
        log.atInfo().addKeyValue("event", "auth.logged_out").addKeyValue("actor_sub", sub)
                .addKeyValue("sessions", found.size()).addKeyValue("cause", "http:backchannel-logout")
                .log("the ops realm ended an operator's sessions");
        return ResponseEntity.ok().build();
    }

    /** The token's subject when it is a valid logout token of the ops realm for this client; else null. */
    String subject(String token) {
        if (token == null || token.length() > 16_384) {
            return null;
        }
        NimbusJwtDecoder d = decoder();
        if (d == null) {
            return null;
        }
        Jwt jwt;
        try {
            jwt = d.decode(token);
        } catch (JwtException e) {
            return null;
        }
        Map<String, Object> c = jwt.getClaims();
        if (!identity.issuer().equals(String.valueOf(c.get("iss")))
                || !OpsTokenChecks.audience(c.get("aud")).contains(identity.clientId())
                || c.containsKey("nonce")
                || !(c.get("events") instanceof Map<?, ?> events && events.containsKey(EVENT))
                || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(60))) {
            return null;
        }
        String sub = jwt.getSubject();
        return sub == null || sub.isBlank() ? null : sub;
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder d = decoder;
        if (d != null) {
            return d;
        }
        ClientRegistration r = registrations.get();
        if (r == null) {
            return null;
        }
        d = NimbusJwtDecoder.withJwkSetUri(r.getProviderDetails().getJwkSetUri()).build();
        // Signature and time only here; the claims are checked above.
        d.setJwtValidator(new org.springframework.security.oauth2.jwt.JwtTimestampValidator());
        decoder = d;
        return d;
    }
}
