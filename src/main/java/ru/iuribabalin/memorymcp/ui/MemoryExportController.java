package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.service.MemoryExportService;
import ru.iuribabalin.memorymcp.service.MemoryService;

import java.nio.charset.StandardCharsets;

/**
 * Download endpoints for the local dashboard: an entry as PDF (any type) or as raw markdown
 * (non-REPORT types only). No authentication - same localhost-only trust model as
 * MemoryViewController.
 */
@RestController
public class MemoryExportController {

    private final MemoryService memoryService;
    private final MemoryExportService exportService;

    public MemoryExportController(MemoryService memoryService, MemoryExportService exportService) {
        this.memoryService = memoryService;
        this.exportService = exportService;
    }

    @GetMapping("/api/memory/{name}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String name) {
        MemoryEntryDetail entry = memoryService.get(name);
        byte[] pdf = exportService.toPdf(entry);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(entry.name() + ".pdf"))
                .body(pdf);
    }

    @GetMapping("/api/memory/{name}/markdown")
    public ResponseEntity<byte[]> markdown(@PathVariable String name) {
        MemoryEntryDetail entry = memoryService.get(name);
        String markdown = exportService.toMarkdown(entry);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(entry.name() + ".md"))
                .body(markdown.getBytes(StandardCharsets.UTF_8));
    }

    private String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
    }
}
