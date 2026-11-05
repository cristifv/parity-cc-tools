package parity.cctools.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * PLAN.md §7: every controller method names its capability. A handler method with neither
 * {@link Requires} nor {@link PublicEndpoint} fails the build here (and is refused at run time by
 * {@link CapabilityInterceptor}); the public ones are exactly the three pages that can't have a login.
 */
class CapabilityCoverageTest {

    @Test
    void everyHandlerNamesItsCapability() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> missing = new ArrayList<>();
        List<String> publicOnes = new ArrayList<>();
        int handlers = 0;
        for (var bd : scan.findCandidateComponents("parity.cctools")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            for (Method m : c.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) {
                    continue;
                }
                handlers++;
                boolean req = m.isAnnotationPresent(Requires.class);
                boolean pub = m.isAnnotationPresent(PublicEndpoint.class);
                if (req == pub) {
                    missing.add(c.getSimpleName() + "." + m.getName());
                }
                if (pub) {
                    publicOnes.add(c.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(handlers).isGreaterThan(30);
        assertThat(missing).as("handlers without exactly one of @Requires/@PublicEndpoint").isEmpty();
        assertThat(publicOnes).containsExactlyInAnyOrder("AccountController.refused", "AccountController.loggedOut",
                "BackchannelLogout.logout");
    }

    @Test
    void theOpsRoleHasEveryCapabilityAndNothingElseHasAny() {
        assertThat(RoleCapabilities.of(List.of("ops"))).isEqualTo(EnumSet.allOf(Capability.class));
        assertThat(RoleCapabilities.of(List.of("parity-admin", "platform-admin", "offline_access"))).isEmpty();
        assertThat(RoleCapabilities.of(List.of())).isEmpty();
        assertThat(RoleCapabilities.of(null)).isEmpty();
        assertThat(Set.of(Capability.values())).hasSize(6);
    }
}
