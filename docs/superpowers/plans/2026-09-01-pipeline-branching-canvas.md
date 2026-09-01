# Pipeline Branching + Canvas Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a hand-authored pipeline branch — a step's Claude-reported `outcome` picks which step runs next — authored on a drag-and-connect canvas instead of the current ordered-list builder.

**Architecture:** A new `pipeline_step_routes` table (outcome → target step, nullable target = end of run) sits alongside the existing linear `pipeline_steps`; a step with zero routes keeps today's `orderIndex + 1` behavior unchanged. `PipelineRun.currentStepOrderIndex` replaces "next = orderIndex+1" as the pointer the execution skill follows. The dashboard builder becomes a `@vue-flow/core` canvas; MCP tools gain one new parameter (`outcome`) and one new response field (`currentStepOrderIndex`).

**Tech Stack:** Spring Boot 4.1 (Java 25), Spring Data JPA/Hibernate, PostgreSQL 17 + Flyway, Spring AI MCP Server annotations, Vue 3 (`<script setup>` + TS) + Vue Router 5 + Tailwind 4, `@vue-flow/core` (new).

**Spec:** `docs/superpowers/specs/2026-09-01-pipeline-branching-canvas-design.md`

## Global Constraints

- No Lombok — plain JPA entities with explicit getters/setters (id has no setter).
- FK relations to another aggregate root are raw `Long xId` columns, not `@ManyToOne`.
- Timestamps are `java.time.Instant`, set manually via `Instant.now()`.
- Enums are Postgres `VARCHAR + CHECK IN (...)`, mapped with `@Enumerated(EnumType.STRING)`.
- Flyway migrations: `BIGSERIAL PRIMARY KEY`, snake_case columns, explicit `VARCHAR(n)` lengths, named indexes `idx_<table>_<column>`, named unique constraints `ux_<table>_<column>`, `TIMESTAMPTZ NOT NULL DEFAULT now()`. `ddl-auto: validate` is on — migrations must match entity mappings exactly.
- Domain exceptions are minimal message-only `RuntimeException` subclasses; every new one gets a matching `@ExceptionHandler` in `ui/ApiExceptionHandler.java`.
- MCP tool classes: `@Component`, constructor injection, `@McpTool` per method, `@McpToolParam` per parameter, mutating tools record a `UsageEvent`.
- REST controllers in `ui/` have no class-level `@RequestMapping` — full paths on each method.
- Frontend: this plan adds the project's first new dependency, `@vue-flow/core` — everything else stays dependency-free (no axios, no test framework; verification is `npm run type-check`).
- Backend tests run against real local Postgres (`docker compose up -d`, port 5433) via `@SpringBootTest @Transactional` (service layer) or Mockito `standaloneSetup` MockMvc slice tests (controller layer) — no Testcontainers, no mocks at the service-test layer.
- The next free Flyway migration number is **V14** (`V13__widen_usage_events_action.sql` already exists).

---

## Task 1: Route data model, graph validation, and CRUD persistence

**Files:**
- Create: `src/main/resources/db/migration/V14__add_pipeline_step_routes.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStepRoute.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepRouteRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineInvalidGraphException.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java` (compile fix only, `StepRequest` gains fields)

**Interfaces:**
- Produces: `PipelineStepRoute` entity (`getStepId`, `getOutcomeKey`, `getTargetStepId`), `PipelineStepRouteRepository.findByStepId(Long)`, `.findByStepIdIn(List<Long>)`, `.deleteByStepIdIn(List<Long>)`. `PipelineUpsertRequest.StepRequest` gains `double positionX, double positionY, List<RouteRequest> routes` where `RouteRequest(String outcomeKey, Integer targetStepIndex)` — `targetStepIndex` is a 0-based index into the *same request's* `steps` list, `null` = end of run. `PipelineDetail.PipelineStepView` gains `double positionX, double positionY, List<RouteView> routes` where `RouteView(String outcomeKey, Integer targetStepOrderIndex)`. `PipelineExecutionDetail.StepView` gains `List<RouteView> routes` where `RouteView(String outcomeKey, Integer targetStepOrderIndex, String targetStepTitle)`. `PipelineInvalidGraphException` (message-only). `PipelineService.resolve(String)` unchanged (package-visible) — Task 2 calls it plus the new `PipelineStepRouteRepository`; Task 3 only touches `PipelineMcpTools`, not `PipelineExecutionDetail` (already done here).
- Consumes: nothing from other tasks.

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE pipeline_steps
    ADD COLUMN position_x DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN position_y DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE TABLE pipeline_step_routes (
    id             BIGSERIAL PRIMARY KEY,
    step_id        BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    outcome_key    VARCHAR(100),
    target_step_id BIGINT REFERENCES pipeline_steps (id) ON DELETE CASCADE
);

CREATE INDEX idx_pipeline_step_routes_step_id ON pipeline_step_routes (step_id);
CREATE UNIQUE INDEX ux_pipeline_step_routes_step_outcome
    ON pipeline_step_routes (step_id, outcome_key);
```

- [ ] **Step 2: Write the `PipelineStepRoute` entity**

```java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_step_routes")
public class PipelineStepRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "outcome_key", length = 100)
    private String outcomeKey;

    @Column(name = "target_step_id")
    private Long targetStepId;

    public Long getId() {
        return id;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public String getOutcomeKey() {
        return outcomeKey;
    }

    public void setOutcomeKey(String outcomeKey) {
        this.outcomeKey = outcomeKey;
    }

    public Long getTargetStepId() {
        return targetStepId;
    }

    public void setTargetStepId(Long targetStepId) {
        this.targetStepId = targetStepId;
    }
}
```

- [ ] **Step 3: Write the repository**

```java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;

import java.util.List;

public interface PipelineStepRouteRepository extends JpaRepository<PipelineStepRoute, Long> {
    List<PipelineStepRoute> findByStepId(Long stepId);
    List<PipelineStepRoute> findByStepIdIn(List<Long> stepIds);
    void deleteByStepIdIn(List<Long> stepIds);
}
```

- [ ] **Step 4: Write the exception**

```java
package ru.iuribabalin.memorymcp.service;

