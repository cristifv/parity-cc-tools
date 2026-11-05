package parity.cctools.ops;

import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The session's pending confirmations (at most 20, each at most 30 minutes old): the review page puts one,
 * the confirm takes it (a second submit finds none: {@code stale_form}), a step-up keeps it for the resume.
 * Stored as one session attribute (a serializable map), so Spring Session JDBC saves the change.
 */
@Component
public class PendingActions {

    static final String ATTRIBUTE = PendingActions.class.getName();
    static final int MAX = 20;
    static final long MAX_AGE_MS = 30 * 60 * 1000L;

    public void put(HttpSession session, Pending p) {
        Map<String, Pending> m = map(session);
        m.remove(p.opId());
        m.put(p.opId(), p);
        while (m.size() > MAX) {
            m.remove(m.keySet().iterator().next());
        }
        session.setAttribute(ATTRIBUTE, m);
    }

    /** The pending action, or null (unknown, expired). */
    public Pending get(HttpSession session, String opId, long nowMs) {
        Pending p = map(session).get(opId);
        return p == null || nowMs - p.createdAtMs() > MAX_AGE_MS ? null : p;
    }

    /** Takes it out (a confirmation is used once). */
    public Pending take(HttpSession session, String opId, long nowMs) {
        Map<String, Pending> m = map(session);
        Pending p = m.remove(opId);
        session.setAttribute(ATTRIBUTE, m);
        return p == null || nowMs - p.createdAtMs() > MAX_AGE_MS ? null : p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Pending> map(HttpSession session) {
        Object o = session.getAttribute(ATTRIBUTE);
        return o instanceof LinkedHashMap<?, ?> m ? new LinkedHashMap<>((Map<String, Pending>) m) : new LinkedHashMap<>();
    }
}
