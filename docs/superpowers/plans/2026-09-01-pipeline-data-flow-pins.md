# Pipeline data-flow pins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a pipeline step declare named output fields, wire them into a later step's prompt via a drag-connected link on the canvas, and have the server substitute the reported value into that step's instruction text at run time.

**Architecture:** Two new persisted entities (`PipelineStepOutput` = declared pin, `PipelineDataLink` = a wire between a source pin and a target step, identified by a client-generated UUID token embedded in the target's `promptText` as `{{data:<token>}}`) plus a run-time value table (`PipelineRunStepOutput`). `PipelineService` validates and persists the authoring side; `PipelineRunService` resolves tokens into a new `resolvedInstructionText` field on every `PipelineRunDetail` response, since `pipeline_get` hands Claude the raw prompt before any run-time value exists. The canvas gets a custom vue-flow node with distinct route/data handles so dragging a wire creates the right kind of edge.

**Tech Stack:** Spring Boot (Java 25), JPA/Hibernate, PostgreSQL/Flyway, Spring AI MCP server, Vue 3 + TypeScript, `@vue-flow/core`.

**Spec:** `docs/superpowers/specs/2026-09-01-pipeline-data-flow-pins-design.md`

## Global Constraints

- Entities are plain JPA with raw `Long` id columns for foreign keys (no `@ManyToOne`) - the existing `PipelineStepRoute` convention.
- Every save (`create`/`update`) fully deletes and recreates a pipeline's steps/routes/outputs/links - no incremental diff/upsert. Match this for outputs and data links.
- A data link's `promptText` placeholder is `{{data:<token>}}` where `token` is a client-generated UUID, stored verbatim server-side - never regenerated.
- Missing output value at run time substitutes `""`, never an error.
- A data link's source step must be an ancestor of its target, using the *same* adjacency rule `PipelineRunService` already executes with (explicit routes if any step has one, else implicit `orderIndex + 1` chaining).
- Backend tests: JUnit 5 + AssertJ, `@SpringBootTest @Transactional` against the local Postgres in `docker-compose.yml` (must be running - `docker compose up -d postgres` - Flyway applies migrations on context start). Run with `./gradlew test --tests '<FullyQualifiedClassName>'`.
- Frontend has no test runner configured (no Vitest, no `*.spec.ts` files anywhere in `ui/`) - do not add one as part of this plan (out of scope/YAGNI). Verify frontend tasks with `cd ui && npm run type-check` plus a manual check against the dev server (`npm run dev`).
- Follow existing code style exactly: constructor injection, no Lombok, Jackson via `tools.jackson.databind.*` (Jackson 3.x - use `.asString()` / `.propertyNames()`, not the 2.x `com.fasterxml.jackson` package).

---

### Task 1: Data model - migration, entities, repositories

**Files:**
- Create: `src/main/resources/db/migration/V15__add_pipeline_data_links.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStepOutput.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineDataLink.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRunStepOutput.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepOutputRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineDataLinkRepository.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepOutputRepository.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/repository/PipelineDataLinkRepositoryTest.java`

**Interfaces:**
- Produces: `PipelineStepOutput{id, stepId, name}`, `PipelineDataLink{id, token, sourceStepId, sourceOutputId, targetStepId}`, `PipelineRunStepOutput{id, runStepId, outputId, value}` entities and their repositories - `PipelineStepOutputRepository.findByStepId(Long)`, `.findByStepIdIn(List<Long>)`, `.deleteByStepIdIn(List<Long>)`; `PipelineDataLinkRepository.findBySourceStepIdIn(List<Long>)`, `.findByTargetStepIdIn(List<Long>)`, `.deleteBySourceStepIdIn(List<Long>)`; `PipelineRunStepOutputRepository.findByRunStepIdIn(List<Long>)`, `.findByRunStepIdAndOutputId(Long, Long): Optional<PipelineRunStepOutput>`. Later tasks depend on these exact method names.

- [ ] **Step 1: Write the migration**

```sql
-- src/main/resources/db/migration/V15__add_pipeline_data_links.sql
CREATE TABLE pipeline_step_outputs (
    id       BIGSERIAL PRIMARY KEY,
    step_id  BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    name     VARCHAR(100) NOT NULL
);

CREATE INDEX idx_pipeline_step_outputs_step_id ON pipeline_step_outputs (step_id);
CREATE UNIQUE INDEX ux_pipeline_step_outputs_step_name ON pipeline_step_outputs (step_id, name);

CREATE TABLE pipeline_data_links (
    id               BIGSERIAL PRIMARY KEY,
    token            VARCHAR(36) NOT NULL,
    source_step_id   BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    source_output_id BIGINT NOT NULL REFERENCES pipeline_step_outputs (id) ON DELETE CASCADE,
    target_step_id   BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_pipeline_data_links_token ON pipeline_data_links (token);
CREATE INDEX idx_pipeline_data_links_source_step_id ON pipeline_data_links (source_step_id);
CREATE INDEX idx_pipeline_data_links_target_step_id ON pipeline_data_links (target_step_id);

CREATE TABLE pipeline_run_step_outputs (
    id          BIGSERIAL PRIMARY KEY,
    run_step_id BIGINT NOT NULL REFERENCES pipeline_run_steps (id) ON DELETE CASCADE,
    output_id   BIGINT NOT NULL REFERENCES pipeline_step_outputs (id) ON DELETE CASCADE,
    value       TEXT NOT NULL
);

CREATE INDEX idx_pipeline_run_step_outputs_run_step_id ON pipeline_run_step_outputs (run_step_id);
CREATE UNIQUE INDEX ux_pipeline_run_step_outputs_run_step_output
    ON pipeline_run_step_outputs (run_step_id, output_id);
```

- [ ] **Step 2: Write the three entities**

```java
// src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStepOutput.java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_step_outputs")
public class PipelineStepOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(nullable = false, length = 100)
    private String name;

    public Long getId() {
        return id;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
```

```java
// src/main/java/ru/iuribabalin/memorymcp/entity/PipelineDataLink.java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_data_links")
public class PipelineDataLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String token;

    @Column(name = "source_step_id", nullable = false)
    private Long sourceStepId;

    @Column(name = "source_output_id", nullable = false)
    private Long sourceOutputId;

    @Column(name = "target_step_id", nullable = false)
    private Long targetStepId;

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getSourceStepId() {
        return sourceStepId;
    }

    public void setSourceStepId(Long sourceStepId) {
        this.sourceStepId = sourceStepId;
    }

    public Long getSourceOutputId() {
        return sourceOutputId;
    }

    public void setSourceOutputId(Long sourceOutputId) {
        this.sourceOutputId = sourceOutputId;
    }

    public Long getTargetStepId() {
        return targetStepId;
    }

    public void setTargetStepId(Long targetStepId) {
        this.targetStepId = targetStepId;
    }
}
```

```java
// src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRunStepOutput.java
package ru.iuribabalin.memorymcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_run_step_outputs")
public class PipelineRunStepOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_step_id", nullable = false)
    private Long runStepId;

    @Column(name = "output_id", nullable = false)
    private Long outputId;

    @Column(nullable = false, columnDefinition = "text")
    private String value;

    public Long getId() {
        return id;
    }

    public Long getRunStepId() {
        return runStepId;
    }

    public void setRunStepId(Long runStepId) {
        this.runStepId = runStepId;
    }

    public Long getOutputId() {
        return outputId;
    }

    public void setOutputId(Long outputId) {
        this.outputId = outputId;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
```

- [ ] **Step 3: Write the three repositories**

```java
// src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepOutputRepository.java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;

import java.util.List;

public interface PipelineStepOutputRepository extends JpaRepository<PipelineStepOutput, Long> {
    List<PipelineStepOutput> findByStepId(Long stepId);
    List<PipelineStepOutput> findByStepIdIn(List<Long> stepIds);
    void deleteByStepIdIn(List<Long> stepIds);
}
```

```java
// src/main/java/ru/iuribabalin/memorymcp/repository/PipelineDataLinkRepository.java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;

import java.util.List;

public interface PipelineDataLinkRepository extends JpaRepository<PipelineDataLink, Long> {
    List<PipelineDataLink> findBySourceStepIdIn(List<Long> stepIds);
    List<PipelineDataLink> findByTargetStepIdIn(List<Long> targetStepIds);
    void deleteBySourceStepIdIn(List<Long> stepIds);
}
```

```java
// src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepOutputRepository.java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineRunStepOutput;

import java.util.List;
import java.util.Optional;

public interface PipelineRunStepOutputRepository extends JpaRepository<PipelineRunStepOutput, Long> {
    List<PipelineRunStepOutput> findByRunStepIdIn(List<Long> runStepIds);
    Optional<PipelineRunStepOutput> findByRunStepIdAndOutputId(Long runStepId, Long outputId);
}
```

- [ ] **Step 4: Write the failing cascade-delete test**

```java
// src/test/java/ru/iuribabalin/memorymcp/repository/PipelineDataLinkRepositoryTest.java
package ru.iuribabalin.memorymcp.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineDataLink;
import ru.iuribabalin.memorymcp.entity.PipelineStep;
import ru.iuribabalin.memorymcp.entity.PipelineStepOutput;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PipelineDataLinkRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepository;
    @Autowired
    private PipelineStepRepository pipelineStepRepository;
    @Autowired
    private PipelineStepOutputRepository pipelineStepOutputRepository;
    @Autowired
    private PipelineDataLinkRepository pipelineDataLinkRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void deletingTheSourceStepCascadesToItsOutputsAndDataLinks() {
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug("data-link-repo-test");
        pipeline.setName("Data link repo test");
        pipeline.setCreatedAt(Instant.now());
        pipeline.setUpdatedAt(Instant.now());
        pipeline = pipelineRepository.save(pipeline);

        PipelineStep source = new PipelineStep();
        source.setPipelineId(pipeline.getId());
        source.setOrderIndex(0);
        source.setTitle("Source");
        source.setContentType(PipelineStep.ContentType.PROMPT);
        source.setPromptText("do the thing");
        source = pipelineStepRepository.save(source);

        PipelineStep target = new PipelineStep();
        target.setPipelineId(pipeline.getId());
        target.setOrderIndex(1);
        target.setTitle("Target");
        target.setContentType(PipelineStep.ContentType.PROMPT);
        target.setPromptText("use {{data:tok-1}}");
        target = pipelineStepRepository.save(target);

        PipelineStepOutput output = new PipelineStepOutput();
        output.setStepId(source.getId());
        output.setName("summary");
        output = pipelineStepOutputRepository.save(output);

        PipelineDataLink link = new PipelineDataLink();
        link.setToken("tok-1");
        link.setSourceStepId(source.getId());
        link.setSourceOutputId(output.getId());
        link.setTargetStepId(target.getId());
        link = pipelineDataLinkRepository.save(link);

        pipelineStepRepository.deleteById(source.getId());
        pipelineStepRepository.flush();
        entityManager.clear();

        assertThat(pipelineStepOutputRepository.findById(output.getId())).isEmpty();
        assertThat(pipelineDataLinkRepository.findById(link.getId())).isEmpty();
    }
}
```

- [ ] **Step 5: Run it to confirm it fails to compile/run before the migration exists**

Run: `docker compose up -d postgres && ./gradlew test --tests 'ru.iuribabalin.memorymcp.repository.PipelineDataLinkRepositoryTest'`
Expected: FAIL - Flyway/Hibernate error, table `pipeline_step_outputs` does not exist (confirms the test exercises real schema, not a mock).

- [ ] **Step 6: Nothing further to implement for this task** - the migration and entities/repos from Steps 1-3 are the implementation; re-run the test.

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.repository.PipelineDataLinkRepositoryTest'`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V15__add_pipeline_data_links.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStepOutput.java \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineDataLink.java \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRunStepOutput.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/PipelineStepOutputRepository.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/PipelineDataLinkRepository.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepOutputRepository.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/PipelineDataLinkRepositoryTest.java
git commit -m "feat: add pipeline data-link data model (V15 migration, entities, repos)"
```

---

### Task 2: `PipelineService` - declare outputs, wire data links, validate, persist, read back

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java` (existing `StepRequest(...)` call sites need updating to compile - see Step 6)

**Interfaces:**
- Consumes: `PipelineStepOutputRepository`, `PipelineDataLinkRepository` from Task 1 (`findByStepId`, `findByStepIdIn`, `deleteByStepIdIn`, `findBySourceStepIdIn`, `findByTargetStepIdIn`, `deleteBySourceStepIdIn`).
- Produces: `PipelineUpsertRequest.StepRequest.OutputRequest(String name)`, `.DataLinkRequest(String token, String sourceOutputName, Integer targetStepIndex)`; `PipelineDetail.PipelineStepView.OutputView(Long id, String name)`, `.DataLinkView(Long id, String token, String sourceOutputName, Integer targetStepOrderIndex, String targetStepTitle)`; `PipelineExecutionDetail.StepView.outputs(): List<String>`. Task 3 and Task 4 read `PipelineStepOutputRepository`/`PipelineDataLinkRepository` the same way.

- [ ] **Step 1: Extend the DTOs**

In `PipelineUpsertRequest.java`, change `StepRequest` to:

```java
    public record StepRequest(
            String title, PipelineStep.ContentType contentType, String promptText,
            Long assetId, Long referenceAssetId, double positionX, double positionY,
            List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut) {

        public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
        }

        public record OutputRequest(String name) {
        }

        public record DataLinkRequest(String token, String sourceOutputName, Integer targetStepIndex) {
        }
    }
```

In `PipelineDetail.java`, change `PipelineStepView` to:

```java
    public record PipelineStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            String promptText, Long assetId, Long referenceAssetId,
            double positionX, double positionY, List<RouteView> routes,
            List<OutputView> outputs, List<DataLinkView> dataLinksOut) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex) {
        }

        public record OutputView(Long id, String name) {
        }

        public record DataLinkView(Long id, String token, String sourceOutputName,
                                    Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
```

In `PipelineExecutionDetail.java`, change `StepView` to:

```java
    public record StepView(int orderIndex, String title, String instructionText, String referenceText,
                            List<RouteView> routes, List<String> outputs) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
```

- [ ] **Step 2: Run the build to see every call site the compiler flags**

Run: `./gradlew compileJava compileTestJava`
Expected: FAIL - constructor argument-count errors in `PipelineService.java` (its `toDetail`/`getForExecution` methods build these records) and in `PipelineServiceTest.java`/`PipelineRunServiceTest.java` (every `new PipelineUpsertRequest.StepRequest(...)` call is missing the two new trailing arguments).

- [ ] **Step 3: Fix every flagged test call site**

For each `StepRequest(...)` constructor call the compiler points at in `PipelineServiceTest.java` and `PipelineRunServiceTest.java`, append two more arguments before the closing `)`: `, List.of(), List.of()` (empty outputs, empty data links - none of the existing tests exercise this feature). Example, `PipelineServiceTest.sampleRequest`:

```java
    private PipelineUpsertRequest sampleRequest(String slug) {
        return new PipelineUpsertRequest(
                slug, "Config diff", "Diffs configs against prod", "pipeline-svc-test-project",
                List.of(new PipelineUpsertRequest.ParameterRequest("folder", "Folder to check", PipelineParameter.Type.STRING, true, null)),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}} against prod", null, null, 0, 0, List.of(), List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("Save report", PipelineStep.ContentType.PROMPT, "Save the report to memory", null, null, 0, 0, List.of(), List.of(), List.of())));
    }
```

Repeat the same `, List.of(), List.of()` append for every other `StepRequest(...)` call the compiler flags in both files (there are roughly a dozen across the two files - `rejectsAPipelineWithACycle`, `rejectsARouteWithAnOutOfRangeTargetStepIndex`, `rejectsAPipelineWithTwoStartingSteps`, `rejectsTwoDefaultRoutesOnTheSameStep`, `rejectsTwoRoutesWithTheSameNonNullOutcomeKeyOnTheSameStep`, `allowsAnUnwiredIsolatedStepAsAWarningOnlyNotAHardError`, `savesAndReadsBackPositionsAndRoutes`, `rejectsAnMdFileStepWithNoUploadedAsset`, and `PipelineRunServiceTest`'s `createSamplePipeline`/`createBranchingPipeline`/`startResolvesTheTrueRootEvenWhenItIsNotTheLowestOrderIndex`/`skippedOnABranchingStepWithADefaultRouteFollowsIt`).

Run: `./gradlew compileTestJava`
Expected: still FAIL on `PipelineService.java` itself (not yet updated) - test files should now compile clean once `PipelineService.java` compiles too (Step 5).

- [ ] **Step 4: Write the new failing tests in `PipelineServiceTest.java`**

`dataLinksOut` lives on the **source** step (spec section 5) - wiring "Report uses Summarize's
output" is declared on the **Summarize** step (index 0), targeting Report (index 1):

```java
    @Test
    void savesAndReadsBackOutputsAndDataLinks() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-1", "Data link pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Summarize", PipelineStep.ContentType.PROMPT, "summarize it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "summary", 1))),
                        new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "use {{data:tok-1}}",
                                null, null, 0, 0, List.of(), List.of(), List.of())));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView summarize = detail.steps().get(0);
        assertThat(summarize.outputs()).extracting(PipelineDetail.PipelineStepView.OutputView::name).containsExactly("summary");
        assertThat(summarize.dataLinksOut()).hasSize(1);
        PipelineDetail.PipelineStepView.DataLinkView link = summarize.dataLinksOut().get(0);
        assertThat(link.token()).isEqualTo("tok-1");
        assertThat(link.sourceOutputName()).isEqualTo("summary");
        assertThat(link.targetStepOrderIndex()).isEqualTo(1);
        assertThat(link.targetStepTitle()).isEqualTo("Report");
    }

    @Test
    void rejectsADuplicateOutputNameOnTheSameStep() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-2", "Dup output", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary"),
                                new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                        List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsADataLinkToAStepThatIsNotAnAncestor() {
        // No routes anywhere -> implicit orderIndex+1 chaining is the graph. Step 1 wiring a link
        // back to step 0 violates the ancestor rule (0 can never run after 1 in this pipeline).
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-3", "Backwards link", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(), List.of(), List.of()),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-2", "x", 0)))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsASelfReferencingDataLink() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-4", "Self link", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                        List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-3", "x", 0)))));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsADataLinkWiringAnUndeclaredOutputName() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-5", "Undeclared output", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(), List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-4", "never-declared", 1))),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of())));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void getForExecutionListsDeclaredOutputNames() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "data-link-6", "Exec view", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                        null, null, 0, 0, List.of(),
                        List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                        List.of())));
        pipelineService.create(request, "Tester");

        var execution = pipelineService.getForExecution("data-link-6");

        assertThat(execution.steps().get(0).outputs()).containsExactly("summary");
    }
```

Add these six tests to `PipelineServiceTest.java`.

- [ ] **Step 5: Run tests to verify they fail**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineServiceTest'`
Expected: FAIL - `PipelineService.java` doesn't compile yet (constructors don't match the new DTO shapes).

- [ ] **Step 6: Implement `PipelineService` changes**

Add fields/constructor params for the two new repositories:

```java
    private final PipelineStepOutputRepository pipelineStepOutputRepository;
    private final PipelineDataLinkRepository pipelineDataLinkRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                            PipelineParameterRepository pipelineParameterRepository,
                            PipelineStepRepository pipelineStepRepository,
                            PipelineStepRouteRepository pipelineStepRouteRepository,
                            PipelineStepOutputRepository pipelineStepOutputRepository,
                            PipelineDataLinkRepository pipelineDataLinkRepository,
                            PipelineAssetService pipelineAssetService,
                            ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineParameterRepository = pipelineParameterRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineStepOutputRepository = pipelineStepOutputRepository;
        this.pipelineDataLinkRepository = pipelineDataLinkRepository;
        this.pipelineAssetService = pipelineAssetService;
        this.objectMapper = objectMapper;
    }
```

Add imports: `ru.iuribabalin.memorymcp.entity.PipelineStepOutput`, `ru.iuribabalin.memorymcp.entity.PipelineDataLink`, `ru.iuribabalin.memorymcp.repository.PipelineStepOutputRepository`, `ru.iuribabalin.memorymcp.repository.PipelineDataLinkRepository`.

Call the new validation from `create` and `update`, right after `validateGraph(request.steps())`:

```java
        validateGraph(request.steps());
        validateDataLinks(request.steps());
```

Add the validation methods (near `validateGraph`):

```java
    /**
     * Reachability for data links uses the same adjacency PipelineRunService executes with:
     * explicit routes if any step declares one, otherwise implicit orderIndex+1 chaining.
     */
    private List<List<Integer>> buildExecutionAdjacency(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        boolean anyRoutes = steps.stream().anyMatch(s -> !s.routes().isEmpty());
        if (!anyRoutes) {
            for (int i = 0; i < n - 1; i++) {
                adjacency.get(i).add(i + 1);
            }
            return adjacency;
        }
        for (int i = 0; i < n; i++) {
            for (PipelineUpsertRequest.StepRequest.RouteRequest route : steps.get(i).routes()) {
                if (route.targetStepIndex() != null) {
                    adjacency.get(i).add(route.targetStepIndex());
                }
            }
        }
        return adjacency;
    }

    private Set<Integer> reachableFrom(int start, List<List<Integer>> adjacency) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int next : adjacency.get(current)) {
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return visited;
    }

    private void validateDataLinks(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        List<List<Integer>> adjacency = buildExecutionAdjacency(steps);
        for (int i = 0; i < n; i++) {
            PipelineUpsertRequest.StepRequest step = steps.get(i);
            Set<String> outputNames = new HashSet<>();
            for (PipelineUpsertRequest.StepRequest.OutputRequest output : step.outputs()) {
                if (!outputNames.add(output.name())) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' declares more than one output named '" + output.name() + "'");
                }
            }
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest link : step.dataLinksOut()) {
                if (!outputNames.contains(link.sourceOutputName())) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' wires an output '" + link.sourceOutputName() + "' it never declared");
                }
                Integer target = link.targetStepIndex();
                if (target == null || target < 0 || target >= n) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' wires a data link to step index " + target
                                    + ", but the pipeline only has " + n + " steps");
                }
                if (target == i) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' cannot wire a data link to itself");
                }
                if (!reachableFrom(i, adjacency).contains(target)) {
                    throw new PipelineInvalidGraphException(
                            "Step '" + step.title() + "' wires a data link to step '" + steps.get(target).title()
                                    + "', but that step can never run after it - a data link's source must be an ancestor of its target");
                }
            }
        }
    }
```

Update `delete()` to also clean up outputs/links:

```java
    @Transactional
    public boolean delete(String slug) {
        return pipelineRepository.findBySlug(slug)
                .map(pipeline -> {
                    List<Long> stepIds = stepIdsOf(pipeline.getId());
                    pipelineParameterRepository.deleteByPipelineId(pipeline.getId());
                    pipelineDataLinkRepository.deleteBySourceStepIdIn(stepIds);
                    pipelineStepOutputRepository.deleteByStepIdIn(stepIds);
                    pipelineStepRouteRepository.deleteByStepIdIn(stepIds);
                    pipelineStepRepository.deleteByPipelineId(pipeline.getId());
                    pipelineRepository.delete(pipeline);
                    return true;
                })
                .orElse(false);
    }
```

Update `replaceParametersAndSteps` to delete-then-recreate outputs and links:

```java
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

        List<Long> existingStepIds = stepIdsOf(pipelineId);
        pipelineDataLinkRepository.deleteBySourceStepIdIn(existingStepIds);
        pipelineStepOutputRepository.deleteByStepIdIn(existingStepIds);
        pipelineStepRouteRepository.deleteByStepIdIn(existingStepIds);
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

        List<List<PipelineStepOutput>> outputsByStep = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            List<PipelineStepOutput> stepOutputs = new ArrayList<>();
            for (PipelineUpsertRequest.StepRequest.OutputRequest outputRequest : request.steps().get(i).outputs()) {
                PipelineStepOutput output = new PipelineStepOutput();
                output.setStepId(savedSteps.get(i).getId());
                output.setName(outputRequest.name());
                stepOutputs.add(pipelineStepOutputRepository.save(output));
            }
            outputsByStep.add(stepOutputs);
        }
        for (int i = 0; i < request.steps().size(); i++) {
            Map<String, Long> outputIdByName = outputsByStep.get(i).stream()
                    .collect(Collectors.toMap(PipelineStepOutput::getName, PipelineStepOutput::getId));
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest linkRequest : request.steps().get(i).dataLinksOut()) {
                PipelineDataLink link = new PipelineDataLink();
                link.setToken(linkRequest.token());
                link.setSourceStepId(savedSteps.get(i).getId());
                link.setSourceOutputId(outputIdByName.get(linkRequest.sourceOutputName()));
                link.setTargetStepId(savedSteps.get(linkRequest.targetStepIndex()).getId());
                pipelineDataLinkRepository.save(link);
            }
        }
    }
```

Update `getForExecution` to populate `outputs` per step:

```java
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
                                .toList(),
                        pipelineStepOutputRepository.findByStepId(step.getId()).stream()
                                .map(PipelineStepOutput::getName)
                                .toList()))
                .toList();
```

Update `toDetail` to populate `outputs`/`dataLinksOut` per step:

```java
    private PipelineDetail toDetail(Pipeline pipeline) {
        List<PipelineDetail.PipelineParameterView> parameters = pipelineParameterRepository
                .findByPipelineIdOrderByOrderIndexAsc(pipeline.getId()).stream()
                .map(p -> new PipelineDetail.PipelineParameterView(p.getId(), p.getName(), p.getLabel(), p.getType(), p.isRequired(), p.getDefaultValue(), p.getOrderIndex()))
                .toList();
        List<PipelineStep> pipelineSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(pipeline.getId());
        Map<Long, Integer> orderIndexById = pipelineSteps.stream()
                .collect(Collectors.toMap(PipelineStep::getId, PipelineStep::getOrderIndex));
        Map<Long, String> titleById = pipelineSteps.stream()
                .collect(Collectors.toMap(PipelineStep::getId, PipelineStep::getTitle));
        List<PipelineDetail.PipelineStepView> steps = pipelineSteps.stream()
                .map(s -> {
                    Map<Long, PipelineStepOutput> outputsById = pipelineStepOutputRepository.findByStepId(s.getId()).stream()
                            .collect(Collectors.toMap(PipelineStepOutput::getId, o -> o));
                    return new PipelineDetail.PipelineStepView(
                            s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(),
                            s.getAssetId(), s.getReferenceAssetId(), s.getPositionX(), s.getPositionY(),
                            pipelineStepRouteRepository.findByStepId(s.getId()).stream()
                                    .map(r -> new PipelineDetail.PipelineStepView.RouteView(
                                            r.getOutcomeKey(),
                                            r.getTargetStepId() != null ? orderIndexById.get(r.getTargetStepId()) : null))
                                    .toList(),
                            outputsById.values().stream()
                                    .map(o -> new PipelineDetail.PipelineStepView.OutputView(o.getId(), o.getName()))
                                    .toList(),
                            pipelineDataLinkRepository.findBySourceStepIdIn(List.of(s.getId())).stream()
                                    .map(link -> new PipelineDetail.PipelineStepView.DataLinkView(
                                            link.getId(), link.getToken(), outputsById.get(link.getSourceOutputId()).getName(),
                                            orderIndexById.get(link.getTargetStepId()), titleById.get(link.getTargetStepId())))
                                    .toList());
                })
                .toList();
        return new PipelineDetail(pipeline.getId(), pipeline.getSlug(), pipeline.getName(), pipeline.getDescription(),
                pipeline.getProjectScope(), parameters, steps, pipeline.getCreatedBy(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineServiceTest'`
Expected: PASS (all tests, old and new)

- [ ] **Step 8: Run the full existing pipeline test suite to check for regressions**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineRunServiceTest' --tests 'ru.iuribabalin.memorymcp.ui.PipelineControllerTest'`
Expected: PASS (compiles now that Step 3's call-site fixes are in place; `PipelineRunServiceTest` itself isn't touched further until Task 3)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java
git commit -m "feat: declare and wire pipeline step output pins in PipelineService"
```

---

### Task 3: `PipelineRunService` - report outputs at run time, resolve `resolvedInstructionText`

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunUnknownOutputException.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: `PipelineStepOutputRepository`, `PipelineDataLinkRepository` (Task 1), `PipelineStepRepository` (existing).
- Produces: `PipelineRunService.updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note, String outcome, String outputsJson): PipelineRunDetail` (signature grows from 5 to 6 args); `PipelineRunDetail.PipelineRunStepView.resolvedInstructionText(): String`. Task 4 calls this new 6-arg signature.

- [ ] **Step 1: Extend `PipelineRunDetail`**

```java
    public record PipelineRunStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            PipelineRunStep.Status status, String note, Instant startedAt, Instant finishedAt,
            String resolvedInstructionText) {
    }
