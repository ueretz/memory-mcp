package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineServiceTest {

    @Autowired
    private PipelineService pipelineService;

    private PipelineUpsertRequest sampleRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Config diff", "Diffs configs against prod", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder to check", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}} against prod", null, null),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save the report to memory", null, null)));
    }

    @Test
    void createsAndFetchesAPipelineWithItsStepsAndParameters() {
        pipelineService.create(sampleRequest("config-diff-1"), "Tester");

        PipelineDetail detail = pipelineService.get("config-diff-1");

        assertThat(detail.name()).isEqualTo("Config diff");
        assertThat(detail.parameters()).extracting(PipelineDetail.PipelineParameterView::name).containsExactly("folder");
        assertThat(detail.steps()).extracting(PipelineDetail.PipelineStepView::title)
                .containsExactly("Check history", "Save report");
    }

    @Test
    void rejectsADuplicateSlug() {
        pipelineService.create(sampleRequest("config-diff-2"), "Tester");

        assertThatThrownBy(() -> pipelineService.create(sampleRequest("config-diff-2"), "Tester"))
                .isInstanceOf(PipelineSlugTakenException.class);
    }

    @Test
    void updateReplacesStepsAndParameters() {
        pipelineService.create(sampleRequest("config-diff-3"), "Tester");
        PipelineUpsertRequest updated = new PipelineUpsertRequest(
                "config-diff-3", "Config diff v2", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Only step", PipelineStep.ContentType.PROMPT, "do it", null, null)));

        PipelineDetail detail = pipelineService.update("config-diff-3", updated);

        assertThat(detail.name()).isEqualTo("Config diff v2");
        assertThat(detail.parameters()).isEmpty();
        assertThat(detail.steps()).extracting(PipelineDetail.PipelineStepView::title).containsExactly("Only step");
    }

    @Test
    void listReturnsPipelinesForAProject() {
        pipelineService.create(sampleRequest("config-diff-4"), "Tester");

        List<PipelineSummary> pipelines = pipelineService.list("pipeline-svc-test-project");

        assertThat(pipelines).extracting(PipelineSummary::slug).contains("config-diff-4");
    }

    @Test
    void deleteRemovesThePipeline() {
        pipelineService.create(sampleRequest("config-diff-5"), "Tester");

        boolean deleted = pipelineService.delete("config-diff-5");

        assertThat(deleted).isTrue();
        assertThatThrownBy(() -> pipelineService.get("config-diff-5")).isInstanceOf(PipelineNotFoundException.class);
    }

    @Test
    void rejectsAnMdFileStepWithNoUploadedAsset() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "config-diff-6", "Config diff", "Diffs configs against prod", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Missing file", PipelineStep.ContentType.MD_FILE, null, null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class);
    }
}
