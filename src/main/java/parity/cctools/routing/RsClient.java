package parity.cctools.routing;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.obs.ToolMetrics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * parity-rs's request/reply protocol (parity-contracts CONVENTIONS "Tenant routing API"):
 * <ul>
 *   <li>a request carries {@code schema_version}, {@code cmd}, {@code id} = {@code <instance>:<cmd>-<uuid>}
 *       and {@code reply_to}; key: the tenant, else the saga, else the id; headers
 *       {@code schema_version}, {@code traceparent}, {@code tenant_id} when it names a tenant, {@code op_id}
 *       when it has one. It is checked against the requests schema first: one that fails is never sent;</li>
 *   <li>reads go to the zone's reads topic, operations to its ops topic;</li>
 *   <li>a person waits at most {@code answer-timeout} (5 s): then the answer is {@code NOT_CONFIRMED} and
 *       the page follows the saga's events by its {@code saga_id}; a retry resends the same body with the
 *       same {@code id} and {@code saga_id} (parity-rs returns the same saga);</li>
 *   <li>{@code wrong_zone} is followed once, to the {@code owner_zone} named, if this tool knows it.</li>
 * </ul>
 */
public class RsClient {

    private static final Logger log = LoggerFactory.getLogger(RsClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How a request ended for the person waiting. */
    public enum Status { OK, ERROR, NOT_CONFIRMED, NOT_SENT }

    /** An answer (or its absence). {@code zone}: the zone that answered (after a wrong_zone follow). */
    public record Answer(Status status, JsonNode result, String code, String message, String rule, String ownerZone,
            String zone, String id) {

        public boolean ok() {
            return status == Status.OK;
        }

        static Answer of(JsonNode reply, String zone, String id) {
            if (reply.path("ok").asBoolean(false)) {
                return new Answer(Status.OK, reply.path("result"), null, null, null, null, zone, id);
            }
            JsonNode e = reply.path("error");
            return new Answer(Status.ERROR, null, e.path("code").asString("internal"), e.path("message").asString(""),
                    e.hasNonNull("rule") ? e.path("rule").asString() : null,
                    e.hasNonNull("owner_zone") ? e.path("owner_zone").asString() : null, zone, id);
        }
    }

    /** A read that parity-rs refused or didn't answer: the page shows {@link #answer()}'s code. */
    public static final class ReadFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Answer answer;

        public ReadFailedException(Answer answer) {
            super("parity-rs didn't answer the read");
            this.answer = answer;
        }

        public Answer answer() {
            return answer;
        }
    }

    private final RsTransport transport;
    private final RoutingSchemas schemas;
    private final CcToolsProperties.Routing routing;
    private final java.util.Set<String> zones;
    private final String instance;
    private final ToolMetrics metrics;

    public RsClient(RsTransport transport, RoutingSchemas schemas, CcToolsProperties properties, ToolMetrics metrics) {
        this.transport = transport;
        this.schemas = schemas;
        this.routing = properties.routing();
        this.zones = properties.zones().keySet();
        this.instance = properties.instance().replaceAll("[^A-Za-z0-9._-]", "-");
        this.metrics = metrics;
    }

    public java.util.Set<String> zones() {
        return zones;
    }

    /** A new command id: {@code <instance>:<cmd>-<uuid>}. */
    public String newId(String cmd) {
        return instance + ":" + cmd + "-" + UUID.randomUUID();
    }

    /** The full request: the envelope first, then the command's fields. */
    public ObjectNode request(ObjectNode fields, String id) {
        ObjectNode b = JSON.createObjectNode();
        b.put("schema_version", 1);
        b.put("cmd", fields.path("cmd").asString(""));
        b.put("id", id);
        b.put("reply_to", routing.repliesTopic());
        for (var e : fields.properties()) {
            if (!b.has(e.getKey())) {
                b.set(e.getKey(), e.getValue());
            }
        }
        return b;
    }

    /** The schema problems of a full request (empty: it may be sent). Names locations, never values. */
    public List<RoutingSchemas.Problem> problems(ObjectNode request) {
        return schemas.check(RoutingSchemas.REQUESTS, request);
    }

