package poloptics.domain;

import java.util.List;

public class ConflictException extends RuntimeException {
    public final long expectedVersion;
    public final long actualVersion;
    public final List<String> divergedEvents;
    public ConflictException(long expected, long actual, List<String> diverged) {
        super("版本冲突：期望分支版本 " + expected + "，当前为 " + actual);
        this.expectedVersion = expected;
        this.actualVersion = actual;
        this.divergedEvents = diverged;
    }
}
