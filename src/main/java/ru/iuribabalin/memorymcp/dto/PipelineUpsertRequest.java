package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

public record PipelineUpsertRequest(
        String slug,
        String name,
        String description,
        String projectScope,
        List<ParameterRequest> parameters,
        List<StepRequest> steps,
        /**
         * Wires from a pipeline parameter into a step's {@code {{data:token}}}. They live at the
         * top level (not inside a step) because their source is a parameter, not a step.
         */
        List<ParameterLinkRequest> parameterLinks,
        /**
         * Named constants on the input plate. Not steps: a variable does no work, so it has no
         * place in the transition graph (V24).
         */
        List<VariableRequest> variables,
        /**
         * Wires out of a variable - into a step's {@code {{data:token}}} or into another variable's
         * value. Top level for the same reason as {@code parameterLinks}: the source is not a step.
         */
        List<VariableLinkRequest> variableLinks,
        /**
         * The step a run begins at. Null means "work it out from the graph" (the pre-V24 rule: the
         * one step nothing routes into) and is what older callers and older exports send; the board
         * always states the start explicitly, which is the whole point of the input plate's start
         * port.
         */
        Integer startStepIndex
) {
    public PipelineUpsertRequest {
        parameterLinks = parameterLinks == null ? List.of() : parameterLinks;
        variables = variables == null ? List.of() : variables;
        variableLinks = variableLinks == null ? List.of() : variableLinks;
    }

    /** Pre-V24 shape: no variables of their own, no explicit start. */
    public PipelineUpsertRequest(String slug, String name, String description, String projectScope,
                                 List<ParameterRequest> parameters, List<StepRequest> steps,
                                 List<ParameterLinkRequest> parameterLinks) {
        this(slug, name, description, projectScope, parameters, steps, parameterLinks, List.of(), List.of(), null);
    }

    /** Pre-V17 shape: no parameter links. */
    public PipelineUpsertRequest(String slug, String name, String description, String projectScope,
                                 List<ParameterRequest> parameters, List<StepRequest> steps) {
        this(slug, name, description, projectScope, parameters, steps, List.of());
    }

    public record ParameterRequest(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    /** Exactly one of {@code targetStepIndex} / {@code targetVariableName} is set. */
    public record ParameterLinkRequest(String token, String parameterName, Integer targetStepIndex,
                                       String targetVariableName) {

        /** Pre-V24 shape: a parameter could only be wired into a step. */
        public ParameterLinkRequest(String token, String parameterName, Integer targetStepIndex) {
            this(token, parameterName, targetStepIndex, null);
        }
    }

    public record VariableRequest(String name, String value) {
    }

    /** Exactly one of {@code targetStepIndex} / {@code targetVariableName} is set. */
    public record VariableLinkRequest(String token, String variableName, Integer targetStepIndex,
                                      String targetVariableName) {
    }

    public record StepRequest(
            String title, PipelineStep.ContentType contentType, String promptText,
            Long assetId, Long referenceAssetId, double positionX, double positionY,
            List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut,
            PipelineStep.ConditionOperator conditionOperator, String conditionValue,
            PipelineStep.ConditionMode conditionMode, Integer maxIterations,
            PipelineStep.WatchSource watchSource, PipelineStep.WatchAspect watchAspect,
            Integer checkIntervalSeconds, Integer watchTimeoutSeconds) {

        public StepRequest {
            conditionMode = conditionMode == null ? PipelineStep.ConditionMode.VALUE : conditionMode;
        }

        /** Pre-V22 shape: no watcher fields. */
        public StepRequest(String title, PipelineStep.ContentType contentType, String promptText,
                           Long assetId, Long referenceAssetId, double positionX, double positionY,
                           List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut,
                           PipelineStep.ConditionOperator conditionOperator, String conditionValue,
                           PipelineStep.ConditionMode conditionMode, Integer maxIterations) {
            this(title, contentType, promptText, assetId, referenceAssetId, positionX, positionY,
                    routes, outputs, dataLinksOut, conditionOperator, conditionValue, conditionMode, maxIterations,
                    null, null, null, null);
        }

        public StepRequest(String title, PipelineStep.ContentType contentType, String promptText,
                           Long assetId, Long referenceAssetId, double positionX, double positionY,
                           List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut,
                           PipelineStep.ConditionOperator conditionOperator, String conditionValue, Integer maxIterations) {
            this(title, contentType, promptText, assetId, referenceAssetId, positionX, positionY,
                    routes, outputs, dataLinksOut, conditionOperator, conditionValue, PipelineStep.ConditionMode.VALUE, maxIterations);
        }

        public StepRequest(String title, PipelineStep.ContentType contentType, String promptText,
                           Long assetId, Long referenceAssetId, double positionX, double positionY,
                           List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut,
                           PipelineStep.ConditionOperator conditionOperator, String conditionValue) {
            this(title, contentType, promptText, assetId, referenceAssetId, positionX, positionY,
                    routes, outputs, dataLinksOut, conditionOperator, conditionValue, null);
        }

        public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
        }

        public record OutputRequest(String name) {
        }

        public record DataLinkRequest(String token, String sourceOutputName, Integer targetStepIndex) {
        }
    }
}