```

- [ ] **Step 2: Write the new exception**

```java
// src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunUnknownOutputException.java
package ru.iuribabalin.memorymcp.service;

import java.util.List;

public class PipelineRunUnknownOutputException extends RuntimeException {
    public PipelineRunUnknownOutputException(String outputName, List<String> validNames) {
        super("Output '" + outputName + "' is not declared for this step — expected one of: " + String.join(", ", validNames));
    }
}
```

- [ ] **Step 3: Register it in `ApiExceptionHandler`**

```java
    @ExceptionHandler(PipelineRunUnknownOutputException.class)
    public ResponseEntity<Map<String, String>> handlePipelineRunUnknownOutput(PipelineRunUnknownOutputException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
```

Add the import `ru.iuribabalin.memorymcp.service.PipelineRunUnknownOutputException;` alongside the other `Pipeline*` imports.

- [ ] **Step 4: Write the failing tests in `PipelineRunServiceTest.java`**

```java
    private void createDataLinkPipeline(String slug) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Data link run", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Summarize", PipelineStep.ContentType.PROMPT, "summarize it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("summary")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "summary", 1))),
                        new PipelineUpsertRequest.StepRequest("Report", PipelineStep.ContentType.PROMPT, "Write it up: {{data:tok-1}}",
                                null, null, 0, 0, List.of(), List.of(), List.of()))
        ), "Tester");
    }

    @Test
    void resolvedInstructionTextSubstitutesAReportedOutputValue() {
        createDataLinkPipeline("run-test-17");
        PipelineRunDetail run = pipelineRunService.start("run-test-17", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"summary\":\"all green\"}");

        assertThat(updated.steps().get(1).resolvedInstructionText()).isEqualTo("Write it up: all green");
    }

    @Test
    void resolvedInstructionTextSubstitutesEmptyStringWhenTheOutputWasNeverReported() {
        createDataLinkPipeline("run-test-18");
        PipelineRunDetail run = pipelineRunService.start("run-test-18", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, null);

        assertThat(updated.steps().get(1).resolvedInstructionText()).isEqualTo("Write it up: ");
    }

    @Test
    void updateStepRejectsAnUndeclaredOutputKey() {
        createDataLinkPipeline("run-test-19");
        PipelineRunDetail run = pipelineRunService.start("run-test-19", "{}", "Tester");

        assertThatThrownBy(() -> pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"typo\":\"x\"}"))
                .isInstanceOf(PipelineRunUnknownOutputException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void resolvedInstructionTextIsPlainPromptTextWhenTheStepHasNoIncomingDataLinks() {
        createSamplePipeline("run-test-20");
        PipelineRunDetail run = pipelineRunService.start("run-test-20", "{\"folder\":\"src\"}", "Tester");

        assertThat(run.steps().get(0).resolvedInstructionText()).isEqualTo("Diff {{folder}}");
    }
```

Every existing call to `pipelineRunService.updateStep(...)` in this file (5-arg form) also needs a trailing `, null` for the new `outputsJson` parameter - the compiler will flag each one in the next step.

- [ ] **Step 5: Run the build to find every 5-arg `updateStep(...)` call site**

Run: `./gradlew compileTestJava`
Expected: FAIL - argument-count errors at every existing `pipelineRunService.updateStep(...)` call in `PipelineRunServiceTest.java` and at `pipelineRunService.updateStep(...)` inside `PipelineMcpTools.java`, plus at `pipelineRunService.updateStep(...)` inside `PipelineMcpToolsTest.java`.

- [ ] **Step 6: Fix every flagged existing call site** by appending `, null` (no outputs reported) as the sixth argument. Example, in `PipelineRunServiceTest.java`:

```java
        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "diffed fine", null, null);
