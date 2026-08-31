package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.iuribabalin.memorymcp.dto.PipelineAssetSummary;
import ru.iuribabalin.memorymcp.entity.PipelineAsset;
import ru.iuribabalin.memorymcp.service.PipelineAssetService;

import java.io.IOException;

@RestController
public class PipelineAssetController {

    private final PipelineAssetService pipelineAssetService;

    public PipelineAssetController(PipelineAssetService pipelineAssetService) {
        this.pipelineAssetService = pipelineAssetService;
    }

    @PostMapping("/api/pipeline-assets")
    public PipelineAssetSummary upload(@RequestParam("file") MultipartFile file) throws IOException {
        return pipelineAssetService.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes(), null);
    }

    @GetMapping("/api/pipeline-assets/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        PipelineAsset asset = pipelineAssetService.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + asset.getFilename() + "\"")
                .body(asset.getData());
    }
}
