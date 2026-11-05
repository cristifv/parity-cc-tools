package parity.cctools.ops;

/**
 * Risk tiers (PLAN.md §3): Low needs a reason; Medium also the typed id (tenant, instance, node, unit or
 * the next ring's name); High also a fresh login (step-up, auth_time within 5 minutes).
 */
public enum Risk {
    LOW, MEDIUM, HIGH;

    public boolean typed() {
        return this != LOW;
    }

    public boolean stepUp() {
        return this == HIGH;
    }

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
