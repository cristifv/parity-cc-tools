package parity.cctools.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import parity.cctools.lifecycle.Drainable;
import parity.cctools.routing.SagaProjection;

/**
 * Live progress over Server-Sent Events (PLAN.md §6.2): one stream per browser tab and saga, fed by the
 * pod's saga projection, each sending the saga's progress fragment (HTML, one line) as event
 * {@code progress}; a finished saga sends its last fragment, then {@code done}, and closes. A stream
 * lasts at most 15 minutes (then the page reconnects and is re-seeded). Sends happen on the stream's own
 * virtual thread, latest state first, so a slow browser never holds the Kafka reader. The drain closes
 * every stream.
 */
@Component
public class ProgressStreams implements Drainable {

    static final Duration MAX = Duration.ofMinutes(15);

    private final SagaProjection projection;
    private final ITemplateEngine templates;
    private final Set<Stream> open = ConcurrentHashMap.newKeySet();
    private volatile boolean draining;

    public ProgressStreams(SagaProjection projection, ITemplateEngine templates) {
        this.projection = projection;
        this.templates = templates;
    }

    /** The progress fragment of a view, as one line of HTML. */
    public String render(SagaProjection.View v, String opId) {
        Context ctx = new Context();
        ctx.setVariable("view", v);
        ctx.setVariable("opId", opId);
        return templates.process("fragments/progress", Set.of("progress"), ctx).replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
    }

    public SseEmitter open(String sagaId, String opId) {
        SseEmitter e = new SseEmitter(MAX.toMillis());
        if (draining) {
            e.complete();
            return e;
        }
        Stream s = new Stream(e, opId);
        open.add(s);
        Runnable unwatch = projection.watch(sagaId, s::offer);
        Runnable end = () -> {
            unwatch.run();
            s.stop();
            open.remove(s);
        };
        e.onCompletion(end);
        e.onTimeout(end);
        e.onError(t -> end.run());
        SagaProjection.View v = projection.get(sagaId);
        if (v != null) {
            s.offer(v);
        }
        s.start();
        return e;
    }

    public int openStreams() {
        return open.size();
    }

    @Override
    public boolean drain(Duration timeout) {
        draining = true;
        for (Stream s : open) {
            s.close();
        }
        return true;
    }

    /** One browser stream: the newest view waits in {@code latest}; its thread sends it. */
    private final class Stream {
        private final SseEmitter emitter;
        private final String opId;
        private final AtomicReference<SagaProjection.View> latest = new AtomicReference<>();
        private final Semaphore signal = new Semaphore(0);
        private final AtomicBoolean running = new AtomicBoolean(true);

        Stream(SseEmitter emitter, String opId) {
            this.emitter = emitter;
            this.opId = opId;
        }

        void offer(SagaProjection.View v) {
            latest.set(v);
            signal.release();
        }

        void start() {
            Thread.ofVirtual().name("sse-progress").start(this::run);
        }

        void stop() {
            running.set(false);
            signal.release();
        }

        void close() {
            stop();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
        }

        private void run() {
            try {
                while (running.get()) {
                    if (!signal.tryAcquire(30, TimeUnit.SECONDS)) {
                        // A comment keeps idle proxies from closing the stream (a keep-alive, not a decision).
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                        continue;
                    }
                    signal.drainPermits();
                    SagaProjection.View v = latest.getAndSet(null);
                    if (v == null) {
                        continue;
                    }
                    emitter.send(SseEmitter.event().name("progress").data(render(v, opId)));
                    if (v.finished()) {
                        emitter.send(SseEmitter.event().name("done").data("done"));
                        emitter.complete();
                        return;
                    }
                }
            } catch (IOException | IllegalStateException e) {
                // The browser went away: the emitter's callbacks clean up.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
