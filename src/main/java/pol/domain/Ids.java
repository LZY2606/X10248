package pol.domain;

import pol.math.Polar;

import java.time.Instant;
import java.util.UUID;

public final class Ids {
    private Ids() {}
    public static String uuid() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }
    public static String hash(String content) { return Polar.sha256Hex(content); }
}
