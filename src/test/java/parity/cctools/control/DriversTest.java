package parity.cctools.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import parity.cctools.catalogue.Catalogue.Driver;
import parity.cctools.control.Drivers.Decision;
import parity.cctools.control.Drivers.MediaObs;
import parity.cctools.control.Drivers.RotationObs;
import parity.cctools.control.Drivers.ScaleObs;

/**
 * The service-control state machines (PLAN.md §6.4), each driver x input: the desired value is set only
 * when it isn't in place (a repeat changes nothing), a move happens only on the observed fact, a holding
 * state sets its value again when it was changed behind it, and a vanished target ends the saga.
 */
class DriversTest {

    @Test
    void rotation() {
        // cordoning: label not yet out -> apply out, stay.
        Decision d = Drivers.rotation("cordoning", new RotationObs(true, "in", true, true));
        assertThat(d.desired().label()).isEqualTo("out");
        assertThat(d.apply()).isTrue();
        assertThat(d.moves()).isFalse();
        // label out, address still in the endpoints: wait (no apply, no move): a fact, not a timer, moves it.
        d = Drivers.rotation("cordoning", new RotationObs(true, "out", true, true));
        assertThat(d.apply()).isFalse();
        assertThat(d.moves()).isFalse();
        // the address left the endpoints -> out_of_rotation (holding, no outcome).
        d = Drivers.rotation("cordoning", new RotationObs(true, "out", false, false));
        assertThat(d.toState()).isEqualTo("out_of_rotation");
        assertThat(d.outcome()).isNull();
        // holding: label changed behind it -> set out again, no move.
        d = Drivers.rotation("out_of_rotation", new RotationObs(true, "in", true, true));
        assertThat(d.apply()).isTrue();
        assertThat(d.desired().label()).isEqualTo("out");
        assertThat(d.moves()).isFalse();
        // uncordoning: needs the address back AND ready.
        assertThat(Drivers.rotation("uncordoning", new RotationObs(true, "out", false, false)).apply()).isTrue();
        assertThat(Drivers.rotation("uncordoning", new RotationObs(true, "in", true, false)).moves()).isFalse();
        d = Drivers.rotation("uncordoning", new RotationObs(true, "in", true, true));
        assertThat(d.toState()).isEqualTo("in_rotation");
        assertThat(d.outcome()).isEqualTo("done");
        // reverting (a cancel) finishes cancelled.
        assertThat(Drivers.rotation("reverting", new RotationObs(true, "in", true, true)).outcome()).isEqualTo("cancelled");
        // the pod is gone -> aborted, nothing applied.
        d = Drivers.rotation("cordoning", new RotationObs(false, null, false, false));
        assertThat(d.outcome()).isEqualTo("aborted");
        assertThat(d.apply()).isFalse();
    }

    static final Set<String> DESTS = Set.of("sbc-a:8888|1|sip:10.0.0.5:5080", "sbc-b:8888|1|sip:10.0.0.5:5080");

