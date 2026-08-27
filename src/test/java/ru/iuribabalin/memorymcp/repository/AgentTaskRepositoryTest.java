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
