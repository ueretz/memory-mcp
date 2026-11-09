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
            String resolvedInstructionText, int executionCount,
            /**
             * WATCHER steps only (null elsewhere). {@code watchExpired} is derived on every read,
             * not stored, so a run whose agent session died still shows an overdue watcher.
             */
            Integer watchCheckCount, Instant watchLastCheckedAt, String watchLastState,
            Instant watchDeadlineAt, Boolean watchExpired,
            /**
             * LOOP and PROMPT_LOOP steps only (null elsewhere): which iteration this step is on and
             * how many it gets. A resumed run reads these instead of counting from zero.
             */
            Integer loopIteration, Integer loopMaxIterations,
            /**
             * Whether this step sits inside an ORCHESTRATOR region - i.e. whether the leading model
             * should delegate it to a cheaper one and then accept the result - together with the
             * author's hint about who should get it and how many attempts it gets.
             */
            Boolean orchestrated, PipelineStep.DelegationHint delegationHint, Integer orchestratorMaxAttempts) {

        /** Pre-V24 shape: no loop iteration or orchestration fields. */
        public PipelineRunStepView(Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
                                   PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt,
                                   String resolvedInstructionText, int executionCount,
                                   Integer watchCheckCount, Instant watchLastCheckedAt, String watchLastState,
                                   Instant watchDeadlineAt, Boolean watchExpired) {
            this(id, orderIndex, title, contentType, status, note, startedAt, finishedAt,
                    resolvedInstructionText, executionCount, watchCheckCount, watchLastCheckedAt,
                    watchLastState, watchDeadlineAt, watchExpired, null, null, null, null, null);
        }

        /** Pre-V22 shape: no watcher state. */
        public PipelineRunStepView(Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
                                   PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt,
                                   String resolvedInstructionText, int executionCount) {
            this(id, orderIndex, title, contentType, status, note, startedAt, finishedAt,
                    resolvedInstructionText, executionCount, null, null, null, null, null);
        }
    }
}