    /** A read: validated, sent to the zone's reads topic, its {@code result} or {@link ReadFailedException}. */
    public JsonNode read(String zone, ObjectNode fields) {
        String cmd = fields.path("cmd").asString("");
        ObjectNode body = request(fields, newId(cmd));
        mustFit(body);
        Answer a = call(zone, RsTransport.Topic.READS, body);
        if (!a.ok()) {
            log.atWarn().addKeyValue("event", "rs.read_failed").addKeyValue("op", cmd).addKeyValue("zone", zone)
                    .addKeyValue("request_id", a.id()).addKeyValue("error_code", code(a))
                    .addKeyValue("rule", a.rule()).log("a parity-rs read failed");
            throw new ReadFailedException(a);
        }
        return a.result();
    }

    /** Throws {@link IllegalStateException} for a request that fails its schema (a bug here). */
    public void mustFit(ObjectNode body) {
        List<RoutingSchemas.Problem> p = problems(body);
        if (!p.isEmpty()) {
            log.atError().addKeyValue("event", "rs.read_failed").addKeyValue("op", body.path("cmd").asString("unknown"))
                    .addKeyValue("error_code", "bad_request").addKeyValue("problems", p.toString())
                    .log("a request fails its schema; not sent");
            throw new IllegalStateException("a parity-rs request failed its schema");
        }
    }

    /**
     * Sends a full, validated request on {@code topic} of {@code zone} and waits for its answer; follows
     * {@code wrong_zone} once. Never throws for a refusal or a missing answer: the {@link Answer} says.
     */
    public Answer call(String zone, RsTransport.Topic topic, ObjectNode body) {
        Answer a = once(zone, topic, body);
        if (a.status() == Status.ERROR && "wrong_zone".equals(a.code()) && a.ownerZone() != null
                && !a.ownerZone().equals(zone) && zones.contains(a.ownerZone())) {
            log.atInfo().addKeyValue("event", "rs.wrong_zone_followed").addKeyValue("request_id", a.id())
                    .addKeyValue("zone", zone).addKeyValue("owner_zone", a.ownerZone())
                    .addKeyValue("op_id", body.path("op_id").asString(null)).log("sent again to the owner zone");
            a = once(a.ownerZone(), topic, body);
        }
        return a;
    }

    private Answer once(String zone, RsTransport.Topic topic, ObjectNode body) {
        String id = body.path("id").asString();
        String cmd = body.path("cmd").asString("");
        long started = System.nanoTime();
        byte[] bytes = JSON.writeValueAsBytes(body);
        CompletableFuture<JsonNode> f = transport.send(zone, topic, key(body), headers(body), id, bytes);
        Duration wait = routing.answerTimeout();
        try {
            JsonNode reply = f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            Answer a = Answer.of(reply, zone, id);
            metrics.backend("parity-rs", cmd, started);
            log.atDebug().addKeyValue("event", "rs.answered").addKeyValue("request_id", id).addKeyValue("op", cmd)
                    .addKeyValue("zone", zone).addKeyValue("outcome", a.ok() ? "ok" : "refused")
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0)
                    .addKeyValue("op_id", body.path("op_id").asString(null)).log("parity-rs answered");
            return a;
        } catch (TimeoutException e) {
            transport.forget(id);
            metrics.backend("parity-rs", cmd, started);
            return new Answer(Status.NOT_CONFIRMED, null, "timeout", null, null, null, zone, id);
        } catch (ExecutionException e) {
            transport.forget(id);
            return new Answer(Status.NOT_SENT, null, "not_sent", null, null, null, zone, id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transport.forget(id);
            return new Answer(Status.NOT_CONFIRMED, null, "interrupted", null, null, null, zone, id);
        }
    }

    /** The record key: the tenant, else the saga, else the id (CONVENTIONS "Tenant routing API"). */
    static String key(ObjectNode body) {
        for (String f : new String[] {"tenant_id", "saga_id", "id"}) {
            if (body.hasNonNull(f) && body.path(f).isString()) {
                return body.path(f).asString();
            }
        }
        return body.path("id").asString();
    }

    static Map<String, String> headers(ObjectNode body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("schema_version", "1");
        h.put("traceparent", Traces.fresh());
        if (body.hasNonNull("tenant_id")) {
            h.put("tenant_id", body.path("tenant_id").asString());
        }
        if (body.hasNonNull("op_id")) {
            h.put("op_id", body.path("op_id").asString());
        }
        return h;
    }

    private static String code(Answer a) {
        return a.code() == null ? "internal" : a.code();
    }
}
