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
            List<RouteRequest> routes) {

        public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
        }
    }
}
