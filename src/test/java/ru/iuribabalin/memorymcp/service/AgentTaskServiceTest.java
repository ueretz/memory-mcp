package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AgentTaskServiceTest {

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private TaskService taskService;

    @Test
    void createsAndListsAgentTasksInCreationOrder() {
        taskService.start("agent-task-svc-test-project", "AT-1", "Test task", Task.Source.MANUAL);

        agentTaskService.create("agent-task-svc-test-project", "AT-1", "Write tests", AgentTask.Type.TESTING, "desc");
        agentTaskService.create("agent-task-svc-test-project", "AT-1", "Analyze", AgentTask.Type.ANALYSIS, "desc");

        assertThat(agentTaskService.list("agent-task-svc-test-project", "AT-1", null, null))
                .extracting(AgentTaskSummary::title)
                .containsExactly("Write tests", "Analyze");
    }

    @Test
    void filtersListByStatus() {
        taskService.start("agent-task-svc-test-status-project", "AT-2", "Test task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-status-project", "AT-2", "Implement", AgentTask.Type.IMPLEMENTATION, "desc");
        agentTaskService.update(
                "agent-task-svc-test-status-project", "AT-2", created.id(), AgentTask.Status.IN_PROGRESS, null, null);
        agentTaskService.create(
                "agent-task-svc-test-status-project", "AT-2", "Another", AgentTask.Type.IMPLEMENTATION, "desc");

        assertThat(agentTaskService.list(
                "agent-task-svc-test-status-project", "AT-2", null, AgentTask.Status.IN_PROGRESS))
                .extracting(AgentTaskSummary::title)
                .containsExactly("Implement");
    }

    @Test
    void updatePartiallyChangesOnlyGivenFields() {
        taskService.start("agent-task-svc-test-update-project", "AT-3", "Test task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-update-project", "AT-3", "Original title", AgentTask.Type.REVIEW, "original desc");

        AgentTaskSummary updated = agentTaskService.update(
                "agent-task-svc-test-update-project", "AT-3", created.id(), AgentTask.Status.DONE, null, "updated desc");

        assertThat(updated.title()).isEqualTo("Original title");
        assertThat(updated.status()).isEqualTo(AgentTask.Status.DONE);
        assertThat(updated.description()).isEqualTo("updated desc");
    }

    @Test
    void throwsWhenUpdatingAnAgentTaskFromADifferentTask() {
        taskService.start("agent-task-svc-test-cross-a", "AT-4", "Task A", Task.Source.MANUAL);
        taskService.start("agent-task-svc-test-cross-b", "AT-5", "Task B", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-cross-a", "AT-4", "Belongs to A", AgentTask.Type.ANALYSIS, "desc");

        assertThatThrownBy(() -> agentTaskService.update(
                "agent-task-svc-test-cross-b", "AT-5", created.id(), AgentTask.Status.DONE, null, null))
                .isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void deleteRemovesTheAgentTask() {
        taskService.start("agent-task-svc-test-delete-project", "AT-6", "Test task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-delete-project", "AT-6", "To delete", AgentTask.Type.TESTING, "desc");

        agentTaskService.delete("agent-task-svc-test-delete-project", "AT-6", created.id());

        assertThat(agentTaskService.list("agent-task-svc-test-delete-project", "AT-6", null, null)).isEmpty();
    }

    @Test
    void throwsWhenCreatingUnderANonExistentTask() {
        assertThatThrownBy(() -> agentTaskService.create(
                "agent-task-svc-test-missing-project", "NO-SUCH-TASK", "title", AgentTask.Type.ANALYSIS, "desc"))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
