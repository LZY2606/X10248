package polopt.domain;

import java.util.List;

/**
 * Hardware-level description of a polarization state (generator or analyzer),
 * kept separate from measured intensities. Angles are stored in their original
 * representation (degrees) together with the rotation convention and basis.
 */
public record StateEncoding(
        String id,
        String role,
        String basis,
        String rotationConvention,
        List<Element> elements,
        List<Double> inputStokes) {

    public record Element(String kind, double axisDeg) {
    }
}
