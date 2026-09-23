package polwork.domain;

/** One spectral point. Normalized value always nanometres; raw kept too. */
public final class WavelengthPoint {
    public final String id;
    public final Quantity value; // nm normalized

    public WavelengthPoint(String id, Quantity value) {
        this.id = id;
        this.value = value;
    }
}
