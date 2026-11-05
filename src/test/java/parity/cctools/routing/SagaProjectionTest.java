package parity.cctools.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The projection moves only forward by seq, drops repeats, reads the saga again on a gap, and reports a finish once. */
class SagaProjectionTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SAGA = "0193a1b2-c3d4-7e5f-9a6b-7c8d9e0f1a2c";
    int ids;

    RsTransport.Event ev(String type, long seq, String extraKey, String extraValue) {
        ObjectNode b = JSON.createObjectNode();
        b.put("schema_version", 1);
        b.put("event_id", String.format("0193a1b2-c3d4-7e5f-ba6b-%012d", ++ids));
        b.put("type", type);
        b.put("at_ms", 1793000000000L + seq);
        b.put("zone", "eu1a");
        b.put("tenant_id", "acme");
        b.put("saga_id", SAGA);
        b.put("op_id", "0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
        b.put("seq", seq);
        b.put("kind", "unit_move");
        if (extraKey != null) {
            b.put(extraKey, extraValue);
        }
        return new RsTransport.Event("eu1a", SAGA, b);
    }

    @Test
    void ordersBySeqAndFinishesOnce() {
        SagaProjection p = new SagaProjection();
        List<String> resyncs = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        p.setResync((zone, id) -> resyncs.add(id));
        p.onFinished(v -> finished.add(v.outcome()));
        p.watch(SAGA, v -> seen.add(v.state()));
        p.apply(ev("saga.accepted", 1, null, null));
        RsTransport.Event two = ev("saga.state_changed", 2, "to_state", "fencing");
        p.apply(two);
        p.apply(two);
        p.apply(ev("saga.state_changed", 2, "to_state", "switching"));
        assertThat(p.get(SAGA).state()).as("a repeated or old seq is dropped").isEqualTo("fencing");
        assertThat(resyncs).isEmpty();
        p.apply(ev("saga.state_changed", 4, "to_state", "handing_over"));
        assertThat(resyncs).as("seq 3 missing: read the saga again").containsExactly(SAGA);
        p.apply(ev("saga.finished", 5, "outcome", "done"));
        p.apply(ev("saga.finished", 6, "outcome", "done"));
        assertThat(p.get(SAGA).outcome()).isEqualTo("done");
        assertThat(finished).containsExactly("done");
        assertThat(seen).containsExactly("accepted", "fencing", "handing_over", "done", "done");
    }

    @Test
    void aSeedNeverGoesBack() {
        SagaProjection p = new SagaProjection();
        p.apply(ev("saga.accepted", 1, null, null));
        p.apply(ev("saga.state_changed", 2, "to_state", "fencing"));
        ObjectNode saga = JSON.createObjectNode();
        saga.put("saga_id", SAGA);
        saga.put("kind", "unit_move");
        saga.put("state", "accepted");
        saga.put("seq", 1);
        p.seed("eu1a", saga);
        assertThat(p.get(SAGA).state()).isEqualTo("fencing");
        saga.put("state", "switching");
        saga.put("seq", 3);
        p.seed("eu1a", saga);
        assertThat(p.get(SAGA).state()).isEqualTo("switching");
    }
}
