package parity.cctools.control;

import java.util.Map;
import java.util.Set;
import parity.cctools.catalogue.Catalogue.Driver;

/**
 * The service-control state machines (PLAN.md §6.4), as pure functions of (state, observation): each
 * state has one desired setting, an absolute value (a label value, a dispatcher state, a replica count),
 * re-applied whenever the observation shows it isn't in place, so a repeat changes nothing and a setting
 * changed behind the saga's back is set again; a transition happens only on an observed fact, never on
 * elapsed time.
 *
 * <table>
 *   <caption>States</caption>
 *   <tr><th>Driver</th><th>Disable</th><th>Holding</th><th>Enable</th><th>Cancel</th></tr>
 *   <tr><td>k8s-rotation</td><td>cordoning: label out until the pod's address left the EndpointSlices</td>
 *       <td>out_of_rotation</td><td>uncordoning: label in until the address is back and ready</td>
 *       <td>reverting, as uncordoning</td></tr>
 *   <tr><td>media-node</td><td>isolating: dispatcher inactive + label out until read back; draining: until the
 *       active-call gauge is 0</td><td>out_of_rotation</td><td>restoring: dispatcher active + label in until
 *       read back</td><td>reverting, as restoring</td></tr>
 *   <tr><td>k8s-scale</td><td>stopping: replicas 0 until the status shows 0</td><td>stopped</td>
 *       <td>starting: the recorded count until that many are ready</td><td>reverting, as starting</td></tr>
 * </table>
 * A holding state stays open (and enforced) until an enable saga releases it (outcome {@code released}).
 */
public final class Drivers {

    private Drivers() {}

    /** What a state wants in place. Exactly the fields of its driver are set. */
    public record Desired(String label, String dispatcher, Integer replicas) {
        static Desired label(String v) {
            return new Desired(v, null, null);
        }

        static Desired media(String dispatcher, String label) {
            return new Desired(label, dispatcher, null);
        }

        static Desired replicas(int n) {
            return new Desired(null, null, n);
        }
    }

    /**
     * The decision for one evaluation: what must be in place ({@code desired}), whether to set it now
     * ({@code apply}: the observation shows it isn't), and the transition the observed fact allows (a new
     * state, or an outcome that finishes the saga), with a short detail for the screen.
     */
    public record Decision(Desired desired, boolean apply, String toState, String outcome, String detail) {
        boolean moves() {
            return toState != null || outcome != null;
        }
    }

    /** What the rotation driver observed: the pod, its label, its address in the Service's EndpointSlices. */
    public record RotationObs(boolean podExists, String label, boolean inEndpoints, boolean endpointReady) {}

    /**
     * What the media-node driver observed: each dispatcher destination's state on each SBC node, keyed
     * {@code <sbc>|<set>|<address>} (null: an SBC node's management interface isn't reachable), each of the node's pods' rotation label, the active-call gauge (null: not readable).
     */
    public record MediaObs(Map<String, String> dispatcher, Map<String, String> podLabels, Double activeCalls) {}

    /** What the scale driver observed: the workload, its desired, current and ready replica counts. */
    public record ScaleObs(boolean exists, int specReplicas, int statusReplicas, int readyReplicas) {}

    public static final String CORDONING = "cordoning";
    public static final String OUT_OF_ROTATION = "out_of_rotation";
    public static final String UNCORDONING = "uncordoning";
    public static final String IN_ROTATION = "in_rotation";
    public static final String ISOLATING = "isolating";
    public static final String DRAINING = "draining";
    public static final String RESTORING = "restoring";
    public static final String STOPPING = "stopping";
    public static final String STOPPED = "stopped";
    public static final String STARTING = "starting";
    public static final String RUNNING = "running";
    public static final String REVERTING = "reverting";

    /** The first state of a disable or an enable saga. */
    public static String start(Driver d, boolean disable) {
        return switch (d) {
            case K8S_ROTATION -> disable ? CORDONING : UNCORDONING;
            case MEDIA_NODE -> disable ? ISOLATING : RESTORING;
            case K8S_SCALE -> disable ? STOPPING : STARTING;
        };
    }

    /** The holding state a disable saga rests in until released. */
    public static String holding(Driver d) {
        return d == Driver.K8S_SCALE ? STOPPED : OUT_OF_ROTATION;
    }

    /** States a cancel may revert (the disabling ones; an enable or a holding state isn't cancelled). */
    public static boolean cancellable(Driver d, String state) {
        return switch (d) {
            case K8S_ROTATION -> CORDONING.equals(state);
            case MEDIA_NODE -> ISOLATING.equals(state) || DRAINING.equals(state);
            case K8S_SCALE -> STOPPING.equals(state);
        };
    }

    /** Every state each driver may be in (the screen and the tests list them). */
    public static Set<String> states(Driver d) {
        return switch (d) {
            case K8S_ROTATION -> Set.of(CORDONING, OUT_OF_ROTATION, UNCORDONING, REVERTING, IN_ROTATION);
            case MEDIA_NODE -> Set.of(ISOLATING, DRAINING, OUT_OF_ROTATION, RESTORING, REVERTING, IN_ROTATION);
            case K8S_SCALE -> Set.of(STOPPING, STOPPED, STARTING, REVERTING, RUNNING);
        };
    }

