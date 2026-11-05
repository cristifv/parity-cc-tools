package parity.cctools.obs;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ids this tool mints: UUIDv7 (RFC 9562, lowercase text) for the {@code op_id} of every confirmed
 * action, the {@code saga_id} of every saga it starts, audit rows and service-control steps
 * (parity-contracts CONVENTIONS "The op_id").
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final Pattern UUID7 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private Ids() {}

    /** A new UUIDv7: 48 bits of Unix milliseconds, version 7, then random bits. */
    public static UUID uuid7(long epochMillis) {
        long hi = (epochMillis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lo = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo);
    }

    public static String newUuid7() {
        return uuid7(System.currentTimeMillis()).toString();
    }

    public static boolean isUuid7(String s) {
        return s != null && UUID7.matcher(s).matches();
    }
}
