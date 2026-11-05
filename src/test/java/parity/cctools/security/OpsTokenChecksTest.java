package parity.cctools.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PLAN.md §2 token checks: only an ops-realm token with the ops role and OTP evidence passes. */
class OpsTokenChecksTest {

    static final String OPS = "https://id.ops.parity.internal/realms/ops";
    static final String PARITY = "https://id.eu1.parity.example/realms/parity";
    final OpsTokenChecks checks = new OpsTokenChecks(OPS, "parity-cc-tools", "ops", "otp", "roles");
    final Instant now = Instant.parse("2026-11-03T10:00:00Z");

    Map<String, Object> good() {
        Map<String, Object> c = new HashMap<>();
        c.put("iss", OPS);
        c.put("aud", List.of("parity-cc-tools"));
        c.put("exp", now.plusSeconds(300));
        c.put("sub", "5f0c2a9e-1b2c-4d3e-8f40-123456789abc");
        c.put("roles", List.of("ops"));
        c.put("amr", List.of("pwd", "otp"));
        return c;
    }

    @Test
    void anOpsLoginWithOtpPasses() {
        assertThat(checks.check(good(), now)).isNull();
    }

    @Test
    void aTokenOfTheTenantRealmParityIsRefused() {
        Map<String, Object> c = good();
        c.put("iss", PARITY);
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.WRONG_ISSUER);
        c.put("iss", OPS + "/");
        assertThat(checks.check(c, now)).as("exact, not a prefix").isEqualTo(OpsTokenChecks.Refusal.WRONG_ISSUER);
        c.remove("iss");
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.WRONG_ISSUER);
    }

    @Test
    void aLoginWithoutOtpIsRefused() {
        Map<String, Object> c = good();
        c.put("amr", List.of("pwd"));
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.NO_OTP);
        c.remove("amr");
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.NO_OTP);
    }

    @Test
    void noOpsRoleWrongAudienceExpiredAreRefused() {
        Map<String, Object> c = good();
        c.put("roles", List.of("viewer"));
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.NO_OPS_ROLE);
        c = good();
        c.remove("roles");
        c.put("realm_access", Map.of("roles", List.of("ops")));
        assertThat(checks.check(c, now)).as("Keycloak's realm_access.roles counts too").isNull();
        c = good();
        c.put("aud", List.of("account", "admin-console"));
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.WRONG_AUDIENCE);
        c = good();
        c.put("exp", now.minusSeconds(1));
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.EXPIRED);
        c = good();
        c.put("sub", " ");
        assertThat(checks.check(c, now)).isEqualTo(OpsTokenChecks.Refusal.NO_SUBJECT);
    }

    @Test
    void stepUpFreshnessIsAuthTimeWithinMaxAge() {
        Operator fresh = new Operator("s", "jdoe", List.of("ops"), now.minusSeconds(299), List.of("otp"), RoleCapabilities.of(List.of("ops")));
        Operator stale = new Operator("s", "jdoe", List.of("ops"), now.minusSeconds(301), List.of("otp"), RoleCapabilities.of(List.of("ops")));
        Operator none = new Operator("s", "jdoe", List.of("ops"), null, List.of("otp"), RoleCapabilities.of(List.of("ops")));
        Operator future = new Operator("s", "jdoe", List.of("ops"), now.plusSeconds(3600), List.of("otp"), RoleCapabilities.of(List.of("ops")));
        java.time.Duration max = java.time.Duration.ofMinutes(5);
        assertThat(fresh.freshLogin(now, max)).isTrue();
        assertThat(stale.freshLogin(now, max)).isFalse();
        assertThat(none.freshLogin(now, max)).isFalse();
        assertThat(future.freshLogin(now, max)).as("an auth_time far in the future isn't trusted").isFalse();
    }
}
