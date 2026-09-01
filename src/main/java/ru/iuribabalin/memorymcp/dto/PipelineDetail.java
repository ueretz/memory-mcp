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
            double positionX, double positionY, List<RouteView> routes,
            List<OutputView> outputs, List<DataLinkView> dataLinksOut,
            PipelineStep.ConditionOperator conditionOperator, String conditionValue) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex) {
        }

        public record OutputView(Long id, String name) {
        }

        public record DataLinkView(Long id, String token, String sourceOutputName,
                                    Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
}
