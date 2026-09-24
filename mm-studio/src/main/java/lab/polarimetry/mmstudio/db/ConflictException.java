package lab.polarimetry.mmstudio.db;

import java.util.Map;

/** Optimistic-concurrency failure: another commit advanced the branch head. */
public class ConflictException extends RuntimeException {
    public final String branchId;
    public final String expectedHead;
    public final String actualHead;
    public final Map<String, Object> serverState;

    public ConflictException(String branchId, String expectedHead, String actualHead,
                             Map<String, Object> serverState) {
        super("version conflict on branch " + branchId + ": expected head "
                + expectedHead + " but head is " + actualHead);
        this.branchId = branchId;
        this.expectedHead = expectedHead;
        this.actualHead = actualHead;
        this.serverState = serverState;
    }
}