```

Apply the same trailing `, null` to every other flagged `updateStep(...)` call in `PipelineRunServiceTest.java` (roughly ten call sites: `updateStepMovesItToDoneAndStampsFinishedAt`, `doneWithAMatchingOutcomeAdvancesToTheRoutedStep`, `doneWithADifferentOutcomeAdvancesToItsOwnBranch`, `doneWithAnUnknownOutcomeThrows`, `doneOnAStepWithNoRoutesInALegacyPipelineFallsBackToOrderIndexPlusOne`, `doneOnTheLastStepOfABranchEndsTheRun` (two calls), `skippedOnALegacyPipelineStillAdvancesViaOrderIndexPlusOne`, `skippedOnABranchingStepWithADefaultRouteFollowsIt`, `skippedOnABranchingStepWithOnlyNamedRoutesEndsThatPathInsteadOfThrowing`).

`PipelineMcpTools.java` (production code, not a test) also calls the old 5-arg form and won't compile
otherwise - make the same minimal `, null` fix there, in `pipelineRunStepUpdate`:

```java
        PipelineRunDetail run = pipelineRunService.updateStep(runId, orderIndex, status, note, outcome, null);
```

And the one matching mock stub in `PipelineMcpToolsTest.java`'s `runStepUpdateDelegatesAndRecordsUsage`:

```java
        when(pipelineRunService.updateStep(1L, 0, PipelineRunStep.Status.DONE, "ok", "success", null)).thenReturn(runDetail);
