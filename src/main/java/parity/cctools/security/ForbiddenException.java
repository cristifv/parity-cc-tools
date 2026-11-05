package parity.cctools.security;

/** The operator lacks the capability (403); the message is a constant. */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(@com.google.errorprone.annotations.CompileTimeConstant String message) {
        super(message);
    }
}
