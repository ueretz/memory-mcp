package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;

public record TaskDetail(
        String taskKey,
        String title,
        Task.Source source,
        Task.Status status,
        Spec spec,
        Progress progress,
        Instant createdAt,
        Instant updatedAt
) {
    public record Spec(String name, String description, Instant updatedAt) {
    }

    public record Progress(long done, long total, int percent) {
    }
}
