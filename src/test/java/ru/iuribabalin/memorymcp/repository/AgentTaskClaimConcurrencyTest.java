package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately NOT @Transactional: concurrent threads need independently-committed reads/writes
 * on separate connections to actually exercise Postgres's row-level locking - a single enclosing
 * test transaction would hide any race instead of catching it.
 */
@SpringBootTest
class AgentTaskClaimConcurrencyTest {

    @Autowired
    private AgentTaskRepository repository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void exactlyOneConcurrentClaimSucceedsOnTheSameSubtask() throws Exception {
        // Random suffix: this test is deliberately not @Transactional (see class comment), so the
        // Task row it creates is committed for real and survives the test. A fixed key would
        // collide with the ux_tasks_project_key unique constraint on any re-run.
        String uniqueSuffix = UUID.randomUUID().toString();
        Task task = new Task();
        task.setProjectScope("agent-task-claim-race-project-" + uniqueSuffix);
        task.setTaskKey("AT-RACE-" + uniqueSuffix);
        task.setTitle("Race test task");
        task.setSource(Task.Source.MANUAL);
        task.setStatus(Task.Status.ACTIVE);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        Task savedTask = taskRepository.saveAndFlush(task);

        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(savedTask.getId());
        agentTask.setTitle("Contested subtask");
        agentTask.setType(AgentTask.Type.IMPLEMENTATION);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setCreatedAt(Instant.now());
        agentTask.setUpdatedAt(Instant.now());
        AgentTask savedAgentTask = repository.saveAndFlush(agentTask);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        int totalClaimed;
        try {
            List<Callable<Integer>> tasks = Collections.nCopies(attempts, (Callable<Integer>) () ->
                    repository.claimIfAvailable(savedAgentTask.getId(), savedTask.getId(), Instant.now()));
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            totalClaimed = 0;
            for (Future<Integer> future : futures) {
                totalClaimed += future.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(totalClaimed).isEqualTo(1);
        assertThat(repository.findByIdAndTaskId(savedAgentTask.getId(), savedTask.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentTask.Status.IN_PROGRESS);
    }
}
