package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.iuribabalin.memorymcp.service.FolderNotFoundException;
import ru.iuribabalin.memorymcp.service.MemoryNotFoundException;
import ru.iuribabalin.memorymcp.service.PdfRenderException;
import ru.iuribabalin.memorymcp.service.PipelineAssetNotFoundException;
import ru.iuribabalin.memorymcp.service.TaskNotFoundException;
import ru.iuribabalin.memorymcp.service.UnsupportedExportException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MemoryNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(MemoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTaskNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedExportException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedExport(UnsupportedExportException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PdfRenderException.class)
    public ResponseEntity<Map<String, String>> handlePdfRenderFailure(PdfRenderException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(FolderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleFolderNotFound(FolderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PipelineAssetNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePipelineAssetNotFound(PipelineAssetNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