public class PipelineInvalidGraphException extends RuntimeException {
    public PipelineInvalidGraphException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Add `positionX`/`positionY` to `PipelineStep`**

In `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`, add two fields after `referenceAssetId` and their accessors:

```java
    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;
```

```java
    public double getPositionX() {
        return positionX;
    }

    public void setPositionX(double positionX) {
        this.positionX = positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public void setPositionY(double positionY) {
        this.positionY = positionY;
    }
```

- [ ] **Step 6: Update `PipelineUpsertRequest`**

Replace the whole file:

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.util.List;

public record PipelineUpsertRequest(
        String slug,
        String name,
        String description,
        String projectScope,
        List<ParameterRequest> parameters,
        List<StepRequest> steps
) {
    public record ParameterRequest(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepRequest(
            String title, PipelineStep.ContentType contentType, String promptText,
            Long assetId, Long referenceAssetId, double positionX, double positionY,
            List<RouteRequest> routes) {

        public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
        }
    }
}
```

- [ ] **Step 7: Update `PipelineDetail`**

Replace the whole file:

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineDetail(
        Long id,
        String slug,
        String name,
        String description,
        String projectScope,
        List<PipelineParameterView> parameters,
        List<PipelineStepView> steps,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public record PipelineParameterView(
            Long id, String name, String label, PipelineParameter.Type type,
            boolean required, String defaultValue, int orderIndex) {
    }

    public record PipelineStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            String promptText, Long assetId, Long referenceAssetId,
            double positionX, double positionY, List<RouteView> routes) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex) {
        }
    }
}
```

- [ ] **Step 8: Write the failing validation + persistence tests**

Append to `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java` (see Step 11 for the required import/`sampleRequest` fixes first — write those fixes *before* running this step, otherwise the file won't compile at all):

```java
    @Test
    void savesAndReadsBackPositionsAndRoutes() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-1", "Branching pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check it",
                                null, null, 10.0, 20.0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", null))),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 230.0, 20.0, List.of())));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView checkStep = detail.steps().get(0);
        assertThat(checkStep.positionX()).isEqualTo(10.0);
        assertThat(checkStep.positionY()).isEqualTo(20.0);
        assertThat(checkStep.routes()).extracting(PipelineDetail.PipelineStepView.RouteView::outcomeKey)
                .containsExactlyInAnyOrder("pass", "fail");
        assertThat(checkStep.routes()).filteredOn(r -> "pass".equals(r.outcomeKey()))
                .extracting(PipelineDetail.PipelineStepView.RouteView::targetStepOrderIndex)
                .containsExactly(1);
    }

    @Test
    void rejectsAPipelineWithACycle() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-2", "Cyclic", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, 0)))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsAPipelineWithTwoStartingSteps() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-3", "Two roots", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null)))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsTwoDefaultRoutesOnTheSameStep() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-4", "Two defaults", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void allowsAnUnwiredIsolatedStepAsAWarningOnlyNotAHardError() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "branch-5", "Isolated draft step", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(new PipelineUpsertRequest.StepRequest.RouteRequest(null, null))),
                        new PipelineUpsertRequest.StepRequest("Not wired yet", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 300, 300, List.of())));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        assertThat(detail.steps()).hasSize(2);
    }

    @Test
    void aPipelineWithNoRoutesAnywhereSkipsGraphValidation() {
        pipelineService.create(sampleRequest("branch-6"), "Tester");

        PipelineDetail detail = pipelineService.get("branch-6");

        assertThat(detail.steps()).allMatch(s -> s.routes().isEmpty());
    }
```

- [ ] **Step 9: Run the tests to verify the new ones fail**

Run: `docker compose up -d && ./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: FAIL to compile — `positionX()`/`routes()` don't exist on `PipelineDetail.PipelineStepView` yet (Step 7 hasn't landed in the running build) or `PipelineInvalidGraphException` doesn't exist (Step 4 pending) — apply steps 1-7 fully first, then this run should fail only on the new assertions (e.g. `rejectsAPipelineWithACycle` not actually throwing yet), not on compilation.

- [ ] **Step 10: Update `PipelineExecutionDetail`**

Replace the whole file (this is what `pipeline_get` returns over MCP — Task 3 adds the `outcome` reporting side, this task only adds the read-side `routes` field so `PipelineService` has somewhere to put the data):

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineParameter;

import java.util.List;

public record PipelineExecutionDetail(
        String slug, String name, String description,
        List<ParameterView> parameters, List<StepView> steps) {

    public record ParameterView(String name, String label, PipelineParameter.Type type, boolean required, String defaultValue) {
    }

    public record StepView(int orderIndex, String title, String instructionText, String referenceText, List<RouteView> routes) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
}
```

This changes `PipelineExecutionDetail.StepView`'s constructor from 4 args to 5 — no test constructs a non-empty `steps` list for it directly today (`PipelineMcpToolsTest` only ever passes `List.of()`), so nothing else needs fixing for this one.

- [ ] **Step 11: Rewrite `PipelineService`**

Replace the whole file:

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineDetail;
import ru.iuribabalin.memorymcp.dto.PipelineExecutionDetail;
import ru.iuribabalin.memorymcp.dto.PipelineSummary;
import ru.iuribabalin.memorymcp.dto.PipelineUpsertRequest;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineParameter;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;
import ru.iuribabalin.memorymcp.repository.PipelineParameterRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRouteRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineParameterRepository pipelineParameterRepository;
    private final PipelineStepRepository pipelineStepRepository;
    private final PipelineStepRouteRepository pipelineStepRouteRepository;
    private final PipelineAssetService pipelineAssetService;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository,
                            PipelineStepRouteRepository pipelineStepRouteRepository,
                            PipelineAssetService pipelineAssetService,
                            ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineAssetService = pipelineAssetService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PipelineSummary> list(String projectScope) {
        return pipelineRepository.findByProjectScopeOrderByUpdatedAtDesc(projectScope).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineDetail get(String slug) {
        return toDetail(resolve(slug));
    }

    @Transactional
    public PipelineDetail create(PipelineUpsertRequest request, String createdBy) {
        validateSteps(request.steps());
        validateGraph(request.steps());
        if (pipelineRepository.findBySlug(request.slug()).isPresent()) {
            throw new PipelineSlugTakenException(request.slug());
        }
        Instant now = Instant.now();
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug(request.slug());
        pipeline.setCreatedBy(createdBy);
        pipeline.setCreatedAt(now);
        applyFields(pipeline, request, now);
        pipeline = pipelineRepository.save(pipeline);
        replaceParametersAndSteps(pipeline.getId(), request);
        return toDetail(resolve(request.slug()));
    }

    @Transactional
    public PipelineDetail update(String slug, PipelineUpsertRequest request) {
        validateSteps(request.steps());
        validateGraph(request.steps());
        Pipeline pipeline = resolve(slug);
        applyFields(pipeline, request, Instant.now());
        pipelineRepository.save(pipeline);
        replaceParametersAndSteps(pipeline.getId(), request);
        return toDetail(resolve(slug));
    }

    @Transactional
    public boolean delete(String slug) {
        return pipelineRepository.findBySlug(slug)
                .map(pipeline -> {
                    pipelineParameterRepository.deleteByPipelineId(pipeline.getId());
                    pipelineStepRouteRepository.deleteByStepIdIn(stepIdsOf(pipeline.getId()));
                    pipelineStepRepository.deleteByPipelineId(pipeline.getId());
                    pipelineRepository.delete(pipeline);
                    return true;
                })
                .orElse(false);
    }

    Pipeline resolve(String slug) {
        return pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
    }

    @Transactional(readOnly = true)
    public PipelineExecutionDetail getForExecution(String slug) {
        Pipeline pipeline = resolve(slug);
        List<PipelineExecutionDetail.ParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineExecutionDetail.ParameterView(p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue()))
                .toList();
        List<PipelineStep> steps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Map<Long, PipelineStep> stepsById = steps.stream().collect(Collectors.toMap(PipelineStep::getId, s -> s));
        List<PipelineExecutionDetail.StepView> stepViews = steps.stream()
                .map(step -> new PipelineExecutionDetail.StepView(
                        step.getOrderIndex(),
                        step.getTitle(),
                        resolveInstructionText(step),
                        step.getReferenceAssetId() != null ? pipelineAssetService.readAsText(step.getReferenceAssetId()) : null,
                        pipelineStepRouteRepository.findByStepId(step.getId()).stream()
                                .map(r -> new PipelineExecutionDetail.StepView.RouteView(
                                        r.getOutcomeKey(),
                                        r.getTargetStepId() != null ? stepsById.get(r.getTargetStepId()).getOrderIndex() : null,
                                        r.getTargetStepId() != null ? stepsById.get(r.getTargetStepId()).getTitle() : null))
                                .toList()))
                .toList();
        return new PipelineExecutionDetail(pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(), parameters, stepViews);
    }

    @Transactional(readOnly = true)
    public void validateParameters(String slug, String parametersJson) {
        Pipeline pipeline = resolve(slug);
        List<PipelineParameter> parameters = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Set<String> provided = new HashSet<>();
        if (parametersJson != null && !parametersJson.isBlank()) {
            JsonNode node;
            try {
                node = objectMapper.readTree(parametersJson);
            } catch (Exception ex) {
                throw new PipelineInvalidParametersException("parametersJson is not valid JSON: " + ex.getMessage());
            }
            provided.addAll(node.propertyNames());
        }
        List<String> missing = parameters.stream()
                .filter(PipelineParameter::isRequired)
                .map(PipelineParameter::getName)
                .filter(name -> !provided.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new PipelineInvalidParametersException("Missing required parameters: " + String.join(", ", missing));
        }
    }

    private void validateSteps(List<PipelineUpsertRequest.StepRequest> steps) {
        for (PipelineUpsertRequest.StepRequest step : steps) {
            if (step.contentType() == PipelineStep.ContentType.MD_FILE && step.assetId() == null) {
                throw new PipelineInvalidParametersException(
                        "Step '" + step.title() + "' is type MD_FILE but has no uploaded file — upload a .md file before saving");
            }
        }
    }

    /**
     * A step with zero routes falls back to legacy orderIndex+1 chaining, but only pipeline-wide:
     * if ANY step anywhere has an explicit route, every step's edges come ONLY from its own
     * explicit routes (an empty list means "dead end / not yet wired", never an implicit chain to
     * whatever a later-created step happens to occupy at orderIndex+1).
     */
    private void validateGraph(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        if (n == 0 || steps.stream().allMatch(s -> s.routes().isEmpty())) {
            return;
        }
        int[] inDegree = new int[n];
        int[] outDegree = new int[n];
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            List<PipelineUpsertRequest.StepRequest.RouteRequest> routes = steps.get(i).routes();
            long defaultRoutes = routes.stream().filter(r -> r.outcomeKey() == null).count();
            if (defaultRoutes > 1) {
                throw new PipelineInvalidGraphException(
                        "Step '" + steps.get(i).title() + "' has more than one default route");
            }
            for (PipelineUpsertRequest.StepRequest.RouteRequest route : routes) {
                outDegree[i]++;
                if (route.targetStepIndex() != null) {
                    int target = route.targetStepIndex();
                    inDegree[target]++;
                    adjacency.get(i).add(target);
                }
            }
        }
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean isolated = inDegree[i] == 0 && outDegree[i] == 0;
            if (!isolated && inDegree[i] == 0) {
                roots.add(steps.get(i).title());
            }
        }
        if (roots.size() != 1) {
            throw new PipelineInvalidGraphException(roots.isEmpty()
                    ? "Pipeline has no starting step — every step has an incoming route"
                    : "Pipeline has more than one starting step: " + String.join(", ", roots));
        }
        int[] remaining = inDegree.clone();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (remaining[i] == 0) {
                queue.add(i);
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            visited++;
            for (int next : adjacency.get(current)) {
                if (--remaining[next] == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < n) {
            throw new PipelineInvalidGraphException("Pipeline has a cycle in its step routes");
        }
    }

    private String resolveInstructionText(PipelineStep step) {
        if (step.getContentType() != PipelineStep.ContentType.MD_FILE) {
            return step.getPromptText();
        }
        if (step.getAssetId() == null) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.getTitle() + "' is type MD_FILE but has no uploaded file");
        }
        return pipelineAssetService.readAsText(step.getAssetId());
    }

    private void applyFields(Pipeline pipeline, PipelineUpsertRequest request, Instant now) {
        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setProjectScope(request.projectScope());
        pipeline.setUpdatedAt(now);
    }

    private List<Long> stepIdsOf(Long pipelineId) {
        return pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipelineId).stream()
                .map(PipelineStep::getId)
                .toList();
    }

    private void replaceParametersAndSteps(Long pipelineId, PipelineUpsertRequest request) {
        pipelineParameterRepository.deleteByPipelineId(pipelineId);
        int paramIndex = 0;
        for (PipelineUpsertRequest.ParameterRequest parameterRequest : request.parameters()) {
            PipelineParameter parameter = new PipelineParameter();
            parameter.setPipelineId(pipelineId);
            parameter.setName(parameterRequest.name());
            parameter.setLabel(parameterRequest.label());
            parameter.setType(parameterRequest.type());
            parameter.setRequired(parameterRequest.required());
            parameter.setDefaultValue(parameterRequest.defaultValue());
            parameter.setOrderIndex(paramIndex++);
            pipelineParameterRepository.save(parameter);
        }

        pipelineStepRouteRepository.deleteByStepIdIn(stepIdsOf(pipelineId));
        pipelineStepRepository.deleteByPipelineId(pipelineId);

        List<PipelineStep> savedSteps = new ArrayList<>();
        int stepIndex = 0;
        for (PipelineUpsertRequest.StepRequest stepRequest : request.steps()) {
            PipelineStep step = new PipelineStep();
            step.setPipelineId(pipelineId);
            step.setOrderIndex(stepIndex++);
            step.setTitle(stepRequest.title());
            step.setContentType(stepRequest.contentType());
            step.setPromptText(stepRequest.promptText());
            step.setAssetId(stepRequest.assetId());
            step.setReferenceAssetId(stepRequest.referenceAssetId());
            step.setPositionX(stepRequest.positionX());
            step.setPositionY(stepRequest.positionY());
            savedSteps.add(pipelineStepRepository.save(step));
        }
        for (int i = 0; i < request.steps().size(); i++) {
            for (PipelineUpsertRequest.StepRequest.RouteRequest routeRequest : request.steps().get(i).routes()) {
                PipelineStepRoute route = new PipelineStepRoute();
                route.setStepId(savedSteps.get(i).getId());
                route.setOutcomeKey(routeRequest.outcomeKey());
                route.setTargetStepId(routeRequest.targetStepIndex() != null
                        ? savedSteps.get(routeRequest.targetStepIndex()).getId()
                        : null);
                pipelineStepRouteRepository.save(route);
            }
        }
    }

    private PipelineSummary toSummary(Pipeline pipeline) {
        int parameterCount = pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        int stepCount = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).size();
        return new PipelineSummary(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameterCount, stepCount, pipeline.getCreatedBy(), pipeline.getUpdatedAt());
    }

    private PipelineDetail toDetail(Pipeline pipeline) {
        List<PipelineDetail.PipelineParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineDetail.PipelineParameterView(p.getId(), p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue(), p.getOrderIndex()))
                .toList();
        List<PipelineStep> pipelineSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Map<Long, Integer> orderIndexById = pipelineSteps.stream()
                .collect(Collectors.toMap(PipelineStep::getId, PipelineStep::getOrderIndex));
        List<PipelineDetail.PipelineStepView> steps = pipelineSteps.stream()
                .map(s -> new PipelineDetail.PipelineStepView(
                        s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(),
                        s.getAssetId(), s.getReferenceAssetId(), s.getPositionX(), s.getPositionY(),
                        pipelineStepRouteRepository.findByStepId(s.getId()).stream()
                                .map(r -> new PipelineDetail.PipelineStepView.RouteView(
                                        r.getOutcomeKey(),
                                        r.getTargetStepId() != null ? orderIndexById.get(r.getTargetStepId()) : null))
                                .toList()))
                .toList();
        return new PipelineDetail(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameters, steps, pipeline.getCreatedBy(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
    }
}
```

- [ ] **Step 12: Fix existing tests to compile against the new `StepRequest`/`PipelineDetail` shapes**

In `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`, update `sampleRequest` and the three other `StepRequest` constructions to pass `0, 0, List.of()` for the three new trailing parameters:

```java
    private PipelineUpsertRequest sampleRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Config diff", "Diffs configs against prod", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder to check", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}} against prod", null, null, 0, 0, List.of()),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save the report to memory", null, null, 0, 0, List.of())));
    }
