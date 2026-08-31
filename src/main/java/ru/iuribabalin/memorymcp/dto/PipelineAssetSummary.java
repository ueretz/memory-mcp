package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record PipelineAssetSummary(Long id, String filename, String contentType, long sizeBytes, Instant createdAt) {
}
