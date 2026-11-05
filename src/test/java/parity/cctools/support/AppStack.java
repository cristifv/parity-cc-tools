package parity.cctools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import parity.cctools.ParityCcToolsApplication;

/**
 * The application in-process against the test containers and stand-ins, started as a deployment would
 * (configuration as arguments, secrets as files the test generates), restartable against the same
 * database. parity-rs is {@link FakeParityRs} ({@code routing.transport: test}).
 */
public final class AppStack {

    private static Path secrets;

    private AppStack() {}

    /** The test's own secrets: generated, 600, in a 700 directory under target/ (never secrets/local-dev). */
    public static synchronized Path secrets() throws IOException {
        if (secrets == null) {
            Path dir = Path.of("target", "it-secrets-" + ProcessHandle.current().pid());
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            write(dir.resolve("session-key"), Base64.getEncoder().encodeToString(key));
            write(dir.resolve("parity-cc-tools.identity.client-secret"), "it-client-secret-" + Long.toHexString(new SecureRandom().nextLong()));
            write(dir.resolve("owner-password"), Containers.postgres().getPassword());
            write(dir.resolve("app-password"), Containers.APP_PASSWORD);
            secrets = dir.toAbsolutePath();
        }
        return secrets;
    }

    private static void write(Path f, String v) throws IOException {
        Files.writeString(f, v);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
    }

    /** The arguments every test app gets; tests add their catalogue and stand-in URLs. */
    public static List<String> baseArgs(String name) throws IOException {
        var pg = Containers.postgres();
        Path s = secrets();
        List<String> a = new ArrayList<>();
        a.add("--PARITY_CC_TOOLS_SECRETS_DIR=" + s + "/");
        a.add("--server.port=0");
        a.add("--management.server.port=0");
        a.add("--spring.datasource.url=" + pg.getJdbcUrl());
        a.add("--spring.datasource.username=" + Containers.APP_ROLE);
        a.add("--parity-cc-tools.db.password-file=" + s.resolve("app-password"));
        a.add("--parity-cc-tools.db.flyway-user=" + pg.getUsername());
        a.add("--parity-cc-tools.db.flyway-password-file=" + s.resolve("owner-password"));
        a.add("--parity-cc-tools.environment=test");
        a.add("--parity-cc-tools.instance=" + name);
        a.add("--parity-cc-tools.routing.transport=test");
        a.add("--parity-cc-tools.session.key-file=" + s.resolve("session-key"));
        a.add("--parity-cc-tools.identity.issuer=" + TestProps.ISSUER);
        a.add("--parity-cc-tools.identity.authorization-uri=" + TestProps.ISSUER + "/protocol/openid-connect/auth");
        a.add("--parity-cc-tools.identity.token-uri=http://127.0.0.1:1/token");
        a.add("--parity-cc-tools.identity.jwk-set-uri=http://127.0.0.1:1/certs");
        a.add("--parity-cc-tools.identity.end-session-uri=" + TestProps.ISSUER + "/protocol/openid-connect/logout");
        a.add("--parity-cc-tools.lifecycle.healthy-file=target/" + name + ".healthy");
        a.add("--parity-cc-tools.lifecycle.drained-file=target/" + name + ".drained");
        a.add("--parity-cc-tools.lifecycle.deregistration-delay=0s");
        a.add("--parity-cc-tools.zones.eu1a.kafka.bootstrap-servers=127.0.0.1:1");
        a.add("--parity-cc-tools.control.refresh-interval=300ms");
        a.add("--parity-cc-tools.health.interval=1s");
        a.add("--parity-cc-tools.audit.partition-interval=1h");
        return a;
    }

    public static ConfigurableApplicationContext start(FakeParityRs rs, List<String> args) {
        return new SpringApplicationBuilder(ParityCcToolsApplication.class)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("fakeRsTransport", rs))
                .run(args.toArray(String[]::new));
    }
}
