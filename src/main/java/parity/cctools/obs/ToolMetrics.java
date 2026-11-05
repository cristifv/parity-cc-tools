package parity.cctools.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The tool's metrics (PLAN.md §9), on the management port's {@code /metrics}. Label values come from
 * fixed vocabularies (action names, outcomes, backends, drivers, catalogue service names), never ids.
 */
@Component
public class ToolMetrics {

    private final MeterRegistry registry;

    public ToolMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("parity_cc_tools_log_lines_dropped", CountingAsyncAppender.class, c -> CountingAsyncAppender.dropped());
    }

    /** {@code parity_cc_tools_actions_total{action,outcome}}. */
    public void action(String action, String outcome) {
        Counter.builder("parity_cc_tools_actions").tag("action", action).tag("outcome", outcome).register(registry).increment();
    }

    /** {@code parity_cc_tools_backend_seconds{backend,op}}. */
    public void backend(String backend, String op, long startedNanos) {
        Timer.builder("parity_cc_tools_backend").tag("backend", backend).tag("op", op).register(registry)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    /** {@code parity_cc_tools_not_confirmed_total}. */
    public void notConfirmed() {
        registry.counter("parity_cc_tools_not_confirmed").increment();
    }

    /** {@code parity_cc_tools_audit_write_failures_total{phase}}. */
    public void auditWriteFailure(String phase) {
        Counter.builder("parity_cc_tools_audit_write_failures").tag("phase", phase).register(registry).increment();
    }

    /** {@code parity_cc_tools_health_probe_failures_total{service}}. */
    public void probeFailure(String service) {
        Counter.builder("parity_cc_tools_health_probe_failures").tag("service", service).register(registry).increment();
    }

    /** {@code parity_cc_tools_logins_total{outcome}}. */
    public void login(String outcome) {
        Counter.builder("parity_cc_tools_logins").tag("outcome", outcome).register(registry).increment();
    }

    /** {@code parity_cc_tools_k8s_call_failures_total{op}}. */
    public void k8sFailure(String op) {
        Counter.builder("parity_cc_tools_k8s_call_failures").tag("op", op).register(registry).increment();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
