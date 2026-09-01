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
        // other, forming a 2-cycle reachable from the root. This must be caught by the
        // topological-sort (Kahn's algorithm) branch in validateGraph, not the root-count check.
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
}
