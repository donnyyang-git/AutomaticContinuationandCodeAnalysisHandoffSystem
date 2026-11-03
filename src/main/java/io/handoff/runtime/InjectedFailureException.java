package io.handoff.runtime;

public final class InjectedFailureException extends Exception {
    private final FailureInjection injection;

    public InjectedFailureException(FailureInjection injection) {
        super("Injected failure: " + injection);
        this.injection = injection;
    }

    public FailureInjection injection() {
        return injection;
    }
}
