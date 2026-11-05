package parity.cctools.ops;

import parity.cctools.security.Capability;

/**
 * Every action an operator can confirm: its audit name, the capability it needs and its base risk tier
 * (PLAN.md §3); {@link RiskPolicy} raises the tier from what the screen saw (force, a live unit with
 * tenants, the last ready instance).
 */
public enum Action {
    TENANT_MOVE("tenant.move", Capability.ROUTING_MOVE, Risk.MEDIUM, true),
    TENANT_MOVE_ZONE("tenant.move_zone", Capability.ROUTING_MOVE, Risk.HIGH, true),
    TENANT_SET_FAILOVER("tenant.set_failover_zone", Capability.ROUTING_MOVE, Risk.MEDIUM, true),
    TENANT_PIN("tenant.pin", Capability.ROUTING_MOVE, Risk.LOW, true),
    TENANT_UNPIN("tenant.unpin", Capability.ROUTING_MOVE, Risk.LOW, true),
    SAGA_CANCEL("saga.cancel", Capability.ROUTING_MOVE, Risk.MEDIUM, true),
    SAGA_RESOLVE("saga.resolve", Capability.ROUTING_MOVE, Risk.HIGH, true),
    SAGA_CONFIRM_CUTOVER("saga.confirm_cutover", Capability.ROUTING_MOVE, Risk.HIGH, true),
    UNIT_DRAIN("unit.drain", Capability.ROUTING_ROLLOUT, Risk.MEDIUM, true),
    UNIT_RELEASE("unit.release", Capability.ROUTING_ROLLOUT, Risk.MEDIUM, true),
    ROLLOUT_START("rollout.start", Capability.ROUTING_ROLLOUT, Risk.HIGH, true),
    ROLLOUT_STOP("rollout.stop", Capability.ROUTING_ROLLOUT, Risk.LOW, true),
    ROLLOUT_RESUME("rollout.resume", Capability.ROUTING_ROLLOUT, Risk.MEDIUM, true),
    ROLLOUT_ADVANCE("rollout.advance", Capability.ROUTING_ROLLOUT, Risk.MEDIUM, true),
    ROLLOUT_ROLLBACK("rollout.rollback", Capability.ROUTING_ROLLOUT, Risk.HIGH, true),
    SERVICE_CORDON("service.cordon", Capability.SERVICE_CONTROL, Risk.MEDIUM, false),
    SERVICE_UNCORDON("service.uncordon", Capability.SERVICE_CONTROL, Risk.MEDIUM, false),
    MEDIA_NODE_OUT("media_node.out", Capability.SERVICE_CONTROL, Risk.MEDIUM, false),
    MEDIA_NODE_IN("media_node.in", Capability.SERVICE_CONTROL, Risk.MEDIUM, false),
    SERVICE_STOP("service.stop", Capability.SERVICE_CONTROL, Risk.HIGH, false),
    SERVICE_START("service.start", Capability.SERVICE_CONTROL, Risk.MEDIUM, false),
    SERVICE_OP_CANCEL("service_op.cancel", Capability.SERVICE_CONTROL, Risk.MEDIUM, false);

    private final String wire;
    private final Capability capability;
    private final Risk base;
    private final boolean routing;

    Action(String wire, Capability capability, Risk base, boolean routing) {
        this.wire = wire;
        this.capability = capability;
        this.base = base;
        this.routing = routing;
    }

    /** The audit log's {@code action}. */
    public String wire() {
        return wire;
    }

    public Capability capability() {
        return capability;
    }

    public Risk base() {
        return base;
    }

    /** True for a parity-rs operation; false for this tool's own service control. */
    public boolean routing() {
        return routing;
    }
}
