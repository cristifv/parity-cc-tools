package parity.cctools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import parity.cctools.ParityCcToolsApplication;
import parity.cctools.security.ContentSecurityPolicy;
import parity.cctools.support.AppStack;
import parity.cctools.support.Containers;
import parity.cctools.support.FakeParityRs;
import parity.cctools.support.TestProps;

/**
 * The web layer (PLAN.md §2, §3, §7) through MockMvc: the login gate, the headers and CSP, CSRF, the
 * capability check, the move wizard end to end (dry run, typed confirmation, parity-rs gets the command),
 * the step-up for a High action (nothing sent; Keycloak asked with max_age and PKCE S256), and every page
 * renders.
 */
@SpringBootTest(classes = ParityCcToolsApplication.class, properties = {
        "parity-cc-tools.environment=test",
        "parity-cc-tools.instance=web-it",
        "parity-cc-tools.routing.transport=test",
        "parity-cc-tools.identity.issuer=" + TestProps.ISSUER,
        "parity-cc-tools.identity.authorization-uri=" + TestProps.ISSUER + "/protocol/openid-connect/auth",
        "parity-cc-tools.identity.token-uri=http://127.0.0.1:1/token",
        "parity-cc-tools.identity.jwk-set-uri=http://127.0.0.1:1/certs",
        "parity-cc-tools.identity.end-session-uri=" + TestProps.ISSUER + "/protocol/openid-connect/logout",
        "parity-cc-tools.zones.eu1a.kafka.bootstrap-servers=127.0.0.1:1",
        "parity-cc-tools.zones.eu1a.kubernetes.enabled=false",
        "parity-cc-tools.health.enabled=false",
        "parity-cc-tools.control.enabled=false",
        "parity-cc-tools.lifecycle.healthy-file=target/web-it.healthy",
        "parity-cc-tools.lifecycle.drained-file=target/web-it.drained",
        "parity-cc-tools.lifecycle.deregistration-delay=0s",
        "management.server.port=0"
})
@AutoConfigureMockMvc
@Import(WebIT.Beans.class)
class WebIT {

    @TestConfiguration
    static class Beans {
        @Bean
        FakeParityRs fakeRsTransport() {
            return new FakeParityRs();
        }

