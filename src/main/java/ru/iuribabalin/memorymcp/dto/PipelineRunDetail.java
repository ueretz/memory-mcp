package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineRunDetail(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        String parametersJson, Instant startedAt, Instant finishedAt, String startedBy,
        Integer currentStepOrderIndex, List<PipelineRunStepView> steps,
        /**
         * Every step Claude should be working on right now. One entry for a sequential pipeline
         * (equal to currentStepOrderIndex); several while parallel branches are in flight - run
         * those concurrently, one sub-agent each. Empty means every path has ended.
         */
        List<Integer> activeStepOrderIndexes) {

    public PipelineRunDetail {
        activeStepOrderIndexes = activeStepOrderIndexes == null
                ? (currentStepOrderIndex == null ? List.of() : List.of(currentStepOrderIndex))
                : activeStepOrderIndexes;
    }

    /** Pre-V18 shape: a single current step. */
    public PipelineRunDetail(Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
                             String parametersJson, Instant startedAt, Instant finishedAt, String startedBy,
                             Integer currentStepOrderIndex, List<PipelineRunStepView> steps) {
        this(id, pipelineId, pipelineSlug, status, parametersJson, startedAt, finishedAt, startedBy,
                currentStepOrderIndex, steps, null);
    }

    public record PipelineRunStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt,
            String resolvedInstructionText) {
    }
}
