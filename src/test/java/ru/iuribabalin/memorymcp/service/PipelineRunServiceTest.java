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
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}}", null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save it", null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
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

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "diffed fine", null, null);

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
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Rollback", PipelineStep.ContentType.PROMPT, "rollback",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
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
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", 0)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");

        PipelineRunDetail run = pipelineRunService.start(slug, "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneWithAMatchingOutcomeAdvancesToTheRoutedStep() {
        createBranchingPipeline("run-test-8");
        PipelineRunDetail run = pipelineRunService.start("run-test-8", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "pass", null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneWithADifferentOutcomeAdvancesToItsOwnBranch() {
        createBranchingPipeline("run-test-9");
        PipelineRunDetail run = pipelineRunService.start("run-test-9", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "broke", "fail", null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void doneWithAnUnknownOutcomeThrows() {
        createBranchingPipeline("run-test-10");
        PipelineRunDetail run = pipelineRunService.start("run-test-10", "{}", "Tester");

        assertThatThrownBy(() -> pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "not-a-real-outcome", null))
                .isInstanceOf(PipelineRunInvalidOutcomeException.class)
                .hasMessageContaining("pass")
                .hasMessageContaining("fail");
    }

    @Test
    void doneOnAStepWithNoRoutesInALegacyPipelineFallsBackToOrderIndexPlusOne() {
        createSamplePipeline("run-test-11");
        PipelineRunDetail run = pipelineRunService.start("run-test-11", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneOnTheLastStepOfABranchEndsTheRun() {
        createBranchingPipeline("run-test-12");
        PipelineRunDetail run = pipelineRunService.start("run-test-12", "{}", "Tester");
        pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "pass", null);

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "deployed", null, null);

        assertThat(updated.currentStepOrderIndex()).isNull();
    }

    @Test
    void skippedOnALegacyPipelineStillAdvancesViaOrderIndexPlusOne() {
        createSamplePipeline("run-test-14");
        PipelineRunDetail run = pipelineRunService.start("run-test-14", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.SKIPPED, "skip it", null, null);

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
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Fallback", PipelineStep.ContentType.PROMPT, "fallback",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");
        PipelineRunDetail run = pipelineRunService.start(slug, "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.SKIPPED, "skip it", null, null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void skippedOnABranchingStepWithOnlyNamedRoutesEndsThatPathInsteadOfThrowing() {
        createBranchingPipeline("run-test-16");
        PipelineRunDetail run = pipelineRunService.start("run-test-16", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.SKIPPED, "skip it", null, null);

        assertThat(updated.currentStepOrderIndex()).isNull();
    }

    private void createDataLinkPipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Data link run", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Summarize", PipelineStep.ContentType.PROMPT, "summarize it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "summary", 1)), null, null),
                        new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "Write it up: {{data:tok-1}}",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");
    }

    @Test
    void resolvedInstructionTextSubstitutesAReportedOutputValue() {
        createDataLinkPipeline("run-test-17");
        PipelineRunDetail run = pipelineRunService.start("run-test-17", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"summary\":\"all green\"}");

        assertThat(updated.steps().get(1).resolvedInstructionText()).isEqualTo("Write it up: all green");
    }

    @Test
    void resolvedInstructionTextSubstitutesEmptyStringWhenTheOutputWasNeverReported() {
        createDataLinkPipeline("run-test-18");
        PipelineRunDetail run = pipelineRunService.start("run-test-18", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, null);

        assertThat(updated.steps().get(1).resolvedInstructionText()).isEqualTo("Write it up: ");
    }

    @Test
    void updateStepRejectsAnUndeclaredOutputKey() {
        createDataLinkPipeline("run-test-19");
        PipelineRunDetail run = pipelineRunService.start("run-test-19", "{}", "Tester");

        assertThatThrownBy(() -> pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"typo\":\"x\"}"))
                .isInstanceOf(PipelineRunUnknownOutputException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void resolvedInstructionTextIsPlainPromptTextWhenTheStepHasNoIncomingDataLinks() {
        createSamplePipeline("run-test-20");
        PipelineRunDetail run = pipelineRunService.start("run-test-20", "{\"folder\":\"src\"}", "Tester");

        assertThat(run.steps().get(0).resolvedInstructionText()).isEqualTo("Diff {{folder}}");
    }

    private void createConditionPipeline(String slug, PipelineStep.ConditionOperator operator, String conditionValue) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Condition run", "desc", "pipeline-run-svc-test-project",
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
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", 3)),
                                List.of(), List.of(), operator, conditionValue),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Rollback", PipelineStep.ContentType.PROMPT, "rollback it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");
    }

    @Test
    void conditionEvaluatingTrueRoutesToTheTrueBranchWithoutClaudeSeeingTheConditionStep() {
        createConditionPipeline("cond-run-1", PipelineStep.ConditionOperator.GREATER_THAN, "10");
        PipelineRunDetail run = pipelineRunService.start("cond-run-1", "{}", "Tester");
        assertThat(run.currentStepOrderIndex()).isZero();

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"20\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
        assertThat(updated.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.DONE);
    }

    @Test
    void conditionEvaluatingFalseRoutesToTheFalseBranch() {
        createConditionPipeline("cond-run-2", PipelineStep.ConditionOperator.GREATER_THAN, "10");
        PipelineRunDetail run = pipelineRunService.start("cond-run-2", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"5\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(3);
    }

    @Test
    void conditionWithUnparseableNumericInputEvaluatesFalse() {
        createConditionPipeline("cond-run-3", PipelineStep.ConditionOperator.GREATER_THAN, "10");
        PipelineRunDetail run = pipelineRunService.start("cond-run-3", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"not-a-number\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(3);
    }

    @Test
    void conditionUsesEqualsOnRawStrings() {
        createConditionPipeline("cond-run-4", PipelineStep.ConditionOperator.EQUALS, "yes");
        PipelineRunDetail run = pipelineRunService.start("cond-run-4", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"yes\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void variableAsTheRootStepAutoExecutesBeforeStartReturns() {
        pipelineService.create(new PipelineUpsertRequest(
                "var-run-1", "Variable run", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Greeting", PipelineStep.ContentType.VARIABLE, "hello",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("greeting")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-2", "greeting", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Use it", PipelineStep.ContentType.PROMPT, "say {{data:tok-2}}",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");

        PipelineRunDetail run = pipelineRunService.start("var-run-1", "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(1);
        assertThat(run.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(run.steps().get(1).resolvedInstructionText()).isEqualTo("say hello");
    }

    @Test
    void consecutiveVariableAndConditionStepsAllAutoAdvanceInOneCall() {
        pipelineService.create(new PipelineUpsertRequest(
                "chain-run-1", "Chained auto steps", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Set threshold", PipelineStep.ContentType.VARIABLE, "5",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("threshold")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-3", "threshold", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Check threshold", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(), PipelineStep.ConditionOperator.EQUALS, "5"),
                        new PipelineUpsertRequest.StepRequest("Notify", PipelineStep.ContentType.PROMPT, "notify",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");

        PipelineRunDetail run = pipelineRunService.start("chain-run-1", "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(2);
        assertThat(run.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(run.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.DONE);
    }

    private void createParameterLinkPipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Param link pipeline", "desc", "pipeline-run-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder", PipelineParameter.Type.STRING, false, "src")),
                List.of(new PipelineUpsertRequest.StepRequest("Scan", PipelineStep.ContentType.PROMPT, "Scan {{data:ptok-1}} now",
                        null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("ptok-1", "folder", 0))
        ), "Tester");
    }

    @Test
    void resolvedInstructionTextSubstitutesAParameterValueTheRunWasStartedWith() {
        createParameterLinkPipeline("param-run-1");

        PipelineRunDetail run = pipelineRunService.start("param-run-1", "{\"folder\":\"lib\"}", "Tester");

        assertThat(run.steps().get(0).resolvedInstructionText()).isEqualTo("Scan lib now");
    }

    @Test
    void resolvedInstructionTextFallsBackToTheParameterDefault() {
        createParameterLinkPipeline("param-run-2");

        PipelineRunDetail run = pipelineRunService.start("param-run-2", "{}", "Tester");

        assertThat(run.steps().get(0).resolvedInstructionText()).isEqualTo("Scan src now");
    }

    @Test
    void conditionFedByAParameterIsEvaluatedAtStart() {
        pipelineService.create(new PipelineUpsertRequest(
                "param-cond-1", "Param condition", "desc", "pipeline-run-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("count", "Count", PipelineParameter.Type.NUMBER, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Many?", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", 2)),
                                List.of(), List.of(), PipelineStep.ConditionOperator.GREATER_THAN, "10"),
                        new PipelineUpsertRequest.StepRequest("Big", PipelineStep.ContentType.PROMPT, "big",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Small", PipelineStep.ContentType.PROMPT, "small",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("ptok-c", "count", 0))
        ), "Tester");

        assertThat(pipelineRunService.start("param-cond-1", "{\"count\":\"20\"}", "Tester").currentStepOrderIndex()).isEqualTo(1);
        assertThat(pipelineRunService.start("param-cond-1", "{\"count\":5}", "Tester").currentStepOrderIndex()).isEqualTo(2);
    }
}
