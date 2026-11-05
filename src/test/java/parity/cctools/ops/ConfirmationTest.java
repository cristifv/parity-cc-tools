package parity.cctools.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import parity.cctools.obs.ToolMetrics;
import parity.cctools.security.ForbiddenException;
import parity.cctools.security.Operator;
import parity.cctools.security.RoleCapabilities;
import parity.cctools.support.TestProps;

/**
 * PLAN.md §3: the risk tier per action and what each tier needs (reason; typed name; fresh login), and
 * force where the guards ask for it. Nothing here writes or sends: these are the checks before that.
 */
class ConfirmationTest {

    final Instant now = Instant.parse("2026-11-03T10:00:00Z");
    final ActionService actions = new ActionService(null, null, null, null, new ToolMetrics(new SimpleMeterRegistry()),
            Clock.fixed(now, ZoneOffset.UTC), TestProps.oneZone());
    final Operator fresh = new Operator("sub-1", "jdoe", List.of("ops"), now.minusSeconds(60), List.of("pwd", "otp"),
            RoleCapabilities.of(List.of("ops")));
    final Operator stale = new Operator("sub-1", "jdoe", List.of("ops"), now.minusSeconds(900), List.of("pwd", "otp"),
            RoleCapabilities.of(List.of("ops")));
    final Operator nobody = new Operator("sub-2", "guest", List.of(), now, List.of("otp"), RoleCapabilities.of(List.of()));

    Pending pending(Action a, RiskPolicy.Facts f) {
        return new Pending("0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b", "0193a1b2-c3d4-7e5f-9a6b-7c8d9e0f1a2c", "cct:x", a,
                RiskPolicy.tier(a, f), RiskPolicy.forceRequired(a, f), "eu1a", "acme", null, null, null, null, "acme", Map.of(), null,
                null, "t", "/", now.toEpochMilli(), Pending.Form.empty());
    }

    static Pending.Form form(String typed, boolean force) {
        return new Pending.Form("Move acme for a support case", "", typed, force);
    }

    @Test
    void tiersFollowThePlan() {
        RiskPolicy.Facts none = RiskPolicy.Facts.none();
        assertThat(RiskPolicy.tier(Action.TENANT_PIN, none)).isEqualTo(Risk.LOW);
        assertThat(RiskPolicy.tier(Action.ROLLOUT_STOP, none)).isEqualTo(Risk.LOW);
        assertThat(RiskPolicy.tier(Action.TENANT_MOVE, none)).isEqualTo(Risk.MEDIUM);
        assertThat(RiskPolicy.tier(Action.TENANT_MOVE, new RiskPolicy.Facts(true, false, false, false))).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.tier(Action.ROLLOUT_ADVANCE, none)).isEqualTo(Risk.MEDIUM);
        assertThat(RiskPolicy.tier(Action.SAGA_CANCEL, none)).isEqualTo(Risk.MEDIUM);
        assertThat(RiskPolicy.tier(Action.TENANT_MOVE_ZONE, none)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.tier(Action.ROLLOUT_START, none)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.tier(Action.ROLLOUT_ROLLBACK, none)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.tier(Action.SAGA_RESOLVE, none)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.tier(Action.UNIT_DRAIN, none)).isEqualTo(Risk.MEDIUM);
        assertThat(RiskPolicy.tier(Action.UNIT_DRAIN, new RiskPolicy.Facts(false, true, false, false))).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.tier(Action.SERVICE_CORDON, none)).isEqualTo(Risk.MEDIUM);
        RiskPolicy.Facts last = new RiskPolicy.Facts(false, false, true, false);
        assertThat(RiskPolicy.tier(Action.SERVICE_CORDON, last)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.forceRequired(Action.SERVICE_CORDON, last)).isTrue();
        assertThat(RiskPolicy.tier(Action.MEDIA_NODE_OUT, last)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.forceRequired(Action.MEDIA_NODE_OUT, last)).isTrue();
        assertThat(RiskPolicy.tier(Action.SERVICE_STOP, none)).isEqualTo(Risk.HIGH);
        assertThat(RiskPolicy.forceRequired(Action.SERVICE_STOP, none)).isFalse();
        assertThat(RiskPolicy.forceRequired(Action.SERVICE_STOP, new RiskPolicy.Facts(false, false, false, true))).isTrue();
    }

    @Test
    void mediumNeedsTheExactTypedName() {
        Pending p = pending(Action.TENANT_MOVE, RiskPolicy.Facts.none());
        assertThatThrownBy(() -> actions.check(fresh, p, form("", false)))
                .isInstanceOfSatisfying(ActionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(ActionRefusedException.Reason.TYPED_CONFIRMATION));
        assertThatThrownBy(() -> actions.check(fresh, p, form("acme2", false))).isInstanceOf(ActionRefusedException.class);
        assertThatThrownBy(() -> actions.check(fresh, p, form("ACME", false))).isInstanceOf(ActionRefusedException.class);
        actions.check(fresh, p, form(" acme ", false));
    }

    @Test
    void lowNeedsAReasonOnly() {
        Pending p = pending(Action.TENANT_PIN, RiskPolicy.Facts.none());
        actions.check(fresh, p, form("", false));
        assertThatThrownBy(() -> actions.check(fresh, p, new Pending.Form("too short", "", "", false)))
                .isInstanceOfSatisfying(ActionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(ActionRefusedException.Reason.REASON_LENGTH));
        assertThatThrownBy(() -> actions.check(fresh, p, new Pending.Form("x".repeat(501), "", "", false))).isInstanceOf(ActionRefusedException.class);
        assertThatThrownBy(() -> actions.check(fresh, p, new Pending.Form("a good reason here", "bad ticket!", "", false)))
                .isInstanceOfSatisfying(ActionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(ActionRefusedException.Reason.TICKET_FORMAT));
    }

    @Test
    void highNeedsAFreshLogin() {
        Pending p = pending(Action.TENANT_MOVE_ZONE, RiskPolicy.Facts.none());
        actions.check(fresh, p, form("acme", false));
        assertThatThrownBy(() -> actions.check(stale, p, form("acme", false))).isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void forceIsNeededWhereTheGuardsSaySo() {
        Pending p = pending(Action.SERVICE_CORDON, new RiskPolicy.Facts(false, false, true, false));
        assertThatThrownBy(() -> actions.check(fresh, p, form("acme", false)))
                .isInstanceOfSatisfying(ActionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(ActionRefusedException.Reason.FORCE_REQUIRED));
        actions.check(fresh, p, form("acme", true));
    }

    @Test
    void theCapabilityIsCheckedAgainAtSending() {
        Pending p = pending(Action.TENANT_PIN, RiskPolicy.Facts.none());
        assertThatThrownBy(() -> actions.check(nobody, p, form("", false))).isInstanceOf(ForbiddenException.class);
    }
}
