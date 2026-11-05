package parity.cctools.routing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parity.cctools.config.CcToolsProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * parity-rs over Kafka (PLAN.md §6.1, parity-rs PLAN §3), per zone on that zone's cluster and
 * credentials: one producer for {@code tenant.routing.requests.<zone>} and
 * {@code tenant.routing.ops.requests.<zone>}; the answers on {@code replies.parity-cc-tools} and the
 * events on {@code tenant.routing.events.<zone>}, each read by one thread with manual assignment (no
 * group), {@code read_committed}, from the latest offset. Every pod reads every answer and keeps its
 * own; every pod reads every event (the pages it serves follow sagas by id).
 */
public final class KafkaRsTransport implements RsTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaRsTransport.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RECORD = 4 * 1024 * 1024;

    private final CcToolsProperties.Routing routing;
    private final String instance;
    private final Map<String, CcToolsProperties.Kafka> kafka;
    private final Map<String, KafkaProducer<String, byte[]>> producers = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final List<Thread> readers = new ArrayList<>();
    private volatile boolean stopping;

    public KafkaRsTransport(CcToolsProperties properties) {
        this.routing = properties.routing();
        this.instance = properties.instance().replaceAll("[^A-Za-z0-9._-]", "-");
        this.kafka = new LinkedHashMap<>();
        properties.zones().forEach((zone, z) -> kafka.put(zone, z.kafka()));
        for (var e : kafka.entrySet()) {
            String zone = e.getKey();
            CcToolsProperties.Kafka k = e.getValue();
            if (k.createTopics()) {
                KafkaClients.createTopics(k, List.of(
                        topic(routing.repliesTopic(), 3600000L),
                        topic(routing.readsTopic(zone), 86400000L),
                        topic(routing.opsTopic(zone), 86400000L),
                        topic(routing.eventsTopic(zone), 604800000L)));
            }
            producers.put(zone, KafkaClients.producer(k, "parity-cc-tools-" + instance + "-" + zone, k.sendTimeout().toMillis()));
            failures.put("replies:" + zone, "not assigned yet");
            failures.put("events:" + zone, "not assigned yet");
            readers.add(Thread.ofPlatform().name("rs-replies-" + zone).daemon(true)
                    .start(() -> read(zone, k, routing.repliesTopic(), "replies:" + zone, this::answer)));
            readers.add(Thread.ofPlatform().name("rs-events-" + zone).daemon(true)
                    .start(() -> read(zone, k, routing.eventsTopic(zone), "events:" + zone, r -> event(zone, r))));
        }
    }

    private static NewTopic topic(String name, long retentionMs) {
        return new NewTopic(name, 3, (short) 1).configs(Map.of("retention.ms", Long.toString(retentionMs)));
    }

    @Override
    public CompletableFuture<JsonNode> send(String zone, Topic topic, String key, Map<String, String> headers, String id,
            byte[] body) {
        KafkaProducer<String, byte[]> producer = producers.get(zone);
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        if (producer == null || stopping) {
            answer.completeExceptionally(new IllegalStateException("no producer for this zone"));
            return answer;
        }
        waiting.put(id, answer);
        String name = topic == Topic.OPS ? routing.opsTopic(zone) : routing.readsTopic(zone);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(name, null, key, body);
        headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        try {
            var unused = producer.send(record, (meta, error) -> {
                if (error != null) {
                    waiting.remove(id);
                    answer.completeExceptionally(error);
                }
            });
        } catch (RuntimeException e) {
            waiting.remove(id);
            answer.completeExceptionally(e);
        }
        return answer;
    }

    @Override
    public void forget(String id) {
        waiting.remove(id);
    }

    @Override
    public void onEvent(Consumer<Event> listener) {
        listeners.add(listener);
    }

    @Override
    public String failure() {
        for (var e : failures.entrySet()) {
            if (e.getValue() != null) {
                return e.getKey() + ": " + e.getValue();
            }
        }
        return null;
    }

    private void read(String zone, CcToolsProperties.Kafka k, String topic, String name, Consumer<ConsumerRecord<String, byte[]>> handle) {
        Duration backoff = Duration.ofMillis(200);
        while (!stopping) {
            try (KafkaConsumer<String, byte[]> consumer = KafkaClients.assignedConsumer(k,
                    "parity-cc-tools-" + instance + "-" + name.replace(':', '-'))) {
                List<PartitionInfo> parts = consumer.partitionsFor(topic, Duration.ofSeconds(5));
                if (parts == null || parts.isEmpty()) {
                    failures.put(name, "the topic has no partitions (does it exist?)");
                    pause(backoff);
                    backoff = next(backoff);
                    continue;
                }
                List<TopicPartition> assigned = new ArrayList<>();
                for (PartitionInfo p : parts) {
                    assigned.add(new TopicPartition(p.topic(), p.partition()));
                }
                consumer.assign(assigned);
                consumer.seekToEnd(assigned);
                for (TopicPartition tp : assigned) {
                    consumer.position(tp, Duration.ofSeconds(10));
                }
                failures.remove(name);
                backoff = Duration.ofMillis(200);
                log.atInfo().addKeyValue("event", "rs.reader_assigned").addKeyValue("zone", zone)
                        .addKeyValue("topic", topic).addKeyValue("partitions", assigned.size())
                        .log("reading from the latest offset");
                while (!stopping) {
                    for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(200))) {
                        handle.accept(r);
                    }
                }
            } catch (RuntimeException e) {
                if (stopping) {
                    return;
                }
                failures.put(name, "the reader failed (" + e.getClass().getSimpleName() + ")");
                log.atWarn().addKeyValue("event", "rs.reader_failed").addKeyValue("zone", zone).addKeyValue("topic", topic)
                        .addKeyValue("error_kind", e.getClass().getName()).log("a parity-rs reader failed; starting again");
                pause(backoff);
                backoff = next(backoff);
            }
        }
    }

    private static Duration next(Duration d) {
        Duration n = d.multipliedBy(2);
        return n.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : n;
    }

    private JsonNode parse(ConsumerRecord<String, byte[]> r) {
        if (r.value() == null || r.value().length > MAX_RECORD) {
            unreadable(r);
            return null;
        }
        try {
            JsonNode n = JSON.readTree(r.value());
            if (n == null || !n.isObject()) {
                unreadable(r);
                return null;
            }
            return n;
        } catch (RuntimeException e) {
            unreadable(r);
            return null;
        }
    }

    private static void unreadable(ConsumerRecord<String, byte[]> r) {
        log.atWarn().addKeyValue("event", "rs.record_unreadable").addKeyValue("topic", r.topic())
                .addKeyValue("partition", r.partition()).addKeyValue("offset", r.offset()).log("an unreadable record was skipped");
    }

    private void answer(ConsumerRecord<String, byte[]> r) {
        JsonNode reply = parse(r);
        if (reply == null) {
            return;
        }
        CompletableFuture<JsonNode> f = waiting.remove(reply.path("id").asString(""));
        if (f != null) {
            f.complete(reply);
        }
    }

    private void event(String zone, ConsumerRecord<String, byte[]> r) {
        JsonNode body = parse(r);
        if (body == null) {
            return;
        }
        Event e = new Event(zone, r.key(), body);
        for (Consumer<Event> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                log.atWarn().addKeyValue("event", "rs.reader_failed").addKeyValue("zone", zone).addKeyValue("topic", r.topic())
                        .addKeyValue("error_kind", ex.getClass().getName()).log("an event listener failed");
            }
        }
    }

    private void pause(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopping = true;
        }
    }

    @Override
    public void close() {
        stopping = true;
        readers.forEach(Thread::interrupt);
        producers.values().forEach(p -> p.close(Duration.ofSeconds(2)));
    }
}
