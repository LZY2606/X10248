package mmlab.optics;

import java.util.Arrays;

/**
 * Ordering of the Stokes components in an encoded state vector. Different
 * instruments export (I,Q,U,V) or (I,U,Q,V); the permutation is part of the
 * derivation fingerprint.
 */
public final class StokesOrder {
    public static final StokesOrder IQUV = new StokesOrder("IQUV", new int[]{0, 1, 2, 3});
    public static final StokesOrder IUQV = new StokesOrder("IUQV", new int[]{0, 2, 1, 3});

    public final String name;
    /** permutation[logicalIndex] = position in the encoded vector */
    public final int[] permutation;

    public StokesOrder(String name, int[] permutation) {
        if (permutation.length != 4) {
            throw new IllegalArgumentException("Stokes order needs 4 components");
        }
        this.name = name;
        this.permutation = permutation.clone();
    }

    public static StokesOrder byName(String name) {
        return switch (name) {
            case "IQUV" -> IQUV;
            case "IUQV" -> IUQV;
            default -> new StokesOrder(name, parsePermutation(name));
        };
    }

    private static int[] parsePermutation(String name) {
        String[] parts = name.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("unknown Stokes order: " + name);
        }
        int[] p = new int[4];
        String labels = "IQUV";
        for (int i = 0; i < 4; i++) {
            int idx = labels.indexOf(parts[i].trim().charAt(0));
            if (idx < 0) {
                throw new IllegalArgumentException("unknown Stokes label: " + parts[i]);
            }
            p[i] = idx;
        }
        return p;
    }

    /** Move an encoded vector into canonical (I,Q,U,V) order. */
    public double[] toCanonical(double[] encoded) {
        double[] out = new double[4];
        for (int logical = 0; logical < 4; logical++) {
            out[logical] = encoded[permutation[logical]];
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StokesOrder that)) {
            return false;
        }
        return name.equals(that.name) && Arrays.equals(permutation, that.permutation);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(permutation);
    }
}
