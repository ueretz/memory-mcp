# Multi-Session Parallel Agent Task Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let several independent Claude Code sessions safely collaborate on one task's agent-task board concurrently - atomic claim of `TODO` subtasks, an optional single-dependency link between subtasks, role-based multi-session guidance in the skill, and 3 new report sections (2 authored + 1 auto-aggregated).

**Architecture:** One nullable self-referencing FK (`depends_on_id`) plus one atomic conditional-UPDATE repository method (`claimIfAvailable`) are the entire concurrency-safety mechanism - no locks, no polling loop in the backend, just a single SQL statement that can only ever let one caller "win." Everything else (role detection, retry-on-conflict, dependency creation) is skill-level guidance for the agent, not new backend machinery.

**Tech Stack:** Same as the existing `agent_tasks` subsystem - Java 25 / Spring Boot / Spring Data JPA / PostgreSQL / Flyway (backend); Vue 3 + TypeScript (frontend); no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-27-agent-task-multi-session-design.md`

## Global Constraints

- `dependsOnId` is set **once, at creation only**, via `agent_task_create` - `agent_task_update`'s signature and semantics do not change at all in this plan.
- `claimIfAvailable` MUST use `@Modifying(clearAutomatically = true)` - a native bulk `UPDATE` bypasses Hibernate's persistence context, so without `clearAutomatically` a `findByIdAndTaskId` called right after (in the same transaction, as `AgentTaskService.claim()` does) can return a stale cached entity instead of the just-updated row.
- `agent_task_list`'s new `claimable: Boolean` param, when `true`, implies `status = TODO` - any `status` argument passed alongside it is silently ignored (not an error, not validated against).
- `AgentTaskSummary`'s new `dependsOnId` field is added as the **last** record component (after `updatedAt`) - every existing positional-argument call site (`new AgentTaskSummary(...)` in tests) needs a 7th argument appended, not inserted in the middle.
- Report template: `buildCriticalIssues()` must run **before** `buildOverview()` in the template's script (so Overview's per-section badge counts reflect the now-populated "Критичные ошибки" panel), and the top-level stat-grid's global critical/high counts must be summed per real-content panel (excluding the `#critical-issues` panel itself) rather than counted across the whole `document` - otherwise the duplicated callout markup inside "Критичные ошибки" double-counts the global tally.
- Backend tests: `@SpringBootTest @Transactional`, AssertJ, real Postgres, matching `AgentTaskServiceTest`/`AgentTaskRepositoryTest` conventions already in the codebase - **except** the new concurrency test (Task 1), which must NOT be `@Transactional` (concurrent threads need independently-committed reads/writes on separate connections, which a single enclosing test transaction would hide).
- Frontend: no automated test harness in `ui/` - verify via `npm run type-check`.

---

## Task 1: Schema, entity, atomic-claim repository method

**Files:**
- Create: `src/main/resources/db/migration/V8__add_agent_task_dependencies.sql`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/AgentTask.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepository.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepositoryTest.java`
- Create: `src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskClaimConcurrencyTest.java`

**Interfaces:**
- Produces: `AgentTask.getDependsOnId()/setDependsOnId(Long)`; `AgentTaskRepository.claimIfAvailable(Long id, Long taskId, Instant now) -> int` (0 or 1 rows affected); `AgentTaskRepository.findClaimable(Long taskId) -> List<AgentTask>` (only `TODO` + unblocked, ordered by `created_at`).

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE agent_tasks ADD COLUMN depends_on_id BIGINT REFERENCES agent_tasks (id) ON DELETE SET NULL;

CREATE INDEX idx_agent_tasks_depends_on_id ON agent_tasks (depends_on_id);
```

Save to `src/main/resources/db/migration/V8__add_agent_task_dependencies.sql`.

- [ ] **Step 2: Add the field to the entity**

In `AgentTask.java`, add the field and its accessors. Insert right after the `description` field/getter/setter block and before `createdAt`:

```java
    @Column(name = "depends_on_id")
    private Long dependsOnId;
```

and, next to the other getters/setters (after `getDescription()`/`setDescription()`):

```java
    public Long getDependsOnId() {
        return dependsOnId;
    }

    public void setDependsOnId(Long dependsOnId) {
        this.dependsOnId = dependsOnId;
    }
```

- [ ] **Step 3: Add the repository methods**

In `AgentTaskRepository.java`, change:

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.AgentTask;

import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<AgentTask> findByIdAndTaskId(Long id, Long taskId);
}
```

to:

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.AgentTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<AgentTask> findByIdAndTaskId(Long id, Long taskId);

    /**
     * Atomic compare-and-set: TODO -> IN_PROGRESS, only if the row is still TODO and (has no
     * dependency, or its dependency is DONE). Returns 0 or 1 - the caller distinguishes "doesn't
     * exist," "not TODO," and "dependency unmet" by re-reading the row when this returns 0.
     * clearAutomatically = true because this is a native bulk UPDATE that bypasses the
     * persistence context - without it, a findByIdAndTaskId in the same transaction right after
     * this call could return a stale cached entity instead of the row this just wrote.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE agent_tasks SET status = 'IN_PROGRESS', updated_at = :now " +
            "WHERE id = :id AND task_id = :taskId AND status = 'TODO' " +
            "AND (depends_on_id IS NULL OR depends_on_id IN (SELECT id FROM agent_tasks WHERE status = 'DONE'))",
            nativeQuery = true)
    int claimIfAvailable(@Param("id") Long id, @Param("taskId") Long taskId, @Param("now") Instant now);

    @Query(value = "SELECT * FROM agent_tasks a WHERE a.task_id = :taskId AND a.status = 'TODO' " +
            "AND (a.depends_on_id IS NULL OR a.depends_on_id IN (SELECT id FROM agent_tasks WHERE status = 'DONE')) " +
            "ORDER BY a.created_at ASC",
            nativeQuery = true)
    List<AgentTask> findClaimable(@Param("taskId") Long taskId);
}
```

