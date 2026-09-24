package polmu.domain;

/** Stokes vector ordering and rotation sign conventions. Both enter the derivation fingerprint. */
public final class Conventions {
    public enum StokesOrder { IQUV, IVUQ }
    public enum RotationSense { CCW_POSITIVE, CW_POSITIVE }

    public final StokesOrder stokesOrder;
    public final RotationSense rotationSense;

    public Conventions(StokesOrder stokesOrder, RotationSense rotationSense) {
        this.stokesOrder = stokesOrder;
        this.rotationSense = rotationSense;
    }

    public static Conventions opticsDefault() {
        return new Conventions(StokesOrder.IQUV, RotationSense.CCW_POSITIVE);
    }

    /** Reorder a Stokes 4-vector from IQUV canonical form into this order. */
    public double[] fromCanonical(double[] s) {
        if (stokesOrder == StokesOrder.IQUV) return s.clone();
        return new double[]{s[0], s[1], s[3], s[2]};
    }

    /** Permutation indices mapping canonical IQUV Mueller element index -> this order. */
    public int[] permutation() {
        return stokesOrder == StokesOrder.IQUV ? new int[]{0, 1, 2, 3} : new int[]{0, 1, 3, 2};
    }

    public String label() {
        return stokesOrder + "/" + rotationSense;
    }
}
