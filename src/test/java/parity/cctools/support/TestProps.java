package parity.cctools.support;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import parity.cctools.config.CcToolsProperties;

/** CcToolsProperties for tests built without Spring (unit tests, the Kafka IT). */
public final class TestProps {

    public static final String ISSUER = "https://id.ops.parity.internal/realms/ops";

    private TestProps() {}

    public static CcToolsProperties.Identity identity() {
        return new CcToolsProperties.Identity("ops", ISSUER, "parity-cc-tools", "test-secret", List.of("openid"), "ops", "otp", "roles",
                Duration.ofSeconds(300), Duration.ofSeconds(60), URI.create("https://id.ops.parity.internal/realms/ops/protocol/openid-connect/auth"),
                URI.create("https://id.ops.parity.internal/realms/ops/protocol/openid-connect/token"),
                URI.create("https://id.ops.parity.internal/realms/ops/protocol/openid-connect/certs"), null, "/logged-out");
    }

    public static CcToolsProperties.Kafka kafka(String bootstrap) {
        return new CcToolsProperties.Kafka(bootstrap, null, null, null, true, Duration.ofSeconds(3), Map.of());
    }

    public static CcToolsProperties.Zone zone(String bootstrap) {
        return new CcToolsProperties.Zone(kafka(bootstrap),
                new CcToolsProperties.Kubernetes(false, URI.create("http://127.0.0.1:1"), null, null, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new CcToolsProperties.Opensips(List.of(), Duration.ofSeconds(1), "default", null, null), List.of(), List.of());
    }

    public static CcToolsProperties of(Map<String, CcToolsProperties.Zone> zones) {
        return new CcToolsProperties(CcToolsProperties.Environment.TEST, "cct-test", "test",
                new CcToolsProperties.Lifecycle("/tmp/x.healthy", "/tmp/x.drained", Duration.ZERO, Duration.ofSeconds(5)),
                new CcToolsProperties.Readiness(Duration.ofMillis(200)),
                new CcToolsProperties.Db(null, null, null, "parity_cc_tools_app"), identity(),
                new CcToolsProperties.Session(Duration.ofHours(8), null),
                new CcToolsProperties.Routing("replies.parity-cc-tools", "tenant.routing.requests.", "tenant.routing.ops.requests.",
                        "tenant.routing.events.", Duration.ofSeconds(5), 50),
                zones, new CcToolsProperties.Health(false, Duration.ofSeconds(10), Duration.ofSeconds(1), 16, 4 << 20),
                new CcToolsProperties.Control(false, Duration.ofSeconds(5)), new CcToolsProperties.Audit(3, Duration.ofHours(6), 30));
    }

    public static CcToolsProperties oneZone() {
        Map<String, CcToolsProperties.Zone> z = new LinkedHashMap<>();
        z.put("eu1a", zone("127.0.0.1:1"));
        return of(z);
    }
}
