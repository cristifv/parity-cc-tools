package parity.cctools.routing;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The commands this tool sends to parity-rs (parity-rs PLAN §3.3), as the fields after the envelope
 * ({@link RsClient#request} adds {@code schema_version}, {@code id}, {@code reply_to}). Every operation
 * carries {@code op_id}, {@code actor}, {@code reason} (and {@code ticket} when given); a saga-starting one
 * its {@code saga_id}; a tenant operation the {@code expected_version} the screen showed. A unit test
 * checks every builder against {@code tenant-routing-requests.v1.json}.
 */
public final class RsRequests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RsRequests() {}

    /** What every operation carries: the action's op_id, who (as parity-rs records it), why. */
    public record Op(String opId, Map<String, Object> actor, String reason, String ticket) {}

    /** A rollout ring: a name and a selector ({@code test}, {@code internal}, {@code early}, or a percent). */
    public record Ring(String name, String select, Integer percent) {}

    private static ObjectNode cmd(String cmd) {
        ObjectNode n = JSON.createObjectNode();
        n.put("cmd", cmd);
        return n;
    }

    private static ObjectNode op(String cmd, Op op) {
        ObjectNode n = cmd(cmd);
        n.put("op_id", op.opId());
        n.set("actor", JSON.valueToTree(op.actor()));
        n.put("reason", op.reason());
        if (op.ticket() != null && !op.ticket().isBlank()) {
            n.put("ticket", op.ticket());
        }
        return n;
    }

    public static ObjectNode ping() {
        return cmd("ping");
    }

    public static ObjectNode getRoute(String tenantId) {
        ObjectNode n = cmd("get_route");
        n.put("tenant_id", tenantId);
        return n;
    }

    public static ObjectNode listRoutes(String after, int limit, String zone, String unit, String tier) {
        ObjectNode n = cmd("list_routes");
        if (after != null && !after.isBlank()) {
            n.put("after", after);
        }
        n.put("limit", Math.max(1, Math.min(500, limit)));
        if (zone != null && !zone.isBlank()) {
            n.put("zone", zone);
        }
        if (unit != null && !unit.isBlank()) {
            n.put("deployment_unit", unit);
        }
        if (tier != null && !tier.isBlank()) {
            n.put("tier", tier);
        }
        return n;
    }

    public static ObjectNode getTopology() {
        return cmd("get_topology");
    }

    public static ObjectNode getSaga(String sagaId) {
        ObjectNode n = cmd("get_saga");
        n.put("saga_id", sagaId);
        return n;
    }

    public static ObjectNode listSagas(String state, String kind, String tenantId, String after, int limit) {
        ObjectNode n = cmd("list_sagas");
        if (state != null) {
            n.put("state", state);
        }
        if (kind != null && !kind.isBlank()) {
            n.put("kind", kind);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            n.put("tenant_id", tenantId);
        }
        if (after != null && !after.isBlank()) {
            n.put("after", after);
        }
        n.put("limit", Math.max(1, Math.min(200, limit)));
        return n;
    }

    /** The dry run: as move_tenant ({@code toUnit}) or move_tenant_zone ({@code toZone}); changes nothing. */
    public static ObjectNode validateMove(Op op, String tenantId, long expectedVersion, String toUnit, String toZone,
            String mediaTrack, boolean force) {
        ObjectNode n = op("validate_move", op);
        n.put("expected_version", expectedVersion);
        n.put("tenant_id", tenantId);
        if (toUnit != null && !toUnit.isBlank()) {
            n.put("to_unit", toUnit);
        }
        if (toZone != null && !toZone.isBlank()) {
            n.put("to_zone", toZone);
        }
        if (mediaTrack != null && !mediaTrack.isBlank()) {
            n.put("media_track", mediaTrack);
        }
        if (force) {
            n.put("force", true);
        }
        return n;
    }

    public static ObjectNode moveTenant(Op op, String sagaId, String tenantId, String toUnit, long expectedVersion,
            String mediaTrack, boolean force) {
        ObjectNode n = op("move_tenant", op);
        n.put("saga_id", sagaId);
        n.put("expected_version", expectedVersion);
        n.put("tenant_id", tenantId);
        n.put("to_unit", toUnit);
        if (mediaTrack != null && !mediaTrack.isBlank()) {
            n.put("media_track", mediaTrack);
        }
        if (force) {
            n.put("force", true);
        }
        return n;
    }

    public static ObjectNode moveTenantZone(Op op, String sagaId, String tenantId, String toZone, String toUnit,
            long expectedVersion, String cutover) {
        ObjectNode n = op("move_tenant_zone", op);
        n.put("saga_id", sagaId);
        n.put("expected_version", expectedVersion);
        n.put("tenant_id", tenantId);
        n.put("to_zone", toZone);
        if (toUnit != null && !toUnit.isBlank()) {
            n.put("to_unit", toUnit);
        }
        n.put("cutover", cutover == null ? "on_confirm" : cutover);
        return n;
    }

    /** {@code failoverZone} null clears it. */
    public static ObjectNode setFailoverZone(Op op, String sagaId, String tenantId, String failoverZone, long expectedVersion) {
        ObjectNode n = op("set_failover_zone", op);
        n.put("saga_id", sagaId);
        n.put("expected_version", expectedVersion);
        n.put("tenant_id", tenantId);
        if (failoverZone == null || failoverZone.isBlank()) {
            n.putNull("failover_zone");
        } else {
            n.put("failover_zone", failoverZone);
        }
        return n;
    }

    /** confirm_cutover, cancel_saga, stop_rollout, resume_rollout, rollback_rollout: a saga and nothing else. */
    public static ObjectNode onSaga(String cmd, Op op, String sagaId) {
        if (!List.of("confirm_cutover", "cancel_saga", "stop_rollout", "resume_rollout", "rollback_rollout").contains(cmd)) {
            throw new IllegalArgumentException("not a saga command");
        }
        ObjectNode n = op(cmd, op);
        n.put("saga_id", sagaId);
        return n;
    }

    /** {@code action}: {@code retry_record} or {@code accept_store}. */
    public static ObjectNode resolveSaga(Op op, String sagaId, String action) {
        ObjectNode n = op("resolve_saga", op);
        n.put("saga_id", sagaId);
        n.put("action", action);
        return n;
    }

    public static ObjectNode advanceRollout(Op op, String sagaId, int expectedRing) {
        ObjectNode n = op("advance_rollout", op);
        n.put("saga_id", sagaId);
        n.put("expected_ring", expectedRing);
        return n;
    }

    public static ObjectNode pinTenant(Op op, String tenantId, long untilMs) {
        ObjectNode n = op("pin_tenant", op);
        n.put("tenant_id", tenantId);
        n.put("until_ms", untilMs);
        return n;
    }

    public static ObjectNode unpinTenant(Op op, String tenantId) {
        ObjectNode n = op("unpin_tenant", op);
        n.put("tenant_id", tenantId);
        return n;
    }

    public static ObjectNode startRollout(Op op, String sagaId, String zone, String release, String unit, List<Ring> rings,
            Integer maxParallel) {
        ObjectNode n = op("start_rollout", op);
        n.put("saga_id", sagaId);
        n.put("zone", zone);
        n.put("release", release);
        n.put("unit", unit);
        n.put("advance", "manual");
        ObjectNode plan = n.putObject("plan");
        ArrayNode rs = plan.putArray("rings");
        for (Ring r : rings) {
            ObjectNode ring = rs.addObject();
            ring.put("name", r.name());
            if (r.percent() != null) {
                ring.putObject("select").put("percent", r.percent());
            } else {
                ring.put("select", r.select());
            }
        }
        if (maxParallel != null) {
            plan.put("max_parallel", maxParallel);
        }
        return n;
    }

    public static ObjectNode drainUnit(Op op, String sagaId, String zone, String unit, String toUnit, boolean allowReleaseChange) {
        ObjectNode n = op("drain_unit", op);
        n.put("saga_id", sagaId);
        n.put("zone", zone);
        n.put("unit", unit);
        if (toUnit != null && !toUnit.isBlank()) {
            n.put("to_unit", toUnit);
        }
        if (allowReleaseChange) {
            n.put("allow_release_change", true);
        }
        return n;
    }

    public static ObjectNode releaseUnit(Op op, String zone, String unit) {
        ObjectNode n = op("release_unit", op);
        n.put("zone", zone);
        n.put("unit", unit);
        return n;
    }
}
