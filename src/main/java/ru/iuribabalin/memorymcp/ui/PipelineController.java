package ru.iuribabalin.memorymcp.ui;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.service.PipelineNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineService;

import java.util.List;

@RestController
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping("/api/pipelines")
    public List<PipelineSummary> list(@RequestParam(required = false) String projectScope) {
        return pipelineService.list(projectScope);
    }

    @GetMapping("/api/pipelines/{slug}")
    public PipelineDetail get(@PathVariable String slug) {
        return pipelineService.get(slug);
    }

    @PostMapping("/api/pipelines")
    public PipelineDetail create(@RequestBody PipelineUpsertRequest request) {
        return pipelineService.create(request, null);
    }

    @PutMapping("/api/pipelines/{slug}")
    public PipelineDetail update(@PathVariable String slug, @RequestBody PipelineUpsertRequest request) {
        return pipelineService.update(slug, request);
    }

    @DeleteMapping("/api/pipelines/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug) {
        if (!pipelineService.delete(slug)) {
            throw new PipelineNotFoundException(slug);
        }
        return ResponseEntity.noContent().build();
    }
}