```

This is a deliberately temporary, minimal compile fix - Task 4 properly threads a real `outputsJson`
MCP parameter through `PipelineMcpTools.java` and replaces this hardcoded `null`.

- [ ] **Step 7: Implement `PipelineRunService` changes**

Add fields/constructor params:

```java
    private final PipelineStepOutputRepository pipelineStepOutputRepository;
    private final PipelineDataLinkRepository pipelineDataLinkRepository;
    private final PipelineRunStepOutputRepository pipelineRunStepOutputRepository;
    private final ObjectMapper objectMapper;

    public PipelineRunService(PipelineRunRepository pipelineRunRepository,
                               PipelineRunStepRepository pipelineRunStepRepository,
                               PipelineRepository pipelineRepository,
                               PipelineStepRepository pipelineStepRepository,
                               PipelineStepRouteRepository pipelineStepRouteRepository,
                               PipelineStepOutputRepository pipelineStepOutputRepository,
                               PipelineDataLinkRepository pipelineDataLinkRepository,
                               PipelineRunStepOutputRepository pipelineRunStepOutputRepository,
                               ObjectMapper objectMapper) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunStepRepository = pipelineRunStepRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineStepRepository = pipelineStepRepository;
        this.pipelineStepRouteRepository = pipelineStepRouteRepository;
        this.pipelineStepOutputRepository = pipelineStepOutputRepository;
        this.pipelineDataLinkRepository = pipelineDataLinkRepository;
        this.pipelineRunStepOutputRepository = pipelineRunStepOutputRepository;
        this.objectMapper = objectMapper;
    }
