package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.Instant;
import java.util.List;

public record MemoryEntryDetail(
        String name,
        MemoryNode.Type type,
        String description,
        String content,
        String projectScope,
        String taskKey,
        String filePath,
        Instant createdAt,
        Instant updatedAt,
        List<MemoryEntrySummary> linkedTo,
        List<MemoryEntrySummary> linkedFrom,
        List<String> warnings
) {
}
