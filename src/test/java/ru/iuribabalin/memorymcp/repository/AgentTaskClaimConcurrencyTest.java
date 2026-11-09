package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.AfterEach;
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

    private Long committedTaskId;
    private Long committedAgentTaskId;

    /**
     * The fixture below is committed for real (see the class comment), so it outlives the test and
     * would otherwise surface in the dashboard as a junk project. Cleaning up here rather than in
     * the test body covers the paths where the test never reaches its own cleanup.
     */
    @AfterEach
    void removeCommittedFixture() {
        if (committedAgentTaskId != null) {
            repository.deleteById(committedAgentTaskId);
        }
        if (committedTaskId != null) {
            taskRepository.deleteById(committedTaskId);
        }
    }

    @Test
    void exactlyOneConcurrentClaimSucceedsOnTheSameSubtask() throws Exception {
        // Random suffix: this test is deliberately not @Transactional (see class comment), so the
        // Task row it creates is committed for real. A fixed key would collide with the
        // ux_tasks_project_key unique constraint on any re-run; removeCommittedFixture() deletes
        // the rows afterwards.
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
        committedTaskId = savedTask.getId();

        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(savedTask.getId());
        agentTask.setTitle("Contested subtask");
        agentTask.setType(AgentTask.Type.IMPLEMENTATION);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setCreatedAt(Instant.now());
        agentTask.setUpdatedAt(Instant.now());
        AgentTask savedAgentTask = repository.saveAndFlush(agentTask);
        committedAgentTaskId = savedAgentTask.getId();

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> tasks = Collections.nCopies(attempts, (Callable<Integer>) () ->
                    repository.claimIfAvailable(savedAgentTask.getId(), savedTask.getId(), Instant.now()));
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            int totalClaimed = 0;
            for (Future<Integer> future : futures) {
                totalClaimed += future.get();
            }

            assertThat(totalClaimed).isEqualTo(1);
            assertThat(repository.findByIdAndTaskId(savedAgentTask.getId(), savedTask.getId()).orElseThrow().getStatus())
                    .isEqualTo(AgentTask.Status.IN_PROGRESS);
        } finally {
            pool.shutdown();
        }
    }
}