```

Add imports: `tools.jackson.databind.JsonNode`, `tools.jackson.databind.ObjectMapper`, the three new entities, the three new repositories, `java.util.Objects`.

Change `updateStep`'s signature and add output recording:

```java
    @Transactional
    public PipelineRunDetail updateStep(Long runId, int orderIndex, PipelineRunStep.Status status, String note,
                                         String outcome, String outputsJson) {
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

        if (outputsJson != null && !outputsJson.isBlank() && runStep.getPipelineStepId() != null) {
            recordOutputs(runStep, outputsJson);
        }

        if ((status == PipelineRunStep.Status.DONE || status == PipelineRunStep.Status.SKIPPED)
                && runStep.getPipelineStepId() != null) {
            run.setCurrentStepOrderIndex(resolveNextOrderIndexForStatus(run.getPipelineId(), runStep.getPipelineStepId(), orderIndex, outcome, status));
            pipelineRunRepository.save(run);
        }
        return toDetail(run, pipelineSlugOf(run));
    }

    private void recordOutputs(PipelineRunStep runStep, String outputsJson) {
        List<PipelineStepOutput> declared = pipelineStepOutputRepository.findByStepId(runStep.getPipelineStepId());
        Map<String, Long> outputIdByName = declared.stream()
                .collect(Collectors.toMap(PipelineStepOutput::getName, PipelineStepOutput::getId));
        JsonNode node;
        try {
            node = objectMapper.readTree(outputsJson);
        } catch (Exception ex) {
            throw new PipelineRunUnknownOutputException(outputsJson,
                    declared.stream().map(PipelineStepOutput::getName).toList());
        }
        for (String name : node.propertyNames()) {
            Long outputId = outputIdByName.get(name);
            if (outputId == null) {
                throw new PipelineRunUnknownOutputException(name,
                        declared.stream().map(PipelineStepOutput::getName).toList());
            }
            PipelineRunStepOutput runStepOutput = pipelineRunStepOutputRepository
                    .findByRunStepIdAndOutputId(runStep.getId(), outputId)
                    .orElseGet(PipelineRunStepOutput::new);
            runStepOutput.setRunStepId(runStep.getId());
            runStepOutput.setOutputId(outputId);
            runStepOutput.setValue(node.get(name).asString());
            pipelineRunStepOutputRepository.save(runStepOutput);
        }
    }
```

Replace `toDetail` to compute `resolvedInstructionText`:

```java
    private PipelineRunDetail toDetail(PipelineRun run, String pipelineSlug) {
        List<PipelineRunStep> runSteps = pipelineRunStepRepository.findByRunIdOrderByOrderIndexAsc(run.getId());
        List<Long> pipelineStepIds = runSteps.stream().map(PipelineRunStep::getPipelineStepId)
                .filter(Objects::nonNull).toList();
        Map<Long, PipelineStep> stepById = pipelineStepRepository.findAllById(pipelineStepIds).stream()
                .collect(Collectors.toMap(PipelineStep::getId, s -> s));
        Map<Long, Long> runStepIdByPipelineStepId = runSteps.stream()
                .filter(rs -> rs.getPipelineStepId() != null)
                .collect(Collectors.toMap(PipelineRunStep::getPipelineStepId, PipelineRunStep::getId));
        List<PipelineDataLink> incomingLinks = pipelineDataLinkRepository.findByTargetStepIdIn(pipelineStepIds);
        List<Long> sourceRunStepIds = incomingLinks.stream()
                .map(link -> runStepIdByPipelineStepId.get(link.getSourceStepId()))
                .filter(Objects::nonNull)
                .toList();
        Map<String, String> reportedValues = pipelineRunStepOutputRepository.findByRunStepIdIn(sourceRunStepIds).stream()
                .collect(Collectors.toMap(o -> o.getRunStepId() + ":" + o.getOutputId(), PipelineRunStepOutput::getValue));

        List<PipelineRunDetail.PipelineRunStepView> steps = runSteps.stream()
                .map(s -> new PipelineRunDetail.PipelineRunStepView(s.getId(), s.getOrderIndex(), s.getTitle(),
                        s.getContentType(), s.getStatus(), s.getNote(), s.getStartedAt(), s.getFinishedAt(),
                        resolveInstructionText(s, stepById, incomingLinks, runStepIdByPipelineStepId, reportedValues)))
                .toList();
        return new PipelineRunDetail(run.getId(), run.getPipelineId(), pipelineSlug, run.getStatus(),
                run.getParametersJson(), run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(),
                run.getCurrentStepOrderIndex(), steps);
    }

    private String resolveInstructionText(PipelineRunStep runStep, Map<Long, PipelineStep> stepById,
                                           List<PipelineDataLink> allIncomingLinks,
                                           Map<Long, Long> runStepIdByPipelineStepId,
                                           Map<String, String> reportedValues) {
        if (runStep.getPipelineStepId() == null) {
            return null;
        }
        PipelineStep step = stepById.get(runStep.getPipelineStepId());
        if (step == null || step.getPromptText() == null) {
            return null;
        }
        String text = step.getPromptText();
        for (PipelineDataLink link : allIncomingLinks) {
            if (!link.getTargetStepId().equals(runStep.getPipelineStepId())) {
                continue;
            }
            Long sourceRunStepId = runStepIdByPipelineStepId.get(link.getSourceStepId());
            String value = sourceRunStepId != null
                    ? reportedValues.getOrDefault(sourceRunStepId + ":" + link.getSourceOutputId(), "")
                    : "";
            text = text.replace("{{data:" + link.getToken() + "}}", value);
        }
        return text;
    }
```

- [ ] **Step 8: Run the new and existing tests, and confirm the whole module still builds**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineRunServiceTest' --tests 'ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest'`
Expected: PASS (all tests, old and new - including the minimal `PipelineMcpTools.java`/`PipelineMcpToolsTest.java` compile fixes from Step 6)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunUnknownOutputException.java \
        src/main/java/ru/iuribabalin/memorymcp/ui/ApiExceptionHandler.java \
        src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java \
        src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java
git commit -m "feat: resolve data-link tokens into resolvedInstructionText at run time"
```

---

### Task 4: MCP tool - `pipeline_run_step_update` gains `outputsJson`

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java`

