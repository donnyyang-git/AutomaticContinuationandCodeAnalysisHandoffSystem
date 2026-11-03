package io.handoff.runtime;

public enum FailureInjection {
    NONE,
    PROCESS_KILL_BEFORE_COMMIT,
    PROCESS_KILL_AFTER_RESULT
}
