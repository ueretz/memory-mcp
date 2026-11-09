package ru.iuribabalin.memorymcp.dto;

import java.util.List;

public record PipelineExportEnvelope(
        int formatVersion,
        PipelineUpsertRequest pipeline,
        List<String> warnings
) {
    public PipelineExportEnvelope {
        warnings = warnings == null ? List.of() : warnings;
    }
}
