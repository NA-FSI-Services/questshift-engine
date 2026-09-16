package io.questshift.session;

/** Duplicate alias, full party, or join after the hour ended. */
public class PartyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public PartyConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
