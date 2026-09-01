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
        List<StepRequest> steps
) {
    public record ParameterRequest(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepRequest(
            String title, PipelineStep.ContentType contentType, String promptText,
            Long assetId, Long referenceAssetId, double positionX, double positionY,
            List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut,
            PipelineStep.ConditionOperator conditionOperator, String conditionValue) {

        public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
        }

        public record OutputRequest(String name) {
        }

        public record DataLinkRequest(String token, String sourceOutputName, Integer targetStepIndex) {
        }
    }
}
