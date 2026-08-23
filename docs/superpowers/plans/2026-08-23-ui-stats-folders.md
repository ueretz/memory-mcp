# UI Redesign + Usage Statistics + Folders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a server-tracked usage-statistics subsystem (events + aggregation + API + UI), apply the approved "constellation-motif, dark-first, denser" visual redesign across the dashboard, and add an arbitrarily-nestable folder system so common/task space can be organized, with files and folders visually distinct in the UI.

**Architecture:** Backend: a new `usage_events` table fed by a fire-and-forget recorder wired into the existing MCP tool methods, aggregated by a new `StatsService` and exposed at `GET /api/stats/overview`; a new `folders` table (self-referencing for nesting, optionally task-scoped) created only via a new `folder_create` MCP tool (mirroring `task_start`), with `MemoryNode` gaining an optional `folder` association that a file-explorer-style listing (root vs. inside-a-folder) respects everywhere entries are browsed. Frontend: a new signature `ConstellationField` component reused in a couple of high-value spots, a density/color pass on existing row/card components, a new `StatsView.vue` plus a pulse counter in the header, and folder browsing (`FolderView.vue`, `FolderRow.vue`) with a distinct folder icon versus the existing document icon for entries — all built from the existing Tailwind v4 token system, no new dependencies.

**Tech Stack:** Spring Boot 4.1 / Java 25 / PostgreSQL + Flyway + Spring Data JPA (backend); Vue 3 + TypeScript + Tailwind v4, no new npm dependencies (frontend).

**Spec:** memory entry `2026-08-23-ui-redesign-and-usage-stats-design` (project `memory-mcp`, dashboard: `http://localhost:8080/p/memory-mcp/e/2026-08-23-ui-redesign-and-usage-stats-design`) plus this plan's folder addendum (see "Folders" section below — the folder feature was scoped after the original spec was written, directly in this plan).

## Global Constraints

