package parity.cctools.audit;

/** The {@code requested} audit row couldn't be written: the action must not be sent (fail closed). */
public class AuditUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditUnavailableException(Throwable cause) {
        super("the audit log can't be written: nothing was sent", cause);
    }
}
