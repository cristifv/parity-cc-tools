package parity.cctools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import parity.cctools.audit.AuditQuery;
import parity.cctools.audit.AuditUnavailableException;
import parity.cctools.control.ServiceOps;
import parity.cctools.ops.Action;
import parity.cctools.ops.ActionService;
import parity.cctools.ops.Pending;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.routing.RsClient;
import parity.cctools.security.Operator;
import parity.cctools.security.RoleCapabilities;
import parity.cctools.support.AppStack;
import parity.cctools.support.Await;
import parity.cctools.support.Containers;
import parity.cctools.support.FakeParityRs;
import parity.cctools.support.KubeStandIn;
import parity.cctools.support.TestCatalogue;
import parity.cctools.web.Reviews;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log (PLAN.md §5) against Postgres: the rows of an action hold who, why, before/after and the
 * op_id; if the requested row can't be written nothing is sent, to parity-rs or to Kubernetes (fail
 * closed); an action not confirmed in time is retried with the same ids.
 */
class AuditIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static FakeParityRs rs;
    static KubeStandIn kube;
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
        List<String> args = new ArrayList<>(AppStack.baseArgs("audit-it"));
        args.add("--PARITY_CC_TOOLS_CATALOGUE=" + TestCatalogue.write("audit-it", kube.port()));
        args.add("--parity-cc-tools.zones.eu1a.kubernetes.api-url=" + kube.url());
        args.add("--parity-cc-tools.health.enabled=false");
        app = AppStack.start(rs, args);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        kube.close();
    }

    Pending move(String tenant) {
        var before = JSON.createObjectNode();
        before.putObject("placement").put("deployment_unit", "bin-a");
        before.put("version", 7);
        var after = before.deepCopy();
        after.putObject("placement").put("deployment_unit", "bin-b");
        return app.getBean(Reviews.class).pending(Action.TENANT_MOVE, RiskPolicy.Facts.none(), Reviews.Target.tenant("eu1a", tenant), null,
                tenant, Map.of("to_unit", "bin-b", "expected_version", "7", "force", "false"), before, after, "Move", "/");
    }

    static final Pending.Form FORM = new Pending.Form("Move acme to the previous release for a support case", "OPS-42", "acme", false);

    @Test
    void anActionsRowsHoldWhoWhyBeforeAfterAndTheOpId() throws Exception {
        Pending p = move("acme");
        ActionService.Result r = app.getBean(ActionService.class).confirm(OP, p, FORM, "10.1.2.3");
        assertThat(r.ok()).isTrue();
        assertThat(r.sagaId()).isEqualTo(p.sagaId());
        List<AuditQuery.Row> rows = app.getBean(AuditQuery.class).byOpId(p.opId());
        assertThat(rows).extracting(AuditQuery.Row::phase).containsExactly("requested", "answered");
        AuditQuery.Row req = rows.get(0);
        assertThat(req.actorSub()).isEqualTo(OP.sub());
        assertThat(req.actorUsername()).isEqualTo("jdoe");
        assertThat(req.actorRoles()).containsExactly("ops");
        assertThat(req.authTime()).isNotNull();
        assertThat(req.reason()).isEqualTo(FORM.reason());
        assertThat(req.ticket()).isEqualTo("OPS-42");
        assertThat(req.action()).isEqualTo("tenant.move");
        assertThat(req.tenantId()).isEqualTo("acme");
        assertThat(req.zone()).isEqualTo("eu1a");
        assertThat(req.confirmKind()).isEqualTo("typed");
        assertThat(req.sagaId()).isEqualTo(p.sagaId());
        assertThat(req.before().path("placement").path("deployment_unit").asString()).isEqualTo("bin-a");
        assertThat(req.after().path("placement").path("deployment_unit").asString()).isEqualTo("bin-b");
        assertThat(req.request().path("op_id").asString()).isEqualTo(p.opId());
        assertThat(req.request().path("saga_id").asString()).isEqualTo(p.sagaId());
        assertThat(req.request().path("expected_version").asLong()).isEqualTo(7);
        assertThat(rows.get(1).outcome()).isEqualTo("ok");
        try (Connection c = Containers.owner(); Statement s = c.createStatement();
                ResultSet q = s.executeQuery("SELECT client_ip, amr FROM audit_log WHERE op_id = '" + p.opId() + "' AND phase = 'requested'")) {
            assertThat(q.next()).isTrue();
            assertThat(q.getString(1)).isEqualTo("10.1.2.3");
            assertThat(q.getString(2)).isEqualTo("{pwd,otp}");
        }
        // What parity-rs got: one move_tenant, valid, on the ops topic, with the same ids and the op_id header.
        var sent = rs.commands("move_tenant").stream().filter(x -> p.opId().equals(x.body().path("op_id").asString())).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).headers()).containsEntry("op_id", p.opId()).containsEntry("tenant_id", "acme");
        assertThat(sent.get(0).key()).isEqualTo("acme");
        assertThat(sent.get(0).topic()).isEqualTo(parity.cctools.routing.RsTransport.Topic.OPS);
        assertThat(rs.violations).isEmpty();
        // The saga's end (its events) gives the outcome row, once.
        JdbcClient db = app.getBean(JdbcClient.class);
        Await.until("the outcome row", Duration.ofSeconds(10), () -> db.sql("SELECT count(*) FROM operation_outcome WHERE saga_id = CAST(? AS uuid)")
                .param(p.sagaId()).query(Integer.class).single() == 1);
        assertThat(db.sql("SELECT outcome FROM operation_outcome WHERE saga_id = CAST(? AS uuid)").param(p.sagaId()).query(String.class).single())
                .isEqualTo("done");
    }

    @Test
    void whenTheAuditRowCantBeWrittenNothingIsSent() throws Exception {
        int sentBefore = rs.received.size();
        int patchesBefore = kube.patches.size();
        ServiceOps ops = app.getBean(ServiceOps.class);
        int opsBefore = ops.recent(1000).size();
        try (Connection c = Containers.owner(); Statement s = c.createStatement()) {
            s.execute("REVOKE INSERT ON audit_log FROM " + Containers.APP_ROLE);
        }
        try {
            Pending p = move("acme");
            assertThatThrownBy(() -> app.getBean(ActionService.class).confirm(OP, p, FORM, "10.1.2.3"))
                    .isInstanceOf(AuditUnavailableException.class);
            Pending cordon = app.getBean(Reviews.class).pending(Action.SERVICE_CORDON, RiskPolicy.Facts.none(),
                    new Reviews.Target("eu1a", null, null, "gateway", "gateway-1", null), null, "gateway-1",
                    Map.of("driver", "k8s-rotation", "namespace", "parity-app"), null, null, "Cordon", "/");
            assertThatThrownBy(() -> app.getBean(ActionService.class).confirm(OP, cordon,
                    new Pending.Form("Cordon gateway-1 to test fail closed", "", "gateway-1", false), "10.1.2.3"))
                    .isInstanceOf(AuditUnavailableException.class);
            Thread.sleep(500);
            assertThat(rs.received).as("nothing reached parity-rs").hasSize(sentBefore);
            assertThat(ops.recent(1000)).as("no service-control saga").hasSize(opsBefore);
            assertThat(kube.patches).as("nothing reached Kubernetes").hasSize(patchesBefore);
            assertThat(kube.label("gateway-1", "parity.io/rotation")).isEqualTo("in");
        } finally {
            try (Connection c = Containers.owner(); Statement s = c.createStatement()) {
                s.execute("GRANT INSERT ON audit_log TO " + Containers.APP_ROLE);
            }
        }
    }

    @Test
    void notConfirmedThenRetriedWithTheSameIds() {
        Pending p = move("globex");
        Pending.Form f = new Pending.Form("Move globex for a load test", "", "globex", false);
        rs.silent = true;
        ActionService.Result r;
        try {
            r = app.getBean(ActionService.class).confirm(OP, p, f, null);
        } finally {
            rs.silent = false;
        }
        assertThat(r.status()).isEqualTo(RsClient.Status.NOT_CONFIRMED);
        AuditQuery q = app.getBean(AuditQuery.class);
        assertThat(q.byOpId(p.opId())).extracting(AuditQuery.Row::outcome).containsExactly(null, "not_confirmed");
        AuditQuery.Row original = q.byOpId(p.opId()).get(0);
        ActionService.Result again = app.getBean(ActionService.class).retry(OP, new ActionService.AuditQueryView(original.opId(),
                original.sagaId(), Action.TENANT_MOVE, original.zone(), original.tenantId(), original.unit(), original.release(),
                original.confirmKind(), original.request()), null);
        assertThat(again.ok()).isTrue();
        var sends = rs.commands("move_tenant").stream().filter(x -> p.opId().equals(x.body().path("op_id").asString())).toList();
        assertThat(sends).hasSize(2);
        assertThat(sends.get(0).body().path("id").asString()).isEqualTo(sends.get(1).body().path("id").asString());
        assertThat(sends.get(0).body().path("saga_id").asString()).isEqualTo(p.sagaId()).isEqualTo(sends.get(1).body().path("saga_id").asString());
        assertThat(q.byOpId(p.opId())).extracting(AuditQuery.Row::confirmKind).contains("retry");
    }
}
