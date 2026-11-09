package ru.iuribabalin.memorymcp.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.dto.TaskDetail;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.Task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void deleteRemovesTheTask() {
        taskService.start("task-svc-test-delete-project", "TSD-1", "Test task", Task.Source.MANUAL);

        boolean deleted = taskService.delete("task-svc-test-delete-project", "TSD-1");

        assertThat(deleted).isTrue();
        assertThat(taskService.list("task-svc-test-delete-project")).isEmpty();
    }

    @Test
    void deleteReturnsFalseWhenTaskDoesNotExist() {
        boolean deleted = taskService.delete("task-svc-test-delete-project-2", "NO-SUCH-TASK");

        assertThat(deleted).isFalse();
    }

    @Test
    void progressIsEmptyForAnActiveTaskWithNoSubtasks() {
        taskService.start("task-progress-empty-project", "TP-1", "Test task", Task.Source.MANUAL);

        TaskDetail.Progress progress = taskService.get("task-progress-empty-project", "TP-1").progress();

        assertThat(progress).isEqualTo(new TaskDetail.Progress(0, 0, 0));
    }

    @Test
    void progressIsFullForADoneTaskWithNoSubtasks() {
        taskService.start("task-progress-done-project", "TP-2", "Test task", Task.Source.MANUAL);
        taskService.close("task-progress-done-project", "TP-2");

        TaskDetail.Progress progress = taskService.get("task-progress-done-project", "TP-2").progress();

        // Nothing to count, so the task's own status is the only honest answer.
        assertThat(progress).isEqualTo(new TaskDetail.Progress(0, 0, 100));
    }

    @Test
    void progressCountsOnlyDoneSubtasksAndTreatsBlockedAsUnfinished() {
        taskService.start("task-progress-mixed-project", "TP-3", "Test task", Task.Source.MANUAL);
        done("task-progress-mixed-project", "TP-3", "Implement");
        inProgress("task-progress-mixed-project", "TP-3", "Review");
        blocked("task-progress-mixed-project", "TP-3", "Deploy");
        agentTaskService.create(
                "task-progress-mixed-project", "TP-3", "Document", AgentTask.Type.REPORTING, "label", null);

        TaskDetail.Progress progress = taskService.get("task-progress-mixed-project", "TP-3").progress();

        assertThat(progress).isEqualTo(new TaskDetail.Progress(1, 4, 25));
    }

    @Test
    void theTaskListReportsTheSameProgressAsTheSingleTaskPath() {
        taskService.start("task-progress-list-project", "TPL-1", "One subtask", Task.Source.MANUAL);
        done("task-progress-list-project", "TPL-1", "Implement");
        taskService.start("task-progress-list-project", "TPL-2", "Two subtasks", Task.Source.MANUAL);
        done("task-progress-list-project", "TPL-2", "Implement");
        inProgress("task-progress-list-project", "TPL-2", "Review");
        taskService.start("task-progress-list-project", "TPL-3", "No subtasks", Task.Source.MANUAL);

        // The list path batches the counts in one grouped query, the single-task path counts per
        // task - the two must not drift apart.
        assertThat(taskService.list("task-progress-list-project"))
                .extracting(TaskSummary::taskKey, TaskSummary::progress)
                .containsExactlyInAnyOrder(
                        tuple(
                                "TPL-1", taskService.get("task-progress-list-project", "TPL-1").progress()),
                        tuple(
                                "TPL-2", taskService.get("task-progress-list-project", "TPL-2").progress()),
                        tuple(
                                "TPL-3", taskService.get("task-progress-list-project", "TPL-3").progress()));
        assertThat(taskService.get("task-progress-list-project", "TPL-2").progress())
                .isEqualTo(new TaskDetail.Progress(1, 2, 50));
    }

    @Test
    void linkingASpecReplacesThePreviousLink() {
        taskService.start("task-spec-link-project", "TSL-1", "Test task", Task.Source.MANUAL);
        saveEntry("task-spec-link-first", "task-spec-link-project", "TSL-1");
        saveEntry("task-spec-link-second", "task-spec-link-project", "TSL-1");

        taskService.setSpec("task-spec-link-project", "TSL-1", "task-spec-link-first");
        TaskDetail relinked = taskService.setSpec("task-spec-link-project", "TSL-1", "task-spec-link-second");

        assertThat(relinked.spec().name()).isEqualTo("task-spec-link-second");
        // Relinking only moves the pointer - the entry that was linked before still exists.
        assertThat(memoryService.get("task-spec-link-first").name()).isEqualTo("task-spec-link-first");
    }

    @Test
    void linkingAnUnknownEntryAsSpecFails() {
        taskService.start("task-spec-missing-project", "TSM-1", "Test task", Task.Source.MANUAL);

        assertThatThrownBy(() -> taskService.setSpec("task-spec-missing-project", "TSM-1", "no-such-entry"))
                .isInstanceOf(MemoryNotFoundException.class);
    }

    @Test
    void deletingTheSpecEntryLeavesTheTaskWithoutASpec() {
        taskService.start("task-spec-delete-project", "TSD-2", "Test task", Task.Source.MANUAL);
        saveEntry("task-spec-delete-entry", "task-spec-delete-project", "TSD-2");
        taskService.setSpec("task-spec-delete-project", "TSD-2", "task-spec-delete-entry");

        memoryService.delete("task-spec-delete-entry");
        // The FK is ON DELETE SET NULL, so the database - not Hibernate - clears the column; the
        // cached Task would still carry the old id without this.
        entityManager.flush();
        entityManager.clear();

        TaskDetail detail = taskService.get("task-spec-delete-project", "TSD-2");
        assertThat(detail.spec()).isNull();
        assertThat(detail.taskKey()).isEqualTo("TSD-2");
    }

    @Test
    void cancelMarksTheTaskCancelled() {
        taskService.start("task-cancel-project", "TC-1", "Test task", Task.Source.MANUAL);

        boolean cancelled = taskService.cancel("task-cancel-project", "TC-1");

        assertThat(cancelled).isTrue();
        assertThat(taskService.get("task-cancel-project", "TC-1").status()).isEqualTo(Task.Status.CANCELLED);
    }

    @Test
    void cancelReturnsFalseWhenTaskDoesNotExist() {
        assertThat(taskService.cancel("task-cancel-project-2", "NO-SUCH-TASK")).isFalse();
    }

    private void saveEntry(String name, String projectScope, String taskKey) {
        memoryService.save(new SaveMemoryRequest(
                name, MemoryNode.Type.PROJECT, "spec", "# spec", projectScope, taskKey, null, null, null));
    }

    private AgentTaskSummary done(String projectScope, String taskKey, String title) {
        AgentTaskSummary created = agentTaskService.create(
                projectScope, taskKey, title, AgentTask.Type.IMPLEMENTATION, "label", null);
        return agentTaskService.update(projectScope, taskKey, created.id(), AgentTask.Status.DONE, null, null);
    }

    private AgentTaskSummary inProgress(String projectScope, String taskKey, String title) {
        AgentTaskSummary created = agentTaskService.create(
                projectScope, taskKey, title, AgentTask.Type.REVIEW, "label", null);
        return agentTaskService.update(projectScope, taskKey, created.id(), AgentTask.Status.IN_PROGRESS, null, null);
    }

    private AgentTaskSummary blocked(String projectScope, String taskKey, String title) {
        AgentTaskSummary created = agentTaskService.create(
                projectScope, taskKey, title, AgentTask.Type.IMPLEMENTATION, "label", null);
        return agentTaskService.update(projectScope, taskKey, created.id(), AgentTask.Status.BLOCKED, null, null);
    }
}
