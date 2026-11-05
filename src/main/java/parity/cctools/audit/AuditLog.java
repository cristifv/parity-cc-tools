package parity.cctools.audit;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import parity.cctools.obs.Ids;
import parity.cctools.obs.ToolMetrics;
import parity.cctools.security.Operator;
import tools.jackson.databind.JsonNode;

/**
 * The audit log's writes (PLAN.md §5): per action a {@code requested} row before anything is sent and an
 * {@code answered} row after; the saga's outcome in {@code operation_outcome}.
 * <ul>
 *   <li>{@link #requested}: its own committed insert; if it fails, {@link AuditUnavailableException} and
 *       the caller sends nothing (fail closed);</li>
 *   <li>{@link #answered}: never throws (the action ran); a failure is counted and logged
 *       ({@code audit.answer_write_failed}, an alert): the requested row then shows as not confirmed;</li>
 *   <li>{@link #outcome}: insert-only, keyed by {@code saga_id}, {@code ON CONFLICT DO NOTHING}, so any
 *       pod may write it and only the first does; true for that one.</li>
 * </ul>
 * The app role may only insert and read these tables. Who acted always comes from the session's login.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    /** How an action was answered. */
    public enum Outcome { OK, ERROR, NOT_CONFIRMED }

    /** The first row of an action: everything the action is, without secrets. */
    public record Requested(
            String opId,
            String requestId,
            String sagaId,
            Operator actor,
            String clientIp,
            String action,
            String zone,
            String tenantId,
            String unit,
            String service,
            String instance,
            String release,
            String reason,
            String ticket,
            String confirmKind,
            JsonNode before,
            JsonNode after,
            JsonNode request) {}

    private final JdbcClient db;
    private final Clock clock;
    private final ToolMetrics metrics;

    public AuditLog(JdbcClient db, Clock clock, ToolMetrics metrics) {
        this.db = db;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Writes the {@code requested} row, or throws {@link AuditUnavailableException}: then send nothing. */
    public void requested(Requested r) {
        try {
            insert(r, "requested", null, null, null);
        } catch (RuntimeException e) {
            metrics.auditWriteFailure("requested");
            log.atError().addKeyValue("event", "audit.write_failed").addKeyValue("op_id", r.opId())
                    .addKeyValue("saga_id", r.sagaId()).addKeyValue("action", r.action())
                    .addKeyValue("error_kind", e.getClass().getName())
                    .log("the requested audit row can't be written: nothing is sent");
            throw new AuditUnavailableException(e);
        }
    }

    /** Writes the {@code answered} row; never throws. */
    public void answered(Requested r, Outcome outcome, String errorCode, String errorRule) {
        try {
            insert(r, "answered", outcome.name().toLowerCase(Locale.ROOT), errorCode, errorRule);
        } catch (RuntimeException e) {
            metrics.auditWriteFailure("answered");
            log.atError().addKeyValue("event", "audit.answer_write_failed").addKeyValue("op_id", r.opId())
                    .addKeyValue("saga_id", r.sagaId()).addKeyValue("phase", "answered")
                    .addKeyValue("error_kind", e.getClass().getName())
                    .log("the answered audit row can't be written; the requested row stays");
        }
    }

    /**
     * The saga's outcome, once per saga across pods. Only for sagas this tool started (an answered row
     * with that saga_id exists). True when this call wrote it.
     */
    public boolean outcome(String sagaId, String opId, String kind, String zone, String outcome, String detail) {
        try {
            int n = db.sql("""
                    INSERT INTO operation_outcome (saga_id, op_id, at, kind, zone, outcome, detail)
                    SELECT CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.saga_id = CAST(? AS uuid) AND a.phase = 'requested')
                    ON CONFLICT (saga_id) DO NOTHING""")
                    .params(sagaId, opId, now(), kind, zone, outcome, clip(detail, 500), sagaId)
                    .update();
            return n == 1;
        } catch (RuntimeException e) {
            metrics.auditWriteFailure("outcome");
            log.atError().addKeyValue("event", "audit.answer_write_failed").addKeyValue("op_id", opId)
                    .addKeyValue("saga_id", sagaId).addKeyValue("phase", "outcome")
                    .addKeyValue("error_kind", e.getClass().getName())
                    .log("the outcome row can't be written");
            return false;
        }
    }

    private void insert(Requested r, String phase, String outcome, String errorCode, String errorRule) {
        db.sql("""
                INSERT INTO audit_log (id, at, phase, op_id, request_id, saga_id, actor_sub, actor_username, actor_roles,
                                       auth_time, amr, client_ip, action, zone, tenant_id, unit, service, instance, release,
                                       reason, ticket, confirm_kind, before, after, request, outcome, error_code, error_rule)
                VALUES (?, ?, ?, CAST(? AS uuid), ?, CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)""")
                .params(UUID.fromString(Ids.newUuid7()), now(), phase, r.opId(), r.requestId(), r.sagaId(),
                        r.actor().sub(), r.actor().username(), r.actor().roles().toArray(new String[0]),
                        r.actor().authTime() == null ? null : r.actor().authTime().atOffset(ZoneOffset.UTC),
                        r.actor().amr().toArray(new String[0]), r.clientIp(), r.action(), r.zone(), r.tenantId(),
                        r.unit(), r.service(), r.instance(), r.release(),
                        "requested".equals(phase) ? r.reason() : null, "requested".equals(phase) ? r.ticket() : null,
                        r.confirmKind(),
                        "requested".equals(phase) ? json(r.before()) : null, "requested".equals(phase) ? json(r.after()) : null,
                        "requested".equals(phase) ? json(r.request()) : null,
                        outcome, errorCode, errorRule)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String json(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.toString();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