    @Test
    void mediaNodeOnEverySbcNode() {
        // one SBC node still active -> apply inactive, stay isolating.
        Decision d = Drivers.media("isolating", new MediaObs(Map.of("sbc-a:8888|1|sip:10.0.0.5:5080", "inactive",
                "sbc-b:8888|1|sip:10.0.0.5:5080", "active"), Map.of("tc-0", "out"), 4.0), DESTS);
        assertThat(d.apply()).isTrue();
        assertThat(d.desired().dispatcher()).isEqualTo("i");
        assertThat(d.moves()).isFalse();
        // every SBC node inactive, labels out -> draining.
        Map<String, String> allOff = Map.of("sbc-a:8888|1|sip:10.0.0.5:5080", "inactive", "sbc-b:8888|1|sip:10.0.0.5:5080", "inactive");
        d = Drivers.media("isolating", new MediaObs(allOff, Map.of("tc-0", "out"), 4.0), DESTS);
        assertThat(d.toState()).isEqualTo("draining");
        // draining: calls still up -> wait; gauge unknown -> wait; 0 -> out_of_rotation.
        assertThat(Drivers.media("draining", new MediaObs(allOff, Map.of("tc-0", "out"), 4.0), DESTS).moves()).isFalse();
        assertThat(Drivers.media("draining", new MediaObs(allOff, Map.of("tc-0", "out"), null), DESTS).moves()).isFalse();
        assertThat(Drivers.media("draining", new MediaObs(allOff, Map.of("tc-0", "out"), 0.0), DESTS).toState()).isEqualTo("out_of_rotation");
        // an opensips restart re-activated it while holding -> set inactive again, no move.
        d = Drivers.media("out_of_rotation", new MediaObs(Map.of("sbc-a:8888|1|sip:10.0.0.5:5080", "active",
                "sbc-b:8888|1|sip:10.0.0.5:5080", "inactive"), Map.of("tc-0", "out"), 0.0), DESTS);
        assertThat(d.apply()).isTrue();
        assertThat(d.moves()).isFalse();
        // an SBC node's MI unreachable -> wait, apply nothing (no blind writes).
        d = Drivers.media("isolating", new MediaObs(null, Map.of(), null), DESTS);
        assertThat(d.apply()).isFalse();
        assertThat(d.moves()).isFalse();
        // a destination missing from an SBC node's list -> failed.
        assertThat(Drivers.media("isolating", new MediaObs(Map.of("sbc-a:8888|1|sip:10.0.0.5:5080", "inactive"), Map.of(), 0.0), DESTS)
                .outcome()).isEqualTo("failed");
        // restoring: active everywhere -> done.
        Map<String, String> allOn = Map.of("sbc-a:8888|1|sip:10.0.0.5:5080", "active", "sbc-b:8888|1|sip:10.0.0.5:5080", "active");
        d = Drivers.media("restoring", new MediaObs(allOn, Map.of("tc-0", "in"), 3.0), DESTS);
        assertThat(d.outcome()).isEqualTo("done");
        assertThat(Drivers.media("reverting", new MediaObs(allOn, Map.of("tc-0", "in"), 3.0), DESTS).outcome()).isEqualTo("cancelled");
    }

    @Test
    void scale() {
        Decision d = Drivers.scale("stopping", new ScaleObs(true, 3, 3, 3), 3);
        assertThat(d.desired().replicas()).isZero();
        assertThat(d.apply()).isTrue();
        assertThat(Drivers.scale("stopping", new ScaleObs(true, 0, 1, 0), 3).moves()).isFalse();
        assertThat(Drivers.scale("stopping", new ScaleObs(true, 0, 0, 0), 3).toState()).isEqualTo("stopped");
        // holding: someone scaled it up (a chart upgrade) -> back to 0.
        d = Drivers.scale("stopped", new ScaleObs(true, 2, 2, 2), 3);
        assertThat(d.apply()).isTrue();
        assertThat(d.desired().replicas()).isZero();
        // starting: back to the recorded count, done when that many are ready.
        d = Drivers.scale("starting", new ScaleObs(true, 0, 0, 0), 3);
        assertThat(d.desired().replicas()).isEqualTo(3);
        assertThat(d.apply()).isTrue();
        assertThat(Drivers.scale("starting", new ScaleObs(true, 3, 3, 2), 3).moves()).isFalse();
        assertThat(Drivers.scale("starting", new ScaleObs(true, 3, 3, 3), 3).outcome()).isEqualTo("done");
        assertThat(Drivers.scale("reverting", new ScaleObs(true, 3, 3, 3), 3).outcome()).isEqualTo("cancelled");
        assertThat(Drivers.scale("stopping", new ScaleObs(false, 0, 0, 0), 3).outcome()).isEqualTo("aborted");
    }

    @Test
    void cancelIsOnlyForDisablingStates() {
        assertThat(Drivers.cancellable(Driver.K8S_ROTATION, "cordoning")).isTrue();
        assertThat(Drivers.cancellable(Driver.K8S_ROTATION, "out_of_rotation")).isFalse();
        assertThat(Drivers.cancellable(Driver.MEDIA_NODE, "draining")).isTrue();
        assertThat(Drivers.cancellable(Driver.MEDIA_NODE, "restoring")).isFalse();
        assertThat(Drivers.cancellable(Driver.K8S_SCALE, "stopping")).isTrue();
        assertThat(Drivers.cancellable(Driver.K8S_SCALE, "stopped")).isFalse();
        assertThat(Drivers.holding(Driver.K8S_SCALE)).isEqualTo("stopped");
        assertThat(Drivers.start(Driver.MEDIA_NODE, true)).isEqualTo("isolating");
        for (Driver d : Driver.values()) {
            assertThat(Drivers.states(d)).contains(Drivers.start(d, true), Drivers.start(d, false), Drivers.holding(d), "reverting");
        }
    }
}