- [ ] **Step 4: Add repository tests for `claimIfAvailable` and `findClaimable`**

Append to `AgentTaskRepositoryTest.java` (inside the existing class, using its existing `saveTask`/`save` helpers):

```java
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

        assertThat(claimable).extracting(AgentTask::getTitle).containsExactly("Free", "Unblocked");
    }
```

- [ ] **Step 5: Write the concurrency test**

```java
package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
        Task task = new Task();
        task.setProjectScope("agent-task-claim-race-project");
        task.setTaskKey("AT-RACE-1");
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
```

Save to `src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskClaimConcurrencyTest.java`.

- [ ] **Step 6: Run the tests**

Run: `docker compose up -d postgres && ./gradlew test --tests "ru.iuribabalin.memorymcp.repository.AgentTaskRepositoryTest" --tests "ru.iuribabalin.memorymcp.repository.AgentTaskClaimConcurrencyTest"`
Expected: PASS (7 tests in `AgentTaskRepositoryTest`, 1 in the new concurrency test).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V8__add_agent_task_dependencies.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/AgentTask.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepository.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepositoryTest.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskClaimConcurrencyTest.java
git commit -m "feat: add agent_task dependency column and atomic claim query"
```

---

## Task 2: Service layer - `claim`, `dependsOnId`, `claimable`

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/AgentTaskSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskNotClaimableException.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ProjectViewController.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/service/AgentTaskServiceTest.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/ui/ProjectViewControllerTest.java`

**Interfaces:**
- Consumes: `AgentTaskRepository.claimIfAvailable`, `.findClaimable` (Task 1).
- Produces: `AgentTaskService.create(projectScope, taskKey, title, type, description, dependsOnId) -> AgentTaskSummary`; `.list(projectScope, taskKey, typeFilter, statusFilter, claimable) -> List<AgentTaskSummary>`; `.claim(projectScope, taskKey, agentTaskId) -> AgentTaskSummary`; `AgentTaskSummary(id, title, type, status, description, updatedAt, dependsOnId)`.

- [ ] **Step 1: Add `dependsOnId` to the DTO**

In `AgentTaskSummary.java`, change:

```java
public record AgentTaskSummary(
        Long id,
        String title,
        AgentTask.Type type,
        AgentTask.Status status,
        String description,
        Instant updatedAt
) {
}
```

to:

```java
public record AgentTaskSummary(
        Long id,
        String title,
        AgentTask.Type type,
        AgentTask.Status status,
        String description,
        Instant updatedAt,
        Long dependsOnId
) {
}
```

- [ ] **Step 2: Write the new exception**

```java
package ru.iuribabalin.memorymcp.service;

public class AgentTaskNotClaimableException extends RuntimeException {

    public AgentTaskNotClaimableException(String message) {
        super(message);
    }
}
```

Save to `src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskNotClaimableException.java`.

- [ ] **Step 3: Add the new usage-event action**

In `UsageEvent.java`, change:

```java
    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE, FOLDER_CREATE,
        AGENT_TASK_CREATE, AGENT_TASK_UPDATE, AGENT_TASK_DELETE
    }
```

to:

```java
    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE, FOLDER_CREATE,
        AGENT_TASK_CREATE, AGENT_TASK_UPDATE, AGENT_TASK_DELETE, AGENT_TASK_CLAIM
    }
```

- [ ] **Step 4: Write the failing tests**

Add these test methods to `AgentTaskServiceTest.java` (keep the existing tests, but see Step 5 for a required one-line fix to each existing test that calls `.list(...)`):

```java
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
```

- [ ] **Step 5: Fix the existing tests' call sites (compile breaks from the signature changes)**

`AgentTaskService.list(...)` gains a 5th parameter and `.create(...)` gains a 6th - every existing call in this file needs updating. In `AgentTaskServiceTest.java`, append `null` to every `.create(...)` call and `false` to every `.list(...)` call:

- `createsAndListsAgentTasksInCreationOrder`: both `.create(...)` calls get `, null` appended; the `.list(...)` call gets `, false` appended.
- `filtersListByStatus`: both `.create(...)` calls get `, null`; the `.list(...)` call gets `, false`.
- `updatePartiallyChangesOnlyGivenFields`: `.create(...)` gets `, null`.
- `throwsWhenUpdatingAnAgentTaskFromADifferentTask`: `.create(...)` gets `, null`.
- `deleteRemovesTheAgentTask`: `.create(...)` gets `, null`; the `.list(...)` call gets `, false`.
- `throwsWhenCreatingUnderANonExistentTask`: `.create(...)` gets `, null`.

