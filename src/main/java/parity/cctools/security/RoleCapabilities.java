package parity.cctools.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Roles to capabilities (PLAN.md §1): one role, {@code ops}, with every capability (user decision
 * 2026-11-03). A later split (view-only, project manager) is a new entry here.
 */
public final class RoleCapabilities {

    private static final Map<String, Set<Capability>> ROLES = Map.of("ops", EnumSet.allOf(Capability.class));

    private RoleCapabilities() {}

    public static Set<Capability> of(Collection<String> roles) {
        EnumSet<Capability> out = EnumSet.noneOf(Capability.class);
        if (roles != null) {
            for (String r : roles) {
                Set<Capability> c = ROLES.get(r);
                if (c != null) {
                    out.addAll(c);
                }
            }
        }
        return Set.copyOf(out);
    }
}
