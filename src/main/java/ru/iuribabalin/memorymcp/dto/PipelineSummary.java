package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;

import java.time.Instant;

public record PipelineSummary(
        Long id,
        String slug,
        String name,
        String description,
        String projectScope,
        int parameterCount,
        int stepCount,
        String createdBy,
        Instant updatedAt,
        /** Newest run, so the list can show a status at a glance; null when never run. */
        Long lastRunId,
        PipelineRun.Status lastRunStatus,
        Instant lastRunStartedAt
) {
    /** Pre-"last run" shape. */
    public PipelineSummary(Long id, String slug, String name, String description, String projectScope,
                           int parameterCount, int stepCount, String createdBy, Instant updatedAt) {
        this(id, slug, name, description, projectScope, parameterCount, stepCount, createdBy, updatedAt, null, null, null);
    }
}
