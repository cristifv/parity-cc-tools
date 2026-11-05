package parity.cctools.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signed-in operator, from the ops realm's ID token (PLAN.md §2): subject, username, realm roles,
 * {@code auth_time} (the last password + OTP), {@code amr}. Its capabilities follow from the roles.
 */
public record Operator(String sub, String username, List<String> roles, Instant authTime, List<String> amr,
        Set<Capability> capabilities) {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._@-]{1,64}$");
    private static final Pattern ROLE = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

    public Operator {
        roles = List.copyOf(roles);
        amr = List.copyOf(amr);
        capabilities = Set.copyOf(capabilities);
    }

    public boolean can(Capability c) {
        return capabilities.contains(c);
    }

    /** True when the last full login (password + OTP) is at most {@code maxAge} old. */
    public boolean freshLogin(Instant now, Duration maxAge) {
        return authTime != null && !authTime.isAfter(now.plusSeconds(60)) && Duration.between(authTime, now).compareTo(maxAge) <= 0;
    }

    /** The actor parity-rs records ({@code {sub, username, roles}}), in the schema's forms. */
    public Map<String, Object> actor() {
        List<String> r = roles.stream().filter(x -> ROLE.matcher(x).matches()).limit(16).toList();
        String user = USERNAME.matcher(username).matches() ? username : "unknown";
        return Map.of("sub", sub.length() > 128 ? sub.substring(0, 128) : sub, "username", user, "roles", r);
    }

    /** The roles claim (a list of strings) or Keycloak's {@code realm_access.roles}. */
    public static List<String> roles(Map<String, Object> claims, String rolesClaim) {
        Object direct = claims.get(rolesClaim);
        List<String> out = strings(direct);
        if (!out.isEmpty()) {
            return out;
        }
        if (claims.get("realm_access") instanceof Map<?, ?> realm) {
            return strings(realm.get("roles"));
        }
        return List.of();
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }
}
