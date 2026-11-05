package parity.cctools.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import parity.cctools.catalogue.Catalogue;

/**
 * {@code parity-cc-tools.*} (application.yml; secrets as files through configtree, PLAN.md §8). Every
 * port is configurable ({@code server.port} 8095, {@code management.server.port} 8096).
 */
@ConfigurationProperties("parity-cc-tools")
public record CcToolsProperties(
        Environment environment,
        String instance,
        String version,
        Lifecycle lifecycle,
        Readiness readiness,
        Db db,
        Identity identity,
        Session session,
        Routing routing,
        Map<String, Zone> zones,
        Health health,
        Control control,
        Audit audit) {

    public CcToolsProperties {
        zones = zones == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(zones));
        environment = environment == null ? Environment.PROD : environment;
    }

    /** The zone names, sorted. */
    public List<String> zoneNames() {
        return List.copyOf(zones.keySet());
    }

    /**
     * One zone (= cell, coordinator ruling 2026-11-03): its own endpoints and credentials, configured per
     * zone, as {@code parity-cc-tools.zones.<zone>}: the zone's Kafka (its parity-rs), its Kubernetes API,
     * its opensips management interface and its service catalogue. v1 has one zone, {@code eu1a}.
     */
    public record Zone(
            Kafka kafka,
            Kubernetes kubernetes,
            Opensips opensips,
            List<Catalogue.ServiceDef> services,
            List<Catalogue.MediaNodeDef> mediaNodes) {

        public Zone {
            services = services == null ? List.of() : List.copyOf(services);
            mediaNodes = mediaNodes == null ? List.of() : List.copyOf(mediaNodes);
        }
    }

    /** {@code dev} and {@code test} allow conveniences (creating topics, plain HTTP); {@code prod} refuses them. */
    public enum Environment { DEV, TEST, PROD }

    public record Lifecycle(String healthyFile, String drainedFile, Duration deregistrationDelay, Duration drainTimeout) {}

    public record Readiness(Duration pollInterval) {}

    /** The app role's password from a file; Flyway as the owner only when {@code flyway-user} is set. */
    public record Db(String passwordFile, String flywayUser, String flywayPasswordFile, String appRole) {}

    /**
     * The ops realm (PLAN.md §2). {@code issuer} is compared exactly with every token's {@code iss}. The
     * endpoints are discovered from the issuer unless {@code authorization-uri}, {@code token-uri} and
     * {@code jwk-set-uri} are all set (tests).
     */
    public record Identity(
            String registrationId,
            String issuer,
            String clientId,
            String clientSecret,
            List<String> scopes,
            String requiredRole,
            String requiredAmr,
            String rolesClaim,
            Duration stepUpMaxAge,
            Duration sessionCheckInterval,
            URI authorizationUri,
            URI tokenUri,
            URI jwkSetUri,
            URI endSessionUri,
            String postLogoutPath) {

        public boolean explicitEndpoints() {
            return authorizationUri != null && tokenUri != null && jwkSetUri != null;
        }
    }

    /** The app's own session: absolute limit (the idle limit is spring.session.timeout); attribute encryption key. */
    public record Session(Duration absoluteTimeout, String keyFile) {}

    /** parity-rs (its PLAN §3): per zone a reads topic, an ops topic and an events topic; one replies topic per zone's cluster. */
    public record Routing(
            String repliesTopic,
            String readsTopicPrefix,
            String opsTopicPrefix,
            String eventsTopicPrefix,
            Duration answerTimeout,
            int pageSize) {

        public String readsTopic(String zone) {
            return readsTopicPrefix + zone;
        }

        public String opsTopic(String zone) {
            return opsTopicPrefix + zone;
        }

        public String eventsTopic(String zone) {
            return eventsTopicPrefix + zone;
        }
    }

    /** SCRAM-SHA-256 when a password file is set; {@code create-topics} in dev and tests only. */
    public record Kafka(
            String bootstrapServers,
            String username,
            String passwordFile,
            String securityProtocol,
            boolean createTopics,
            Duration sendTimeout,
            Map<String, String> client) {}

    /** A zone's Kubernetes API (the pod's service account in its own zone; a plain URL in tests). */
    public record Kubernetes(
            boolean enabled,
            URI apiUrl,
            String tokenFile,
            String caFile,
            Duration requestTimeout,
            Duration watchTimeout) {}

    /**
     * The management interface (mi_http, JSON-RPC, internal only) of every opensips SBC node of the zone
     * (PLAN.md §6.4): the media-node driver sets and reads the dispatcher state on each of them. This tool
     * is the only writer of dispatcher state; opensips runs {@code persistent_state 0}, so a restarted SBC
     * node comes back with every entry active: {@code pod-namespace} and {@code pod-selector} name the
     * opensips pods, whose (re)start or readiness change wakes the held media-node sagas to set it again.
     */
    public record Opensips(List<URI> miUrls, Duration timeout, String partition, String podNamespace, String podSelector) {
        public Opensips {
            miUrls = miUrls == null ? List.of() : List.copyOf(miUrls);
        }
    }

    /** The collector: a display refresh, never a decision (PLAN.md §6.3). */
    public record Health(boolean enabled, Duration interval, Duration probeTimeout, int parallelism, int metricsMaxBytes) {}

    /** Service control: how often observations are refreshed (a timer refreshes, a fact decides). */
    public record Control(boolean enabled, Duration refreshInterval) {}

    public record Audit(int partitionsAhead, Duration partitionInterval, int reconcileDays) {}
}
