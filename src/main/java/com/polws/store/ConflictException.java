package com.polws.store;

import java.util.Map;

/** Optimistic concurrency conflict: branch head moved between view and commit. */
public final class ConflictException extends RuntimeException {
    public final String branch;
    public final long expectedHead;
    public final long actualHead;
    public final Map<String, Object> ourPayload;
    public final Map<String, Object> theirPayload;
    /** Marker payload recorded when a save is denied due to the conflict (accepted and rejected results both kept). */
    public final Map<String, Object> deniedPayload;

    public ConflictException(String branch, long expectedHead, long actualHead,
                             Map<String, Object> ourPayload, Map<String, Object> theirPayload,
                             Map<String, Object> deniedPayload) {
        super("version conflict on branch " + branch + ": expected head " + expectedHead + " but head is " + actualHead);
        this.branch = branch;
        this.expectedHead = expectedHead;
        this.actualHead = actualHead;
        this.ourPayload = ourPayload;
        this.theirPayload = theirPayload;
        this.deniedPayload = deniedPayload;
    }
}
