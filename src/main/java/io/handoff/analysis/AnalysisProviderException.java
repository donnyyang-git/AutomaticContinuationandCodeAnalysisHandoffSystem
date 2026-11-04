package io.handoff.analysis;

public final class AnalysisProviderException extends Exception {
    private final String code;

    public AnalysisProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AnalysisProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}