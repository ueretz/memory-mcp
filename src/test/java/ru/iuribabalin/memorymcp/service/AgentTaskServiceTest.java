package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;

import java.util.List;

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

        agentTaskService.create("agent-task-svc-test-project", "AT-1", "Write tests", AgentTask.Type.TESTING, "desc", null);
        agentTaskService.create("agent-task-svc-test-project", "AT-1", "Analyze", AgentTask.Type.ANALYSIS, "desc", null);

        assertThat(agentTaskService.list("agent-task-svc-test-project", "AT-1", null, null, false))
                .extracting(AgentTaskSummary::title)
                .containsExactly("Write tests", "Analyze");
    }

    @Test
    void filtersListByStatus() {
        taskService.start("agent-task-svc-test-status-project", "AT-2", "Test task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-status-project", "AT-2", "Implement", AgentTask.Type.IMPLEMENTATION, "desc", null);
        agentTaskService.update(
                "agent-task-svc-test-status-project", "AT-2", created.id(), AgentTask.Status.IN_PROGRESS, null, null);
        agentTaskService.create(
                "agent-task-svc-test-status-project", "AT-2", "Another", AgentTask.Type.IMPLEMENTATION, "desc", null);

        assertThat(agentTaskService.list(
                "agent-task-svc-test-status-project", "AT-2", null, AgentTask.Status.IN_PROGRESS, false))
                .extracting(AgentTaskSummary::title)
                .containsExactly("Implement");
    }

    @Test
    void updatePartiallyChangesOnlyGivenFields() {
        taskService.start("agent-task-svc-test-update-project", "AT-3", "Test task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-update-project", "AT-3", "Original title", AgentTask.Type.REVIEW, "original desc", null);

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
                "agent-task-svc-test-cross-a", "AT-4", "Belongs to A", AgentTask.Type.ANALYSIS, "desc", null);

        assertThatThrownBy(() -> agentTaskService.update(
                "agent-task-svc-test-cross-b", "AT-5", created.id(), AgentTask.Status.DONE, null, null))
                .isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void deleteRemovesTheAgentTask() {
        taskService.start("agent-task-svc-test-delete-project", "AT-6", "Test task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-delete-project", "AT-6", "To delete", AgentTask.Type.TESTING, "desc", null);

        agentTaskService.delete("agent-task-svc-test-delete-project", "AT-6", created.id());

        assertThat(agentTaskService.list("agent-task-svc-test-delete-project", "AT-6", null, null, false)).isEmpty();
    }

    @Test
    void throwsWhenCreatingUnderANonExistentTask() {
        assertThatThrownBy(() -> agentTaskService.create(
                "agent-task-svc-test-missing-project", "NO-SUCH-TASK", "title", AgentTask.Type.ANALYSIS, "desc", null))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void createValidatesDependsOnIdBelongsToTheSameTask() {
        taskService.start("agent-task-svc-test-dep-a", "AT-DEP-1", "Task A", Task.Source.MANUAL);
        taskService.start("agent-task-svc-test-dep-b", "AT-DEP-2", "Task B", Task.Source.MANUAL);
        AgentTaskSummary inTaskA = agentTaskService.create(
                "agent-task-svc-test-dep-a", "AT-DEP-1", "Architecture", AgentTask.Type.ANALYSIS, "desc", null);

        assertThatThrownBy(() -> agentTaskService.create(
                "agent-task-svc-test-dep-b", "AT-DEP-2", "Impl", AgentTask.Type.IMPLEMENTATION, "desc", inTaskA.id()))
                .isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void createAcceptsAValidDependsOnIdInTheSameTask() {
        taskService.start("agent-task-svc-test-dep-ok", "AT-DEP-3", "Task", Task.Source.MANUAL);
        AgentTaskSummary architecture = agentTaskService.create(
                "agent-task-svc-test-dep-ok", "AT-DEP-3", "Architecture", AgentTask.Type.ANALYSIS, "desc", null);

        AgentTaskSummary implementation = agentTaskService.create(
                "agent-task-svc-test-dep-ok", "AT-DEP-3", "Impl", AgentTask.Type.IMPLEMENTATION, "desc", architecture.id());

        assertThat(implementation.dependsOnId()).isEqualTo(architecture.id());
    }

    @Test
    void claimMovesATodoSubtaskToInProgress() {
        taskService.start("agent-task-svc-test-claim-1", "AT-CLAIM-SVC-1", "Task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-claim-1", "AT-CLAIM-SVC-1", "Impl", AgentTask.Type.IMPLEMENTATION, "desc", null);

        AgentTaskSummary claimed = agentTaskService.claim("agent-task-svc-test-claim-1", "AT-CLAIM-SVC-1", created.id());

        assertThat(claimed.status()).isEqualTo(AgentTask.Status.IN_PROGRESS);
    }

    @Test
    void claimThrowsWhenAlreadyClaimed() {
        taskService.start("agent-task-svc-test-claim-2", "AT-CLAIM-SVC-2", "Task", Task.Source.MANUAL);
        AgentTaskSummary created = agentTaskService.create(
                "agent-task-svc-test-claim-2", "AT-CLAIM-SVC-2", "Impl", AgentTask.Type.IMPLEMENTATION, "desc", null);
        agentTaskService.claim("agent-task-svc-test-claim-2", "AT-CLAIM-SVC-2", created.id());

        assertThatThrownBy(() -> agentTaskService.claim("agent-task-svc-test-claim-2", "AT-CLAIM-SVC-2", created.id()))
                .isInstanceOf(AgentTaskNotClaimableException.class);
    }

    @Test
    void claimThrowsWhenDependencyIsNotDoneYet() {
        taskService.start("agent-task-svc-test-claim-3", "AT-CLAIM-SVC-3", "Task", Task.Source.MANUAL);
        AgentTaskSummary architecture = agentTaskService.create(
                "agent-task-svc-test-claim-3", "AT-CLAIM-SVC-3", "Architecture", AgentTask.Type.ANALYSIS, "desc", null);
        AgentTaskSummary implementation = agentTaskService.create(
                "agent-task-svc-test-claim-3", "AT-CLAIM-SVC-3", "Impl", AgentTask.Type.IMPLEMENTATION, "desc", architecture.id());

        assertThatThrownBy(() -> agentTaskService.claim("agent-task-svc-test-claim-3", "AT-CLAIM-SVC-3", implementation.id()))
                .isInstanceOf(AgentTaskNotClaimableException.class);
    }

    @Test
    void claimSucceedsOnceTheDependencyIsDone() {
        taskService.start("agent-task-svc-test-claim-4", "AT-CLAIM-SVC-4", "Task", Task.Source.MANUAL);
        AgentTaskSummary architecture = agentTaskService.create(
                "agent-task-svc-test-claim-4", "AT-CLAIM-SVC-4", "Architecture", AgentTask.Type.ANALYSIS, "desc", null);
        agentTaskService.update("agent-task-svc-test-claim-4", "AT-CLAIM-SVC-4", architecture.id(), AgentTask.Status.DONE, null, null);
        AgentTaskSummary implementation = agentTaskService.create(
                "agent-task-svc-test-claim-4", "AT-CLAIM-SVC-4", "Impl", AgentTask.Type.IMPLEMENTATION, "desc", architecture.id());

        AgentTaskSummary claimed = agentTaskService.claim("agent-task-svc-test-claim-4", "AT-CLAIM-SVC-4", implementation.id());

        assertThat(claimed.status()).isEqualTo(AgentTask.Status.IN_PROGRESS);
    }

    @Test
    void listWithClaimableTrueIgnoresTheStatusFilter() {
        taskService.start("agent-task-svc-test-claimable-1", "AT-CLAIMABLE-SVC-1", "Task", Task.Source.MANUAL);
        AgentTaskSummary todo = agentTaskService.create(
                "agent-task-svc-test-claimable-1", "AT-CLAIMABLE-SVC-1", "Todo", AgentTask.Type.IMPLEMENTATION, "desc", null);
        AgentTaskSummary done = agentTaskService.create(
                "agent-task-svc-test-claimable-1", "AT-CLAIMABLE-SVC-1", "Done", AgentTask.Type.IMPLEMENTATION, "desc", null);
        agentTaskService.update("agent-task-svc-test-claimable-1", "AT-CLAIMABLE-SVC-1", done.id(), AgentTask.Status.DONE, null, null);

        List<AgentTaskSummary> result = agentTaskService.list(
                "agent-task-svc-test-claimable-1", "AT-CLAIMABLE-SVC-1", null, AgentTask.Status.DONE, true);

        assertThat(result).extracting(AgentTaskSummary::title).containsExactly("Todo");
    }
}
