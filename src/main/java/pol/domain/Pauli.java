package pol.domain;

/**
 * Pauli basis sigma_0..sigma_3 with the physical imaginary sigma_y,
 * in (H,V) ordering:
 *   sigma_0 = I, sigma_1 = sigma_z, sigma_2 = sigma_y, sigma_3 = sigma_x
 * (Cloude/Stokes convention where sigma_1 maps Q, sigma_2 maps U/V mixing).
 */
public final class Pauli {
    private Pauli() {
    }

    public static Cplx[][][] complexPauli() {
        Cplx z = Cplx.ZERO;
        Cplx[][] s0 = {
                {new Cplx(1, 0), z},
                {z, new Cplx(1, 0)}};
        Cplx[][] s1 = {
                {new Cplx(1, 0), z},
                {z, new Cplx(-1, 0)}};
        Cplx[][] s2 = {
                {z, new Cplx(0, -1)},
                {new Cplx(0, 1), z}};
        Cplx[][] s3 = {
                {z, new Cplx(1, 0)},
                {new Cplx(1, 0), z}};
        return new Cplx[][][] {s0, s1, s2, s3};
    }
}
