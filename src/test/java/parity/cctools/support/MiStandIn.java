package parity.cctools.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** One opensips SBC node's mi_http stand-in: ds_list and ds_set_state (JSON-RPC 2.0) over a dispatcher table. */
public final class MiStandIn implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    /** {@code <set>|<address>} to "Active"/"Inactive". */
    public final Map<String, String> table = new ConcurrentHashMap<>();
    public final List<String> calls = new CopyOnWriteArrayList<>();

    public MiStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mi", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mi";
    }

    private void handle(HttpExchange x) throws IOException {
        JsonNode req = JSON.readTree(x.getRequestBody().readAllBytes());
        String method = req.path("method").asString();
        calls.add(method + " " + req.path("params"));
        ObjectNode reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", req.path("id"));
        if ("ds_list".equals(method)) {
            ObjectNode result = reply.putObject("result");
            ArrayNode sets = result.putArray("PARTITIONS").addObject().put("name", "default").putArray("SETS");
            Map<Integer, ArrayNode> bySet = new java.util.TreeMap<>();
            table.forEach((k, v) -> {
                String[] p = k.split("\\|", 2);
                int set = Integer.parseInt(p[0]);
                bySet.computeIfAbsent(set, s -> {
                    ObjectNode o = sets.addObject();
                    o.put("id", s);
                    return o.putArray("Destinations");
                }).addObject().put("URI", p[1]).put("state", v).put("first_hit_counter", 0);
            });
        } else if ("ds_set_state".equals(method)) {
            JsonNode p = req.path("params");
            String key = p.path("group").asInt() + "|" + p.path("address").asString();
            if (!table.containsKey(key)) {
                reply.putObject("error").put("code", 404).put("message", "destination not found");
            } else {
                table.put(key, "i".equals(p.path("state").asString()) ? "Inactive" : "Active");
                reply.put("result", "OK");
            }
        } else {
            reply.putObject("error").put("code", -32601).put("message", "method not found");
        }
        byte[] b = reply.toString().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