- [ ] **Step 6: Run the tests to verify they fail to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.AgentTaskServiceTest"`
Expected: FAIL to compile - `AgentTaskService.create`/`.list` don't have the new parameters yet, `.claim` doesn't exist, `AgentTaskNotClaimableException` doesn't exist yet as a real dependency of the test.

- [ ] **Step 7: Implement the service changes**

In `AgentTaskService.java`, change:

```java
    @Transactional
    public AgentTaskSummary create(String projectScope, String taskKey, String title, AgentTask.Type type, String description) {
        Task task = taskService.resolve(projectScope, taskKey);
        Instant now = Instant.now();
        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(task.getId());
        agentTask.setTitle(title);
        agentTask.setType(type);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setDescription(description);
        agentTask.setCreatedAt(now);
        agentTask.setUpdatedAt(now);
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskSummary> list(String projectScope, String taskKey, AgentTask.Type typeFilter, AgentTask.Status statusFilter) {
        Task task = taskService.resolve(projectScope, taskKey);
        return agentTaskRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
                .filter(agentTask -> typeFilter == null || agentTask.getType() == typeFilter)
                .filter(agentTask -> statusFilter == null || agentTask.getStatus() == statusFilter)
                .map(this::toSummary)
                .toList();
    }
```

to:

```java
    @Transactional
    public AgentTaskSummary create(String projectScope, String taskKey, String title, AgentTask.Type type, String description, Long dependsOnId) {
        Task task = taskService.resolve(projectScope, taskKey);
        if (dependsOnId != null) {
            agentTaskRepository.findByIdAndTaskId(dependsOnId, task.getId())
                    .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, dependsOnId));
        }
        Instant now = Instant.now();
        AgentTask agentTask = new AgentTask();
        agentTask.setTaskId(task.getId());
        agentTask.setTitle(title);
        agentTask.setType(type);
        agentTask.setStatus(AgentTask.Status.TODO);
        agentTask.setDescription(description);
        agentTask.setDependsOnId(dependsOnId);
        agentTask.setCreatedAt(now);
        agentTask.setUpdatedAt(now);
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskSummary> list(String projectScope, String taskKey, AgentTask.Type typeFilter, AgentTask.Status statusFilter, boolean claimable) {
        Task task = taskService.resolve(projectScope, taskKey);
        List<AgentTask> source = claimable
                ? agentTaskRepository.findClaimable(task.getId())
                : agentTaskRepository.findByTaskIdOrderByCreatedAtAsc(task.getId());
        return source.stream()
                .filter(agentTask -> typeFilter == null || agentTask.getType() == typeFilter)
                .filter(agentTask -> claimable || statusFilter == null || agentTask.getStatus() == statusFilter)
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public AgentTaskSummary claim(String projectScope, String taskKey, Long agentTaskId) {
        Task task = taskService.resolve(projectScope, taskKey);
        int updated = agentTaskRepository.claimIfAvailable(agentTaskId, task.getId(), Instant.now());
        if (updated == 0) {
            AgentTask existing = agentTaskRepository.findByIdAndTaskId(agentTaskId, task.getId())
                    .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, agentTaskId));
            if (existing.getStatus() != AgentTask.Status.TODO) {
                throw new AgentTaskNotClaimableException(
                        "Agent task %d is %s, not TODO - it's already been claimed".formatted(agentTaskId, existing.getStatus()));
            }
            throw new AgentTaskNotClaimableException(
                    "Agent task %d depends on %d, which is not DONE yet".formatted(agentTaskId, existing.getDependsOnId()));
        }
        return toSummary(agentTaskRepository.findByIdAndTaskId(agentTaskId, task.getId())
                .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, agentTaskId)));
    }
```

And update `toSummary` to pass the new field:

```java
    private AgentTaskSummary toSummary(AgentTask agentTask) {
        return new AgentTaskSummary(
                agentTask.getId(),
                agentTask.getTitle(),
                agentTask.getType(),
                agentTask.getStatus(),
                agentTask.getDescription(),
                agentTask.getUpdatedAt(),
                agentTask.getDependsOnId());
    }
```

- [ ] **Step 8: Fix `ProjectViewController`'s call site**

In `ProjectViewController.java`, change:

```java
    @GetMapping("/api/projects/{projectScope}/tasks/{taskKey}/agent-tasks")
    public List<AgentTaskSummary> agentTasks(@PathVariable String projectScope, @PathVariable String taskKey) {
        return agentTaskService.list(projectScope, taskKey, null, null);
    }
```

to:

```java
    @GetMapping("/api/projects/{projectScope}/tasks/{taskKey}/agent-tasks")
    public List<AgentTaskSummary> agentTasks(@PathVariable String projectScope, @PathVariable String taskKey) {
        return agentTaskService.list(projectScope, taskKey, null, null, false);
    }
```

- [ ] **Step 9: Fix `ProjectViewControllerTest`'s mock stubs**

