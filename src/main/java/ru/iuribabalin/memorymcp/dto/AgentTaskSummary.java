package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.AgentTask;

import java.time.Instant;

public record AgentTaskSummary(
        Long id,
        String title,
        AgentTask.Type type,
        AgentTask.Status status,
        String description,
        Instant updatedAt
) {
}
