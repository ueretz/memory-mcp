package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineExportEnvelope;
import ru.iuribabalin.memorymcp.dto.PipelineImportResponse;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.service.PipelineExportService;
import ru.iuribabalin.memorymcp.service.PipelineUnsupportedFormatVersionException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineExportControllerTest {

    @Mock
    private PipelineExportService exportService;

    @InjectMocks
    private PipelineExportController exportController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(exportController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void exportReturnsAJsonAttachmentNamedAfterThePipeline() throws Exception {
        when(exportService.export("portable")).thenReturn(new PipelineExportEnvelope(
                1, request("portable"), List.of("Step 'File' lost its asset references")));

        mockMvc.perform(get("/api/pipelines/portable/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"portable.pipeline.json\""))
                .andExpect(jsonPath("$.formatVersion").value(1))
                .andExpect(jsonPath("$.pipeline.slug").value("portable"))
                .andExpect(jsonPath("$.pipeline.steps[0].assetId").doesNotExist())
                .andExpect(jsonPath("$.warnings[0]").value("Step 'File' lost its asset references"));
    }

    @Test
    void importReturnsTheCreatedPipelineAndWarnings() throws Exception {
        PipelineDetail detail = new PipelineDetail(
                2L, "portable-2", "Portable", "desc", "scope", List.of(), List.of(), List.of(),
                null, null, null);
        when(exportService.importPipeline(any(), isNull())).thenReturn(new PipelineImportResponse(
                detail, List.of("Step 'File' lost its asset references")));

        mockMvc.perform(post("/api/pipelines/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatVersion\":1,\"pipeline\":{\"slug\":\"portable\"},\"warnings\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pipeline.slug").value("portable-2"))
                .andExpect(jsonPath("$.warnings[0]").value("Step 'File' lost its asset references"));
    }

    @Test
    void importRejectsAnUnknownFormatVersionWithBadRequest() throws Exception {
        when(exportService.importPipeline(any(), isNull()))
                .thenThrow(new PipelineUnsupportedFormatVersionException(99));

        mockMvc.perform(post("/api/pipelines/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formatVersion\":99,\"pipeline\":{\"slug\":\"portable\"},\"warnings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Unsupported pipeline export formatVersion 99; supported version is 1"));
    }

    private PipelineUpsertRequest request(String slug) {
        return new PipelineUpsertRequest(
                slug, "Portable", "desc", "scope", List.of(),
                List.of(new PipelineUpsertRequest.StepRequest(
                        "Only step", PipelineStep.ContentType.PROMPT, "do it", null, null,
                        0, 0, List.of(), List.of(), List.of(), null, null)));
    }
}