In `ProjectViewControllerTest.java`:
- `new AgentTaskSummary(1L, "Analyze", AgentTask.Type.ANALYSIS, AgentTask.Status.DONE, "desc", Instant.now())` -> append `, null` (8 total args... i.e. add a 7th arg `null` for `dependsOnId`).
- `when(agentTaskService.list("memory-mcp", "AT-1", null, null))` -> `when(agentTaskService.list("memory-mcp", "AT-1", null, null, false))`.
- `when(agentTaskService.list("memory-mcp", "NO-SUCH", null, null))` -> `when(agentTaskService.list("memory-mcp", "NO-SUCH", null, null, false))`.

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.AgentTaskServiceTest" --tests "ru.iuribabalin.memorymcp.ui.ProjectViewControllerTest"`
Expected: PASS (12 tests in `AgentTaskServiceTest`, 2 in `ProjectViewControllerTest`).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/AgentTaskSummary.java \
        src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskNotClaimableException.java \
        src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskService.java \
        src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java \
        src/main/java/ru/iuribabalin/memorymcp/ui/ProjectViewController.java \
        src/test/java/ru/iuribabalin/memorymcp/service/AgentTaskServiceTest.java \
        src/test/java/ru/iuribabalin/memorymcp/ui/ProjectViewControllerTest.java
git commit -m "feat: add AgentTaskService.claim, dependsOnId, and claimable filtering"
```

---

## Task 3: MCP tools - `agent_task_claim` + param additions

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/AgentTaskMcpTools.java`

**Interfaces:**
- Consumes: `AgentTaskService.create(..., dependsOnId)`, `.list(..., claimable)`, `.claim(...)` (Task 2).
- Produces: MCP tool `agent_task_claim`, and the new optional params on `agent_task_create`/`agent_task_list`, consumed by the skill in Task 5.

- [ ] **Step 1: Update `agent_task_create` and `agent_task_list`, add `agent_task_claim`**

In `AgentTaskMcpTools.java`, change:

```java
    @McpTool(name = "agent_task_create",
            description = "Create a subtask on the agent task board for a task, breaking its work into a smaller " +
                    "tracked unit. Not idempotent - call agent_task_list first if you want to check for an existing " +
                    "duplicate before creating one. New subtasks start in TODO status.")
    public AgentTaskSummary agentTaskCreate(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key this subtask belongs to - must already exist via task_start", required = true) String taskKey,
            @McpToolParam(description = "Short subtask title", required = true) String title,
            @McpToolParam(description = "Subtask category: ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, or REPORTING", required = true) AgentTask.Type type,
            @McpToolParam(description = "Markdown notes/analysis for this subtask - what it covers, findings so far", required = false) String description) {
        AgentTaskSummary result = agentTaskService.create(projectScope, taskKey, title, type, description);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_CREATE, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "agent_task_list",
            description = "List the subtasks on a task's agent task board, optionally filtered by type or status - " +
                    "use before agent_task_create to check for duplicates, or to see what's left to do.")
    public List<AgentTaskSummary> agentTaskList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "Filter by subtask category", required = false) AgentTask.Type type,
            @McpToolParam(description = "Filter by status", required = false) AgentTask.Status status) {
        return agentTaskService.list(projectScope, taskKey, type, status);
    }
```

to:

```java
    @McpTool(name = "agent_task_create",
            description = "Create a subtask on the agent task board for a task, breaking its work into a smaller " +
                    "tracked unit. Not idempotent - call agent_task_list first if you want to check for an existing " +
                    "duplicate before creating one. New subtasks start in TODO status.")
    public AgentTaskSummary agentTaskCreate(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key this subtask belongs to - must already exist via task_start", required = true) String taskKey,
            @McpToolParam(description = "Short subtask title", required = true) String title,
            @McpToolParam(description = "Subtask category: ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, or REPORTING", required = true) AgentTask.Type type,
            @McpToolParam(description = "Markdown notes/analysis for this subtask - what it covers, findings so far", required = false) String description,
            @McpToolParam(description = "Id of another subtask in the same task that must be DONE before this one is claimable - omit if this subtask has no dependency", required = false) Long dependsOnId) {
        AgentTaskSummary result = agentTaskService.create(projectScope, taskKey, title, type, description, dependsOnId);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_CREATE, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "agent_task_list",
            description = "List the subtasks on a task's agent task board, optionally filtered by type or status - " +
                    "use before agent_task_create to check for duplicates, or to see what's left to do.")
    public List<AgentTaskSummary> agentTaskList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "Filter by subtask category", required = false) AgentTask.Type type,
            @McpToolParam(description = "Filter by status", required = false) AgentTask.Status status,
            @McpToolParam(description = "true = only return subtasks that are TODO and not blocked by an unfinished dependency - what you can actually claim right now. When true, any status filter is ignored.", required = false) Boolean claimable) {
        return agentTaskService.list(projectScope, taskKey, type, status, Boolean.TRUE.equals(claimable));
    }

    @McpTool(name = "agent_task_claim",
            description = "Atomically claim a TODO subtask before starting work on it - use this instead of " +
                    "agent_task_update when multiple independent sessions might be racing for the same subtask " +
                    "(multi-session/parallel execution). Fails cleanly if someone else already claimed it, or if " +
                    "the subtask depends on another one that isn't DONE yet - in either case that's expected, not " +
                    "an error to report to the user: just pick a different subtask from " +
                    "agent_task_list(claimable: true) and try again.")
    public AgentTaskSummary agentTaskClaim(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "The subtask's id", required = true) Long agentTaskId) {
        AgentTaskSummary result = agentTaskService.claim(projectScope, taskKey, agentTaskId);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_CLAIM, null, projectScope, taskKey, null);
        return result;
    }
```

