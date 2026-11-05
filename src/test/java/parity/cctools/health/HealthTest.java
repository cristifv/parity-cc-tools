package parity.cctools.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.health.Health.Probe;
import parity.cctools.health.Health.State;

/** PLAN.md §6.3: the instance states, the service's worst, and the metrics picked from Prometheus text. */
class HealthTest {

    static final Probe OK = new Probe(200, null);
    static final Probe BAD = new Probe(503, null);
    static final Probe NONE = new Probe(null, "refused");

    @Test
    void derivation() {
        assertThat(Health.derive("Running", true, OK, OK, false)).isEqualTo(State.OK);
        assertThat(Health.derive("Running", true, OK, BAD, false)).isEqualTo(State.NOT_READY);
        assertThat(Health.derive("Running", false, OK, OK, false)).isEqualTo(State.NOT_READY);
        assertThat(Health.derive("Running", true, BAD, OK, false)).isEqualTo(State.DOWN);
        assertThat(Health.derive("Pending", false, NONE, NONE, false)).isEqualTo(State.DOWN);
        assertThat(Health.derive("Running", true, NONE, NONE, false)).isEqualTo(State.UNKNOWN);
        assertThat(Health.derive("Running", true, OK, BAD, true)).as("a cordoned pod reports not ready: draining").isEqualTo(State.DRAINING);
        assertThat(Health.derive("Running", true, BAD, BAD, true)).as("livez failing is down even when draining").isEqualTo(State.DOWN);
    }

    static Health.Instance inst(String pod, State s) {
        return new Health.Instance("eu1a", "svc", pod, null, null, "Running", true, 0, null, OK, OK, Map.of(), null, false, s);
    }

    @Test
    void worstAndCounts() {
        List<Health.Instance> l = List.of(inst("a", State.OK), inst("b", State.NOT_READY), inst("c", State.DRAINING));
        assertThat(Health.worst(l, false)).isEqualTo(State.NOT_READY);
        assertThat(Health.counts(l)).containsEntry(State.OK, 1).containsEntry(State.NOT_READY, 1).containsEntry(State.DRAINING, 1);
        assertThat(Health.worst(List.of(), false)).isEqualTo(State.DOWN);
        assertThat(Health.worst(List.of(), true)).as("stopped on purpose").isEqualTo(State.DRAINING);
    }

    @Test
    void prometheusPicksOnlyListedSeriesWithTheirLabels() {
        String text = """
                # HELP telephony_controller_sessions Sessions
                # TYPE telephony_controller_sessions gauge
                telephony_controller_sessions{instance="tc-0",service="tc"} 7.0
                telephony_controller_sessions{instance="tc-1",service="tc"} 2
                telephony_controller_ready 1
                http_server_requests_seconds_count{uri="/x",status="200"} 99 1700000000000
                other_metric{label="a \\"quoted\\" one"} 5
                """;
        Map<String, Double> v = PrometheusText.pick(text, List.of(
                new Catalogue.MetricDef("telephony_controller_sessions", Map.of(), "sessions"),
                new Catalogue.MetricDef("telephony_controller_sessions", Map.of("instance", "tc-1"), "tc1"),
                new Catalogue.MetricDef("telephony_controller_ready", Map.of(), null),
                new Catalogue.MetricDef("http_server_requests_seconds_count", Map.of("status", "500"), "errors"),
                new Catalogue.MetricDef("other_metric", Map.of("label", "a \"quoted\" one"), "q")));
        assertThat(v).containsEntry("sessions", 9.0).containsEntry("tc1", 2.0).containsEntry("telephony_controller_ready", 1.0)
                .containsEntry("q", 5.0).doesNotContainKey("errors");
    }
}
