package parity.cctools.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.lifecycle.ConfigurationRefusedException;
import parity.cctools.lifecycle.ReadinessChecks;
import parity.cctools.obs.ToolMetrics;

/**
 * The ops login and web hardening (PLAN.md §2, §7):
 * <ul>
 *   <li>OIDC login against the realm {@code ops} only (authorization code + PKCE S256, a confidential
 *       client); Spring's ID-token validator checks the signature, the issuer (exactly), the audience
 *       and the time; {@link OpsTokenChecks} then the {@code ops} role and the OTP evidence;</li>
 *   <li>the tokens stay in the session (encrypted in Postgres, {@link SessionEncryption}), never in the
 *       browser; {@link SessionRevalidation} ends a session Keycloak no longer backs;</li>
 *   <li>CSRF synchronizer tokens (HTMX sends them from a meta tag), the CSP, HSTS, nosniff,
 *       {@code Referrer-Policy: no-referrer}, a deny-all {@code Permissions-Policy}, {@code no-store};</li>
 *   <li>the capability of every controller method ({@link CapabilityInterceptor}, registered by the
 *       web configuration).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    public static final String CSRF_HEADER = "X-CSRF-TOKEN";
    /** Where to go after the next login (the step-up's resume page); a local path only. */
    public static final String AFTER_LOGIN = SecurityConfig.class.getName() + ".after_login";
    /** Set while a step-up login is under way (logged with the login). */
    public static final String STEP_UP = SecurityConfig.class.getName() + ".step_up";
    private static final Pattern LOCAL_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9/_.?=&%-]{0,300}$");
    private static final Set<String> REASONS = Set.of("wrong_issuer", "wrong_audience", "expired", "no_subject",
            "no_ops_role", "no_otp", "invalid_id_token", "access_denied", "invalid_grant", "authorization_request_not_found");

    /** No local users: the only login is the ops realm (and Boot must not make its default user). */
    @Bean
    UserDetailsService noUsers() {
        return username -> {
            throw new UsernameNotFoundException("no local users");
        };
    }

    @Bean
    OpsClientRegistrations opsClientRegistrations(CcToolsProperties properties) {
        return new OpsClientRegistrations(properties.identity());
    }

    /** The realm's metadata is loaded: a readiness check (the tool can't log anyone in before). */
    @Bean
    ReadinessChecks.Check realmCheck(OpsClientRegistrations registrations) {
        return new ReadinessChecks.Check("ops-realm", registrations::failure);
    }

    @Bean
    OpsTokenChecks opsTokenChecks(CcToolsProperties properties) {
        CcToolsProperties.Identity i = properties.identity();
        return new OpsTokenChecks(i.issuer(), i.clientId(), i.requiredRole(), i.requiredAmr(), i.rolesClaim());
    }

    /** The tokens live in the (encrypted, shared) session, so any pod can refresh them. */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(OpsClientRegistrations registrations,
            OAuth2AuthorizedClientRepository clients) {
        DefaultOAuth2AuthorizedClientManager m = new DefaultOAuth2AuthorizedClientManager(registrations, clients);
        m.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build());
        return m;
    }

    /**
     * The session cookie (PLAN.md §7): {@code __Host-cct_session}, Secure, HttpOnly, SameSite=Strict, path /
     * (a {@code __Host-} cookie must be Secure, path / and have no Domain). Declared here so it is the same
     * with an embedded server, behind any servlet container and in tests.
     */
    @Bean
    org.springframework.session.web.http.CookieSerializer sessionCookie() {
        org.springframework.session.web.http.DefaultCookieSerializer c = new org.springframework.session.web.http.DefaultCookieSerializer();
        c.setCookieName(SESSION_COOKIE);
        c.setUseSecureCookie(true);
        c.setUseHttpOnlyCookie(true);
        c.setSameSite("Strict");
        c.setCookiePath("/");
        return c;
    }

    public static final String SESSION_COOKIE = "__Host-cct_session";

    /** Session attributes encrypted at rest (AES-GCM; the key from a file). */
    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> encryptedSessions(CcToolsProperties properties) {
        String file = properties.session().keyFile();
        if (file == null || file.isBlank()) {
            throw new ConfigurationRefusedException("parity-cc-tools.session.key-file is required (32 random bytes, base64)");
        }
        String key = parity.cctools.config.AppConfig.readSecret(file);
        SessionEncryption enc = new SessionEncryption(key, SecurityConfig.class.getClassLoader());
        return repo -> {
            GenericConversionService cs = new GenericConversionService();
            cs.addConverter(Object.class, byte[].class, enc.serializer());
            cs.addConverter(byte[].class, Object.class, enc.deserializer());
            repo.setConversionService(cs);
        };
    }

    /** The ops checks after Spring's own ID-token validation (signature, iss, aud, exp, nonce). */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> opsUserService(OpsTokenChecks checks, Clock clock) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            OpsTokenChecks.Refusal r = checks.check(user.getIdToken().getClaims(), clock.instant());
            if (r != null) {
                throw new OAuth2AuthenticationException(new OAuth2Error(r.code()));
            }
            return user;
        };
    }

    @Bean
    SecurityFilterChain web(HttpSecurity http, CcToolsProperties properties, OpsClientRegistrations registrations,
            OAuth2AuthorizedClientRepository authorizedClients, OAuth2AuthorizedClientManager clientManager,
            OAuth2UserService<OidcUserRequest, OidcUser> userService, ToolMetrics metrics, Clock clock) throws Exception {
        CcToolsProperties.Identity identity = properties.identity();
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(r -> "GET".equals(r.getMethod()) && r.getHeader("HX-Request") == null
                && !r.getRequestURI().endsWith("/stream"));
        HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
        csrf.setHeaderName(CSRF_HEADER);
        OidcClientInitiatedLogoutSuccessHandler logoutSuccess = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logoutSuccess.setPostLogoutRedirectUri("{baseUrl}" + identity.postLogoutPath());
        SessionRevalidation revalidation = new SessionRevalidation(clientManager, identity.registrationId(), identity.issuer(),
                identity.requiredRole(), identity.rolesClaim(), identity.sessionCheckInterval(), clock, metrics);
        String loginPath = StepUpResolver.BASE_URI + "/" + identity.registrationId();

        http.authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/css/**", "/js/**", "/vendor/**", "/img/**", "/favicon.svg").permitAll()
                        .requestMatchers("/login-refused", identity.postLogoutPath(), "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/backchannel-logout").permitAll()
                        .anyRequest().authenticated())
                .csrf(c -> c.csrfTokenRepository(csrf).ignoringRequestMatchers("/backchannel-logout"))
                .requestCache(r -> r.requestCache(cache))
                .oauth2Login(o -> o.loginPage(loginPath)
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(
                                new StepUpResolver(registrations, identity.stepUpMaxAge().toSeconds())))
                        .authorizedClientRepository(authorizedClients)
                        .userInfoEndpoint(u -> u.oidcUserService(userService))
                        .successHandler(success(cache, metrics))
                        .failureHandler(failure(metrics)))
                .logout(l -> l.logoutUrl("/logout").addLogoutHandler(logoutLog()).logoutSuccessHandler(logoutSuccess))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(htmxEntryPoint(loginPath),
                        r -> r.getHeader("HX-Request") != null))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(ContentSecurityPolicy.policy(identity.issuer())))
                        .httpStrictTransportSecurity(s -> s.disable())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // Always, not only on a secure request: TLS may end at the internal ingress.
                        .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", ContentSecurityPolicy.HSTS))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", ContentSecurityPolicy.PERMISSIONS_POLICY))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin")))
                .addFilterBefore(revalidation, AuthorizationFilter.class);
        return http.build();
    }

    /** HTMX requests without a session: 401 and {@code HX-Redirect} to the login, not a redirected fragment. */
    private static org.springframework.security.web.AuthenticationEntryPoint htmxEntryPoint(String loginPath) {
        HttpStatusEntryPoint status = new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return (request, response, e) -> {
            response.setHeader("HX-Redirect", request.getContextPath() + loginPath);
            status.commence(request, response, e);
        };
    }

    private static AuthenticationSuccessHandler success(HttpSessionRequestCache cache, ToolMetrics metrics) {
        return (HttpServletRequest request, HttpServletResponse response, Authentication auth) -> {
            HttpSession session = request.getSession(false);
            boolean stepUp = session != null && Boolean.TRUE.equals(session.getAttribute(STEP_UP));
            String sub = auth.getPrincipal() instanceof OidcUser u ? u.getSubject() : "unknown";
            metrics.login("ok");
            log.atInfo().addKeyValue("event", "auth.login_succeeded").addKeyValue("actor_sub", sub)
                    .addKeyValue("step_up", stepUp).log("an operator signed in");
            String target = "/";
            if (session != null) {
                session.removeAttribute(STEP_UP);
                Object after = session.getAttribute(AFTER_LOGIN);
                session.removeAttribute(AFTER_LOGIN);
                if (after instanceof String s && LOCAL_PATH.matcher(s).matches()) {
                    target = s;
                } else {
                    SavedRequest saved = cache.getRequest(request, response);
                    String path = saved == null ? null : localPart(saved.getRedirectUrl(), request);
                    if (path != null) {
                        target = path;
                    }
                }
                cache.removeRequest(request, response);
            }
            response.sendRedirect(request.getContextPath() + target);
        };
    }

    /** The path and query of a saved URL on this host, or null. */
    static String localPart(String url, HttpServletRequest request) {
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getHost() != null && !u.getHost().equalsIgnoreCase(request.getServerName())) {
                return null;
            }
            String path = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            String ctx = request.getContextPath();
            if (!ctx.isEmpty() && path.startsWith(ctx)) {
                path = path.substring(ctx.length());
            }
            return LOCAL_PATH.matcher(path).matches() ? path : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AuthenticationFailureHandler failure(ToolMetrics metrics) {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException e) -> {
            String code = e instanceof OAuth2AuthenticationException o ? o.getError().getErrorCode() : "login_failed";
            String reason = REASONS.contains(code) ? code : "login_failed";
            metrics.login("refused");
            log.atWarn().addKeyValue("event", "auth.login_refused").addKeyValue("reason", reason)
                    .log("an ops login was refused");
            response.sendRedirect(request.getContextPath() + "/login-refused?reason=" + reason);
        };
    }

    private static LogoutHandler logoutLog() {
        return (request, response, auth) -> {
            if (auth != null && auth.getPrincipal() instanceof OidcUser u) {
                log.atInfo().addKeyValue("event", "auth.logged_out").addKeyValue("actor_sub", u.getSubject())
                        .addKeyValue("cause", "http:logout").log("an operator signed out");
            }
        };
    }

    /** Right after Spring Session's filter: the absolute session limit. */
    @Bean
    FilterRegistrationBean<SessionAbsoluteTimeout> sessionAbsoluteTimeoutFilter(CcToolsProperties properties) {
        FilterRegistrationBean<SessionAbsoluteTimeout> r =
                new FilterRegistrationBean<>(new SessionAbsoluteTimeout(properties.session().absoluteTimeout()));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 60);
        return r;
    }
}
