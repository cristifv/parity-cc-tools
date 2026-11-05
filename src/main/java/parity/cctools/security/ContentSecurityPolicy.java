package parity.cctools.security;

import java.net.URI;

/**
 * The CSP (PLAN.md §7, as parity-frontend §3.2): scripts, styles and connections from {@code 'self'}
 * only (HTMX and its SSE extension are vendored), no frames, no inline code; forms may post only here
 * and to the ops realm (the logout's redirect to Keycloak's end-session endpoint is a form action).
 */
public final class ContentSecurityPolicy {

    private ContentSecurityPolicy() {}

    public static String policy(String issuer) {
        URI u = URI.create(issuer);
        String realmOrigin = u.getScheme() + "://" + u.getAuthority();
        return "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
                + "frame-src 'none'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'; form-action 'self' "
                + realmOrigin;
    }

    /** Every browser feature off. */
    public static final String PERMISSIONS_POLICY = "accelerometer=(), autoplay=(), camera=(), clipboard-read=(), "
            + "display-capture=(), fullscreen=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), "
            + "midi=(), payment=(), publickey-credentials-get=(), screen-wake-lock=(), usb=(), xr-spatial-tracking=()";

    /** HSTS: one year, subdomains (internal names only; no preload list for an internal tool). */
    public static final String HSTS = "max-age=31536000; includeSubDomains";
}
