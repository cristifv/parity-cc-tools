package parity.cctools.ops;

/**
 * An action's risk tier and whether it needs {@code force}, from what the screen saw (PLAN.md §3, §6.4):
 * <ul>
 *   <li>a unit move with {@code force} (it ignores a pin) is High;</li>
 *   <li>draining a live unit that has tenants is High;</li>
 *   <li>cordoning the last ready instance of a service, or taking out the last media node in rotation, is
 *       High and needs {@code force};</li>
 *   <li>stopping a whole service is High; one on the call path also needs {@code force}.</li>
 * </ul>
 */
public final class RiskPolicy {

    private RiskPolicy() {}

    /** What the screen knew when it built the confirmation. */
    public record Facts(boolean force, boolean liveUnitWithTenants, boolean lastReady, boolean callPath) {
        public static Facts none() {
            return new Facts(false, false, false, false);
        }
    }

    public static Risk tier(Action a, Facts f) {
        return switch (a) {
            case TENANT_MOVE -> f.force() ? Risk.HIGH : Risk.MEDIUM;
            case UNIT_DRAIN -> f.liveUnitWithTenants() ? Risk.HIGH : Risk.MEDIUM;
            case SERVICE_CORDON, MEDIA_NODE_OUT -> f.lastReady() ? Risk.HIGH : Risk.MEDIUM;
            default -> a.base();
        };
    }

    public static boolean forceRequired(Action a, Facts f) {
        return switch (a) {
            case SERVICE_CORDON, MEDIA_NODE_OUT -> f.lastReady();
            case SERVICE_STOP -> f.callPath();
            default -> false;
        };
    }
}
