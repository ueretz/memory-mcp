package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineExportEnvelope;
import ru.iuribabalin.memorymcp.dto.PipelineImportResponse;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PipelineExportServiceTest {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private PipelineExportService exportService;

    @Autowired
    private PipelineAssetService assetService;

    @Test
    void exportImportRoundTripPreservesTheWholeGraph() {
        pipelineService.create(graphRequest("round-trip"), "Tester");

        PipelineExportEnvelope exported = exportService.export("round-trip");
        PipelineImportResponse imported = exportService.importPipeline(exported, null);
        PipelineExportEnvelope reexported = exportService.export(imported.pipeline().slug());

        assertThat(exported.formatVersion()).isEqualTo(1);
        assertThat(exported.warnings()).isEmpty();
        assertThat(imported.pipeline().slug()).isEqualTo("round-trip-2");
        assertThat(reexported.pipeline())
                .usingRecursiveComparison()
                .ignoringFields("slug", "steps.promptText", "steps.dataLinksOut.token", "parameterLinks.token")
                .isEqualTo(exported.pipeline());
        assertThat(reexported.warnings()).isEmpty();

        String dataToken = imported.pipeline().steps().get(0).dataLinksOut().get(0).token();
        String parameterToken = imported.pipeline().parameterLinks().get(0).token();
        assertThat(dataToken).isNotEqualTo("round-trip-data");
        assertThat(parameterToken).isNotEqualTo("round-trip-parameter");
        assertThat(imported.pipeline().steps().get(1).promptText())
                .isEqualTo("Use {{data:" + dataToken + "}} and {{data:" + parameterToken + "}}");
        assertThat(imported.pipeline().steps().get(0).routes().get(0).targetStepOrderIndex()).isEqualTo(1);
        assertThat(imported.pipeline().steps().get(0).dataLinksOut().get(0).targetStepOrderIndex()).isEqualTo(1);
        assertThat(imported.pipeline().parameterLinks().get(0).targetStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void importPicksTheFirstFreeSlugSuffix() {
        pipelineService.create(singleStepRequest("portable-slug"), "Tester");
        pipelineService.create(singleStepRequest("portable-slug-2"), "Tester");
        PipelineExportEnvelope exported = exportService.export("portable-slug");

        PipelineImportResponse imported = exportService.importPipeline(exported, null);

        assertThat(imported.pipeline().slug()).isEqualTo("portable-slug-3");
    }

    @Test
    void importSlugOverrideIsAlsoCollisionChecked() {
        pipelineService.create(singleStepRequest("portable-source"), "Tester");
        pipelineService.create(singleStepRequest("chosen-slug"), "Tester");
        PipelineExportEnvelope exported = exportService.export("portable-source");

        PipelineImportResponse imported = exportService.importPipeline(exported, "chosen-slug");

        assertThat(imported.pipeline().slug()).isEqualTo("chosen-slug-2");
    }

    @Test
    void importRejectsAnUnknownFormatVersion() {
        PipelineExportEnvelope envelope = new PipelineExportEnvelope(
                99, singleStepRequest("future-format"), List.of());

        assertThatThrownBy(() -> exportService.importPipeline(envelope, null))
                .isInstanceOf(PipelineUnsupportedFormatVersionException.class)
                .hasMessageContaining("99");
    }

    @Test
    void assetReferencesAreDroppedAndReportedOnExportAndImport() {
        Long assetId = assetService.upload(
                "instructions.md", "text/markdown", "# Instructions".getBytes(StandardCharsets.UTF_8), "Tester").id();
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "asset-round-trip", "Assets", "desc", "pipeline-export-test",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest(
                                "File step", PipelineStep.ContentType.MD_FILE, null, assetId, assetId,
                                10, 20, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest(
                                "Plain step", PipelineStep.ContentType.PROMPT, "continue", null, null,
                                30, 40, List.of(), List.of(), List.of(), null, null)));
        pipelineService.create(request, "Tester");

        PipelineExportEnvelope exported = exportService.export("asset-round-trip");
        PipelineImportResponse imported = exportService.importPipeline(exported, null);

        assertThat(exported.warnings()).singleElement().asString().contains("File step");
        assertThat(exported.pipeline().steps()).allMatch(step -> step.assetId() == null && step.referenceAssetId() == null);
        assertThat(imported.warnings()).containsExactlyElementsOf(exported.warnings());
        assertThat(imported.pipeline().steps()).allMatch(step -> step.assetId() == null && step.referenceAssetId() == null);
        assertThat(imported.pipeline().steps().get(0).contentType()).isEqualTo(PipelineStep.ContentType.MD_FILE);
    }

    private PipelineUpsertRequest graphRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Portable graph", "Every kind of edge", "pipeline-export-test",
                List.of(new PipelineUpsertRequest.ParameterRequest(
                        "folder", "Folder", PipelineParameter.Type.STRING, true, "/tmp")),
                List.of(
                        new PipelineUpsertRequest.StepRequest(
                                "Summarize", PipelineStep.ContentType.PROMPT, "Summarize the input", null, null,
                                10, 20,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("next", 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest(
                                        "round-trip-data", "summary", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest(
                                "Report", PipelineStep.ContentType.PROMPT,
                                "Use {{data:round-trip-data}} and {{data:round-trip-parameter}}", null, null,
                                300, 20, List.of(), List.of(), List.of(), null, null)),
                List.of(new PipelineUpsertRequest.ParameterLinkRequest(
                        "round-trip-parameter", "folder", 1)));
    }

    private PipelineUpsertRequest singleStepRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Single step", "desc", "pipeline-export-test", List.of(),
                List.of(new PipelineUpsertRequest.StepRequest(
                        "Only step", PipelineStep.ContentType.PROMPT, "do it", null, null,
                        0, 0, List.of(), List.of(), List.of(), null, null)));
    }
}
