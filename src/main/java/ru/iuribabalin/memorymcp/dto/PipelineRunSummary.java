package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;

import java.time.Instant;

public record PipelineRunSummary(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        Instant startedAt, Instant finishedAt, String startedBy) {
}
