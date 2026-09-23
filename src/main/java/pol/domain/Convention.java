package pol.domain;

import java.util.List;

/**
 * Coordinate and ordering conventions that feed the derivation fingerprint.
 * The Stokes component order and the rotation angle sign convention are part of
 * the identity of every derived Mueller estimate, so swapping either changes
 * the fingerprint and invalidates direct comparison.
 */
public final class Convention {
    public final List<String> stokesOrder;
    public final String rotationSign;
    public final String angleZero;
    public final String angleUnit;

    public Convention(List<String> stokesOrder, String rotationSign, String angleZero, String angleUnit) {
        this.stokesOrder = List.copyOf(stokesOrder);
        this.rotationSign = rotationSign;
        this.angleZero = angleZero;
        this.angleUnit = angleUnit;
    }

    public static Convention standard() {
        return new Convention(List.of("S0", "S1", "S2", "S3"), "active-positive-CCW",
                "analyzer-transmission-axis-at-0deg=horizontal", "deg");
    }

    public static Convention swappedOrder() {
        return new Convention(List.of("S0", "S1", "S3", "S2"), "active-positive-CCW",
                "analyzer-transmission-axis-at-0deg=horizontal", "deg");
    }

    public static Convention flippedRotation() {
        return new Convention(List.of("S0", "S1", "S2", "S3"), "active-positive-CW",
                "analyzer-transmission-axis-at-0deg=horizontal", "deg");
    }

    public String orderCode() {
        return String.join(",", stokesOrder);
    }

    public String fingerprint() {
        String basis = "stokes=[" + orderCode()
                + "];rotsign=" + rotationSign
                + ";zero=" + angleZero
                + ";angleunit=" + angleUnit;
        return "fp:" + Ids.sha256(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16)
                + " {" + basis + "}";
    }

    public String shortFp() {
        return fingerprint().substring(3, 19);
    }
}
