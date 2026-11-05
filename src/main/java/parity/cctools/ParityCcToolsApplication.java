package parity.cctools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import parity.cctools.lifecycle.ExitCodes;
import parity.cctools.lifecycle.ShutdownSignals;

/**
 * parity-cc-tools: the internal ops UI (PLAN.md). Exit codes: 0 drained, 1 failed (or not drained in
 * time), 2 configuration, 128 + n on a second signal.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ParityCcToolsApplication {

    public static void main(String[] args) {
        // Before the start: a signal during it must not leave a healthy file or the JVM's 143.
        ShutdownSignals.install();
        ConfigurableApplicationContext context;
        try {
            context = new SpringApplication(ParityCcToolsApplication.class).run(args);
        } catch (Throwable failure) {
            System.exit(ExitCodes.forStartupFailure(failure));
            return;
        }
        ShutdownSignals.started(context);
    }
}
