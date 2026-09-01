package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineRunServiceTest {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private PipelineRunService pipelineRunService;

    private void createSamplePipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Config diff", "desc", "pipeline-run-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}}", null, null, 0, 0, List.of(), List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save it", null, null, 0, 0, List.of(), List.of(), List.of()))
        ), "Tester");
    }

    @Test
    void startSnapshotsStepsAsPending() {
        createSamplePipeline("run-test-1");

        PipelineRunDetail run = pipelineRunService.start("run-test-1", "{\"folder\":\"src/config\"}", "Tester");

        assertThat(run.status()).isEqualTo(PipelineRun.Status.RUNNING);
        assertThat(run.steps()).hasSize(2);
        assertThat(run.steps()).allMatch(step -> step.status() == PipelineRunStep.Status.PENDING);
    }

    @Test
    void updateStepMovesItToDoneAndStampsFinishedAt() {
        createSamplePipeline("run-test-2");
        PipelineRunDetail run = pipelineRunService.start("run-test-2", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "diffed fine", null);

        assertThat(updated.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(updated.steps().get(0).note()).isEqualTo("diffed fine");
        assertThat(updated.steps().get(0).finishedAt()).isNotNull();
    }

    @Test
    void completeSetsFinalStatusAndFinishedAt() {
        createSamplePipeline("run-test-3");
        PipelineRunDetail run = pipelineRunService.start("run-test-3", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail completed = pipelineRunService.complete(run.id(), PipelineRun.Status.DONE);

        assertThat(completed.status()).isEqualTo(PipelineRun.Status.DONE);
        assertThat(completed.finishedAt()).isNotNull();
    }

    @Test
    void listByPipelineReturnsRunsNewestFirst() {
        createSamplePipeline("run-test-4");
        pipelineRunService.start("run-test-4", "{\"folder\":\"a\"}", "Tester");
        pipelineRunService.start("run-test-4", "{\"folder\":\"b\"}", "Tester");

        assertThat(pipelineRunService.listByPipeline("run-test-4")).hasSize(2);
    }

    @Test
    void validateParametersThrowsWhenARequiredParameterIsMissing() {
        createSamplePipeline("run-test-5");

        assertThatThrownBy(() -> pipelineService.validateParameters("run-test-5", "{}"))
                .isInstanceOf(PipelineInvalidParametersException.class)
                .hasMessageContaining("folder");
    }

    @Test
    void getForExecutionInterpolatesNothingButReturnsRawInstructionText() {
        createSamplePipeline("run-test-6");

        var execution = pipelineService.getForExecution("run-test-6");

        assertThat(execution.steps()).extracting(step -> step.instructionText())
                .containsExactly("Diff {{folder}}", "Save it");
    }

    private void createBranchingPipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Branching", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", 2)),
                                List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Rollback", PipelineStep.ContentType.PROMPT, "rollback",
                                null, null, 0, 0, List.of(), List.of(), List.of()))
        ), "Tester");
    }

    @Test
    void startPointsCurrentStepAtTheRoot() {
        createSamplePipeline("run-test-7");

        PipelineRunDetail run = pipelineRunService.start("run-test-7", "{\"folder\":\"src\"}", "Tester");

        assertThat(run.currentStepOrderIndex()).isZero();
    }

    @Test
    void startResolvesTheTrueRootEvenWhenItIsNotTheLowestOrderIndex() {
        String slug = "run-test-13";
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Branching, root not at zero", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Rollback", PipelineStep.ContentType.PROMPT, "rollback",
                                null, null, 0, 0, List.of(), List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", 0)),
                                List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of()))
        ), "Tester");

        PipelineRunDetail run = pipelineRunService.start(slug, "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneWithAMatchingOutcomeAdvancesToTheRoutedStep() {
        createBranchingPipeline("run-test-8");
        PipelineRunDetail run = pipelineRunService.start("run-test-8", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "pass");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneWithADifferentOutcomeAdvancesToItsOwnBranch() {
        createBranchingPipeline("run-test-9");
        PipelineRunDetail run = pipelineRunService.start("run-test-9", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "broke", "fail");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void doneWithAnUnknownOutcomeThrows() {
        createBranchingPipeline("run-test-10");
        PipelineRunDetail run = pipelineRunService.start("run-test-10", "{}", "Tester");

        assertThatThrownBy(() -> pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "not-a-real-outcome"))
                .isInstanceOf(PipelineRunInvalidOutcomeException.class)
                .hasMessageContaining("pass")
                .hasMessageContaining("fail");
    }

    @Test
    void doneOnAStepWithNoRoutesInALegacyPipelineFallsBackToOrderIndexPlusOne() {
        createSamplePipeline("run-test-11");
        PipelineRunDetail run = pipelineRunService.start("run-test-11", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneOnTheLastStepOfABranchEndsTheRun() {
        createBranchingPipeline("run-test-12");
        PipelineRunDetail run = pipelineRunService.start("run-test-12", "{}", "Tester");
        pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "pass");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "deployed", null);

        assertThat(updated.currentStepOrderIndex()).isNull();
    }

    @Test
    void skippedOnALegacyPipelineStillAdvancesViaOrderIndexPlusOne() {
        createSamplePipeline("run-test-14");
        PipelineRunDetail run = pipelineRunService.start("run-test-14", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.SKIPPED, "skip it", null);

        assertThat(updated.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.SKIPPED);
        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void skippedOnABranchingStepWithADefaultRouteFollowsIt() {
        String slug = "run-test-15";
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Branching with default", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2)),
                                List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Fallback", PipelineStep.ContentType.PROMPT, "fallback",
                                null, null, 0, 0, List.of(), List.of(), List.of()))
        ), "Tester");
        PipelineRunDetail run = pipelineRunService.start(slug, "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.SKIPPED, "skip it", null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void skippedOnABranchingStepWithOnlyNamedRoutesEndsThatPathInsteadOfThrowing() {
        createBranchingPipeline("run-test-16");
        PipelineRunDetail run = pipelineRunService.start("run-test-16", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.SKIPPED, "skip it", null);

        assertThat(updated.currentStepOrderIndex()).isNull();
    }
}
