package parity.cctools.lifecycle;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code /readyz}: the readiness checks pass (Postgres, the Kafka readers, the realm's metadata) and no drain has started. */
@Component("cctools")
public class CcToolsHealth implements HealthIndicator {

    private final LifecycleFiles files;

    public CcToolsHealth(LifecycleFiles files) {
        this.files = files;
    }

    @Override
    public Health health() {
        return files.ready() ? Health.up().build() : Health.down().withDetails(files.failures()).build();
    }
}
