package parity.cctools.mi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parity.cctools.config.CcToolsProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One opensips SBC node's management interface over HTTP (module {@code mi_http}, JSON-RPC 2.0), internal
 * only (PLAN.md §6.4, Q10): {@code ds_list} reads the dispatcher's destinations and their states,
 * {@code ds_set_state} sets one ({@code a} active, {@code i} inactive: absolute values). This tool controls
 * the dispatcher itself, on every SBC node of the zone (user decision 2026-11-03).
 */
public class OpensipsMi {

    private static final Logger log = LoggerFactory.getLogger(OpensipsMi.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong IDS = new AtomicLong();

    /** A destination's state as {@code ds_list} prints it, lowercased: active, inactive, probing, disabled. */
    public record Destination(int set, String address, String state) {}

    /** The MI call failed or opensips refused it. */
    public static class MiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MiException(Throwable cause) {
            super("an opensips management call failed", cause);
        }
    }

    private final String zone;
    private final URI url;
    private final Duration timeout;
    private final String partition;
    private final HttpClient http;

    public OpensipsMi(String zone, URI url, CcToolsProperties.Opensips o) {
        this.zone = zone;
        this.url = url;
        this.timeout = o.timeout();
        this.partition = o.partition();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** This SBC node's name on the screens and in the step rows: its management host and port. */
    public String name() {
        return url.getHost() + ":" + (url.getPort() < 0 ? 80 : url.getPort());
    }

    /** Every destination of every set, keyed {@code <set>|<address>}. */
    public Map<String, Destination> dsList() {
        JsonNode result = call("ds_list", JSON.createObjectNode());
        Map<String, Destination> out = new LinkedHashMap<>();
        for (JsonNode p : field(result, "PARTITIONS")) {
            String name = field(p, "name").asString("default");
            if (partition != null && !partition.isBlank() && !partition.equals(name)) {
                continue;
            }
            for (JsonNode s : field(p, "SETS")) {
                int set = field(s, "id").asInt(-1);
                for (JsonNode d : field(s, "Destinations")) {
                    String address = field(d, "URI").asString("");
                    String state = field(d, "state").asString("unknown").toLowerCase(Locale.ROOT);
                    out.put(key(set, address), new Destination(set, address, state));
                }
            }
        }
        return out;
    }

    public static String key(int set, String address) {
        return set + "|" + address;
    }

    /** Sets one destination's state: {@code a} (active) or {@code i} (inactive). */
    public void dsSetState(String state, int set, String address) {
        ObjectNode params = JSON.createObjectNode();
        params.put("state", "i".equals(state) ? "i" : "a");
        params.put("group", set);
        params.put("address", address);
        if (partition != null && !partition.isBlank() && !"default".equals(partition)) {
            params.put("partition", partition);
        }
        call("ds_set_state", params);
    }

    private JsonNode call(String method, ObjectNode params) {
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        if (!params.isEmpty()) {
            body.set("params", params);
        }
        body.put("id", IDS.incrementAndGet());
        HttpRequest req = HttpRequest.newBuilder(url).timeout(timeout).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw failed(method, new IOException("http status " + r.statusCode()));
            }
            JsonNode reply = JSON.readTree(r.body());
            if (reply.has("error")) {
                throw failed(method, new IOException("the MI call returned an error"));
            }
            return reply.path("result");
        } catch (IOException | RuntimeException e) {
            if (e instanceof MiException m) {
                throw m;
            }
            throw failed(method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiException(e);
        }
    }

    private MiException failed(String method, Exception e) {
        log.atWarn().addKeyValue("event", "mi.call_failed").addKeyValue("op", method).addKeyValue("zone", zone)
                .addKeyValue("sbc", name())
                .addKeyValue("error_kind", e.getClass().getName()).log("an opensips management call failed");
        return new MiException(e);
    }

    /** A field by name, any case (opensips versions differ: PARTITIONS / Partitions). */
    static JsonNode field(JsonNode n, String name) {
        if (n == null || !n.isObject()) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
        if (n.has(name)) {
            return n.path(name);
        }
        for (var e : n.properties()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return tools.jackson.databind.node.MissingNode.getInstance();
    }
}
