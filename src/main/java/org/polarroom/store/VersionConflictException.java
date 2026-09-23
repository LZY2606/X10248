package org.polarroom.store;

/** Thrown when an append races with another committer on the same branch. */
public class VersionConflictException extends RuntimeException {
    public final String expectedHead;
    public final String actualHead;

    public VersionConflictException(String expectedHead, String actualHead) {
        super("branch head moved: expected=" + expectedHead + " actual=" + actualHead);
        this.expectedHead = expectedHead;
        this.actualHead = actualHead;
    }
}