```

```java
        PipelineUpsertRequest updated = new PipelineUpsertRequest(
                "config-diff-3", "Config diff v2", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Only step", PipelineStep.ContentType.PROMPT, "do it", null, null, 0, 0, List.of())));
```

```java
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "config-diff-6", "Config diff", "Diffs configs against prod", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("Missing file", PipelineStep.ContentType.MD_FILE, null, null, null, 0, 0, List.of())));
```

Add the import used by the new tests from Step 8 (should already be present): `PipelineInvalidGraphException` lives in the same `service` package as the test, so no import line is needed.

In `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`, update `createSamplePipeline`:

```java
    private void createSamplePipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Config diff", "desc", "pipeline-run-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}}", null, null, 0, 0, List.of()),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save it", null, null, 0, 0, List.of()))
        ), "Tester");
    }
```

- [ ] **Step 13: Add the exception handler entry**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`, add the import:

```java
import ru.iuribabalin.memorymcp.service.PipelineInvalidGraphException;
```

and the handler method (next to `handlePipelineInvalidParameters`):

```java
    @ExceptionHandler(PipelineInvalidGraphException.class)
    public ResponseEntity<Map<String, String>> handlePipelineInvalidGraph(PipelineInvalidGraphException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
```

- [ ] **Step 14: Run the tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest" --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest" --tests "ru.iuribabalin.memorymcp.ui.PipelineControllerTest"`
Expected: PASS (all tests, including the 5 new ones from Step 8)

- [ ] **Step 15: Commit**

```bash
git add src/main/resources/db/migration/V14__add_pipeline_step_routes.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStepRoute.java \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepRouteRepository.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineInvalidGraphException.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java
git commit -m "feat: add pipeline step routes with graph validation (cycles, single root, one default route)"
```

---

## Task 2: Execution engine — `currentStepOrderIndex` and outcome resolution

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunInvalidOutcomeException.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRun.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/ui/PipelineRunControllerTest.java` (compile fix)

**Interfaces:**
- Consumes: `PipelineStepRouteRepository` (Task 1).
- Produces: `PipelineRunDetail` gains `Integer currentStepOrderIndex` (placed right before `steps`). `PipelineRunService.updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note, String outcome)` — signature now takes 5 args, not 4. `PipelineRunInvalidOutcomeException(String outcome, List<String> validOutcomes)`. Task 3 (`PipelineMcpTools`) calls the new `updateStep` signature and reads `currentStepOrderIndex` off the response.

- [ ] **Step 1: Add `currentStepOrderIndex` to `PipelineRun`**

In `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRun.java`, add after `startedBy`:

```java
    @Column(name = "current_step_order_index")
    private Integer currentStepOrderIndex;
```

```java
    public Integer getCurrentStepOrderIndex() {
        return currentStepOrderIndex;
    }

    public void setCurrentStepOrderIndex(Integer currentStepOrderIndex) {
        this.currentStepOrderIndex = currentStepOrderIndex;
    }
```

- [ ] **Step 2: Add the migration column**

Append to the *same* `V14__add_pipeline_step_routes.sql` file from Task 1 (one migration per logical change-set is preferred, but this column is small enough and part of the same feature — keep the file name, just add this statement at the end):

```sql
ALTER TABLE pipeline_runs ADD COLUMN current_step_order_index INTEGER;
```

- [ ] **Step 3: Update `PipelineRunDetail`**

Replace the whole file:

```java
package ru.iuribabalin.memorymcp.dto;

import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;
import java.util.List;

public record PipelineRunDetail(
        Long id, Long pipelineId, String pipelineSlug, PipelineRun.Status status,
        String parametersJson, Instant startedAt, Instant finishedAt, String startedBy,
        Integer currentStepOrderIndex, List<PipelineRunStepView> steps) {

    public record PipelineRunStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt) {
    }
}
```

- [ ] **Step 4: Write the exception**

```java
package ru.iuribabalin.memorymcp.service;

import java.util.List;

public class PipelineRunInvalidOutcomeException extends RuntimeException {
    public PipelineRunInvalidOutcomeException(String outcome, List<String> validOutcomes) {
        super("Outcome '" + outcome + "' is not valid for this step — expected one of: " + String.join(", ", validOutcomes));
    }
}
```

- [ ] **Step 5: Write the failing tests**

Append to `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`:

```java
    private void createBranchingPipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Branching", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check", PipelineStep.ContentType.PROMPT, "check",
                                null, null, 0, 0, List.of(
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("pass", 1),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("fail", 2))),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy",
                                null, null, 0, 0, List.of()),
                        new PipelineUpsertRequest.StepRequest("Rollback", PipelineStep.ContentType.PROMPT, "rollback",
                                null, null, 0, 0, List.of()))
        ), "Tester");
    }

    @Test
    void startPointsCurrentStepAtTheRoot() {
        createSamplePipeline("run-test-7");

        PipelineRunDetail run = pipelineRunService.start("run-test-7", "{\"folder\":\"src\"}", "Tester");

        assertThat(run.currentStepOrderIndex()).isZero();
    }

    @Test
    void doneWithAMatchingOutcomeAdvancesToTheRoutedStep() {
        createBranchingPipeline("run-test-8");
        PipelineRunDetail run = pipelineRunService.start("run-test-8", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "pass");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneWithADifferentOutcomeAdvancesToItsOwnBranch() {
        createBranchingPipeline("run-test-9");
        PipelineRunDetail run = pipelineRunService.start("run-test-9", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "broke", "fail");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void doneWithAnUnknownOutcomeThrows() {
        createBranchingPipeline("run-test-10");
        PipelineRunDetail run = pipelineRunService.start("run-test-10", "{}", "Tester");

        assertThatThrownBy(() -> pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "not-a-real-outcome"))
                .isInstanceOf(PipelineRunInvalidOutcomeException.class)
                .hasMessageContaining("pass")
                .hasMessageContaining("fail");
    }

    @Test
    void doneOnAStepWithNoRoutesInALegacyPipelineFallsBackToOrderIndexPlusOne() {
        createSamplePipeline("run-test-11");
        PipelineRunDetail run = pipelineRunService.start("run-test-11", "{\"folder\":\"src\"}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null);

        assertThat(updated.currentStepOrderIndex()).isEqualTo(1);
    }

    @Test
    void doneOnTheLastStepOfABranchEndsTheRun() {
        createBranchingPipeline("run-test-12");
        PipelineRunDetail run = pipelineRunService.start("run-test-12", "{}", "Tester");
        pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", "pass");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, "deployed", null);

        assertThat(updated.currentStepOrderIndex()).isNull();
    }
```