**Interfaces:**
- Consumes: `PipelineRunService.updateStep(Long, int, PipelineRunStep.Status, String, String, String)` (Task 3's new 6-arg signature).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void runStepUpdatePassesOutputsJsonThrough() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, 1, List.of());
        when(pipelineRunService.updateStep(1L, 0, PipelineRunStep.Status.DONE, "ok", "success", "{\"summary\":\"x\"}")).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStepUpdate(1L, 0, PipelineRunStep.Status.DONE, "ok", "success", "{\"summary\":\"x\"}");

        assertThat(result).isEqualTo(runDetail);
    }
```

Task 3 already updated this test's inner `pipelineRunService.updateStep(...)` mock stub to 6 args
(hardcoded trailing `null`) as part of its minimal compile fix - the only thing left is the outer
`pipelineMcpTools.pipelineRunStepUpdate(...)` call, still 5-arg because the tool's own public
signature hasn't changed yet. Update `runStepUpdateDelegatesAndRecordsUsage` so both calls are
6-arg:

```java
    @Test
    void runStepUpdateDelegatesAndRecordsUsage() {
        when(settingsService.isEnabled(SettingsService.PIPELINES_ENABLED)).thenReturn(true);
        PipelineRunDetail runDetail = new PipelineRunDetail(1L, 1L, "config-diff", PipelineRun.Status.RUNNING, "{}", Instant.now(), null, null, 1, List.of());
        when(pipelineRunService.updateStep(1L, 0, PipelineRunStep.Status.DONE, "ok", "success", null)).thenReturn(runDetail);

        PipelineRunDetail result = pipelineMcpTools.pipelineRunStepUpdate(1L, 0, PipelineRunStep.Status.DONE, "ok", "success", null);

        assertThat(result).isEqualTo(runDetail);
        assertThat(result.currentStepOrderIndex()).isEqualTo(1);
        verify(usageEventRecorder).record(ru.iuribabalin.memorymcp.entity.UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, "1", null, null, null);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest'`
Expected: FAIL - compile error, `pipelineRunStepUpdate` doesn't accept 6 arguments yet.

- [ ] **Step 3: Implement the MCP tool change**

```java
    @McpTool(name = "pipeline_run_step_update",
            description = "Report the outcome of one pipeline run step after doing its work: RUNNING when you start " +
                    "it, then DONE or FAILED when you finish, or SKIPPED if the user told you to skip it. Include a " +
                    "short note describing what you did or why it failed. If pipeline_get showed this step has more " +
                    "than one route, pass 'outcome' matching one of its route keys when reporting DONE so the run " +
                    "advances down the right branch. If pipeline_get showed this step has 'outputs', pass " +
                    "'outputsJson' as a JSON object of those names to their values, e.g. {\"summary\": \"...\"} - " +
                    "later steps that wire this step's output into their prompt need it. Check the returned " +
                    "currentStepOrderIndex for what to do next - null means every path from here has ended, call " +
                    "pipeline_run_complete. On FAILED, stop and ask the user how to proceed before calling this " +
                    "again - do not silently continue to the next step.")
    public PipelineRunDetail pipelineRunStepUpdate(
            @McpToolParam(description = "The run id, from pipeline_run_start", required = true) Long runId,
            @McpToolParam(description = "0-based index of the step in the run's step list", required = true) Integer orderIndex,
            @McpToolParam(description = "New status: RUNNING, DONE, FAILED, or SKIPPED", required = true) PipelineRunStep.Status status,
            @McpToolParam(description = "Short summary of what happened for this step", required = false) String note,
            @McpToolParam(description = "This step's outcome - only needed when pipeline_get showed the step has more than one route; must exactly match one of that step's outcome keys", required = false) String outcome,
            @McpToolParam(description = "JSON object of this step's output values keyed by name, e.g. {\"summary\": \"...\"} - only needed for names pipeline_get listed under this step's 'outputs'", required = false) String outputsJson) {
        requireEnabled();
        PipelineRunDetail run = pipelineRunService.updateStep(runId, orderIndex, status, note, outcome, outputsJson);
        usageEventRecorder.record(UsageEvent.Action.PIPELINE_RUN_STEP_UPDATE, String.valueOf(runId), null, null, null);
        return run;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest'`
Expected: PASS

- [ ] **Step 5: Run the full backend test suite to confirm no regressions anywhere**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java \
        src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java
git commit -m "feat: pipeline_run_step_update accepts outputsJson"
```

---

### Task 5: `.claude/skills/pipelines/SKILL.md` - teach the skill about outputs and resolved text

**Files:**
- Modify: `.claude/skills/pipelines/SKILL.md`

**Interfaces:**
- Consumes: `outputs` field on `pipeline_get`'s step view (Task 2), `resolvedInstructionText` field on run-step views (Task 3), `outputsJson` param on `pipeline_run_step_update` (Task 4) - all by name, no code interface.

- [ ] **Step 1: Update step 5 of the skill's numbered list** to read `resolvedInstructionText` instead of `instructionText` once a run exists, and report `outputsJson` when a step declares outputs. Replace this bullet inside step 5:

```markdown
   - Substitute `{{paramName}}` in `instructionText` with the parameter values you collected.
```

with:

```markdown
   - Use `resolvedInstructionText` from the run response (not `instructionText` from `pipeline_get`)
     as this step's actual instructions - the server has already substituted any `{{data:...}}`
     tokens with values earlier steps reported, alongside your own `{{paramName}}` substitution.
```

Add a new bullet after the existing "Check that step's `routes`..." bullet in step 5:

```markdown
   - Check that step's `outputs` (from `pipeline_get`, step 1). If non-empty, decide the values for
     each declared name and pass them as `outputsJson` on `pipeline_run_step_update` - a JSON object
     like `{"summary": "..."}`. Skip this if `outputs` is empty for that step.
```

- [ ] **Step 2: Read the file back and sanity-check the numbered list still reads coherently top to bottom** (no code to run - this is documentation).

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/pipelines/SKILL.md
git commit -m "docs: teach the pipelines skill about output pins and resolvedInstructionText"
```

---

### Task 6: Frontend types - `PipelineUpsertStep`/`PipelineStepView`/`PipelineRunStepView` gain the new fields

**Files:**
- Modify: `ui/src/api/types.ts`

**Interfaces:**
- Produces: `PipelineOutputView{id, name}`, `PipelineUpsertOutput{name}`, `PipelineDataLinkView{id, token, sourceOutputName, targetStepOrderIndex, targetStepTitle}`, `PipelineUpsertDataLink{token, sourceOutputName, targetStepIndex}` types; `PipelineStepView.outputs/dataLinksOut`, `PipelineUpsertStep.outputs/dataLinksOut`, `PipelineRunStepView.resolvedInstructionText`. Tasks 7-9 import these exact names.

- [ ] **Step 1: Add the new interfaces and extend the existing ones**

```typescript
export interface PipelineOutputView {
  id: number
  name: string
}

export interface PipelineUpsertOutput {
  name: string
}

export interface PipelineDataLinkView {
  id: number
  token: string
  sourceOutputName: string
  targetStepOrderIndex: number | null
  targetStepTitle: string | null
}

export interface PipelineUpsertDataLink {
  token: string
  sourceOutputName: string
  targetStepIndex: number | null
}
```

Extend `PipelineStepView`:

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
  outputs: PipelineOutputView[]
  dataLinksOut: PipelineDataLinkView[]
}
```

Extend `PipelineUpsertStep`:

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
  outputs: PipelineUpsertOutput[]
  dataLinksOut: PipelineUpsertDataLink[]
}
```

Extend `PipelineRunStepView`:

```typescript
export interface PipelineRunStepView {
  id: number
  orderIndex: number
  title: string
  contentType: PipelineStepContentType
  status: PipelineRunStepStatus
  note: string | null
  startedAt: string | null
  finishedAt: string | null
  resolvedInstructionText: string | null
}
```

- [ ] **Step 2: Type-check**

Run: `cd ui && npm run type-check`
Expected: FAIL - `PipelineBuilderView.vue` constructs `PipelineUpsertStep` object literals (in `loadForEdit`/`addStep`) missing the two new required properties.

- [ ] **Step 3: Commit anyway** - `PipelineBuilderView.vue` is fixed in Task 7; this task's deliverable is the type definitions, verified once Task 7 lands. Skip straight to commit since there's no way to get `type-check` green without touching the `.vue` file, which belongs to the next task.

```bash
git add ui/src/api/types.ts
git commit -m "feat(ui): add output pin and data link types"
```

---

### Task 7: Inspector - declare outputs, show wired inputs (no canvas drag yet)