- [ ] **Step 2: Build and manually verify**

Run: `./gradlew bootJar && java -jar build/libs/memory-mcp.jar`

With the server up, do a live JSON-RPC check over `POST /mcp` (initialize -> `notifications/initialized` -> `tools/call`, same streamable-HTTP handshake used to verify the original `agent_task_*` tools): `task_start` a scratch task, `agent_task_create` two subtasks A and B where B's `dependsOnId` is A's id, confirm `agent_task_list(claimable: true)` returns only A, `agent_task_claim(A)` succeeds and moves it to `IN_PROGRESS`, a second `agent_task_claim(A)` fails with a clear error, `agent_task_update(A, status: DONE)`, then confirm `agent_task_list(claimable: true)` now returns B, and `agent_task_claim(B)` succeeds.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/mcp/AgentTaskMcpTools.java
git commit -m "feat: add agent_task_claim tool, dependsOnId/claimable params"
```

---

## Task 4: Frontend - surface `dependsOnId` on the board

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/components/AgentTaskBoard.vue`
- Modify: `ui/src/components/AgentTaskCard.vue`

**Interfaces:**
- Consumes: `AgentTaskSummary.dependsOnId` (Task 2, now present in the REST response).
- Produces: a small "depends on: <title>" hint on `AgentTaskCard` when set - read-only, matches the board's existing read-only design (Global Constraint from the original agent-task-board spec).

- [ ] **Step 1: Add the field to the frontend type**

In `ui/src/api/types.ts`, change:

```ts
export interface AgentTaskSummary {
  id: number
  title: string
  type: AgentTaskType
  status: AgentTaskStatus
  description: string | null
  updatedAt: string
}
```

to:

```ts
export interface AgentTaskSummary {
  id: number
  title: string
  type: AgentTaskType
  status: AgentTaskStatus
  description: string | null
  updatedAt: string
  dependsOnId: number | null
}
```

- [ ] **Step 2: Resolve the dependency's title in `AgentTaskBoard.vue`**

In `AgentTaskBoard.vue`, add a helper right after the `grouped` computed:

```ts
function dependencyTitle(agentTask: AgentTaskSummary): string | null {
  if (!agentTask.dependsOnId) {
    return null
  }
  return props.agentTasks.find((candidate) => candidate.id === agentTask.dependsOnId)?.title ?? `#${agentTask.dependsOnId}`
}
```

and pass it down to each card:

```html
        <AgentTaskCard
          v-for="agentTask in grouped.get(column.status)"
          :key="agentTask.id"
          :agent-task="agentTask"
          :resolve-link="resolveLink"
          :depends-on-title="dependencyTitle(agentTask)"
        />
```

- [ ] **Step 3: Render the hint in `AgentTaskCard.vue`**

Add the prop:

```ts
defineProps<{
  agentTask: AgentTaskSummary
  resolveLink?: (name: string) => string | null
  dependsOnTitle?: string | null
}>()
```

and render it right after the title/chevron button, before the description:

```html
    <p v-if="dependsOnTitle" class="flex items-center gap-1 text-[11px] text-faint">
      <AppIcon name="link" class="size-3" />
      Depends on: {{ dependsOnTitle }}
    </p>
    <MarkdownBody
```

(i.e. insert the new `<p>` immediately before the existing `<MarkdownBody v-if="expanded && agentTask.description" ...>` line.)

- [ ] **Step 4: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add ui/src/api/types.ts ui/src/components/AgentTaskBoard.vue ui/src/components/AgentTaskCard.vue
git commit -m "feat: show subtask dependency hint on the agent task board"
```

---

## Task 5: Skill - `agent-task-board` multi-session mode

**Files:**
- Modify: `.claude/skills/agent-task-board/SKILL.md`

**Interfaces:**
- Consumes: `agent_task_claim`, `agent_task_create(..., dependsOnId)`, `agent_task_list(..., claimable)` (Task 3).

Documentation-only task, no automated test - verify via the checklist in Step 2 (same self-review approach used for the original `agent-task-board`/`agent-task-report` skill tasks).

- [ ] **Step 1: Append the multi-session section**

Append to the end of `.claude/skills/agent-task-board/SKILL.md` (after the existing "Before marking any subtask `DONE`..." paragraph that currently ends the file):