Also fix the two existing calls in this file that now need a trailing `outcome` argument — `updateStepMovesItToDoneAndStampsFinishedAt`:

```java
        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "diffed fine", null);
```

- [ ] **Step 6: Fix `PipelineRunControllerTest` to compile**

```java
        when(pipelineRunService.get(1L)).thenReturn(new PipelineRunDetail(
                1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, "Tester", 0, List.of()));
```

- [ ] **Step 7: Add the exception handler entry**

In `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`, add the import:

```java
import ru.iuribabalin.memorymcp.service.PipelineRunInvalidOutcomeException;
```

and the handler method (next to `handlePipelineRunStepNotFound`):

```java
    @ExceptionHandler(PipelineRunInvalidOutcomeException.class)
    public ResponseEntity<Map<String, String>> handlePipelineRunInvalidOutcome(PipelineRunInvalidOutcomeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
```

- [ ] **Step 8: Run the tests to verify they fail**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest"`
Expected: FAIL to compile — `PipelineRunService.updateStep` doesn't accept 5 args yet.

- [ ] **Step 9: Rewrite `PipelineRunService`**

Replace the whole file:

```java
package ru.iuribabalin.memorymcp.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.dto.PipelineRunDetail;
import ru.iuribabalin.memorymcp.dto.PipelineRunSummary;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepRoute;
import ru.iuribabalin.memorymcp.repository.PipelineRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunRepository;
import ru.iuribabalin.memorymcp.repository.PipelineRunStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRepository;
import ru.iuribabalin.memorymcp.repository.PipelineStepRouteRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PipelineRunService {

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunStepRepository pipelineRunStepRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineStepRepository pipelineStepRepository;
    private final PipelineStepRouteRepository pipelineStepRouteRepository;

    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
                               PipelineRunStepRepository pipelineRunStepRepository,
                               PipelineRepository pipelineRepository,
                               PipelineStepRepository pipelineStepRepository,
                               PipelineStepRouteRepository pipelineStepRouteRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunStepRepository = pipelineRunStepRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
    }

    @Transactional
    public PipelineRunDetail start(String slug, String parametersJson, String startedBy) {
        Pipeline pipeline = pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
        List<PipelineStep> steps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Instant now = Instant.now();
        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipeline.getId());
        run.setStatus(PipelineRun.Status.RUNNING);
        run.setParametersJson(parametersJson);
        run.setStartedAt(now);
        run.setStartedBy(startedBy);
        run.setCurrentStepOrderIndex(resolveRootOrderIndex(steps));
        run = pipelineRunRepository.save(run);
        for (PipelineStep step : steps) {
            PipelineRunStep runStep = new PipelineRunStep();
            runStep.setRunId(run.getId());
            runStep.setPipelineStepId(step.getId());
            runStep.setOrderIndex(step.getOrderIndex());
            runStep.setTitle(step.getTitle());
            runStep.setContentType(step.getContentType());
            runStep.setStatus(PipelineRunStep.Status.PENDING);
            pipelineRunStepRepository.save(runStep);
        }
        return toDetail(run, pipeline.getSlug());
    }

    @Transactional
    public PipelineRunDetail updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note, String outcome) {
        PipelineRun run = resolve(runId);
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(runId, orderIndex)
                .orElseThrow(() -> new PipelineRunStepNotFoundException(runId, orderIndex));
        Instant now = Instant.now();
        if (runStep.getStartedAt() == null && status == PipelineRunStep.Status.RUNNING) {
            runStep.setStartedAt(now);
        }
        if (status == PipelineRunStep.Status.DONE || status == PipelineRunStep.Status.FAILED
                || status == PipelineRunStep.Status.SKIPPED) {
            runStep.setFinishedAt(now);
        }
        runStep.setStatus(status);
        runStep.setNote(note);
        pipelineRunStepRepository.save(runStep);

        if (status == PipelineRunStep.Status.DONE && runStep.getPipelineStepId() != null) {
            run.setCurrentStepOrderIndex(resolveNextOrderIndex(run.getPipelineId(), runStep.getPipelineStepId(), orderIndex, outcome));
            pipelineRunRepository.save(run);
        }
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional
    public PipelineRunDetail complete(Long runId, PipelineRun.Status status) {
        PipelineRun run = resolve(runId);
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        pipelineRunRepository.save(run);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional(readOnly = true)
    public PipelineRunDetail get(Long runId) {
        PipelineRun run = resolve(runId);
        return toDetail(run, pipelineSlugOf(run));
    }

    @Transactional(readOnly = true)
    public List<PipelineRunSummary> listByPipeline(String slug) {
        Pipeline pipeline = pipelineRepository.findBySlug(slug)
                .orElseThrow(() -> new PipelineNotFoundException(slug));
        return pipelineRunRepository.findByPipelineIdOrderByStartedAtDesc(pipeline.getId()).stream()
                .map(run -> toSummary(run, pipeline.getSlug()))
                .toList();
    }

    private PipelineRun resolve(Long runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new PipelineRunNotFoundException(runId));
    }

    private String pipelineSlugOf(PipelineRun run) {
        return pipelineRepository.findById(run.getPipelineId()).map(Pipeline::getSlug).orElse(null);
    }

    private Integer resolveRootOrderIndex(List<PipelineStep> steps) {
        if (steps.isEmpty()) {
            return null;
        }
        List<PipelineStepRoute> allRoutes = pipelineStepRouteRepository.findByStepIdIn(
                steps.stream().map(PipelineStep::getId).toList());
        if (allRoutes.isEmpty()) {
            return steps.stream().mapToInt(PipelineStep::getOrderIndex).min().orElseThrow();
        }
        Set<Long> targeted = allRoutes.stream().map(PipelineStepRoute::getTargetStepId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> withOutgoing = allRoutes.stream().map(PipelineStepRoute::getStepId).collect(Collectors.toSet());
        return steps.stream()
                .filter(s -> !targeted.contains(s.getId()))
                .filter(s -> withOutgoing.contains(s.getId()))
                .mapToInt(PipelineStep::getOrderIndex)
                .min()
                .orElseThrow(() -> new IllegalStateException("Pipeline has no starting step"));
    }

    /**
     * A step with no routes falls back to legacy orderIndex+1 chaining only when the WHOLE
     * pipeline has no routes anywhere (a pipeline never touched by branching). Inside a pipeline
     * that does use branching, a step with no explicit routes is a dead end (end of that path) -
     * never an implicit chain to whatever step happens to sit at orderIndex+1.
     */
    private Integer resolveNextOrderIndex(Long pipelineId, Long finishedStepId, int finishedOrderIndex, String outcome) {
        List<PipelineStep> allSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipelineId);
        List<PipelineStepRoute> allRoutes = pipelineStepRouteRepository.findByStepIdIn(
                allSteps.stream().map(PipelineStep::getId).toList());
        if (allRoutes.isEmpty()) {
            return allSteps.stream()
                    .map(PipelineStep::getOrderIndex)
                    .filter(i -> i == finishedOrderIndex + 1)
                    .findFirst()
                    .orElse(null);
        }
        List<PipelineStepRoute> stepRoutes = allRoutes.stream()
                .filter(r -> r.getStepId().equals(finishedStepId))
                .toList();
        if (stepRoutes.isEmpty()) {
            return null;
        }
        Optional<PipelineStepRoute> matched = stepRoutes.stream()
                .filter(r -> r.getOutcomeKey() != null && r.getOutcomeKey().equals(outcome))
                .findFirst();
        if (matched.isEmpty()) {
            matched = stepRoutes.stream().filter(r -> r.getOutcomeKey() == null).findFirst();
        }
        if (matched.isEmpty()) {
            List<String> validOutcomes = stepRoutes.stream()
                    .map(PipelineStepRoute::getOutcomeKey)
                    .filter(Objects::nonNull)
                    .toList();
            throw new PipelineRunInvalidOutcomeException(outcome, validOutcomes);
        }
        Long targetStepId = matched.get().getTargetStepId();
        if (targetStepId == null) {
            return null;
        }
        return allSteps.stream()
                .filter(s -> s.getId().equals(targetStepId))
                .map(PipelineStep::getOrderIndex)
                .findFirst()
                .orElse(null);
    }

    private PipelineRunSummary toSummary(PipelineRun run, String pipelineSlug) {
        return new PipelineRunSummary(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getStartedBy());
    }

    private PipelineRunDetail toDetail(PipelineRun run, String pipelineSlug) {
        List<PipelineRunDetail.PipelineRunStepView> steps = pipelineRunStepRepository
                .findByRunIdOrderByOrderIndexAsc(run.getId()).stream()
                .map(s -> new PipelineRunDetail.PipelineRunStepView(s.getId(), s.getOrderIndex(), s.getTitle(),
                        s.getContentType(), s.getStatus(), s.getNote(), s.getStartedAt(), s.getFinishedAt()))
                .toList();
        return new PipelineRunDetail(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getParametersJson(), run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(),
                run.getCurrentStepOrderIndex(), steps);
    }
}
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest" --tests "ru.iuribabalin.memorymcp.ui.PipelineRunControllerTest"`
Expected: PASS (all tests)

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V14__add_pipeline_step_routes.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRun.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunInvalidOutcomeException.java \
        src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java \
        src/test/java/ru/iuribabalin/memorymcp/ui/PipelineRunControllerTest.java
git commit -m "feat: resolve outcome-driven branching in pipeline run execution"
```

---

## Task 3: MCP tool surface — `outcome` param on `pipeline_run_step_update`

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java`
- Modify: `src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java`

