package io.questshift.session;

/** Blank alias, unknown seat, or Start without a member. */
public class PartyInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PartyInvalidException(String message) {
        super(message);
    }
}
