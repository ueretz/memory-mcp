package ru.iuribabalin.memorymcp.dto;

import java.util.List;

public record PipelineImportResponse(
        PipelineDetail pipeline,
        List<String> warnings
) {
}
