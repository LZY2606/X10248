package io.polwork.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Content fingerprints. The fingerprint of an optics encoding always includes the
 * declared Stokes component order and the rotation sign convention, so that
 * reordering S1/S2 or flipping the rotation direction cannot silently reuse a
 * previously computed result.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String sha256Hex(String canonicalText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortFingerprint(String canonicalText) {
        return sha256Hex(canonicalText).substring(0, 12);
    }

    public static String opticsFingerprint(SourceBundles.OpticsEncoding optics) {
        StringBuilder sb = new StringBuilder("OPTICS|");
        sb.append("order=").append(optics.stokesOrder()).append('|');
        sb.append("rotSign=").append(optics.rotationSign()).append('|');
        sb.append("angleBasis=").append(optics.angleBasis()).append('|');
        sb.append("psg=");
        for (Stokes s : optics.psg()) {
            sb.append(s.id()).append(formatArray(s.ordered())).append(';');
        }
        sb.append("|psa=");
        for (Stokes s : optics.psa()) {
            sb.append(s.id()).append(formatArray(s.ordered())).append(';');
        }
        return sha256Hex(sb.toString());
    }

    public static String intensityFingerprint(SourceBundles.IntensityBundle b) {
        StringBuilder sb = new StringBuilder("INTENSITY|v=").append(b.version()).append('|');
        for (var r : b.readings()) {
            sb.append(r.psgId()).append(',').append(r.psaId()).append(',')
                    .append(r.counts() == null ? "null" : r.counts().rawText()).append(';');
        }
        return sha256Hex(sb.toString());
    }

    public static String darkFingerprint(SourceBundles.DarkBundle b) {
        StringBuilder sb = new StringBuilder("DARK|v=").append(b.version()).append('|');
        for (var r : b.readings()) {
            sb.append(r.psaId()).append(':')
                    .append(r.counts() == null ? "null" : r.counts().rawText()).append(';');
        }
        return sha256Hex(sb.toString());
    }

    public static String referenceFingerprint(SourceBundles.ReferenceSample r) {
        StringBuilder sb = new StringBuilder("REFERENCE|v=").append(r.version()).append('|')
                .append("m00t=").append(r.m00Transmittance()).append('|')
                .append("basis=").append(r.basis()).append('|');
        for (double[] row : r.mueller()) {
            sb.append(formatArray(row));
        }
        return sha256Hex(sb.toString());
    }

    public static String instrumentFingerprint(SourceBundles.InstrumentMatrix i) {
        StringBuilder sb = new StringBuilder("INSTRUMENT|v=").append(i.version()).append('|')
                .append("order=").append(i.stokesOrder()).append("|rotSign=")
                .append(i.rotationSign()).append("|basis=").append(i.basis()).append('|');
        for (double[] row : i.w()) {
            sb.append(formatArray(row));
        }
        return sha256Hex(sb.toString());
    }

    public static String wavelengthFingerprint(SourceBundles.Wavelength w) {
        return sha256Hex("WAVELENGTH|" + (w.quantity() == null ? "null" : w.quantity().rawText())
                + "|basis=" + (w.quantity() == null ? null : w.quantity().basis()));
    }

    public static String covarianceFingerprint(SourceBundles.CovarianceBundle c) {
        StringBuilder sb = new StringBuilder("COVARIANCE|v=").append(c.version()).append('|')
                .append("correlated=").append(c.correlated()).append('|');
        for (double[] row : c.matrix()) {
            sb.append(formatArray(row));
        }
        return sha256Hex(sb.toString());
    }

    static String formatArray(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.10g", v[i]));
        }
        return sb.append(']').toString();
    }
}
