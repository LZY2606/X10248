package polwork.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/**
 * Optical conventions. Rotation sign and Stokes ordering are part of the
 * measurement fingerprint: changing either one changes the identity of every
 * derived result, and the old branch remains readable.
 */
public final class Conventions {

    /** Ordering of the four Stokes components. */
    public enum StokesOrder {
        S0S1S2S3,
        S1S2S3S0
    }

    /**
     * Sign applied to recorded optical-element angles. OPTICS_INTO_BEAM is the
     * common lab-optics convention (positive rotation seen looking into the
     * beam); LAB_POS_CCW is the instrument encoder convention (CCW positive
     * looking along propagation).
     */
    public enum RotationSign {
        OPTICS_INTO_BEAM,
        LAB_POS_CCW
    }

    private final StokesOrder stokesOrder;
    private final RotationSign rotationSign;

    public Conventions(StokesOrder stokesOrder, RotationSign rotationSign) {
        this.stokesOrder = Objects.requireNonNull(stokesOrder);
        this.rotationSign = Objects.requireNonNull(rotationSign);
    }

    public static Conventions defaultLab() {
        return new Conventions(StokesOrder.S0S1S2S3, RotationSign.OPTICS_INTO_BEAM);
    }

    public StokesOrder stokesOrder() {
        return stokesOrder;
    }

    public RotationSign rotationSign() {
        return rotationSign;
    }

    /** p[i] = storage index holding physical component i (i=0 is S0). */
    public int[] storagePermutation() {
        return switch (stokesOrder) {
            case S0S1S2S3 -> new int[] {0, 1, 2, 3};
            case S1S2S3S0 -> new int[] {3, 0, 1, 2};
        };
    }

    /** Sign multiplier applied to recorded optical-element angles (rad). */
    public double angleSign() {
        return rotationSign == RotationSign.OPTICS_INTO_BEAM ? -1.0 : 1.0;
    }

    /** Stable canonical tag used inside fingerprints. */
    public String tag() {
        return "stokes=" + stokesOrder.name() + ";rot=" + rotationSign.name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Conventions c)) return false;
        return stokesOrder == c.stokesOrder && rotationSign == c.rotationSign;
    }

    @Override
    public int hashCode() {
        return Objects.hash(stokesOrder, rotationSign);
    }

    @Override
    public String toString() {
        return tag();
    }

    /**
     * Content fingerprint over every interpretation input. A changed source
     * version or convention yields a new fingerprint; derived results are keyed
     * by it rather than silently replacing the previous conclusion.
     */
    public static String fingerprint(String materialId,
                                     String wavelengthId,
                                     String intensityVersionId,
                                     String darkVersionId,
                                     String referenceVersionId,
                                     String instrumentVersionId,
                                     String covarianceVersionId,
                                     Conventions conventions,
                                     String ruleSetVersion) {
        String canonical = String.join("|",
                nz(materialId), nz(wavelengthId), nz(intensityVersionId),
                nz(darkVersionId), nz(referenceVersionId),
                nz(instrumentVersionId), nz(covarianceVersionId),
                conventions.tag(), nz(ruleSetVersion)).toLowerCase(Locale.ROOT);
        return "fp_" + sha256Hex(canonical).substring(0, 24);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
