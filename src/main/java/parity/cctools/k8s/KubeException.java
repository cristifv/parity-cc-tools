package parity.cctools.k8s;

/**
 * A Kubernetes API call failed: {@link #status()} is the HTTP status (0: no answer). 403, 404, 409 and
 * 422 are definitive refusals; anything else may pass on the next refresh.
 */
public class KubeException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final int status;

    public KubeException(int status, Throwable cause) {
        super("a Kubernetes API call failed", cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** True for an answer that won't change by asking again (forbidden, gone, invalid). */
    public boolean definitive() {
        return status == 403 || status == 404 || status == 409 || status == 422;
    }

    /** {@code http_<status>} or {@code unreachable}: the log's error.code. */
    public String code() {
        return status == 0 ? "unreachable" : "http_" + status;
    }
}
