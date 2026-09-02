package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;

import java.time.Instant;
import java.util.List;

/**
 * One row of a pipeline's run history. Carries enough progress for the dashboard to show, per run,
 * which step(s) are being worked on right now and how far along it is - without loading every step.
 */
public record PipelineRunSummary(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        Instant startedAt, Instant finishedAt, String startedBy,
        Integer currentStepOrderIndex, String currentStepTitle,
        int doneStepCount, int totalStepCount,
        List<ActiveStepView> activeSteps) {

    public PipelineRunSummary {
        activeSteps = activeSteps == null ? List.of() : activeSteps;
    }

    /** Pre-progress shape, kept for older callers and tests. */
    public PipelineRunSummary(Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
                              Instant startedAt, Instant finishedAt, String startedBy) {
        this(id, pipelineId, pipelineSlug, status, startedAt, finishedAt, startedBy, null, null, 0, 0, List.of());
    }

    public record ActiveStepView(int orderIndex, String title) {
    }
}
