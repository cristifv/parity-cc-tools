package parity.cctools.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import parity.cctools.config.CcToolsProperties;

/**
 * At start (PLAN.md §5): actions answered (or not confirmed) whose saga has no outcome row yet are read
 * again with {@code get_saga}; a finished one gets its outcome now (the projection's finished listener),
 * an active one is followed from then on. A saga that ended while every pod was down is not lost.
 */
@Component
public class OutcomeReconciler {

    private static final Logger log = LoggerFactory.getLogger(OutcomeReconciler.class);

    private final JdbcClient db;
    private final RsClient client;
    private final SagaProjection projection;
    private final int days;

    public OutcomeReconciler(JdbcClient db, RsClient client, SagaProjection projection, CcToolsProperties properties) {
        this.db = db;
        this.client = client;
        this.projection = projection;
        this.days = properties.audit().reconcileDays();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        Thread.ofVirtual().name("outcome-reconcile").start(this::reconcile);
    }

    /** Returns how many sagas were looked up. */
    public int reconcile() {
        record Open(String sagaId, String zone) {}
        java.util.List<Open> open;
        try {
            open = db.sql("""
                    SELECT DISTINCT a.saga_id::text AS saga_id, a.zone FROM audit_log a
                    WHERE a.phase = 'answered' AND a.outcome IN ('ok', 'not_confirmed') AND a.saga_id IS NOT NULL
                      AND a.service IS NULL AND a.zone IS NOT NULL AND a.at > now() - make_interval(days => ?)
                      AND NOT EXISTS (SELECT 1 FROM operation_outcome o WHERE o.saga_id = a.saga_id)
                    LIMIT 500""").param(days).query((rs, n) -> new Open(rs.getString(1), rs.getString(2))).list();
        } catch (RuntimeException e) {
            return 0;
        }
        int written = 0;
        for (Open o : open) {
            if (!client.zones().contains(o.zone())) {
                continue;
            }
            try {
                var r = client.read(o.zone(), RsRequests.getSaga(o.sagaId()));
                var v = projection.seed(o.zone(), r.path("saga"));
                if (v != null && v.finished()) {
                    written++;
                }
            } catch (RuntimeException e) {
                // Not known or not reachable now: the events (or the next start) settle it.
            }
        }
        log.atInfo().addKeyValue("event", "audit.reconciled").addKeyValue("checked", open.size()).addKeyValue("written", written)
                .log("answered actions without an outcome were looked up");
        return open.size();
    }
}
