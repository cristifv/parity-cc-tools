package parity.cctools.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import parity.cctools.control.ServiceOps;
import parity.cctools.ops.Action;
import parity.cctools.ops.ActionService;
import parity.cctools.ops.Pending;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.security.Operator;
import parity.cctools.security.RoleCapabilities;
import parity.cctools.support.AppStack;
import parity.cctools.support.Await;
import parity.cctools.support.FakeParityRs;
import parity.cctools.support.KubeStandIn;
import parity.cctools.support.MiStandIn;
import parity.cctools.support.TestCatalogue;
import parity.cctools.web.Reviews;

/**
 * Service control (PLAN.md §6.4) against a Kubernetes API stand-in and two opensips SBC stand-ins: a cordon
 * saga resumes after the app restarts (another pod, the same database) and finishes on the observed fact;
 * a whole service stops and starts back to its recorded count; a media node leaves the dispatcher of
 * every SBC node, drains to 0 calls, stays out when an SBC node "restarts", and comes back.
 */
class ServiceControlIT {

    static FakeParityRs rs;
    static KubeStandIn kube;
    static MiStandIn sbcA;
    static MiStandIn sbcB;
    static List<String> args;
    static ConfigurableApplicationContext app;
    static Operator OP;

    @BeforeAll
    static void start() throws Exception {
        OP = new Operator("5f0c2a9e-1b2c-4d3e-8f40-123456789abc", "jdoe", List.of("ops"), Instant.now(), List.of("pwd", "otp"),
                RoleCapabilities.of(List.of("ops")));
        rs = new FakeParityRs();
        kube = new KubeStandIn();
        kube.pod("gateway-1", Map.of("app.kubernetes.io/name", "gateway", "parity.io/rotation", "in"), true);
        kube.pod("gateway-2", Map.of("app.kubernetes.io/name", "gateway", "parity.io/rotation", "in"), true);
        kube.service("gateway", Map.of("app.kubernetes.io/name", "gateway", "parity.io/rotation", "in"));
        kube.deployment("worker", 3);
        kube.pod("tc-media-1", Map.of("app.kubernetes.io/name", "telephony-controller", "parity.media/node", "media-1"), true);
        kube.pod("tc-media-2", Map.of("app.kubernetes.io/name", "telephony-controller", "parity.media/node", "media-2"), true);
        kube.pod("opensips-a", Map.of("app.kubernetes.io/name", "opensips"), true);
        kube.pod("opensips-b", Map.of("app.kubernetes.io/name", "opensips"), true);
        sbcA = new MiStandIn();
        sbcB = new MiStandIn();
        for (MiStandIn m : List.of(sbcA, sbcB)) {
            m.table.put("1|sip:10.0.0.5:5080", "Active");
            m.table.put("2|sip:10.0.0.5:5090", "Active");
            m.table.put("1|sip:10.0.0.6:5080", "Active");
        }
        args = new ArrayList<>(AppStack.baseArgs("svc-it"));
        args.add("--PARITY_CC_TOOLS_CATALOGUE=" + TestCatalogue.write("svc-it", kube.port()));
        args.add("--parity-cc-tools.zones.eu1a.kubernetes.api-url=" + kube.url());
        args.add("--parity-cc-tools.zones.eu1a.opensips.mi-urls=" + sbcA.url() + "," + sbcB.url());
        args.add("--parity-cc-tools.zones.eu1a.opensips.pod-namespace=parity-edge");
        args.add("--parity-cc-tools.zones.eu1a.opensips.pod-selector=app.kubernetes.io/name=opensips");
        app = AppStack.start(rs, args);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        kube.close();
        sbcA.close();
        sbcB.close();
    }

    static Pending pending(ConfigurableApplicationContext ctx, Action a, String service, String instance, String driver, String ns,
            String expected, RiskPolicy.Facts facts) {
        return ctx.getBean(Reviews.class).pending(a, facts, new Reviews.Target("eu1a", null, null, service, instance, null), null, expected,
                Map.of("driver", driver, "namespace", ns), null, null, a.wire(), "/services");
    }

    static Pending.Form form(String typed, boolean force) {
        return new Pending.Form("Service control integration test", "", typed, force);
    }

    static ServiceOps.Op op(ConfigurableApplicationContext ctx, String sagaId) {
        return ctx.getBean(ServiceOps.class).find(sagaId).orElseThrow();
    }

    @Test
    void aCordonResumesAfterARestartAndEndsOnTheObservedFact() {
        kube.freezeEndpoints = true;
        Pending p = pending(app, Action.SERVICE_CORDON, "gateway", "gateway-1", "k8s-rotation", "parity-app", "gateway-1", RiskPolicy.Facts.none());
        ActionService.Result r = app.getBean(ActionService.class).confirm(OP, p, form("gateway-1", false), null);
        assertThat(r.ok()).isTrue();
        Await.until("the label out", Duration.ofSeconds(10), () -> "out".equals(kube.label("gateway-1", "parity.io/rotation")));
        // The endpoints still hold the pod: the saga waits for that fact, however long it takes.
        assertThat(op(app, p.sagaId()).state()).isEqualTo("cordoning");
        app.close();
        app = null;
        kube.unfreezeEndpoints();
        ConfigurableApplicationContext second = AppStack.start(rs, args);
        app = second;
        Await.until("the restarted app finishes the cordon", Duration.ofSeconds(15),
                () -> "out_of_rotation".equals(op(second, p.sagaId()).state()));
        ServiceOps.Op held = op(second, p.sagaId());
        assertThat(held.outcome()).as("holding until an enable releases it").isNull();
        assertThat(second.getBean(ServiceOps.class).steps(p.sagaId())).extracting(ServiceOps.Step::toState)
                .containsExactly("cordoning", "out_of_rotation");
        // Uncordon: the holding saga is released; the enable saga ends when the pod is back and ready.
        Pending back = pending(second, Action.SERVICE_UNCORDON, "gateway", "gateway-1", "k8s-rotation", "parity-app", "gateway-1",
                RiskPolicy.Facts.none());
        assertThat(second.getBean(ActionService.class).confirm(OP, back, form("gateway-1", false), null).ok()).isTrue();
        Await.until("uncordoned", Duration.ofSeconds(10), () -> "done".equals(op(second, back.sagaId()).outcome()));
        assertThat(op(second, p.sagaId()).outcome()).isEqualTo("released");
        assertThat(kube.label("gateway-1", "parity.io/rotation")).isEqualTo("in");
    }

