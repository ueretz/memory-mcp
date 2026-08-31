package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.service.PipelineRunNotFoundException;
import ru.iuribabalin.memorymcp.service.PipelineRunService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineRunControllerTest {

    @Mock
    private PipelineRunService pipelineRunService;

    @InjectMocks
    private PipelineRunController pipelineRunController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pipelineRunController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getReturns404ForUnknownRun() throws Exception {
        when(pipelineRunService.get(99L)).thenThrow(new PipelineRunNotFoundException(99L));

        mockMvc.perform(get("/api/pipeline-runs/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturnsTheRun() throws Exception {
        when(pipelineRunService.get(1L)).thenReturn(new PipelineRunDetail(
                1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, "Tester", List.of()));

        mockMvc.perform(get("/api/pipeline-runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineSlug").value("config-diff"));
    }
}