**Files:**
- Modify: `ui/src/views/PipelineBuilderView.vue`

**Interfaces:**
- Consumes: `PipelineUpsertOutput`, `PipelineUpsertDataLink`, `PipelineOutputView`, `PipelineDataLinkView` (Task 6).
- Produces: `steps.value[i].outputs`/`dataLinksOut` kept in sync with the inspector's "Outputs" list editor; a "Wired inputs" read-only list shown under the prompt textarea. Task 8 wires actual canvas drag-and-drop on top of this same local state shape.

- [ ] **Step 1: Fix `loadForEdit` and `addStep` so the app compiles** (this closes out Task 6's type-check failure)

In `loadForEdit`, extend the `steps.value = pipeline.steps.map(...)` mapping:

```typescript
  steps.value = pipeline.steps.map((s) => ({
    title: s.title,
    contentType: s.contentType,
    promptText: s.promptText,
    assetId: s.assetId,
    referenceAssetId: s.referenceAssetId,
    positionX: s.positionX,
    positionY: s.positionY,
    routes: s.routes.map((r) => ({ outcomeKey: r.outcomeKey, targetStepIndex: r.targetStepOrderIndex })),
    outputs: s.outputs.map((o) => ({ name: o.name })),
    dataLinksOut: s.dataLinksOut.map((l) => ({ token: l.token, sourceOutputName: l.sourceOutputName, targetStepIndex: l.targetStepOrderIndex })),
  }))
```

In `addStep`:

```typescript
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
    outputs: [],
    dataLinksOut: [],
  })
}
```

- [ ] **Step 2: Type-check to confirm the fix**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 3: Add output add/remove helpers** next to `removeStep`

```typescript
function addOutput(stepIndex: number) {
  steps.value[stepIndex].outputs.push({ name: '' })
}

function removeOutput(stepIndex: number, outputIndex: number) {
  const removedName = steps.value[stepIndex].outputs[outputIndex].name
  steps.value[stepIndex].outputs.splice(outputIndex, 1)
  // A data link wiring the removed output would silently point at nothing - drop it rather than
  // leave a dangling {{data:...}} token with no declared pin behind it.
  steps.value[stepIndex].dataLinksOut = steps.value[stepIndex].dataLinksOut.filter(
    (link) => link.sourceOutputName !== removedName,
  )
}

function wiredInputsFor(stepIndex: number): { token: string; sourceStepTitle: string; sourceOutputName: string }[] {
  const result: { token: string; sourceStepTitle: string; sourceOutputName: string }[] = []
  steps.value.forEach((step, sourceIndex) => {
    step.dataLinksOut.forEach((link) => {
      if (link.targetStepIndex === stepIndex) {
        result.push({ token: link.token, sourceStepTitle: step.title || `Шаг ${sourceIndex + 1}`, sourceOutputName: link.sourceOutputName })
      }
    })
  })
  return result
}
```

- [ ] **Step 4: Add the "Outputs" editor and "Wired inputs" list to the step inspector template** - inside the `<template v-if="selectedStep && selectedStepIndex !== null">` block, right after the reference-file section:

```html
              <div class="mt-3 text-[12.5px] text-muted">
                <div class="mb-1 flex items-center justify-between">
                  <label class="font-medium">Выходы (пины)</label>
                  <button type="button" class="text-accent" @click="addOutput(selectedStepIndex)">+ Выход</button>
                </div>
                <div v-for="(output, outputIndex) in selectedStep.outputs" :key="outputIndex" class="mb-1 flex items-center gap-2">
                  <input
                    v-model="output.name"
                    placeholder="имя, напр. summary"
                    class="flex-1 rounded-lg border border-border bg-panel px-2 py-1 text-[12px] text-content"
                  />
                  <button type="button" class="text-faint hover:text-red-600" @click="removeOutput(selectedStepIndex, outputIndex)">
                    <AppIcon name="trash" class="size-4" />
                  </button>
                </div>
              </div>
              <div v-if="wiredInputsFor(selectedStepIndex).length" class="mt-3 text-[11.5px] text-faint">
                <p class="mb-1 font-medium text-muted">Подключённые входы:</p>
                <p v-for="input in wiredInputsFor(selectedStepIndex)" :key="input.token">
                  {{ `{{data:${input.token}}}` }} → {{ input.sourceStepTitle }} . {{ input.sourceOutputName }}
                </p>
              </div>
```

- [ ] **Step 5: Manual check against the dev server**

Run: `cd ui && npm run dev`, open a pipeline in the builder, select a step, add an output named `summary`, save, reload the page, select the step again.
Expected: the `summary` output is still there after reload (round-tripped through `createPipeline`/`updatePipeline` → `PipelineDetail.steps[].outputs`).

- [ ] **Step 6: Commit**

```bash
git add ui/src/views/PipelineBuilderView.vue
git commit -m "feat(ui): declare step output pins and show wired inputs in the inspector"
```

---

### Task 8: Custom vue-flow node - drag a wire from an output pin to create a data link

**Files:**
- Create: `ui/src/components/PipelineStepNode.vue`
- Modify: `ui/src/views/PipelineBuilderView.vue`

**Interfaces:**
- Consumes: `steps.value[i].outputs`/`dataLinksOut` (Task 7).
- Produces: dragging from a step's data-output handle to another step's data-input handle pushes a `PipelineUpsertDataLink` into `dataLinksOut` and appends `{{data:<token>}}` to the target step's `promptText`. Task 9 reuses `PipelineStepNode.vue` in read-only mode.

- [ ] **Step 1: Write the custom node component**

The existing box chrome (border/background/padding, selected/status coloring) lives entirely in
the `.pipeline-node*` CSS classes in `ui/src/styles/main.css`, applied via each node's top-level
`class` string - unchanged by switching to a custom node type, since vue-flow still applies that
`class` to the node's outer wrapper element regardless of which component renders its inside. The
custom component below only renders *content* (label + output pins) - it must not add its own
border/background/padding, or the box would be styled twice.

```vue
<!-- ui/src/components/PipelineStepNode.vue -->
<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

defineProps<{
  data: {
    label: string
    outputs: { name: string }[]
  }
}>()
</script>

<template>
  <div>
    <Handle id="data-in" type="target" :position="Position.Left" class="!h-2.5 !w-2.5 !bg-sky-500" />
    <Handle id="route" type="source" :position="Position.Right" class="!h-2.5 !w-2.5 !bg-content" />

    <span>{{ data.label }}</span>

    <div v-for="(output, index) in data.outputs" :key="output.name" class="relative mt-1 text-[10.5px] text-muted">
      {{ output.name }}
      <Handle
        :id="`output-${output.name}`"
        type="source"
        :position="Position.Right"
        class="!h-2 !w-2 !bg-emerald-500"
        :style="{ top: `${8 + (index + 1) * 16}px` }"
      />
    </div>
  </div>
</template>
```

- [ ] **Step 2: Register the custom node type and branch `onConnect` in `PipelineBuilderView.vue`**

Add to the imports:

```typescript
import PipelineStepNode from '@/components/PipelineStepNode.vue'
```

Change `flowNodes` to carry `type` + a `data` object for the custom component, keeping the
existing top-level `class` field exactly as-is (it still drives `pipeline-node`/
`pipeline-node-selected`/`pipeline-node-end` styling on the wrapper, unchanged):

```typescript
const flowNodes = computed(() => [
  ...steps.value.map((step, index) => ({
    id: String(index),
    type: 'pipelineStep',
    position: { x: step.positionX, y: step.positionY },
    class: selectedStepIndex.value === index ? 'pipeline-node pipeline-node-selected' : 'pipeline-node',
    data: { label: step.title || `Шаг ${index + 1}`, outputs: step.outputs },
  })),
  {
    id: END_NODE_ID,
    type: 'pipelineStep',
    position: endPosition.value,
    class: 'pipeline-node pipeline-node-end',
    data: { label: 'Конец рана', outputs: [] },
  },
])
```

This only replaces the old `label: ...` field with `type: 'pipelineStep', data: { label: ..., outputs: ... }` - the `class` field and its logic are untouched, so the existing selected-node highlight keeps working exactly as before.

Add data-link edges to `flowEdges`:

```typescript
const flowEdges = computed(() => [
  ...steps.value.flatMap((step, index) =>
    step.routes.map((route) => ({
      id: edgeId(index, route),
      source: String(index),
      sourceHandle: 'route',
      target: route.targetStepIndex === null ? END_NODE_ID : String(route.targetStepIndex),
      targetHandle: 'data-in',
      label: route.outcomeKey ?? '(по умолчанию)',
    })),
  ),
  ...steps.value.flatMap((step, index) =>
    step.dataLinksOut.map((link) => ({
      id: `data-${link.token}`,
      source: String(index),
      sourceHandle: `output-${link.sourceOutputName}`,
      target: String(link.targetStepIndex),
      targetHandle: 'data-in',
      class: 'pipeline-data-edge',
      style: { strokeDasharray: '4 4', stroke: '#10b981' },
    })),
  ),
])
```

Change `onConnect` to branch on the handle used:

```typescript
function onConnect(connection: { source: string; target: string; sourceHandle?: string | null; targetHandle?: string | null }) {
  const sourceIndex = Number(connection.source)
  if (connection.sourceHandle && connection.sourceHandle.startsWith('output-')) {
    const sourceOutputName = connection.sourceHandle.slice('output-'.length)
    const targetIndex = Number(connection.target)
    const token = crypto.randomUUID()
    steps.value[sourceIndex].dataLinksOut.push({ token, sourceOutputName, targetStepIndex: targetIndex })
    const target = steps.value[targetIndex]
    target.promptText = `${target.promptText ?? ''}\n{{data:${token}}}`
    selectedStepIndex.value = null
    selectedEdge.value = null
    return
  }
  const targetIndex = connection.target === END_NODE_ID ? null : Number(connection.target)
  steps.value[sourceIndex].routes.push({ outcomeKey: null, targetStepIndex: targetIndex })
  selectedStepIndex.value = null
  selectedEdge.value = { stepIndex: sourceIndex, routeIndex: steps.value[sourceIndex].routes.length - 1 }
}
```

Register the custom node type on the `<VueFlow>` element:

```html
            <VueFlow
              :nodes="flowNodes"
              :edges="flowEdges"
              :node-types="{ pipelineStep: PipelineStepNode }"
              :nodes-connectable="true"
              fit-view-on-init
              @node-drag-stop="onNodeDragStop"
              @node-click="onNodeClick"
              @edge-click="onEdgeClick"
              @connect="onConnect"
            />
```

`vue-flow`'s `:node-types` prop takes a plain object of `{ typeName: Component }` - no wrapper needed for a plain `.vue` SFC import.

- [ ] **Step 3: Type-check**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 4: Manual check against the dev server**

Run: `cd ui && npm run dev`. Open the builder for a pipeline with 2+ steps, add an output pin to step 1, drag from that pin's small handle to step 2.
Expected: a dashed green edge appears between the pin and step 2; step 2's prompt textarea now contains a `{{data:<uuid>}}` line; the "Wired inputs" list under step 2's prompt shows the new entry. Save, reload, re-select step 2 - the wire and prompt text persist.

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/PipelineStepNode.vue ui/src/views/PipelineBuilderView.vue
git commit -m "feat(ui): drag a wire from an output pin to create a data link"
```

---

### Task 9: Read-only views render data-link edges and resolved run text

**Files:**
- Modify: `ui/src/views/PipelineView.vue`
- Modify: `ui/src/views/PipelineRunView.vue`

**Interfaces:**
- Consumes: `PipelineStepNode.vue` (Task 8), `PipelineDetail.PipelineStepView.dataLinksOut` (Task 2).

Both files currently build nodes as plain `{id, position, label, class}` objects (no `data`, no
custom component) and edges from `step.routes` only (falling back to an implicit `orderIndex ->
orderIndex+1` chain when no step has any route). Neither view currently renders step instruction
text anywhere (confirmed - `PipelineView.vue`/`PipelineRunView.vue` only ever show `title`/`note` in
the node label), so there is nothing to change there; this task only adds the custom node type and
data-link edges, mirroring Task 8's `PipelineBuilderView.vue` change exactly.

- [ ] **Step 1: `PipelineView.vue`** - add the import, switch `flowNodes` to the custom type, add data-link edges to `flowEdges`

```typescript
import PipelineStepNode from '@/components/PipelineStepNode.vue'
```

```typescript
const flowNodes = computed(() => {
  if (!pipeline.value) return []
  const steps = pipeline.value.steps
  const positions = stepPositions.value
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => positions.get(s.orderIndex)!.x)) : 0
  return [
    ...steps.map((step) => ({
      id: String(step.orderIndex),
      type: 'pipelineStep',
      position: positions.get(step.orderIndex)!,
      class: 'pipeline-node',
      data: { label: `${step.orderIndex + 1}. ${step.title}`, outputs: step.outputs },
    })),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      class: 'pipeline-node pipeline-node-end',
      data: { label: 'Конец рана', outputs: [] },
    },
  ]
})
```

Add data-link edges after the existing `return steps.flatMap(...)` route-edges branch inside `flowEdges` (both the implicit-chain branch and the routes branch still `return` as today - only the final `return` in the routes branch gains a spread of data-link edges):

```typescript
  return [
    ...steps.flatMap((step) =>
      step.routes.map((route) => ({
        id: `${step.orderIndex}-${route.outcomeKey ?? 'default'}-${route.targetStepOrderIndex ?? END_NODE_ID}`,
        source: String(step.orderIndex),
        target: route.targetStepOrderIndex === null ? END_NODE_ID : String(route.targetStepOrderIndex),
        label: route.outcomeKey ?? '(по умолчанию)' as string | undefined,
      })),
    ),
    ...steps.flatMap((step) =>
      step.dataLinksOut.map((link) => ({
        id: `data-${link.token}`,
        source: String(step.orderIndex),
        sourceHandle: `output-${link.sourceOutputName}`,
        target: String(link.targetStepOrderIndex),
        targetHandle: 'data-in',
        class: 'pipeline-data-edge',
        style: { strokeDasharray: '4 4', stroke: '#10b981' },
      })),
    ),
  ]
