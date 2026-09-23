package polarmueller.store;

import java.util.List;

/** Raised when a save arrives with a stale base version (two windows saving). */
public final class ConflictException extends RuntimeException {
    public final int currentVersion;
    public final int baseVersion;
    public final List<String> interveningEvents;
    public final String attemptedChange;

    public ConflictException(int currentVersion, int baseVersion,
                             List<String> interveningEvents, String attemptedChange) {
        super("version conflict: base=" + baseVersion + " current=" + currentVersion);
        this.currentVersion = currentVersion;
        this.baseVersion = baseVersion;
        this.interveningEvents = interveningEvents;
        this.attemptedChange = attemptedChange;
    }
}
