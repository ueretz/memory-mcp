package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
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
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}} against prod", null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save the report to memory", null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));
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
                List.of(new PipelineUpsertRequest.StepRequest("Only step", PipelineStep.ContentType.PROMPT, "do it", null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

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
                List.of(new PipelineUpsertRequest.StepRequest("Missing file", PipelineStep.ContentType.MD_FILE, null, null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

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
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", null)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 230.0, 20.0, List.of(), List.of(), List.of(), null, null)));

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
        // other, forming a 2-cycle reachable from the root. Only cycles returning to LOOP are
        // allowed, so validateGraph must reject this one after the root-count check.
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-2", "Cyclic", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2)), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("C", PipelineStep.ContentType.PROMPT, "c",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)), List.of(), List.of(), null, null)));

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
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 5)), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

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
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)), List.of(), List.of(), null, null)));

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
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

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
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", null)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

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
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Not wired yet", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 300, 300, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps()).hasSize(2);
    }

    @Test
    void aPipelineWithNoRoutesAnywhereSkipsGraphValidation() {
        pipelineService.create(sampleRequest("branch-6"), "Tester");

        PipelineDetail detail = pipelineService.get("branch-6");

        assertThat(detail.steps()).allMatch(s -> s.routes().isEmpty());
    }

    @Test
    void resavingAPipelineKeepsItsDataLinkTokens() {
        // The board resubmits every existing link with the same client-generated token on each
        // save. Deleting the old rows and inserting the new ones in one flush must not trip the
        // unique token index - the old rows have to be gone before the new ones are written.
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-resave", "Data link pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Summarize", PipelineStep.ContentType.PROMPT, "summarize it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-resave", "summary", 1)), null, null),
                        new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "use {{data:tok-resave}}",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));
        pipelineService.create(request, "Tester");

        PipelineDetail detail = pipelineService.update("data-link-resave", request);

        assertThat(detail.steps().get(0).dataLinksOut())
                .extracting(PipelineDetail.PipelineStepView.DataLinkView::token)
                .containsExactly("tok-resave");
    }

    @Test
    void savesAndReadsBackOutputsAndDataLinks() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-1", "Data link pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Summarize", PipelineStep.ContentType.PROMPT, "summarize it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "summary", 1)), null, null),
                        new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "use {{data:tok-1}}",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView summarize = detail.steps().get(0);
        assertThat(summarize.outputs()).extracting(PipelineDetail.PipelineStepView.OutputView::name).containsExactly("summary");
        assertThat(summarize.dataLinksOut()).hasSize(1);
        PipelineDetail.PipelineStepView.DataLinkView link = summarize.dataLinksOut().get(0);
        assertThat(link.token()).isEqualTo("tok-1");
        assertThat(link.sourceOutputName()).isEqualTo("summary");
        assertThat(link.targetStepOrderIndex()).isEqualTo(1);
        assertThat(link.targetStepTitle()).isEqualTo("Report");
    }

    @Test
    void readsBackMultipleOutputsInDeclarationOrder() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-7", "Multiple outputs", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("zeta"),
                                new PipelineUpsertRequest.StepRequest.OutputRequest("alpha"),
                                new PipelineUpsertRequest.StepRequest.OutputRequest("mu")),
                        List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps().get(0).outputs()).extracting(PipelineDetail.PipelineStepView.OutputView::name)
                .containsExactly("zeta", "alpha", "mu");
    }

    @Test
    void rejectsADuplicateOutputNameOnTheSameStep() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-2", "Dup output", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary"),
                                new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                        List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsADataLinkToAStepThatIsNotAnAncestor() {
        // No routes anywhere -> implicit orderIndex+1 chaining is the graph. Step 1 wiring a link
        // back to step 0 violates the ancestor rule (0 can never run after 1 in this pipeline).
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-3", "Backwards link", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-2", "x", 0)), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsASelfReferencingDataLink() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-4", "Self link", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                        List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-3", "x", 0)), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsADataLinkWiringAnUndeclaredOutputName() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-5", "Undeclared output", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(), List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-4", "never-declared", 1)), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void getForExecutionListsDeclaredOutputNames() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-6", "Exec view", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                        List.of(), null, null)));
        pipelineService.create(request, "Tester");

        var execution = pipelineService.getForExecution("data-link-6");

        assertThat(execution.steps().get(0).outputs()).containsExactly("summary");
    }

    @Test
    void savesAndReadsBackAConditionStepWithItsIncomingLinkAndBranches() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-1", "Condition pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Score", PipelineStep.ContentType.PROMPT, "score it",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("score")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "score", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Check score", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(),
                                PipelineStep.ConditionOperator.GREATER_THAN, "10"),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView condition = detail.steps().get(1);
        assertThat(condition.contentType()).isEqualTo(PipelineStep.ContentType.CONDITION);
        assertThat(condition.conditionOperator()).isEqualTo(PipelineStep.ConditionOperator.GREATER_THAN);
        assertThat(condition.conditionValue()).isEqualTo("10");
        assertThat(condition.routes()).extracting(PipelineDetail.PipelineStepView.RouteView::outcomeKey)
                .containsExactlyInAnyOrder("true", "false");
    }

    @Test
    void rejectsAConditionStepMissingOperatorOrValue() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-2", "Missing operator", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-2", "x", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", null),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class);
    }

    @Test
    void rejectsAConditionStepWithoutExactlyTwoTrueFalseRoutes() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-3", "Wrong routes", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-3", "x", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", null)),
                                List.of(), List.of(),
                                PipelineStep.ConditionOperator.EQUALS, "yes")));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsAConditionStepWithoutExactlyOneIncomingDataLink() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-4", "No incoming link", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", null),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(),
                                PipelineStep.ConditionOperator.EQUALS, "yes")));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void savesAndReadsBackAVariableStepWithItsSingleOutput() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "var-1", "Variable pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Greeting", PipelineStep.ContentType.VARIABLE, "hello",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("greeting")),
                                List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Use it", PipelineStep.ContentType.PROMPT, "say it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView variable = detail.steps().get(0);
        assertThat(variable.contentType()).isEqualTo(PipelineStep.ContentType.VARIABLE);
        assertThat(variable.promptText()).isEqualTo("hello");
        assertThat(variable.outputs()).extracting(PipelineDetail.PipelineStepView.OutputView::name).containsExactly("greeting");
    }

    @Test
    void rejectsAVariableStepWithoutExactlyOneOutput() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "var-2", "No output", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.VARIABLE, "hello",
                        null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsAVariableStepWithANamedRoute() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "var-3", "Named route on variable", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.VARIABLE, "hello",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("go", 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("greeting")),
                                List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    private PipelineUpsertRequest parameterLinkRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Param link pipeline", "desc", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder", PipelineParameter.Type.STRING, true, null)),
                List.of(new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "scan {{data:ptok-1}}",
                        null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("ptok-1", "folder", 0)));
    }

    @Test
    void savesAndReadsBackParameterLinksAcrossResaves() {
        pipelineService.create(parameterLinkRequest("param-link-1"), "Tester");

        PipelineDetail detail = pipelineService.update("param-link-1", parameterLinkRequest("param-link-1"));

        assertThat(detail.parameterLinks()).hasSize(1);
        PipelineDetail.PipelineParameterLinkView link = detail.parameterLinks().get(0);
        assertThat(link.token()).isEqualTo("ptok-1");
        assertThat(link.parameterName()).isEqualTo("folder");
        assertThat(link.targetStepOrderIndex()).isEqualTo(0);
        assertThat(link.targetStepTitle()).isEqualTo("Report");
        assertThat(detail.steps().get(0).dataLinksOut()).isEmpty();
    }

    @Test
    void rejectsAParameterLinkToAnUndeclaredParameter() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "param-link-2", "Param link pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "scan",
                        null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("ptok-2", "missing", 0)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void aParameterLinkCountsAsTheConditionStepsSingleInput() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "param-link-3", "Param link pipeline", "desc", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("count", "Count", PipelineParameter.Type.NUMBER, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Many?", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(), PipelineStep.ConditionOperator.GREATER_THAN, "10"),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("ptok-3", "count", 0)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.parameterLinks()).extracting(PipelineDetail.PipelineParameterLinkView::parameterName).containsExactly("count");
    }

    @Test
    void rejectsAParallelStepWithKeyedBranchesOrNoWiredBranch() {
        PipelineUpsertRequest keyed = new PipelineUpsertRequest(
                "parallel-1", "Parallel", "desc", "pipeline-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Fan out", PipelineStep.ContentType.PARALLEL, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("a", 1)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Work", PipelineStep.ContentType.PROMPT, "work",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));
        assertThatThrownBy(() -> pipelineService.create(keyed, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("outcome keys");

        PipelineUpsertRequest unwired = new PipelineUpsertRequest(
                "parallel-2", "Parallel", "desc", "pipeline-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Fan out", PipelineStep.ContentType.PARALLEL, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)),
                                List.of(), List.of(), null, null)));
        assertThatThrownBy(() -> pipelineService.create(unwired, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("at least one branch");
    }

    @Test
    void aParallelStepMayHaveSeveralUnkeyedRoutes() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "parallel-3", "Parallel", "desc", "pipeline-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Fan out", PipelineStep.ContentType.PARALLEL, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps().get(0).routes()).hasSize(2);
    }

    @Test
    void savesALoopWithItsIterationLimitAndBodyAndDoneRoutes() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "loop-1", "Review loop", "desc", "pipeline-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Review cycle", PipelineStep.ContentType.LOOP, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("done", 2)),
                                List.of(), List.of(), null, null, 3),
                        new PipelineUpsertRequest.StepRequest("Work", PipelineStep.ContentType.PROMPT, "work",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 0)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Finish", PipelineStep.ContentType.PROMPT, "finish",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps().get(0).maxIterations()).isEqualTo(3);
        assertThat(detail.steps().get(0).routes())
                .extracting(PipelineDetail.PipelineStepView.RouteView::outcomeKey)
                .containsExactlyInAnyOrder(null, "done");
    }

    @Test
    void rejectsALoopWithoutAFiniteIterationLimit() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "loop-2", "Invalid loop", "desc", "pipeline-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Loop", PipelineStep.ContentType.LOOP, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("done", null)),
                                List.of(), List.of(), null, null, 0),
                        new PipelineUpsertRequest.StepRequest("Work", PipelineStep.ContentType.PROMPT, "work",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 0)),
                                List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class)
                .hasMessageContaining("between 1 and 100");
    }

    private PipelineUpsertRequest.StepRequest watcher(
            String title, PipelineStep.WatchSource source, PipelineStep.WatchAspect aspect,
            String target, Integer interval, Integer timeout,
            List<PipelineUpsertRequest.StepRequest.RouteRequest> routes) {
        return new PipelineUpsertRequest.StepRequest(title, PipelineStep.ContentType.WATCHER, target,
                null, null, 0, 0, routes, List.of(), List.of(), null, null,
                PipelineStep.ConditionMode.VALUE, null, source, aspect, interval, timeout);
    }

    private PipelineUpsertRequest.StepRequest.RouteRequest route(String key, Integer target) {
        return new PipelineUpsertRequest.StepRequest.RouteRequest(key, target);
    }

    private PipelineUpsertRequest withSteps(String slug, PipelineUpsertRequest.StepRequest... steps) {
        return new PipelineUpsertRequest(slug, "Watcher pipeline", "desc", "pipeline-svc-test-project",
                List.of(), List.of(steps));
    }

    private PipelineUpsertRequest.StepRequest prompt(String title, String text,
                                                      List<PipelineUpsertRequest.StepRequest.RouteRequest> routes) {
        return new PipelineUpsertRequest.StepRequest(title, PipelineStep.ContentType.PROMPT, text,
                null, null, 0, 0, routes, List.of(), List.of(), null, null);
    }

    @Test
    void savesAWatcherWithItsSourceAspectIntervalAndTimeout() {
        PipelineUpsertRequest request = withSteps("watcher-1",
                watcher("Wait for the build", PipelineStep.WatchSource.GITLAB_MR, PipelineStep.WatchAspect.PIPELINE,
                        "https://gitlab.example.com/g/p/-/merge_requests/42", 60, 3600,
                        List.of(route("success", 1), route("failed", null), route("timeout", null))),
                prompt("Hand over to review", "ask for review", List.of()));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView step = detail.steps().get(0);
        assertThat(step.watchSource()).isEqualTo(PipelineStep.WatchSource.GITLAB_MR);
        assertThat(step.watchAspect()).isEqualTo(PipelineStep.WatchAspect.PIPELINE);
        assertThat(step.checkIntervalSeconds()).isEqualTo(60);
        assertThat(step.watchTimeoutSeconds()).isEqualTo(3600);
        assertThat(step.routes()).extracting(PipelineDetail.PipelineStepView.RouteView::outcomeKey)
                .containsExactlyInAnyOrder("success", "failed", "timeout");
    }

    @Test
    void rejectsAWatcherWhoseRouteKeysDoNotMatchItsSource() {
        PipelineUpsertRequest request = withSteps("watcher-2",
                watcher("Wait", PipelineStep.WatchSource.COMMAND, null, "curl -f http://localhost:8080/health", 60, 600,
                        List.of(route("success", 1), route("failed", null), route("timeout", null))),
                prompt("Next", "go", List.of()));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("success, timeout");
    }

    @Test
    void rejectsAWatcherWithATimeoutShorterThanItsCheckInterval() {
        PipelineUpsertRequest request = withSteps("watcher-3",
                watcher("Wait", PipelineStep.WatchSource.COMMAND, null, "curl -f http://localhost:8080/health", 600, 60,
                        List.of(route("success", 1), route("timeout", null))),
                prompt("Next", "go", List.of()));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class)
                .hasMessageContaining("не меньше интервала");
    }

    @Test
    void rejectsAGitlabWatcherWhoseTargetIsNotAMergeRequestUrl() {
        PipelineUpsertRequest request = withSteps("watcher-4",
                watcher("Wait", PipelineStep.WatchSource.GITLAB_MR, PipelineStep.WatchAspect.REVIEW,
                        "https://gitlab.example.com/g/p", 60, 3600,
                        List.of(route("approved", 1), route("comments", null), route("timeout", null))),
                prompt("Next", "go", List.of()));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("merge_requests");
    }

    @Test
    void rejectsACommandWatcherWithAnIncomingDataLink() {
        PipelineUpsertRequest.StepRequest source = new PipelineUpsertRequest.StepRequest(
                "Build the command", PipelineStep.ContentType.PROMPT, "decide the probe", null, null, 0, 0,
                List.of(route(null, 1)),
                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("cmd")),
                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("cmd", "cmd", 1)),
                null, null);
        PipelineUpsertRequest request = withSteps("watcher-5", source,
                watcher("Wait", PipelineStep.WatchSource.COMMAND, null, "{{data:cmd}}", 60, 600,
                        List.of(route("success", null), route("timeout", null))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("COMMAND");
    }

    @Test
    void rejectsAWatcherReachableFromAParallelFanOut() {
        PipelineUpsertRequest request = withSteps("watcher-6",
                new PipelineUpsertRequest.StepRequest("Fan out", PipelineStep.ContentType.PARALLEL, null,
                        null, null, 0, 0, List.of(route(null, 1), route(null, 2)),
                        List.of(), List.of(), null, null),
                watcher("Wait", PipelineStep.WatchSource.COMMAND, null, "curl -f http://localhost:8080/health", 60, 600,
                        List.of(route("success", null), route("timeout", null))),
                prompt("Other branch", "go", List.of()));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("PARALLEL");
    }

    @Test
    void getForExecutionExposesTheWatcherConfigTheAgentNeeds() {
        pipelineService.create(withSteps("watcher-exec-1",
                watcher("Wait for review", PipelineStep.WatchSource.GITLAB_MR, PipelineStep.WatchAspect.REVIEW,
                        "https://gitlab.example.com/g/p/-/merge_requests/7", 120, 7200,
                        List.of(route("approved", 1), route("comments", null), route("timeout", null))),
                prompt("Merge it", "merge", List.of())), "Tester");

        PipelineExecutionDetail detail = pipelineService.getForExecution("watcher-exec-1");

        PipelineExecutionDetail.StepView step = detail.steps().get(0);
        assertThat(step.watchSource()).isEqualTo(PipelineStep.WatchSource.GITLAB_MR);
        assertThat(step.watchAspect()).isEqualTo(PipelineStep.WatchAspect.REVIEW);
        assertThat(step.checkIntervalSeconds()).isEqualTo(120);
        assertThat(step.watchTimeoutSeconds()).isEqualTo(7200);
        assertThat(step.instructionText()).isEqualTo("https://gitlab.example.com/g/p/-/merge_requests/7");
    }

    @Test
    void allowsAGitlabWatcherWhoseTargetArrivesFromAnEarlierStepsOutput() {
        PipelineUpsertRequest.StepRequest maker = new PipelineUpsertRequest.StepRequest(
                "Create the MR", PipelineStep.ContentType.PROMPT, "push and open an MR", null, null, 0, 0,
                List.of(route(null, 1)),
                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("mrUrl")),
                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("mrUrl", "mrUrl", 1)),
                null, null);
        PipelineUpsertRequest request = withSteps("watcher-tpl-1", maker,
                watcher("Wait for the build", PipelineStep.WatchSource.GITLAB_MR, PipelineStep.WatchAspect.PIPELINE,
                        "{{data:mrUrl}}", 60, 3600,
                        List.of(route("success", null), route("failed", null), route("timeout", null))));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps().get(1).promptText()).isEqualTo("{{data:mrUrl}}");
    }

    @Test
    void allowsACommandWatcherFedByAPipelineParameter() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "watcher-param-1", "Watch a health url", "desc", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("healthUrl", "Health URL",
                        PipelineParameter.Type.STRING, true, null)),
                List.of(
                        watcher("Wait for the service", PipelineStep.WatchSource.COMMAND, null,
                                "curl -fsS {{data:healthUrl}}", 60, 600,
                                List.of(route("success", 1), route("timeout", null))),
                        prompt("Report", "report", List.of())),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("healthUrl", "healthUrl", 0)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.parameterLinks()).extracting(PipelineDetail.PipelineParameterLinkView::parameterName)
                .containsExactly("healthUrl");
    }

    @Test
    void allowsAWatcherPlacedAfterTheParallelBranchesHaveJoined() {
        PipelineUpsertRequest request = withSteps("watcher-join-1",
                new PipelineUpsertRequest.StepRequest("Fan out", PipelineStep.ContentType.PARALLEL, null,
                        null, null, 0, 0, List.of(route(null, 1), route(null, 2)),
                        List.of(), List.of(), null, null),
                prompt("Write the code", "code", List.of(route(null, 3))),
                prompt("Write the tests", "tests", List.of(route(null, 3))),
                new PipelineUpsertRequest.StepRequest("Wait for both", PipelineStep.ContentType.JOIN, null,
                        null, null, 0, 0, List.of(route(null, 4)), List.of(), List.of(), null, null),
                watcher("Wait for the build", PipelineStep.WatchSource.COMMAND, null, "test -f /tmp/built",
                        60, 600, List.of(route("success", null), route("timeout", null))));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps().get(4).watchSource()).isEqualTo(PipelineStep.WatchSource.COMMAND);
    }

    /** Full-shape step request: every per-kind field spelled out, including the V23 region fields. */
    private PipelineUpsertRequest.StepRequest step(String title, PipelineStep.ContentType type, String prompt,
                                                   List<PipelineUpsertRequest.StepRequest.RouteRequest> routes,
                                                   Integer maxIterations, Integer regionStartStepIndex,
                                                   PipelineStep.DelegationHint delegationHint) {
        return new PipelineUpsertRequest.StepRequest(title, type, prompt, null, null, 0, 0, routes,
                List.of(), List.of(), null, null, PipelineStep.ConditionMode.VALUE, maxIterations,
                null, null, null, null, regionStartStepIndex, delegationHint);
    }

    @Test
    void savesAndReadsBackLoopRegionAndOrchestratorFields() {
        pipelineService.create(new PipelineUpsertRequest(
                "loop-region-fields", "Loops", "desc", "pipeline-svc-test-project", List.of(),
                List.of(
                        step("Цикл", PipelineStep.ContentType.LOOP, null, List.of(route(null, 1), route("done", 3)), 2, null, null),
                        step("Тело", PipelineStep.ContentType.PROMPT, "Сделай", List.of(route(null, 2)), null, null, null),
                        step("Конец цикла", PipelineStep.ContentType.LOOP_END, null, List.of(), null, 0, null),
                        step("Ревью", PipelineStep.ContentType.PROMPT_LOOP, "Проведи ревью", List.of(route(null, 4)), 5, null, null),
                        step("Оркестратор", PipelineStep.ContentType.ORCHESTRATOR, null, List.of(route(null, 5)), 2, null, null),
                        step("Черновик", PipelineStep.ContentType.PROMPT, "Набросай", List.of(route(null, 6)), null, null, PipelineStep.DelegationHint.CHEAPEST),
                        step("Приёмка", PipelineStep.ContentType.ORCHESTRATOR_END, null, List.of(route(null, null)), null, 4, null))
        ), "Tester");

        PipelineDetail saved = pipelineService.get("loop-region-fields");

        assertThat(saved.steps().get(2).contentType()).isEqualTo(PipelineStep.ContentType.LOOP_END);
        assertThat(saved.steps().get(2).regionStartStepOrderIndex()).isEqualTo(0);
        assertThat(saved.steps().get(3).contentType()).isEqualTo(PipelineStep.ContentType.PROMPT_LOOP);
        assertThat(saved.steps().get(3).maxIterations()).isEqualTo(5);
        assertThat(saved.steps().get(5).delegationHint()).isEqualTo(PipelineStep.DelegationHint.CHEAPEST);
        assertThat(saved.steps().get(6).regionStartStepOrderIndex()).isEqualTo(4);
    }

    // ---- Loop regions (V24) -------------------------------------------------------------------

    private PipelineUpsertRequest.StepRequest loop(String title, int max, Integer bodyTarget, Integer doneTarget) {
        return step(title, PipelineStep.ContentType.LOOP, null,
                List.of(route(null, bodyTarget), route("done", doneTarget)), max, null, null);
    }

    private PipelineUpsertRequest.StepRequest loopEnd(String title, Integer loopIndex) {
        return step(title, PipelineStep.ContentType.LOOP_END, null, List.of(), null, loopIndex, null);
    }

    @Test
    void loopEndMustPointAtALoopStep() {
        PipelineUpsertRequest request = withSteps("loop-end-1",
                loop("Цикл", 2, 1, null),
                prompt("Тело", "do", List.of(route(null, 2))),
                loopEnd("Конец цикла", 1));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("is type LOOP_END and must point at a LOOP step");
    }

    @Test
    void loopEndMustPointAtAnExistingStep() {
        PipelineUpsertRequest request = withSteps("loop-end-2",
                loop("Цикл", 2, 1, null),
                prompt("Тело", "do", List.of(route(null, 2))),
                loopEnd("Конец цикла", 99));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("is type LOOP_END and must point at a LOOP step");
    }

    @Test
    void oneLoopTakesAtMostOneLoopEnd() {
        PipelineUpsertRequest request = withSteps("loop-end-3",
                loop("Цикл", 2, 1, null),
                step("Развилка", PipelineStep.ContentType.PARALLEL, null, List.of(route(null, 2), route(null, 3)), null, null, null),
                loopEnd("Конец A", 0),
                loopEnd("Конец B", 0));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("already has a LOOP_END");
    }

    @Test
    void loopEndMustBeReachableFromItsLoopBody() {
        PipelineUpsertRequest request = withSteps("loop-end-4",
                loop("Цикл", 2, 1, 2),
                prompt("Тело", "do", List.of(route(null, 0))),
                prompt("После цикла", "report", List.of(route(null, 3))),
                loopEnd("Конец цикла", 0));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("is not reachable from the body of the LOOP");
    }

    @Test
    void loopMustBeClosedEitherByAnEdgeOrByALoopEnd() {
        PipelineUpsertRequest request = withSteps("loop-end-5",
                loop("Цикл", 2, 1, null),
                prompt("Тело", "do", List.of(route(null, null))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class)
                .hasMessageContaining("is type LOOP and its body never returns");
    }

    @Test
    void loopClosedByALoopEndSavesWithoutABackEdge() {
        PipelineDetail saved = pipelineService.create(withSteps("loop-end-6",
                loop("Цикл", 3, 1, null),
                prompt("Тело", "do", List.of(route(null, 2))),
                loopEnd("Конец цикла", 0)), "Tester");

        assertThat(saved.steps()).hasSize(3);
        assertThat(saved.steps().get(2).regionStartStepOrderIndex()).isEqualTo(0);
    }

    @Test
    void loopClosedByADrawnBackEdgeStillSaves() {
        PipelineDetail saved = pipelineService.create(withSteps("loop-end-7",
                loop("Цикл", 3, 1, 2),
                prompt("Тело", "do", List.of(route(null, 0))),
                prompt("Отчёт", "report", List.of(route(null, null)))), "Tester");

        assertThat(saved.steps()).hasSize(3);
    }

    // ---- Prompt loop (V24) --------------------------------------------------------------------

    private PipelineUpsertRequest.StepRequest promptLoop(String title, String text, Integer max,
                                                          List<PipelineUpsertRequest.StepRequest.RouteRequest> routes) {
        return step(title, PipelineStep.ContentType.PROMPT_LOOP, text, routes, max, null, null);
    }

    @Test
    void promptLoopNeedsAnInstruction() {
        PipelineUpsertRequest request = withSteps("prompt-loop-1",
                promptLoop("Ревью", "   ", 5, List.of(route(null, null))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class)
                .hasMessageContaining("is type PROMPT_LOOP and needs an instruction");
    }

    @Test
    void promptLoopNeedsAnIterationLimitBetweenOneAndAHundred() {
        PipelineUpsertRequest request = withSteps("prompt-loop-2",
                promptLoop("Ревью", "проведи ревью", 0, List.of(route(null, null))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class)
                .hasMessageContaining("is type PROMPT_LOOP and needs a max iteration count between 1 and 100");
    }

    @Test
    void promptLoopSavesWithItsPromptAndLimit() {
        PipelineDetail saved = pipelineService.create(withSteps("prompt-loop-3",
                promptLoop("Ревью", "проведи ревью, после поправь ошибки", 5, List.of(route(null, 1))),
                prompt("Отчёт", "report", List.of(route(null, null)))), "Tester");

        assertThat(saved.steps().get(0).contentType()).isEqualTo(PipelineStep.ContentType.PROMPT_LOOP);
        assertThat(saved.steps().get(0).maxIterations()).isEqualTo(5);
        assertThat(saved.steps().get(0).promptText()).isEqualTo("проведи ревью, после поправь ошибки");
    }
}
