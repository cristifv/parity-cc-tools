package parity.cctools.control;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import parity.cctools.catalogue.Catalogue.Driver;
import parity.cctools.obs.Ids;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The service-control sagas in Postgres: {@code service_op} (current state, CAS on version) and {@code service_op_step}. */
@Component
public class ServiceOps {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One saga. {@code instance}: a pod, a media node, or {@code *} (the whole workload). */
    public record Op(String id, String opId, String zone, Driver driver, String direction, String service, String instance,
            String namespace, String state, String outcome, JsonNode recorded, JsonNode observed, String actorSub,
            String actorUsername, String reason, boolean forced, long version, int seq, Instant createdAt, Instant updatedAt,
            Instant finishedAt, String detail) {

        public boolean open() {
            return outcome == null;
        }

        public boolean disable() {
            return "disable".equals(direction);
        }
    }

    public record Step(int seq, String opId, Instant at, String fromState, String toState, String cause, String actorSub,
            String detail) {}

    private static final String COLUMNS = "id::text, op_id::text, zone, driver, direction, service, instance, namespace, state, "
            + "outcome, recorded::text, observed::text, actor_sub, actor_username, reason, forced, version, seq, created_at, "
            + "updated_at, finished_at, detail";

    private final JdbcClient db;
    private final Clock clock;

    public ServiceOps(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    void insert(Op o, String cause) {
        db.sql("""
                INSERT INTO service_op (id, op_id, zone, driver, direction, service, instance, namespace, state, recorded,
                                        actor_sub, actor_username, reason, forced, version, seq, created_at, updated_at, detail)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, 1, 1, ?, ?, ?)""")
                .params(o.id(), o.opId(), o.zone(), o.driver().wire(), o.direction(), o.service(), o.instance(), o.namespace(),
                        o.state(), o.recorded() == null ? "{}" : o.recorded().toString(), o.actorSub(), o.actorUsername(),
                        o.reason(), o.forced(), now(), now(), o.detail())
                .update();
        step(o.id(), 1, o.opId(), null, o.state(), cause, o.actorSub(), o.detail(), null);
    }

    void step(String serviceOpId, int seq, String opId, String from, String to, String cause, String actorSub, String detail,
            JsonNode observed) {
        db.sql("""
                INSERT INTO service_op_step (id, service_op_id, seq, op_id, at, from_state, to_state, cause, actor_sub, detail, observed)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS uuid), ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""")
                .params(UUID.fromString(Ids.newUuid7()).toString(), serviceOpId, seq, opId, now(), from, to, clip(cause, 200),
                        actorSub, clip(detail, 500), observed == null ? null : observed.toString())
                .update();
    }

    /**
     * A transition with compare-and-set on {@code version}: false when another pod moved it first (then
     * nothing is written).
     */
    boolean transition(Op o, String to, String outcome, String cause, String causeOpId, String actorSub, String detail,
            JsonNode observed) {
        int n = db.sql("""
                UPDATE service_op SET state = ?, outcome = ?, finished_at = ?, version = version + 1, seq = seq + 1,
                                      updated_at = ?, detail = ?, observed = CAST(? AS jsonb)
                WHERE id = CAST(? AS uuid) AND version = ? AND outcome IS NULL""")
                .params(to, outcome, outcome == null ? null : now(), now(), clip(detail, 500),
                        observed == null ? null : observed.toString(), o.id(), o.version())
                .update();
        if (n != 1) {
            return false;
        }
        step(o.id(), o.seq() + 1, causeOpId == null ? o.opId() : causeOpId, o.state(), to, cause, actorSub, detail, observed);
        return true;
    }

    /** The last observation (shown), without a transition. */
    void observed(Op o, JsonNode observed, String detail) {
        db.sql("UPDATE service_op SET observed = CAST(? AS jsonb), detail = ?, updated_at = ? WHERE id = CAST(? AS uuid) AND outcome IS NULL")
                .params(observed == null ? null : observed.toString(), clip(detail, 500), now(), o.id()).update();
    }

    /** The open saga, locked for this transaction, or empty when finished or another pod is evaluating it. */
    Optional<Op> lockOpen(String id) {
        return db.sql("SELECT " + COLUMNS + " FROM service_op WHERE id = CAST(? AS uuid) AND outcome IS NULL FOR UPDATE SKIP LOCKED")
                .param(id).query(ServiceOps::row).optional();
    }

    public Optional<Op> find(String id) {
        if (!Ids.isUuid7(id)) {
            return Optional.empty();
        }
        return db.sql("SELECT " + COLUMNS + " FROM service_op WHERE id = CAST(? AS uuid)").param(id).query(ServiceOps::row).optional();
    }

    public Optional<Op> openFor(String zone, String service, String instance) {
        return db.sql("SELECT " + COLUMNS + " FROM service_op WHERE zone = ? AND service = ? AND instance = ? AND outcome IS NULL")
                .params(zone, service, instance).query(ServiceOps::row).optional();
    }

    public List<Op> open() {
        return db.sql("SELECT " + COLUMNS + " FROM service_op WHERE outcome IS NULL ORDER BY created_at").query(ServiceOps::row).list();
    }

    public List<Op> recent(int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM service_op ORDER BY created_at DESC LIMIT ?").param(limit).query(ServiceOps::row).list();
    }

    /** The sagas an action caused or touched (started, cancelled, released by). */
    public List<Op> byOpId(String opId) {
        if (!Ids.isUuid7(opId)) {
            return List.of();
        }
        return db.sql("SELECT " + COLUMNS + " FROM service_op WHERE op_id = CAST(? AS uuid) OR id IN "
                + "(SELECT service_op_id FROM service_op_step WHERE op_id = CAST(? AS uuid)) ORDER BY created_at")
                .params(opId, opId).query(ServiceOps::row).list();
    }

    public List<Step> steps(String id) {
        return db.sql("""
                SELECT seq, op_id::text, at, from_state, to_state, cause, actor_sub, detail FROM service_op_step
                WHERE service_op_id = CAST(? AS uuid) ORDER BY seq""").param(id)
                .query((rs, n) -> new Step(rs.getInt(1), rs.getString(2), instant(rs.getTimestamp(3)), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))).list();
    }

    static Op row(ResultSet rs, int n) throws SQLException {
        return new Op(rs.getString(1), rs.getString(2), rs.getString(3), Driver.ofWire(rs.getString(4)), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), json(rs.getString(11)),
                json(rs.getString(12)), rs.getString(13), rs.getString(14), rs.getString(15), rs.getBoolean(16), rs.getLong(17),
                rs.getInt(18), instant(rs.getTimestamp(19)), instant(rs.getTimestamp(20)), instant(rs.getTimestamp(21)),
                rs.getString(22));
    }

    static JsonNode json(String s) {
        return s == null ? null : JSON.readTree(s);
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