        /**
         * Stands in for Keycloak's token endpoint behind the session check: a refreshed access token with
         * the ops role, or (for a subject starting "revoked") invalid_grant, as a disabled account gets.
         */
        @Bean
        @org.springframework.context.annotation.Primary
        org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager testAuthorizedClients() {
            return req -> {
                String sub = req.getPrincipal().getName();
                if (sub.startsWith("revoked")) {
                    throw new org.springframework.security.oauth2.client.ClientAuthorizationException(
                            new org.springframework.security.oauth2.core.OAuth2Error("invalid_grant"), "ops");
                }
                Instant now = Instant.now();
                java.util.Base64.Encoder b = java.util.Base64.getUrlEncoder().withoutPadding();
                String at = b.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "."
                        + b.encodeToString(("{\"iss\":\"" + TestProps.ISSUER + "\",\"roles\":[\"ops\"],\"exp\":"
                                + now.plusSeconds(300).getEpochSecond() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".";
                return new org.springframework.security.oauth2.client.OAuth2AuthorizedClient(OPS, sub,
                        new org.springframework.security.oauth2.core.OAuth2AccessToken(
                                org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, at, now, now.plusSeconds(300)));
            };
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        var pg = Containers.postgres();
        var s = AppStack.secrets();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", () -> Containers.APP_ROLE);
        r.add("parity-cc-tools.db.password-file", () -> s.resolve("app-password").toString());
        r.add("parity-cc-tools.db.flyway-user", pg::getUsername);
        r.add("parity-cc-tools.db.flyway-password-file", () -> s.resolve("owner-password").toString());
        r.add("parity-cc-tools.session.key-file", () -> s.resolve("session-key").toString());
        r.add("parity-cc-tools.identity.client-secret", () -> "web-it-client-secret");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeParityRs rs;

    static final ClientRegistration OPS = ClientRegistration.withRegistrationId("ops").clientId("parity-cc-tools").clientSecret("x")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri(TestProps.ISSUER + "/auth").tokenUri("http://127.0.0.1:1/token").jwkSetUri("http://127.0.0.1:1/certs")
            .issuerUri(TestProps.ISSUER).userNameAttributeName("sub").build();

    static RequestPostProcessor operator(Instant authTime, List<String> roles) {
        return operator("5f0c2a9e-1b2c-4d3e-8f40-123456789abc", authTime, roles);
    }

    static RequestPostProcessor operator(String sub, Instant authTime, List<String> roles) {
        Instant now = Instant.now();
        return oidcLogin().clientRegistration(OPS)
                .idToken(t -> t.issuer(TestProps.ISSUER).subject(sub).audience(List.of("parity-cc-tools"))
                        .claim("preferred_username", "jdoe").claim("roles", roles).claim("amr", List.of("pwd", "otp"))
                        .claim("auth_time", authTime).issuedAt(now).expiresAt(now.plusSeconds(300)));
    }

    static RequestPostProcessor ops() {
        return operator(Instant.now().minusSeconds(30), List.of("ops"));
    }

    /** The next request in the same session: Spring Session's cookie when it set one, and the request's session. */
    static RequestPostProcessor session(MvcResult r) {
        Cookie c = r.getResponse().getCookie("__Host-cct_session");
        var s = (org.springframework.mock.web.MockHttpSession) r.getRequest().getSession(false);
        assertThat(c != null || s != null).as("a session").isTrue();
        return req -> {
            if (c != null) {
                req.setCookies(c);
            }
            if (s != null) {
                req.setSession(s);
            }
            return req;
        };
    }

    static String opId(String html) {
        Matcher m = Pattern.compile("/routing/actions/([0-9a-f-]{36})/confirm|/rollout/actions/([0-9a-f-]{36})/confirm").matcher(html);
        assertThat(m.find()).as("a confirm form").isTrue();
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    @Test
    void withoutALoginEveryPageGoesToTheOpsRealm() throws Exception {
        MvcResult r = mvc.perform(get("/tenants").accept(org.springframework.http.MediaType.TEXT_HTML)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getHeader("Location")).endsWith("/oauth2/authorization/ops");
        MvcResult h = mvc.perform(get("/tenants").header("HX-Request", "true")).andReturn();
        assertThat(h.getResponse().getStatus()).isEqualTo(401);
        assertThat(h.getResponse().getHeader("HX-Redirect")).isEqualTo("/oauth2/authorization/ops");
        assertThat(mvc.perform(get("/login-refused").param("reason", "no_otp")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theLoginAsksForPkceAndTheStepUpForMaxAge() throws Exception {
        String normal = mvc.perform(get("/oauth2/authorization/ops")).andReturn().getResponse().getHeader("Location");
        assertThat(normal).startsWith(TestProps.ISSUER + "/protocol/openid-connect/auth").contains("code_challenge_method=S256")
                .contains("code_challenge=").doesNotContain("max_age");
        String stepUp = mvc.perform(get("/oauth2/authorization/ops").param("step_up", "1")).andReturn().getResponse().getHeader("Location");
        assertThat(stepUp).contains("max_age=300").contains("code_challenge_method=S256");
    }

    @Test
    void pagesCarryTheHeaders() throws Exception {
        MvcResult r = mvc.perform(get("/tenants").with(ops())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        var h = r.getResponse();
        assertThat(h.getHeader("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy.policy(TestProps.ISSUER))
                .contains("script-src 'self'").contains("frame-ancestors 'none'").contains("form-action 'self' https://id.ops.parity.internal");
        assertThat(h.getHeader("Strict-Transport-Security")).startsWith("max-age=");
        assertThat(h.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(h.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getHeader("Permissions-Policy")).contains("camera=()").contains("microphone=()");
        assertThat(h.getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getResponse().getContentAsString()).contains("acme").contains("globex").doesNotContain("<script>");
    }

    @Test
    void withoutTheOpsRoleNothingIsServedAndAPostNeedsCsrf() throws Exception {
        assertThat(mvc.perform(get("/tenants").with(operator(Instant.now(), List.of("viewer")))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(post("/tenants/eu1a/acme/review").param("action", "pin").with(ops())).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void theMoveWizardDryRunsThenSendsWithTheTypedName() throws Exception {
        int before = rs.commands("move_tenant").size();
        MvcResult review = mvc.perform(post("/tenants/eu1a/acme/review").with(ops()).with(csrf()).param("action", "move")
                .param("toUnit", "bin-b").param("reason", "Move acme to the previous release for a support case")).andReturn();
        assertThat(review.getResponse().getStatus()).isEqualTo(200);
        String html = review.getResponse().getContentAsString();
        assertThat(html).contains("parity-rs's plan").contains("bin-b runs the previous release").contains("Type");
        String opId = opId(html);
        assertThat(rs.commands("validate_move")).anyMatch(x -> opId.equals(x.body().path("op_id").asString()));
        assertThat(review.getResponse().getHeaders("Set-Cookie")).anySatisfy(h -> assertThat(h).startsWith("__Host-cct_session=")
                .contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/").doesNotContain("Domain"));
        RequestPostProcessor s = session(review);
        // A wrong typed name: refused, nothing sent.
        MvcResult wrong = mvc.perform(post("/routing/actions/" + opId + "/confirm").with(s).with(ops()).with(csrf())
                .param("reason", "Move acme to the previous release for a support case").param("typed", "globex")).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).contains("Type the exact name");
        assertThat(rs.commands("move_tenant")).hasSize(before);
        MvcResult ok = mvc.perform(post("/routing/actions/" + opId + "/confirm").with(s).with(ops()).with(csrf())
                .param("reason", "Move acme to the previous release for a support case").param("typed", "acme")).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(302);
        assertThat(ok.getResponse().getHeader("Location")).isEqualTo("/ops/" + opId);
        assertThat(rs.commands("move_tenant")).hasSize(before + 1);
        // The same confirmation can't be used twice.
        MvcResult twice = mvc.perform(post("/routing/actions/" + opId + "/confirm").with(s).with(ops()).with(csrf())
                .param("reason", "Move acme to the previous release for a support case").param("typed", "acme")).andReturn();
        assertThat(twice.getResponse().getContentAsString()).contains("expired or was already used");
        assertThat(rs.commands("move_tenant")).hasSize(before + 1);
        assertThat(mvc.perform(get("/ops/" + opId).with(ops())).andReturn().getResponse().getContentAsString())
                .contains(opId).contains("tenant.move requested");
        assertThat(rs.violations).isEmpty();
    }

    @Test
    void aZoneMoveIsShownButRefusedInV1() throws Exception {
        String page = mvc.perform(get("/tenants/eu1a/acme").with(ops())).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("zone_copy_unavailable");
        MvcResult r = mvc.perform(post("/tenants/eu1a/acme/review").with(ops()).with(csrf()).param("action", "move_zone")
                .param("toZone", "eu2a").param("reason", "Switch acme's home zone for residency")).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("zone_copy_unavailable").doesNotContain("/confirm\"");
        assertThat(rs.commands("move_tenant_zone")).isEmpty();
    }

    @Test
    void aHighActionWithAStaleLoginStepsUpAndSendsNothing() throws Exception {
        RequestPostProcessor stale = operator(Instant.now().minusSeconds(3600), List.of("ops"));
        MvcResult review = mvc.perform(post("/units/eu1a/bin-c/review").with(stale).with(csrf()).param("action", "start_rollout")
                .param("release", "1.9.0").param("rings", "test:test;all:percent=100")).andReturn();
        assertThat(review.getResponse().getContentAsString()).contains("risk-high");
        String opId = opId(review.getResponse().getContentAsString());
        RequestPostProcessor s = session(review);
        MvcResult confirm = mvc.perform(post("/rollout/actions/" + opId + "/confirm").with(s).with(stale).with(csrf())
                .param("reason", "Start the 1.9.0 rollout on bin-c").param("typed", "bin-c")).andReturn();
        assertThat(confirm.getResponse().getStatus()).isEqualTo(302);
        assertThat(confirm.getResponse().getHeader("Location")).isEqualTo("/oauth2/authorization/ops?step_up=1");
        assertThat(rs.commands("start_rollout")).isEmpty();
        // After the fresh login the resume page has the inputs; confirming sends it.
        String resumed = mvc.perform(get("/rollout/actions/" + opId + "/resume").with(s).with(ops())).andReturn().getResponse()
                .getContentAsString();
        assertThat(resumed).contains("You signed in again").contains("Start the 1.9.0 rollout on bin-c");
        MvcResult sent = mvc.perform(post("/rollout/actions/" + opId + "/confirm").with(s).with(ops()).with(csrf())
                .param("reason", "Start the 1.9.0 rollout on bin-c").param("typed", "bin-c")).andReturn();
        assertThat(sent.getResponse().getHeader("Location")).isEqualTo("/ops/" + opId);
        assertThat(rs.commands("start_rollout")).hasSize(1);
    }

    @Test
    void aSessionTheOpsRealmNoLongerBacksEnds() throws Exception {
        MvcResult first = mvc.perform(post("/tenants/eu1a/acme/review").with(ops()).with(csrf()).param("action", "pin")
                .param("reason", "Pin acme during the incident")).andReturn();
        RequestPostProcessor s = session(first);
        MvcResult next = mvc.perform(get("/tenants").with(s).with(operator("revoked-5f0c2a9e", Instant.now(), List.of("ops")))).andReturn();
        assertThat(next.getResponse().getStatus()).isEqualTo(302);
        assertThat(next.getResponse().getHeader("Location")).isEqualTo("/login-refused?reason=session_revoked");
    }

    @Test
    void everyPageRenders() throws Exception {
        for (String p : List.of("/tenants", "/tenants/eu1a/acme", "/units", "/rollouts", "/operations", "/services", "/health", "/audit",
                "/topology", "/me", "/health/table")) {
            MvcResult r = mvc.perform(get(p).with(ops())).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(200);
        }
    }
}
