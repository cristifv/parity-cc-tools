package parity.cctools.routing;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.AnnotationKeyword;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * parity-contracts' tenant-routing schemas (0.16.0, unchanged in 0.17.0), copied into the jar at build
 * time ({@code contracts/}), through networknt 3.0.8 with the contracts' annotation keywords registered.
 * Every request this tool writes is checked against {@code tenant-routing-requests.v1.json} before its
 * audit row and before it is sent (writers validate what they write): a request that fails is a bug,
 * never sent. {@link #allowedOn} reads each command's {@code x-topics}.
 */
public final class RoutingSchemas {

    public static final String REQUESTS = "requests";
    public static final String REPLIES = "replies";
    public static final String EVENTS = "events";

    private static final Map<String, String> FILES = Map.of(
            REQUESTS, "contracts/schemas/tenant-routing/tenant-routing-requests.v1.json",
            REPLIES, "contracts/schemas/tenant-routing/tenant-routing-replies.v1.json",
            EVENTS, "contracts/schemas/tenant-routing/tenant-routing-events.v1.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Schema> schemas = new LinkedHashMap<>();
    private final Map<String, JsonNode> documents = new LinkedHashMap<>();

    public RoutingSchemas() {
        Dialect dialect = Dialect.builder(Dialects.getDraft202012())
                .keyword(new AnnotationKeyword("x-topics"))
                .keyword(new AnnotationKeyword("x-reserved"))
                .keyword(new AnnotationKeyword("x-key"))
                .build();
        SchemaRegistry registry = SchemaRegistry.withDialect(dialect);
        ClassLoader loader = RoutingSchemas.class.getClassLoader();
        for (Map.Entry<String, String> e : FILES.entrySet()) {
            try (InputStream in = loader.getResourceAsStream(e.getValue())) {
                if (in == null) {
                    throw new IllegalStateException("the jar lacks a parity-contracts schema (built without parity-contracts?)");
                }
                JsonNode doc = JSON.readTree(in);
                documents.put(e.getKey(), doc);
                schemas.put(e.getKey(), registry.getSchema(SchemaLocation.of(doc.path("$id").asString()), doc));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /** One schema error: where (a JSON pointer) and which keyword. Never the value. */
    public record Problem(String at, String keyword) {}

    /** The errors of {@code instance} against the schema {@code name} (at most 50). */
    public List<Problem> check(String name, JsonNode instance) {
        Schema schema = schemas.get(name);
        if (schema == null) {
            throw new IllegalArgumentException("no such schema");
        }
        List<Error> errors = schema.validate(instance);
        return errors.stream().limit(50).map(e -> new Problem(pointer(e.getInstanceLocation()), e.getKeyword())).toList();
    }

    /** The topics ({@code requests}, {@code ops}, {@code onboarding}) a command is allowed on. */
    public Set<String> allowedOn(String cmd) {
        Set<String> out = new TreeSet<>();
        for (var e : documents.get(REQUESTS).path("$defs").properties()) {
            JsonNode d = e.getValue();
            if (cmd.equals(d.path("properties").path("cmd").path("const").asString(""))) {
                d.path("x-topics").forEach(t -> out.add(t.asString()));
            }
        }
        return Set.copyOf(out);
    }

    /** Every command the requests schema defines. */
    public Set<String> commands() {
        Set<String> out = new TreeSet<>();
        for (var e : documents.get(REQUESTS).path("$defs").properties()) {
            String cmd = e.getValue().path("properties").path("cmd").path("const").asString("");
            if (!cmd.isEmpty()) {
                out.add(cmd);
            }
        }
        return Set.copyOf(out);
    }

    /** The contracts version the jar was built with. */
    public static String contractsVersion() {
        try (InputStream in = RoutingSchemas.class.getClassLoader().getResourceAsStream("contracts/VERSION")) {
            return in == null ? "unknown" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "unknown";
        }
    }

    static String pointer(NodePath path) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < path.getNameCount(); i++) {
            Object element = path.getElement(i);
            b.append('/').append(String.valueOf(element).replace("~", "~0").replace("/", "~1"));
        }
        return b.toString();
    }
}