**Interfaces:**
- Consumes: `PipelineRunService.updateStep(Long, int, PipelineRunStep.Status, String, String)` (Task 2), `PipelineRunDetail.currentStepOrderIndex()` (Task 2), `PipelineExecutionDetail.StepView.routes()` (Task 1 — already wired through `PipelineService.getForExecution`, nothing to do here beyond it flowing through unchanged).
- Produces: nothing new for later tasks — this is the outermost layer.

- [ ] **Step 1: Fix `PipelineMcpToolsTest` to compile against the new `PipelineRunDetail` shape**

Both `PipelineRunDetail` constructions in this file need a `currentStepOrderIndex` argument inserted before the trailing `List.of()`:

```java
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, 0, List.of());
```

(apply the same change to both occurrences — one in `runStartValidatesParametersAndRecordsUsage`, one in `runStepUpdateDelegatesAndRecordsUsage`).

- [ ] **Step 2: Write the failing test**

Replace `runStepUpdateDelegatesAndRecordsUsage` with a version that passes and asserts on `outcome`:

```java
    @Test
    void runStepUpdateDelegatesAndRecordsUsage() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, 1, List.of());
        when(pipelineRunService.updateStep(1L, 0, PipelineRunStep.Status.DONE, "ok", "success")).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStepUpdate(1L, 0, PipelineRunStep.Status.DONE, "ok", "success");

        assertThat(result).isEqualTo(runDetail);
        assertThat(result.currentStepOrderIndex()).isEqualTo(1);
        verify(usageEventRecorder).record(ru.iuribabalin.memorymcp.entity.UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, "1", null, null, null);
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest"`
Expected: FAIL to compile — `pipelineRunStepUpdate` doesn't accept 5 args yet.

- [ ] **Step 4: Add the `outcome` parameter to `pipelineRunStepUpdate`**

In `src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java`, replace the `pipeline_run_step_update` method:

```java
    @McpTool(name = "pipeline_run_step_update",
            description = "Report the outcome of one pipeline run step after doing its work: RUNNING when you start " +
                    "it, then DONE or FAILED when you finish, or SKIPPED if the user told you to skip it. Include a " +
                    "short note describing what you did or why it failed. If pipeline_get showed this step has more " +
                    "than one route, pass 'outcome' matching one of its route keys when reporting DONE so the run " +
                    "advances down the right branch. Check the returned currentStepOrderIndex for what to do next - " +
                    "null means every path from here has ended, call pipeline_run_complete. On FAILED, stop and ask " +
                    "the user how to proceed before calling this again - do not silently continue to the next step.")
    public PipelineRunDetail pipelineRunStepUpdate(
            @McpToolParam(description = "The run id, from pipeline_run_start", required = true) Long runId,
            @McpToolParam(description = "0-based index of the step in the run's step list", required = true) Integer orderIndex,
            @McpToolParam(description = "New status: RUNNING, DONE, FAILED, or SKIPPED", required = true) PipelineRunStep.Status status,
            @McpToolParam(description = "Short summary of what happened for this step", required = false) String note,
            @McpToolParam(description = "This step's outcome - only needed when pipeline_get showed the step has more than one route; must exactly match one of that step's outcome keys", required = false) String outcome) {
        requireEnabled();
        PipelineRunDetail run = pipelineRunService.updateStep(runId, orderIndex, status, note, outcome);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, String.valueOf(runId), null, null, null);
        return run;
    }
```

Also update the `pipeline_get` tool's description (still zero-arg change, just the docstring) so Claude knows to look at `routes`:

```java
    @McpTool(name = "pipeline_get",
            description = "Fetch a pipeline's full definition - its ordered steps and parameters. Uploaded .md step " +
                    "content and any optional reference attachment are inlined as plain text, no separate download " +
                    "needed. A step's 'routes' list (if non-empty) shows which outcome values lead to which step - " +
                    "read it before starting a run so you know what to pass as 'outcome' on " +
                    "pipeline_run_step_update. Call pipeline_get before pipeline_run_start so you know what " +
                    "parameters to ask the user for.")
    public PipelineExecutionDetail pipelineGet(
            @McpToolParam(description = "The pipeline's slug, from pipeline_list", required = true) String slug) {
        requireEnabled();
        return pipelineService.getForExecution(slug);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest"`
Expected: PASS (all tests)

- [ ] **Step 6: Run the full backend test suite**

Run: `./gradlew test`
Expected: PASS — this is the first point where every backend file touched across Tasks 1-3 compiles and runs together.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java \
        src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java
git commit -m "feat: report and route on step outcome via the pipeline_run_step_update MCP tool"
```

---

## Task 4: Frontend — data contract + canvas builder

**Files:**
- Modify: `ui/package.json` (add `@vue-flow/core`)
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/views/PipelineBuilderView.vue`
- Modify: `ui/src/styles/main.css` (shared `.pipeline-node*` classes, reused by Task 5)

**Interfaces:**
- Consumes: `fetchPipeline`, `createPipeline`, `updatePipeline`, `uploadPipelineAsset` (unchanged signatures from `ui/src/api/client.ts` — only the JSON *shape* changes, which types.ts now declares).
- Produces: `PipelineUpsertStep` (with `positionX`, `positionY`, `routes: PipelineUpsertRoute[]`) and `PipelineStepView`/`PipelineRunDetail` (with `currentStepOrderIndex`) — Task 5's read-only views consume these same types, already updated here.

This task is one unit (not split further) because `types.ts` and `PipelineBuilderView.vue` must land together — `npm run type-check` cannot pass with only one of them changed, so there is no meaningful intermediate commit between them.

- [ ] **Step 1: Add the dependency**

```bash
cd ui && npm install @vue-flow/core
```

- [ ] **Step 2: Update `ui/src/api/types.ts`**

Add two new interfaces (near the other `Pipeline*` types) and update three existing ones. First, add after `PipelineParameterView`:

```typescript
export interface PipelineRouteView {
  outcomeKey: string | null
  targetStepOrderIndex: number | null
}

export interface PipelineUpsertRoute {
  outcomeKey: string | null
  targetStepIndex: number | null
}
```

Replace `PipelineStepView`:

```typescript
export interface PipelineStepView {
  id: number
  orderIndex: number
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
  positionX: number
  positionY: number
  routes: PipelineRouteView[]
}
```

Replace `PipelineUpsertStep`:

```typescript
export interface PipelineUpsertStep {
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
  positionX: number
  positionY: number
  routes: PipelineUpsertRoute[]
}
```

Replace `PipelineRunDetail`:

```typescript
export interface PipelineRunDetail {
  id: number
  pipelineId: number
  pipelineSlug: string
  status: PipelineRunStatus
  parametersJson: string | null
  startedAt: string
  finishedAt: string | null
  startedBy: string | null
  currentStepOrderIndex: number | null
  steps: PipelineRunStepView[]
}
```

- [ ] **Step 3: Run type-check to confirm the expected failure**

Run: `cd ui && npm run type-check`
Expected: FAIL — `PipelineBuilderView.vue`'s `addStep`/`loadForEdit` build `PipelineUpsertStep` objects missing `positionX`/`positionY`/`routes`.

- [ ] **Step 4: Rewrite `PipelineBuilderView.vue`**

Replace the whole file:

