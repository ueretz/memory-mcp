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
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}} against prod", null, null, 0, 0, List.of()),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save the report to memory", null, null, 0, 0, List.of())));
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
                List.of(new PipelineUpsertRequest.StepRequest("Only step", PipelineStep.ContentType.PROMPT, "do it", null, null, 0, 0, List.of())));

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
                List.of(new PipelineUpsertRequest.StepRequest("Missing file", PipelineStep.ContentType.MD_FILE, null, null, null, 0, 0, List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class);
    }

    @Test
    void savesAndReadsBackPositionsAndRoutes() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-1", "Branching pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check it",
                                null, null, 10.0, 20.0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", null))),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 230.0, 20.0, List.of())));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView checkStep = detail.steps().get(0);
        assertThat(checkStep.positionX()).isEqualTo(10.0);
        assertThat(checkStep.positionY()).isEqualTo(20.0);
        assertThat(checkStep.routes()).extracting(PipelineDetail.PipelineStepView.RouteView::outcomeKey)
                .containsExactlyInAnyOrder("pass", "fail");
        assertThat(checkStep.routes()).filteredOn(r -> "pass".equals(r.outcomeKey()))
                .extracting(PipelineDetail.PipelineStepView.RouteView::targetStepOrderIndex)
                .containsExactly(1);
    }

    @Test
    void rejectsAPipelineWithACycle() {
        // Single root (step 0) so the root-count check passes; steps 1 and 2 route to each
        // other, forming a 2-cycle reachable from the root. This must be caught by the
        // topological-sort (Kahn's algorithm) branch in validateGraph, not the root-count check.
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-2", "Cyclic", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2))),
                        new PipelineUpsertRequest.StepRequest("C", PipelineStep.ContentType.PROMPT, "c",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsARouteWithAnOutOfRangeTargetStepIndex() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-7", "Out of range target", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 5))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsAPipelineWithTwoStartingSteps() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-3", "Two roots", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsTwoDefaultRoutesOnTheSameStep() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-4", "Two defaults", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsTwoRoutesWithTheSameNonNullOutcomeKeyOnTheSameStep() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-7", "Duplicate outcome key", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", null))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void allowsAnUnwiredIsolatedStepAsAWarningOnlyNotAHardError() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-5", "Isolated draft step", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))),
                        new PipelineUpsertRequest.StepRequest("Not wired yet", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 300, 300, List.of())));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps()).hasSize(2);
    }

    @Test
    void aPipelineWithNoRoutesAnywhereSkipsGraphValidation() {
        pipelineService.create(sampleRequest("branch-6"), "Tester");

        PipelineDetail detail = pipelineService.get("branch-6");

        assertThat(detail.steps()).allMatch(s -> s.routes().isEmpty());
    }
}
