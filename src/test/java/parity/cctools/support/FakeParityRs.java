package parity.cctools.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import parity.cctools.routing.RoutingSchemas;
import parity.cctools.routing.RsTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A parity-rs stand-in behind the {@link RsTransport} interface (parity-rs isn't built yet: S3). It checks
 * every request against the contract's schema and its topic (x-topics), records it, answers like parity-rs
 * (PLAN §3) and emits saga events in seq order. {@code silent} drops the answers (not confirmed);
 * zone moves are refused with {@code zone_copy_unavailable} (v1).
 */
public final class FakeParityRs implements RsTransport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** One request as the stand-in received it. */
    public record Received(String zone, Topic topic, String key, Map<String, String> headers, JsonNode body) {}

    public final List<Received> received = new CopyOnWriteArrayList<>();
    public final List<String> violations = new CopyOnWriteArrayList<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ObjectNode> routes = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> sagas = new ConcurrentHashMap<>();
    private final RoutingSchemas schemas = new RoutingSchemas();
    public volatile boolean silent;
    public volatile Duration eventDelay = Duration.ofMillis(50);

    public FakeParityRs() {
        route("acme", "bin-a", 7);
        route("globex", "bin-b", 3);
    }

    private void route(String tenant, String unit, long version) {
        ObjectNode r = JSON.createObjectNode();
        r.put("tenant_id", tenant);
        r.put("name", tenant.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + tenant.substring(1) + " Ltd");
        r.put("tier", "small");
        r.putNull("parent");
        ObjectNode p = r.putObject("placement");
        p.put("home_zone", "eu1a").put("failover_zone", "eu2a").put("zone", "eu1a").put("cell", "eu1a").put("deployment_unit", unit)
                .put("media_track", "blue");
        r.put("version", version);
        r.put("revision", 1834);
        r.put("owner_zone", "eu1a");
        r.putObject("assignment").put("unit", unit).put("media_track", "blue").put("epoch", 7702);
        r.putObject("unit").put("role", "live").put("release", "1.8.3").put("ready", true).put("health", "ok");
        r.putNull("pin");
        r.putNull("active_saga");
        routes.put(tenant, r);
    }

    public List<Received> commands(String cmd) {
        return received.stream().filter(r -> cmd.equals(r.body().path("cmd").asString())).toList();
    }

    @Override
    public CompletableFuture<JsonNode> send(String zone, Topic topic, String key, Map<String, String> headers, String id, byte[] body) {
        JsonNode b = JSON.readTree(body);
        received.add(new Received(zone, topic, key, Map.copyOf(headers), b));
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        var problems = schemas.check(RoutingSchemas.REQUESTS, b);
        String cmd = b.path("cmd").asString("");
        if (!problems.isEmpty()) {
            violations.add(cmd + " " + problems);
        }
        if (!schemas.allowedOn(cmd).contains(topic == Topic.OPS ? "ops" : "requests")) {
            violations.add(cmd + " on the wrong topic " + topic);
        }
        if (b.hasNonNull("tenant_id") && !b.path("tenant_id").asString().equals(headers.get("tenant_id"))) {
            violations.add(cmd + " tenant_id header differs");
        }
        if (silent) {
            return f;
        }
        ObjectNode reply = JSON.createObjectNode();
        reply.put("schema_version", 1);
        reply.put("id", id);
        try {
            reply.put("ok", true);
            reply.set("result", handle(zone, b));
        } catch (Refusal r) {
            reply.removeAll();
            reply.put("schema_version", 1);
            reply.put("id", id);
            reply.put("ok", false);
            ObjectNode e = reply.putObject("error");
            e.put("code", r.code);
            e.put("message", r.code);
            if (r.rule != null) {
                e.put("rule", r.rule);
            }
        }
        var answer = reply;
        Thread.ofVirtual().start(() -> f.complete(answer));
        return f;
    }

    static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        final String rule;

        Refusal(String code, String rule) {
            super(code, null, false, false);
            this.code = code;
            this.rule = rule;
        }
    }

    private JsonNode handle(String zone, JsonNode b) {
        String cmd = b.path("cmd").asString();
        ObjectNode result = JSON.createObjectNode();
        switch (cmd) {
            case "ping" -> result.put("pong", true).put("zone", zone).put("role", "leader");
            case "get_route" -> {
                ObjectNode r = routes.get(b.path("tenant_id").asString());
                if (r == null) {
                    throw new Refusal("not_found", null);
                }
                result.set("route", r.deepCopy());
            }
            case "list_routes" -> {
                ArrayNode a = result.putArray("routes");
                routes.values().stream().sorted((x, y) -> x.path("tenant_id").asString().compareTo(y.path("tenant_id").asString()))
                        .forEach(r -> a.add(r.deepCopy()));
                result.putNull("next_after");
            }
            case "get_topology" -> {
                ObjectNode regions = result.putObject("regions");
                regions.putObject("eu1").put("residency", "eu").put("default_zone", "eu1a");
                ObjectNode z = result.putObject("zones").putObject("eu1a");
                z.put("region", "eu1").put("failover", "eu2a").put("open", true).put("state", "active");
                ObjectNode units = z.putObject("units");
                units.putObject("bin-a").put("role", "live").put("release", "1.8.3").put("ready", true).put("health", "ok").put("tenant_count", 1);
                units.putObject("bin-b").put("role", "previous").put("release", "1.8.3").put("ready", true).put("health", "ok").put("tenant_count", 1);
                units.putObject("bin-c").put("role", "empty").put("release", "1.9.0").put("ready", true).put("health", "ok").put("tenant_count", 0);
            }
            case "get_saga" -> {
                ObjectNode s = sagas.get(b.path("saga_id").asString());
                if (s == null) {
                    throw new Refusal("not_found", null);
                }
                result.set("saga", s.deepCopy());
            }
            case "list_sagas" -> {
                ArrayNode a = result.putArray("sagas");
                sagas.values().forEach(s -> a.add(s.deepCopy()));
                result.putNull("next_after");
            }
            case "validate_move" -> {
                if (b.hasNonNull("to_zone")) {
                    throw new Refusal("conflict", "zone_copy_unavailable");
                }
                ObjectNode r = routes.get(b.path("tenant_id").asString());
                if (r == null) {
                    throw new Refusal("not_found", null);
                }
                if (r.path("version").asLong() != b.path("expected_version").asLong()) {
                    throw new Refusal("conflict", "version_mismatch");
                }
                ObjectNode plan = result.putObject("plan");
                plan.putArray("steps").add("fence the old unit (etcd)").add("switch the store placement").add("hand over");
                plan.set("before", r.path("placement"));
                ObjectNode after = ((ObjectNode) r.path("placement")).deepCopy();
                after.put("deployment_unit", b.path("to_unit").asString());
                plan.set("after", after);
                plan.putArray("warnings").add("bin-b runs the previous release (1.8.3)");
            }
            case "move_tenant_zone" -> throw new Refusal("conflict", "zone_copy_unavailable");
            case "move_tenant", "set_failover_zone", "start_rollout", "drain_unit" -> {
                String sagaId = b.path("saga_id").asString();
                ObjectNode existing = sagas.get(sagaId);
                if (existing == null) {
                    startSaga(zone, b, sagaId, kind(cmd));
                }
                result.put("accepted", true).put("saga_id", sagaId);
            }
            default -> result.put("accepted", true);
        }
        return result;
    }

    static String kind(String cmd) {
        return switch (cmd) {
            case "move_tenant" -> "unit_move";
            case "set_failover_zone" -> "placement_update";
            case "start_rollout" -> "rollout";
            default -> "drain";
        };
    }

    private void startSaga(String zone, JsonNode b, String sagaId, String kind) {
        ObjectNode s = JSON.createObjectNode();
        s.put("saga_id", sagaId).put("kind", kind).put("zone", zone).put("state", "accepted").putNull("outcome")
                .put("op_id", b.path("op_id").asString()).putNull("parent_saga_id").put("seq", 1)
                .put("created_at_ms", System.currentTimeMillis()).put("updated_at_ms", System.currentTimeMillis());
        if (b.hasNonNull("tenant_id")) {
            s.put("tenant_id", b.path("tenant_id").asString());
        }
        s.set("actor", b.path("actor"));
        s.put("reason", b.path("reason").asString());
        s.putArray("steps").addObject().put("name", "accepted").put("at_ms", System.currentTimeMillis());
        sagas.put(sagaId, s);
        List<String[]> states = new ArrayList<>();
        states.add(new String[] {"accepted", "fencing"});
        states.add(new String[] {"fencing", "switching"});
        states.add(new String[] {"switching", "handing_over"});
        Thread.ofVirtual().start(() -> {
            long seq = 1;
            emit(zone, b, sagaId, kind, seq, "saga.accepted", null);
            for (String[] t : states) {
                pause();
                seq++;
                emit(zone, b, sagaId, kind, seq, "saga.state_changed", t);
                s.put("state", t[1]).put("seq", seq);
            }
            pause();
            seq++;
            emit(zone, b, sagaId, kind, seq, "saga.finished", null);
            s.put("state", "done").put("outcome", "done").put("seq", seq);
            if ("unit_move".equals(kind)) {
                ObjectNode r = routes.get(b.path("tenant_id").asString());
                ((ObjectNode) r.path("placement")).put("deployment_unit", b.path("to_unit").asString());
                r.put("version", r.path("version").asLong() + 1);
            }
        });
    }

    private void pause() {
        try {
            Thread.sleep(eventDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(String zone, JsonNode b, String sagaId, String kind, long seq, String type, String[] transition) {
        ObjectNode e = JSON.createObjectNode();
        e.put("schema_version", 1);
        e.put("event_id", uuid7());
        e.put("type", type);
        e.put("at_ms", System.currentTimeMillis());
        e.put("zone", zone);
        if (b.hasNonNull("tenant_id")) {
            e.put("tenant_id", b.path("tenant_id").asString());
        }
        e.put("saga_id", sagaId);
        e.put("op_id", b.path("op_id").asString());
        e.put("seq", seq);
        e.put("kind", kind);
        switch (type) {
            case "saga.accepted" -> e.set("actor", b.path("actor"));
            case "saga.state_changed" -> e.put("from_state", transition[0]).put("to_state", transition[1]).put("step", transition[1]);
            default -> e.put("outcome", "done");
        }
        var schemaErrors = schemas.check(RoutingSchemas.EVENTS, e);
        if (!schemaErrors.isEmpty()) {
            violations.add("an event of the stand-in itself: " + schemaErrors);
        }
        Event ev = new Event(zone, sagaId, e);
        listeners.forEach(l -> l.accept(ev));
    }

    static String uuid7() {
        return parity.cctools.obs.Ids.newUuid7();
    }

    @Override
    public void forget(String id) {
        // Nothing waits here.
    }

    @Override
    public void onEvent(Consumer<Event> listener) {
        listeners.add(listener);
    }

    @Override
    public String failure() {
        return null;
    }
}