```vue
<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow, type EdgeMouseEvent, type NodeDragEvent, type NodeMouseEvent } from '@vue-flow/core'
import { computed, ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'

import { createPipeline, fetchPipeline, updatePipeline, uploadPipelineAsset } from '@/api/client'
import type {
  PipelineParameterType,
  PipelineStepContentType,
  PipelineUpsertParameter,
  PipelineUpsertRoute,
  PipelineUpsertStep,
} from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'

const props = defineProps<{ project: string; slug?: string }>()
const project = toRef(props, 'project')
const editingSlug = toRef(props, 'slug')
const isEditing = computed(() => !!editingSlug.value)

const router = useRouter()

const slug = ref('')
const name = ref('')
const description = ref('')
const parameters = ref<PipelineUpsertParameter[]>([])
const steps = ref<PipelineUpsertStep[]>([])
const saving = ref(false)
const saveError = ref<string | null>(null)

const END_NODE_ID = 'end'
const endPosition = ref({ x: 480, y: 120 })

const selectedStepIndex = ref<number | null>(null)
const selectedEdge = ref<{ stepIndex: number; routeIndex: number } | null>(null)

function edgeId(stepIndex: number, route: PipelineUpsertRoute): string {
  const targetId = route.targetStepIndex === null ? END_NODE_ID : String(route.targetStepIndex)
  return `${stepIndex}-${route.outcomeKey ?? 'default'}-${targetId}`
}

async function loadForEdit() {
  if (!editingSlug.value) return
  const pipeline = await fetchPipeline(editingSlug.value)
  slug.value = pipeline.slug
  name.value = pipeline.name
  description.value = pipeline.description ?? ''
  parameters.value = pipeline.parameters.map((p) => ({
    name: p.name,
    label: p.label,
    type: p.type,
    required: p.required,
    defaultValue: p.defaultValue,
  }))
  steps.value = pipeline.steps.map((s) => ({
    title: s.title,
    contentType: s.contentType,
    promptText: s.promptText,
    assetId: s.assetId,
    referenceAssetId: s.referenceAssetId,
    positionX: s.positionX,
    positionY: s.positionY,
    routes: s.routes.map((r) => ({ outcomeKey: r.outcomeKey, targetStepIndex: r.targetStepOrderIndex })),
  }))
  applyLegacyAutoLayoutIfNeeded()
}

function applyLegacyAutoLayoutIfNeeded() {
  const allAtOrigin = steps.value.length > 0 && steps.value.every((s) => s.positionX === 0 && s.positionY === 0)
  if (!allAtOrigin) return
  steps.value.forEach((step, index) => {
    step.positionX = index * 220
    step.positionY = 0
  })
  endPosition.value = { x: steps.value.length * 220, y: 0 }
}

watch(editingSlug, loadForEdit, { immediate: true })

function addParameter() {
  parameters.value.push({ name: '', label: '', type: 'STRING' as PipelineParameterType, required: false, defaultValue: null })
}

function removeParameter(index: number) {
  parameters.value.splice(index, 1)
}

function addStep() {
  const offset = steps.value.length * 220
  steps.value.push({
    title: '',
    contentType: 'PROMPT' as PipelineStepContentType,
    promptText: '',
    assetId: null,
    referenceAssetId: null,
    positionX: offset,
    positionY: 200,
    routes: [],
  })
}

function removeStep(index: number) {
  steps.value.splice(index, 1)
  // Routes referencing this step by index are now stale (indices shifted) - drop anything
  // pointing at or past the removed step so a save can't silently rewire to the wrong node.
  steps.value.forEach((step) => {
    step.routes = step.routes.filter((r) => r.targetStepIndex === null || r.targetStepIndex < steps.value.length)
  })
  selectedStepIndex.value = null
  selectedEdge.value = null
}

async function onMdFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const asset = await uploadPipelineAsset(file)
  steps.value[index].assetId = asset.id
}

async function onReferenceFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const asset = await uploadPipelineAsset(file)
  steps.value[index].referenceAssetId = asset.id
}

const flowNodes = computed(() => [
  ...steps.value.map((step, index) => ({
    id: String(index),
    position: { x: step.positionX, y: step.positionY },
    label: step.title || `Шаг ${index + 1}`,
    class: selectedStepIndex.value === index ? 'pipeline-node pipeline-node-selected' : 'pipeline-node',
  })),
  {
    id: END_NODE_ID,
    position: endPosition.value,
    label: 'Конец рана',
    class: 'pipeline-node pipeline-node-end',
  },
])

const flowEdges = computed(() =>
  steps.value.flatMap((step, index) =>
    step.routes.map((route) => ({
      id: edgeId(index, route),
      source: String(index),
      target: route.targetStepIndex === null ? END_NODE_ID : String(route.targetStepIndex),
      label: route.outcomeKey ?? '(по умолчанию)',
    })),
  ),
)

// Best-effort UI hint only - the authoritative check is PipelineService's graph validation on
// save. A step is flagged if something else in the pipeline branches at all, but nothing routes
// into this step and it isn't the first one - i.e. it looks like an unwired, mid-edit node.
const unreachableStepIndexes = computed(() => {
  const anyRoutes = steps.value.some((s) => s.routes.length > 0)
  if (!anyRoutes) return new Set<number>()
  const targeted = new Set<number>()
  steps.value.forEach((step) => {
    step.routes.forEach((route) => {
      if (route.targetStepIndex !== null) targeted.add(route.targetStepIndex)
    })
  })
  const result = new Set<number>()
  steps.value.forEach((_, index) => {
    if (index !== 0 && !targeted.has(index)) result.add(index)
  })
  return result
})

function onNodeDragStop({ node }: NodeDragEvent) {
  if (node.id === END_NODE_ID) {
    endPosition.value = { x: node.position.x, y: node.position.y }
    return
  }
  const index = Number(node.id)
  steps.value[index].positionX = node.position.x
  steps.value[index].positionY = node.position.y
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedEdge.value = null
  selectedStepIndex.value = node.id === END_NODE_ID ? null : Number(node.id)
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedStepIndex.value = null
  const stepIndex = Number(edge.source)
  const routeIndex = steps.value[stepIndex].routes.findIndex((r) => edgeId(stepIndex, r) === edge.id)
  selectedEdge.value = routeIndex >= 0 ? { stepIndex, routeIndex } : null
}

function onConnect(connection: { source: string; target: string }) {
  const sourceIndex = Number(connection.source)
  const targetIndex = connection.target === END_NODE_ID ? null : Number(connection.target)
  steps.value[sourceIndex].routes.push({ outcomeKey: null, targetStepIndex: targetIndex })
  selectedStepIndex.value = null
  selectedEdge.value = { stepIndex: sourceIndex, routeIndex: steps.value[sourceIndex].routes.length - 1 }
}

function removeSelectedRoute() {
  if (!selectedEdge.value) return
  steps.value[selectedEdge.value.stepIndex].routes.splice(selectedEdge.value.routeIndex, 1)
  selectedEdge.value = null
}

const selectedStep = computed(() => (selectedStepIndex.value !== null ? steps.value[selectedStepIndex.value] : null))
const selectedRoute = computed(() =>
  selectedEdge.value ? steps.value[selectedEdge.value.stepIndex].routes[selectedEdge.value.routeIndex] : null,
)

async function save() {
  saving.value = true
  saveError.value = null
  try {
    const request = {
      slug: slug.value,
      name: name.value,
      description: description.value || null,
      projectScope: project.value,
      parameters: parameters.value,
      steps: steps.value,
    }
    const result = isEditing.value ? await updatePipeline(editingSlug.value!, request) : await createPipeline(request)
    await router.push({ name: 'pipeline', params: { project: project.value, slug: result.slug } })
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Pipelines"
      :title="isEditing ? 'Редактирование пайплайна' : 'Новый пайплайн'"
    />

    <ErrorState v-if="saveError" :message="saveError" />

    <div class="space-y-6">
      <section class="rounded-2xl border border-border bg-panel p-5">
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Slug</label>
        <input
          v-model="slug"
          :disabled="isEditing"
          class="mb-4 w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content disabled:opacity-60"
          placeholder="config-diff"
        />
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Название</label>
        <input v-model="name" class="mb-4 w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content" />
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Описание</label>
        <textarea v-model="description" rows="2" class="w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content" />
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Параметры</h2>
          <button type="button" class="text-[12.5px] font-medium text-accent" @click="addParameter">+ Параметр</button>
        </div>
        <div v-for="(parameter, index) in parameters" :key="index" class="mb-3 flex items-center gap-2">
          <input v-model="parameter.name" placeholder="name" class="w-32 rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content" />
          <input v-model="parameter.label" placeholder="label" class="flex-1 rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content" />
          <select v-model="parameter.type" class="rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content">
            <option value="STRING">STRING</option>
            <option value="NUMBER">NUMBER</option>
            <option value="BOOLEAN">BOOLEAN</option>
          </select>
          <label class="flex items-center gap-1 text-[12px] text-muted">
            <input v-model="parameter.required" type="checkbox" /> required
          </label>
          <button type="button" class="text-faint hover:text-red-600" @click="removeParameter(index)">
            <AppIcon name="trash" class="size-4" />
          </button>
        </div>
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
          <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep">+ Шаг</button>
        </div>
        <p class="mb-3 text-[12px] text-faint">
          Перетащите узел, чтобы разместить его; потяните от одного узла к другому, чтобы создать маршрут.
          Клик по узлу или связи открывает панель редактирования справа.
        </p>
        <div class="flex gap-4">
          <div class="h-[420px] flex-1 overflow-hidden rounded-xl border border-border bg-elevated">
            <VueFlow
              :nodes="flowNodes"
              :edges="flowEdges"
              :nodes-connectable="true"
              fit-view-on-init
              @node-drag-stop="onNodeDragStop"
              @node-click="onNodeClick"
              @edge-click="onEdgeClick"
              @connect="onConnect"
            />
          </div>
          <aside class="w-72 shrink-0 rounded-xl border border-border bg-elevated p-4">
            <template v-if="selectedStep && selectedStepIndex !== null">
              <div class="mb-3 flex items-center justify-between">
                <span class="text-[12px] text-faint">Шаг #{{ selectedStepIndex + 1 }}</span>
                <button type="button" class="text-faint hover:text-red-600" @click="removeStep(selectedStepIndex)">
                  <AppIcon name="trash" class="size-4" />
                </button>
              </div>
              <span v-if="unreachableStepIndexes.has(selectedStepIndex)" class="mb-2 block text-[11.5px] text-amber-500">
                ⚠ Ни один маршрут не ведёт в этот шаг
              </span>
              <input
                v-model="selectedStep.title"
                placeholder="Название шага"
                class="mb-2 w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              />
              <div class="mb-2 flex gap-3 text-[12.5px] text-muted">
                <label class="flex items-center gap-1"><input v-model="selectedStep.contentType" type="radio" value="PROMPT" /> Prompt</label>
                <label class="flex items-center gap-1"><input v-model="selectedStep.contentType" type="radio" value="MD_FILE" /> .md файл</label>
              </div>
              <textarea
                v-if="selectedStep.contentType === 'PROMPT'"
                v-model="selectedStep.promptText"
                rows="4"
                placeholder="Инструкция для Claude — можно {{paramName}}"
                class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              />
              <div v-else class="text-[12.5px] text-muted">
                <input type="file" accept=".md" @change="onMdFileChosen(selectedStepIndex, $event)" />
                <span v-if="selectedStep.assetId" class="ml-2">Загружен: asset #{{ selectedStep.assetId }}</span>
              </div>
              <div class="mt-3 text-[12.5px] text-muted">
                <label class="mb-1 block">Ссылочный файл (необязательно):</label>
                <input type="file" @change="onReferenceFileChosen(selectedStepIndex, $event)" />
                <span v-if="selectedStep.referenceAssetId" class="ml-2">Загружен: asset #{{ selectedStep.referenceAssetId }}</span>
              </div>
            </template>
            <template v-else-if="selectedRoute">
              <div class="mb-3 flex items-center justify-between">
                <span class="text-[12px] text-faint">Маршрут</span>
                <button type="button" class="text-faint hover:text-red-600" @click="removeSelectedRoute">
                  <AppIcon name="trash" class="size-4" />
                </button>
              </div>
              <label class="mb-1 block text-[12.5px] font-medium text-muted">Ключ outcome</label>
              <input
                v-model="selectedRoute.outcomeKey"
                placeholder="пусто = маршрут по умолчанию"
                class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              />
              <p class="mt-2 text-[11.5px] text-faint">
                Claude должен вернуть это значение как outcome в pipeline_run_step_update, чтобы run пошёл по этой
                связи. Пустое значение — маршрут по умолчанию для этого шага (используется, если outcome не
                передан или не совпал ни с одним другим маршрутом).
              </p>
            </template>
            <p v-else class="text-[12.5px] text-faint">Выберите узел или связь на канвасе.</p>
          </aside>
        </div>
      </section>

      <button
        type="button"
        :disabled="saving"
        class="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover disabled:opacity-50"
        @click="save"
      >
        {{ saving ? 'Сохранение…' : 'Сохранить' }}
      </button>
    </div>
  </div>
</template>
```

