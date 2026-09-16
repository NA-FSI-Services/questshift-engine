package io.questshift.session;

/** Second Start or active import while a party is already live. */
public class PartyActiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public PartyActiveException(String code) {
        super("A party is already running. Join with " + code + ".");
        this.code = code;
    }

    public String getJoinCode() {
        return code;
    }
}
