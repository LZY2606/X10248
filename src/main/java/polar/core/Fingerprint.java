package polar.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Derivation fingerprint. Stokes order and rotation convention are explicit
 * inputs, so any convention change yields a different fingerprint and derived
 * results computed under different conventions can never be confused.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(Stokes.Conventions conventions,
                            String wavelengthVersion,
                            String covarianceVersion,
                            List<String> sourceHashes,
                            String ruleVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("stokesOrder=").append(conventions.order()).append(';');
        sb.append("rotationConvention=").append(conventions.rotation()).append(';');
        sb.append("wavelength=").append(wavelengthVersion).append(';');
        sb.append("covariance=").append(covarianceVersion).append(';');
        sb.append("rules=").append(ruleVersion).append(';');
        sourceHashes.stream().sorted().forEach(h -> sb.append("src=").append(h).append(';'));
        return sha256(sb.toString());
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
