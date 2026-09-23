package polroom.store;

/** Raised on an optimistic-lock conflict between two concurrent review windows. */
public class ConflictException extends RuntimeException {

    public final String branchId;
    public final long expectedRevision;
    public final long actualRevision;
    public final String serverEventId;

    public ConflictException(String branchId, long expectedRevision, long actualRevision,
                             String serverEventId) {
        super("branch " + branchId + " at revision " + actualRevision
                + ", expected " + expectedRevision);
        this.branchId = branchId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
        this.serverEventId = serverEventId;
    }
}
