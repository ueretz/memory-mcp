package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.PipelineExportEnvelope;
import ru.iuribabalin.memorymcp.dto.PipelineImportResponse;
import ru.iuribabalin.memorymcp.service.PipelineExportService;

@RestController
public class PipelineExportController {

    private final PipelineExportService exportService;

    public PipelineExportController(PipelineExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/api/pipelines/{slug}/export")
    public ResponseEntity<PipelineExportEnvelope> export(@PathVariable String slug) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(slug + ".pipeline.json").build().toString())
                .body(exportService.export(slug));
    }

    @PostMapping("/api/pipelines/import")
    public ResponseEntity<PipelineImportResponse> importPipeline(
            @RequestBody PipelineExportEnvelope envelope,
            @RequestParam(required = false) String slug) {
        PipelineImportResponse imported = exportService.importPipeline(envelope, slug);
        return ResponseEntity.status(HttpStatus.CREATED).body(imported);
    }
}
