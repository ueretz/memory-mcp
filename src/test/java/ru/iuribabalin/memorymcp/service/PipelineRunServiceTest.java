package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunStepCheckResult;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.repository.PipelineRunStepRepository;

import java.time.Instant;
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

    @Autowired
    private PipelineRunStepRepository pipelineRunStepRepository;

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
    void aVariableValueIsSubstitutedIntoTheStepItIsWiredTo() {
        pipelineService.create(new PipelineUpsertRequest(
                "var-run-1", "Variable run", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Use it", PipelineStep.ContentType.PROMPT,
                        "say {{data:tok-2}}", null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(),
                List.of(new PipelineUpsertRequest.VariableRequest("greeting", "hello")),
                List.of(new PipelineUpsertRequest.VariableLinkRequest("tok-2", "greeting", 0, null)),
                0), "Tester");

        PipelineRunDetail run = pipelineRunService.start("var-run-1", "{}", "Tester");

        // The variable never shows up as a step of the run - it is not one.
        assertThat(run.steps()).hasSize(1);
        assertThat(run.currentStepOrderIndex()).isZero();
        assertThat(run.steps().get(0).resolvedInstructionText()).isEqualTo("say hello");
    }

    @Test
    void aVariableBuiltOutOfAParameterAndAnotherVariableResolvesInOnePass() {
        pipelineService.create(new PipelineUpsertRequest(
                "var-run-2", "Nested variable run", "desc", "pipeline-run-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("who", "Who", PipelineParameter.Type.STRING, false, null)),
                List.of(new PipelineUpsertRequest.StepRequest("Use it", PipelineStep.ContentType.PROMPT,
                        "{{data:tok-full}}", null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest("tok-who", "who", null, "full")),
                List.of(new PipelineUpsertRequest.VariableRequest("greeting", "Привет"),
                        new PipelineUpsertRequest.VariableRequest("full", "{{data:tok-greeting}}, {{data:tok-who}}!")),
                List.of(new PipelineUpsertRequest.VariableLinkRequest("tok-greeting", "greeting", null, "full"),
                        new PipelineUpsertRequest.VariableLinkRequest("tok-full", "full", 0, null)),
                0), "Tester");

        PipelineRunDetail run = pipelineRunService.start("var-run-2", "{\"who\":\"Юрий\"}", "Tester");

        assertThat(run.steps().get(0).resolvedInstructionText()).isEqualTo("Привет, Юрий!");
    }

    @Test
    void aVariableCanFeedTheValueAConditionComparesAgainst() {
        pipelineService.create(new PipelineUpsertRequest(
                "chain-run-1", "Variable into condition", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check threshold", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(), PipelineStep.ConditionOperator.EQUALS, "5"),
                        new PipelineUpsertRequest.StepRequest("Notify", PipelineStep.ContentType.PROMPT, "notify",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(),
                List.of(new PipelineUpsertRequest.VariableRequest("threshold", "5")),
                List.of(new PipelineUpsertRequest.VariableLinkRequest("tok-3", "threshold", 0, null)),
                0), "Tester");

        PipelineRunDetail run = pipelineRunService.start("chain-run-1", "{}", "Tester");

        assertThat(run.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(run.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void aRunBeginsAtTheStepPinnedToTheInputPlate() {
        pipelineService.create(new PipelineUpsertRequest(
                "explicit-start-run-1", "Explicit start", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Unwired", PipelineStep.ContentType.PROMPT, "skip me",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Real start", PipelineStep.ContentType.PROMPT, "start here",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2)),
                                List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Then", PipelineStep.ContentType.PROMPT, "then",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)),
                List.of(), List.of(), List.of(), 1), "Tester");

        PipelineRunDetail run = pipelineRunService.start("explicit-start-run-1", "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(1);
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

    private PipelineUpsertRequest.StepRequest prompt(String title, List<PipelineUpsertRequest.StepRequest.RouteRequest> routes) {
        return new PipelineUpsertRequest.StepRequest(title, PipelineStep.ContentType.PROMPT, title.toLowerCase(),
                null, null, 0, 0, routes, List.of(), List.of(), null, null);
    }

    private void createForkJoinPipeline(String slug) {
        // 0 Collect -> 1 PARALLEL -> {2 Lint, 3 Test} -> 4 JOIN -> 5 Report -> end
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Fork/join", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        prompt("Collect", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1))),
                        new PipelineUpsertRequest.StepRequest("Fan out", PipelineStep.ContentType.PARALLEL, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, 3)),
                                List.of(), List.of(), null, null),
                        prompt("Lint", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 4))),
                        prompt("Test", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 4))),
                        new PipelineUpsertRequest.StepRequest("Wait for all", PipelineStep.ContentType.JOIN, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 5)),
                                List.of(), List.of(), null, null),
                        prompt("Report", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))))
        ), "Tester");
    }

    @Test
    void parallelStepActivatesEveryBranchAndJoinWaitsForAllOfThem() {
        createForkJoinPipeline("fork-join-1");
        PipelineRunDetail run = pipelineRunService.start("fork-join-1", "{}", "Tester");
        assertThat(run.activeStepOrderIndexes()).containsExactly(0);

        PipelineRunDetail forked = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, null);
        assertThat(forked.activeStepOrderIndexes()).containsExactly(2, 3);
        assertThat(forked.currentStepOrderIndex()).isEqualTo(2);
        assertThat(forked.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.DONE);

        PipelineRunDetail oneDone = pipelineRunService.updateStep(run.id(), 3, PipelineRunStep.Status.DONE, "tests green", null, null);
        assertThat(oneDone.activeStepOrderIndexes()).containsExactly(2);
        assertThat(oneDone.steps().get(4).status()).isEqualTo(PipelineRunStep.Status.PENDING);
        assertThat(oneDone.steps().get(4).note()).contains("1 из 2");

        PipelineRunDetail joined = pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, "lint clean", null, null);
        assertThat(joined.steps().get(4).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(joined.activeStepOrderIndexes()).containsExactly(5);

        PipelineRunDetail finished = pipelineRunService.updateStep(run.id(), 5, PipelineRunStep.Status.DONE, "reported", null, null);
        assertThat(finished.activeStepOrderIndexes()).isEmpty();
        assertThat(finished.currentStepOrderIndex()).isNull();
    }

    @Test
    void runSummaryListsEveryActiveStep() {
        createForkJoinPipeline("fork-join-2");
        PipelineRunDetail run = pipelineRunService.start("fork-join-2", "{}", "Tester");
        pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, null);

        var summary = pipelineRunService.listByPipeline("fork-join-2").get(0);

        assertThat(summary.activeSteps()).extracting(s -> s.title()).containsExactly("Lint", "Test");
        assertThat(summary.doneStepCount()).isEqualTo(2);
        assertThat(summary.totalStepCount()).isEqualTo(6);
    }

    private void createReviewLoopPipeline(String slug, int maxIterations) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Code review loop", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Review cycle", PipelineStep.ContentType.LOOP, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("done", 4)),
                                List.of(), List.of(), null, null, maxIterations),
                        prompt("Write code", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 2))),
                        new PipelineUpsertRequest.StepRequest("Review", PipelineStep.ContentType.PROMPT, "review",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("approve", 4),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("changes", 3)),
                                List.of(), List.of(), null, null),
                        prompt("Fix code", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 0))),
                        prompt("Publish", List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))))
        ), "Tester");
    }

    @Test
    void reviewLoopReactivatesItsBodyUntilReviewApproves() {
        createReviewLoopPipeline("loop-run-1", 3);
        PipelineRunDetail run = pipelineRunService.start("loop-run-1", "{}", "Tester");
        assertThat(run.activeStepOrderIndexes()).containsExactly(1);

        for (int iteration = 0; iteration < 2; iteration++) {
            pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "coded", null, null);
            pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, "needs work", "changes", null);
            run = pipelineRunService.updateStep(run.id(), 3, PipelineRunStep.Status.DONE, "fixed", null, null);
        }

        assertThat(run.activeStepOrderIndexes()).containsExactly(1);
        assertThat(run.steps().get(1).executionCount()).isEqualTo(3);
        assertThat(run.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.PENDING);
        assertThat(run.steps().get(0).note()).isEqualTo("Итерация 3 из 3");

        pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "coded", null, null);
        run = pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, "approved", "approve", null);

        assertThat(run.activeStepOrderIndexes()).containsExactly(4);
        assertThat(run.steps().get(2).executionCount()).isEqualTo(3);
        assertThat(run.steps().get(3).executionCount()).isEqualTo(2);
    }

    @Test
    void reviewLoopTakesDoneRouteWhenItsIterationLimitIsReached() {
        createReviewLoopPipeline("loop-run-2", 1);
        PipelineRunDetail run = pipelineRunService.start("loop-run-2", "{}", "Tester");
        pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "coded", null, null);
        pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, "needs work", "changes", null);

        run = pipelineRunService.updateStep(run.id(), 3, PipelineRunStep.Status.DONE, "fixed", null, null);

        assertThat(run.activeStepOrderIndexes()).containsExactly(4);
        assertThat(run.steps().get(1).executionCount()).isEqualTo(1);
        assertThat(run.steps().get(0).note()).isEqualTo("Лимит итераций: 1 — выход из цикла");
    }

    @Test
    void promptConditionWaitsForModelAndRoutesOnTrueOrFalse() {
        pipelineService.create(new PipelineUpsertRequest(
                "prompt-condition-1", "Model decision", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Ready?", PipelineStep.ContentType.CONDITION,
                                "Исправлены все замечания ревью? Ответь да или нет.", null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", 2)),
                                List.of(), List.of(), null, null, PipelineStep.ConditionMode.PROMPT, null),
                        prompt("Publish", List.of()),
                        prompt("Keep working", List.of()))
        ), "Tester");

        PipelineRunDetail yesRun = pipelineRunService.start("prompt-condition-1", "{}", "Tester");
        assertThat(yesRun.activeStepOrderIndexes()).containsExactly(0);
        assertThat(yesRun.steps().get(0).resolvedInstructionText()).contains("Исправлены все замечания ревью?");
        PipelineRunDetail yes = pipelineRunService.updateStep(
                yesRun.id(), 0, PipelineRunStep.Status.DONE, "Да", "true", null);
        assertThat(yes.activeStepOrderIndexes()).containsExactly(1);

        PipelineRunDetail noRun = pipelineRunService.start("prompt-condition-1", "{}", "Tester");
        PipelineRunDetail no = pipelineRunService.updateStep(
                noRun.id(), 0, PipelineRunStep.Status.DONE, "Нет", "false", null);
        assertThat(no.activeStepOrderIndexes()).containsExactly(2);
    }

    private PipelineUpsertRequest.StepRequest.RouteRequest route(String key, Integer target) {
        return new PipelineUpsertRequest.StepRequest.RouteRequest(key, target);
    }

    private PipelineUpsertRequest.StepRequest watcher(
            String title, PipelineStep.WatchSource source, PipelineStep.WatchAspect aspect, String target,
            int interval, int timeout, List<PipelineUpsertRequest.StepRequest.RouteRequest> routes) {
        return new PipelineUpsertRequest.StepRequest(title, PipelineStep.ContentType.WATCHER, target,
                null, null, 0, 0, routes, List.of(), List.of(), null, null,
                PipelineStep.ConditionMode.VALUE, null, source, aspect, interval, timeout);
    }

    /** Watcher at orderIndex 0, success -> 1, failed -> 2, timeout -> end. */
    private void createGitlabWatcherPipeline(String slug, int timeoutSeconds) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Watch the build", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(
                        watcher("Wait for the build", PipelineStep.WatchSource.GITLAB_MR,
                                PipelineStep.WatchAspect.PIPELINE,
                                "https://gitlab.example.com/g/p/-/merge_requests/42", 60, timeoutSeconds,
                                List.of(route("success", 1), route("failed", 2), route("timeout", null))),
                        prompt("Hand over to review", List.of()),
                        prompt("Fix the build", List.of()))), "Tester");
    }

    @Test
    void aWatcherStepIsHandedToTheAgentLikeAPrompt() {
        createGitlabWatcherPipeline("watch-run-1", 3600);

        PipelineRunDetail run = pipelineRunService.start("watch-run-1", null, "Tester");

        assertThat(run.activeStepOrderIndexes()).containsExactly(0);
        assertThat(run.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.PENDING);
    }

    @Test
    void theFirstCheckStartsTheDeadlineAndTheStepIsRunning() {
        createGitlabWatcherPipeline("watch-run-2", 3600);
        PipelineRunDetail run = pipelineRunService.start("watch-run-2", null, "Tester");

        PipelineRunStepCheckResult result = pipelineRunService.checkStep(run.id(), 0, "running");

        assertThat(result.checkCount()).isEqualTo(1);
        assertThat(result.deadlineAt()).isNotNull();
        assertThat(result.expired()).isFalse();
        PipelineRunDetail.PipelineRunStepView step = pipelineRunService.get(run.id()).steps().get(0);
        assertThat(step.status()).isEqualTo(PipelineRunStep.Status.RUNNING);
        assertThat(step.watchLastState()).isEqualTo("running");
        assertThat(step.watchCheckCount()).isEqualTo(1);
    }

    @Test
    void aLaterCheckKeepsTheDeadlineTheFirstOneSet() {
        createGitlabWatcherPipeline("watch-run-3", 3600);
        PipelineRunDetail run = pipelineRunService.start("watch-run-3", null, "Tester");

        PipelineRunStepCheckResult first = pipelineRunService.checkStep(run.id(), 0, "running");
        PipelineRunStepCheckResult second = pipelineRunService.checkStep(run.id(), 0, "running");

        assertThat(second.deadlineAt()).isEqualTo(first.deadlineAt());
        assertThat(second.checkCount()).isEqualTo(2);
    }

    @Test
    void aCheckPastTheDeadlineComesBackExpired() {
        createGitlabWatcherPipeline("watch-run-4", 60);
        PipelineRunDetail run = pipelineRunService.start("watch-run-4", null, "Tester");
        pipelineRunService.checkStep(run.id(), 0, "running");
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.id(), 0).orElseThrow();
        runStep.setWatchDeadlineAt(Instant.now().minusSeconds(1));
        pipelineRunStepRepository.save(runStep);

        PipelineRunStepCheckResult result = pipelineRunService.checkStep(run.id(), 0, "running");

        assertThat(result.expired()).isTrue();
    }

    @Test
    void aWatcherOutcomeRoutesLikeAnyOtherOutcome() {
        createGitlabWatcherPipeline("watch-run-5", 3600);
        PipelineRunDetail run = pipelineRunService.start("watch-run-5", null, "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(
                run.id(), 0, PipelineRunStep.Status.DONE, "build failed", "failed", null);

        assertThat(updated.activeStepOrderIndexes()).containsExactly(2);
    }

    @Test
    void checkingAStepThatIsNotAWatcherIsRejected() {
        createSamplePipeline("watch-run-6");
        PipelineRunDetail run = pipelineRunService.start("watch-run-6", "{\"folder\":\"src\"}", "Tester");

        assertThatThrownBy(() -> pipelineRunService.checkStep(run.id(), 0, "running"))
                .isInstanceOf(PipelineRunNotAWatcherException.class);
    }

    @Test
    void anOverdueWatcherReadsAsExpiredWithoutAnyFurtherCheck() {
        createGitlabWatcherPipeline("watch-run-7", 60);
        PipelineRunDetail run = pipelineRunService.start("watch-run-7", null, "Tester");
        pipelineRunService.checkStep(run.id(), 0, "running");
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.id(), 0).orElseThrow();
        runStep.setWatchDeadlineAt(Instant.now().minusSeconds(1));
        pipelineRunStepRepository.save(runStep);

        assertThat(pipelineRunService.get(run.id()).steps().get(0).watchExpired()).isTrue();
    }

    @Test
    void reactivatingAWatcherInALoopResetsItsChecksAndDeadline() {
        pipelineService.create(new PipelineUpsertRequest(
                "watch-run-8", "Review loop", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Review cycle", PipelineStep.ContentType.LOOP, null,
                                null, null, 0, 0, List.of(route(null, 1), route("done", 3)),
                                List.of(), List.of(), null, null, 3),
                        watcher("Wait for review", PipelineStep.WatchSource.COMMAND, null,
                                "test -f /tmp/reviewed", 60, 600,
                                List.of(route("success", 2), route("timeout", null))),
                        prompt("Work the comments", List.of(route(null, 0))),
                        prompt("Merge", List.of()))), "Tester");
        PipelineRunDetail run = pipelineRunService.start("watch-run-8", null, "Tester");
        pipelineRunService.checkStep(run.id(), 1, "waiting");
        pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "reviewed", "success", null);
        pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, "fixed", null, null);

        PipelineRunDetail.PipelineRunStepView watcherStep = pipelineRunService.get(run.id()).steps().get(1);

        assertThat(watcherStep.status()).isEqualTo(PipelineRunStep.Status.PENDING);
        assertThat(watcherStep.watchCheckCount()).isEqualTo(0);
        assertThat(watcherStep.watchDeadlineAt()).isNull();
        assertThat(watcherStep.watchLastState()).isNull();
    }

    @Test
    void aClosedWatcherDoesNotKeepReadingAsExpired() {
        createGitlabWatcherPipeline("watch-run-9", 60);
        PipelineRunDetail run = pipelineRunService.start("watch-run-9", null, "Tester");
        pipelineRunService.checkStep(run.id(), 0, "success");
        pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "build green", "success", null);
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.id(), 0).orElseThrow();
        runStep.setWatchDeadlineAt(Instant.now().minusSeconds(1));
        pipelineRunStepRepository.save(runStep);

        assertThat(pipelineRunService.get(run.id()).steps().get(0).watchExpired()).isFalse();
    }

    @Test
    void checkingAWatcherThatIsAlreadyClosedIsRejected() {
        createGitlabWatcherPipeline("watch-run-10", 3600);
        PipelineRunDetail run = pipelineRunService.start("watch-run-10", null, "Tester");
        pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "build green", "success", null);

        assertThatThrownBy(() -> pipelineRunService.checkStep(run.id(), 0, "success"))
                .isInstanceOf(PipelineRunStepClosedException.class);
    }

    @Test
    void anOverlongObservedStateIsStoredTruncatedRatherThanFailingTheCheck() {
        createGitlabWatcherPipeline("watch-run-11", 3600);
        PipelineRunDetail run = pipelineRunService.start("watch-run-11", null, "Tester");

        pipelineRunService.checkStep(run.id(), 0, "x".repeat(250));

        assertThat(pipelineRunService.get(run.id()).steps().get(0).watchLastState()).hasSize(100);
    }

    @Test
    void aWatcherTargetIsResolvedFromTheEarlierStepsReportedOutput() {
        pipelineService.create(new PipelineUpsertRequest(
                "watch-run-12", "Create then watch", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Create the MR", PipelineStep.ContentType.PROMPT,
                                "push and open an MR", null, null, 0, 0, List.of(route(null, 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("mrUrl")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("mrUrl", "mrUrl", 1)),
                                null, null),
                        watcher("Wait for the build", PipelineStep.WatchSource.GITLAB_MR,
                                PipelineStep.WatchAspect.PIPELINE, "{{data:mrUrl}}", 60, 3600,
                                List.of(route("success", null), route("failed", null), route("timeout", null))))),
                "Tester");
        PipelineRunDetail run = pipelineRunService.start("watch-run-12", null, "Tester");

        PipelineRunDetail afterCreate = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE,
                "opened", null, "{\"mrUrl\":\"https://gitlab.example.com/g/p/-/merge_requests/77\"}");

        assertThat(afterCreate.activeStepOrderIndexes()).containsExactly(1);
        assertThat(afterCreate.steps().get(1).resolvedInstructionText())
                .isEqualTo("https://gitlab.example.com/g/p/-/merge_requests/77");
    }

    @Test
    void aWatcherWhoseStepDefinitionWasReplacedMidRunFallsBackToAnHour() {
        pipelineService.create(new PipelineUpsertRequest(
                "watch-run-13", "Long watch", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(watcher("Wait a day", PipelineStep.WatchSource.COMMAND, null, "test -f /tmp/ready",
                        60, 86400, List.of(route("success", null), route("timeout", null))))), "Tester");
        PipelineRunDetail run = pipelineRunService.start("watch-run-13", null, "Tester");
        // Saving the pipeline replaces its step rows, so the run step now points at a deleted
        // definition and its configured 24-hour timeout is no longer reachable.
        pipelineService.update("watch-run-13", new PipelineUpsertRequest(
                "watch-run-13", "Long watch", "desc", "pipeline-run-svc-test-project", List.of(),
                List.of(watcher("Wait a day", PipelineStep.WatchSource.COMMAND, null, "test -f /tmp/ready",
                        60, 86400, List.of(route("success", null), route("timeout", null))))));

        PipelineRunStepCheckResult result = pipelineRunService.checkStep(run.id(), 0, "waiting");

        assertThat(result.deadlineAt()).isBetween(
                Instant.now().plusSeconds(3500), Instant.now().plusSeconds(3700));
    }
}
