package one.neo.graph;

/** Thrown when a running sync job is cancelled; the slot cursor is left where it was. */
public class CancelledException extends RuntimeException {
    public CancelledException(String message) {
        super(message);
    }
}