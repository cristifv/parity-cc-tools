package parity.cctools.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import parity.cctools.lifecycle.Drainable;

/**
 * The requests in flight on the site's port: the drain's first step waits until none is left
 * (PLAN.md §8: remove healthy, finish the requests in flight, then drained). New requests are
 * still served while it waits; the load balancer has stopped sending them once the healthy file
 * is gone.
 *
 * <p>Outermost, it also gives each request its {@code request_id} (in the MDC, so every record
 * made while serving it carries it; an {@code X-Request-Id} from the edge is kept as
 * {@code attrs.edge_request_id}, never trusted as ours) and writes the one {@code http.request}
 * record per request (log-record.v1): {@code op} is the method and the matched route pattern,
 * never the raw path (a confirmation link's token is in its query, and paths can carry ids).
 */
@Component
@Order(1)
public class InFlightRequests extends OncePerRequestFilter implements Drainable {

    private static final Logger log = LoggerFactory.getLogger(InFlightRequests.class);
    private static final Pattern EDGE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,200}$");
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final AtomicInteger inFlight = new AtomicInteger();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean counted = !request.getRequestURI().endsWith("/stream");
        if (counted) {
            inFlight.incrementAndGet();
        }
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        MDC.put("request_id", requestId);
        String traceparent = request.getHeader("traceparent");
        boolean traced = traceparent != null && TRACEPARENT.matcher(traceparent).matches();
        if (traced) {
            MDC.put("traceparent", traceparent);
        }
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            if (counted) {
                inFlight.decrementAndGet();
            }
            logRequest(request, status, started);
            MDC.remove("request_id");
            if (traced) {
                MDC.remove("traceparent");
            }
        }
    }

    private static void logRequest(HttpServletRequest request, int status, long started) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern instanceof String p && !p.isEmpty() ? p.replaceAll("[^A-Za-z0-9_./:{}* -]", "_") : "other";
        route = route.length() > 100 ? route.substring(0, 100) : route;
        String edge = request.getHeader("X-Request-Id");
        (status >= 500 ? log.atWarn() : log.atInfo()).addKeyValue("event", "http.request")
                .addKeyValue("op", request.getMethod() + " " + route)
                .addKeyValue("outcome", status >= 500 ? "error" : status >= 400 ? "refused" : "ok")
                .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                .addKeyValue("status", status)
                .addKeyValue("edge_request_id", edge != null && EDGE_ID.matcher(edge).matches() ? edge : null)
                .log("request served");
    }

    public int count() {
        return inFlight.get();
    }

    @Override
    public boolean drain(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }
}