- [ ] **Step 5: Add shared canvas node styles to `main.css`**

These classes are shared with Task 5's read-only graph views, which may render on a page the builder's own chunk was never loaded on — put them in the global stylesheet, not a component-local `<style>` block, so they're always available. Append to `ui/src/styles/main.css`:

```css

.pipeline-node {
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  background: var(--color-panel);
  color: var(--color-content);
  font-size: 12.5px;
  padding: 8px 12px;
}
.pipeline-node-selected {
  box-shadow: 0 0 0 2px var(--color-accent);
}
.pipeline-node-end {
  border-style: dashed;
  color: var(--color-faint);
}
.pipeline-node-done {
  border-color: var(--color-accent);
  opacity: 1;
}
.pipeline-node-failed {
  border-color: #dc2626;
}
.pipeline-node-running {
  border-color: var(--color-accent);
  box-shadow: 0 0 0 2px var(--color-accent-soft);
}
.pipeline-node-not-reached {
  opacity: 0.45;
}
```

- [ ] **Step 6: Run type-check**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 7: Manual smoke check**

Run: `cd ui && npm run dev` (with the backend running via `docker compose up -d` and `feature.pipelines.enabled` turned on in Settings). Open the pipeline builder, confirm: dragging a step node moves it, dragging from one node's handle to another creates a connection, clicking an edge shows the outcome-key editor in the right panel, clicking "Сохранить" round-trips through a reload without losing positions/routes. Stop the dev server when done (Ctrl-C) — this step has no automated assertion, it's the frontend equivalent of Step 6's `PASS`.

- [ ] **Step 8: Commit**

```bash
git add ui/package.json ui/package-lock.json ui/src/api/types.ts ui/src/views/PipelineBuilderView.vue ui/src/styles/main.css
git commit -m "feat(ui): rebuild the pipeline builder as a vue-flow drag-and-connect canvas"
```

---

## Task 5: Frontend — read-only graph rendering for pipeline detail and run views

**Files:**
- Modify: `ui/src/views/PipelineView.vue`
- Modify: `ui/src/views/PipelineRunView.vue`

**Interfaces:**
- Consumes: `PipelineStepView.routes`/`positionX`/`positionY` and `PipelineRunDetail.currentStepOrderIndex` (Task 4), `fetchPipeline`/`fetchPipelineRun` (unchanged client functions), the shared `.pipeline-node*` CSS classes (Task 4 Step 5).
- Produces: nothing consumed elsewhere — this is a leaf task.

A run's steps table (`pipeline_run_steps`) only snapshots `title`/`contentType`/`status`, not position or routes — Task 1/2 didn't add those columns there on purpose (the spec's "snapshot" reasoning is about *content* staying accurate after edits, not about re-deriving layout). `PipelineRunView.vue` therefore fetches the **current** pipeline definition for the graph's shape/positions/routes and overlays this run's per-step status by matching `orderIndex` — if the pipeline was edited after the run, the shape shown is the current one, not a historical snapshot; that's an accepted simplification, not a bug.

- [ ] **Step 1: Rewrite the "Шаги" section of `PipelineView.vue`**

Replace the whole file:

```vue
<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, ref, toRef } from 'vue'
import { useRouter } from 'vue-router'

import { deletePipeline, fetchPipeline, fetchPipelineRuns } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { projectLocation } from '@/lib/links'

const props = defineProps<{ project: string; slug: string }>()
const project = toRef(props, 'project')
const slug = toRef(props, 'slug')

const { data: pipeline, error, loading } = useAsyncData(() => fetchPipeline(slug.value), [slug])
const { data: runs, loading: runsLoading } = useAsyncData(() => fetchPipelineRuns(slug.value), [slug])

const router = useRouter()
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

async function confirmDelete() {
  deleting.value = true
  deleteError.value = null
  try {
    await deletePipeline(slug.value)
    await router.push({ name: 'pipelines', params: { project: project.value } })
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    deleting.value = false
    showDeleteConfirm.value = false
  }
}

const END_NODE_ID = 'end'

const flowNodes = computed(() => {
  if (!pipeline.value) return []
  const steps = pipeline.value.steps
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => s.positionX)) : 0
  return [
    ...steps.map((step) => ({
      id: String(step.orderIndex),
      position: { x: step.positionX, y: step.positionY },
      label: `${step.orderIndex + 1}. ${step.title}`,
      class: 'pipeline-node',
    })),
    { id: END_NODE_ID, position: { x: maxX + 240, y: 0 }, label: 'Конец рана', class: 'pipeline-node pipeline-node-end' },
  ]
})

const flowEdges = computed(() => {
  if (!pipeline.value) return []
  return pipeline.value.steps.flatMap((step) =>
    step.routes.map((route) => ({
      id: `${step.orderIndex}-${route.outcomeKey ?? 'default'}-${route.targetStepOrderIndex ?? END_NODE_ID}`,
      source: String(step.orderIndex),
      target: route.targetStepOrderIndex === null ? END_NODE_ID : String(route.targetStepOrderIndex),
      label: route.outcomeKey ?? '(по умолчанию)',
    })),
  )
})

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'Выполняется',
  DONE: 'Готово',
  FAILED: 'Ошибка',
  ABORTED: 'Прервано',
}
</script>

<template>
  <div>
    <ErrorState v-if="error" :message="error" />
    <template v-else>
      <PageHeader eyebrow="Pipeline" :title="pipeline?.name ?? slug" :subtitle="pipeline?.description ?? undefined">
        <template #actions>
          <RouterLink
            :to="{ name: 'pipeline-edit', params: { project, slug } }"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            Редактировать
          </RouterLink>
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-red-500/50 hover:text-red-600"
            @click="showDeleteConfirm = true"
          >
            <AppIcon name="trash" class="size-4" />
            Удалить
          </button>
          <RouterLink
            :to="projectLocation(project)"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="arrowLeft" class="size-4" />
            Назад
          </RouterLink>
        </template>
      </PageHeader>

      <SkeletonRows v-if="loading" :rows="2" class="mb-6" />

      <section v-else-if="pipeline" class="mb-9">
        <h2 class="mb-3 text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
        <div class="h-[360px] overflow-hidden rounded-xl border border-border bg-elevated">
          <VueFlow :nodes="flowNodes" :edges="flowEdges" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
        </div>
      </section>

      <section>
        <h2 class="mb-3 text-[13px] font-semibold tracking-wide text-content uppercase">История запусков</h2>
        <SkeletonRows v-if="runsLoading" :rows="2" />
        <EmptyState v-else-if="!runs?.length" icon="task" title="Пока не было ни одного запуска" />
        <ul v-else class="space-y-2">
          <li v-for="run in runs" :key="run.id">
            <RouterLink
              :to="{ name: 'pipeline-run', params: { project, slug, runId: run.id } }"
              class="flex items-center justify-between rounded-xl border border-border bg-panel px-4 py-3 transition hover:border-accent/40"
            >
              <span class="text-[13px] text-content">Запуск #{{ run.id }}</span>
              <span class="text-[12px] text-faint">{{ STATUS_LABEL[run.status] }} · {{ new Date(run.startedAt).toLocaleString() }}</span>
            </RouterLink>
          </li>
        </ul>
      </section>
    </template>

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Удалить этот пайплайн?"
      message="Определение и история запусков будут удалены безвозвратно."
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
    <p v-if="deleteError" class="mt-3 text-[12.5px] text-red-600">{{ deleteError }}</p>
  </div>
</template>
```

- [ ] **Step 2: Rewrite `PipelineRunView.vue`**

Replace the whole file:

```vue
<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, toRef } from 'vue'

import { fetchPipeline, fetchPipelineRun } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const slug = toRef(props, 'slug')
const runId = toRef(props, 'runId')

const { data: run, error, loading } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])
const { data: pipeline, loading: pipelineLoading } = useAsyncData(() => fetchPipeline(slug.value), [slug])

const title = computed(() => (run.value ? `Запуск #${run.value.id} — ${run.value.pipelineSlug}` : `Запуск #${runId.value}`))

const END_NODE_ID = 'end'

const STATUS_CLASS: Record<string, string> = {
  PENDING: 'pipeline-node pipeline-node-not-reached',
  RUNNING: 'pipeline-node pipeline-node-running',
  DONE: 'pipeline-node pipeline-node-done',
  FAILED: 'pipeline-node pipeline-node-failed',
  SKIPPED: 'pipeline-node pipeline-node-not-reached',
}

const flowNodes = computed(() => {
  if (!pipeline.value || !run.value) return []
  const runStepByOrderIndex = new Map(run.value.steps.map((s) => [s.orderIndex, s]))
  const steps = pipeline.value.steps
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => s.positionX)) : 0
  const isCurrent = (orderIndex: number) => run.value!.currentStepOrderIndex === orderIndex
  return [
    ...steps.map((step) => {
      const runStep = runStepByOrderIndex.get(step.orderIndex)
      const statusClass = runStep ? STATUS_CLASS[runStep.status] : 'pipeline-node pipeline-node-not-reached'
      return {
        id: String(step.orderIndex),
        position: { x: step.positionX, y: step.positionY },
        label: `${step.orderIndex + 1}. ${step.title}${runStep?.note ? ` — ${runStep.note}` : ''}`,
        class: isCurrent(step.orderIndex) ? `${statusClass} pipeline-node-selected` : statusClass,
      }
    }),
    {
      id: END_NODE_ID,
      position: { x: maxX + 240, y: 0 },
      label: 'Конец рана',
      class: run.value.currentStepOrderIndex === null ? 'pipeline-node pipeline-node-end pipeline-node-done' : 'pipeline-node pipeline-node-end',
    },
  ]
})

