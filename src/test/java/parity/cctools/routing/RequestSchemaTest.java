package parity.cctools.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import parity.cctools.obs.ToolMetrics;
import parity.cctools.ops.Action;
import parity.cctools.ops.Pending;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.ops.RoutingActions;
import parity.cctools.security.Operator;
import parity.cctools.security.RoleCapabilities;
import parity.cctools.support.TestProps;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every request this tool can write validates against parity-contracts' tenant-routing-requests.v1.json
 * (0.16.0 schemas, pinned through 0.17.0), on the topic it is sent to (x-topics); the key and headers
 * follow CONVENTIONS "Tenant routing API".
 */
class RequestSchemaTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    final RoutingSchemas schemas = new RoutingSchemas();
    final RsClient rs = new RsClient(null, schemas, TestProps.oneZone(), new ToolMetrics(new SimpleMeterRegistry()));
    final Operator op = new Operator("5f0c2a9e-1b2c-4d3e-8f40-123456789abc", "jdoe", List.of("ops", "offline_access"), null,
            List.of("otp"), RoleCapabilities.of(List.of("ops")));
    static final String OP = "0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b";
    static final String SAGA = "0193a1b2-c3d4-7e5f-9a6b-7c8d9e0f1a2c";

    ObjectNode full(ObjectNode fields) {
        return rs.request(fields, rs.newId(fields.path("cmd").asString()));
    }

    void valid(ObjectNode fields, String topic) {
        ObjectNode body = full(fields);
        assertThat(rs.problems(body)).as(body.toString()).isEmpty();
        assertThat(schemas.allowedOn(body.path("cmd").asString())).as("x-topics of " + body.path("cmd")).contains(topic);
    }

    @Test
    void everyReadIsValidOnTheReadsTopic() {
        valid(RsRequests.ping(), "requests");
        valid(RsRequests.getRoute("acme"), "requests");
        valid(RsRequests.listRoutes(null, 50, "eu1a", "bin-a", "small"), "requests");
        valid(RsRequests.listRoutes("acme", 9999, null, null, null), "requests");
        valid(RsRequests.getTopology(), "requests");
        valid(RsRequests.getSaga(SAGA), "requests");
        valid(RsRequests.listSagas("active", "rollout", "acme", SAGA, 500), "requests");
    }

    @Test
    void everyOperationIsValidOnTheOpsTopic() {
        RsRequests.Op o = new RsRequests.Op(OP, op.actor(), "Move acme for a support case", "OPS-123");
        valid(RsRequests.validateMove(o, "acme", 7, "bin-b", null, "blue", false), "ops");
        valid(RsRequests.validateMove(o, "acme", 7, null, "eu2a", null, false), "ops");
        valid(RsRequests.moveTenant(o, SAGA, "acme", "bin-b", 7, "blue", true), "ops");
        valid(RsRequests.moveTenantZone(o, SAGA, "acme", "eu2a", null, 7, null), "ops");
        valid(RsRequests.setFailoverZone(o, SAGA, "acme", "eu2a", 7), "ops");
        valid(RsRequests.setFailoverZone(o, SAGA, "acme", null, 7), "ops");
        for (String c : List.of("confirm_cutover", "cancel_saga", "stop_rollout", "resume_rollout", "rollback_rollout")) {
            valid(RsRequests.onSaga(c, o, SAGA), "ops");
        }
        valid(RsRequests.resolveSaga(o, SAGA, "retry_record"), "ops");
        valid(RsRequests.advanceRollout(o, SAGA, 2), "ops");
        valid(RsRequests.pinTenant(o, "acme", 1793000000000L), "ops");
        valid(RsRequests.unpinTenant(o, "acme"), "ops");
        valid(RsRequests.startRollout(o, SAGA, "eu1a", "1.9.0", "bin-c",
                List.of(new RsRequests.Ring("test", "test", null), new RsRequests.Ring("all", null, 100)), 5), "ops");
        valid(RsRequests.drainUnit(o, SAGA, "eu1a", "bin-a", "bin-b", true), "ops");
        valid(RsRequests.releaseUnit(o, "eu1a", "bin-c"), "ops");
        // An operation is never allowed on the reads topic (parity-rs would answer forbidden).
        assertThat(schemas.allowedOn("move_tenant")).doesNotContain("requests");
    }

    @Test
    void everyRoutingActionsBodyValidates() {
        Map<Action, Map<String, String>> params = new LinkedHashMap<>();
        params.put(Action.TENANT_MOVE, Map.of("to_unit", "bin-b", "expected_version", "7", "force", "false"));
        params.put(Action.TENANT_MOVE_ZONE, Map.of("to_zone", "eu2a", "expected_version", "7", "cutover", "on_confirm"));
        params.put(Action.TENANT_SET_FAILOVER, Map.of("failover_zone", "", "expected_version", "7"));
        params.put(Action.TENANT_PIN, Map.of("until_ms", "1793000000000"));
        params.put(Action.TENANT_UNPIN, Map.of());
        params.put(Action.SAGA_CANCEL, Map.of());
        params.put(Action.SAGA_CONFIRM_CUTOVER, Map.of());
        params.put(Action.SAGA_RESOLVE, Map.of("resolve_action", "accept_store"));
        params.put(Action.ROLLOUT_STOP, Map.of());
        params.put(Action.ROLLOUT_RESUME, Map.of());
        params.put(Action.ROLLOUT_ROLLBACK, Map.of());
        params.put(Action.ROLLOUT_ADVANCE, Map.of("expected_ring", "2"));
        params.put(Action.ROLLOUT_START, Map.of("rings", "test:test;internal:internal;all:percent=100", "max_parallel", "4"));
        params.put(Action.UNIT_DRAIN, Map.of("to_unit", "bin-b", "allow_release_change", "true"));
        params.put(Action.UNIT_RELEASE, Map.of());
        Set<Action> routing = EnumSet.noneOf(Action.class);
        for (Action a : Action.values()) {
            if (a.routing()) {
                routing.add(a);
            }
        }
        assertThat(params.keySet()).containsExactlyInAnyOrderElementsOf(routing);
        for (var e : params.entrySet()) {
            Pending p = new Pending(OP, SAGA, rs.newId(RoutingActions.cmd(e.getKey())), e.getKey(), RiskPolicy.tier(e.getKey(), RiskPolicy.Facts.none()),
                    false, "eu1a", "acme", "bin-c", null, null, "1.9.0", "acme", e.getValue(), null, null, "t", "/", 0,
                    Pending.Form.empty());
            ObjectNode body = rs.request(RoutingActions.body(p, op, new Pending.Form("A reason long enough", "T-1", "acme", false)), p.requestId());
            assertThat(rs.problems(body)).as(e.getKey() + ": " + body).isEmpty();
            assertThat(body.path("op_id").asString()).isEqualTo(OP);
            assertThat(schemas.allowedOn(body.path("cmd").asString())).contains("ops");
        }
    }

    @Test
    void keysAndHeadersFollowTheConventions() {
        ObjectNode move = full(RsRequests.moveTenant(new RsRequests.Op(OP, op.actor(), "Move acme for a support case", null), SAGA, "acme",
                "bin-b", 7, null, false));
        assertThat(RsClient.key(move)).isEqualTo("acme");
        assertThat(RsClient.headers(move)).containsEntry("tenant_id", "acme").containsEntry("op_id", OP).containsEntry("schema_version", "1")
                .containsKey("traceparent");
        ObjectNode drain = full(RsRequests.drainUnit(new RsRequests.Op(OP, op.actor(), "Drain bin-a for the upgrade", null), SAGA, "eu1a",
                "bin-a", null, false));
        assertThat(RsClient.key(drain)).isEqualTo(SAGA);
        assertThat(RsClient.headers(drain)).doesNotContainKey("tenant_id");
        ObjectNode topo = full(RsRequests.getTopology());
        assertThat(RsClient.key(topo)).isEqualTo(topo.path("id").asString());
        assertThat(topo.path("reply_to").asString()).isEqualTo("replies.parity-cc-tools");
        assertThat(topo.path("id").asString()).matches("^[A-Za-z0-9._:-]{1,200}$");
    }

    @Test
    void theContractsFixturesAgreeWithThisValidator() throws Exception {
        Path dir = Path.of(System.getProperty("parity.contracts.dir", "../parity-contracts"), "fixtures/tenant-routing.v1");
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir.resolve("valid"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("request-")).toList()) {
                JsonNode value = JSON.readTree(f.toFile()).path("record").path("value");
                if (!schemas.check(RoutingSchemas.REQUESTS, value).isEmpty()) {
                    wrong.add(f.getFileName().toString());
                }
            }
        }
        for (String bad : List.of("request-no-op-id.json", "request-no-reason.json", "request-no-expected-version.json")) {
            JsonNode value = JSON.readTree(dir.resolve("invalid").resolve(bad).toFile()).path("record").path("value");
            if (schemas.check(RoutingSchemas.REQUESTS, value).isEmpty()) {
                wrong.add(bad + " (should fail)");
            }
        }
        assertThat(wrong).isEmpty();
    }
}
