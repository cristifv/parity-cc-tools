package parity.cctools.web;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import parity.cctools.obs.Ids;
import parity.cctools.ops.Action;
import parity.cctools.ops.Pending;
import parity.cctools.ops.RiskPolicy;
import parity.cctools.ops.RoutingActions;
import parity.cctools.routing.RsClient;
import tools.jackson.databind.JsonNode;

/**
 * Builds the pending confirmation of an action when its review page is shown: the ids are minted here
 * (the op_id of the action; a new saga_id for a saga-starting one, else the saga acted on; the command
 * id), the risk tier and the force requirement come from what the screen saw.
 */
@Component
public class Reviews {

    private final RsClient rs;
    private final Clock clock;

    public Reviews(RsClient rs, Clock clock) {
        this.rs = rs;
        this.clock = clock;
    }

    /** The target of an action, as the review shows it. */
    public record Target(String zone, String tenantId, String unit, String service, String instance, String release) {
        public static Target tenant(String zone, String tenantId) {
            return new Target(zone, tenantId, null, null, null, null);
        }
    }

    public Pending pending(Action a, RiskPolicy.Facts facts, Target t, String sagaId, String expectedConfirmation,
            Map<String, String> params, JsonNode before, JsonNode after, String title, String returnPath) {
        String opId = Ids.newUuid7();
        String saga = RoutingActions.startsSaga(a) ? Ids.newUuid7() : sagaId;
        String requestId = a.routing() ? rs.newId(RoutingActions.cmd(a)) : "cct:" + a.wire().replace('.', '-') + "-" + UUID.randomUUID();
        return new Pending(opId, saga, requestId, a, RiskPolicy.tier(a, facts), RiskPolicy.forceRequired(a, facts), t.zone(),
                t.tenantId(), t.unit(), t.service(), t.instance(), t.release(), expectedConfirmation, params,
                before == null ? null : before.toString(), after == null ? null : after.toString(), title, returnPath,
                clock.millis(), Pending.Form.empty());
    }
}