const flowEdges = computed(() => {
  if (!pipeline.value) return []
  return pipeline.value.steps.flatMap((step) =>
    step.routes.map((route) => ({
      id: `${step.orderIndex}-${route.outcomeKey ?? 'default'}-${route.targetStepOrderIndex ?? END_NODE_ID}`,
      source: String(step.orderIndex),
      target: route.targetStepOrderIndex === null ? END_NODE_ID : String(route.targetStepOrderIndex),
      label: route.outcomeKey ?? '(по умолчанию)',
    })),
  )
})
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" />

    <ErrorState v-if="error" :message="error" />
    <SkeletonRows v-else-if="loading || pipelineLoading" :rows="3" />

    <div v-else-if="run && pipeline" class="h-[420px] overflow-hidden rounded-xl border border-border bg-elevated">
      <VueFlow :nodes="flowNodes" :edges="flowEdges" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
    </div>
  </div>
</template>
```

- [ ] **Step 3: Run type-check**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 4: Manual smoke check**

Run: `cd ui && npm run dev`. Start a run of a branching pipeline from an MCP-connected Claude Code session (or `curl -X POST` the run-start/step-update endpoints directly for a quick check), then open that run's page and confirm: the taken branch's steps are colored (done/running/failed), the untaken branch stays dimmed, and the pipeline detail page (not a specific run) renders the same graph shape un-colored.

- [ ] **Step 5: Commit**

```bash
git add ui/src/views/PipelineView.vue ui/src/views/PipelineRunView.vue
git commit -m "feat(ui): render pipeline detail and run views as read-only branching graphs"
```

---

## Task 6: Update the `pipelines` skill for branching

**Files:**
- Modify: `.claude/skills/pipelines/SKILL.md`

**Interfaces:**
- Consumes: `routes` on `pipeline_get`'s step view and `currentStepOrderIndex` on every `pipeline_run_*` response (Tasks 1-3) — this task only changes prose, no code.

- [ ] **Step 1: Update step 3 ("Start the run") to mention `currentStepOrderIndex`**

In `.claude/skills/pipelines/SKILL.md`, replace:

```markdown
3. **Start the run:** `pipeline_run_start(slug, parametersJson)` with parameters as a JSON object
   string, e.g. `{"folder": "src/config"}`. This returns `runId` and the ordered step list with
   each step's `orderIndex`, `title`, `contentType`, and `status` (starts `PENDING`) — the step
   content (`instructionText`/`referenceText`) isn't repeated here; match each step by
   `orderIndex` against what `pipeline_get` already returned in step 1.
```

with:

```markdown
3. **Start the run:** `pipeline_run_start(slug, parametersJson)` with parameters as a JSON object
   string, e.g. `{"folder": "src/config"}`. This returns `runId`, `currentStepOrderIndex` (which
   step to work on next), and the full step list with each step's `orderIndex`, `title`,
   `contentType`, and `status` (starts `PENDING`) — the step content (`instructionText`/
   `referenceText`) isn't repeated here; match each step by `orderIndex` against what
   `pipeline_get` already returned in step 1. **If the pipeline branches, don't assume the next
   step is `orderIndex + 1` — always follow `currentStepOrderIndex` from the most recent response.**
```

- [ ] **Step 2: Update step 5 ("Work through steps in order") for branching**

Replace:

```markdown
5. **Work through steps in order.** For each step:
   - Substitute `{{paramName}}` in `instructionText` with the parameter values you collected.
   - If `referenceText` is present, treat it as supplementary reference material (e.g. an example
     report format) for that step, not an instruction to follow literally.
   - Do the actual work using your normal tools.
   - Update the checklist line in chat: `- [x]` on success, `- [!]` on failure.
   - Call `pipeline_run_step_update(runId, orderIndex, status, note)` with `status` = `DONE` or
     `FAILED` (`SKIPPED` if the user told you to skip this step), and a short `note` summarizing
     what happened.
```

with:

```markdown
5. **Work through steps following `currentStepOrderIndex`, not a fixed sequence.** After each
   response (`pipeline_run_start` or `pipeline_run_step_update`), the step to work on is whichever
   one has `orderIndex == currentStepOrderIndex` — for a non-branching pipeline this is always the
   next one in order, so nothing changes there. For each step:
   - Substitute `{{paramName}}` in `instructionText` with the parameter values you collected.
   - If `referenceText` is present, treat it as supplementary reference material (e.g. an example
     report format) for that step, not an instruction to follow literally.
   - Do the actual work using your normal tools.
   - Update the checklist line in chat: `- [x]` on success, `- [!]` on failure.
   - Check that step's `routes` (from `pipeline_get`, step 1). If it's empty, call
     `pipeline_run_step_update(runId, orderIndex, status, note)` exactly as before. If it has one
     or more entries, decide which `outcome` value best matches what actually happened (e.g.
     `"pass"`/`"fail"`, `"bug"`/`"feature"`/`"question"` — whatever keys that pipeline's routes
     use) and call `pipeline_run_step_update(runId, orderIndex, status, note, outcome)` — the
     `outcome` must exactly match one of that step's route keys or the call is rejected with the
     valid options listed.
   - Read `currentStepOrderIndex` off the response: if it's a number, that's the next step to work
     on (loop back to the top of this step). If it's `null`, every path from here has ended — go
     to step 7 below and call `pipeline_run_complete`.
```

- [ ] **Step 3: Update the "Resuming an interrupted run" section**

Replace:

```markdown
## Resuming an interrupted run

If the user asks to continue a pipeline run from an earlier session, call
`pipeline_run_get(runId)` to see which steps are already `DONE`/`FAILED`/`SKIPPED`, and resume
from the first `PENDING` step - don't redo finished steps.
```

with:

```markdown
## Resuming an interrupted run

If the user asks to continue a pipeline run from an earlier session, call
`pipeline_run_get(runId)` and resume from whatever step its `currentStepOrderIndex` points to -
don't assume it's "the first `PENDING` step", since in a branching pipeline some `PENDING` steps
may belong to a path that was never taken and will stay `PENDING` forever.
```

- [ ] **Step 4: Read the whole file back and sanity-check it**

Run: `cat .claude/skills/pipelines/SKILL.md`
Expected: the file reads coherently end-to-end — no leftover references to "the next step in order" as if branching didn't exist, no duplicated numbered steps.

- [ ] **Step 5: Commit**

```bash
git add .claude/skills/pipelines/SKILL.md
git commit -m "docs: teach the pipelines skill to follow currentStepOrderIndex and report outcome"
```

---

## Self-Review

**Spec coverage** (against `docs/superpowers/specs/2026-09-01-pipeline-branching-canvas-design.md`):
- §1 data model (routes table, positions, single default route, no-routes = legacy) → Task 1.
- §2 validation (single root, no cycles, at most one default route, isolated steps are a warning not an error) → Task 1 `validateGraph`, refined during planning to gate the legacy `orderIndex+1` fallback at the whole-pipeline level (not per-step) so a freshly-added, not-yet-wired canvas node is never silently chained to whatever step happens to sit at the next `orderIndex` — this is a precision fix within the spec's stated intent, not a new decision; flagged to the user in the plan handoff.
- §3 execution flow (`currentStepOrderIndex`, outcome resolution, invalid-outcome error, explicit complete) → Task 2, mirrored in Task 1's execution-time root/edge semantics.
- §4 MCP/DTO changes (`currentStepOrderIndex` on `PipelineRunDetail`, `outcome` param, `routes` on `pipeline_get`) → Tasks 1 (DTO), 2 (DTO), 3 (tool param + description).
- §5 CRUD changes (`StepRequest`/`PipelineStepView` gain positions + routes, index-based route targets resolved after steps are persisted) → Task 1.
- §6 frontend (vue-flow canvas builder, right-panel inspector, fixed End node, legacy auto-layout, read-only graph views for detail/run) → Tasks 4-5.
- Skill update for the execution engine to actually use any of this → Task 6.
- Out-of-scope items (continue-on-error, MCP authoring, non-Postgres assets, parallel fan-out, dagre-style auto-layout) → untouched, as intended.

**Type/name consistency check:** `PipelineStepRoute`/`PipelineStepRouteRepository` names match across Tasks 1-3. `PipelineRunService.updateStep`'s 5-arg signature (Task 2) matches every call site fixed in Tasks 2-3. `PipelineDetail.PipelineStepView.RouteView(outcomeKey, targetStepOrderIndex)` (Task 1, dashboard side) is deliberately a different shape from `PipelineExecutionDetail.StepView.RouteView(outcomeKey, targetStepOrderIndex, targetStepTitle)` (Task 1, MCP side) — the extra `targetStepTitle` exists so Claude doesn't need to cross-reference two lists; frontend `PipelineRouteView` (Task 4, `types.ts`) matches the dashboard shape (no title). `PipelineUpsertRoute`/`RouteRequest` both use `targetStepIndex` (frontend/backend naming matches). `END_NODE_ID = 'end'` string literal is reused identically across `PipelineBuilderView.vue`, `PipelineView.vue`, and `PipelineRunView.vue` (Tasks 4-5) — it is never persisted (a route's `targetStepId`/`targetStepIndex` being `null` is what's persisted), so the three copies never need to agree with anything outside the frontend.

**No placeholders:** every step above has literal, complete code — no "similar to Task N", no "add appropriate error handling" without showing the handling.

