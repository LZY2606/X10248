package polroom.core;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Polarization conventions that change how optical states and Mueller elements are
 * interpreted. Both enter the fingerprint so data/results are never silently reused
 * across conventions.
 *
 * stokesOrder is a permutation string, e.g. "IQUV" or "IVUQ".
 * rotationSign is +1 (right-hand / counterclockwise looking into beam, per config)
 * or -1 for the opposite convention.
 * angleBasis documents the reference (e.g. "lab-x, looking INTO beam").
 */
public final class Conventions {
    public final String stokesOrder;
    public final int rotationSign;
    public final String angleBasis;

    public Conventions(String stokesOrder, int rotationSign, String angleBasis) {
        if (stokesOrder != null && !stokesOrder.equals("IQUV")) {
            validatePermutation(stokesOrder);
        }
        if (rotationSign != 1 && rotationSign != -1) {
            throw new IllegalArgumentException("rotationSign must be +1 or -1: " + rotationSign);
        }
        this.stokesOrder = stokesOrder == null ? "IQUV" : stokesOrder;
        this.rotationSign = rotationSign;
        this.angleBasis = angleBasis == null ? "lab-x" : angleBasis;
    }

    private static void validatePermutation(String p) {
        if (p.length() != 4) throw new IllegalArgumentException("stokes order must have 4 labels: " + p);
        String sorted = p.chars().sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (!sorted.equals("IQUV")) throw new IllegalArgumentException("stokes order must permute I,Q,U,V: " + p);
    }

    /** Index mapping: for a channel label in THIS convention, its canonical IQUV index. */
    public int canonicalIndex(String label) {
        int here = stokesOrder.indexOf(label);
        // returns permutation row: canonical position -> stored position handled by permutation()
        return here;
    }

    /** permutation[i] = stored position of canonical component i (I=0,Q=1,U=2,V=3). */
    public int[] permutation() {
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) {
            char c = "IQUV".charAt(i);
            p[i] = stokesOrder.indexOf(c);
        }
        return p;
    }

    public Conventions withStokesOrder(String order) {
        return new Conventions(order, rotationSign, angleBasis);
    }

    public Conventions withRotationSign(int sign) {
        return new Conventions(stokesOrder, sign, angleBasis);
    }

    public String fingerprint() {
        Map<String, String> m = new TreeMap<>();
        m.put("stokesOrder", stokesOrder);
        m.put("rotationSign", Integer.toString(rotationSign));
        m.put("angleBasis", angleBasis);
        String body = m.toString();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "conv-" + HexFormat.of().formatHex(md.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Conventions c)) return false;
        return rotationSign == c.rotationSign && Objects.equals(stokesOrder, c.stokesOrder)
                && Objects.equals(angleBasis, c.angleBasis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stokesOrder, rotationSign, angleBasis);
    }

    @Override
    public String toString() {
        return "Conventions{" + stokesOrder + ", rotSign=" + rotationSign + ", basis=" + angleBasis + ", " + fingerprint() + "}";
    }
}
