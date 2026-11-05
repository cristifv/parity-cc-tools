package parity.cctools.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import parity.cctools.routing.RsClient;

/** Small helpers the controllers share: input forms, the client address, codes shown. */
final class Pages {

    static final Pattern TENANT = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    static final Pattern PLACE = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$");
    static final Pattern RELEASE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");
    static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$");
    static final Pattern UUID7 = parity.cctools.obs.Ids.UUID7;

    private Pages() {}

    static boolean tenant(String s) {
        return s != null && TENANT.matcher(s).matches();
    }

    static boolean place(String s) {
        return s != null && PLACE.matcher(s).matches();
    }

    static boolean uuid7(String s) {
        return s != null && UUID7.matcher(s).matches();
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** The client's address as Tomcat sees it (the internal ingress's forwarded address when trusted). */
    static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null || ip.length() > 64 ? null : ip;
    }

    static String code(RsClient.Answer a) {
        if (a == null) {
            return "nothing";
        }
        return switch (a.status()) {
            case OK -> "ok";
            case ERROR -> a.code() + (a.rule() == null ? "" : " (" + a.rule() + ")");
            case NOT_CONFIRMED -> "nothing in time";
            case NOT_SENT -> "nothing: the request couldn't be sent";
        };
    }

    /** The first 8 characters of an id (a typed confirmation where nothing better names the target). */
    static String short8(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
