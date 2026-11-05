package parity.cctools.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import parity.cctools.catalogue.Catalogue;
import parity.cctools.catalogue.Catalogues;
import parity.cctools.lifecycle.ConfigurationRefusedException;
import parity.cctools.lifecycle.ReadinessChecks;

/**
 * The shared beans: the clock, the catalogue, the database passwords from files (the app role's for the
 * pool, the owner's for Flyway when the migration runs in the app, as admin-console), and the start-up
 * refusals (exit 2).
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The catalogues, one per zone, checked at start: a bad entry refuses the start (exit 2). */
    @Bean
    Catalogues catalogues(CcToolsProperties properties) {
        refuseUnsafe(properties);
        java.util.Map<String, Catalogue> byZone = new java.util.TreeMap<>();
        properties.zones().forEach((zone, z) -> {
            Catalogue c = new Catalogue(z.services(), z.mediaNodes());
            byZone.put(zone, c);
            log.atInfo().addKeyValue("event", "config.loaded").addKeyValue("file", "parity-cc-tools.zones." + zone)
                    .addKeyValue("zone", zone).addKeyValue("services", c.services().size())
                    .addKeyValue("media_nodes", c.mediaNodes().size()).log("service catalogue loaded");
        });
        return new Catalogues(byZone);
    }

    /** Settings for dev and tests only are refused in prod. */
    static void refuseUnsafe(CcToolsProperties p) {
        boolean prod = p.environment() == CcToolsProperties.Environment.PROD;
        if (p.zones().isEmpty()) {
            throw new ConfigurationRefusedException("parity-cc-tools.zones names no zone");
        }
        java.util.regex.Pattern zoneName = java.util.regex.Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$");
        for (var e : p.zones().entrySet()) {
            CcToolsProperties.Zone z = e.getValue();
            if (!zoneName.matcher(e.getKey()).matches()) {
                throw new ConfigurationRefusedException("a zone name in parity-cc-tools.zones isn't a zone name");
            }
            if (z.kafka() == null || z.kafka().bootstrapServers() == null || z.kafka().bootstrapServers().isBlank()) {
                throw new ConfigurationRefusedException("a zone has no parity-cc-tools.zones.<zone>.kafka.bootstrap-servers");
            }
            if (prod && z.kafka().createTopics()) {
                throw new ConfigurationRefusedException("parity-cc-tools.zones.<zone>.kafka.create-topics is for dev and test only");
            }
            if (z.kubernetes() == null || z.opensips() == null) {
                throw new ConfigurationRefusedException("a zone lacks its kubernetes or opensips block");
            }
            if (prod && z.kubernetes().enabled() && !"https".equals(z.kubernetes().apiUrl().getScheme())) {
                throw new ConfigurationRefusedException("parity-cc-tools.zones.<zone>.kubernetes.api-url must be https outside dev and test");
            }
        }
        if (prod && !p.identity().issuer().startsWith("https://")) {
            throw new ConfigurationRefusedException("parity-cc-tools.identity.issuer must be https outside dev and test");
        }
        if (p.identity().clientSecret() == null || p.identity().clientSecret().isBlank()) {
            throw new ConfigurationRefusedException("parity-cc-tools.identity.client-secret is missing (a file in the secrets directory)");
        }
        if (p.identity().stepUpMaxAge().toSeconds() < 30 || p.identity().stepUpMaxAge().toSeconds() > 900) {
            throw new ConfigurationRefusedException("parity-cc-tools.identity.step-up-max-age must be 30 s to 15 min");
        }
    }

    /** Postgres answers: a readiness check. */
    @Bean
    ReadinessChecks.Check postgresCheck(org.springframework.jdbc.core.simple.JdbcClient db) {
        return new ReadinessChecks.Check("postgres", () -> {
            try {
                db.sql("SELECT 1").query(Integer.class).single();
                return null;
            } catch (RuntimeException e) {
                return "postgres unreachable (" + e.getClass().getSimpleName() + ")";
            }
        });
    }

    /** {@code parity-cc-tools.db.password-file}: the app role's password from a file, never logged. */
    @Bean
    static BeanPostProcessor databasePasswordFile(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                String file = environment.getProperty("parity-cc-tools.db.password-file");
                if (bean instanceof HikariDataSource ds && !"flyway".equals(ds.getPoolName()) && file != null && !file.isBlank()) {
                    ds.setPassword(read(file));
                }
                return bean;
            }
        };
    }

    /**
     * Flyway as the owner role, when {@code parity-cc-tools.db.flyway-user} is set (local-dev, tests): its
     * own small pool. Deployments migrate apart (the owner role) and leave it unset.
     */
    @Bean(defaultCandidate = false)
    @FlywayDataSource
    @ConditionalOnProperty("parity-cc-tools.db.flyway-user")
    DataSource flywayDataSource(Environment environment) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("flyway");
        ds.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        ds.setUsername(environment.getRequiredProperty("parity-cc-tools.db.flyway-user"));
        String file = environment.getProperty("parity-cc-tools.db.flyway-password-file");
        if (file != null && !file.isBlank()) {
            ds.setPassword(read(file));
        }
        ds.setMaximumPoolSize(2);
        return ds;
    }

    /** Flyway runs only as the owner: never DDL as the app role. */
    @Bean
    FlywayMigrationStrategy migrateOnlyAsOwner(Environment environment) {
        return flyway -> {
            String owner = environment.getProperty("parity-cc-tools.db.flyway-user");
            if (owner != null && !owner.isBlank()) {
                flyway.migrate();
            } else {
                log.atInfo().addKeyValue("event", "config.loaded").addKeyValue("file", "spring.flyway")
                        .log("migrations not run here: parity-cc-tools.db.flyway-user isn't set");
            }
        };
    }

    /** A secret from a file (a password, a key), stripped; never logged. */
    public static String readSecret(String file) {
        return read(file);
    }

    static String read(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new ConfigurationRefusedException("a password or key file can't be read");
        }
    }
}
