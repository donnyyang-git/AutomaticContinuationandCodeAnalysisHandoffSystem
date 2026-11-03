package io.handoff.runtime;

public record WorkItem(
        String operationId,
        String taskId,
        String kind,
        String subject,
        String status,
        int attemptCount,
        String inputSha256) {}
