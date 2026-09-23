package pol.math;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Stokes conventions, state encoding and fingerprints.
 * Canonical Stokes order is [I,Q,U,V]; alternative orders are mapped into the
 * canonical one before computation, while the declared order stays in the fingerprint.
 */
public final class Polar {
    public static final String[] CANONICAL = {"I", "Q", "U", "V"};

    private Polar() {}

    public static double[] normalizeStokes(double[] stokes) {
        double n = 0;
        for (int i = 1; i < stokes.length; i++) n += stokes[i] * stokes[i];
        n = Math.sqrt(n);
        if (n == 0) return stokes.clone();
        double[] out = stokes.clone();
        for (int i = 1; i < out.length; i++) out[i] /= n;
        return out;
    }

    /**
     * Polarization state generator/analyzer vector as 4x4 Mueller matrix.
     * kind = "PSG" or "PSA". sign handles rotation convention: +1 or -1.
     */
    public static double[][] stateMueller(double[] stokes, String kind, int sign) {
        double[] s = normalizeStokes(stokes);
        double d = kind.equals("PSA") ? 0.5 : 1.0;
        double[][] m = Mat.create(4, 4);
        double[] v = {s[0], sign * s[1], sign * s[2], sign * s[3]};
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                m[i][j] = d * v[i] * v[j];
        return m;
    }

    /** Flatten a 4x4 state Mueller in row-major order, exactly the 16 entries of one design row. */
    public static double[] designRow(double[] stokes, String kind, int sign) {
        double[][] m = stateMueller(stokes, kind, sign);
        double[] row = new double[16];
        int k = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) row[k++] = m[i][j];
        return row;
    }

    public static int[] orderMapping(String declaredOrder) {
        String[] parts = declaredOrder.toUpperCase(Locale.ROOT).split("[,>/\\s]+");
        if (parts.length != 4) {
            parts = declaredOrder.replaceAll("[^IQUV]", "").split("");
        }
        int[] map = new int[4];
        for (int i = 0; i < 4; i++) {
            int p = Arrays.asList(CANONICAL).indexOf(parts[i]);
            if (p < 0) throw new IllegalArgumentException("bad stokes order: " + declaredOrder);
            map[i] = p;
        }
        return map;
    }

    /** Reorder a state vector from a declared Stokes order into canonical [I,Q,U,V]. */
    public static double[] toCanonical(double[] declared, String order) {
        int[] map = orderMapping(order);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[map[i]] = declared[i];
        return out;
    }

    public static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(content.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convention fingerprint: rotation sign + Stokes order + encoder identity enter the
     * fingerprint so a convention change never silently masquerades as the same data.
     */
    public static String fingerprint(int rotationSign, String stokesOrder, String stateEncodingId) {
        String body = "rot=" + rotationSign + "|stokes=" + stokesOrder + "|enc=" + stateEncodingId;
        return "fp-" + Polar.sha256Hex(body).substring(0, 16) + " (" + body + ")";
    }
}
