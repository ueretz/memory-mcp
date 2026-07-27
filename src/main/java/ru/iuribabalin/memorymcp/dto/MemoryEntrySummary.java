package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.Instant;

public record MemoryEntrySummary(
        String name,
        MemoryNode.Type type,
        String description,
        String projectScope,
        String taskKey,
        String filePath,
        Instant updatedAt
) {
}
