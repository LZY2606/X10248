package polbench.store;

/** Optimistic concurrency conflict: client saw baseVersion but branch head moved. */
public final class ConflictException extends RuntimeException {
    public final long baseVersion;
    public final long currentHead;

    public ConflictException(long baseVersion, long currentHead) {
        super("version conflict: base=" + baseVersion + " head=" + currentHead);
        this.baseVersion = baseVersion;
        this.currentHead = currentHead;
    }
}
