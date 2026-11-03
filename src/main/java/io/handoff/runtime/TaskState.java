package io.handoff.runtime;

import java.util.EnumSet;
import java.util.Map;

public enum TaskState {
    CREATED,
    INDEXING,
    ANALYZING,
    CHECKPOINTING,
    PAUSED,
    INTERRUPTED,
    RESUMING,
    REQUIRES_REVIEW,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Map<TaskState, EnumSet<TaskState>> LEGAL = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(INDEXING, CANCELLED, FAILED)),
            Map.entry(INDEXING, EnumSet.of(ANALYZING, PAUSED, INTERRUPTED, REQUIRES_REVIEW, FAILED, CANCELLED)),
            Map.entry(ANALYZING, EnumSet.of(CHECKPOINTING, PAUSED, INTERRUPTED, REQUIRES_REVIEW, FAILED, CANCELLED)),
            Map.entry(CHECKPOINTING, EnumSet.of(ANALYZING, PAUSED, INTERRUPTED, REQUIRES_REVIEW, COMPLETED, FAILED)),
            Map.entry(PAUSED, EnumSet.of(RESUMING, CANCELLED)),
            Map.entry(INTERRUPTED, EnumSet.of(RESUMING, REQUIRES_REVIEW, CANCELLED)),
            Map.entry(RESUMING, EnumSet.of(INDEXING, ANALYZING, REQUIRES_REVIEW, FAILED)),
            Map.entry(REQUIRES_REVIEW, EnumSet.of(RESUMING, FAILED, CANCELLED)),
            Map.entry(COMPLETED, EnumSet.noneOf(TaskState.class)),
            Map.entry(FAILED, EnumSet.noneOf(TaskState.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(TaskState.class)));

    public boolean canTransitionTo(TaskState target) {
        return LEGAL.get(this).contains(target);
    }
}
