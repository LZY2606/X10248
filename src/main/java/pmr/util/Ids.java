package pmr.util;

import java.util.concurrent.atomic.AtomicLong;

/** Deterministic, stable identifiers. Replay-derived ids do not depend on wall time. */
public final class Ids {
    private static final AtomicLong COUNTER = new AtomicLong();

    private Ids() {}

    /** Stable id derived from the hash of its semantic inputs. */
    public static String stable(String prefix, String semantic) {
        return prefix + "_" + Hashing.sha256Hex(semantic).substring(0, 16);
    }

    /** Runtime id for interactive (non-replay) events. */
    public static String runtime(String prefix) {
        return prefix + "_r" + Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString((int) COUNTER.incrementAndGet());
    }
}
