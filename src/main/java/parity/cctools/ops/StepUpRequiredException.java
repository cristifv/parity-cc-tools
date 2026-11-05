package parity.cctools.ops;

/** A high-risk action needs a fresh login first; the controller sends the operator to the ops realm and resumes. */
public class StepUpRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final java.util.UUID opId;

    public StepUpRequiredException(java.util.UUID opId) {
        super("a fresh login is needed for this action");
        this.opId = opId;
    }

    public String opId() {
        return opId.toString();
    }
}
