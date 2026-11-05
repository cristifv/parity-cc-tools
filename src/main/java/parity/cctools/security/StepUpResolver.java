package parity.cctools.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The authorization request to the ops realm: always PKCE S256 (the client is confidential; Spring adds
 * PKCE only to public clients by default); with {@code step_up=1}, also {@code max_age} (PLAN.md §2), so
 * Keycloak asks for the password and the OTP again when the last full login is older.
 */
public final class StepUpResolver implements OAuth2AuthorizationRequestResolver {

    public static final String BASE_URI = "/oauth2/authorization";
    public static final String STEP_UP_PARAM = "step_up";

    private final DefaultOAuth2AuthorizationRequestResolver normal;
    private final DefaultOAuth2AuthorizationRequestResolver stepUp;

    public StepUpResolver(ClientRegistrationRepository registrations, long maxAgeSeconds) {
        Consumer<OAuth2AuthorizationRequest.Builder> pkce = OAuth2AuthorizationRequestCustomizers.withPkce();
        normal = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
        normal.setAuthorizationRequestCustomizer(pkce);
        stepUp = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
        String maxAge = Long.toString(maxAgeSeconds);
        stepUp.setAuthorizationRequestCustomizer(pkce.andThen(b -> b.additionalParameters(p -> p.put("max_age", maxAge))));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return wantsStepUp(request) ? stepUp.resolve(request) : normal.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return wantsStepUp(request) ? stepUp.resolve(request, clientRegistrationId) : normal.resolve(request, clientRegistrationId);
    }

    private static boolean wantsStepUp(HttpServletRequest request) {
        return "1".equals(request.getParameter(STEP_UP_PARAM));
    }
}