```markdown

## Multi-session mode - when several independent sessions share one board

Everything above assumes one session drives the whole board, using `agent_task_update` freely -
that's still the default and doesn't change. This section is for when you're told (or dispatched)
to play a specific role on a board that other independent sessions are also working on
concurrently - the claim primitive below exists specifically because unconditional
`agent_task_update(status: "IN_PROGRESS")` is racy when more than one session might grab the same
`TODO` subtask at once.

1. **Map your role to a subtask type**: architect -> `ANALYSIS`, implementer -> `IMPLEMENTATION`,
   tester -> `TESTING`, reviewer -> `REVIEW`, summarizer -> `REPORTING`.
2. **Find what you can actually pick up**: `agent_task_list(type: <your type>, claimable: true)` -
   this already excludes subtasks blocked by an unfinished dependency, so you don't have to
   cross-reference `dependsOnId` yourself.
3. **Claim it atomically**: `agent_task_claim(agentTaskId: <chosen>)`, not `agent_task_update`.
   If it throws - someone else claimed it first, or its dependency just changed - that's expected
   under concurrency, not an error to surface to the user. Just pick the next candidate from step
   2's list (re-list if needed) and try again.
4. **Do the work**, then `agent_task_update(status: "DONE" | "BLOCKED", description: ...)` - same
   as solo mode from here; `agent_task_update` is still how you move something you already own.
5. **Implementer, right after marking your own subtask `DONE`**: immediately
   `agent_task_create(type: "REVIEW", dependsOnId: <your own subtask's id>, title: "Review: <your title>")`
   so review work for that piece becomes claimable the moment it's ready - this is what lets a
   reviewer session pick up "the second session's small task" without a central coordinator
   noticing and creating the review subtask itself.
6. **Summarizer**: poll `agent_task_list()` (no filters) until no `IMPLEMENTATION`/`TESTING`/
   `REVIEW` subtask is left `TODO` or `IN_PROGRESS` - a `BLOCKED` one is fine, it gets surfaced in
   the report rather than waited on forever. Then create+claim the `REPORTING` subtask and invoke
   `agent-task-report` with `reportKind: "final"` as usual.
7. The Phase 1 -> Phase 2 user-confirmation checkpoint still applies globally regardless of
   session count: whichever session runs Phase 1 is the one that asks the user to confirm before
   any `IMPLEMENTATION` subtask exists to be claimed at all - that single checkpoint is enough,
   it doesn't need per-session coordination.
```

- [ ] **Step 2: Self-review checklist**

Re-read the appended section against: does every MCP tool name/param mentioned
(`agent_task_list`, `claimable`, `agent_task_claim`, `agent_task_create`, `dependsOnId`,
`agent_task_update`) match the actual tool signatures from Task 3 exactly? Is the default
(single-session, unchanged) stated clearly enough that an agent won't start using `claim`
needlessly for ordinary solo work? Does step 5 make it unambiguous *who* creates the paired
review subtask and *when*?

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/agent-task-board/SKILL.md
git commit -m "docs: add multi-session mode to agent-task-board skill"
```

---

## Task 6: Report - 3 new sections + auto-aggregated critical errors

**Files:**
- Modify: `.claude/skills/agent-task-report/assets/agent_task_report_template.html`
- Modify: `.claude/skills/agent-task-report/SKILL.md`

**Interfaces:**
- Produces: 2 new placeholders (`{{IMPACT_CONTENT}}`, `{{VERIFICATION_CONTENT}}`) and a new no-placeholder auto-built section (`critical-issues`), documented in the skill for whoever fills the template next (the skill itself, or a future task).

- [ ] **Step 1: Add the 3 new nav buttons in the approved order**

In `agent_task_report_template.html`, change:

```html
      <button class="nav-btn" data-section="review">Код-ревью</button>
      <button class="nav-btn" data-section="risks">Риски и заметки</button>
    </nav>
```

to:

```html
      <button class="nav-btn" data-section="review">Код-ревью</button>
      <button class="nav-btn" data-section="critical-issues">Критичные ошибки</button>
      <button class="nav-btn" data-section="impact">Влияние на прод/продукт</button>
      <button class="nav-btn" data-section="verification">На что обращать внимание</button>
      <button class="nav-btn" data-section="risks">Риски и заметки</button>
    </nav>
```

- [ ] **Step 2: Add the 3 new sections in the same order**

Change:

```html
      <section id="review" class="section-panel">
        <h2>Код-ревью</h2>
        {{REVIEW_CONTENT}}
      </section>
      <section id="risks" class="section-panel">
        <h2>Риски и заметки</h2>
        {{RISKS_CONTENT}}
      </section>
```

to:

```html
      <section id="review" class="section-panel">
        <h2>Код-ревью</h2>
        {{REVIEW_CONTENT}}
      </section>
      <section id="critical-issues" class="section-panel">
        <h2>Критичные ошибки</h2>
        <div id="critical-issues-list"></div>
      </section>
      <section id="impact" class="section-panel">
        <h2>Влияние на прод/продукт</h2>
        {{IMPACT_CONTENT}}
      </section>
      <section id="verification" class="section-panel">
        <h2>На что обращать внимание при проверке</h2>
        {{VERIFICATION_CONTENT}}
      </section>
      <section id="risks" class="section-panel">
        <h2>Риски и заметки</h2>
        {{RISKS_CONTENT}}
      </section>
```

- [ ] **Step 3: Add CSS for the critical-issues list**

In the `<style>` block, right after the existing `ol.diagram-notes li::marker { ... }` rule, add:

```css
  .critical-issue-link { cursor: pointer; margin: 0 0 14px; }
  .critical-issue-link:hover .callout { border-color: var(--accent); }
  .critical-issue-source {
    font-size: 0.72rem;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    color: var(--fg-muted);
    margin-bottom: 4px;
  }
```

- [ ] **Step 4: Add `buildCriticalIssues()` and fix the global stat scope**

Change:

```js
      function countBadges(scope, level) {
        return scope.querySelectorAll('.badge.' + level).length;
      }

      function buildOverview() {
        var levels = ['critical', 'high', 'medium', 'low'];
        var labels = { critical: 'Критично', high: 'Высоко', medium: 'Средне', low: 'Низко' };
        var statGrid = document.getElementById('overview-stats');
        var html = levels.map(function (lvl) {
          var n = countBadges(document, lvl);
          return '<div class="stat-tile ' + lvl + '"><div class="stat-value">' + n + '</div><div class="stat-label">' + labels[lvl] + '</div></div>';
        }).join('');
```

to:

```js
      function countBadges(scope, level) {
        return scope.querySelectorAll('.badge.' + level).length;
      }

      function buildCriticalIssues() {
        var container = document.getElementById('critical-issues-list');
        var callouts = Array.prototype.slice.call(
          document.querySelectorAll('.section-panel:not(#critical-issues) .callout.sev-critical, .section-panel:not(#critical-issues) .callout.sev-high')
        );
        if (!callouts.length) {
          container.innerHTML = '<p class="overview-link-empty">Критичных находок нет.</p>';
          return;
        }
        container.innerHTML = callouts.map(function (callout) {
          var panel = callout.closest('.section-panel');
          var sectionId = panel ? panel.id : '';
          var navBtn = document.querySelector('.nav-btn[data-section="' + sectionId + '"]');
          var sectionLabel = navBtn ? navBtn.textContent : sectionId;
          return '<div class="critical-issue-link" data-section="' + sectionId + '">' +
                   '<div class="critical-issue-source">' + sectionLabel + '</div>' +
                   callout.outerHTML +
                 '</div>';
        }).join('');
        Array.prototype.slice.call(container.querySelectorAll('.critical-issue-link')).forEach(function (link) {
          link.addEventListener('click', function () { activateSection(link.dataset.section); });
        });
      }

      function buildOverview() {
        var levels = ['critical', 'high', 'medium', 'low'];
        var labels = { critical: 'Критично', high: 'Высоко', medium: 'Средне', low: 'Низко' };
        var contentPanels = Array.prototype.slice.call(document.querySelectorAll('.section-panel:not(#critical-issues)'));
        var statGrid = document.getElementById('overview-stats');
        var html = levels.map(function (lvl) {
          var n = contentPanels.reduce(function (sum, panel) { return sum + countBadges(panel, lvl); }, 0);
          return '<div class="stat-tile ' + lvl + '"><div class="stat-value">' + n + '</div><div class="stat-label">' + labels[lvl] + '</div></div>';
        }).join('');
```

(The rest of `buildOverview()` - the `implementationSteps`/`diagramCount` tiles and the `overview-links` loop - stays unchanged; only the `countBadges(document, lvl)` line changes to the `contentPanels.reduce(...)` line above, and everything else in the function keeps using `panel`-scoped `countBadges` calls as before, which are already correctly scoped per-section.)

- [ ] **Step 5: Call `buildCriticalIssues()` before `buildOverview()`**

Change:

```js
      buildOverview();
      markScrollableDiagrams();
      activateSection('overview');
```

to:

```js
      buildCriticalIssues();
      buildOverview();
      markScrollableDiagrams();
      activateSection('overview');
```

- [ ] **Step 6: Verify the template**

Run: `grep -Eo '(src|href)="https?://[^"]*"' .claude/skills/agent-task-report/assets/agent_task_report_template.html`
Expected: no output (still self-contained, no external requests).

Run: `grep -c '<button class="nav-btn' .claude/skills/agent-task-report/assets/agent_task_report_template.html`
Expected: `10`. Then confirm by eye that each nav button's `data-section="X"` has a matching `<section id="X" class="section-panel"` - the 10 ids in order should be: `overview, architecture, interaction, implementation, tests, review, critical-issues, impact, verification, risks`.

Open the file directly in a browser (`open .claude/skills/agent-task-report/assets/agent_task_report_template.html`) with placeholders unfilled, add a manual `<div class="callout sev-critical"><span class="badge critical">crit</span> test</div>` inside the `{{REVIEW_CONTENT}}` spot temporarily via browser devtools (or just eyeball that "Критичные ошибки" shows "Критичных находок нет." with no content filled in, since there are no real `.callout` elements yet) - confirm clicking through all 10 nav items works and the Overview stat tiles show `0` for critical/high (not inflated).

- [ ] **Step 7: Update the skill's placeholder list and guidance**

In `.claude/skills/agent-task-report/SKILL.md`, change:

```markdown
1. Read `assets/agent_task_report_template.html` into your own working context - never write it
   or the filled version to disk. Replace each placeholder exactly once: `{{TASK_TITLE}}`,
   `{{GENERATED_AT}}`, `{{TASK_DESCRIPTION}}`, `{{ARCHITECTURE_CONTENT}}`,
   `{{INTERACTION_CONTENT}}`, `{{IMPLEMENTATION_CONTENT}}`, `{{TESTS_CONTENT}}`,
   `{{REVIEW_CONTENT}}`, `{{RISKS_CONTENT}}`. The "Overview" section has no placeholder - it's
   built client-side by the report's own `<script>` from the other sections.
```

to:

```markdown
1. Read `assets/agent_task_report_template.html` into your own working context - never write it
   or the filled version to disk. Replace each placeholder exactly once: `{{TASK_TITLE}}`,
   `{{GENERATED_AT}}`, `{{TASK_DESCRIPTION}}`, `{{ARCHITECTURE_CONTENT}}`,
   `{{INTERACTION_CONTENT}}`, `{{IMPLEMENTATION_CONTENT}}`, `{{TESTS_CONTENT}}`,
   `{{REVIEW_CONTENT}}`, `{{IMPACT_CONTENT}}`, `{{VERIFICATION_CONTENT}}`, `{{RISKS_CONTENT}}`.
   Two sections have no placeholder at all - don't invent one for them: "Обзор" (built
   client-side from the other sections' stats) and "Критичные ошибки" (built client-side by
   scanning every other section for `.callout.sev-critical`/`.sev-high` and listing them with a
   jump-link back to where they actually live - just tag findings with the right severity class
   wherever they belong, in Код-ревью/Риски/etc., and this section fills itself).
```

Then, right after the existing bullet list in step 4 (`Content per section is plain HTML...`), add:

```markdown
   - `{{TESTS_CONTENT}}` should explicitly state test-case coverage (what's covered, what isn't),
     not just pass/fail counts.
   - `{{IMPACT_CONTENT}}` ("Влияние на прод/продукт") - what this change touches in production:
     rollout risk, backward compatibility, data migrations, anything a deploy needs to account
     for. For `reportKind: "planning"`, describe anticipated impact; for `"final"`, the real one.
   - `{{VERIFICATION_CONTENT}}` ("На что обращать внимание при проверке") - a checklist for
     whoever verifies this task manually: what to click through, what edge cases to try, what
     would indicate something's wrong. For `reportKind: "planning"` this can be the planned test
     plan; for `"final"` it should reflect what was actually verified and what's still unverified.
```

- [ ] **Step 8: Commit**

```bash
git add .claude/skills/agent-task-report/assets/agent_task_report_template.html \
        .claude/skills/agent-task-report/SKILL.md
git commit -m "feat: add impact/verification report sections and auto critical-errors aggregation"
```

---

## Task 7: Sync skills, classpath mirrors, README

**Files:**
- Modify: `src/main/resources/skill/agent-task-board/SKILL.md` (mirror of `.claude/skills/agent-task-board/SKILL.md`)
- Modify: `src/main/resources/skill/agent-task-report/SKILL.md` (mirror)
- Modify: `src/main/resources/skill/agent-task-report/assets/agent_task_report_template.html` (mirror)
- Modify: `README.md`

**Interfaces:**
- Consumes: the finished skill/template files from Tasks 5 and 6.

Mechanical sync + documentation task, no code, no automated test.

- [ ] **Step 1: Sync the classpath mirrors**

```bash
cp .claude/skills/agent-task-board/SKILL.md src/main/resources/skill/agent-task-board/SKILL.md
cp .claude/skills/agent-task-report/SKILL.md src/main/resources/skill/agent-task-report/SKILL.md
cp .claude/skills/agent-task-report/assets/agent_task_report_template.html \
   src/main/resources/skill/agent-task-report/assets/agent_task_report_template.html
diff .claude/skills/agent-task-board/SKILL.md src/main/resources/skill/agent-task-board/SKILL.md && echo OK1
diff .claude/skills/agent-task-report/SKILL.md src/main/resources/skill/agent-task-report/SKILL.md && echo OK2
diff .claude/skills/agent-task-report/assets/agent_task_report_template.html \
     src/main/resources/skill/agent-task-report/assets/agent_task_report_template.html && echo OK3
```

Expected: `OK1`, `OK2`, `OK3` all printed (no diffs).

- [ ] **Step 2: Update the README's MCP tools table**

In `README.md`'s `### MCP-инструменты` table, add a new row right after the `agent_task_delete` row:

```markdown
| `agent_task_claim` | `projectScope`, `taskKey`, `agentTaskId` | Атомарно захватить `TODO`-подзадачу (`TODO → IN_PROGRESS`) для безопасной работы нескольких независимых сессий на одной доске; падает, если уже захвачена или зависимость (`dependsOnId`) ещё не `DONE` |
```

And update the `agent_task_create`/`agent_task_list` rows' parameter columns to include the new optional params:
- `agent_task_create`: `projectScope`, `taskKey`, `title`, `type`, `description?`, `dependsOnId?`
- `agent_task_list`: `projectScope`, `taskKey`, `type?`, `status?`, `claimable?`

- [ ] **Step 3: Add a checklist item**

In `README.md`'s `## Статус и дальнейшие шаги` section, add a new checked item at the end:

```markdown
- [x] Мультисессионное параллельное исполнение доски подзадач — атомарный захват `TODO` через
      `agent_task_claim` (conditional UPDATE, без гонок между независимыми сессиями), опциональная
      зависимость между подзадачами (`dependsOnId`, self-FK) и фильтр `agent_task_list(claimable:
      true)`, раздел про мультисессионный режим в скилле `agent-task-board`, 2 новых раздела отчёта
      (влияние на прод/продукт, на что обращать внимание при проверке) плюс авто-агрегация
      критичных находок в `agent-task-report`
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/skill/agent-task-board/SKILL.md \
        src/main/resources/skill/agent-task-report/SKILL.md \
        src/main/resources/skill/agent-task-report/assets/agent_task_report_template.html \
        README.md
git commit -m "docs: sync multi-session skill changes to classpath mirrors, update README"
```
