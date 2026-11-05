package parity.cctools.ops;

/** An action refused before anything was written or sent; the form shows {@link #reason()}. */
public class ActionRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why (log attrs.reason and the form's message). */
    public enum Reason {
        TYPED_CONFIRMATION("typed_confirmation", "Type the exact name shown to confirm."),
        REASON_LENGTH("reason_length", "Give a reason of 10 to 500 characters."),
        TICKET_FORMAT("ticket_format", "The ticket reference has characters it can't have."),
        FORCE_REQUIRED("force_required", "This touches the last ready instance or the call path: tick force to go on."),
        STALE_FORM("stale_form", "This confirmation expired or was already used: start again.");

        private final String code;
        private final String message;

        Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }

    private final Reason reason;

    public ActionRefusedException(Reason reason) {
        super("an action was refused before it was sent");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
