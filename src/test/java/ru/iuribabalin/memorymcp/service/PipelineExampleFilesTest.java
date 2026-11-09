package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineExportEnvelope;
import ru.iuribabalin.memorymcp.dto.PipelineImportResponse;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunStepCheckResult;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pipelines in {@code docs/examples/pipelines} are handed to users as "import this and run it",
 * so they are checked the only way that means anything: imported through the real import path and,
 * for the two whose new blocks drive execution, actually run.
 */
@SpringBootTest
@Transactional
class PipelineExampleFilesTest {

    private static final Path EXAMPLES = Path.of("docs/examples/pipelines");

    @Autowired
    private PipelineExportService pipelineExportService;

    @Autowired
    private PipelineRunService pipelineRunService;

    @Autowired
    private ObjectMapper objectMapper;

    private PipelineImportResponse importExample(String filename) throws IOException {
        PipelineExportEnvelope envelope = objectMapper.readValue(
                Files.readString(EXAMPLES.resolve(filename)), PipelineExportEnvelope.class);
        PipelineImportResponse imported = pipelineExportService.importPipeline(envelope, null);
        assertThat(imported.warnings()).as("%s imported with warnings", filename).isEmpty();
        return imported;
    }

    @Test
    void everyExampleFileImports() throws IOException {
        assertThat(importExample("review-loop.pipeline.json").pipeline().steps()).hasSize(2);
        assertThat(importExample("loop-region.pipeline.json").pipeline().steps()).hasSize(5);
        assertThat(importExample("orchestrator.pipeline.json").pipeline().steps()).hasSize(6);
    }

    @Test
    void theLoopRegionExampleRunsItsBodyThreeTimesAndThenLeaves() throws IOException {
        String slug = importExample("loop-region.pipeline.json").pipeline().slug();

        PipelineRunDetail run = pipelineRunService.start(slug, "{\"command\":\"./gradlew test\"}", "Tester");

        // The loop and its end are server-executed, so the agent starts on the first body step.
        assertThat(run.steps().get(0).contentType()).isEqualTo(PipelineStep.ContentType.LOOP);
        assertThat(run.activeStepOrderIndexes()).containsExactly(1);
        for (int iteration = 1; iteration <= 3; iteration++) {
            assertThat(run.activeStepOrderIndexes()).as("iteration %d starts at the body", iteration).containsExactly(1);
            pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "red", null,
                    "{\"failures\":\"2 tests\"}");
            run = pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, "fixed", null, null);
        }

        assertThat(run.activeStepOrderIndexes()).as("the iteration limit sends the flow to the report").containsExactly(4);
        assertThat(run.steps().get(1).executionCount()).isEqualTo(3);
        assertThat(run.steps().get(3).note()).isEqualTo("Возврат в цикл «Цикл починки»");
    }

    @Test
    void theReviewLoopExampleRepeatsItsPromptFiveTimes() throws IOException {
        String slug = importExample("review-loop.pipeline.json").pipeline().slug();

        PipelineRunDetail run = pipelineRunService.start(slug, "{\"folder\":\"src\"}", "Tester");
        assertThat(run.activeStepOrderIndexes()).containsExactly(0);

        for (int pass = 1; pass <= 4; pass++) {
            PipelineRunStepCheckResult result = pipelineRunService.checkStep(run.id(), 0, "проход " + pass);
            assertThat(result.iteration()).isEqualTo(pass);
            assertThat(result.maxIterations()).isEqualTo(5);
            assertThat(result.done()).as("pass %d is not the last one", pass).isFalse();
        }

        assertThat(pipelineRunService.checkStep(run.id(), 0, "чисто").done()).isTrue();
        run = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "5 итераций", null,
                "{\"verdict\":\"чисто\"}");

        assertThat(run.activeStepOrderIndexes()).containsExactly(1);
        assertThat(run.steps().get(1).resolvedInstructionText()).contains("чисто");
    }

    @Test
    void theOrchestratorExampleMarksItsRegion() throws IOException {
        String slug = importExample("orchestrator.pipeline.json").pipeline().slug();

        PipelineRunDetail run = pipelineRunService.start(slug, "{\"topic\":\"изменения\"}", "Tester");

        assertThat(run.activeStepOrderIndexes()).containsExactly(1);
        assertThat(run.steps().get(1).orchestrated()).isTrue();
        assertThat(run.steps().get(1).delegationHint()).isEqualTo(PipelineStep.DelegationHint.CHEAPEST);
        assertThat(run.steps().get(1).orchestratorMaxAttempts()).isEqualTo(2);
        assertThat(run.steps().get(3).delegationHint()).isEqualTo(PipelineStep.DelegationHint.BALANCED);
        assertThat(run.steps().get(5).orchestrated()).isFalse();
    }
}
