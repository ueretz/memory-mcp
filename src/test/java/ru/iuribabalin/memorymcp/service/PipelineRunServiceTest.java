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
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}}", null, null, 0, 0, List.of()),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save it", null, null, 0, 0, List.of()))
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

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "diffed fine");

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
}
