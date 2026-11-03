package io.handoff.runtime;

public final class RuntimeException extends Exception {
    private final String code;

    public RuntimeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
