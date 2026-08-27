package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AgentTaskRepositoryTest {

    @Autowired
    private AgentTaskRepository repository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void ordersByCreationTimeRegardlessOfType() {
        Task task = saveTask("agent-task-repo-test-project", "AT-REPO-1");

        save(task, "Write tests", AgentTask.Type.TESTING);
        save(task, "Analyze", AgentTask.Type.ANALYSIS);
        save(task, "Implement", AgentTask.Type.IMPLEMENTATION);

        List<AgentTask> ordered = repository.findByTaskIdOrderByCreatedAtAsc(task.getId());

        assertThat(ordered).extracting(AgentTask::getTitle)
                .containsExactly("Write tests", "Analyze", "Implement");
    }

    @Test
    void findByIdAndTaskIdOnlyMatchesTheOwningTask() {
        Task taskA = saveTask("agent-task-repo-test-project-a", "AT-REPO-2");
        Task taskB = saveTask("agent-task-repo-test-project-b", "AT-REPO-3");
        AgentTask agentTask = save(taskA, "Belongs to A", AgentTask.Type.ANALYSIS);

        assertThat(repository.findByIdAndTaskId(agentTask.getId(), taskA.getId())).isPresent();
        assertThat(repository.findByIdAndTaskId(agentTask.getId(), taskB.getId())).isEmpty();
    }

    @Test
    void claimIfAvailableSucceedsOnATodoSubtaskWithNoDependency() {
        Task task = saveTask("agent-task-repo-test-claim-project", "AT-CLAIM-1");
        AgentTask agentTask = save(task, "Claimable", AgentTask.Type.IMPLEMENTATION);

        int updated = repository.claimIfAvailable(agentTask.getId(), task.getId(), Instant.now());

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findByIdAndTaskId(agentTask.getId(), task.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentTask.Status.IN_PROGRESS);
    }

    @Test
    void claimIfAvailableFailsWhenNotTodo() {
        Task task = saveTask("agent-task-repo-test-claim-project-2", "AT-CLAIM-2");
        AgentTask agentTask = save(task, "Already started", AgentTask.Type.IMPLEMENTATION);
        agentTask.setStatus(AgentTask.Status.IN_PROGRESS);
        repository.saveAndFlush(agentTask);

        int updated = repository.claimIfAvailable(agentTask.getId(), task.getId(), Instant.now());

        assertThat(updated).isEqualTo(0);
    }

    @Test
    void claimIfAvailableFailsWhenDependencyNotDone() {
        Task task = saveTask("agent-task-repo-test-claim-project-3", "AT-CLAIM-3");
        AgentTask dependency = save(task, "Architecture", AgentTask.Type.ANALYSIS);
        AgentTask dependent = save(task, "Implementation", AgentTask.Type.IMPLEMENTATION);
        dependent.setDependsOnId(dependency.getId());
        repository.saveAndFlush(dependent);

        int updated = repository.claimIfAvailable(dependent.getId(), task.getId(), Instant.now());

        assertThat(updated).isEqualTo(0);
    }

    @Test
    void claimIfAvailableSucceedsWhenDependencyIsDone() {
        Task task = saveTask("agent-task-repo-test-claim-project-4", "AT-CLAIM-4");
        AgentTask dependency = save(task, "Architecture", AgentTask.Type.ANALYSIS);
        dependency.setStatus(AgentTask.Status.DONE);
        repository.saveAndFlush(dependency);
        AgentTask dependent = save(task, "Implementation", AgentTask.Type.IMPLEMENTATION);
        dependent.setDependsOnId(dependency.getId());
        repository.saveAndFlush(dependent);

        int updated = repository.claimIfAvailable(dependent.getId(), task.getId(), Instant.now());

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void findClaimableReturnsOnlyUnblockedTodoSubtasksInCreationOrder() {
        Task task = saveTask("agent-task-repo-test-claimable-project", "AT-CLAIMABLE-1");
        AgentTask freeTodo = save(task, "Free", AgentTask.Type.IMPLEMENTATION);
        AgentTask doneDependency = save(task, "Done dep", AgentTask.Type.ANALYSIS);
        doneDependency.setStatus(AgentTask.Status.DONE);
        repository.saveAndFlush(doneDependency);
        AgentTask unblockedByDoneDep = save(task, "Unblocked", AgentTask.Type.IMPLEMENTATION);
        unblockedByDoneDep.setDependsOnId(doneDependency.getId());
        repository.saveAndFlush(unblockedByDoneDep);
        AgentTask pendingDependency = save(task, "Pending dep", AgentTask.Type.ANALYSIS);
        AgentTask blocked = save(task, "Blocked", AgentTask.Type.IMPLEMENTATION);
        blocked.setDependsOnId(pendingDependency.getId());
        repository.saveAndFlush(blocked);
        AgentTask inProgress = save(task, "In progress", AgentTask.Type.IMPLEMENTATION);
        inProgress.setStatus(AgentTask.Status.IN_PROGRESS);
        repository.saveAndFlush(inProgress);

        List<AgentTask> claimable = repository.findClaimable(task.getId());

        // "Pending dep" has no dependency of its own, so it is itself claimable even though it is
        // the (unmet) dependency that keeps "Blocked" from being claimable.
        assertThat(claimable).extracting(AgentTask::getTitle)
                .containsExactly("Free", "Unblocked", "Pending dep");
    }

    private Task saveTask(String projectScope, String taskKey) {
        Task task = new Task();
        task.setProjectScope(projectScope);
        task.setTaskKey(taskKey);
        task.setTitle("Test task");
        task.setSource(Task.Source.MANUAL);
        task.setStatus(Task.Status.ACTIVE);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return taskRepository.saveAndFlush(task);
    }

    private AgentTask save(Task task, String title, AgentTask.Type type) {
        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(task.getId());
        agentTask.setTitle(title);
        agentTask.setType(type);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setCreatedAt(Instant.now());
        agentTask.setUpdatedAt(Instant.now());
        return repository.saveAndFlush(agentTask);
    }
}
