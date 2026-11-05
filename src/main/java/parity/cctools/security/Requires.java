package parity.cctools.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The capability a controller method needs. Every handler method carries this or {@link PublicEndpoint};
 * {@link CapabilityInterceptor} refuses a method with neither (fail closed), and a unit test fails the
 * build when one is missing.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Requires {
    Capability value();
}
