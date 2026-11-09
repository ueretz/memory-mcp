package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;

public record TaskDetail(
        String taskKey,
        String title,
        Task.Source source,
        Task.Status status,
        SpecSummary spec,
        Progress progress,
        Instant createdAt,
        Instant updatedAt
) {
    public record Progress(long done, long total, int percent) {
    }
}
