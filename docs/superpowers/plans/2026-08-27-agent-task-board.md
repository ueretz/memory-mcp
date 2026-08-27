# Agent Task Board Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Jira-like subtask board (`agent_tasks`) inside an existing MCP `Task`, driven entirely by the agent through new MCP tools, visible read-only on the dashboard, plus a new skill that auto-drives the board through Analysis → Implementation → Testing → Review → Reporting and produces a sidebar-navigated HTML completion report.

**Architecture:** New `agent_tasks` table (FK to `tasks`, cascade delete) with a `type` enum (5 fixed categories, variable count) and a `status` enum (4 Jira-like states), following the exact entity/repository/service/`@McpTool`/REST layering already used by `Task`/`Folder`. Dashboard gets one new read-only endpoint and a Kanban-style board component on the existing task page. A new skill (`agent-task-board`) owns the lifecycle logic and a new report template asset (adapted from `task-planner`'s proven sidebar-nav/inline-SVG template) for the final report.

**Tech Stack:** Java 25 / Spring Boot 4.1.0 / Spring Data JPA / PostgreSQL / Flyway (backend); Vue 3 + TypeScript + Tailwind 4 (frontend); no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-27-agent-task-board-design.md`

## Global Constraints

- Package root: `ru.iuribabalin.memorymcp`. Entities use flat `Long` FK fields (e.g. `taskId`), never JPA `@ManyToOne` — matches `MemoryNode.taskId`/`Folder.task`... (`Folder` is the one exception using `@ManyToOne Task task`; for `AgentTask` follow `MemoryNode`'s simpler flat-`Long` style since there's no need to navigate from `AgentTask` back to `Task` object graph anywhere).
- `AgentTask.Type` is exactly `ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, REPORTING` (not `REPORT` — that name is already `MemoryNode.type`). `AgentTask.Status` is exactly `TODO, IN_PROGRESS, DONE, BLOCKED`.
- Subtasks are ordered by `created_at` only, **not** by `type` — the skill always creates them in lifecycle order (ANALYSIS first, REPORTING last), so creation order already reflects the intended sequence. Sorting by the `type` string alphabetically would be a bug: it places `REPORTING`/`REVIEW` before `TESTING` (R < T).
- Dashboard stays **read-only** for agent tasks — no write REST endpoints, no edit UI. All mutations go through MCP tools only.
- New MCP tools are prefixed `agent_task_*` (never `task_*` — that prefix is already the MCP-level ticket/`Task`).
- Any HTML report the new skill produces (or the report template it uses) embeds diagrams as inline SVG/HTML only — never Mermaid, PlantUML, or any other JS diagramming library. Reason: the report's full HTML is a `memory_save` tool-call argument the model generates token-by-token; a multi-MB library payload is infeasible. This is already established practice in `task-planner`'s `SKILL.md` ("Common mistakes").
- The new skill's own instructions must forbid writing plans/analysis/results/reports to local files — everything goes through `agent_task_create`/`agent_task_update`/`memory_save`, mirroring the `FORBIDDEN` rule already in the main `memory-mcp` `SKILL.md`. This is a constraint on the *skill text being authored* (Task 7), not on how this plan itself is executed.
- Backend tests: `@SpringBootTest @Transactional`, AssertJ assertions, against the real Postgres started via `docker compose up -d postgres` (no mocks, no Testcontainers) — matches `FolderServiceTest`/`FolderRepositoryTest`. Controller tests: `@ExtendWith(MockitoExtension.class)` + `MockMvcBuilders.standaloneSetup(...).setControllerAdvice(new ApiExceptionHandler())` — matches `FolderViewControllerTest`.
- Frontend: there is no automated test harness in `ui/` (no vitest, no test script in `package.json`). Frontend tasks verify via `cd ui && npm run type-check` (runs `vue-tsc -b`), not unit tests. Don't introduce a new test framework just for this feature.
- Tailwind 4 color utilities (`bg-agent-todo`, `text-agent-analysis`, etc.) are generated automatically from `--color-*` custom properties declared in the `@theme inline` block of `ui/src/styles/main.css` — adding the CSS variable is sufficient, no Tailwind config changes needed (same mechanism as the existing `--color-type-*`/`--color-status-*` tokens).

---

## Task 1: `AgentTask` entity, migration, repository

**Files:**
- Create: `src/main/resources/db/migration/V7__add_agent_tasks.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/AgentTask.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepository.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepositoryTest.java`

**Interfaces:**
- Produces: `entity.AgentTask` with nested `enum Type {ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, REPORTING}` and `enum Status {TODO, IN_PROGRESS, DONE, BLOCKED}`, fields/getters/setters `id (Long, read-only), taskId (Long), title (String), type (Type), status (Status), description (String), createdAt (Instant), updatedAt (Instant)`.
- Produces: `repository.AgentTaskRepository extends JpaRepository<AgentTask, Long>` with `List<AgentTask> findByTaskIdOrderByCreatedAtAsc(Long taskId)` and `Optional<AgentTask> findByIdAndTaskId(Long id, Long taskId)`.

This is schema/infra scaffolding, not TDD-style behavior — following this project's own precedent (`Folder`/`Task` entities+migrations were not written test-first either). Steps below write the pieces then verify with one passing test, matching `FolderRepositoryTest`'s existing style.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE agent_tasks (
    id             BIGSERIAL PRIMARY KEY,
    task_id        BIGINT       NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    title          VARCHAR(500) NOT NULL,
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('ANALYSIS','IMPLEMENTATION','TESTING','REVIEW','REPORTING')),
    status         VARCHAR(20)  NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO','IN_PROGRESS','DONE','BLOCKED')),
    description    TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_tasks_task_id ON agent_tasks (task_id);
```

Save to `src/main/resources/db/migration/V7__add_agent_tasks.sql`.

- [ ] **Step 2: Write the entity**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_tasks")
public class AgentTask {

    public enum Type {
        ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, REPORTING
    }

    public enum Status {
        TODO, IN_PROGRESS, DONE, BLOCKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

- [ ] **Step 3: Write the repository**

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

- [ ] **Step 4: Write the repository test**

```java
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
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `docker compose up -d postgres && ./gradlew test --tests "ru.iuribabalin.memorymcp.repository.AgentTaskRepositoryTest"`
Expected: PASS (2 tests). Flyway applies `V7__add_agent_tasks.sql` automatically on context start.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V7__add_agent_tasks.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/AgentTask.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepository.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/AgentTaskRepositoryTest.java
git commit -m "feat: add agent_tasks schema, entity, and repository"
```

---

## Task 2: `AgentTaskService` (create/list/update/delete)

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/AgentTaskSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskNotFoundException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java` (add 3 enum constants)
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/AgentTaskServiceTest.java`

**Interfaces:**
- Consumes: `AgentTaskRepository.findByTaskIdOrderByCreatedAtAsc(Long)`, `.findByIdAndTaskId(Long, Long)` (Task 1); `TaskService.resolve(String projectScope, String taskKey) -> Task` (existing, package-private, same package `service`); `TaskNotFoundException` (existing).
- Produces: `dto.AgentTaskSummary(Long id, String title, AgentTask.Type type, AgentTask.Status status, String description, Instant updatedAt)`; `service.AgentTaskNotFoundException extends RuntimeException`; `service.AgentTaskService` with methods:
  - `create(String projectScope, String taskKey, String title, AgentTask.Type type, String description) -> AgentTaskSummary`
  - `list(String projectScope, String taskKey, AgentTask.Type typeFilter, AgentTask.Status statusFilter) -> List<AgentTaskSummary>`
  - `update(String projectScope, String taskKey, Long agentTaskId, AgentTask.Status status, String title, String description) -> AgentTaskSummary`
  - `delete(String projectScope, String taskKey, Long agentTaskId) -> void`

- [ ] **Step 1: Write the DTO**

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.AgentTask;

import java.time.Instant;

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

- [ ] **Step 2: Write the exception**

```java
package ru.iuribabalin.memorymcp.service;

public class AgentTaskNotFoundException extends RuntimeException {

    public AgentTaskNotFoundException(String projectScope, String taskKey, Long agentTaskId) {
        super("No agent task " + agentTaskId + " under task '" + taskKey + "' in project '" + projectScope + "'");
    }
}
```

- [ ] **Step 3: Add the new usage-event actions**

In `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java`, change:

```java
    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE, FOLDER_CREATE
    }
```

to:

```java
    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE, FOLDER_CREATE,
        AGENT_TASK_CREATE, AGENT_TASK_UPDATE, AGENT_TASK_DELETE
    }
```

(No migration needed — `usage_events.action` is a plain `VARCHAR(20)` with no DB check constraint, validated only at the JPA enum level. `"AGENT_TASK_CREATE"` etc. are 18 characters, within the column limit.)

- [ ] **Step 4: Write the failing test**

```java
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
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `docker compose up -d postgres && ./gradlew test --tests "ru.iuribabalin.memorymcp.service.AgentTaskServiceTest"`
Expected: FAIL to compile — `AgentTaskService` does not exist yet.

- [ ] **Step 6: Write the service**

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.repository.AgentTaskRepository;

import java.time.Instant;
import java.util.List;

@Service
public class AgentTaskService {

    private final AgentTaskRepository agentTaskRepository;
    private final TaskService taskService;

    public AgentTaskService(AgentTaskRepository agentTaskRepository, TaskService taskService) {
        this.agentTaskRepository = agentTaskRepository;
        this.taskService = taskService;
    }

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

    @Transactional
    public AgentTaskSummary update(String projectScope, String taskKey, Long agentTaskId, AgentTask.Status status, String title, String description) {
        AgentTask agentTask = resolveOwned(projectScope, taskKey, agentTaskId);
        if (status != null) {
            agentTask.setStatus(status);
        }
        if (title != null) {
            agentTask.setTitle(title);
        }
        if (description != null) {
            agentTask.setDescription(description);
        }
        agentTask.setUpdatedAt(Instant.now());
        return toSummary(agentTaskRepository.save(agentTask));
    }

    @Transactional
    public void delete(String projectScope, String taskKey, Long agentTaskId) {
        agentTaskRepository.delete(resolveOwned(projectScope, taskKey, agentTaskId));
    }

    private AgentTask resolveOwned(String projectScope, String taskKey, Long agentTaskId) {
        Task task = taskService.resolve(projectScope, taskKey);
        return agentTaskRepository.findByIdAndTaskId(agentTaskId, task.getId())
                .orElseThrow(() -> new AgentTaskNotFoundException(projectScope, taskKey, agentTaskId));
    }

    private AgentTaskSummary toSummary(AgentTask agentTask) {
        return new AgentTaskSummary(
                agentTask.getId(),
                agentTask.getTitle(),
                agentTask.getType(),
                agentTask.getStatus(),
                agentTask.getDescription(),
                agentTask.getUpdatedAt());
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.AgentTaskServiceTest"`
Expected: PASS (6 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/AgentTaskSummary.java \
        src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskNotFoundException.java \
        src/main/java/ru/iuribabalin/memorymcp/service/AgentTaskService.java \
        src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java \
        src/test/java/ru/iuribabalin/memorymcp/service/AgentTaskServiceTest.java
git commit -m "feat: add AgentTaskService with create/list/update/delete"
```

---

## Task 3: MCP tools (`agent_task_create/list/update/delete`)

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/mcp/AgentTaskMcpTools.java`

**Interfaces:**
- Consumes: `AgentTaskService.create/list/update/delete` (Task 2); `UsageEventRecorder.record(UsageEvent.Action, String entryName, String projectScope, String taskKey, String createdBy)` (existing).
- Produces: MCP tools `agent_task_create`, `agent_task_list`, `agent_task_update`, `agent_task_delete`, consumed by the new skill in Task 7.

No automated test precedent exists in this codebase for `@McpTool`-annotated classes (`TaskMcpTools`/`FolderMcpTools`/`MemoryMcpTools` have none) — verification here is manual, matching the project's own documented practice ("все 11 MCP-инструментов... проверено вручную"). Don't invent a new MCP test harness just for this task.

- [ ] **Step 1: Write the MCP tools**

```java
package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.entity.AgentTask;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.AgentTaskService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;
import java.util.Map;

@Component
public class AgentTaskMcpTools {

    private final AgentTaskService agentTaskService;
    private final UsageEventRecorder usageEventRecorder;

    public AgentTaskMcpTools(AgentTaskService agentTaskService, UsageEventRecorder usageEventRecorder) {
        this.agentTaskService = agentTaskService;
        this.usageEventRecorder = usageEventRecorder;
    }

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

    @McpTool(name = "agent_task_update",
            description = "Move a subtask's status and/or update its title/analysis notes. This is the main tool for " +
                    "driving the board: set status to IN_PROGRESS before starting work on a subtask, DONE (with a " +
                    "result summary in description) when it's finished, or BLOCKED (with the reason in description) " +
                    "if it's stuck. All fields except agentTaskId are optional - only given fields change.")
    public AgentTaskSummary agentTaskUpdate(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "The subtask's id, from agent_task_create or agent_task_list", required = true) Long agentTaskId,
            @McpToolParam(description = "New status: TODO, IN_PROGRESS, DONE, or BLOCKED", required = false) AgentTask.Status status,
            @McpToolParam(description = "New title", required = false) String title,
            @McpToolParam(description = "New/appended markdown notes", required = false) String description) {
        AgentTaskSummary result = agentTaskService.update(projectScope, taskKey, agentTaskId, status, title, description);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_UPDATE, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "agent_task_delete",
            description = "Remove a stale or duplicate subtask from the agent task board.")
    public Map<String, Object> agentTaskDelete(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey,
            @McpToolParam(description = "The subtask's id", required = true) Long agentTaskId) {
        agentTaskService.delete(projectScope, taskKey, agentTaskId);
        usageEventRecorder.record(UsageEvent.Action.AGENT_TASK_DELETE, null, projectScope, taskKey, null);
        return Map.of("deleted", true, "id", agentTaskId);
    }
}
```

- [ ] **Step 2: Build and manually verify**

Run: `./gradlew bootJar && java -jar build/libs/memory-mcp.jar`

With the server up, register it in a Claude Code session (`claude mcp add --scope user --transport http memory-mcp http://localhost:8080/mcp` if not already registered) and, in that session, call in order: `task_start` for a scratch project/task, then `agent_task_create` (type `ANALYSIS`), `agent_task_list` (confirm it comes back `TODO`), `agent_task_update` (set `IN_PROGRESS` then `DONE` with a `description`), `agent_task_delete`. Confirm each call's JSON response matches the shapes above and no exception is thrown server-side (watch the `logback-spring.xml` stdout).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/mcp/AgentTaskMcpTools.java
git commit -m "feat: add agent_task_create/list/update/delete MCP tools"
```

---

## Task 4: Read-only REST endpoint

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ProjectViewController.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/ProjectViewControllerTest.java` (new file — no controller test currently exists for `ProjectViewController`)

**Interfaces:**
- Consumes: `AgentTaskService.list(String, String, AgentTask.Type, AgentTask.Status)` (Task 2).
- Produces: `GET /api/projects/{projectScope}/tasks/{taskKey}/agent-tasks -> List<AgentTaskSummary>`, consumed by the frontend `fetchAgentTasks` in Task 5.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.ui.ProjectViewControllerTest"`
Expected: FAIL to compile — `ProjectViewController` has no `AgentTaskService` constructor param or `/agent-tasks` route yet.

- [ ] **Step 3: Add the endpoint**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ProjectViewController.java`, change:

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.ProjectSummary;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.service.ProjectService;
import ru.iuribabalin.memorymcp.service.TaskService;

import java.util.List;

@RestController
public class ProjectViewController {

    private final ProjectService projectService;
    private final TaskService taskService;

    public ProjectViewController(ProjectService projectService, TaskService taskService) {
        this.projectService = projectService;
        this.taskService = taskService;
    }

    @GetMapping("/api/projects")
    public List<ProjectSummary> list() {
        return projectService.list();
    }

    @GetMapping("/api/projects/{projectScope}/tasks")
    public List<TaskSummary> tasks(@PathVariable String projectScope) {
        return taskService.list(projectScope);
    }
}
```

to:

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.AgentTaskSummary;
import ru.iuribabalin.memorymcp.dto.ProjectSummary;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.service.AgentTaskService;
import ru.iuribabalin.memorymcp.service.ProjectService;
import ru.iuribabalin.memorymcp.service.TaskService;

import java.util.List;

@RestController
public class ProjectViewController {

    private final ProjectService projectService;
    private final TaskService taskService;
    private final AgentTaskService agentTaskService;

    public ProjectViewController(ProjectService projectService, TaskService taskService, AgentTaskService agentTaskService) {
        this.projectService = projectService;
        this.taskService = taskService;
        this.agentTaskService = agentTaskService;
    }

    @GetMapping("/api/projects")
    public List<ProjectSummary> list() {
        return projectService.list();
    }

    @GetMapping("/api/projects/{projectScope}/tasks")
    public List<TaskSummary> tasks(@PathVariable String projectScope) {
        return taskService.list(projectScope);
    }

    @GetMapping("/api/projects/{projectScope}/tasks/{taskKey}/agent-tasks")
    public List<AgentTaskSummary> agentTasks(@PathVariable String projectScope, @PathVariable String taskKey) {
        return agentTaskService.list(projectScope, taskKey, null, null);
    }
}
```

- [ ] **Step 4: Wire the 404 mapping**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`, `AgentTaskNotFoundException` doesn't need its own handler — `agentTasks()` calls `agentTaskService.list()`, which throws `TaskNotFoundException` (already handled) when the task itself doesn't exist, and `AgentTaskNotFoundException` is only ever thrown by `update`/`delete`, which have no REST endpoint (MCP-only, per the read-only constraint). No change needed to this file — confirm by reading it, don't add a dead handler for an exception no REST path can throw.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.ui.ProjectViewControllerTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/ui/ProjectViewController.java \
        src/test/java/ru/iuribabalin/memorymcp/ui/ProjectViewControllerTest.java
git commit -m "feat: add read-only GET /api/projects/{scope}/tasks/{key}/agent-tasks"
```

---

## Task 5: Frontend types, API client, color tokens, board components

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/api/client.ts`
- Modify: `ui/src/styles/main.css`
- Create: `ui/src/components/AgentTaskTypeBadge.vue`
- Create: `ui/src/components/AgentTaskCard.vue`
- Create: `ui/src/components/AgentTaskBoard.vue`

**Interfaces:**
- Consumes: `GET /api/projects/{projectScope}/tasks/{taskKey}/agent-tasks` (Task 4); existing `MarkdownBody.vue` (`content: string | null`), `EmptyState.vue` (`icon?, title, hint?`), `AppIcon.vue` (`name`).
- Produces: `AgentTaskType`, `AgentTaskStatus`, `AgentTaskSummary` types; `fetchAgentTasks(projectScope: string, taskKey: string): Promise<AgentTaskSummary[]>`; `<AgentTaskBoard :agent-tasks="AgentTaskSummary[]" />`, consumed by `TaskView.vue` in Task 6.

- [ ] **Step 1: Add types**

In `ui/src/api/types.ts`, after the `TaskSource` type declaration, add:

```ts
export type AgentTaskType = 'ANALYSIS' | 'IMPLEMENTATION' | 'TESTING' | 'REVIEW' | 'REPORTING'

export type AgentTaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

export interface AgentTaskSummary {
  id: number
  title: string
  type: AgentTaskType
  status: AgentTaskStatus
  description: string | null
  updatedAt: string
}
```

- [ ] **Step 2: Add the API client function**

In `ui/src/api/client.ts`, add `AgentTaskSummary` to the type-only import block at the top (alphabetical, matches existing order):

```ts
import type {
  AgentTaskSummary,
  FolderSummary,
  GraphResponse,
  MemoryEntryDetail,
  MemoryEntrySummary,
  MemoryType,
  ProjectSummary,
  SetupInfo,
  StatsOverview,
  TaskSummary,
} from './types'
```

Then, after `fetchTasks`, add:

```ts
export function fetchAgentTasks(projectScope: string, taskKey: string): Promise<AgentTaskSummary[]> {
  return getJson(`/api/projects/${encodeURIComponent(projectScope)}/tasks/${encodeURIComponent(taskKey)}/agent-tasks`)
}
```

- [ ] **Step 3: Add color tokens**

In `ui/src/styles/main.css`, in the light `:root` block, right after `--c-active: #1d6fd8;` / `--c-done: #6b7280;` (before `--c-shadow`), add:

```css
  --c-agent-todo: #6b7280;
  --c-agent-in-progress: #1d6fd8;
  --c-agent-blocked: #dc2626;
  --c-agent-done: #0d9668;

  --c-agent-analysis: #7c3aed;
  --c-agent-implementation: #2563eb;
  --c-agent-testing: #0d9488;
  --c-agent-review: #d97706;
  --c-agent-reporting: #e11d48;
```

In the `.dark` block, right after `--c-active: #60a5fa;` / `--c-done: #8b8b9e;` (before `--c-shadow`), add:

```css
  --c-agent-todo: #9797ac;
  --c-agent-in-progress: #60a5fa;
  --c-agent-blocked: #f87171;
  --c-agent-done: #34d399;

  --c-agent-analysis: #a78bfa;
  --c-agent-implementation: #60a5fa;
  --c-agent-testing: #2dd4bf;
  --c-agent-review: #fbbf24;
  --c-agent-reporting: #fb7185;
```

In the `@theme inline` block, right after `--color-status-done: var(--c-done);` (before `--shadow-panel`), add:

```css
  --color-agent-todo: var(--c-agent-todo);
  --color-agent-in-progress: var(--c-agent-in-progress);
  --color-agent-blocked: var(--c-agent-blocked);
  --color-agent-done: var(--c-agent-done);

  --color-agent-analysis: var(--c-agent-analysis);
  --color-agent-implementation: var(--c-agent-implementation);
  --color-agent-testing: var(--c-agent-testing);
  --color-agent-review: var(--c-agent-review);
  --color-agent-reporting: var(--c-agent-reporting);
```

- [ ] **Step 4: Write `AgentTaskTypeBadge.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'

import type { AgentTaskType } from '@/api/types'

const props = withDefaults(defineProps<{ type: AgentTaskType; variant?: 'dot' | 'pill' }>(), {
  variant: 'pill',
})

// Written out in full so Tailwind can see every class it needs to generate.
const DOT: Record<AgentTaskType, string> = {
  ANALYSIS: 'bg-agent-analysis',
  IMPLEMENTATION: 'bg-agent-implementation',
  TESTING: 'bg-agent-testing',
  REVIEW: 'bg-agent-review',
  REPORTING: 'bg-agent-reporting',
}

const PILL: Record<AgentTaskType, string> = {
  ANALYSIS: 'text-agent-analysis bg-agent-analysis/10 ring-agent-analysis/20',
  IMPLEMENTATION: 'text-agent-implementation bg-agent-implementation/10 ring-agent-implementation/20',
  TESTING: 'text-agent-testing bg-agent-testing/10 ring-agent-testing/20',
  REVIEW: 'text-agent-review bg-agent-review/10 ring-agent-review/20',
  REPORTING: 'text-agent-reporting bg-agent-reporting/10 ring-agent-reporting/20',
}

const dotClass = computed(() => DOT[props.type])
const pillClass = computed(() => PILL[props.type])
</script>

<template>
  <span
    v-if="variant === 'dot'"
    :class="dotClass"
    class="inline-block size-2 shrink-0 rounded-full"
    :title="type"
  />
  <span
    v-else
    :class="pillClass"
    class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10.5px] font-semibold tracking-wide uppercase ring-1 ring-inset"
  >
    <span :class="dotClass" class="size-1.5 rounded-full" />
    {{ type }}
  </span>
</template>
```

- [ ] **Step 5: Write `AgentTaskCard.vue`**

The toggle lives on its own `<button>` inside the card, not wrapping the whole card - if the whole card were the button, clicking a link inside the expanded markdown description would also re-collapse the card (click bubbles to the button).

```vue
<script setup lang="ts">
import { ref } from 'vue'

import type { AgentTaskSummary } from '@/api/types'
import { relativeTime } from '@/lib/format'

import AgentTaskTypeBadge from './AgentTaskTypeBadge.vue'
import AppIcon from './AppIcon.vue'
import MarkdownBody from './MarkdownBody.vue'

defineProps<{ agentTask: AgentTaskSummary }>()

const expanded = ref(false)
</script>

<template>
  <div class="flex flex-col gap-2 rounded-xl border border-border bg-panel p-3">
    <button type="button" class="flex items-center justify-between gap-2 text-left" @click="expanded = !expanded">
      <span class="flex min-w-0 items-center gap-2">
        <AgentTaskTypeBadge :type="agentTask.type" />
        <span class="truncate text-[13px] font-medium text-content">{{ agentTask.title }}</span>
      </span>
      <AppIcon name="chevron" class="size-3.5 shrink-0 text-faint transition" :class="{ 'rotate-90': expanded }" />
    </button>
    <MarkdownBody v-if="expanded && agentTask.description" :content="agentTask.description" />
    <p v-else-if="agentTask.description" class="line-clamp-2 text-[12px] text-muted">{{ agentTask.description }}</p>
    <time class="text-[11px] text-faint" :datetime="agentTask.updatedAt">{{ relativeTime(agentTask.updatedAt) }}</time>
  </div>
</template>
```

- [ ] **Step 6: Write `AgentTaskBoard.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'

import type { AgentTaskStatus, AgentTaskSummary } from '@/api/types'

import AgentTaskCard from './AgentTaskCard.vue'
import EmptyState from './EmptyState.vue'

const props = defineProps<{ agentTasks: AgentTaskSummary[] }>()

const COLUMNS: Array<{ status: AgentTaskStatus; label: string; dot: string }> = [
  { status: 'TODO', label: 'To Do', dot: 'bg-agent-todo' },
  { status: 'IN_PROGRESS', label: 'In Progress', dot: 'bg-agent-in-progress' },
  { status: 'BLOCKED', label: 'Blocked', dot: 'bg-agent-blocked' },
  { status: 'DONE', label: 'Done', dot: 'bg-agent-done' },
]

const grouped = computed(() => {
  const map = new Map<AgentTaskStatus, AgentTaskSummary[]>()
  for (const column of COLUMNS) {
    map.set(column.status, [])
  }
  for (const agentTask of props.agentTasks) {
    map.get(agentTask.status)?.push(agentTask)
  }
  return map
})
</script>

<template>
  <EmptyState
    v-if="agentTasks.length === 0"
    icon="task"
    title="No subtasks on this board yet"
    hint="The agent-task-board skill creates and drives these as it works through the task."
  />
  <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
    <div v-for="column in COLUMNS" :key="column.status" class="flex flex-col gap-3">
      <h3 class="flex items-center gap-2 text-[12px] font-semibold tracking-wide text-muted uppercase">
        <span class="size-2 rounded-full" :class="column.dot" />
        {{ column.label }}
        <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
          {{ grouped.get(column.status)?.length ?? 0 }}
        </span>
      </h3>
      <div class="flex flex-col gap-2.5">
        <AgentTaskCard v-for="agentTask in grouped.get(column.status)" :key="agentTask.id" :agent-task="agentTask" />
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 7: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors.

- [ ] **Step 8: Commit**

```bash
git add ui/src/api/types.ts ui/src/api/client.ts ui/src/styles/main.css \
        ui/src/components/AgentTaskTypeBadge.vue ui/src/components/AgentTaskCard.vue ui/src/components/AgentTaskBoard.vue
git commit -m "feat: add agent task board frontend components and API client"
```

---

## Task 6: Wire the board into `TaskView.vue`

**Files:**
- Modify: `ui/src/views/TaskView.vue`

**Interfaces:**
- Consumes: `fetchAgentTasks` (Task 5), `<AgentTaskBoard>` (Task 5), existing `useAsyncData` composable.

- [ ] **Step 1: Add the fetch + section**

In `ui/src/views/TaskView.vue`, change the import block:

```ts
import { fetchEntries, fetchFolders, fetchTasks } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryCard from '@/components/EntryCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import FolderCard from '@/components/FolderCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import StatusBadge from '@/components/StatusBadge.vue'
```

to:

```ts
import { fetchAgentTasks, fetchEntries, fetchFolders, fetchTasks } from '@/api/client'
import AgentTaskBoard from '@/components/AgentTaskBoard.vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryCard from '@/components/EntryCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import FolderCard from '@/components/FolderCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import StatusBadge from '@/components/StatusBadge.vue'
```

Then, right after the existing `const { data: folders } = useAsyncData(...)` line, add:

```ts
const { data: agentTasks } = useAsyncData(() => fetchAgentTasks(project.value, taskKey.value), [project, taskKey])
```

Then, in the `<template>`, right after the `</section>` that closes the "Folders" `<section v-if="folders?.length" ...>` block and before `<ErrorState ...`, add:

```html
    <section class="mb-9">
      <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="task" class="size-4 text-faint" />
        Agent Tasks
      </h2>
      <AgentTaskBoard :agent-tasks="agentTasks ?? []" />
    </section>
```

- [ ] **Step 2: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors.

- [ ] **Step 3: Manual verification**

Run: `docker compose up -d postgres && ./gradlew bootJar -PskipUi && cd ui && npm run dev` (Vite dev server proxies `/api` to the jar per `vite.config.ts` - start the jar in another terminal: `java -jar ../build/libs/memory-mcp.jar`). Open a task page (`/p/<project>/t/<taskKey>`) for a task with no agent tasks yet — confirm the "Agent Tasks" section shows the `EmptyState`. Manually insert a few rows via `psql` or by calling `agent_task_create` through a connected Claude Code session, reload, confirm cards appear in the right status column with the right type color.

- [ ] **Step 4: Commit**

```bash
git add ui/src/views/TaskView.vue
git commit -m "feat: show the agent task board on the task page"
```

---

## Task 7: `agent-task-board` skill

**Files:**
- Create: `.claude/skills/agent-task-board/SKILL.md`
- Modify: `src/main/resources/skill/SKILL.md` (add the 4 new tools to the "Tools" reference list)
- Modify: `.claude/skills/memory-mcp/SKILL.md` (mirror of the file above — kept in sync by `cp`, per the README's documented convention)

**Interfaces:**
- Consumes: `agent_task_create/list/update/delete` (Task 3), `memory_save` (existing), the report template from Task 8 (referenced by relative path `assets/agent_task_report_template.html`).

This is a documentation/skill-authoring task, not code — there's no automated test for skill content (same as `task-planner/SKILL.md`, which also has none). Verification is a manual read-through against the checklist in Step 3.

- [ ] **Step 1: Write `.claude/skills/agent-task-board/SKILL.md`**

```markdown
---
name: agent-task-board
description: Use automatically right when task_start is called with a substantive, multi-step description of work - decomposes it into a tracked subtask board (agent_task_* MCP tools) and drives it through Analysis, Implementation, Testing, Review, and Reporting. Skip for one-line fixes or pure questions - don't create a board for trivial work.
---

# agent-task-board: Jira-like subtask board for one MCP task

This skill turns a task's description into a tracked board of subtasks and drives it to
completion, so progress is visible on the dashboard (`/p/{project}/t/{taskKey}`) instead of
living only in this conversation.

## FORBIDDEN: no local files - everything through MCP

Plans, analysis, subtask progress, test/review results, and the final report all go through
MCP tools - `agent_task_create`, `agent_task_update`, `memory_save` - **never** through
`Write`/`Edit` to a local file: not in the project, not in a scratch/temp directory, not in
`docs/`. This is not "prefer MCP" - it is an unconditional rule, with the same single exception
as the main `memory-mcp` skill: a real source file the user explicitly asked for as part of the
application. Concretely:

- The plan from the ANALYSIS subtask -> `agent_task_update(..., description: ...)`, not a file.
- Each IMPLEMENTATION/TESTING/REVIEW subtask's result summary -> its `description` via
  `agent_task_update`, not a `*.md`/`*.txt` next to the code.
- The final report (see "Reporting" below) -> `memory_save(type: "REPORT", ...)`, not an
  `.html` file on disk.

## When this runs

Right after `task_start`, if the work the user described is more than a one-line fix or a pure
question - i.e. it breaks into multiple concrete steps. For trivial work, skip this skill
entirely: don't create a board for a single-line change.

## Lifecycle

1. **Analysis.** `agent_task_create(type: "ANALYSIS", title: "Analyze & plan")`, then
   `agent_task_update(status: "IN_PROGRESS")`. Analyze the codebase and produce a plan - for a
   large or architecturally uncertain task, invoke the `task-planner` skill to get a reviewed
   plan; for a smaller task, a lighter inline analysis is enough. Write the plan into the
   subtask's `description` via `agent_task_update`, then `status: "DONE"`.
2. **Implementation.** Create one `agent_task_create(type: "IMPLEMENTATION")` subtask per
   concrete step of the plan. Before starting each: `status: "IN_PROGRESS"`. After finishing:
   `status: "DONE"` with a summary of what changed (files touched, what was done) in
   `description`. If a step is stuck, `status: "BLOCKED"` with the reason in `description` -
   never leave a subtask silently `IN_PROGRESS` with no explanation.
3. **Testing.** One or more `agent_task_create(type: "TESTING")` subtasks for writing/running
   tests. Record results (what's covered, pass/fail) in `description`.
4. **Review.** `agent_task_create(type: "REVIEW")`. Run a code review - invoke the `code-review`
   skill if available - and record findings plus how each was resolved in `description`.
5. **Reporting.** `agent_task_create(type: "REPORTING")`. Build the final report (below), save it
   via `memory_save(type: "REPORT", ...)`, mark this subtask `DONE`, then call `task_close`.

Before marking any subtask `DONE`, always fill `description` with the actual result - an empty
`description` on a `DONE` subtask means the analytics this board exists to capture never got
written down.

## Building the final report

1. Copy `assets/agent_task_report_template.html` to a scratch location only in memory (i.e. read
   it, build the final HTML content in your own working context) - never write the filled report
   to a local file; the filled HTML goes straight into `memory_save`'s `content` argument. Replace
   each placeholder exactly once: `{{TASK_TITLE}}`, `{{GENERATED_AT}}`, `{{TASK_DESCRIPTION}}`,
   `{{ARCHITECTURE_CONTENT}}`, `{{INTERACTION_CONTENT}}`, `{{IMPLEMENTATION_CONTENT}}`,
   `{{TESTS_CONTENT}}`, `{{REVIEW_CONTENT}}`, `{{RISKS_CONTENT}}`. The "Overview" section has no
   placeholder - it's built client-side by the report's own `<script>` from the other sections.
2. Content per section is plain HTML built from the subtasks' `description` fields plus your own
   observations: headings, `<p>`, `<table>`, `<div class="callout sev-{critical|high|medium|low}">
   <span class="badge {critical|high|medium|low}">high</span> ...</div>` for flagged findings,
   `<ol class="plan-steps">` for the implementation steps.
3. **Diagrams - inline SVG/HTML only, never Mermaid/PlantUML/any JS diagram library** (the report
   is a `memory_save` tool-call argument generated token-by-token; a multi-MB library payload
   doesn't fit). Component/architecture diagrams -> `<div class="flow"><div class="flow-node">...
   </div><div class="flow-arrow">→</div>...</div>` (plain HTML, text wraps naturally, can't
   overflow). Sequence/interaction diagrams -> hand-built `<svg class="sequence" ...>` with
   lifelines and arrows: keep in-SVG `<text>` under ~24 characters (a method name, an HTTP
   verb+path, a short verdict) and put explanations in an `<ol class="diagram-notes">`
   immediately below the `</svg>`, one `<li>` per arrow. Space lifelines >= 170px apart and
   message rows >= 40px apart vertically; set `width`/`height` attributes equal to the `viewBox`
   size. These are the exact same patterns and hard rules as `task-planner`'s "Diagram patterns" -
   reuse them, don't invent new CSS classes.
4. Save: `memory_save(name: "<task-key>-completion-report", type: "REPORT", content: <filled
   HTML>, projectScope, taskKey, createdBy)`. Tell the user the dashboard link
   (`http://<host>:<port>/p/<projectScope>/t/<taskKey>/e/<name>/report`), never a file path.
```

- [ ] **Step 2: Add the 4 tools to the main skill's reference list**

In `src/main/resources/skill/SKILL.md`, find the `## Tools` section and, right after the line
documenting `location_scan`, add:

```markdown
- `agent_task_create(projectScope, taskKey, title, type, description?)` - create a subtask on a
  task's agent task board (`type`: `ANALYSIS`/`IMPLEMENTATION`/`TESTING`/`REVIEW`/`REPORTING`).
  Not idempotent - check `agent_task_list` first to avoid duplicates. See the `agent-task-board`
  skill for how these get driven end to end.
- `agent_task_list(projectScope, taskKey, type?, status?)` - list a task's subtasks, optionally
  filtered by category or status.
- `agent_task_update(projectScope, taskKey, agentTaskId, status?, title?, description?)` - move a
  subtask's status (`TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`) and/or update its notes.
- `agent_task_delete(projectScope, taskKey, agentTaskId)` - remove a stale/duplicate subtask.
```

- [ ] **Step 3: Sync the mirror and verify**

Run: `cp src/main/resources/skill/SKILL.md .claude/skills/memory-mcp/SKILL.md`

Then read `.claude/skills/agent-task-board/SKILL.md` back and check against this checklist (same
self-review criteria as `writing-plans`' own "No Placeholders" list, applied to skill content):
no `TBD`/`TODO`, the frontmatter `description` states the auto-trigger condition, the `FORBIDDEN`
block appears before the lifecycle section (not after), every placeholder named in "Building the
final report" step 1 has a matching `{{...}}` token, and every MCP tool name mentioned
(`agent_task_create`, `agent_task_update`, `task_close`, `memory_save`) matches Task 3/7's actual
tool names exactly.

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/agent-task-board/SKILL.md src/main/resources/skill/SKILL.md .claude/skills/memory-mcp/SKILL.md
git commit -m "docs: add agent-task-board skill, document agent_task_* tools"
```

---

## Task 8: Report template asset

**Files:**
- Create: `.claude/skills/agent-task-board/assets/agent_task_report_template.html`

**Interfaces:**
- Consumes: none (static asset).
- Produces: the file referenced by name in Task 7's SKILL.md step "Building the final report".

Adapted directly from `.claude/skills/task-planner/assets/report_template.html`, which already
implements exactly the layout asked for - a fixed-width left nav next to a content pane, sections
shown/hidden by click, sticky mini-TOC, dark/light theme toggle independent of the dashboard's own
theme, self-contained (no external requests). Changes from the original: 7 sections instead of 6
(added "Диаграммы взаимодействия", "Код-ревью"; dropped "Зависимости и прод",
"Производительность" - out of scope for a completion report), renamed placeholders, and the
`localStorage` theme key changed so this report's theme choice doesn't collide with
`task-planner`'s reports in the same browser profile.

- [ ] **Step 1: Write the template**

```html
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{TASK_TITLE}}</title>
<style>
  :root {
    color-scheme: light dark;
    --bg: #ffffff;
    --bg-alt: #f4f5f7;
    --fg: #1b1f24;
    --fg-muted: #5b6472;
    --border: #dfe3e8;
    --accent: #3457d5;
    --accent-fg: #ffffff;
    --code-bg: #f0f1f4;
    --sev-critical: #c0392b;
    --sev-high: #d9822b;
    --sev-medium: #b8960c;
    --sev-low: #3d8b40;
    --diagram-canvas: #f6f7fb;
    --diagram-box: #ffffff;
    --diagram-stroke: #3457d5;
    --shadow: 0 1px 2px rgba(20, 24, 32, 0.06), 0 4px 10px rgba(20, 24, 32, 0.05);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #14161a;
      --bg-alt: #1c1f25;
      --fg: #e6e9ef;
      --fg-muted: #9aa4b2;
      --border: #2c313a;
      --accent: #8ea1ff;
      --accent-fg: #0e1116;
      --code-bg: #20242c;
      --diagram-canvas: #181b22;
      --diagram-box: #20242e;
      --diagram-stroke: #8ea1ff;
      --shadow: 0 1px 2px rgba(0, 0, 0, 0.35), 0 4px 14px rgba(0, 0, 0, 0.3);
    }
  }
  :root[data-theme="dark"] {
    --bg: #14161a; --bg-alt: #1c1f25; --fg: #e6e9ef; --fg-muted: #9aa4b2;
    --border: #2c313a; --accent: #8ea1ff; --accent-fg: #0e1116; --code-bg: #20242c;
    --diagram-canvas: #181b22; --diagram-box: #20242e; --diagram-stroke: #8ea1ff;
    --shadow: 0 1px 2px rgba(0, 0, 0, 0.35), 0 4px 14px rgba(0, 0, 0, 0.3);
  }
  :root[data-theme="light"] {
    --bg: #ffffff; --bg-alt: #f4f5f7; --fg: #1b1f24; --fg-muted: #5b6472;
    --border: #dfe3e8; --accent: #3457d5; --accent-fg: #ffffff; --code-bg: #f0f1f4;
    --diagram-canvas: #f6f7fb; --diagram-box: #ffffff; --diagram-stroke: #3457d5;
    --shadow: 0 1px 2px rgba(20, 24, 32, 0.06), 0 4px 10px rgba(20, 24, 32, 0.05);
  }
  * { box-sizing: border-box; }
  html { scroll-behavior: smooth; }
  body {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: var(--bg);
    color: var(--fg);
    line-height: 1.6;
  }
  header {
    padding: 18px 28px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-alt);
  }
  .header-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
  header h1 { margin: 0 0 4px; font-size: 1.4rem; }
  header .meta { color: var(--fg-muted); font-size: 0.85rem; }
  .theme-toggle {
    flex: none;
    width: 38px;
    height: 38px;
    border-radius: 50%;
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--fg);
    font-size: 1.05rem;
    cursor: pointer;
    line-height: 1;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .theme-toggle:hover { border-color: var(--accent); }
  header details {
    margin-top: 12px;
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px 12px;
    background: var(--bg);
  }
  header summary { cursor: pointer; font-weight: 600; font-size: 0.9rem; }
  header .task-desc-body { margin-top: 8px; white-space: pre-wrap; font-size: 0.9rem; color: var(--fg-muted); }

  .layout { display: flex; min-height: calc(100vh - 90px); }
  nav.sidebar {
    flex: 0 0 220px;
    border-right: 1px solid var(--border);
    background: var(--bg-alt);
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .nav-btn {
    text-align: left;
    padding: 10px 12px;
    border-radius: 8px;
    border: none;
    background: transparent;
    color: var(--fg);
    font-size: 0.92rem;
    cursor: pointer;
  }
  .nav-btn:hover { background: var(--border); }
  .nav-btn.active { background: var(--accent); color: var(--accent-fg); font-weight: 600; }

  main { flex: 1; min-width: 0; padding: 0 28px 28px; overflow-x: auto; }
  .section-panel { display: none; max-width: 980px; }
  .section-panel.active { display: block; }
  .section-panel h2 { margin-top: 0; padding-top: 24px; font-size: 1.5rem; }
  .section-panel h3 { margin-top: 32px; font-size: 1.12rem; }
  .section-panel h2, .section-panel h3 { scroll-margin-top: 56px; }

  .mini-toc {
    position: sticky;
    top: 0;
    z-index: 5;
    background: var(--bg);
    border-bottom: 1px solid var(--border);
    padding: 10px 0;
    margin: 0 0 4px;
    display: flex;
    flex-wrap: wrap;
    gap: 4px 16px;
    font-size: 0.82rem;
  }
  .mini-toc[hidden] { display: none; }
  .mini-toc a { color: var(--fg-muted); text-decoration: none; padding: 2px 6px; border-radius: 5px; white-space: nowrap; }
  .mini-toc a:hover { color: var(--accent); background: var(--bg-alt); }

  table { border-collapse: collapse; width: 100%; margin: 12px 0; }
  th, td { border: 1px solid var(--border); padding: 8px 10px; text-align: left; font-size: 0.9rem; vertical-align: top; }
  th { background: var(--bg-alt); }

  code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  code { background: var(--code-bg); padding: 1px 5px; border-radius: 4px; font-size: 0.88em; }
  pre { background: var(--code-bg); padding: 12px; border-radius: 8px; overflow-x: auto; }

  .callout {
    border: 1px solid var(--border);
    border-left-width: 4px;
    border-radius: 6px;
    padding: 10px 14px;
    margin: 10px 0;
    background: var(--bg-alt);
  }
  .sev-critical { border-left-color: var(--sev-critical); background: var(--bg-alt); background: color-mix(in srgb, var(--sev-critical) 10%, var(--bg-alt)); }
  .sev-high { border-left-color: var(--sev-high); background: var(--bg-alt); background: color-mix(in srgb, var(--sev-high) 8%, var(--bg-alt)); }
  .sev-medium { border-left-color: var(--sev-medium); }
  .sev-low { border-left-color: var(--sev-low); }
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 0.74rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.02em;
    padding: 3px 9px;
    border-radius: 999px;
    color: #fff;
    margin-right: 6px;
  }
  .badge.critical { background: var(--sev-critical); }
  .badge.high { background: var(--sev-high); }
  .badge.medium { background: var(--sev-medium); }
  .badge.low { background: var(--sev-low); }
  .badge.critical::before { content: "\1F525"; font-size: 0.85em; }
  .badge.high::before { content: "\26A0"; font-size: 0.85em; }

  /* Overview section (auto-generated by JS from the other sections' content) */
  .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; margin: 16px 0 24px; }
  .stat-tile { border: 1px solid var(--border); border-radius: 10px; padding: 16px 10px; text-align: center; background: var(--bg-alt); }
  .stat-tile .stat-value { font-size: 1.9rem; font-weight: 700; line-height: 1.1; }
  .stat-tile .stat-label { margin-top: 4px; font-size: 0.74rem; color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.03em; }
  .stat-tile.critical .stat-value { color: var(--sev-critical); }
  .stat-tile.high .stat-value { color: var(--sev-high); }
  .stat-tile.medium .stat-value { color: var(--sev-medium); }
  .stat-tile.low .stat-value { color: var(--sev-low); }
  .overview-links { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
  .overview-link-card { border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; background: var(--bg); cursor: pointer; box-shadow: var(--shadow); }
  .overview-link-card:hover { border-color: var(--accent); }
  .overview-link-title { font-weight: 600; margin-bottom: 8px; }
  .overview-link-badges { display: flex; flex-wrap: wrap; gap: 4px; min-height: 1.6em; }
  .overview-link-empty { color: var(--fg-muted); font-size: 0.85rem; }

  /* Component/architecture diagrams: plain HTML flex nodes on a "canvas" background -
     text wraps naturally, so overflow is structurally impossible regardless of
     screen width. */
  .diagram {
    position: relative;
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 20px 18px;
    background-color: var(--diagram-canvas);
    background-image: radial-gradient(var(--border) 1px, transparent 1px);
    background-size: 18px 18px;
    overflow-x: auto;
    margin: 14px 0;
  }
  .diagram.is-scrollable::after {
    content: "";
    position: absolute;
    top: 1px; right: 1px; bottom: 1px;
    width: 28px;
    pointer-events: none;
    background: linear-gradient(to right, transparent, var(--diagram-canvas));
  }
  .flow { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; margin: 10px 0; }
  .flow-node {
    border: 1.5px solid var(--diagram-stroke);
    background: var(--diagram-box);
    box-shadow: var(--shadow);
    border-radius: 10px;
    padding: 12px 16px;
    font-size: 0.9rem;
    min-width: 190px;
    max-width: 240px;
    flex: 0 0 auto;
    text-align: center;
    overflow-wrap: break-word;
  }
  .flow-node strong { display: block; margin-bottom: 5px; font-size: 0.98rem; }
  .flow-node .flow-node-detail { display: block; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.76rem; color: var(--fg-muted); margin-top: 3px; text-align: left; }
  .flow-arrow { display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--diagram-stroke); font-size: 1.5rem; font-weight: 700; flex: none; line-height: 1; min-width: 32px; }
  .flow-arrow-label { font-size: 0.68rem; font-weight: 400; color: var(--fg-muted); white-space: nowrap; margin-top: 3px; }
  .flow-branch { display: flex; flex-direction: column; gap: 10px; flex: none; border-left: 2px dashed var(--diagram-stroke); padding-left: 16px; margin-left: 2px; }
  .flow-branch .flow-node { min-width: 190px; }

  /* Sequence/interaction diagrams only: SVG with fixed intrinsic size (set via
     width/height attributes matching viewBox) - never force-shrunk, .diagram
     scrolls horizontally instead so text stays legible on narrow screens. */
  .diagram svg.sequence { display: block; margin: 0 auto; position: relative; }
  .diagram-arrow { stroke: var(--diagram-stroke); stroke-width: 2; fill: none; }
  .diagram-label text { fill: var(--fg-muted); font-family: -apple-system, sans-serif; font-size: 13px; }
  .lifeline { stroke: var(--border); stroke-width: 1.5; stroke-dasharray: 4 3; }
  .lifeline-actor text { fill: var(--fg); font-family: -apple-system, sans-serif; font-size: 13px; font-weight: 700; }
  ol.diagram-notes { margin: 10px 0 0; padding-left: 20px; font-size: 0.85rem; color: var(--fg-muted); }
  ol.diagram-notes li { margin-bottom: 4px; }
  ol.diagram-notes li::marker { color: var(--diagram-stroke); font-weight: 700; }

  @media (max-width: 560px) {
    .flow { flex-direction: column; align-items: stretch; }
    .flow-node, .flow-branch .flow-node { min-width: 0; max-width: none; }
    .flow-arrow { flex-direction: row; }
    .flow-branch { border-left: none; border-top: 2px dashed var(--diagram-stroke); padding-left: 0; padding-top: 12px; margin-left: 0; }
  }

  ol.plan-steps { padding-left: 22px; }
  ol.plan-steps li { margin-bottom: 12px; }

  @media (max-width: 760px) {
    .layout { flex-direction: column; }
    nav.sidebar { flex-direction: row; overflow-x: auto; flex: none; }
    main { padding: 0 18px 18px; }
  }
</style>
</head>
<body>
  <header>
    <div class="header-top">
      <div>
        <h1>{{TASK_TITLE}}</h1>
        <div class="meta">Отчёт сгенерирован: {{GENERATED_AT}}</div>
      </div>
      <button id="theme-toggle" class="theme-toggle" type="button" aria-label="Переключить тему">🌙</button>
    </div>
    <details>
      <summary>Исходное описание задачи</summary>
      <div class="task-desc-body">{{TASK_DESCRIPTION}}</div>
    </details>
  </header>

  <div class="layout">
    <nav class="sidebar">
      <button class="nav-btn active" data-section="overview">Обзор</button>
      <button class="nav-btn" data-section="architecture">Архитектура</button>
      <button class="nav-btn" data-section="interaction">Диаграммы взаимодействия</button>
      <button class="nav-btn" data-section="implementation">Реализация</button>
      <button class="nav-btn" data-section="tests">Тесты</button>
      <button class="nav-btn" data-section="review">Код-ревью</button>
      <button class="nav-btn" data-section="risks">Риски и заметки</button>
    </nav>
    <main>
      <div class="mini-toc" id="mini-toc" hidden></div>

      <section id="overview" class="section-panel active">
        <h2>Обзор</h2>
        <div class="stat-grid" id="overview-stats"></div>
        <h3>Разделы отчёта</h3>
        <div class="overview-links" id="overview-links"></div>
      </section>

      <section id="architecture" class="section-panel">
        <h2>Архитектура</h2>
        {{ARCHITECTURE_CONTENT}}
      </section>
      <section id="interaction" class="section-panel">
        <h2>Диаграммы взаимодействия</h2>
        {{INTERACTION_CONTENT}}
      </section>
      <section id="implementation" class="section-panel">
        <h2>Реализация</h2>
        {{IMPLEMENTATION_CONTENT}}
      </section>
      <section id="tests" class="section-panel">
        <h2>Тесты</h2>
        {{TESTS_CONTENT}}
      </section>
      <section id="review" class="section-panel">
        <h2>Код-ревью</h2>
        {{REVIEW_CONTENT}}
      </section>
      <section id="risks" class="section-panel">
        <h2>Риски и заметки</h2>
        {{RISKS_CONTENT}}
      </section>
    </main>
  </div>

  <script>
    (function () {
      var root = document.documentElement;
      var STORAGE_KEY = 'agent-task-board-report-theme';
      var toggleBtn = document.getElementById('theme-toggle');

      function systemPrefersDark() {
        return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
      }
      function effectiveTheme() {
        var override = root.getAttribute('data-theme');
        if (override === 'light' || override === 'dark') return override;
        return systemPrefersDark() ? 'dark' : 'light';
      }
      function syncToggleIcon() {
        var dark = effectiveTheme() === 'dark';
        toggleBtn.textContent = dark ? '☀️' : '🌙';
        toggleBtn.setAttribute('aria-label', dark ? 'Переключить на светлую тему' : 'Переключить на тёмную тему');
      }
      var saved = null;
      try { saved = localStorage.getItem(STORAGE_KEY); } catch (e) { /* sandboxed iframe: storage may be blocked */ }
      if (saved === 'light' || saved === 'dark') root.setAttribute('data-theme', saved);
      syncToggleIcon();
      toggleBtn.addEventListener('click', function () {
        var next = effectiveTheme() === 'dark' ? 'light' : 'dark';
        root.setAttribute('data-theme', next);
        try { localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* sandboxed iframe: storage may be blocked */ }
        syncToggleIcon();
      });
    })();

    (function () {
      var navButtons = Array.prototype.slice.call(document.querySelectorAll('.nav-btn'));
      var panels = Array.prototype.slice.call(document.querySelectorAll('.section-panel'));
      var tocEl = document.getElementById('mini-toc');
      var slugCounts = {};

      function slugify(text) {
        var base = text.toLowerCase().trim()
          .replace(/[^a-z0-9а-яё\s-]/gi, '')
          .replace(/\s+/g, '-') || 'section';
        var n = slugCounts[base] || 0;
        slugCounts[base] = n + 1;
        return n ? base + '-' + n : base;
      }

      function buildMiniToc(sectionId) {
        var panel = document.getElementById(sectionId);
        var headings = Array.prototype.slice.call(panel.querySelectorAll('h2, h3'));
        headings.shift(); // first h2 is the section title itself
        if (headings.length < 2) { tocEl.hidden = true; tocEl.innerHTML = ''; return; }
        tocEl.innerHTML = headings.map(function (h) {
          if (!h.id) h.id = slugify(h.textContent);
          var sub = h.tagName === 'H3' ? ' style="padding-left:16px;opacity:.85"' : '';
          return '<a href="#' + h.id + '"' + sub + '>' + h.textContent + '</a>';
        }).join('');
        tocEl.hidden = false;
      }

      function activateSection(sectionId) {
        navButtons.forEach(function (b) { b.classList.toggle('active', b.dataset.section === sectionId); });
        panels.forEach(function (p) { p.classList.toggle('active', p.id === sectionId); });
        buildMiniToc(sectionId);
        main.scrollTop = 0;
        window.scrollTo(0, 0);
      }
      var main = document.querySelector('main');

      navButtons.forEach(function (btn) {
        btn.addEventListener('click', function () { activateSection(btn.dataset.section); });
      });

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
        var implementationSteps = document.querySelectorAll('#implementation .plan-steps > li').length;
        var diagramCount = document.querySelectorAll('.diagram').length;
        html += '<div class="stat-tile"><div class="stat-value">' + implementationSteps + '</div><div class="stat-label">Шагов реализации</div></div>';
        html += '<div class="stat-tile"><div class="stat-value">' + diagramCount + '</div><div class="stat-label">Диаграмм</div></div>';
        statGrid.innerHTML = html;

        var linksEl = document.getElementById('overview-links');
        linksEl.innerHTML = navButtons.filter(function (b) { return b.dataset.section !== 'overview'; }).map(function (b) {
          var sectionId = b.dataset.section;
          var panel = document.getElementById(sectionId);
          var bits = levels.map(function (lvl) {
            var n = countBadges(panel, lvl);
            return n ? '<span class="badge ' + lvl + '">' + n + '</span>' : '';
          }).join('');
          return '<div class="overview-link-card" data-section="' + sectionId + '"><div class="overview-link-title">' + b.textContent + '</div><div class="overview-link-badges">' + (bits || '<span class="overview-link-empty">без замечаний</span>') + '</div></div>';
        }).join('');
        Array.prototype.slice.call(linksEl.querySelectorAll('.overview-link-card')).forEach(function (card) {
          card.addEventListener('click', function () { activateSection(card.dataset.section); });
        });
      }

      function markScrollableDiagrams() {
        Array.prototype.slice.call(document.querySelectorAll('.diagram')).forEach(function (d) {
          function check() { d.classList.toggle('is-scrollable', d.scrollWidth > d.clientWidth + 2); }
          check();
          window.addEventListener('resize', check);
        });
      }

      buildOverview();
      markScrollableDiagrams();
      activateSection('overview');
    })();
  </script>
</body>
</html>
```

Save to `.claude/skills/agent-task-board/assets/agent_task_report_template.html`.

- [ ] **Step 2: Verify it's a valid self-contained document**

Run: `grep -Eo '(src|href)="https?://[^"]*"' .claude/skills/agent-task-board/assets/agent_task_report_template.html`
Expected: no output (no external requests — everything inline, matching the `REPORT` type's
self-containment requirement).

Then open it directly in a browser (`open .claude/skills/agent-task-board/assets/agent_task_report_template.html`
on macOS) with the `{{...}}` placeholders left unfilled, and click through all 7 nav items —
confirm each section shows/hides correctly and the theme toggle works.

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/agent-task-board/assets/agent_task_report_template.html
git commit -m "feat: add sidebar-nav report template for agent-task-board"
```

---

## Task 9: README updates

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the new tools to the MCP tools table**

In the `### MCP-инструменты` table, right after the row for `location_scan`, add:

```markdown
| `agent_task_create` | `projectScope`, `taskKey`, `title`, `type`, `description?` | Создать подзадачу на доске задачи (`type`: `ANALYSIS`/`IMPLEMENTATION`/`TESTING`/`REVIEW`/`REPORTING`), статус `TODO` |
| `agent_task_list` | `projectScope`, `taskKey`, `type?`, `status?` | Список подзадач задачи с опциональными фильтрами |
| `agent_task_update` | `projectScope`, `taskKey`, `agentTaskId`, `status?`, `title?`, `description?` | Сдвинуть статус подзадачи и/или дополнить аналитику |
| `agent_task_delete` | `projectScope`, `taskKey`, `agentTaskId` | Удалить ошибочную/дублирующую подзадачу |
```

- [ ] **Step 2: Add a checklist item**

In the `## Статус и дальнейшие шаги` section, add a new checked item at the end of the list:

```markdown
- [x] Доска подзадач агента (`agent_tasks`) внутри MCP-задачи — Jira-подобный борд с 5 типами
      подзадач (`ANALYSIS`/`IMPLEMENTATION`/`TESTING`/`REVIEW`/`REPORTING`) и 4 статусами
      (`TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`), read-only Kanban на `/p/{project}/t/{task}`,
      MCP-инструменты `agent_task_*`, и скилл `agent-task-board`, который автоматически ведёт
      задачу через весь цикл и в конце собирает HTML-отчёт (`type: REPORT`) с боковым меню и
      inline-SVG диаграммами
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document the agent task board feature"
```
