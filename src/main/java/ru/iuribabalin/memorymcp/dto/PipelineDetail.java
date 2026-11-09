package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineDetail(
        Long id,
        String slug,
        String name,
        String description,
        String projectScope,
        List<PipelineParameterView> parameters,
        List<PipelineStepView> steps,
        List<PipelineParameterLinkView> parameterLinks,
        List<PipelineVariableView> variables,
        List<PipelineVariableLinkView> variableLinks,
        /** Null only while a pipeline has no steps yet. */
        Integer startStepOrderIndex,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public record PipelineParameterView(
            Long id, String name, String label, PipelineParameter.Type type,
            boolean required, String defaultValue, int orderIndex) {
    }

    /**
     * Exactly one of {@code targetStepOrderIndex} / {@code targetVariableName} is set - a parameter
     * either feeds a step or helps build a variable.
     */
    public record PipelineParameterLinkView(Long id, String token, String parameterName,
                                            Integer targetStepOrderIndex, String targetStepTitle,
                                            String targetVariableName) {
    }

    public record PipelineVariableView(Long id, String name, String value, int orderIndex) {
    }

    /**
     * Exactly one of {@code targetStepOrderIndex} / {@code targetVariableName} is set - a variable's
     * value either feeds a step or helps build another variable.
     */
    public record PipelineVariableLinkView(Long id, String token, String variableName,
                                           Integer targetStepOrderIndex, String targetStepTitle,
                                           String targetVariableName) {
    }

    public record PipelineStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            String promptText, Long assetId, Long referenceAssetId,
            double positionX, double positionY, List<RouteView> routes,
            List<OutputView> outputs, List<DataLinkView> dataLinksOut,
            PipelineStep.ConditionOperator conditionOperator, String conditionValue,
            PipelineStep.ConditionMode conditionMode, Integer maxIterations,
            PipelineStep.WatchSource watchSource, PipelineStep.WatchAspect watchAspect,
            Integer checkIntervalSeconds, Integer watchTimeoutSeconds,
            /** orderIndex of the step opening the region this one closes (LOOP_END, ORCHESTRATOR_END). */
            Integer regionStartStepOrderIndex,
            PipelineStep.DelegationHint delegationHint) {

        /** Pre-V23 shape: no region or delegation fields. */
        public PipelineStepView(
                Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
                String promptText, Long assetId, Long referenceAssetId,
                double positionX, double positionY, List<RouteView> routes,
                List<OutputView> outputs, List<DataLinkView> dataLinksOut,
                PipelineStep.ConditionOperator conditionOperator, String conditionValue,
                PipelineStep.ConditionMode conditionMode, Integer maxIterations,
                PipelineStep.WatchSource watchSource, PipelineStep.WatchAspect watchAspect,
                Integer checkIntervalSeconds, Integer watchTimeoutSeconds) {
            this(id, orderIndex, title, contentType, promptText, assetId, referenceAssetId,
                    positionX, positionY, routes, outputs, dataLinksOut, conditionOperator, conditionValue,
                    conditionMode, maxIterations, watchSource, watchAspect, checkIntervalSeconds,
                    watchTimeoutSeconds, null, null);
        }

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex) {
        }

        public record OutputView(Long id, String name) {
        }

        public record DataLinkView(Long id, String token, String sourceOutputName,
                                    Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
}
