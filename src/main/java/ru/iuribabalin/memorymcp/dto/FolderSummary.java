package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record FolderSummary(
        String name,
        String description,
        String projectScope,
        String taskKey,
        String parentFolder,
        String createdBy,
        Instant updatedAt
) {
}
