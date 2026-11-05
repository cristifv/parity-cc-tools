package parity.cctools.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.obs.Ids;
import parity.cctools.obs.ToolMetrics;
import parity.cctools.routing.KafkaRsTransport;
import parity.cctools.routing.RoutingSchemas;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.routing.RsTransport;
import parity.cctools.routing.SagaProjection;
import parity.cctools.support.Await;
import parity.cctools.support.Containers;
import parity.cctools.support.TestProps;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parity-rs client on real Kafka (Redpanda), against a parity-rs stand-in that reads the zones' request
 * topics and answers on replies.parity-cc-tools: a read, an operation that is answered wrong_zone and
 * followed once to the owner zone, and that saga's events (written in a Kafka transaction) reaching the
 * projection through tenant.routing.events.<zone> with read_committed.
 */
class RsKafkaIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static KafkaRsTransport transport;
    static RsClient client;
    static SagaProjection projection;
    static Thread standIn;
    static final AtomicBoolean running = new AtomicBoolean(true);
    static final List<JsonNode> seen = new CopyOnWriteArrayList<>();
    static final List<String> problems = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        String bootstrap = Containers.redpanda().getBootstrapServers();
        Map<String, CcToolsProperties.Zone> zones = new LinkedHashMap<>();
        zones.put("eu1a", TestProps.zone(bootstrap));
        zones.put("eu2a", TestProps.zone(bootstrap));
        CcToolsProperties props = TestProps.of(zones);
        transport = new KafkaRsTransport(props);
        RoutingSchemas schemas = new RoutingSchemas();
        client = new RsClient(transport, schemas, props, new ToolMetrics(new SimpleMeterRegistry()));
        projection = new SagaProjection();
        transport.onEvent(projection::apply);
        standIn = Thread.ofPlatform().name("rs-stand-in").daemon(true).start(() -> serve(bootstrap, schemas));
        Await.until("the readers assigned", Duration.ofSeconds(60), () -> transport.failure() == null);
    }

    @AfterAll
    static void stop() {
        running.set(false);
        transport.close();
    }

    static void serve(String bootstrap, RoutingSchemas schemas) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "parity-rs-stand-in");
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "parity-rs.it-" + UUID.randomUUID());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(c, new StringDeserializer(), new ByteArrayDeserializer());
                KafkaProducer<String, byte[]> producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            producer.initTransactions();
            consumer.subscribe(List.of("tenant.routing.requests.eu1a", "tenant.routing.ops.requests.eu1a", "tenant.routing.requests.eu2a",
                    "tenant.routing.ops.requests.eu2a"));
            while (running.get()) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(200))) {
                    JsonNode req = JSON.readTree(r.value());
                    seen.add(req);
                    if (!schemas.check(RoutingSchemas.REQUESTS, req).isEmpty()) {
                        problems.add(req.path("cmd").asString() + " fails the schema");
                    }
                    String zone = r.topic().substring(r.topic().lastIndexOf('.') + 1);
                    ObjectNode reply = JSON.createObjectNode();
                    reply.put("schema_version", 1);
                    reply.put("id", req.path("id").asString());
                    List<ProducerRecord<String, byte[]>> out = new java.util.ArrayList<>();
                    switch (req.path("cmd").asString()) {
                        case "get_route" -> {
                            reply.put("ok", true);
                            ObjectNode route = reply.putObject("result").putObject("route");
                            route.put("tenant_id", req.path("tenant_id").asString()).put("version", 7).putNull("placement");
                        }
                        case "move_tenant" -> {
                            if ("eu1a".equals(zone)) {
                                reply.put("ok", false);
                                reply.putObject("error").put("code", "wrong_zone").put("message", "another zone owns this tenant")
                                        .put("owner_zone", "eu2a");
                            } else {
                                reply.put("ok", true);
                                reply.putObject("result").put("accepted", true).put("saga_id", req.path("saga_id").asString());
                                String saga = req.path("saga_id").asString();
                                out.add(event(zone, req, saga, 1, "saga.accepted"));
                                out.add(event(zone, req, saga, 2, "saga.state_changed"));
                                out.add(event(zone, req, saga, 3, "saga.finished"));
                            }
                        }
                        default -> {
                            reply.put("ok", false);
                            reply.putObject("error").put("code", "bad_request").put("message", "not in the stand-in");
                        }
                    }
                    ProducerRecord<String, byte[]> answer = new ProducerRecord<>(req.path("reply_to").asString(), r.key(),
                            reply.toString().getBytes(StandardCharsets.UTF_8));
                    answer.headers().add("schema_version", "1".getBytes(StandardCharsets.UTF_8));
                    producer.beginTransaction();
                    var unused = producer.send(answer);
                    for (ProducerRecord<String, byte[]> e : out) {
                        var unusedEvent = producer.send(e);
                    }
                    producer.commitTransaction();
                }
            }
        } catch (RuntimeException e) {
            problems.add("stand-in failed: " + e);
        }
    }

    static ProducerRecord<String, byte[]> event(String zone, JsonNode req, String saga, long seq, String type) {
        ObjectNode e = JSON.createObjectNode();
        e.put("schema_version", 1).put("event_id", Ids.newUuid7()).put("type", type).put("at_ms", System.currentTimeMillis())
                .put("zone", zone).put("tenant_id", req.path("tenant_id").asString()).put("saga_id", saga)
                .put("op_id", req.path("op_id").asString()).put("seq", seq).put("kind", "unit_move");
        switch (type) {
            case "saga.accepted" -> e.set("actor", req.path("actor"));
            case "saga.state_changed" -> e.put("from_state", "accepted").put("to_state", "switching");
            default -> e.put("outcome", "done");
        }
        ProducerRecord<String, byte[]> r = new ProducerRecord<>("tenant.routing.events." + zone, saga, e.toString().getBytes(StandardCharsets.UTF_8));
        r.headers().add("schema_version", "1".getBytes(StandardCharsets.UTF_8));
        r.headers().add("tenant_id", req.path("tenant_id").asString().getBytes(StandardCharsets.UTF_8));
        r.headers().add("op_id", req.path("op_id").asString().getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @Test
    void aReadIsAnswered() {
        JsonNode r = client.read("eu1a", RsRequests.getRoute("acme"));
        assertThat(r.path("route").path("tenant_id").asString()).isEqualTo("acme");
        assertThat(problems).isEmpty();
    }

    @Test
    void anOperationFollowsWrongZoneOnceAndItsEventsArrive() {
        String op = Ids.newUuid7();
        String saga = Ids.newUuid7();
        ObjectNode body = client.request(RsRequests.moveTenant(new RsRequests.Op(op, Map.of("sub", "s-1", "username", "jdoe",
                "roles", List.of("ops")), "Move acme to bin-b for the integration test", null), saga, "acme", "bin-b", 7, null, false),
                client.newId("move_tenant"));
        client.mustFit(body);
        RsClient.Answer a = client.call("eu1a", RsTransport.Topic.OPS, body);
        assertThat(a.ok()).as(a.toString()).isTrue();
        assertThat(a.zone()).isEqualTo("eu2a");
        assertThat(a.result().path("saga_id").asString()).isEqualTo(saga);
        Await.until("the saga's events", Duration.ofSeconds(20), () -> projection.get(saga) != null && projection.get(saga).finished());
        assertThat(projection.get(saga).opId()).isEqualTo(op);
        assertThat(projection.get(saga).seq()).isEqualTo(3);
        long sends = seen.stream().filter(s -> saga.equals(s.path("saga_id").asString())).count();
        assertThat(sends).as("sent to eu1a, then once to the owner zone").isEqualTo(2);
        assertThat(problems).isEmpty();
    }
}