```

Register the node type on the template's `<VueFlow>`:

```html
          <VueFlow :nodes="flowNodes" :edges="flowEdges" :node-types="{ pipelineStep: PipelineStepNode }" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
```

- [ ] **Step 2: `PipelineRunView.vue`** - the same three changes, keeping its existing per-run status `class` computation untouched

```typescript
import PipelineStepNode from '@/components/PipelineStepNode.vue'
```

```typescript
const flowNodes = computed(() => {
  if (!pipeline.value || !run.value) return []
  const runStepByOrderIndex = new Map(run.value.steps.map((s) => [s.orderIndex, s]))
  const steps = pipeline.value.steps
  const positions = stepPositions.value
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => positions.get(s.orderIndex)!.x)) : 0
  const isCurrent = (orderIndex: number) => run.value!.currentStepOrderIndex === orderIndex
  return [
    ...steps.map((step) => {
      const runStep = runStepByOrderIndex.get(step.orderIndex)
      const statusClass = runStep ? STATUS_CLASS[runStep.status] : 'pipeline-node pipeline-node-not-reached'
      return {
        id: String(step.orderIndex),
        type: 'pipelineStep',
        position: positions.get(step.orderIndex)!,
        class: isCurrent(step.orderIndex) ? `${statusClass} pipeline-node-selected` : statusClass,
        data: { label: `${step.orderIndex + 1}. ${step.title}${runStep?.note ? ` — ${runStep.note}` : ''}`, outputs: step.outputs },
      }
    }),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      class: run.value.currentStepOrderIndex === null ? 'pipeline-node pipeline-node-end pipeline-node-done' : 'pipeline-node pipeline-node-end',
      data: { label: 'Конец рана', outputs: [] },
    },
  ]
})
```

Apply the identical `flowEdges` data-link-edge addition from Step 1 (the function body is byte-for-byte the same in this file today - same change, same code).

```html
    <div v-else-if="run && pipeline" class="h-[420px] overflow-hidden rounded-xl border border-border bg-elevated">
      <VueFlow :nodes="flowNodes" :edges="flowEdges" :node-types="{ pipelineStep: PipelineStepNode }" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
    </div>
```

- [ ] **Step 3: Type-check**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 4: Manual check against the dev server**

Run: `cd ui && npm run dev`. Open the read-only pipeline view and a run view for the pipeline built in Task 8's manual check.
Expected: the dashed green data-link edge is visible in both read-only graphs, in the same position relative to the output pin as it was in the builder; node box styling (border/background/selected/status colors) looks identical to before this task.

- [ ] **Step 5: Commit**

```bash
git add ui/src/views/PipelineView.vue ui/src/views/PipelineRunView.vue
git commit -m "feat(ui): render data-link edges in the read-only pipeline and run views"
```
