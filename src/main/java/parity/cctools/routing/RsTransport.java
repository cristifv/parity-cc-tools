package parity.cctools.routing;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * How requests reach a zone's parity-rs and its answers and events come back (PLAN.md §6.1). The Kafka
 * implementation is {@link KafkaRsTransport}; tests use an in-memory parity-rs stand-in.
 */
public interface RsTransport {

    /** The two request topics of a zone: reads only, or every operation (parity-cc-tools only). */
    enum Topic { READS, OPS }

    /** An event of a zone's {@code tenant.routing.events.<zone>}. */
    record Event(String zone, String key, JsonNode body) {}

    /**
     * Writes one request record. The future completes with the answer whose {@code id} is {@code id}, or
     * exceptionally if the record couldn't be written; it never completes by itself on a timer (the
     * caller decides how long a person waits, then follows the saga by its events).
     */
    CompletableFuture<JsonNode> send(String zone, Topic topic, String key, Map<String, String> headers, String id, byte[] body);

    /** The caller stopped waiting for {@code id}. */
    void forget(String id);

    /** Every event of every configured zone, once per record read (at least once: dedup by event_id). */
    void onEvent(Consumer<Event> listener);

    /** Null when every reader is assigned and every producer is up (a readiness check). */
    String failure();
}
