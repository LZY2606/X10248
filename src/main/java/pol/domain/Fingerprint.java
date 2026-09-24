package pol.domain;

/**
 * Analysis fingerprint: every convention/version that can change a derived
 * result. Rotation convention and Stokes order are explicit first-class
 * members (they must enter the fingerprint), alongside data versions.
 */
public record Fingerprint(
        String stokesLabel,
        String rotationCode,
        String darkVersion,
        String referenceVersion,
        String instrumentVersion,
        String covarianceVersion,
        String wavelengthId,
        String estimatorMode,
        double rcond) {

    public String text() {
        return String.join("|",
                stokesLabel,
                rotationCode,
                "dark=" + darkVersion,
                "ref=" + referenceVersion,
                "inst=" + instrumentVersion,
                "cov=" + covarianceVersion,
                "wl=" + wavelengthId,
                "mode=" + estimatorMode,
                "rcond=" + rcond);
    }
}
