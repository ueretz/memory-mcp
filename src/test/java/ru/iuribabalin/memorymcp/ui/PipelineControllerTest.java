package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.service.PipelineNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {

    @Mock
    private PipelineService pipelineService;

    @InjectMocks
    private PipelineController pipelineController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pipelineController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsPipelinesForAProject() throws Exception {
        when(pipelineService.list("memory-mcp"))
                .thenReturn(List.of(new PipelineSummary(1L, "config-diff", "Config diff", "desc", "memory-mcp", 1, 2, "Tester", Instant.now())));

        mockMvc.perform(get("/api/pipelines").param("projectScope", "memory-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("config-diff"));
    }

    @Test
    void deleteReturns404WhenPipelineIsMissing() throws Exception {
        when(pipelineService.delete("missing")).thenThrow(new PipelineNotFoundException("missing"));

        mockMvc.perform(delete("/api/pipelines/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContentOnSuccess() throws Exception {
        when(pipelineService.delete("config-diff")).thenReturn(true);

        mockMvc.perform(delete("/api/pipelines/config-diff"))
                .andExpect(status().isNoContent());
    }

    @Test
    void createDeserializesAWatcherStepsConfigFromJson() throws Exception {
        String body = """
                {
                  "slug": "watch-build",
                  "name": "Watch the build",
                  "description": null,
                  "projectScope": "memory-mcp",
                  "parameters": [],
                  "parameterLinks": [],
                  "steps": [
                    {
                      "title": "Wait for the build",
                      "contentType": "WATCHER",
                      "promptText": "https://gitlab.example.com/g/p/-/merge_requests/42",
                      "assetId": null,
                      "referenceAssetId": null,
                      "positionX": 0,
                      "positionY": 0,
                      "routes": [
                        {"outcomeKey": "success", "targetStepIndex": null},
                        {"outcomeKey": "failed", "targetStepIndex": null},
                        {"outcomeKey": "timeout", "targetStepIndex": null}
                      ],
                      "outputs": [],
                      "dataLinksOut": [],
                      "conditionOperator": null,
                      "conditionValue": null,
                      "conditionMode": "VALUE",
                      "maxIterations": null,
                      "watchSource": "GITLAB_MR",
                      "watchAspect": "PIPELINE",
                      "checkIntervalSeconds": 60,
                      "watchTimeoutSeconds": 86400
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/pipelines").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineUpsertRequest> captor = ArgumentCaptor.forClass(PipelineUpsertRequest.class);
        verify(pipelineService).create(captor.capture(), isNull());
        PipelineUpsertRequest.StepRequest step = captor.getValue().steps().get(0);
        assertThat(step.contentType()).isEqualTo(PipelineStep.ContentType.WATCHER);
        assertThat(step.watchSource()).isEqualTo(PipelineStep.WatchSource.GITLAB_MR);
        assertThat(step.watchAspect()).isEqualTo(PipelineStep.WatchAspect.PIPELINE);
        assertThat(step.checkIntervalSeconds()).isEqualTo(60);
        assertThat(step.watchTimeoutSeconds()).isEqualTo(86400);
    }
}
