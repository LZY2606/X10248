package pol.domain;

/** Wavelength with original token, normalized value in nanometres and unit. */
public record WavelengthPoint(String id, String token, Double nanometres, String unit, String diagnostic) {
}
