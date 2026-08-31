package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunSummary;
import ru.iuribabalin.memorymcp.service.PipelineRunService;

import java.util.List;

@RestController
public class PipelineRunController {

    private final PipelineRunService pipelineRunService;

    public PipelineRunController(PipelineRunService pipelineRunService) {
        this.pipelineRunService = pipelineRunService;
    }

    @GetMapping("/api/pipelines/{slug}/runs")
    public List<PipelineRunSummary> listByPipeline(@PathVariable String slug) {
        return pipelineRunService.listByPipeline(slug);
    }

    @GetMapping("/api/pipeline-runs/{id}")
    public PipelineRunDetail get(@PathVariable Long id) {
        return pipelineRunService.get(id);
    }
}
