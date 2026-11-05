package parity.cctools.lifecycle;

import java.time.Duration;

/** Work the drain waits for, in order: the requests in flight, the email worker, the billing client. */
public interface Drainable {

    /** Stops taking new work and waits for the work in progress; false if time ran out. */
    boolean drain(Duration timeout) throws InterruptedException;
}
