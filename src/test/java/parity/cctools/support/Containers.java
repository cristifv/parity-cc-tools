package parity.cctools.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The test containers, on random host ports, labelled {@code parity.test=parity-cc-tools} so scripts/ci.sh
 * removes any a killed run leaves behind. One of each per test JVM. Postgres: the container user owns the
 * database (Flyway runs as it); the app connects as {@code parity_cc_tools_app}, which only gets the
 * migrations' grants.
 */
public final class Containers {

    public static final String LABEL = "parity.test";
    public static final String LABEL_VALUE = "parity-cc-tools";
    public static final String APP_ROLE = "parity_cc_tools_app";
    public static final String APP_PASSWORD = "app-" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    // postgres:16-alpine (2026-09-17, as parity-store and parity-frontend), by digest.
    static final DockerImageName POSTGRES = DockerImageName.parse(
            "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea").asCompatibleSubstituteFor("postgres");
    // RedpandaContainer checks the tag's version, so by tag; scripts/ci.sh checks the digest.
    static final DockerImageName REDPANDA = DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v26.2.3");

    private static PostgreSQLContainer postgres;
    private static RedpandaContainer redpanda;

    private Containers() {}

    public static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(POSTGRES).withDatabaseName("parity_cc_tools").withUsername("parity_cc_tools_owner")
                    .withLabel(LABEL, LABEL_VALUE);
            postgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
            try (Connection c = owner(); Statement s = c.createStatement()) {
                s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return postgres;
    }

    public static Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static synchronized RedpandaContainer redpanda() {
        if (redpanda == null) {
            redpanda = new RedpandaContainer(REDPANDA).withLabel(LABEL, LABEL_VALUE);
            redpanda.start();
            Runtime.getRuntime().addShutdownHook(new Thread(redpanda::stop));
        }
        return redpanda;
    }
}
