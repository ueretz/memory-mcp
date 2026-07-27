package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

public record SaveMemoryRequest(
        String name,
        MemoryNode.Type type,
        String description,
        String content,
        String projectScope,
        String taskKey,
        String filePath
) {
}