    /** k8s-rotation: one pod out of its Service's endpoints and back. */
    public static Decision rotation(String state, RotationObs o) {
        boolean out = CORDONING.equals(state) || OUT_OF_ROTATION.equals(state);
        Desired want = Desired.label(out ? "out" : "in");
        if (!o.podExists()) {
            return new Decision(want, false, null, "aborted", "the pod is gone (its replacement starts in rotation)");
        }
        boolean apply = !want.label().equals(o.label());
        return switch (state) {
            case CORDONING -> "out".equals(o.label()) && !o.inEndpoints()
                    ? new Decision(want, false, OUT_OF_ROTATION, null, "the pod's address left the Service's endpoints")
                    : new Decision(want, apply, null, null, apply ? "setting the label out" : "waiting for the endpoints to drop the pod");
            case OUT_OF_ROTATION -> new Decision(want, apply, null, null, apply ? "the label was changed: setting it out again" : "out of rotation");
            case UNCORDONING, REVERTING -> "in".equals(o.label()) && o.inEndpoints() && o.endpointReady()
                    ? new Decision(want, false, IN_ROTATION, UNCORDONING.equals(state) ? "done" : "cancelled", "the pod is back in the endpoints, ready")
                    : new Decision(want, apply, null, null, apply ? "setting the label in" : "waiting for the endpoints to take the pod back");
            default -> new Decision(want, false, null, "failed", "unknown state");
        };
    }

    /**
     * media-node: a media node out of the dispatcher of every SBC node, its calls drained, and back.
     * {@code destinations} are {@code <sbc>|<set>|<address>}: a step is confirmed only when every SBC
     * node reads back the state.
     */
    public static Decision media(String state, MediaObs o, Set<String> destinations) {
        boolean out = ISOLATING.equals(state) || DRAINING.equals(state) || OUT_OF_ROTATION.equals(state);
        Desired want = Desired.media(out ? "i" : "a", out ? "out" : "in");
        if (o.dispatcher() == null) {
            return new Decision(want, false, null, null, "an opensips SBC node's management interface isn't reachable: waiting");
        }
        String wantState = out ? "inactive" : "active";
        boolean dsInPlace = destinations.stream().allMatch(d -> wantState.equals(o.dispatcher().get(d)));
        boolean missing = destinations.stream().anyMatch(d -> !o.dispatcher().containsKey(d));
        if (missing) {
            return new Decision(want, false, null, "failed", "a dispatcher destination of this node isn't in an SBC node's list");
        }
        boolean labelsInPlace = o.podLabels().values().stream().allMatch(want.label()::equals);
        boolean apply = !dsInPlace || !labelsInPlace;
        return switch (state) {
            case ISOLATING -> dsInPlace && labelsInPlace
                    ? new Decision(want, false, DRAINING, null, "no new calls reach the node (dispatcher read back inactive)")
                    : new Decision(want, apply, null, null, "setting the dispatcher entries inactive on every SBC node");
            case DRAINING -> {
                if (apply) {
                    yield new Decision(want, true, null, null, "the dispatcher was changed: setting it inactive again");
                }
                if (o.activeCalls() == null) {
                    yield new Decision(want, false, null, null, "the active-call gauge can't be read: waiting");
                }
                yield o.activeCalls() <= 0
                        ? new Decision(want, false, OUT_OF_ROTATION, null, "the node's calls have ended (gauge 0)")
                        : new Decision(want, false, null, null, "waiting for the node's calls to end");
            }
            case OUT_OF_ROTATION -> new Decision(want, apply, null, null,
                    apply ? "the dispatcher or a label was changed (an opensips restart?): setting it again" : "out of rotation");
            case RESTORING, REVERTING -> dsInPlace && labelsInPlace
                    ? new Decision(want, false, IN_ROTATION, RESTORING.equals(state) ? "done" : "cancelled",
                            "the dispatcher entries read back active")
                    : new Decision(want, apply, null, null, "setting the dispatcher entries active");
            default -> new Decision(want, false, null, "failed", "unknown state");
        };
    }

    /** k8s-scale: a whole workload to 0 replicas and back to the recorded count. */
    public static Decision scale(String state, ScaleObs o, int recordedReplicas) {
        boolean down = STOPPING.equals(state) || STOPPED.equals(state);
        Desired want = Desired.replicas(down ? 0 : Math.max(1, recordedReplicas));
        if (!o.exists()) {
            return new Decision(want, false, null, "aborted", "the workload is gone");
        }
        boolean apply = o.specReplicas() != want.replicas();
        return switch (state) {
            case STOPPING -> o.specReplicas() == 0 && o.statusReplicas() == 0
                    ? new Decision(want, false, STOPPED, null, "no replica runs")
                    : new Decision(want, apply, null, null, apply ? "scaling to 0" : "waiting for the replicas to stop");
            case STOPPED -> new Decision(want, apply, null, null, apply ? "the workload was scaled up: back to 0" : "stopped");
            case STARTING, REVERTING -> !apply && o.readyReplicas() >= want.replicas()
                    ? new Decision(want, false, RUNNING, STARTING.equals(state) ? "done" : "cancelled",
                            "the recorded number of replicas is ready")
                    : new Decision(want, apply, null, null, apply ? "scaling back to the recorded count" : "waiting for the replicas to be ready");
            default -> new Decision(want, false, null, "failed", "unknown state");
        };
    }
}
