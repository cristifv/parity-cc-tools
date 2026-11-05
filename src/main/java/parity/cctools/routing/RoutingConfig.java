package parity.cctools.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import parity.cctools.audit.AuditLog;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.lifecycle.ConfigurationRefusedException;
import parity.cctools.lifecycle.ReadinessChecks;
import parity.cctools.obs.ToolMetrics;

/**
 * The parity-rs side: the schemas, the transport (Kafka; {@code parity-cc-tools.routing.transport: test}
 * lets a test supply its own stand-in, refused outside the test environment), the client, the saga
 * projection fed by every zone's events, and the outcome rows written when a saga this tool started ends.
 */
@Configuration
public class RoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

    @Bean
    RoutingSchemas routingSchemas() {
        return new RoutingSchemas();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "parity-cc-tools.routing.transport", havingValue = "kafka", matchIfMissing = true)
    KafkaRsTransport kafkaRsTransport(CcToolsProperties properties) {
        return new KafkaRsTransport(properties);
    }

    @Bean
    RsClient rsClient(RsTransport transport, RoutingSchemas schemas, CcToolsProperties properties, ToolMetrics metrics,
            org.springframework.core.env.Environment env) {
        String mode = env.getProperty("parity-cc-tools.routing.transport", "kafka");
        if (!"kafka".equals(mode) && properties.environment() != CcToolsProperties.Environment.TEST) {
            throw new ConfigurationRefusedException("parity-cc-tools.routing.transport other than kafka is for tests only");
        }
        return new RsClient(transport, schemas, properties, metrics);
    }

    @Bean
    SagaProjection sagaProjection(RsTransport transport, RsClient client, AuditLog audit) {
        SagaProjection p = new SagaProjection();
        transport.onEvent(p::apply);
        p.setResync((zone, sagaId) -> Thread.ofVirtual().name("saga-resync").start(() -> {
            try {
                var r = client.read(zone, RsRequests.getSaga(sagaId));
                if (r.has("saga")) {
                    p.seed(zone, r.path("saga"));
                }
            } catch (RuntimeException e) {
                // The page keeps the events it has; the next gap or reconnect reads again.
            }
        }));
        p.onFinished(v -> {
            if (!"parity-rs".equals(v.source()) || v.opId() == null) {
                return;
            }
            if (audit.outcome(v.sagaId(), v.opId(), "routing", v.zone(), v.outcome(), v.detail())) {
                log.atInfo().addKeyValue("event", "action.outcome").addKeyValue("op_id", v.opId())
                        .addKeyValue("saga_id", v.sagaId()).addKeyValue("zone", v.zone()).addKeyValue("tenant_id", v.tenantId())
                        .addKeyValue("saga_outcome", v.outcome()).log("an action's saga ended");
            }
        });
        return p;
    }

    /** The answer and event readers are assigned in every zone (a readiness check). */
    @Bean
    ReadinessChecks.Check parityRsReaders(RsTransport transport) {
        return new ReadinessChecks.Check("parity-rs-readers", transport::failure);
    }
}
