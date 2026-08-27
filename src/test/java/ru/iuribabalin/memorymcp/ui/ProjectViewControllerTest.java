package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.service.AgentTaskService;
import ru.iuribabalin.memorymcp.service.ProjectService;
import ru.iuribabalin.memorymcp.service.TaskNotFoundException;
import ru.iuribabalin.memorymcp.service.TaskService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProjectViewControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TaskService taskService;

    @Mock
    private AgentTaskService agentTaskService;

    @InjectMocks
    private ProjectViewController projectViewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectViewController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsAgentTasksForATask() throws Exception {
        AgentTaskSummary summary = new AgentTaskSummary(
                1L, "Analyze", AgentTask.Type.ANALYSIS, AgentTask.Status.DONE, "desc", Instant.now());
        when(agentTaskService.list("memory-mcp", "AT-1", null, null)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/AT-1/agent-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Analyze"));
    }

    @Test
    void returns404WhenTaskDoesNotExist() throws Exception {
        when(agentTaskService.list("memory-mcp", "NO-SUCH", null, null))
                .thenThrow(new TaskNotFoundException("memory-mcp", "NO-SUCH"));

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/NO-SUCH/agent-tasks"))
                .andExpect(status().isNotFound());
    }
}
