package lab.polarimetry.mmstudio.ingest;

import java.util.List;

/** Thrown on any import failure; callers roll back the whole transaction. */
public class IngestException extends RuntimeException {
    private final List<String> diagnostics;

    public IngestException(String message, List<String> diagnostics) {
        super(message + (diagnostics.isEmpty() ? "" : ": " + String.join("; ", diagnostics)));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<String> diagnostics() { return diagnostics; }
}
