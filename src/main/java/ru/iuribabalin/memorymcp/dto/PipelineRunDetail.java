package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineRunDetail(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        String parametersJson, Instant startedAt, Instant finishedAt, String startedBy,
        Integer currentStepOrderIndex, List<PipelineRunStepView> steps) {

    public record PipelineRunStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt) {
    }
}
