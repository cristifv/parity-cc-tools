package parity.cctools.security;

/**
 * What an operator may do (PLAN.md §1): every controller method names one ({@link Requires}), checked in
 * one place ({@link CapabilityInterceptor}); a role is a set of capabilities ({@link RoleCapabilities}),
 * so a narrower role later is a mapping, not new code.
 */
public enum Capability {
    ROUTING_READ("routing.read"),
    ROUTING_MOVE("routing.move"),
    ROUTING_ROLLOUT("routing.rollout"),
    SERVICE_CONTROL("service.control"),
    HEALTH_READ("health.read"),
    AUDIT_READ("audit.read");

    private final String wire;

    Capability(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
