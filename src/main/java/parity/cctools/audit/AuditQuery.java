package parity.cctools.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import parity.cctools.obs.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The audit screen's reads (PLAN.md §4.9): search, one action's rows, a tenant's history, a saga's outcome. */
@Component
public class AuditQuery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One audit row as shown. */
    public record Row(String id, Instant at, String phase, String opId, String requestId, String sagaId, String actorSub,
            String actorUsername, List<String> actorRoles, Instant authTime, String action, String zone, String tenantId, String unit,
            String service, String instance, String release, String reason, String ticket, String confirmKind, JsonNode before,
            JsonNode after, JsonNode request, String outcome, String errorCode, String errorRule) {}

    /** The search form; blank fields don't filter. */
    public record Filter(String actor, String action, String tenantId, String unit, String zone, String service, String opId,
            Instant from, Instant to) {}

    /** A saga's outcome row. */
    public record Outcome(String sagaId, String opId, Instant at, String kind, String zone, String outcome, String detail) {}

    private static final String COLUMNS = "id::text, at, phase, op_id::text, request_id, saga_id::text, actor_sub, actor_username, "
            + "actor_roles, auth_time, action, zone, tenant_id, unit, service, instance, release, reason, ticket, confirm_kind, "
            + "before::text, after::text, request::text, outcome, error_code, error_rule";

    private final JdbcClient db;

    public AuditQuery(JdbcClient db) {
        this.db = db;
    }

    public List<Row> search(Filter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM audit_log WHERE at >= ? AND at < ?");
        List<Object> params = new ArrayList<>();
        Instant to = f.to() == null ? Instant.now().plusSeconds(60) : f.to();
        Instant from = f.from() == null ? to.minusSeconds(30L * 24 * 3600) : f.from();
        params.add(from.atOffset(ZoneOffset.UTC));
        params.add(to.atOffset(ZoneOffset.UTC));
        eq(sql, params, "actor_username", f.actor());
        eq(sql, params, "action", f.action());
        eq(sql, params, "tenant_id", f.tenantId());
        eq(sql, params, "unit", f.unit());
        eq(sql, params, "zone", f.zone());
        eq(sql, params, "service", f.service());
        if (f.opId() != null && !f.opId().isBlank()) {
            if (!Ids.isUuid7(f.opId().strip())) {
                return List.of();
            }
            sql.append(" AND op_id = CAST(? AS uuid)");
            params.add(f.opId().strip());
        }
        sql.append(" ORDER BY at DESC, id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(500, limit)));
        return db.sql(sql.toString()).params(params).query(AuditQuery::row).list();
    }

    private static void eq(StringBuilder sql, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            params.add(value.strip());
        }
    }

    /** Every row of one action (its op_id), oldest first. */
    public List<Row> byOpId(String opId) {
        if (!Ids.isUuid7(opId)) {
            return List.of();
        }
        return db.sql("SELECT " + COLUMNS + " FROM audit_log WHERE op_id = CAST(? AS uuid) ORDER BY at, id").param(opId)
                .query(AuditQuery::row).list();
    }

    public Optional<Row> one(String id) {
        if (id == null || !id.matches("^[0-9a-f-]{36}$")) {
            return Optional.empty();
        }
        return db.sql("SELECT " + COLUMNS + " FROM audit_log WHERE id = CAST(? AS uuid)").param(id).query(AuditQuery::row).optional();
    }

    public List<Row> forTenant(String tenantId, int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM audit_log WHERE tenant_id = ? ORDER BY at DESC LIMIT ?").params(tenantId, limit)
                .query(AuditQuery::row).list();
    }

    public List<Outcome> outcomes(String opId) {
        if (!Ids.isUuid7(opId)) {
            return List.of();
        }
        return db.sql("SELECT saga_id::text, op_id::text, at, kind, zone, outcome, detail FROM operation_outcome WHERE op_id = CAST(? AS uuid)")
                .param(opId).query((rs, n) -> new Outcome(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))).list();
    }

    static Row row(ResultSet rs, int n) throws SQLException {
        java.sql.Array roles = rs.getArray(9);
        List<String> r = new ArrayList<>();
        if (roles != null) {
            for (Object o : (Object[]) roles.getArray()) {
                r.add(String.valueOf(o));
            }
        }
        return new Row(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), r,
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18),
                rs.getString(19), rs.getString(20), json(rs.getString(21)), json(rs.getString(22)), json(rs.getString(23)),
                rs.getString(24), rs.getString(25), rs.getString(26));
    }

    static JsonNode json(String s) {
        return s == null ? null : JSON.readTree(s);
    }
}