    @Test
    void aWholeServiceStopsAndStartsBackToItsRecordedCount() {
        ConfigurableApplicationContext ctx = app;
        kube.freezeWorkloads = true;
        Pending stop = pending(ctx, Action.SERVICE_STOP, "worker", "*", "k8s-scale", "parity-app", "worker", RiskPolicy.Facts.none());
        assertThat(ctx.getBean(ActionService.class).confirm(OP, stop, form("worker", false), null).ok()).isTrue();
        Await.until("scaled to 0", Duration.ofSeconds(10), () -> kube.specReplicas("worker") == 0);
        assertThat(op(ctx, stop.sagaId()).state()).as("waits for the status, not a timer").isEqualTo("stopping");
        assertThat(op(ctx, stop.sagaId()).recorded().path("replicas").asInt()).isEqualTo(3);
        kube.settleWorkloads();
        Await.until("stopped", Duration.ofSeconds(10), () -> "stopped".equals(op(ctx, stop.sagaId()).state()));
        // A chart upgrade scales it up behind the saga: the holding saga sets 0 again.
        kube.deployment("worker", 2);
        Await.until("back to 0", Duration.ofSeconds(10), () -> kube.specReplicas("worker") == 0);
        kube.settleWorkloads();
        Pending start = pending(ctx, Action.SERVICE_START, "worker", "*", "k8s-scale", "parity-app", "worker", RiskPolicy.Facts.none());
        assertThat(ctx.getBean(ActionService.class).confirm(OP, start, form("worker", false), null).ok()).isTrue();
        Await.until("scaled back", Duration.ofSeconds(10), () -> kube.specReplicas("worker") == 3);
        kube.settleWorkloads();
        kube.freezeWorkloads = false;
        Await.until("running", Duration.ofSeconds(10), () -> "done".equals(op(ctx, start.sagaId()).outcome()));
        assertThat(op(ctx, stop.sagaId()).outcome()).isEqualTo("released");
    }

    @Test
    void aMediaNodeLeavesEverySbcNodeDrainsAndComesBack() {
        ConfigurableApplicationContext ctx = app;
        kube.metricsText = "telephony_controller_sessions{node=\"media-1\"} 3\n";
        Pending out = pending(ctx, Action.MEDIA_NODE_OUT, "media-node", "media-1", "media-node", "parity-media", "media-1",
                RiskPolicy.Facts.none());
        assertThat(ctx.getBean(ActionService.class).confirm(OP, out, form("media-1", false), null).ok()).isTrue();
        Await.until("draining", Duration.ofSeconds(10), () -> "draining".equals(op(ctx, out.sagaId()).state()));
        for (MiStandIn m : List.of(sbcA, sbcB)) {
            assertThat(m.table).containsEntry("1|sip:10.0.0.5:5080", "Inactive").containsEntry("2|sip:10.0.0.5:5090", "Inactive")
                    .containsEntry("1|sip:10.0.0.6:5080", "Active");
        }
        assertThat(kube.label("tc-media-1", "parity.io/rotation")).isEqualTo("out");
        assertThat(op(ctx, out.sagaId()).state()).as("calls still up: no timer moves it").isEqualTo("draining");
        kube.metricsText = "telephony_controller_sessions{node=\"media-1\"} 0\n";
        Await.until("out of rotation", Duration.ofSeconds(10), () -> "out_of_rotation".equals(op(ctx, out.sagaId()).state()));
        // CT-001: SBC node A restarts (persistent_state 0: every entry active again) and its pod restarts: the
        // held saga reads back and sets the node's entries inactive again; the other node's entry stays active.
        sbcA.table.replaceAll((k, v) -> "Active");
        kube.restartPod("opensips-a");
        Await.until("set inactive again on the restarted SBC node", Duration.ofSeconds(10),
                () -> "Inactive".equals(sbcA.table.get("1|sip:10.0.0.5:5080")) && "Inactive".equals(sbcA.table.get("2|sip:10.0.0.5:5090")));
        assertThat(sbcA.table).containsEntry("1|sip:10.0.0.6:5080", "Active");
        assertThat(op(ctx, out.sagaId()).state()).as("still held").isEqualTo("out_of_rotation");
        assertThat(op(ctx, out.sagaId()).outcome()).isNull();
        Pending in = pending(ctx, Action.MEDIA_NODE_IN, "media-node", "media-1", "media-node", "parity-media", "media-1",
                RiskPolicy.Facts.none());
        assertThat(ctx.getBean(ActionService.class).confirm(OP, in, form("media-1", false), null).ok()).isTrue();
        Await.until("back in rotation", Duration.ofSeconds(10), () -> "done".equals(op(ctx, in.sagaId()).outcome()));
        for (MiStandIn m : List.of(sbcA, sbcB)) {
            assertThat(m.table).containsEntry("1|sip:10.0.0.5:5080", "Active").containsEntry("2|sip:10.0.0.5:5090", "Active");
        }
        assertThat(kube.label("tc-media-1", "parity.io/rotation")).isEqualTo("in");
    }
}
