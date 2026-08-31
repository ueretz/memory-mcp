package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record PipelineSummary(
        Long id,
        String slug,
        String name,
        String description,
        String projectScope,
        int parameterCount,
        int stepCount,
        String createdBy,
        Instant updatedAt
) {
}
