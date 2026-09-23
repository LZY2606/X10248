package pol.domain;

/** Tiny shared diagnostic carrier for parsing without importing the service state. */
public final class State {
    public static final class DiagLike {
        public final String code;
        public final String message;
        public final String location;

        public DiagLike(String code, String message, String location) {
            this.code = code;
            this.message = message;
            this.location = location;
        }
    }
}
