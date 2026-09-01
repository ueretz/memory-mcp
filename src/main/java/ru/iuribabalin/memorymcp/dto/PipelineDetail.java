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
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public record PipelineParameterView(
            Long id, String name, String label, PipelineParameter.Type type,
            boolean required, String defaultValue, int orderIndex) {
    }

    public record PipelineStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            String promptText, Long assetId, Long referenceAssetId,
            double positionX, double positionY, List<RouteView> routes) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex) {
        }
    }
}
