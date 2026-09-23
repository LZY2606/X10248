package polwork.domain;

import java.util.List;

/**
 * Encoding of one generator (PSG) or analyzer (PSA) state.
 * The Stokes vector is stored in the *physical* order (S0,S1,S2,S3) and is
 * dimensionless; {@code componentBasis} records that fact explicitly. Angles
 * keep both the original representation and a normalized radian value.
 */
public final class OpticalState {
    public final String name;
    public final List<Quantity> angles;   // element angles, raw text + rad
    public final List<Double> stokesPhysical; // always S0,S1,S2,S3
    public final String componentBasis;  // "Stokes(S0,S1,S2,S3), dimensionless"

    public OpticalState(String name, List<Quantity> angles, List<Double> stokesPhysical) {
        if (stokesPhysical.size() != 4) {
            throw new IllegalArgumentException("state " + name + " needs 4 Stokes components");
        }
        this.name = name;
        this.angles = angles;
        this.stokesPhysical = stokesPhysical;
        this.componentBasis = "Stokes(S0,S1,S2,S3), dimensionless";
    }
}
