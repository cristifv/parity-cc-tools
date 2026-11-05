package parity.cctools.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler served without a login: the login-refused and logged-out pages and Keycloak's back-channel
 * logout (which checks its own signed token). Nothing else.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {
    /** Why this handler is public (documentation, shown in the test's failure message). */
    String value();
}
