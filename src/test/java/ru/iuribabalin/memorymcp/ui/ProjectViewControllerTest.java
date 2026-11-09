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
import ru.iuribabalin.memorymcp.dto.SpecSummary;
import ru.iuribabalin.memorymcp.dto.TaskDetail;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.service.AgentTaskService;
import ru.iuribabalin.memorymcp.service.ProjectService;
import ru.iuribabalin.memorymcp.service.TaskNotFoundException;
import ru.iuribabalin.memorymcp.service.TaskService;
import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
                1L, "Analyze", AgentTask.Type.ANALYSIS, AgentTask.Status.DONE, "desc", null, Instant.now(), null);
        when(agentTaskService.list("memory-mcp", "AT-1", null, null, false)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/AT-1/agent-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Analyze"));
    }

    @Test
    void returns404WhenTaskDoesNotExist() throws Exception {
        when(agentTaskService.list("memory-mcp", "NO-SUCH", null, null, false))
                .thenThrow(new TaskNotFoundException("memory-mcp", "NO-SUCH"));

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/NO-SUCH/agent-tasks"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesATask() throws Exception {
        when(taskService.delete("memory-mcp", "AT-1")).thenReturn(true);

        mockMvc.perform(delete("/api/projects/memory-mcp/tasks/AT-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returns404WhenDeletingAMissingTask() throws Exception {
        when(taskService.delete("memory-mcp", "NO-SUCH")).thenReturn(false);

        mockMvc.perform(delete("/api/projects/memory-mcp/tasks/NO-SUCH"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAProject() throws Exception {
        mockMvc.perform(delete("/api/projects/memory-mcp"))
                .andExpect(status().isNoContent());

        verify(projectService).delete("memory-mcp");
    }

    @Test
    void servesOneTaskWithItsSpecAndProgress() throws Exception {
        Instant now = Instant.now();
        TaskDetail detail = new TaskDetail(
                "AT-1", "Test task", Task.Source.MANUAL, Task.Status.ACTIVE,
                new SpecSummary("at-1-spec", "What has to be done", now),
                new TaskDetail.Progress(1, 4, 25), now, now);
        when(taskService.get("memory-mcp", "AT-1")).thenReturn(detail);

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/AT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskKey").value("AT-1"))
                .andExpect(jsonPath("$.spec.name").value("at-1-spec"))
                .andExpect(jsonPath("$.progress.done").value(1))
                .andExpect(jsonPath("$.progress.total").value(4))
                .andExpect(jsonPath("$.progress.percent").value(25));
    }

    @Test
    void servesATaskWithoutASpecAsNull() throws Exception {
        Instant now = Instant.now();
        when(taskService.get("memory-mcp", "AT-2")).thenReturn(new TaskDetail(
                "AT-2", null, Task.Source.MANUAL, Task.Status.CANCELLED, null,
                new TaskDetail.Progress(0, 0, 0), now, now));

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/AT-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spec").doesNotExist())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void returns404WhenTheRequestedTaskDoesNotExist() throws Exception {
        when(taskService.get("memory-mcp", "NO-SUCH"))
                .thenThrow(new TaskNotFoundException("memory-mcp", "NO-SUCH"));

        mockMvc.perform(get("/api/projects/memory-mcp/tasks/NO-SUCH"))
                .andExpect(status().isNotFound());
    }
}
