package parity.cctools.ops;

import java.io.Serializable;
import java.util.Map;

/**
 * An action the operator is confirming, kept in the (encrypted) session from the moment the review page
 * is shown: its ids are minted here ({@code op_id}, {@code saga_id}, the command {@code id}), so a lost
 * answer, a step-up login or a resend never makes a second operation. The confirm form carries only the
 * {@code op_id} and the operator's inputs; the target comes from here, never from the form.
 */
public record Pending(
        String opId,
        String sagaId,
        String requestId,
        Action action,
        Risk risk,
        boolean forceRequired,
        String zone,
        String tenantId,
        String unit,
        String service,
        String instance,
        String release,
        String expectedConfirmation,
        Map<String, String> params,
        String beforeJson,
        String afterJson,
        String title,
        String returnPath,
        long createdAtMs,
        Form form) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Pending {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** What the operator typed (kept for the page that resumes after a step-up). */
    public record Form(String reason, String ticket, String typed, boolean force) implements Serializable {
        private static final long serialVersionUID = 1L;

        public static Form empty() {
            return new Form("", "", "", false);
        }
    }

    public Pending withForm(Form f) {
        return new Pending(opId, sagaId, requestId, action, risk, forceRequired, zone, tenantId, unit, service, instance, release,
                expectedConfirmation, params, beforeJson, afterJson, title, returnPath, createdAtMs, f);
    }

    public String param(String name) {
        return params.get(name);
    }
}