- Java 25 / Spring Boot 4.1, base package `ru.iuribabalin.memorymcp`.
- Flyway migrations live in `src/main/resources/db/migration/`; the next free version is **V5** (an uncommitted, unrelated `V4__add_report_type_and_created_by.sql` already claims V4 — do not touch it), and this plan uses **V5** for `usage_events` (Task 1) and **V6** for `folders` (Task 11) — run them in that order.
- Folders are an organizational, file-explorer-style concept: browsing a project/task's root (no `folder` param) shows only entries with no folder, exactly like files at the root of a directory tree. An entry's folder must belong to the exact same project/task scope as the entry itself (enforced in `MemoryService.save`); a folder's parent must belong to the exact same project/task scope as the folder itself (enforced in `FolderService.create`). Folders are created only via the `folder_create` MCP tool — the dashboard UI is read-only for folders, exactly like it already is for tasks.
- Tests need a real local Postgres: `docker-compose up -d postgres` (exposes `localhost:5433`, matching `application.yml`'s default datasource URL). This repo has no H2/Testcontainers — `@SpringBootTest` runs against that real database and Flyway applies all migrations including the new V5/V6.
- No frontend automated test framework exists in `ui/` — frontend tasks verify via `cd ui && npm run type-check` (must pass) plus a manual check in the running dev server. Do not introduce a new test framework as part of this plan.
- Any new top-level SPA route added to `ui/src/router/index.ts` MUST also be added to `SpaForwardController`'s `@GetMapping` prefix list (`src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java`) or a hard refresh on that route 404s — see the comment already in `router/index.ts`. (The new `/p/:project/f/:folder` route in Task 16 is already covered by the existing `/p/**` prefix — no controller change needed for it.)
- Tailwind class names must appear as complete literal strings somewhere in the source for the JIT scanner to generate them — never build a utility class via string interpolation (e.g. `` `bg-type-${x}` ``). Use a static `Record<MemoryType, string>` map, exactly like the existing `DOT`/`PILL` maps in `ui/src/components/TypeBadge.vue`.
- Any new chart follows the dataviz skill. The app's existing 6 type-color tokens (`--c-user` etc.) **fail** `validate_palette.js`'s categorical-CVD-separation check (worst adjacent pair ΔE 5.0, below the legal floor) — so the one chart that uses them (type breakdown) MUST show the type name as a direct text label on every row; never rely on hue alone to distinguish types (no color-only donut/pie).
- `UsageEventRecorder.record(...)` must never throw into its caller — wrap in try/catch, log at WARN, swallow. A broken stats write must never break `memory_save`/`memory_get`/etc.
- This worktree's branch point (committed `main`) does NOT include a separate, still-uncommitted feature that exists only in the parent checkout's working directory (a `createdBy` field threaded through `MemoryNode`/`MemoryService`/`MemoryEntrySummary`/`MemoryEntryDetail`, a `REPORT` entry type, PDF export). Task 2 discovered this: `MemoryMcpTools`'s `memory_save` already took a `createdBy` MCP parameter, so `SaveMemoryRequest` needed a `createdBy` field to carry it through to `UsageEventRecorder` (which has its own independent `created_by` column) — that field was added and is the one place `createdBy` legitimately exists in this branch. Do not add `createdBy` anywhere else (`MemoryNode`, `MemoryEntrySummary`, `MemoryEntryDetail`, `MemoryService`) and do not reference `MemoryNode.Type.REPORT` — neither exists on this branch, and wiring them in would pull in scope that belongs to that separate feature.

---

### Task 1: Usage-event schema (migration, entity, repository)

**Files:**
- Create: `src/main/resources/db/migration/V5__add_usage_events.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/UsageEventRepository.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/repository/UsageEventRepositoryTest.java`

**Interfaces:**
- Produces: `UsageEvent` entity with `enum Action { SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE }` and fields `id, action, entryName, projectScope, taskKey, createdBy, occurredAt` with standard getters/setters. `UsageEventRepository extends JpaRepository<UsageEvent, Long>` with `List<DailyCountRow> countByDay(Instant since, String projectScope, String taskKey)`, where `DailyCountRow` is a nested projection interface with `getDay(): Instant` and `getCnt(): long`.

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.UsageEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UsageEventRepositoryTest {

    @Autowired
    private UsageEventRepository repository;

    @Test
    void countsEventsGroupedByDayWithinScope() {
        Instant now = Instant.now();
        saveEvent(UsageEvent.Action.SAVE, "proj-a", now);
        saveEvent(UsageEvent.Action.GET, "proj-a", now);
        saveEvent(UsageEvent.Action.SAVE, "proj-b", now);
        saveEvent(UsageEvent.Action.SAVE, "proj-a", now.minus(40, ChronoUnit.DAYS));

        List<UsageEventRepository.DailyCountRow> rows =
                repository.countByDay(now.minus(7, ChronoUnit.DAYS), "proj-a", null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCnt()).isEqualTo(2);
    }

    private void saveEvent(UsageEvent.Action action, String projectScope, Instant occurredAt) {
        UsageEvent event = new UsageEvent();
        event.setAction(action);
        event.setProjectScope(projectScope);
        event.setOccurredAt(occurredAt);
        repository.saveAndFlush(event);
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.repository.UsageEventRepositoryTest"`
Expected: FAIL — `UsageEvent`/`UsageEventRepository` don't exist yet.

- [ ] **Step 3: Create the migration**

```sql
CREATE TABLE usage_events (
    id             BIGSERIAL PRIMARY KEY,
    action         VARCHAR(20)  NOT NULL,
    entry_name     VARCHAR(500),
    project_scope  VARCHAR(200),
    task_key       VARCHAR(100),
    created_by     VARCHAR(300),
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_events_project_scope_occurred_at ON usage_events (project_scope, occurred_at);
CREATE INDEX idx_usage_events_action_occurred_at ON usage_events (action, occurred_at);
CREATE INDEX idx_usage_events_entry_name ON usage_events (entry_name);
```

- [ ] **Step 4: Create the entity**

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
@Table(name = "usage_events")
public class UsageEvent {

    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Column(name = "entry_name", length = 500)
    private String entryName;

    @Column(name = "project_scope", length = 200)
    private String projectScope;

    @Column(name = "task_key", length = 100)
    private String taskKey;

    @Column(name = "created_by", length = 300)
    private String createdBy;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public Long getId() {
        return id;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public String getEntryName() {
        return entryName;
    }

    public void setEntryName(String entryName) {
        this.entryName = entryName;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public void setProjectScope(String projectScope) {
        this.projectScope = projectScope;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public void setTaskKey(String taskKey) {
        this.taskKey = taskKey;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
```

- [ ] **Step 5: Create the repository**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.UsageEvent;

import java.time.Instant;
import java.util.List;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    interface DailyCountRow {
        Instant getDay();
        long getCnt();
    }

    /**
     * projectScope/taskKey null means "no filter on that dimension" - a project with no
     * taskKey returns activity for the whole project (common entries and every task), not
     * just its task-less "common" slice, unlike MemoryService's stricter COMMON/TASK modes.
     */
    @Query(value = """
            select date_trunc('day', occurred_at) as day, count(*) as cnt
            from usage_events
            where occurred_at >= :since
            and (:projectScope is null or project_scope = :projectScope)
            and (:taskKey is null or task_key = :taskKey)
            group by day
            order by day
            """, nativeQuery = true)
    List<DailyCountRow> countByDay(@Param("since") Instant since,
                                    @Param("projectScope") String projectScope,
                                    @Param("taskKey") String taskKey);
}
```

- [ ] **Step 6: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.repository.UsageEventRepositoryTest"`
Expected: PASS (requires `docker-compose up -d postgres` running first)

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V5__add_usage_events.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/UsageEventRepository.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/UsageEventRepositoryTest.java
git commit -m "feat: add usage_events table and repository"
```

---

### Task 2: UsageEventRecorder + wire into MCP tools

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/UsageEventRecorder.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/MemoryMcpTools.java` (whole file, ~105 lines)
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/TaskMcpTools.java` (whole file, ~53 lines)

**Interfaces:**
- Consumes: `UsageEventRepository` (Task 1), `UsageEvent.Action` (Task 1).
- Produces: `UsageEventRecorder.record(UsageEvent.Action action, String entryName, String projectScope, String taskKey, String createdBy)` — swallows all exceptions, safe to call unconditionally after any service call.

- [ ] **Step 1: Create the recorder**

```java
package ru.iuribabalin.memorymcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.time.Instant;

@Service
public class UsageEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageEventRecorder.class);

    private final UsageEventRepository repository;

    public UsageEventRecorder(UsageEventRepository repository) {
        this.repository = repository;
    }

    /** Never throws - a broken stats write must never break the memory/task operation it followed. */
    public void record(UsageEvent.Action action, String entryName, String projectScope, String taskKey, String createdBy) {
        try {
            UsageEvent event = new UsageEvent();
            event.setAction(action);
            event.setEntryName(entryName);
            event.setProjectScope(projectScope);
            event.setTaskKey(taskKey);
            event.setCreatedBy(createdBy);
            event.setOccurredAt(Instant.now());
            repository.save(event);
        } catch (RuntimeException ex) {
            log.warn("Failed to record usage event {} for entry {}", action, entryName, ex);
        }
    }
}
```

- [ ] **Step 2: Wire it into `MemoryMcpTools`**

Replace the whole file with:

```java
package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.GraphResponse;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.dto.MemoryEntrySummary;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.MemoryService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;
import java.util.Map;

@Component
public class MemoryMcpTools {

    private final MemoryService memoryService;
    private final UsageEventRecorder usageEventRecorder;

    public MemoryMcpTools(MemoryService memoryService, UsageEventRecorder usageEventRecorder) {
        this.memoryService = memoryService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "memory_save",
            description = "Create or update a long-term memory entry. Upserts by name. Parses [[other-name]] " +
                    "references inside content into graph links. Call this whenever you learn a durable user " +
                    "preference, receive corrective feedback, discover a project-specific fact, or produce " +
                    "reusable reference knowledge. If you're mid-task, pass projectScope/taskKey - omitting them " +
                    "while linking to a task-scoped entry makes this one unreachable from that task's page and " +
                    "graph, and the response's warnings field will flag it.")
    public MemoryEntryDetail memorySave(
            @McpToolParam(description = "Unique kebab-case slug identifying this entry (use the fully-qualified class name for type=LOCATION)", required = true) String name,
            @McpToolParam(description = "One of USER, FEEDBACK, PROJECT, REFERENCE, LOCATION, REPORT", required = true) MemoryNode.Type type,
            @McpToolParam(description = "One-line summary shown in cheap listings", required = true) String description,
            @McpToolParam(description = "Full markdown content; may reference other entries via [[name]]. For type=REPORT, a full " +
                    "self-contained HTML document instead (inline CSS/JS, no external CDN/resources) - it's rendered " +
                    "as a real HTML page in the dashboard, not markdown-parsed", required = true) String content,
            @McpToolParam(description = "Project this entry is scoped to, auto-derived from the git repo name", required = false) String projectScope,
            @McpToolParam(description = "Task key to scope this entry to a specific task (must already exist via task_start); omit for project-level common context", required = false) String taskKey,
            @McpToolParam(description = "Relative file path this entry points at, for type=LOCATION (e.g. a class or file you just worked on)", required = false) String filePath,
            @McpToolParam(description = "Who created this entry, e.g. 'Name <email>' - auto-derive from `git config user.name`/`user.email` in the current repo, never ask the user for it", required = false) String createdBy) {
        MemoryEntryDetail result = memoryService.save(new SaveMemoryRequest(name, type, description, content, projectScope, taskKey, filePath, createdBy));
        usageEventRecorder.record(UsageEvent.Action.SAVE, result.name(), result.projectScope(), result.taskKey(), createdBy);
        return result;
    }

    @McpTool(name = "memory_get",
            description = "Fetch the full content of one memory entry by name, including entries it links to and " +
                    "entries that link to it.")
    public MemoryEntryDetail memoryGet(
            @McpToolParam(description = "The entry's name/slug", required = true) String name) {
        MemoryEntryDetail result = memoryService.get(name);
        usageEventRecorder.record(UsageEvent.Action.GET, name, result.projectScope(), result.taskKey(), null);
        return result;
    }

    @McpTool(name = "memory_list",
            description = "Cheap index of memory entries (name, type, description, updatedAt only - no content). " +
                    "Call this first when you need an overview, instead of memory_get on everything.")
    public List<MemoryEntrySummary> memoryList(
            @McpToolParam(description = "Optional filter: USER, FEEDBACK, PROJECT, or REFERENCE", required = false) MemoryNode.Type type,
            @McpToolParam(description = "Project scope filter. Alone (no taskKey), returns only project-level common entries", required = false) String projectScope,
            @McpToolParam(description = "Task key filter - lists that task's entries instead of the project's common ones", required = false) String taskKey,
            @McpToolParam(description = "Max results, default 50", required = false) Integer limit,
            @McpToolParam(description = "Offset for pagination, default 0", required = false) Integer offset) {
        List<MemoryEntrySummary> result = memoryService.list(type, projectScope, taskKey, limit == null ? 50 : limit, offset == null ? 0 : offset);
        usageEventRecorder.record(UsageEvent.Action.LIST, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "memory_search",
            description = "Full-text search over entry names, descriptions, and content. Returns the same cheap " +
                    "summary shape as memory_list, ranked by relevance.")
    public List<MemoryEntrySummary> memorySearch(
            @McpToolParam(description = "Search query", required = true) String query,
            @McpToolParam(description = "Optional filter: USER, FEEDBACK, PROJECT, or REFERENCE", required = false) MemoryNode.Type type,
            @McpToolParam(description = "Optional project scope filter", required = false) String projectScope,
            @McpToolParam(description = "Optional task key filter (requires projectScope)", required = false) String taskKey,
            @McpToolParam(description = "Max results, default 20", required = false) Integer limit) {
        List<MemoryEntrySummary> result = memoryService.search(query, type, projectScope, taskKey, limit == null ? 20 : limit);
        usageEventRecorder.record(UsageEvent.Action.SEARCH, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "memory_graph",
            description = "Return the full memory graph as nodes and edges (derived from [[links]] in content), " +
                    "for visualization or for understanding how entries relate.")
    public GraphResponse memoryGraph(
            @McpToolParam(description = "Optional filter: USER, FEEDBACK, PROJECT, or REFERENCE", required = false) MemoryNode.Type type,
            @McpToolParam(description = "Project scope filter. Alone (no taskKey), returns only project-level common entries", required = false) String projectScope,
            @McpToolParam(description = "Task key filter - graph of that task's entries instead of the project's common ones", required = false) String taskKey) {
        GraphResponse result = memoryService.graph(type, projectScope, taskKey);
        usageEventRecorder.record(UsageEvent.Action.GRAPH, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "memory_related",
            description = "Return entries directly linked to/from the given entry - cheaper than memory_get when " +
                    "you just need to know what's connected before re-deriving something already documented.")
    public List<MemoryEntrySummary> memoryRelated(
            @McpToolParam(description = "The entry's name/slug", required = true) String name,
            @McpToolParam(description = "Traversal depth, default 1 (only 1-hop is currently supported)", required = false) Integer depth) {
        List<MemoryEntrySummary> result = memoryService.related(name, depth == null ? 1 : depth);
        usageEventRecorder.record(UsageEvent.Action.RELATED, name, null, null, null);
        return result;
    }

    @McpTool(name = "memory_delete",
            description = "Delete a memory entry and its links. Use when a memory has become stale or wrong.")
    public Map<String, Object> memoryDelete(
            @McpToolParam(description = "The entry's name/slug", required = true) String name) {
        boolean deleted = memoryService.delete(name);
        usageEventRecorder.record(UsageEvent.Action.DELETE, name, null, null, null);
        return Map.of("deleted", deleted, "name", name);
    }
}
```

- [ ] **Step 3: Wire it into `TaskMcpTools`**

Replace the whole file with:

```java
package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.TaskSummary;
import ru.iuribabalin.memorymcp.entity.Task;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.TaskService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;
import java.util.Map;

@Component
public class TaskMcpTools {

    private final TaskService taskService;
    private final UsageEventRecorder usageEventRecorder;

    public TaskMcpTools(TaskService taskService, UsageEventRecorder usageEventRecorder) {
        this.taskService = taskService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "task_start",
            description = "Create or resume a task folder under a project. ALWAYS ask the user explicitly " +
                    "whether current work belongs to a task before calling this - never infer silently. The " +
                    "task is identified by its ticket/task key (e.g. a Jira key) - resolve it via a ticket-tracker " +
                    "MCP tool if one is available in this session, otherwise ask the user for the key directly. " +
                    "Idempotent: calling again with the same projectScope+taskKey resumes the existing task.")
    public TaskSummary taskStart(
            @McpToolParam(description = "Project identifier, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key, e.g. a Jira key or any user-given task number", required = true) String taskKey,
            @McpToolParam(description = "Task title/summary, from the ticket tracker or the user", required = false) String title,
            @McpToolParam(description = "MANUAL if the user gave the key directly, JIRA if resolved via a ticket-tracker tool", required = false) Task.Source source) {
        TaskSummary result = taskService.start(projectScope, taskKey, title, source);
        usageEventRecorder.record(UsageEvent.Action.TASK_START, null, projectScope, taskKey, null);
        return result;
    }

    @McpTool(name = "task_list",
            description = "List tasks under a project - use this to check whether a task already exists " +
                    "before creating a duplicate, or to show what's been worked on.")
    public List<TaskSummary> taskList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope) {
        return taskService.list(projectScope);
    }

    @McpTool(name = "task_close",
            description = "Mark a task as done once its work is complete.")
    public Map<String, Object> taskClose(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "The task/ticket key", required = true) String taskKey) {
        boolean closed = taskService.close(projectScope, taskKey);
        usageEventRecorder.record(UsageEvent.Action.TASK_CLOSE, null, projectScope, taskKey, null);
        return Map.of("closed", closed, "taskKey", taskKey);
    }
}
```

- [ ] **Step 4: Compile and smoke-check**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/service/UsageEventRecorder.java \
        src/main/java/ru/iuribabalin/memorymcp/mcp/MemoryMcpTools.java \
        src/main/java/ru/iuribabalin/memorymcp/mcp/TaskMcpTools.java
git commit -m "feat: record usage events from every MCP tool call"
```

---

### Task 3: Stats aggregation (`MemoryNodeRepository` additions, DTO, `StatsService`)

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/repository/MemoryNodeRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/StatsOverview.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/StatsService.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/StatsServiceTest.java`

**Interfaces:**
- Consumes: `UsageEventRepository.countByDay` (Task 1), `TaskService.resolve(String, String)` (existing, package-private, same package as `StatsService`).
- Produces: `StatsService.overview(String projectScope, String taskKey, int days): StatsOverview`, where `StatsOverview(Totals totals, List<DailyActivity> activityByDay, List<TypeBreakdown> byType, List<TopEntry> topEntries)`, `Totals(long totalEntries, long totalEvents)`, `DailyActivity(LocalDate day, long count)`, `TypeBreakdown(MemoryNode.Type type, long count)`, `TopEntry(String name, MemoryNode.Type type, String description, String projectScope, String taskKey, long accessCount)`.

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.entity.MemoryNode;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class StatsServiceTest {

    @Autowired
    private StatsService statsService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private UsageEventRepository usageEventRepository;

    @Test
    void overviewAggregatesEntriesAndEventsForAProject() {
        memoryService.save(new SaveMemoryRequest(
                "stats-test-entry", MemoryNode.Type.PROJECT, "desc", "content",
                "stats-test-project", null, null, "Tester <t@example.com>"));

        UsageEvent get = new UsageEvent();
        get.setAction(UsageEvent.Action.GET);
        get.setEntryName("stats-test-entry");
        get.setProjectScope("stats-test-project");
        get.setOccurredAt(Instant.now());
        usageEventRepository.saveAndFlush(get);

        StatsOverview overview = statsService.overview("stats-test-project", null, 30);

        assertThat(overview.totals().totalEntries()).isEqualTo(1);
        assertThat(overview.byType()).hasSize(1);
        assertThat(overview.byType().get(0).type()).isEqualTo(MemoryNode.Type.PROJECT);
        assertThat(overview.topEntries()).hasSize(1);
        assertThat(overview.topEntries().get(0).name()).isEqualTo("stats-test-entry");
        assertThat(overview.topEntries().get(0).accessCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.StatsServiceTest"`
Expected: FAIL — `StatsService`/`StatsOverview` don't exist yet.

- [ ] **Step 3: Add the aggregate queries to `MemoryNodeRepository`**

Add these two methods and nested projection interfaces inside the existing `MemoryNodeRepository` interface (after the existing `search` method):

```java
    interface TypeCountRow {
        MemoryNode.Type getType();
        long getCnt();
    }

    interface TopEntryRow {
        String getName();
        MemoryNode.Type getType();
        String getDescription();
        String getProjectScope();
        String getTaskKey();
        long getCnt();
    }

    @Query("""
            select n.type as type, count(n) as cnt from MemoryNode n
            where (:projectScope is null or n.projectScope = :projectScope)
            and (:taskId is null or n.task.id = :taskId)
            group by n.type
            """)
    List<TypeCountRow> countGroupedByType(@Param("projectScope") String projectScope,
                                           @Param("taskId") Long taskId);

    @Query(value = """
            select n.name as name, n.type as type, n.description as description,
                   n.project_scope as projectScope, t.task_key as taskKey, count(ue.id) as cnt
            from memory_nodes n
            join usage_events ue on ue.entry_name = n.name
            left join tasks t on t.id = n.task_id
            where ue.occurred_at >= :since
            and ue.action in ('GET','RELATED')
            and (:projectScope is null or n.project_scope = :projectScope)
            and (:taskId is null or n.task_id = :taskId)
            group by n.id, n.name, n.type, n.description, n.project_scope, t.task_key
            order by cnt desc
            limit :limit
            """, nativeQuery = true)
    List<TopEntryRow> topAccessedEntries(@Param("since") Instant since,
                                          @Param("projectScope") String projectScope,
                                          @Param("taskId") Long taskId,
                                          @Param("limit") int limit);
```

Add `import java.time.Instant;` to the top of the file alongside the existing imports.

- [ ] **Step 4: Create the DTO**

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.LocalDate;
import java.util.List;

public record StatsOverview(
        Totals totals,
        List<DailyActivity> activityByDay,
        List<TypeBreakdown> byType,
        List<TopEntry> topEntries
) {
    public record Totals(long totalEntries, long totalEvents) {
    }

    public record DailyActivity(LocalDate day, long count) {
    }

    public record TypeBreakdown(MemoryNode.Type type, long count) {
    }

    public record TopEntry(String name, MemoryNode.Type type, String description,
                            String projectScope, String taskKey, long accessCount) {
    }
}
```

- [ ] **Step 5: Create `StatsService`**

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.repository.MemoryNodeRepository;
import ru.iuribabalin.memorymcp.repository.UsageEventRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class StatsService {

    private final MemoryNodeRepository nodeRepository;
    private final UsageEventRepository eventRepository;
    private final TaskService taskService;

    public StatsService(MemoryNodeRepository nodeRepository, UsageEventRepository eventRepository, TaskService taskService) {
        this.nodeRepository = nodeRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
    }

    @Transactional(readOnly = true)
    public StatsOverview overview(String projectScope, String taskKey, int days) {
        int window = days > 0 ? days : 30;
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
        // taskKey only makes sense together with a projectScope - same contract as memory_save.
        Long taskId = (projectScope != null && taskKey != null)
                ? taskService.resolve(projectScope, taskKey).getId()
                : null;
        String scopedTaskKey = projectScope != null ? taskKey : null;

        List<StatsOverview.DailyActivity> activityByDay = eventRepository
                .countByDay(since, projectScope, scopedTaskKey)
                .stream()
                .map(row -> new StatsOverview.DailyActivity(row.getDay().atZone(ZoneOffset.UTC).toLocalDate(), row.getCnt()))
                .toList();

        List<StatsOverview.TypeBreakdown> byType = nodeRepository
                .countGroupedByType(projectScope, taskId)
                .stream()
                .map(row -> new StatsOverview.TypeBreakdown(row.getType(), row.getCnt()))
                .toList();

        List<StatsOverview.TopEntry> topEntries = nodeRepository
                .topAccessedEntries(since, projectScope, taskId, 10)
                .stream()
                .map(row -> new StatsOverview.TopEntry(
                        row.getName(), row.getType(), row.getDescription(),
                        row.getProjectScope(), row.getTaskKey(), row.getCnt()))
                .toList();

        long totalEntries = byType.stream().mapToLong(StatsOverview.TypeBreakdown::count).sum();
        long totalEvents = activityByDay.stream().mapToLong(StatsOverview.DailyActivity::count).sum();

        return new StatsOverview(new StatsOverview.Totals(totalEntries, totalEvents), activityByDay, byType, topEntries);
    }
}
```

- [ ] **Step 6: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.StatsServiceTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/repository/MemoryNodeRepository.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/StatsOverview.java \
        src/main/java/ru/iuribabalin/memorymcp/service/StatsService.java \
        src/test/java/ru/iuribabalin/memorymcp/service/StatsServiceTest.java
git commit -m "feat: aggregate usage stats via StatsService"
```

---

### Task 4: `StatsViewController`

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/ui/StatsViewController.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/StatsViewControllerTest.java`

**Interfaces:**
- Consumes: `StatsService.overview(String, String, int)` (Task 3).
- Produces: `GET /api/stats/overview?projectScope=&taskKey=&days=30` returning a `StatsOverview` JSON body; `TaskNotFoundException` (existing, already handled by `ApiExceptionHandler`) on an unknown project/taskKey pair.

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.service.StatsService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsViewController.class)
class StatsViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatsService statsService;

    @Test
    void returnsOverviewForAScopedProject() throws Exception {
        StatsOverview overview = new StatsOverview(
                new StatsOverview.Totals(3, 5),
                List.of(),
                List.of(),
                List.of());
        when(statsService.overview(eq("memory-mcp"), any(), anyInt())).thenReturn(overview);

        mockMvc.perform(get("/api/stats/overview").param("projectScope", "memory-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.totalEntries").value(3))
                .andExpect(jsonPath("$.totals.totalEvents").value(5));
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.ui.StatsViewControllerTest"`
Expected: FAIL — `StatsViewController` doesn't exist yet.

- [ ] **Step 3: Create the controller**

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.StatsOverview;
import ru.iuribabalin.memorymcp.service.StatsService;

@RestController
public class StatsViewController {

    private final StatsService statsService;

    public StatsViewController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/stats/overview")
    public StatsOverview overview(
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false, defaultValue = "30") int days) {
        return statsService.overview(projectScope, taskKey, days);
    }
}
```

- [ ] **Step 4: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.ui.StatsViewControllerTest"`
Expected: PASS (this test uses `@WebMvcTest` + a mocked service, no database needed)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/ui/StatsViewController.java \
        src/test/java/ru/iuribabalin/memorymcp/ui/StatsViewControllerTest.java
git commit -m "feat: expose GET /api/stats/overview"
```

---

### Task 5: Frontend stats types + API client

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/api/client.ts`

**Interfaces:**
- Consumes: `GET /api/stats/overview` (Task 4).
- Produces: `fetchStats(projectScope?: string | null, taskKey?: string | null, days?: number): Promise<StatsOverview>`; types `StatsOverview`, `StatsTotals`, `DailyActivity`, `TypeBreakdown`, `TopEntry`.

- [ ] **Step 1: Add the types**

Append to `ui/src/api/types.ts`:

```ts
export interface StatsTotals {
  totalEntries: number
  totalEvents: number
}

export interface DailyActivity {
  day: string
  count: number
}

export interface TypeBreakdown {
  type: MemoryType
  count: number
}

export interface TopEntry {
  name: string
  type: MemoryType
  description: string
  projectScope: string | null
  taskKey: string | null
  accessCount: number
}

export interface StatsOverview {
  totals: StatsTotals
  activityByDay: DailyActivity[]
  byType: TypeBreakdown[]
  topEntries: TopEntry[]
}
```

- [ ] **Step 2: Add the client function**

Add `StatsOverview` to the `import type { ... } from './types'` block at the top of `ui/src/api/client.ts`, and append at the end of the file:

```ts
export function fetchStats(
  projectScope?: string | null,
  taskKey?: string | null,
  days = 30,
): Promise<StatsOverview> {
  return getJson('/api/stats/overview', {
    projectScope: projectScope ?? undefined,
    taskKey: taskKey ?? undefined,
    days,
  })
}
```

- [ ] **Step 3: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 4: Commit**

```bash
git add ui/src/api/types.ts ui/src/api/client.ts
git commit -m "feat(ui): add stats API types and client"
```

---

### Task 6: `ConstellationField` signature component

**Files:**
- Create: `ui/src/components/ConstellationField.vue`

**Interfaces:**
- Produces: `<ConstellationField :density="28" />` — an absolutely-positioned, `pointer-events-none` decorative SVG dot/line pattern that fills its (positioned, `overflow-hidden`) parent. No props are required; `density` (pattern tile size in px) defaults to 28.

- [ ] **Step 1: Create the component**

```vue
<script setup lang="ts">
withDefaults(defineProps<{ density?: number }>(), { density: 28 })

const patternId = `constellation-${Math.random().toString(36).slice(2)}`
</script>

<template>
  <svg class="pointer-events-none absolute inset-0 size-full" aria-hidden="true">
    <defs>
      <pattern :id="patternId" :width="density" :height="density" patternUnits="userSpaceOnUse">
        <circle cx="2" cy="2" r="1.4" class="fill-border-strong" />
        <line x1="2" y1="2" :x2="density" y2="2" class="stroke-border-strong" stroke-width="0.6" opacity="0.4" />
        <line x1="2" y1="2" x2="2" :y2="density" class="stroke-border-strong" stroke-width="0.6" opacity="0.4" />
      </pattern>
    </defs>
    <rect width="100%" height="100%" :fill="`url(#${patternId})`" />
  </svg>
</template>
```

- [ ] **Step 2: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add ui/src/components/ConstellationField.vue
git commit -m "feat(ui): add ConstellationField signature component"
```

---

### Task 7: Density + type-color pass on rows and cards

**Files:**
- Modify: `ui/src/components/EntryRow.vue`
- Modify: `ui/src/components/TaskRow.vue`
- Modify: `ui/src/components/ProjectCard.vue`

**Interfaces:**
- Consumes: `ConstellationField` (Task 6).
- Produces: `EntryRow` gains an optional `accessCount?: number` prop (used by Task 8's "most accessed" list) and shows a type-colored left border. No other component's public props change.

- [ ] **Step 1: Update `EntryRow.vue`**

Replace the whole file with:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import type { MemoryEntrySummary } from '@/api/types'
import { relativeTime } from '@/lib/format'
import { entryLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'
import TypeBadge from './TypeBadge.vue'

const props = withDefaults(
  defineProps<{ entry: MemoryEntrySummary; showScope?: boolean; accessCount?: number }>(),
  { showScope: false },
)

const to = computed(() => entryLocation(props.entry))

const scope = computed(() => {
  const parts = [props.entry.projectScope, props.entry.taskKey].filter(Boolean)
  return parts.join(' / ')
})
</script>

<template>
  <component
    :is="to ? RouterLink : 'div'"
    :to="to ?? undefined"
    class="group flex items-center gap-3.5 rounded-xl border border-l-2 border-border bg-panel px-3.5 py-2.5 transition duration-150"
    :style="{ borderLeftColor: `var(--color-type-${entry.type.toLowerCase()})` }"
    :class="to ? 'hover:-translate-y-px hover:border-accent/40 hover:shadow-panel' : 'opacity-80'"
  >
    <span
      class="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-elevated text-muted transition group-hover:border-accent/30 group-hover:text-accent"
    >
      <AppIcon name="document" class="size-4" />
    </span>

    <span class="min-w-0 flex-1">
      <span class="flex items-center gap-2">
        <TypeBadge :type="entry.type" variant="dot" />
        <span class="truncate text-[13.5px] font-medium text-content">{{ entry.name }}</span>
        <span
          v-if="showScope && scope"
          class="hidden truncate rounded-md bg-elevated px-1.5 py-0.5 font-mono text-[11px] text-faint sm:inline"
        >
          {{ scope }}
        </span>
      </span>
      <span class="mt-0.5 block truncate text-[12.5px] text-muted">{{ entry.description }}</span>
    </span>

    <span
      v-if="accessCount !== undefined"
      class="hidden shrink-0 rounded-full bg-elevated px-2 py-0.5 text-[11px] font-medium text-muted tabular-nums sm:inline"
    >
      {{ accessCount }} {{ accessCount === 1 ? 'view' : 'views' }}
    </span>
    <time
      class="hidden shrink-0 text-[12px] whitespace-nowrap text-faint sm:block"
      :datetime="entry.updatedAt"
    >
      {{ relativeTime(entry.updatedAt) }}
    </time>
    <AppIcon
      v-if="to"
      name="chevron"
      class="size-3.5 shrink-0 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent"
    />
  </component>
</template>
```

- [ ] **Step 2: Tighten `TaskRow.vue` density**

In `ui/src/components/TaskRow.vue`, change the `RouterLink`'s class from
`"group flex items-center gap-3.5 rounded-xl border border-border bg-panel px-4 py-3 transition duration-150 hover:-translate-y-px hover:border-accent/40 hover:shadow-panel"`
to
`"group flex items-center gap-3.5 rounded-xl border border-border bg-panel px-3.5 py-2.5 transition duration-150 hover:-translate-y-px hover:border-accent/40 hover:shadow-panel"`
(only the padding changes, `px-4 py-3` → `px-3.5 py-2.5`).

- [ ] **Step 3: Swap `ProjectCard.vue`'s decorative blur for `ConstellationField`**

In `ui/src/components/ProjectCard.vue`:
1. Add `import ConstellationField from './ConstellationField.vue'` below the existing `AppIcon` import.
2. Replace the decorative `<div>` block:
```vue
    <div
      class="pointer-events-none absolute -top-16 -right-16 size-32 rounded-full bg-accent/10 opacity-0 blur-2xl transition duration-300 group-hover:opacity-100"
    />
```
with:
```vue
    <ConstellationField class="opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
```
3. Change the root `RouterLink`'s `p-5` to `p-4` (density pass; leave every other class on that element unchanged).

- [ ] **Step 4: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 5: Manual check**

Run: `cd ui && npm run dev`, open the app, and confirm: entry rows in a project show a thin colored left edge matching their type dot; hovering a project card on the Projects page reveals a faint dot/line pattern instead of the old blurred circle; task rows are visibly a bit more compact than before.

- [ ] **Step 6: Commit**

```bash
git add ui/src/components/EntryRow.vue ui/src/components/TaskRow.vue ui/src/components/ProjectCard.vue
git commit -m "feat(ui): denser rows, type-color left border, constellation card hover"
```

---

### Task 8: `StatsView.vue` (global statistics page) + navigation

**Files:**
- Create: `ui/src/views/StatsView.vue`
- Modify: `ui/src/components/AppIcon.vue`
- Modify: `ui/src/components/AppSidebar.vue`
- Modify: `ui/src/router/index.ts`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java`

**Interfaces:**
- Consumes: `fetchStats` (Task 5), `ConstellationField` (Task 6), `EntryRow` with `accessCount` prop (Task 7).
- Produces: route `{ name: 'stats', path: '/stats' }`; a "Statistics" sidebar link.

- [ ] **Step 1: Add a chart icon to `AppIcon.vue`**

In `ui/src/components/AppIcon.vue`, add this entry to the `ICONS` map (after the `graph` entry):

```ts
  chart: [
    'M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 0 1 3 19.875v-6.75ZM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V8.625ZM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V4.125Z',
  ],
```

- [ ] **Step 2: Create `StatsView.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'

import { fetchStats } from '@/api/client'
import type { DailyActivity, MemoryType } from '@/api/types'
import ConstellationField from '@/components/ConstellationField.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryRow from '@/components/EntryRow.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import TypeBadge from '@/components/TypeBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'

// Written out in full so Tailwind can see every class it needs to generate.
const BAR_FILL: Record<MemoryType, string> = {
  USER: 'bg-type-user',
  FEEDBACK: 'bg-type-feedback',
  PROJECT: 'bg-type-project',
  REFERENCE: 'bg-type-reference',
  LOCATION: 'bg-type-location',
  REPORT: 'bg-type-report',
}

const { data: stats, error, loading, reload } = useAsyncData(() => fetchStats(null, null, 30))

const CHART_WIDTH = 640
const CHART_HEIGHT = 140

function points(activity: DailyActivity[]): string {
  if (activity.length === 0) {
    return ''
  }
  const max = Math.max(...activity.map((d) => d.count), 1)
  const stepX = activity.length > 1 ? CHART_WIDTH / (activity.length - 1) : 0
  return activity
    .map((d, i) => `${i * stepX},${CHART_HEIGHT - (d.count / max) * (CHART_HEIGHT - 8) - 4}`)
    .join(' ')
}

const linePoints = computed(() => (stats.value ? points(stats.value.activityByDay) : ''))
const areaPoints = computed(() =>
  stats.value && stats.value.activityByDay.length > 0
    ? `0,${CHART_HEIGHT} ${linePoints.value} ${CHART_WIDTH},${CHART_HEIGHT}`
    : '',
)

const maxTypeCount = computed(() => Math.max(...(stats.value?.byType.map((t) => t.count) ?? [1]), 1))
</script>

<template>
  <div>
    <PageHeader eyebrow="Overview" title="Statistics" subtitle="How memory is being used across every project." />

    <ErrorState v-if="error" :message="error" class="mb-6" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="4" />

    <template v-else-if="stats">
      <section class="group relative mb-6 overflow-hidden rounded-2xl border border-border bg-panel p-6">
        <ConstellationField class="opacity-40" />
        <div class="relative flex flex-wrap gap-8">
          <div>
            <p class="text-3xl font-semibold tracking-tight text-content tabular-nums">
              {{ stats.totals.totalEvents }}
            </p>
            <p class="mt-1 text-[12px] font-semibold tracking-wide text-faint uppercase">Events · last 30 days</p>
          </div>
          <div>
            <p class="text-3xl font-semibold tracking-tight text-content tabular-nums">
              {{ stats.totals.totalEntries }}
            </p>
            <p class="mt-1 text-[12px] font-semibold tracking-wide text-faint uppercase">Entries stored</p>
          </div>
        </div>

        <EmptyState
          v-if="stats.activityByDay.length === 0"
          icon="sparkles"
          title="No activity yet"
          hint="Once Claude saves or reads memory, activity shows up here."
          class="relative mt-6 border-0 bg-transparent"
        />
        <svg
          v-else
          class="relative mt-4 h-[140px] w-full"
          :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
          preserveAspectRatio="none"
        >
          <polygon :points="areaPoints" class="fill-accent/10" />
          <polyline
            :points="linePoints"
            fill="none"
            class="stroke-accent"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </section>

      <section class="mb-6 rounded-2xl border border-border bg-panel p-5">
        <h2 class="mb-4 text-[13px] font-semibold tracking-wide text-content uppercase">By type</h2>
        <EmptyState v-if="stats.byType.length === 0" icon="document" title="No entries yet" />
        <div v-else class="space-y-2.5">
          <div v-for="row in stats.byType" :key="row.type" class="flex items-center gap-3">
            <TypeBadge :type="row.type" variant="dot" />
            <span class="w-20 shrink-0 text-[12.5px] font-medium text-content">{{ row.type }}</span>
            <div class="h-2 flex-1 overflow-hidden rounded-full bg-elevated">
              <div
                class="h-full rounded-full"
                :class="BAR_FILL[row.type]"
                :style="{ width: `${(row.count / maxTypeCount) * 100}%` }"
              />
            </div>
            <span class="w-8 shrink-0 text-right text-[12.5px] tabular-nums text-muted">{{ row.count }}</span>
          </div>
        </div>
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <h2 class="mb-4 text-[13px] font-semibold tracking-wide text-content uppercase">Most accessed</h2>
        <EmptyState v-if="stats.topEntries.length === 0" icon="graph" title="Nothing accessed yet" />
        <div v-else class="space-y-2">
          <EntryRow
            v-for="entry in stats.topEntries"
            :key="entry.name"
            :entry="{
              name: entry.name,
              type: entry.type,
              description: entry.description,
              projectScope: entry.projectScope,
              taskKey: entry.taskKey,
              filePath: null,
              createdBy: null,
              updatedAt: '',
            }"
            :access-count="entry.accessCount"
          />
        </div>
      </section>
    </template>
  </div>
</template>
```

- [ ] **Step 3: Add the route**

In `ui/src/router/index.ts`, add after the `setup` route:

```ts
    { path: '/stats', name: 'stats', component: () => import('@/views/StatsView.vue') },
```

- [ ] **Step 4: Mirror the route in `SpaForwardController`**

In `src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java`, change:
```java
    @GetMapping({"/setup", "/p/**"})
```
to:
```java
    @GetMapping({"/setup", "/stats", "/p/**"})
```

- [ ] **Step 5: Add the sidebar nav link**

In `ui/src/components/AppSidebar.vue`, add this `RouterLink` right after the closing tag of the existing "All projects" `RouterLink` and before the "Setup" `RouterLink`:

```vue
      <RouterLink
        :to="{ name: 'stats' }"
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium text-muted transition hover:bg-elevated hover:text-content"
        active-class="!bg-accent-soft !text-accent"
      >
        <AppIcon name="chart" class="size-4" />
        Statistics
      </RouterLink>
```

- [ ] **Step 6: Type-check and compile**

Run: `cd ui && npm run type-check && cd .. && ./gradlew compileJava`
Expected: both succeed

- [ ] **Step 7: Manual check**

Run: `./gradlew bootRun` (or `cd ui && npm run dev` against an already-running backend), navigate to `/stats` and via the new sidebar link, and confirm the page loads (empty state is fine on a fresh database) in both light and dark theme, and that a hard refresh on `/stats` doesn't 404.

- [ ] **Step 8: Commit**

```bash
git add ui/src/views/StatsView.vue ui/src/components/AppIcon.vue ui/src/components/AppSidebar.vue \
        ui/src/router/index.ts src/main/java/ru/iuribabalin/memorymcp/ui/SpaForwardController.java
git commit -m "feat(ui): add global Statistics page and navigation"
```

---

### Task 9: Header activity pulse

**Files:**
- Modify: `ui/src/components/AppHeader.vue`

**Interfaces:**
- Consumes: `fetchStats` (Task 5).

- [ ] **Step 1: Update `AppHeader.vue`**

Replace the whole file with:

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { fetchStats } from '@/api/client'
import { useAsyncData } from '@/composables/useAsyncData'
import { useTheme } from '@/composables/useTheme'

import AppIcon from './AppIcon.vue'
import BreadcrumbBar from './BreadcrumbBar.vue'

defineEmits<{ 'toggle-sidebar': []; 'open-search': [] }>()

const { isDark, toggle } = useTheme()
const shortcut = ref('Ctrl K')

onMounted(() => {
  if (navigator.platform.toLowerCase().includes('mac')) {
    shortcut.value = '⌘ K'
  }
})

const { data: pulse } = useAsyncData(() => fetchStats(null, null, 7))
const eventCount = computed(() => pulse.value?.totals.totalEvents ?? null)
</script>

<template>
  <header
    class="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border bg-bg/80 px-4 backdrop-blur-md sm:px-6 lg:px-10"
  >
    <button
      type="button"
      class="-ml-1 rounded-lg p-1.5 text-muted transition hover:bg-elevated hover:text-content lg:hidden"
      aria-label="Open navigation"
      @click="$emit('toggle-sidebar')"
    >
      <AppIcon name="menu" class="size-5" />
    </button>

    <BreadcrumbBar class="min-w-0 flex-1" />

    <RouterLink
      v-if="eventCount !== null"
      :to="{ name: 'stats' }"
      class="hidden items-center gap-1.5 rounded-full bg-elevated px-2.5 py-1 text-[11.5px] font-medium text-muted transition hover:text-content md:inline-flex"
    >
      <span class="size-1.5 rounded-full bg-accent" />
      {{ eventCount }} {{ eventCount === 1 ? 'event' : 'events' }} this week
    </RouterLink>

    <button
      type="button"
      class="group flex items-center gap-2 rounded-lg border border-border bg-panel py-1.5 pr-2 pl-2.5 text-[13px] text-muted transition hover:border-border-strong hover:text-content"
      @click="$emit('open-search')"
    >
      <AppIcon name="search" class="size-4" />
      <span class="hidden sm:inline">Search</span>
      <kbd
        class="hidden rounded-md border border-border bg-elevated px-1.5 py-0.5 font-sans text-[10.5px] font-medium text-faint sm:inline"
      >
        {{ shortcut }}
      </kbd>
    </button>

    <button
      type="button"
      class="rounded-lg border border-border bg-panel p-2 text-muted transition hover:border-border-strong hover:text-content"
      :aria-label="isDark ? 'Switch to light theme' : 'Switch to dark theme'"
      @click="toggle"
    >
      <AppIcon :name="isDark ? 'sun' : 'moon'" class="size-4" />
    </button>
  </header>
</template>
```

- [ ] **Step 2: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 3: Manual check**

Run: `cd ui && npm run dev`, confirm the header shows "N events this week" on a wide viewport (hidden below `md`), links to `/stats`, and the layout doesn't shift/overflow at narrow widths.

- [ ] **Step 4: Commit**

```bash
git add ui/src/components/AppHeader.vue
git commit -m "feat(ui): show weekly activity pulse in the header"
```

---

### Task 10: Per-project stats section

**Files:**
- Modify: `ui/src/views/ProjectView.vue`

**Interfaces:**
- Consumes: `fetchStats` (Task 5).

- [ ] **Step 1: Add the stats fetch**

In `ui/src/views/ProjectView.vue`, add `fetchStats` to the existing `import { fetchEntries, fetchTasks } from '@/api/client'` line (making it `import { fetchEntries, fetchStats, fetchTasks } from '@/api/client'`), and add this alongside the existing `common`/`tasks` data fetches:

```ts
const { data: stats, loading: statsLoading } = useAsyncData(() => fetchStats(project.value, null, 30), [project])
```

- [ ] **Step 2: Add the section**

Insert this new `<section>` in the template, between the closing `</section>` of the "Common" section and the opening `<section>` of the "Tasks" section:

```vue
    <section class="mb-9 rounded-2xl border border-border bg-panel p-5">
      <h2 class="mb-4 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="chart" class="size-4 text-faint" />
        Activity
      </h2>
      <SkeletonRows v-if="statsLoading" :rows="1" />
      <div v-else-if="stats" class="flex flex-wrap gap-8">
        <div>
          <p class="text-2xl font-semibold tracking-tight text-content tabular-nums">
            {{ stats.totals.totalEvents }}
          </p>
          <p class="mt-1 text-[12px] text-faint">Events · last 30 days</p>
        </div>
        <div v-if="stats.topEntries[0]" class="min-w-0">
          <p class="truncate text-2xl font-semibold tracking-tight text-content">
            {{ stats.topEntries[0].name }}
          </p>
          <p class="mt-1 text-[12px] text-faint">Most accessed</p>
        </div>
      </div>
    </section>
```

- [ ] **Step 3: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 4: Manual check**

Run: `cd ui && npm run dev`, open any project page, and confirm the new "Activity" section renders (zeros are fine on a fresh database) above the Tasks section.

- [ ] **Step 5: Commit**

```bash
git add ui/src/views/ProjectView.vue
git commit -m "feat(ui): show per-project activity stats"
```

---

### Task 11: Folder schema (migration V6, `Folder` entity, `MemoryNode.folder`, `FolderRepository`)

**Files:**
- Create: `src/main/resources/db/migration/V6__add_folders.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/Folder.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/MemoryNode.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/FolderRepository.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/repository/FolderRepositoryTest.java`

**Interfaces:**
- Produces: `Folder` entity (`id, name, description, projectScope, task, parent, createdBy, createdAt, updatedAt`), `MemoryNode.getFolder()/setFolder(Folder)`, `FolderRepository.findByName(String)` and `List<Folder> listChildren(String parentName, String projectScope, Long taskId)` (`parentName == null` means top-level).

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Folder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FolderRepositoryTest {

    @Autowired
    private FolderRepository repository;

    @Test
    void listsDirectChildrenOnly() {
        Folder root = save("folder-repo-test-root", null);
        Folder child = save("folder-repo-test-child", root);
        save("folder-repo-test-grandchild", child);

        List<Folder> topLevel = repository.listChildren(null, "folder-repo-test-project", null);
        assertThat(topLevel).extracting(Folder::getName).contains("folder-repo-test-root");

        List<Folder> children = repository.listChildren("folder-repo-test-root", "folder-repo-test-project", null);
        assertThat(children).extracting(Folder::getName).containsExactly("folder-repo-test-child");
    }

    private Folder save(String name, Folder parent) {
        Folder folder = new Folder();
        folder.setName(name);
        folder.setDescription("desc");
        folder.setProjectScope("folder-repo-test-project");
        folder.setParent(parent);
        folder.setCreatedAt(Instant.now());
        folder.setUpdatedAt(Instant.now());
        return repository.saveAndFlush(folder);
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.repository.FolderRepositoryTest"`
Expected: FAIL — `Folder`/`FolderRepository` don't exist yet.

- [ ] **Step 3: Create the migration**

```sql
CREATE TABLE folders (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(500) NOT NULL,
    description    VARCHAR(500) NOT NULL,
    project_scope  VARCHAR(200) NOT NULL,
    task_id        BIGINT REFERENCES tasks (id) ON DELETE CASCADE,
    parent_id      BIGINT REFERENCES folders (id) ON DELETE CASCADE,
    created_by     VARCHAR(300),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_folders_name UNIQUE (name)
);

CREATE INDEX idx_folders_project_scope ON folders (project_scope);
CREATE INDEX idx_folders_parent_id ON folders (parent_id);
CREATE INDEX idx_folders_task_id ON folders (task_id);

ALTER TABLE memory_nodes ADD COLUMN folder_id BIGINT REFERENCES folders (id) ON DELETE SET NULL;
CREATE INDEX idx_memory_nodes_folder_id ON memory_nodes (folder_id);
```

- [ ] **Step 4: Create the `Folder` entity**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "folders")
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "project_scope", nullable = false, length = 200)
    private String projectScope;

    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne
    @JoinColumn(name = "parent_id")
    private Folder parent;

    @Column(name = "created_by", length = 300)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public void setProjectScope(String projectScope) {
        this.projectScope = projectScope;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public Folder getParent() {
        return parent;
    }

    public void setParent(Folder parent) {
        this.parent = parent;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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

- [ ] **Step 5: Add `folder` to `MemoryNode`**

In `src/main/java/ru/iuribabalin/memorymcp/entity/MemoryNode.java`, add this field next to the existing `task` field (imports `ManyToOne`/`JoinColumn` are already present in this file):

```java
    @ManyToOne
    @JoinColumn(name = "folder_id")
    private Folder folder;
```

and these accessors next to `getTask()`/`setTask()`:

```java
    public Folder getFolder() {
        return folder;
    }

    public void setFolder(Folder folder) {
        this.folder = folder;
    }
```

- [ ] **Step 6: Create the repository**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.iuribabalin.memorymcp.entity.Folder;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    Optional<Folder> findByName(String name);

    @Query("""
            select f from Folder f
            where f.projectScope = :projectScope
            and ((:taskId is null and f.task is null) or f.task.id = :taskId)
            and ((:parentName is null and f.parent is null) or f.parent.name = :parentName)
            order by f.name
            """)
    List<Folder> listChildren(@Param("parentName") String parentName,
                               @Param("projectScope") String projectScope,
                               @Param("taskId") Long taskId);
}
```

- [ ] **Step 7: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.repository.FolderRepositoryTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V6__add_folders.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/Folder.java \
        src/main/java/ru/iuribabalin/memorymcp/entity/MemoryNode.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/FolderRepository.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/FolderRepositoryTest.java
git commit -m "feat: add folders table and MemoryNode.folder"
```

---

### Task 12: `FolderService`

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/dto/FolderSummary.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/FolderNotFoundException.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/FolderService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/FolderServiceTest.java`

**Interfaces:**
- Consumes: `FolderRepository` (Task 11), `TaskService.resolve(String, String)` (existing, package-private).
- Produces: `FolderSummary(String name, String description, String projectScope, String taskKey, String parentFolder, String createdBy, Instant updatedAt)`; `FolderService.create(String projectScope, String taskKey, String name, String description, String parentFolder, String createdBy): FolderSummary` (idempotent upsert by name, throws `IllegalArgumentException` if `parentFolder` belongs to a different project/task); `FolderService.listChildren(String projectScope, String taskKey, String parentFolder): List<FolderSummary>`.

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.FolderSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FolderServiceTest {

    @Autowired
    private FolderService folderService;

    @Test
    void createsNestedFolderAndListsItAsAChild() {
        folderService.create("folder-svc-test-project", null, "folder-svc-test-root", "root desc", null, "Tester");
        FolderSummary child = folderService.create(
                "folder-svc-test-project", null, "folder-svc-test-child", "child desc", "folder-svc-test-root", "Tester");

        assertThat(child.parentFolder()).isEqualTo("folder-svc-test-root");
        assertThat(folderService.listChildren("folder-svc-test-project", null, "folder-svc-test-root"))
                .extracting(FolderSummary::name)
                .containsExactly("folder-svc-test-child");
    }

    @Test
    void rejectsParentFromADifferentProject() {
        folderService.create("folder-svc-test-project-a", null, "folder-svc-test-a-root", "desc", null, "Tester");

        assertThatThrownBy(() -> folderService.create(
                "folder-svc-test-project-b", null, "folder-svc-test-b-child", "desc", "folder-svc-test-a-root", "Tester"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.FolderServiceTest"`
Expected: FAIL — `FolderService` doesn't exist yet.

- [ ] **Step 3: Create the DTO and exception**

```java
package ru.iuribabalin.memorymcp.dto;

import java.time.Instant;

public record FolderSummary(
        String name,
        String description,
        String projectScope,
        String taskKey,
        String parentFolder,
        String createdBy,
        Instant updatedAt
) {
}
```

```java
package ru.iuribabalin.memorymcp.service;

public class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(String name) {
        super("No folder named '" + name + "' - call folder_create first");
    }
}
```

- [ ] **Step 4: Create `FolderService`**

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.entity.Folder;
import ru.iuribabalin.memorymcp.repository.FolderRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final TaskService taskService;

    public FolderService(FolderRepository folderRepository, TaskService taskService) {
        this.folderRepository = folderRepository;
        this.taskService = taskService;
    }

    @Transactional
    public FolderSummary create(String projectScope, String taskKey, String name, String description,
                                 String parentFolder, String createdBy) {
        Instant now = Instant.now();
        Folder folder = folderRepository.findByName(name).orElseGet(Folder::new);
        boolean isNew = folder.getId() == null;
        folder.setName(name);
        folder.setDescription(description);
        folder.setProjectScope(projectScope);
        folder.setTask(taskKey != null ? taskService.resolve(projectScope, taskKey) : null);
        folder.setParent(resolveParent(parentFolder, projectScope, taskKey));
        if (isNew) {
            folder.setCreatedBy(createdBy);
            folder.setCreatedAt(now);
        }
        folder.setUpdatedAt(now);
        return toSummary(folderRepository.save(folder));
    }

    @Transactional(readOnly = true)
    public List<FolderSummary> listChildren(String projectScope, String taskKey, String parentFolder) {
        Long taskId = taskKey != null ? taskService.resolve(projectScope, taskKey).getId() : null;
        return folderRepository.listChildren(parentFolder, projectScope, taskId).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public FolderSummary get(String name) {
        return folderRepository.findByName(name).map(this::toSummary)
                .orElseThrow(() -> new FolderNotFoundException(name));
    }

    private Folder resolveParent(String parentFolder, String projectScope, String taskKey) {
        if (parentFolder == null) {
            return null;
        }
        Folder parent = folderRepository.findByName(parentFolder)
                .orElseThrow(() -> new FolderNotFoundException(parentFolder));
        String parentTaskKey = parent.getTask() != null ? parent.getTask().getTaskKey() : null;
        if (!Objects.equals(parent.getProjectScope(), projectScope) || !Objects.equals(parentTaskKey, taskKey)) {
            throw new IllegalArgumentException(
                    "Parent folder '%s' belongs to a different project/task scope".formatted(parentFolder));
        }
        return parent;
    }

    private FolderSummary toSummary(Folder folder) {
        return new FolderSummary(
                folder.getName(),
                folder.getDescription(),
                folder.getProjectScope(),
                folder.getTask() != null ? folder.getTask().getTaskKey() : null,
                folder.getParent() != null ? folder.getParent().getName() : null,
                folder.getCreatedBy(),
                folder.getUpdatedAt());
    }
}
```

- [ ] **Step 5: Handle `FolderNotFoundException` in `ApiExceptionHandler`**

Add this import and handler method to `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`:

```java
import ru.iuribabalin.memorymcp.service.FolderNotFoundException;
```

```java
    @ExceptionHandler(FolderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleFolderNotFound(FolderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
```

- [ ] **Step 6: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.FolderServiceTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/FolderSummary.java \
        src/main/java/ru/iuribabalin/memorymcp/service/FolderNotFoundException.java \
        src/main/java/ru/iuribabalin/memorymcp/service/FolderService.java \
        src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java \
        src/test/java/ru/iuribabalin/memorymcp/service/FolderServiceTest.java
git commit -m "feat: add FolderService with nested-folder scope validation"
```

---

### Task 13: `folder_create` / `folder_list` MCP tools

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/mcp/FolderMcpTools.java`

**Interfaces:**
- Consumes: `FolderService` (Task 12), `UsageEventRecorder` (Task 2).
- Produces: MCP tools `folder_create(projectScope, taskKey?, name, description, parentFolder?, createdBy?)` and `folder_list(projectScope, taskKey?, parentFolder?)`.

- [ ] **Step 1: Add `FOLDER_CREATE` to `UsageEvent.Action`**

In `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java`, change:
```java
    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE
    }
```
to:
```java
    public enum Action {
        SAVE, GET, LIST, SEARCH, GRAPH, RELATED, DELETE, TASK_START, TASK_CLOSE, FOLDER_CREATE
    }
```

- [ ] **Step 2: Create `FolderMcpTools`**

```java
package ru.iuribabalin.memorymcp.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.entity.UsageEvent;
import ru.iuribabalin.memorymcp.service.FolderService;
import ru.iuribabalin.memorymcp.service.UsageEventRecorder;

import java.util.List;

@Component
public class FolderMcpTools {

    private final FolderService folderService;
    private final UsageEventRecorder usageEventRecorder;

    public FolderMcpTools(FolderService folderService, UsageEventRecorder usageEventRecorder) {
        this.folderService = folderService;
        this.usageEventRecorder = usageEventRecorder;
    }

    @McpTool(name = "folder_create",
            description = "Create or update a folder to organize memory entries under a project's common space or " +
                    "a task. Idempotent by name - calling again with the same name updates its description/parent. " +
                    "Call folder_list first to check whether a suitable folder already exists before creating a new " +
                    "one. Pass parentFolder to nest it inside another folder (must already exist, same project/task).")
    public FolderSummary folderCreate(
            @McpToolParam(description = "Project this folder belongs to, auto-derived from the git repo name", required = true) String projectScope,
            @McpToolParam(description = "Task key to scope this folder to a specific task; omit for a project-level common folder", required = false) String taskKey,
            @McpToolParam(description = "Unique kebab-case slug for this folder", required = true) String name,
            @McpToolParam(description = "One-line summary of what belongs in this folder", required = true) String description,
            @McpToolParam(description = "Name of an existing folder to nest this one inside; omit for a top-level folder", required = false) String parentFolder,
            @McpToolParam(description = "Who created this folder, e.g. 'Name <email>' - auto-derive from git config, never ask the user", required = false) String createdBy) {
        FolderSummary result = folderService.create(projectScope, taskKey, name, description, parentFolder, createdBy);
        usageEventRecorder.record(UsageEvent.Action.FOLDER_CREATE, name, projectScope, taskKey, createdBy);
        return result;
    }

    @McpTool(name = "folder_list",
            description = "List folders directly under a project's common space, a task, or another folder - use " +
                    "before folder_create to avoid duplicates, or to see how entries are already organized.")
    public List<FolderSummary> folderList(
            @McpToolParam(description = "Project identifier", required = true) String projectScope,
            @McpToolParam(description = "Task key filter; omit to list the project's common-space folders", required = false) String taskKey,
            @McpToolParam(description = "Parent folder name; omit to list top-level folders", required = false) String parentFolder) {
        return folderService.listChildren(projectScope, taskKey, parentFolder);
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java \
        src/main/java/ru/iuribabalin/memorymcp/mcp/FolderMcpTools.java
git commit -m "feat: add folder_create/folder_list MCP tools"
```

---

### Task 14: Folder-aware `memory_save`/`memory_list`/`memory_search`

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/SaveMemoryRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/MemoryEntrySummary.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/MemoryEntryDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/repository/MemoryNodeRepository.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/MemoryService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/MemoryMcpTools.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/MemoryServiceTest.java`

**Interfaces:**
- Consumes: `FolderRepository` (Task 11).
- Produces: `SaveMemoryRequest` gains `folder` (String, inserted right after `taskKey`, before `filePath`); `MemoryEntrySummary`/`MemoryEntryDetail` gain `folder` (String, inserted right after `taskKey`; neither DTO has a `createdBy` field to worry about — see Global Constraints); `MemoryService.list(type, projectScope, taskKey, folderName, limit, offset)` and `MemoryService.search(query, type, projectScope, taskKey, folderName, limit)` — **browsing without a `folderName` now means root-only** (entries inside any folder are excluded from the unscoped listing, matching a file-explorer model); `MemoryService.graph(...)` keeps its existing 3-arg signature and shows every entry in scope regardless of folder.

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.MemoryEntryDetail;
import ru.iuribabalin.memorymcp.dto.SaveMemoryRequest;
import ru.iuribabalin.memorymcp.entity.MemoryNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MemoryServiceTest {

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private FolderService folderService;

    @Test
    void savingIntoAFolderExcludesItFromTheRootListing() {
        folderService.create("mem-svc-test-project", null, "mem-svc-test-folder", "desc", null, "Tester");
        memoryService.save(new SaveMemoryRequest(
                "mem-svc-test-in-folder", MemoryNode.Type.PROJECT, "d", "c",
                "mem-svc-test-project", null, "mem-svc-test-folder", null, null, "Tester"));
        memoryService.save(new SaveMemoryRequest(
                "mem-svc-test-at-root", MemoryNode.Type.PROJECT, "d", "c",
                "mem-svc-test-project", null, null, null, null, "Tester"));

        assertThat(memoryService.list(null, "mem-svc-test-project", null, null, 50, 0))
                .extracting(s -> s.name())
                .containsExactly("mem-svc-test-at-root");

        assertThat(memoryService.list(null, "mem-svc-test-project", null, "mem-svc-test-folder", 50, 0))
                .extracting(s -> s.name())
                .containsExactly("mem-svc-test-in-folder");

        MemoryEntryDetail detail = memoryService.get("mem-svc-test-in-folder");
        assertThat(detail.folder()).isEqualTo("mem-svc-test-folder");
    }

    @Test
    void rejectsAFolderFromADifferentProject() {
        folderService.create("mem-svc-test-project-a", null, "mem-svc-test-a-folder", "desc", null, "Tester");

        assertThatThrownBy(() -> memoryService.save(new SaveMemoryRequest(
                "mem-svc-test-cross-project", MemoryNode.Type.PROJECT, "d", "c",
                "mem-svc-test-project-b", null, "mem-svc-test-a-folder", null, null, "Tester")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Note: `SaveMemoryRequest`'s field order is `(name, type, description, content, projectScope, taskKey, folder, filePath, createdBy)` after this task's Step 2 — `folder` is inserted right after `taskKey`, before the pre-existing `filePath`.

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.MemoryServiceTest"`
Expected: FAIL — `SaveMemoryRequest` has no `folder` parameter yet, `MemoryEntryDetail` has no `folder()`, `memoryService.list`/`get` don't match this shape yet.

- [ ] **Step 3: Add `folder` to the request/response DTOs**

Replace `src/main/java/ru/iuribabalin/memorymcp/dto/SaveMemoryRequest.java` with:

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

public record SaveMemoryRequest(
        String name,
        MemoryNode.Type type,
        String description,
        String content,
        String projectScope,
        String taskKey,
        String folder,
        String filePath,
        String createdBy
) {
}
```

Replace `src/main/java/ru/iuribabalin/memorymcp/dto/MemoryEntrySummary.java` with:

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.MemoryNode;

import java.time.Instant;

public record MemoryEntrySummary(
        String name,
        MemoryNode.Type type,
        String description,
        String projectScope,
        String taskKey,
        String folder,
        String filePath,
        Instant updatedAt
) {
}
```

Note: this worktree's committed `MemoryEntrySummary`/`MemoryEntryDetail`/`MemoryNode`/`MemoryService` do NOT have a `createdBy` field anywhere (that plumbing belongs to a separate, still-uncommitted feature in the parent checkout that this plan does not touch — see Global Constraints). Do not add `createdBy` to either DTO here; only add `folder`.

In `src/main/java/ru/iuribabalin/memorymcp/dto/MemoryEntryDetail.java`, add `String folder,` right after `String taskKey,` in the record's parameter list.

- [ ] **Step 4: Add folder-scoped queries to `MemoryNodeRepository`**

Replace the `listByFilters` method with:

```java
    @Query("""
            select n from MemoryNode n
            where (:type is null or n.type = :type)
            and (:projectScope is null or n.projectScope = :projectScope)
            and (
                :taskFilterMode = 'NONE'
                or (:taskFilterMode = 'COMMON' and n.task is null)
                or (:taskFilterMode = 'TASK' and n.task.id = :taskId)
            )
            and (
                :folderFilterMode = 'NONE'
                or (:folderFilterMode = 'ROOT' and n.folder is null)
                or (:folderFilterMode = 'IN' and n.folder.name = :folderName)
            )
            order by n.updatedAt desc
            """)
    List<MemoryNode> listByFilters(@Param("type") MemoryNode.Type type,
                                    @Param("projectScope") String projectScope,
                                    @Param("taskFilterMode") String taskFilterMode,
                                    @Param("taskId") Long taskId,
                                    @Param("folderFilterMode") String folderFilterMode,
                                    @Param("folderName") String folderName,
                                    Pageable pageable);
```

Replace the `search` method with:

```java
    @Query(value = """
            select n.* from memory_nodes n
            left join folders f on f.id = n.folder_id
            where n.search_vector @@ plainto_tsquery('english', :query)
            and (:type is null or n.type = :type)
            and (:projectScope is null or n.project_scope = :projectScope)
            and (
                :taskFilterMode = 'NONE'
                or (:taskFilterMode = 'COMMON' and n.task_id is null)
                or (:taskFilterMode = 'TASK' and n.task_id = :taskId)
            )
            and (
                :folderFilterMode = 'NONE'
                or (:folderFilterMode = 'ROOT' and n.folder_id is null)
                or (:folderFilterMode = 'IN' and f.name = :folderName)
            )
            order by ts_rank(n.search_vector, plainto_tsquery('english', :query)) desc
            """, nativeQuery = true)
    List<MemoryNode> search(@Param("query") String query,
                            @Param("type") String type,
                            @Param("projectScope") String projectScope,
                            @Param("taskFilterMode") String taskFilterMode,
                            @Param("taskId") Long taskId,
                            @Param("folderFilterMode") String folderFilterMode,
                            @Param("folderName") String folderName,
                            Pageable pageable);
```

- [ ] **Step 5: Wire folder resolution and filtering into `MemoryService`**

In `src/main/java/ru/iuribabalin/memorymcp/service/MemoryService.java`:

1. Add `import ru.iuribabalin.memorymcp.repository.FolderRepository;` and `import ru.iuribabalin.memorymcp.entity.Folder;` and `import java.util.Objects;`.
2. Add a `FolderRepository folderRepository` field, set via the constructor (add it as the last constructor parameter).
3. In `save(...)`, after `node.setFilePath(request.filePath());`, add:
```java
        node.setFolder(resolveFolder(request.projectScope(), request.taskKey(), request.folder()));
```
4. Add this private method (near `resolveTaskFilter`):
```java
    private Folder resolveFolder(String projectScope, String taskKey, String folderName) {
        if (folderName == null) {
            return null;
        }
        Folder folder = folderRepository.findByName(folderName)
                .orElseThrow(() -> new FolderNotFoundException(folderName));
        String folderTaskKey = folder.getTask() != null ? folder.getTask().getTaskKey() : null;
        if (!Objects.equals(folder.getProjectScope(), projectScope) || !Objects.equals(folderTaskKey, taskKey)) {
            throw new IllegalArgumentException(
                    "Folder '%s' belongs to a different project/task scope".formatted(folderName));
        }
        return folder;
    }

    /** No folder given -> browsing the root (folder IS NULL), matching a file-explorer model. */
    private FolderFilter resolveFolderFilter(String folderName) {
        return folderName != null ? new FolderFilter("IN", folderName) : new FolderFilter("ROOT", null);
    }

    private record FolderFilter(String mode, String name) {
    }
```
5. Replace the `list` method with:
```java
    @Transactional(readOnly = true)
    public List<MemoryEntrySummary> list(MemoryNode.Type type, String projectScope, String taskKey, String folderName, int limit, int offset) {
        int pageSize = limit > 0 ? limit : 50;
        int page = pageSize > 0 ? offset / pageSize : 0;
        Pageable pageable = PageRequest.of(page, pageSize);
        TaskFilter taskFilter = resolveTaskFilter(projectScope, taskKey);
        FolderFilter folderFilter = resolveFolderFilter(folderName);
        return nodeRepository.listByFilters(type, projectScope, taskFilter.mode(), taskFilter.taskId(),
                        folderFilter.mode(), folderFilter.name(), pageable).stream()
                .map(this::toSummary)
                .toList();
    }
```
6. Replace the `search` method with:
```java
    @Transactional(readOnly = true)
    public List<MemoryEntrySummary> search(String query, MemoryNode.Type type, String projectScope, String taskKey, String folderName, int limit) {
        int pageSize = limit > 0 ? limit : 20;
        String typeName = type != null ? type.name() : null;
        TaskFilter taskFilter = resolveTaskFilter(projectScope, taskKey);
        FolderFilter folderFilter = resolveFolderFilter(folderName);
        return nodeRepository.search(query, typeName, projectScope, taskFilter.mode(), taskFilter.taskId(),
                        folderFilter.mode(), folderFilter.name(), PageRequest.of(0, pageSize)).stream()
                .map(this::toSummary)
                .toList();
    }
```
7. In `graph(...)`, change the `nodeRepository.listByFilters(...)` call to pass `"NONE"` and `null` for the two new folder params (graph shows everything in scope, folder or not):
```java
        List<MemoryNode> nodes = nodeRepository.listByFilters(type, projectScope, taskFilter.mode(), taskFilter.taskId(), "NONE", null, Pageable.unpaged());
```
8. In `toSummary(...)` and `toDetail(...)`, add `node.getFolder() != null ? node.getFolder().getName() : null` as the new `folder` argument (positioned to match the DTOs' new field order — right after `taskKey`).

- [ ] **Step 6: Add `folder` to `memory_save`/`memory_list`/`memory_search` in `MemoryMcpTools`**

In `src/main/java/ru/iuribabalin/memorymcp/mcp/MemoryMcpTools.java`:

1. In `memorySave`, add a new `@McpToolParam` right after `taskKey` (before `filePath`):
```java
            @McpToolParam(description = "Name of an existing folder (see folder_create/folder_list) to file this entry under; omit to save it at the root of its project/task scope", required = false) String folder,
```
and update the call to build `SaveMemoryRequest` to `new SaveMemoryRequest(name, type, description, content, projectScope, taskKey, folder, filePath, createdBy)`.

2. In `memoryList`, add a new `@McpToolParam` right after `taskKey` (before `limit`):
```java
            @McpToolParam(description = "Folder name to list entries directly inside; omit to list entries at the root of this project/task scope (folders' contents are hidden from the root listing)", required = false) String folder,
```
and update the call to `memoryService.list(type, projectScope, taskKey, folder, limit == null ? 50 : limit, offset == null ? 0 : offset)`.

3. In `memorySearch`, add a new `@McpToolParam` right after `taskKey` (before `limit`):
```java
            @McpToolParam(description = "Folder name to restrict the search to; omit to search only entries at the root of this project/task scope", required = false) String folder,
```
and update the call to `memoryService.search(query, type, projectScope, taskKey, folder, limit == null ? 20 : limit)`.

- [ ] **Step 7: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.MemoryServiceTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/SaveMemoryRequest.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/MemoryEntrySummary.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/MemoryEntryDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/MemoryNodeRepository.java \
        src/main/java/ru/iuribabalin/memorymcp/service/MemoryService.java \
        src/main/java/ru/iuribabalin/memorymcp/mcp/MemoryMcpTools.java \
        src/test/java/ru/iuribabalin/memorymcp/service/MemoryServiceTest.java
git commit -m "feat: make memory_save/list/search folder-aware"
```

---

### Task 15: REST API — `FolderViewController` + folder-aware `/api/memory`

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/ui/FolderViewController.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/MemoryViewController.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/ui/FolderViewControllerTest.java`

**Interfaces:**
- Consumes: `FolderService` (Task 12), `MemoryService.list`/`search` with `folderName` (Task 14).
- Produces: `GET /api/folders?projectScope=&taskKey=&parent=` → `List<FolderSummary>`; `GET /api/folders/{name}` → `FolderSummary`; `GET /api/memory` and `GET /api/memory/search` gain an optional `folder` query param.

- [ ] **Step 1: Write the failing test**

```java
package ru.iuribabalin.memorymcp.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.service.FolderService;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FolderViewController.class)
class FolderViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FolderService folderService;

    @Test
    void listsTopLevelFoldersForAProject() throws Exception {
        FolderSummary folder = new FolderSummary("docs", "desc", "memory-mcp", null, null, "Tester", Instant.now());
        when(folderService.listChildren("memory-mcp", null, null)).thenReturn(List.of(folder));

        mockMvc.perform(get("/api/folders").param("projectScope", "memory-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("docs"));
    }

    @Test
    void getsAFolderByName() throws Exception {
        FolderSummary folder = new FolderSummary("docs", "desc", "memory-mcp", null, null, "Tester", Instant.now());
        when(folderService.get("docs")).thenReturn(folder);

        mockMvc.perform(get("/api/folders/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("desc"));
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.ui.FolderViewControllerTest"`
Expected: FAIL — `FolderViewController` doesn't exist yet.

- [ ] **Step 3: Create the controller**

```java
package ru.iuribabalin.memorymcp.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.iuribabalin.memorymcp.dto.FolderSummary;
import ru.iuribabalin.memorymcp.service.FolderService;

import java.util.List;

@RestController
public class FolderViewController {

    private final FolderService folderService;

    public FolderViewController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/api/folders")
    public List<FolderSummary> list(
            @RequestParam String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String parent) {
        return folderService.listChildren(projectScope, taskKey, parent);
    }

    @GetMapping("/api/folders/{name}")
    public FolderSummary get(@PathVariable String name) {
        return folderService.get(name);
    }
}
```

- [ ] **Step 4: Add the `folder` param to `MemoryViewController`**

Replace `src/main/java/ru/iuribabalin/memorymcp/ui/MemoryViewController.java`'s `list` and `search` methods with:

```java
    @GetMapping("/api/memory")
    public List<MemoryEntrySummary> list(
            @RequestParam(required = false) MemoryNode.Type type,
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        return memoryService.list(type, projectScope, taskKey, folder, limit, offset);
    }
```

```java
    @GetMapping("/api/memory/search")
    public List<MemoryEntrySummary> search(
            @RequestParam String q,
            @RequestParam(required = false) MemoryNode.Type type,
            @RequestParam(required = false) String projectScope,
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return memoryService.search(q, type, projectScope, taskKey, folder, limit);
    }
```

- [ ] **Step 5: Run the test, confirm it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.ui.FolderViewControllerTest"`
Expected: PASS

- [ ] **Step 6: Compile the whole project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (confirms every caller of the now-changed `MemoryService.list`/`search` signatures was updated in Task 14)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/ui/FolderViewController.java \
        src/main/java/ru/iuribabalin/memorymcp/ui/MemoryViewController.java \
        src/test/java/ru/iuribabalin/memorymcp/ui/FolderViewControllerTest.java
git commit -m "feat: expose folder browsing over REST"
```

---

### Task 16: Frontend folder browsing UI

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/api/client.ts`
- Modify: `ui/src/lib/links.ts`
- Create: `ui/src/components/FolderRow.vue`
- Create: `ui/src/views/FolderView.vue`
- Modify: `ui/src/router/index.ts`
- Modify: `ui/src/views/ProjectView.vue`
- Modify: `ui/src/views/TaskView.vue`

**Interfaces:**
- Consumes: `GET /api/folders`, `GET /api/folders/{name}`, folder-aware `GET /api/memory` (Task 15).
- Produces: `FolderSummary` type; `fetchFolders(projectScope, taskKey?, parentFolder?)`, `fetchFolder(name)`; `fetchEntries` gains an optional 4th `folder` parameter; `folderLocation(projectScope, name)`; route `{ name: 'folder', path: '/p/:project/f/:folder' }` (already covered by the existing `/p/**` prefix in `SpaForwardController` — no backend route change needed).

- [ ] **Step 1: Add types**

Append to `ui/src/api/types.ts`:

```ts
export interface FolderSummary {
  name: string
  description: string
  projectScope: string
  taskKey: string | null
  parentFolder: string | null
  createdBy: string | null
  updatedAt: string
}
```

Add `folder?: string | null` as a new field on the existing `MemoryEntrySummary` interface (optional, so the inline entry objects built in `StatsView.vue`'s "Most accessed" list don't need updating).

- [ ] **Step 2: Add client functions**

Add `FolderSummary` to the `import type { ... } from './types'` block in `ui/src/api/client.ts`. Replace `fetchEntries` with:

```ts
export function fetchEntries(
  projectScope: string,
  taskKey?: string | null,
  type?: MemoryType | null,
  folder?: string | null,
): Promise<MemoryEntrySummary[]> {
  return getJson('/api/memory', {
    projectScope,
    taskKey: taskKey ?? undefined,
    type: type ?? undefined,
    folder: folder ?? undefined,
    limit: 200,
  })
}
```

Append at the end of the file:

```ts
export function fetchFolders(
  projectScope: string,
  taskKey?: string | null,
  parentFolder?: string | null,
): Promise<FolderSummary[]> {
  return getJson('/api/folders', {
    projectScope,
    taskKey: taskKey ?? undefined,
    parent: parentFolder ?? undefined,
  })
}

export function fetchFolder(name: string): Promise<FolderSummary> {
  return getJson(`/api/folders/${encodeURIComponent(name)}`)
}
```

- [ ] **Step 3: Add `folderLocation`**

Append to `ui/src/lib/links.ts`:

```ts
export function folderLocation(projectScope: string, name: string): RouteLocationRaw {
  return { name: 'folder', params: { project: projectScope, folder: name } }
}
```

- [ ] **Step 4: Create `FolderRow.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'

import type { FolderSummary } from '@/api/types'
import { folderLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'

const props = defineProps<{ folder: FolderSummary; projectScope: string }>()

const to = computed(() => folderLocation(props.projectScope, props.folder.name))
</script>

<template>
  <RouterLink
    :to="to"
    class="group flex items-center gap-3.5 rounded-xl border border-border bg-panel px-3.5 py-2.5 transition duration-150 hover:-translate-y-px hover:border-accent/40 hover:shadow-panel"
  >
    <span
      class="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-elevated text-muted transition group-hover:border-accent/30 group-hover:text-accent"
    >
      <AppIcon name="folder" class="size-4" />
    </span>

    <span class="min-w-0 flex-1">
      <span class="truncate text-[13.5px] font-medium text-content">{{ folder.name }}</span>
      <span class="mt-0.5 block truncate text-[12.5px] text-muted">{{ folder.description }}</span>
    </span>

    <AppIcon
      name="chevron"
      class="size-3.5 shrink-0 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent"
    />
  </RouterLink>
</template>
```

- [ ] **Step 5: Create `FolderView.vue`**

```vue
<script setup lang="ts">
import { computed, toRef } from 'vue'

import { fetchEntries, fetchFolder, fetchFolders } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryRow from '@/components/EntryRow.vue'
import ErrorState from '@/components/ErrorState.vue'
import FolderRow from '@/components/FolderRow.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { folderLocation, projectLocation, taskLocation } from '@/lib/links'

const props = defineProps<{ project: string; folder: string }>()

const project = toRef(props, 'project')
const folderName = toRef(props, 'folder')

const { data: folder, error: folderError, loading: folderLoading } = useAsyncData(
  () => fetchFolder(folderName.value),
  [folderName],
)

const { data: subfolders, loading: subfoldersLoading } = useAsyncData(
  () => fetchFolders(project.value, folder.value?.taskKey ?? null, folderName.value),
  [folderName, folder],
)

const { data: entries, error: entriesError, loading: entriesLoading, reload } = useAsyncData(
  () => fetchEntries(project.value, folder.value?.taskKey ?? null, null, folderName.value),
  [folderName, folder],
)

const backLink = computed(() => {
  if (!folder.value) {
    return projectLocation(project.value)
  }
  if (folder.value.parentFolder) {
    return folderLocation(project.value, folder.value.parentFolder)
  }
  return folder.value.taskKey ? taskLocation(project.value, folder.value.taskKey) : projectLocation(project.value)
})
</script>

<template>
  <div>
    <ErrorState v-if="folderError" :message="folderError" />
    <template v-else>
      <PageHeader eyebrow="Folder" :title="folder?.name ?? folderName" :subtitle="folder?.description">
        <template #actions>
          <RouterLink
            :to="backLink"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="arrowLeft" class="size-4" />
            Back
          </RouterLink>
        </template>
      </PageHeader>

      <SkeletonRows v-if="folderLoading" :rows="1" class="mb-6" />

      <section class="mb-9">
        <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
          <AppIcon name="folder" class="size-4 text-faint" />
          Folders
        </h2>
        <SkeletonRows v-if="subfoldersLoading" :rows="2" />
        <EmptyState v-else-if="!subfolders?.length" icon="folder" title="No subfolders" />
        <div v-else class="space-y-2">
          <FolderRow v-for="sub in subfolders" :key="sub.name" :folder="sub" :project-scope="project" />
        </div>
      </section>

      <section>
        <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
          <AppIcon name="document" class="size-4 text-faint" />
          Entries
        </h2>
        <ErrorState v-if="entriesError" :message="entriesError" @retry="reload" />
        <SkeletonRows v-else-if="entriesLoading" :rows="3" />
        <EmptyState v-else-if="!entries?.length" icon="document" title="No entries in this folder yet" />
        <div v-else class="space-y-2">
          <EntryRow v-for="entry in entries" :key="entry.name" :entry="entry" />
        </div>
      </section>
    </template>
  </div>
</template>
```

- [ ] **Step 6: Add the route**

In `ui/src/router/index.ts`, add this route after the `project-graph` route (grouping it with the other `/p/:project/*` routes):

```ts
    {
      path: '/p/:project/f/:folder',
      name: 'folder',
      component: () => import('@/views/FolderView.vue'),
      props: true,
    },
```

- [ ] **Step 7: Add a "Folders" section to `ProjectView.vue`**

In `ui/src/views/ProjectView.vue`:
1. Task 10 already changed the api/client import line to `import { fetchEntries, fetchStats, fetchTasks } from '@/api/client'` — add `fetchFolders` to that line too, giving `import { fetchEntries, fetchFolders, fetchStats, fetchTasks } from '@/api/client'`.
2. Add `import FolderRow from '@/components/FolderRow.vue'` alongside the other component imports.
3. Add this alongside the existing `common`/`tasks` fetches:
```ts
const { data: folders } = useAsyncData(() => fetchFolders(project.value, null, null), [project])
```
4. Insert this new `<section>` right before the existing `<section class="mb-9">` that renders "Common" (only shown once folders exist, since most projects won't have any):
```vue
    <section v-if="folders?.length" class="mb-9">
      <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="folder" class="size-4 text-faint" />
        Folders
        <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
          {{ folders.length }}
        </span>
      </h2>
      <div class="space-y-2">
        <FolderRow v-for="folder in folders" :key="folder.name" :folder="folder" :project-scope="project" />
      </div>
    </section>
```

- [ ] **Step 8: Add the same section to `TaskView.vue`**

In `ui/src/views/TaskView.vue`:
1. Add `fetchFolders` to the existing `import { fetchEntries, fetchTasks } from '@/api/client'` line.
2. Add `import FolderRow from '@/components/FolderRow.vue'` alongside the other component imports.
3. Add this alongside the existing `entries`/`tasks` fetches:
```ts
const { data: folders } = useAsyncData(() => fetchFolders(project.value, taskKey.value, null), [project, taskKey])
```
4. Insert this new `<section>` right before the existing entries list (after the closing `</PageHeader>` tag, before the `<ErrorState v-if="error" ...>` line):
```vue
    <section v-if="folders?.length" class="mb-9">
      <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="folder" class="size-4 text-faint" />
        Folders
        <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
          {{ folders.length }}
        </span>
      </h2>
      <div class="space-y-2">
        <FolderRow v-for="folder in folders" :key="folder.name" :folder="folder" :project-scope="project" />
      </div>
    </section>
```

- [ ] **Step 9: Type-check**

Run: `cd ui && npm run type-check`
Expected: no errors

- [ ] **Step 10: Manual check**

Run: `cd ui && npm run dev`. Since folders can currently only be created via the `folder_create` MCP tool, use `psql` or a quick MCP call to create a top-level folder and a nested one under a test project, then confirm: the project page shows a "Folders" section with a folder icon, clicking it opens `FolderView` showing its description, its subfolder, and a "Back" link; entries saved into the folder no longer appear in the project's root "Common" list.

- [ ] **Step 11: Commit**

```bash
git add ui/src/api/types.ts ui/src/api/client.ts ui/src/lib/links.ts \
        ui/src/components/FolderRow.vue ui/src/views/FolderView.vue ui/src/router/index.ts \
        ui/src/views/ProjectView.vue ui/src/views/TaskView.vue
git commit -m "feat(ui): browse folders alongside entries, distinct icon per kind"
```

---

## Self-Review Notes

- **Spec coverage:** dark-first stance — Global Constraints + Task 7/8 rely on existing tokens, no toggle-behavior change needed (already dark-first-capable). Constellation signature — Task 6/7/8. Type-color-as-navigation — Task 7 (EntryRow halo) + Task 8 (byType chart, direct-labeled per the CVD finding). Density — Task 7. `UsageEvent` schema/capture/aggregation/API — Tasks 1-4. Stats UI (global + per-project + header pulse) — Tasks 8-10. Nested folders (arbitrary depth, dedicated `folder_create`/`folder_list` tools, root-vs-inside-folder browsing, distinct file/folder icons in the UI) — Tasks 11-16. Testing plan — every backend task ships a test; frontend tasks use `type-check` + manual verification per the Global Constraints note (no frontend test framework exists in this repo).
- **Out of scope confirmed:** no font-family changes, no touching the in-flight PDF-export WIP, no forcing dark mode by default, no UI-driven folder creation (Claude-only, like tasks), no folder rename/move/delete tool (only `folder_create`'s idempotent upsert-by-name and description/parent update) — none of the tasks above touch fonts, `MemoryExportService`/`PdfRenderer`/`ReportView.vue`/`V4__*.sql`, `useTheme.ts`'s default-resolution logic, or add folder mutation UI/tools beyond create.
- **Type/signature consistency checked:** `UsageEvent.Action` enum values used identically in Task 2 (recorder calls) and Task 3 (`topAccessedEntries`'s `action in ('GET','RELATED')`); Task 13 extends the same enum with `FOLDER_CREATE`. `StatsOverview` field names match between Task 3 (Java) and Task 5 (TypeScript) and are consumed identically in Tasks 8-10. `EntryRow`'s new `accessCount` prop (Task 7) is the same name/type used in Task 8's `StatsView.vue`. `SaveMemoryRequest`'s field order (`..., taskKey, folder, filePath, createdBy`) set in Task 14 Step 3 is used consistently by Task 14 Step 6's `MemoryMcpTools` call and by `MemoryServiceTest`. `FolderSummary` field names/order (Task 12) are used identically by `FolderMcpTools` (Task 13), `FolderViewController` (Task 15), and the frontend `FolderSummary` type + `FolderRow.vue`/`FolderView.vue` (Task 16). `MemoryService.list`/`search`'s new `folderName` parameter (Task 14) is threaded through by every caller changed in the same task, and Task 15 Step 6 (`./gradlew compileJava`) is the checkpoint that catches any caller missed.
