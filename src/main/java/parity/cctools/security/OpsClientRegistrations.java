package parity.cctools.security;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import parity.cctools.config.CcToolsProperties;

/**
 * The one client registration, the ops realm (PLAN.md §2). Loaded lazily: the endpoints come from the
 * issuer's OIDC metadata (its {@code issuer} must equal the configured one exactly) the first time they
 * are needed, so the tool starts while Keycloak is down; the readiness check ({@link #failure()}) keeps
 * the pod out of rotation until the metadata is loaded. Tests set the endpoints explicitly.
 */
public final class OpsClientRegistrations implements ClientRegistrationRepository {

    private final CcToolsProperties.Identity identity;
    private final AtomicReference<ClientRegistration> loaded = new AtomicReference<>();
    private volatile String failure = "the ops realm's metadata isn't loaded yet";

    public OpsClientRegistrations(CcToolsProperties.Identity identity) {
        this.identity = identity;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!identity.registrationId().equals(registrationId)) {
            return null;
        }
        return get();
    }

    /** The registration, loading it if needed; null while the realm can't be reached. */
    public ClientRegistration get() {
        ClientRegistration r = loaded.get();
        if (r != null) {
            return r;
        }
        try {
            r = build();
            loaded.compareAndSet(null, r);
            failure = null;
            return loaded.get();
        } catch (RuntimeException e) {
            failure = "the ops realm's metadata can't be loaded (" + e.getClass().getSimpleName() + ")";
            return null;
        }
    }

    /** Null once loaded (the readiness check); it tries again on each call until then. */
    public String failure() {
        if (loaded.get() == null) {
            get();
        }
        return failure;
    }

    private ClientRegistration build() {
        ClientRegistration.Builder b;
        if (identity.explicitEndpoints()) {
            b = ClientRegistration.withRegistrationId(identity.registrationId())
                    .authorizationUri(identity.authorizationUri().toString())
                    .tokenUri(identity.tokenUri().toString())
                    .jwkSetUri(identity.jwkSetUri().toString())
                    .issuerUri(identity.issuer())
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .providerConfigurationMetadata(identity.endSessionUri() == null ? Map.of()
                            : Map.of("end_session_endpoint", identity.endSessionUri().toString()));
        } else {
            b = ClientRegistrations.fromIssuerLocation(identity.issuer()).registrationId(identity.registrationId())
                    .userNameAttributeName(IdTokenClaimNames.SUB);
        }
        return b.clientId(identity.clientId())
                .clientSecret(identity.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(identity.scopes())
                .clientName("ops")
                .build();
    }
}
