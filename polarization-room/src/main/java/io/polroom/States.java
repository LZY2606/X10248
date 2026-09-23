package io.polroom;

/**
 * Optical state encodings. States are kept both in their raw representation (the imported
 * angular recipe plus its declared Stokes order) and in canonical IQUV Stokes vectors.
 * The rotation sign convention is part of the recipe interpretation and therefore of
 * every derived fingerprint.
 */
public final class States {

    public record Recipe(String type, double angleRad, String stokesOrderRaw, int rotationSign) {}

    private States() {}

    /** Build a canonical IQUV Stokes vector for a linear/analyser state at physical angle theta. */
    public static double[] stokes(Recipe recipe) {
        double theta = recipe.rotationSign() * recipe.angleRad();
        double[] raw;
        switch (recipe.type()) {
            case "H" -> raw = new double[] {1, 1, 0, 0};
            case "V" -> raw = new double[] {1, -1, 0, 0};
            case "P" -> raw = new double[] {1, 0, 1, 0};
            case "M" -> raw = new double[] {1, 0, -1, 0};
            case "R" -> raw = new double[] {1, 0, 0, 1};
            case "L" -> raw = new double[] {1, 0, 0, -1};
            case "LIN" -> raw = new double[] {1, Math.cos(2 * theta), Math.sin(2 * theta), 0};
            default -> throw new IllegalArgumentException("Unknown state type: " + recipe.type());
        }
        return new Conventions(recipe.stokesOrderRaw(), recipe.rotationSign()).toCanonical(raw);
    }
}
