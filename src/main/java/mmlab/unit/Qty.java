package mmlab.unit;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A physical quantity carrying its original textual representation and unit.
 * The original representation is never discarded for plotting; normalisation
 * happens only in the derivation layer.
 */
public final class Qty {
    @JsonProperty("value")
    public final double value;
    @JsonProperty("unit")
    public final String unit;
    @JsonProperty("raw")
    public final String raw;
    @JsonProperty("frame")
    public final String coordinateFrame;

    public Qty(@JsonProperty("value") double value,
               @JsonProperty("unit") String unit,
               @JsonProperty("raw") String raw,
               @JsonProperty("frame") String coordinateFrame) {
        this.value = value;
        this.unit = unit == null ? "" : unit;
        this.raw = raw == null ? "" : raw;
        this.coordinateFrame = coordinateFrame == null ? "" : coordinateFrame;
    }

    public static Qty of(double value, String unit) {
        return new Qty(value, unit, String.valueOf(value), "");
    }

    public static Qty of(double value, String unit, String frame) {
        return new Qty(value, unit, String.valueOf(value), frame);
    }

    @Override
    public String toString() {
        return value + " " + unit + (coordinateFrame.isEmpty() ? "" : " [" + coordinateFrame + "]");
    }
}
