package parity.cctools.health;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The health dashboard's model (PLAN.md §6.3) and the state derivation, a pure function (unit-tested). */
public final class Health {

    private Health() {}

    /** An instance's state; {@link #severity} orders the dashboard (problems first). */
    public enum State {
        OK(0), DRAINING(1), NOT_READY(2), UNKNOWN(3), DOWN(4);

        private final int severity;

        State(int severity) {
            this.severity = severity;
        }

        public int severity() {
            return severity;
        }

        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One probe: the HTTP status, or the error kind when there was no answer. */
    public record Probe(Integer status, String error) {
        public boolean ok() {
            return status != null && status == 200;
        }

        public boolean answered() {
            return status != null;
        }

        public String shown() {
            return status != null ? Integer.toString(status) : error == null ? "-" : error;
        }
    }

    public record Instance(String zone, String service, String pod, String node, String podIp, String phase, boolean podReady,
            int restarts, String lastTermination, Probe livez, Probe readyz, Map<String, Double> metrics, String rotation,
            boolean draining, State state) {}

    public record Service(String zone, String service, State worst, Map<State, Integer> counts, List<Instance> instances,
            String error, boolean callPath) {}

    public record Snapshot(Instant at, List<Service> services) {
        public static Snapshot empty() {
            return new Snapshot(null, List.of());
        }
    }

    /**
     * The state of one instance: {@code down} when the pod isn't running or {@code /livez} answers other
     * than 200; {@code unknown} when neither probe got an answer; {@code draining} when it is out of
     * rotation (its label or an open service-control saga); {@code not_ready} when {@code /readyz} isn't
     * 200 or Kubernetes doesn't see the pod ready; else {@code ok}.
     */
    public static State derive(String phase, boolean podReady, Probe livez, Probe readyz, boolean draining) {
        if (!"Running".equals(phase)) {
            return State.DOWN;
        }
        if (livez.answered() && !livez.ok()) {
            return State.DOWN;
        }
        if (!livez.answered() && !readyz.answered()) {
            return State.UNKNOWN;
        }
        if (draining) {
            return State.DRAINING;
        }
        if (!readyz.ok() || !podReady) {
            return State.NOT_READY;
        }
        return State.OK;
    }

    /** A service's state: the worst instance; no instance at all is down, unless it was stopped on purpose. */
    public static State worst(List<Instance> instances, boolean stoppedOnPurpose) {
        if (instances.isEmpty()) {
            return stoppedOnPurpose ? State.DRAINING : State.DOWN;
        }
        State w = State.OK;
        for (Instance i : instances) {
            if (i.state().severity() > w.severity()) {
                w = i.state();
            }
        }
        return w;
    }

    public static Map<State, Integer> counts(List<Instance> instances) {
        Map<State, Integer> c = new EnumMap<>(State.class);
        for (Instance i : instances) {
            c.merge(i.state(), 1, Integer::sum);
        }
        return c;
    }
}
