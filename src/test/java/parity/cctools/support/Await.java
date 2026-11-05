package parity.cctools.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Waits for a condition the system reaches by itself (tests only; the code under test never waits on time). */
public final class Await {

    private Await() {}

    public static void until(String what, Duration max, BooleanSupplier condition) {
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
