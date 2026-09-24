package pol.domain;

/**
 * Sign convention for rotating optical elements (positive angle direction
 * when viewed along the propagation axis). Enters the fingerprint and flips
 * the sign of U/V mixing for encoded states.
 */
public enum RotationConvention {
    RIGHT_HANDED("RH", "positive rotation counter-clockwise looking against propagation"),
    LEFT_HANDED("LH", "positive rotation clockwise looking against propagation");

    private final String code;
    private final String description;

    RotationConvention(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public int sign() {
        return this == RIGHT_HANDED ? 1 : -1;
    }

    public String fingerprint() {
        return "rotation=" + code;
    }

    public static RotationConvention fromCode(String code) {
        if (code == null) {
            return RIGHT_HANDED;
        }
        return switch (code.trim().toUpperCase()) {
            case "RH", "R", "RIGHT", "+" -> RIGHT_HANDED;
            case "LH", "L", "LEFT", "-" -> LEFT_HANDED;
            default -> throw new IllegalArgumentException("unknown rotation convention: " + code);
        };
    }
}
