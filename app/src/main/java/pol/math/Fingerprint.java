package pol.math;

import pol.Ids;

import java.util.Map;

/**
 * Interpretation conventions that must enter the derivation fingerprint:
 * the Stokes component ordering and the rotation sign convention.
 *
 * stokesOrder: "SIQUV" or "SUVIQ" (maps component names to vector index).
 * rotationSign: +1 means increasing instrument angle rotates counter-clockwise (right-handed);
 *              -1 means clockwise increments are positive.
 */
public final class Fingerprint {
    public final String stokesOrder;
    public final int rotationSign;

    public Fingerprint(String stokesOrder, int rotationSign) {
        if (!"SIQUV".equals(stokesOrder) && !"SUVIQ".equals(stokesOrder))
            throw new IllegalArgumentException("unsupported stokes order: " + stokesOrder);
        if (rotationSign != 1 && rotationSign != -1)
            throw new IllegalArgumentException("rotation sign must be +1 or -1");
        this.stokesOrder = stokesOrder;
        this.rotationSign = rotationSign;
    }

    public static Fingerprint of(Map<String, Object> m) {
        return new Fingerprint(
                m.getOrDefault("stokesOrder", "SIQUV").toString(),
                Integer.parseInt(m.getOrDefault("rotationSign", "1").toString()));
    }

    public String code() {
        return "FP/" + stokesOrder + "/ROT" + (rotationSign > 0 ? "PLUS" : "MINUS");
    }

    public String hash(String... extra) {
        String[] parts = new String[extra.length + 2];
        parts[0] = stokesOrder;
        parts[1] = Integer.toString(rotationSign);
        System.arraycopy(extra, 0, parts, 2, extra.length);
        return Ids.hash(parts);
    }

    /** Signed angle under this rotation convention: raw instrument value already in degrees. */
    public double signedAngleDeg(double instrumentAngleDeg) {
        return rotationSign * instrumentAngleDeg;
    }

    /** Index of a named Stokes component under this ordering. */
    public int index(String component) {
        int p = stokesOrder.indexOf(component.charAt(0));
        if (p < 0) throw new IllegalArgumentException("unknown stokes component " + component);
        return p;
    }

    public Map<String, Object> toMap() {
        return Map.of("stokesOrder", stokesOrder, "rotationSign", rotationSign, "code", code());
    }
}
