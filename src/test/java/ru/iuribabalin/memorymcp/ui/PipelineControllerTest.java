package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.service.PipelineNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
