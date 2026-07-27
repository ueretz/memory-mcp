package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;

public record TaskSummary(
        String taskKey,
        String title,
        Task.Source source,
        Task.Status status,
        Instant updatedAt
) {
}
