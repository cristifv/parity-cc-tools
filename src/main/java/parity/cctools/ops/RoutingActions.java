package parity.cctools.ops;

import java.util.ArrayList;
import java.util.List;
import parity.cctools.routing.RsRequests;
import parity.cctools.security.Operator;
import tools.jackson.databind.node.ObjectNode;

/** A pending routing action's parity-rs command (fields only; the client adds the envelope). */
public final class RoutingActions {

    private RoutingActions() {}

    public static ObjectNode body(Pending p, Operator op, Pending.Form f) {
        RsRequests.Op o = new RsRequests.Op(p.opId(), op.actor(), f.reason().strip(), blankToNull(f.ticket()));
        return switch (p.action()) {
            case TENANT_MOVE -> RsRequests.moveTenant(o, p.sagaId(), p.tenantId(), p.param("to_unit"), version(p),
                    p.param("media_track"), "true".equals(p.param("force")));
            case TENANT_MOVE_ZONE -> RsRequests.moveTenantZone(o, p.sagaId(), p.tenantId(), p.param("to_zone"), p.param("to_unit"),
                    version(p), p.param("cutover"));
            case TENANT_SET_FAILOVER -> RsRequests.setFailoverZone(o, p.sagaId(), p.tenantId(), p.param("failover_zone"), version(p));
            case TENANT_PIN -> RsRequests.pinTenant(o, p.tenantId(), Long.parseLong(p.param("until_ms")));
            case TENANT_UNPIN -> RsRequests.unpinTenant(o, p.tenantId());
            case SAGA_CANCEL -> RsRequests.onSaga("cancel_saga", o, p.sagaId());
            case SAGA_CONFIRM_CUTOVER -> RsRequests.onSaga("confirm_cutover", o, p.sagaId());
            case SAGA_RESOLVE -> RsRequests.resolveSaga(o, p.sagaId(), p.param("resolve_action"));
            case ROLLOUT_STOP -> RsRequests.onSaga("stop_rollout", o, p.sagaId());
            case ROLLOUT_RESUME -> RsRequests.onSaga("resume_rollout", o, p.sagaId());
            case ROLLOUT_ROLLBACK -> RsRequests.onSaga("rollback_rollout", o, p.sagaId());
            case ROLLOUT_ADVANCE -> RsRequests.advanceRollout(o, p.sagaId(), Integer.parseInt(p.param("expected_ring")));
            case ROLLOUT_START -> RsRequests.startRollout(o, p.sagaId(), p.zone(), p.release(), p.unit(), rings(p.param("rings")),
                    p.param("max_parallel") == null ? null : Integer.valueOf(p.param("max_parallel")));
            case UNIT_DRAIN -> RsRequests.drainUnit(o, p.sagaId(), p.zone(), p.unit(), p.param("to_unit"),
                    "true".equals(p.param("allow_release_change")));
            case UNIT_RELEASE -> RsRequests.releaseUnit(o, p.zone(), p.unit());
            default -> throw new IllegalArgumentException("not a routing action");
        };
    }

    /** The parity-rs command of a routing action. */
    public static String cmd(Action a) {
        return switch (a) {
            case TENANT_MOVE -> "move_tenant";
            case TENANT_MOVE_ZONE -> "move_tenant_zone";
            case TENANT_SET_FAILOVER -> "set_failover_zone";
            case TENANT_PIN -> "pin_tenant";
            case TENANT_UNPIN -> "unpin_tenant";
            case SAGA_CANCEL -> "cancel_saga";
            case SAGA_CONFIRM_CUTOVER -> "confirm_cutover";
            case SAGA_RESOLVE -> "resolve_saga";
            case ROLLOUT_STOP -> "stop_rollout";
            case ROLLOUT_RESUME -> "resume_rollout";
            case ROLLOUT_ROLLBACK -> "rollback_rollout";
            case ROLLOUT_ADVANCE -> "advance_rollout";
            case ROLLOUT_START -> "start_rollout";
            case UNIT_DRAIN -> "drain_unit";
            case UNIT_RELEASE -> "release_unit";
            default -> throw new IllegalArgumentException("not a routing action");
        };
    }

    /** True when the action starts a new saga (a fresh saga_id is minted for it). */
    public static boolean startsSaga(Action a) {
        return switch (a) {
            case TENANT_MOVE, TENANT_MOVE_ZONE, TENANT_SET_FAILOVER, ROLLOUT_START, UNIT_DRAIN,
                    SERVICE_CORDON, SERVICE_UNCORDON, MEDIA_NODE_OUT, MEDIA_NODE_IN, SERVICE_STOP, SERVICE_START -> true;
            default -> false;
        };
    }

    /** True when the command starts (or is) a saga parity-rs reports on by {@code saga_id}. */
    public static boolean followsSaga(Action a) {
        return switch (a) {
            case TENANT_PIN, TENANT_UNPIN, UNIT_RELEASE -> false;
            default -> a.routing();
        };
    }

    private static long version(Pending p) {
        return Long.parseLong(p.param("expected_version"));
    }

    /** {@code name:test;name:percent=10} to rings. */
    public static List<RsRequests.Ring> rings(String spec) {
        List<RsRequests.Ring> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String part : spec.split(";", -1)) {
            String[] nv = part.split(":", 2);
            if (nv.length != 2) {
                continue;
            }
            String sel = nv[1].strip();
            if (sel.startsWith("percent=")) {
                out.add(new RsRequests.Ring(nv[0].strip(), null, Integer.valueOf(sel.substring("percent=".length()))));
            } else {
                out.add(new RsRequests.Ring(nv[0].strip(), sel, null));
            }
        }
        return out;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
