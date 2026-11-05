package parity.cctools.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.lifecycle.ConfigurationRefusedException;

/**
 * A zone's Kafka clients, as admin-console's: authenticated as the zone's {@code kafka.username} with
 * SCRAM-SHA-256 when a password file is set (the password is read from the file, never from
 * configuration), plus the deployment's extra properties.
 */
public final class KafkaClients {

    private KafkaClients() {}

    static Map<String, Object> base(CcToolsProperties.Kafka k, String clientId) {
        Map<String, Object> p = new HashMap<>();
        p.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, k.bootstrapServers());
        p.put(CommonClientConfigs.CLIENT_ID_CONFIG, clientId);
        if (k.passwordFile() != null && !k.passwordFile().isBlank()) {
            String password;
            try {
                password = Files.readString(Path.of(k.passwordFile()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new ConfigurationRefusedException("parity-cc-tools.zones.<zone>.kafka.password-file can't be read");
            }
            if (k.username() == null || k.username().isBlank() || password.isEmpty()
                    || password.contains("\"") || k.username().contains("\"")) {
                throw new ConfigurationRefusedException("parity-cc-tools.zones.<zone>.kafka.username or its password is not usable");
            }
            p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                    k.securityProtocol() == null || k.securityProtocol().isBlank() ? "SASL_SSL" : k.securityProtocol());
            p.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256");
            p.put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.scram.ScramLoginModule required username=\""
                    + k.username() + "\" password=\"" + password + "\";");
        }
        if (k.client() != null) {
            p.putAll(k.client());
        }
        return p;
    }

    /** No group: read by manual assignment, from the latest offset, committed records only. */
    static KafkaConsumer<String, byte[]> assignedConsumer(CcToolsProperties.Kafka k, String clientId) {
        Map<String, Object> p = base(k, clientId);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
    }

    /** Idempotent, acks=all; a send never blocks longer than {@code maxBlockMs} on metadata. */
    static KafkaProducer<String, byte[]> producer(CcToolsProperties.Kafka k, String clientId, long maxBlockMs) {
        Map<String, Object> p = base(k, clientId);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) Math.max(maxBlockMs * 2, 4_000));
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.max(maxBlockMs, 2_000));
        p.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
    }

    /** Local-dev and tests ({@code create-topics}): the zone's topics. Deployments create them (parity-deploy). */
    static void createTopics(CcToolsProperties.Kafka k, List<NewTopic> topics) {
        try (Admin admin = Admin.create(base(k, "parity-cc-tools-admin"))) {
            for (NewTopic t : topics) {
                try {
                    admin.createTopics(List.of(t)).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException("can't create the topics", e.getCause());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while creating the topics", e);
        }
    }
}
