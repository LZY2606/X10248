package polwork.domain;

/**
 * One polar decomposition candidate. Multiple candidates (Lu-Chipman forward,
 * reverse order, Cloude spectral) are retained; when the solution is not
 * unique every legitimate candidate is kept and the reviewer chooses.
 */
public final class DecompositionCandidate {
    public final String method;
    public final double[][] diattenuator;
    public final double[][] retarder;
    public final double[][] depolarizer;
    public final double retardanceRad;
    public final double diattenuation;
    public final String diagnostic;
    public final boolean unique;

    public DecompositionCandidate(String method, double[][] diattenuator, double[][] retarder,
                                  double[][] depolarizer, double retardanceRad,
                                  double diattenuation, String diagnostic, boolean unique) {
        this.method = method;
        this.diattenuator = diattenuator;
        this.retarder = retarder;
        this.depolarizer = depolarizer;
        this.retardanceRad = retardanceRad;
        this.diattenuation = diattenuation;
        this.diagnostic = diagnostic;
        this.unique = unique;
    }
}
