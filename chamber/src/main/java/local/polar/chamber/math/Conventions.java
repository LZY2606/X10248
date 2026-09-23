package local.polar.chamber.math;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Vector-coordinate conventions that change the *meaning* of a fit.
 *
 * <p>The Stokes component order and the rotation sign convention participate in the
 * estimation fingerprint: the same raw intensities interpreted with a different order
 * or rotation sign are different candidates, never silent overwrites.
 */
public final class Conventions {

    /** Standard orders for the four Stokes components S = (S0, S1, S2, S3). */
    public static final Map<String, int[]> STOKES_ORDERS = Map.of(
            "IQUV", new int[]{0, 1, 2, 3},
            "IVQU", new int[]{0, 3, 1, 2},
            "IUQV", new int[]{0, 2, 1, 3}
    );

    /** {@code RIGHT}: theta appears as +theta in rotators (right-handed). {@code LEFT}: -theta. */
    public enum RotationSign {
        RIGHT, LEFT
    }

    private Conventions() {
    }

    public static int[] stokesOrder(String name) {
        int[] o = STOKES_ORDERS.get(name);
        if (o == null) {
            throw new IllegalArgumentException(
                    "unsupported Stokes order '" + name + "', known: " + STOKES_ORDERS.keySet());
        }
        return o.clone();
    }

    public static String stokesOrderCanonical(String name) {
        if (!STOKES_ORDERS.containsKey(name)) {
            throw new IllegalArgumentException("unsupported Stokes order '" + name + "'");
        }
        return name;
    }

    public static int rotationSign(RotationSign sign) {
        return sign == RotationSign.RIGHT ? 1 : -1;
    }

    /**
     * Permute a canonical (I,Q,U,V) vector into the requested order.
     *
     * @param canonical indices 0..3 hold I,Q,U,V
     * @param order target order name, e.g. IVQU
     */
    public static double[] toOrder(double[] canonical, String order) {
        int[] perm = stokesOrder(order);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = canonical[perm[i]];
        }
        return out;
    }

    /** Move a vector expressed in {@code order} back to canonical IQUV. */
    public static double[] fromOrder(double[] ordered, String order) {
        int[] perm = stokesOrder(order);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[perm[i]] = ordered[i];
        }
        return out;
    }

    /**
     * Convert a Mueller matrix fitted in {@code order} coordinates into canonical IQUV.
     * M_canonical = P^{-1} M_order P where P maps canonical -> order.
     */
    public static double[][] matrixToCanonical(double[][] mOrder, String order) {
        if ("IQUV".equals(order)) {
            return Lin.copy(mOrder);
        }
        int[] perm = stokesOrder(order);
        double[][] p = new double[4][4];
        for (int i = 0; i < 4; i++) {
            p[i][perm[i]] = 1.0;
        }
        double[][] pinv = new double[4][4];
        for (int i = 0; i < 4; i++) {
            pinv[perm[i]][i] = 1.0;
        }
        return mul3(pinv, mOrder, p);
    }

    private static double[][] mul3(double[][] a, double[][] b, double[][] c) {
        int n = a.length;
        double[][] t = new double[n][n];
        double[][] r = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                for (int j = 0; j < n; j++) {
                    t[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                for (int j = 0; j < n; j++) {
                    r[i][j] += t[i][k] * c[k][j];
                }
            }
        }
        return r;
    }

    /** Short stable tag of the convention block used by a fit. */
    public static String fingerprint(String stokesOrder, RotationSign rotationSign,
                                     String rulesVersion) {
        String payload = "stokes=" + stokesOrderCanonical(stokesOrder)
                + "|rot=" + rotationSign.name()
                + "|rules=" + rulesVersion
                + "|vec=row-stack";
        return sha256Hex(payload).substring(0, 12);
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean knownOrder(String order) {
        return order != null && STOKES_ORDERS.containsKey(order);
    }

    public static String describeOrder(String order) {
        return order + Arrays.toString(stokesOrder(order));
    }
}
