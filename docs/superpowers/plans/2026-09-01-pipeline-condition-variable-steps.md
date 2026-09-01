# Pipeline Condition/Variable step kinds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new pipeline step kinds the server auto-executes with no Claude round trip - `CONDITION` (compares an incoming data-link value to a literal and picks a `true`/`false` branch, rendered as a diamond) and `VARIABLE` (publishes an author-supplied literal as a named output).

**Architecture:** `PipelineStep.ContentType` gains `CONDITION`/`VARIABLE`; two new nullable columns hold a Condition's operator/comparand. `PipelineRunService` gains an auto-advance loop that walks forward through consecutive Condition/Variable steps after every pointer update, reusing the *existing* `resolveNextOrderIndex` outcome-matching logic unchanged - Condition just computes its own outcome server-side instead of receiving it from Claude. `PipelineService` adds kind-specific save-time validation. The frontend adds an add-step-kind menu, kind-specific inspector fields, and a diamond shape for Condition nodes.

**Tech Stack:** Spring Boot (Java 25), JPA/Hibernate, PostgreSQL/Flyway, Vue 3 + TypeScript, `@vue-flow/core`.

**Spec:** `docs/superpowers/specs/2026-09-01-pipeline-condition-variable-steps-design.md`

## Global Constraints

- `EQUALS` compares raw strings; `GREATER_THAN`/`LESS_THAN`/`GREATER_OR_EQUAL`/`LESS_OR_EQUAL` parse both sides as `double` and evaluate to `false` (never throw) if either side fails to parse.
- A Condition step: exactly one incoming data link, exactly two routes with `outcomeKey` set exactly `{"true", "false"}`, no default route.
- A Variable step: exactly one declared output, `promptText` non-blank (its literal value), zero or exactly one route and that route must have `outcomeKey == null` (no named routes).
- The auto-advance loop relies on `orderIndex` values being contiguous `0..n-1` in save order - already true elsewhere in this codebase (`replaceParametersAndSteps`'s `stepIndex++`).
- Only `Condition` gets a diamond shape; `Variable` stays rectangular.
- Backend tests: JUnit 5 + AssertJ, `@SpringBootTest @Transactional` against the local Postgres (docker-compose, must be running). Run with `./gradlew test --tests '<FullyQualifiedClassName>'`.
- Frontend has no test runner - verify with `cd ui && npm run type-check` and `npx vite build`; no browser tooling is available in this environment for manual UI verification, note that honestly rather than skip it silently.
- Follow existing code style exactly: constructor injection, no Lombok, Jackson via `tools.jackson.databind.*`.

---

### Task 1: Data model - migration, `PipelineStep` entity fields, new repository method

**Files:**
- Create: `src/main/resources/db/migration/V16__add_pipeline_step_condition_fields.sql`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepository.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepositoryTest.java`

**Interfaces:**
- Produces: `PipelineStep.ContentType` gains `CONDITION, VARIABLE`; new `PipelineStep.ConditionOperator { EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, LESS_OR_EQUAL }`; `PipelineStep.getConditionOperator()/setConditionOperator()/getConditionValue()/setConditionValue()`; `PipelineRunStepRepository.findByRunIdAndPipelineStepId(Long runId, Long pipelineStepId): Optional<PipelineRunStep>`. Tasks 2 and 3 depend on these exact names.

- [ ] **Step 1: Write the migration**

```sql
-- src/main/resources/db/migration/V16__add_pipeline_step_condition_fields.sql
ALTER TABLE pipeline_steps
    ADD COLUMN condition_operator VARCHAR(20),
    ADD COLUMN condition_value    VARCHAR(500);
```

- [ ] **Step 2: Update the `PipelineStep` entity**

Change the `ContentType` enum and add the new enum + fields + accessors:

```java
    public enum ContentType { PROMPT, MD_FILE, CONDITION, VARIABLE }

    public enum ConditionOperator { EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, LESS_OR_EQUAL }
```

Add these fields after `referenceAssetId` (before `positionX`):

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "condition_operator", length = 20)
    private ConditionOperator conditionOperator;

    @Column(name = "condition_value", length = 500)
    private String conditionValue;
```

Add matching getters/setters after `getReferenceAssetId()`/`setReferenceAssetId()`:

```java
    public ConditionOperator getConditionOperator() {
        return conditionOperator;
    }

    public void setConditionOperator(ConditionOperator conditionOperator) {
        this.conditionOperator = conditionOperator;
    }

    public String getConditionValue() {
        return conditionValue;
    }

    public void setConditionValue(String conditionValue) {
        this.conditionValue = conditionValue;
    }
```

- [ ] **Step 3: Add the new repository method**

```java
// src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepository.java
package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;

import java.util.List;
import java.util.Optional;

public interface PipelineRunStepRepository extends JpaRepository<PipelineRunStep, Long> {
    List<PipelineRunStep> findByRunIdOrderByOrderIndexAsc(Long runId);
    Optional<PipelineRunStep> findByRunIdAndOrderIndex(Long runId, int orderIndex);
    Optional<PipelineRunStep> findByRunIdAndPipelineStepId(Long runId, Long pipelineStepId);
}
```

- [ ] **Step 4: Write a failing test proving the new column/enum values round-trip**

```java
// src/test/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepositoryTest.java
package ru.iuribabalin.memorymcp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.iuribabalin.memorymcp.entity.Pipeline;
import ru.iuribabalin.memorymcp.entity.PipelineRun;
import ru.iuribabalin.memorymcp.entity.PipelineRunStep;
import ru.iuribabalin.memorymcp.entity.PipelineStep;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PipelineRunStepRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepository;
    @Autowired
    private PipelineStepRepository pipelineStepRepository;
    @Autowired
    private PipelineRunRepository pipelineRunRepository;
    @Autowired
    private PipelineRunStepRepository pipelineRunStepRepository;

    @Test
    void savesAndReadsBackConditionFieldsAndFindsByPipelineStepId() {
        Pipeline pipeline = new Pipeline();
        pipeline.setSlug("condition-repo-test");
        pipeline.setName("Condition repo test");
        pipeline.setCreatedAt(Instant.now());
        pipeline.setUpdatedAt(Instant.now());
        pipeline = pipelineRepository.save(pipeline);

        PipelineStep step = new PipelineStep();
        step.setPipelineId(pipeline.getId());
        step.setOrderIndex(0);
        step.setTitle("Check score");
        step.setContentType(PipelineStep.ContentType.CONDITION);
        step.setConditionOperator(PipelineStep.ConditionOperator.GREATER_THAN);
        step.setConditionValue("10");
        step = pipelineStepRepository.save(step);

        assertThat(step.getContentType()).isEqualTo(PipelineStep.ContentType.CONDITION);
        assertThat(step.getConditionOperator()).isEqualTo(PipelineStep.ConditionOperator.GREATER_THAN);
        assertThat(step.getConditionValue()).isEqualTo("10");

        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipeline.getId());
        run.setStatus(PipelineRun.Status.RUNNING);
        run.setStartedAt(Instant.now());
        run = pipelineRunRepository.save(run);

        PipelineRunStep runStep = new PipelineRunStep();
        runStep.setRunId(run.getId());
        runStep.setPipelineStepId(step.getId());
        runStep.setOrderIndex(0);
        runStep.setTitle(step.getTitle());
        runStep.setContentType(step.getContentType());
        runStep.setStatus(PipelineRunStep.Status.PENDING);
        runStep = pipelineRunStepRepository.save(runStep);

        assertThat(pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), step.getId()))
                .contains(runStep);
        assertThat(pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), -1L)).isEmpty();
    }
}
```

- [ ] **Step 5: Run it to confirm it fails before the migration/entity changes exist**

Run: `docker compose up -d postgres && ./gradlew test --tests 'ru.iuribabalin.memorymcp.repository.PipelineRunStepRepositoryTest'`
Expected: FAIL - compile error (`ConditionOperator`/`setConditionOperator`/`findByRunIdAndPipelineStepId` don't exist yet) or a Flyway/Hibernate schema error.

- [ ] **Step 6: Nothing further to implement** - Steps 1-3 are the implementation; re-run the test.

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.repository.PipelineRunStepRepositoryTest'`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V16__add_pipeline_step_condition_fields.sql \
        src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java \
        src/main/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepository.java \
        src/test/java/ru/iuribabalin/memorymcp/repository/PipelineRunStepRepositoryTest.java
git commit -m "feat: add Condition/Variable step kinds to PipelineStep (V16 migration)"
```

---

### Task 2: `PipelineService` - authoring, validation, persistence, read-back

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java` (existing `StepRequest(...)` call sites need updating to compile)

**Interfaces:**
- Consumes: `PipelineStep.ConditionOperator` (Task 1).
- Produces: `PipelineUpsertRequest.StepRequest.conditionOperator()/conditionValue()` (two new trailing fields, 12 args total now); `PipelineDetail.PipelineStepView.conditionOperator()/conditionValue()`. Task 3 doesn't touch these DTOs directly but the round trip they establish is what Task 3's engine reads back via the `PipelineStep` entity.

- [ ] **Step 1: Extend `PipelineUpsertRequest.StepRequest`**

```java
    public record StepRequest(
            String title, PipelineStep.ContentType contentType, String promptText,
            Long assetId, Long referenceAssetId, double positionX, double positionY,
            List<RouteRequest> routes, List<OutputRequest> outputs, List<DataLinkRequest> dataLinksOut,
            PipelineStep.ConditionOperator conditionOperator, String conditionValue) {

        public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
        }

        public record OutputRequest(String name) {
        }

        public record DataLinkRequest(String token, String sourceOutputName, Integer targetStepIndex) {
        }
    }
```

- [ ] **Step 2: Extend `PipelineDetail.PipelineStepView`**

```java
    public record PipelineStepView(
            Long id, int orderIndex, String title, PipelineStep.ContentType contentType,
            String promptText, Long assetId, Long referenceAssetId,
            double positionX, double positionY, List<RouteView> routes,
            List<OutputView> outputs, List<DataLinkView> dataLinksOut,
            PipelineStep.ConditionOperator conditionOperator, String conditionValue) {

        public record RouteView(String outcomeKey, Integer targetStepOrderIndex) {
        }

        public record OutputView(Long id, String name) {
        }

        public record DataLinkView(Long id, String token, String sourceOutputName,
                                    Integer targetStepOrderIndex, String targetStepTitle) {
        }
    }
```

- [ ] **Step 3: Run the build to find every call site the compiler flags**

Run: `./gradlew compileJava compileTestJava`
Expected: FAIL - constructor argument-count errors in `PipelineService.java` (`toDetail`/`replaceParametersAndSteps` build these records) and in `PipelineServiceTest.java`/`PipelineRunServiceTest.java` (every `new PipelineUpsertRequest.StepRequest(...)` call - there are 29 in `PipelineServiceTest.java` and 13 in `PipelineRunServiceTest.java` today).

- [ ] **Step 4: Fix every flagged test call site** by appending `, null, null` (no condition operator/value) as the two trailing arguments. Example:

```java
new PipelineUpsertRequest.StepRequest("Check history", PipelineStep.ContentType.PROMPT, "Diff {{folder}}",
        null, null, 0, 0, List.of(), List.of(), List.of(), null, null)
```

Apply the same trailing `, null, null` to every other flagged `StepRequest(...)` call in both files.

Run: `./gradlew compileTestJava`
Expected: still FAIL on `PipelineService.java` itself (not yet updated) - test files should compile clean once `PipelineService.java` compiles too (Step 6).

- [ ] **Step 5: Write the new failing tests in `PipelineServiceTest.java`**

```java
    @Test
    void savesAndReadsBackAConditionStepWithItsIncomingLinkAndBranches() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-1", "Condition pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Score", PipelineStep.ContentType.PROMPT, "score it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("score")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "score", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Check score", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(),
                                PipelineStep.ConditionOperator.GREATER_THAN, "10"),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView condition = detail.steps().get(1);
        assertThat(condition.contentType()).isEqualTo(PipelineStep.ContentType.CONDITION);
        assertThat(condition.conditionOperator()).isEqualTo(PipelineStep.ConditionOperator.GREATER_THAN);
        assertThat(condition.conditionValue()).isEqualTo("10");
        assertThat(condition.routes()).extracting(PipelineDetail.PipelineStepView.RouteView::outcomeKey)
                .containsExactlyInAnyOrder("true", "false");
    }

    @Test
    void rejectsAConditionStepMissingOperatorOrValue() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-2", "Missing operator", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-2", "x", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", null),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidParametersException.class);
    }

    @Test
    void rejectsAConditionStepWithoutExactlyTwoTrueFalseRoutes() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-3", "Wrong routes", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.PROMPT, "a",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("x")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-3", "x", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", null)),
                                List.of(), List.of(),
                                PipelineStep.ConditionOperator.EQUALS, "yes")));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsAConditionStepWithoutExactlyOneIncomingDataLink() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "cond-4", "No incoming link", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", null),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(),
                                PipelineStep.ConditionOperator.EQUALS, "yes")));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void savesAndReadsBackAVariableStepWithItsSingleOutput() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "var-1", "Variable pipeline", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Greeting", PipelineStep.ContentType.VARIABLE, "hello",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("greeting")),
                                List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Use it", PipelineStep.ContentType.PROMPT, "say it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        PipelineDetail detail = pipelineService.create(request, "Tester");

        PipelineDetail.PipelineStepView variable = detail.steps().get(0);
        assertThat(variable.contentType()).isEqualTo(PipelineStep.ContentType.VARIABLE);
        assertThat(variable.promptText()).isEqualTo("hello");
        assertThat(variable.outputs()).extracting(PipelineDetail.PipelineStepView.OutputView::name).containsExactly("greeting");
    }

    @Test
    void rejectsAVariableStepWithoutExactlyOneOutput() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "var-2", "No output", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.VARIABLE, "hello",
                        null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }

    @Test
    void rejectsAVariableStepWithANamedRoute() {
        PipelineUpsertRequest request = new PipelineUpsertRequest(
                "var-3", "Named route on variable", "desc", "pipeline-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("A", PipelineStep.ContentType.VARIABLE, "hello",
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("go", 1)),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("greeting")),
                                List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("B", PipelineStep.ContentType.PROMPT, "b",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
                .isInstanceOf(PipelineInvalidGraphException.class);
    }
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineServiceTest'`
Expected: FAIL - `PipelineService.java` doesn't compile yet.

- [ ] **Step 7: Implement `PipelineService` changes**

Call the new validation from `create` and `update`, right after `validateDataLinks(request.steps())`:

```java
        validateDataLinks(request.steps());
        validateStepKinds(request.steps());
```

Add the validation method (near `validateDataLinks`):

```java
    private void validateStepKinds(List<PipelineUpsertRequest.StepRequest> steps) {
        int n = steps.size();
        int[] incomingDataLinkCount = new int[n];
        for (PipelineUpsertRequest.StepRequest step : steps) {
            for (PipelineUpsertRequest.StepRequest.DataLinkRequest link : step.dataLinksOut()) {
                if (link.targetStepIndex() != null) {
                    incomingDataLinkCount[link.targetStepIndex()]++;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            PipelineUpsertRequest.StepRequest step = steps.get(i);
            if (step.contentType() == PipelineStep.ContentType.CONDITION) {
                validateConditionStep(step, incomingDataLinkCount[i]);
            } else if (step.contentType() == PipelineStep.ContentType.VARIABLE) {
                validateVariableStep(step);
            }
        }
    }

    private void validateConditionStep(PipelineUpsertRequest.StepRequest step, int incomingDataLinkCount) {
        if (step.conditionOperator() == null || step.conditionValue() == null || step.conditionValue().isBlank()) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type CONDITION but is missing a comparison operator/value");
        }
        if (incomingDataLinkCount != 1) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type CONDITION and must have exactly one incoming data link (has "
                            + incomingDataLinkCount + ")");
        }
        Set<String> outcomeKeys = step.routes().stream()
                .map(PipelineUpsertRequest.StepRequest.RouteRequest::outcomeKey)
                .collect(Collectors.toSet());
        if (step.routes().size() != 2 || !outcomeKeys.equals(Set.of("true", "false"))) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type CONDITION and must have exactly two routes with outcome keys 'true' and 'false'");
        }
    }

    private void validateVariableStep(PipelineUpsertRequest.StepRequest step) {
        if (step.promptText() == null || step.promptText().isBlank()) {
            throw new PipelineInvalidParametersException(
                    "Step '" + step.title() + "' is type VARIABLE but has no value");
        }
        if (step.outputs().size() != 1) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type VARIABLE and must declare exactly one output (has "
                            + step.outputs().size() + ")");
        }
        boolean hasNamedRoute = step.routes().stream().anyMatch(r -> r.outcomeKey() != null);
        if (step.routes().size() > 1 || hasNamedRoute) {
            throw new PipelineInvalidGraphException(
                    "Step '" + step.title() + "' is type VARIABLE and can have at most one route, with no outcome key");
        }
    }
```

Update `replaceParametersAndSteps`'s step-building loop to copy the two new fields (add these two lines right after `step.setReferenceAssetId(stepRequest.referenceAssetId());`):

```java
            step.setConditionOperator(stepRequest.conditionOperator());
            step.setConditionValue(stepRequest.conditionValue());
```

Update `toDetail`'s `PipelineStepView` construction to pass the two new fields (append them as the last two constructor arguments, reading from the entity `s`):

```java
                    return new PipelineDetail.PipelineStepView(
                            s.getId(), s.getOrderIndex(), s.getTitle(), s.getContentType(), s.getPromptText(),
                            s.getAssetId(), s.getReferenceAssetId(), s.getPositionX(), s.getPositionY(),
                            pipelineStepRouteRepository.findByStepId(s.getId()).stream()
                                    .map(r -> new PipelineDetail.PipelineStepView.RouteView(
                                            r.getOutcomeKey(),
                                            r.getTargetStepId() != null ? orderIndexById.get(r.getTargetStepId()) : null))
                                    .toList(),
                            stepOutputs.stream()
                                    .map(o -> new PipelineDetail.PipelineStepView.OutputView(o.getId(), o.getName()))
                                    .toList(),
                            pipelineDataLinkRepository.findBySourceStepIdIn(List.of(s.getId())).stream()
                                    .map(link -> new PipelineDetail.PipelineStepView.DataLinkView(
                                            link.getId(), link.getToken(), outputsById.get(link.getSourceOutputId()).getName(),
                                            orderIndexById.get(link.getTargetStepId()), titleById.get(link.getTargetStepId())))
                                    .toList(),
                            s.getConditionOperator(), s.getConditionValue());
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineServiceTest'`
Expected: PASS (all tests, old and new)

- [ ] **Step 9: Run the rest of the pipeline test suite to check for regressions**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineRunServiceTest' --tests 'ru.iuribabalin.memorymcp.ui.PipelineControllerTest' --tests 'ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest'`
Expected: PASS (compiles now that Step 4's call-site fixes are in place)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java \
        src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java \
        src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java
git commit -m "feat: validate and persist Condition/Variable step kinds in PipelineService"
```

---

### Task 3: `PipelineRunService` - auto-execute Condition/Variable steps

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: `PipelineRunStepRepository.findByRunIdAndPipelineStepId` (Task 1), `PipelineStep.getConditionOperator()/getConditionValue()` (Task 1), `PipelineUpsertRequest.StepRequest.conditionOperator()/conditionValue()` persisted by Task 2.
- Produces: `advancePastNonInteractiveSteps`, `executeConditionStep`, `executeVariableStep` (private) - no new public surface; `start()`/`updateStep()`'s existing public signatures are unchanged, only their internal pointer-resolution behavior changes.

- [ ] **Step 1: Write the failing tests in `PipelineRunServiceTest.java`**

```java
    private void createConditionPipeline(String slug, PipelineStep.ConditionOperator operator, String conditionValue) {
        pipelineService.create(new PipelineUpsertRequest(
                slug, "Condition run", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Score", PipelineStep.ContentType.PROMPT, "score it",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("score")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-1", "score", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Check score", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", 3)),
                                List.of(), List.of(), operator, conditionValue),
                        new PipelineUpsertRequest.StepRequest("Deploy", PipelineStep.ContentType.PROMPT, "deploy it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null),
                        new PipelineUpsertRequest.StepRequest("Rollback", PipelineStep.ContentType.PROMPT, "rollback it",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");
    }

    @Test
    void conditionEvaluatingTrueRoutesToTheTrueBranchWithoutClaudeSeeingTheConditionStep() {
        createConditionPipeline("cond-run-1", PipelineStep.ConditionOperator.GREATER_THAN, "10");
        PipelineRunDetail run = pipelineRunService.start("cond-run-1", "{}", "Tester");
        assertThat(run.currentStepOrderIndex()).isZero();

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"20\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
        assertThat(updated.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.DONE);
    }

    @Test
    void conditionEvaluatingFalseRoutesToTheFalseBranch() {
        createConditionPipeline("cond-run-2", PipelineStep.ConditionOperator.GREATER_THAN, "10");
        PipelineRunDetail run = pipelineRunService.start("cond-run-2", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"5\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(3);
    }

    @Test
    void conditionWithUnparseableNumericInputEvaluatesFalse() {
        createConditionPipeline("cond-run-3", PipelineStep.ConditionOperator.GREATER_THAN, "10");
        PipelineRunDetail run = pipelineRunService.start("cond-run-3", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"not-a-number\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(3);
    }

    @Test
    void conditionUsesEqualsOnRawStrings() {
        createConditionPipeline("cond-run-4", PipelineStep.ConditionOperator.EQUALS, "yes");
        PipelineRunDetail run = pipelineRunService.start("cond-run-4", "{}", "Tester");

        PipelineRunDetail updated = pipelineRunService.updateStep(run.id(), 0, PipelineRunStep.Status.DONE, "ok", null, "{\"score\":\"yes\"}");

        assertThat(updated.currentStepOrderIndex()).isEqualTo(2);
    }

    @Test
    void variableAsTheRootStepAutoExecutesBeforeStartReturns() {
        pipelineService.create(new PipelineUpsertRequest(
                "var-run-1", "Variable run", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Greeting", PipelineStep.ContentType.VARIABLE, "hello",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("greeting")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-2", "greeting", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Use it", PipelineStep.ContentType.PROMPT, "say {{data:tok-2}}",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");

        PipelineRunDetail run = pipelineRunService.start("var-run-1", "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(1);
        assertThat(run.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(run.steps().get(1).resolvedInstructionText()).isEqualTo("say hello");
    }

    @Test
    void consecutiveVariableAndConditionStepsAllAutoAdvanceInOneCall() {
        pipelineService.create(new PipelineUpsertRequest(
                "chain-run-1", "Chained auto steps", "desc", "pipeline-run-svc-test-project",
                List.of(),
                List.of(
                        new PipelineUpsertRequest.StepRequest("Set threshold", PipelineStep.ContentType.VARIABLE, "5",
                                null, null, 0, 0, List.of(),
                                List.of(new PipelineUpsertRequest.StepRequest.OutputRequest("threshold")),
                                List.of(new PipelineUpsertRequest.StepRequest.DataLinkRequest("tok-3", "threshold", 1)),
                                null, null),
                        new PipelineUpsertRequest.StepRequest("Check threshold", PipelineStep.ContentType.CONDITION, null,
                                null, null, 0, 0,
                                List.of(new PipelineUpsertRequest.StepRequest.RouteRequest("true", 2),
                                        new PipelineUpsertRequest.StepRequest.RouteRequest("false", null)),
                                List.of(), List.of(), PipelineStep.ConditionOperator.EQUALS, "5"),
                        new PipelineUpsertRequest.StepRequest("Notify", PipelineStep.ContentType.PROMPT, "notify",
                                null, null, 0, 0, List.of(), List.of(), List.of(), null, null))
        ), "Tester");

        PipelineRunDetail run = pipelineRunService.start("chain-run-1", "{}", "Tester");

        assertThat(run.currentStepOrderIndex()).isEqualTo(2);
        assertThat(run.steps().get(0).status()).isEqualTo(PipelineRunStep.Status.DONE);
        assertThat(run.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.DONE);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineRunServiceTest'`
Expected: FAIL - `PipelineRunService.java` doesn't have the auto-advance logic yet, so a Condition/Variable step stays `PENDING` and `currentStepOrderIndex` points at it directly instead of skipping past it.

- [ ] **Step 3: Implement `PipelineRunService` changes**

Add the auto-advance call at the end of `start()`, right before `return toDetail(run, pipeline.getSlug());`:

```java
        advancePastNonInteractiveSteps(run, steps);
        pipelineRunRepository.save(run);
        return toDetail(run, pipeline.getSlug());
```

Change the pointer-update block inside `updateStep()`:

```java
        if ((status == PipelineRunStep.Status.DONE || status == PipelineRunStep.Status.SKIPPED)
                && runStep.getPipelineStepId() != null) {
            run.setCurrentStepOrderIndex(resolveNextOrderIndexForStatus(run.getPipelineId(), runStep.getPipelineStepId(), orderIndex, outcome, status));
            List<PipelineStep> allSteps = pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(run.getPipelineId());
            advancePastNonInteractiveSteps(run, allSteps);
            pipelineRunRepository.save(run);
        }
```

Add the new private methods (near `resolveNextOrderIndex`):

```java
    private void advancePastNonInteractiveSteps(PipelineRun run, List<PipelineStep> orderedSteps) {
        while (run.getCurrentStepOrderIndex() != null) {
            PipelineStep step = orderedSteps.get(run.getCurrentStepOrderIndex());
            if (step.getContentType() == PipelineStep.ContentType.CONDITION) {
                run.setCurrentStepOrderIndex(executeConditionStep(run, step));
            } else if (step.getContentType() == PipelineStep.ContentType.VARIABLE) {
                run.setCurrentStepOrderIndex(executeVariableStep(run, step));
            } else {
                return;
            }
        }
    }

    private Integer executeConditionStep(PipelineRun run, PipelineStep step) {
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.getId(), step.getOrderIndex())
                .orElseThrow(() -> new PipelineRunStepNotFoundException(run.getId(), step.getOrderIndex()));
        String actualValue = resolveConditionInputValue(run, step);
        boolean result = evaluateCondition(step.getConditionOperator(), actualValue, step.getConditionValue());
        String outcome = result ? "true" : "false";
        Instant now = Instant.now();
        runStep.setStatus(PipelineRunStep.Status.DONE);
        runStep.setStartedAt(now);
        runStep.setFinishedAt(now);
        runStep.setNote("Condition evaluated to " + outcome + " (" + actualValue + " " + step.getConditionOperator() + " " + step.getConditionValue() + ")");
        pipelineRunStepRepository.save(runStep);
        return resolveNextOrderIndex(run.getPipelineId(), step.getId(), step.getOrderIndex(), outcome);
    }

    private String resolveConditionInputValue(PipelineRun run, PipelineStep step) {
        List<PipelineDataLink> incoming = pipelineDataLinkRepository.findByTargetStepIdIn(List.of(step.getId()));
        if (incoming.isEmpty()) {
            return "";
        }
        PipelineDataLink link = incoming.get(0);
        return pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), link.getSourceStepId())
                .flatMap(sourceRunStep -> pipelineRunStepOutputRepository.findByRunStepIdAndOutputId(sourceRunStep.getId(), link.getSourceOutputId()))
                .map(PipelineRunStepOutput::getValue)
                .orElse("");
    }

    private boolean evaluateCondition(PipelineStep.ConditionOperator operator, String actualValue, String comparand) {
        if (operator == PipelineStep.ConditionOperator.EQUALS) {
            return actualValue.equals(comparand);
        }
        Double actualNumber = parseNumberOrNull(actualValue);
        Double comparandNumber = parseNumberOrNull(comparand);
        if (actualNumber == null || comparandNumber == null) {
            return false;
        }
        return switch (operator) {
            case GREATER_THAN -> actualNumber > comparandNumber;
            case LESS_THAN -> actualNumber < comparandNumber;
            case GREATER_OR_EQUAL -> actualNumber >= comparandNumber;
            case LESS_OR_EQUAL -> actualNumber <= comparandNumber;
            default -> false;
        };
    }

    private Double parseNumberOrNull(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer executeVariableStep(PipelineRun run, PipelineStep step) {
        PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.getId(), step.getOrderIndex())
                .orElseThrow(() -> new PipelineRunStepNotFoundException(run.getId(), step.getOrderIndex()));
        Instant now = Instant.now();
        runStep.setStatus(PipelineRunStep.Status.DONE);
        runStep.setStartedAt(now);
        runStep.setFinishedAt(now);
        runStep.setNote("Variable set to its configured value");
        pipelineRunStepRepository.save(runStep);

        List<PipelineStepOutput> outputs = pipelineStepOutputRepository.findByStepId(step.getId());
        if (!outputs.isEmpty()) {
            PipelineStepOutput output = outputs.get(0);
            PipelineRunStepOutput runStepOutput = pipelineRunStepOutputRepository
                    .findByRunStepIdAndOutputId(runStep.getId(), output.getId())
                    .orElseGet(PipelineRunStepOutput::new);
            runStepOutput.setRunStepId(runStep.getId());
            runStepOutput.setOutputId(output.getId());
            runStepOutput.setValue(step.getPromptText());
            pipelineRunStepOutputRepository.save(runStepOutput);
        }
        return resolveNextOrderIndex(run.getPipelineId(), step.getId(), step.getOrderIndex(), null);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'ru.iuribabalin.memorymcp.service.PipelineRunServiceTest'`
Expected: PASS (all tests, old and new)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java \
        src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java
git commit -m "feat: auto-execute Condition/Variable steps in PipelineRunService"
```

---

### Task 4: `.claude/skills/pipelines/SKILL.md` - note that some steps auto-execute

**Files:**
- Modify: `.claude/skills/pipelines/SKILL.md`

**Interfaces:**
- Consumes: nothing structural - purely documents behavior established by Task 3.

- [ ] **Step 1: Read the file in full**, then add one clarifying sentence to step 5's intro paragraph (right after "for a non-branching pipeline this is always the next one in order, so nothing changes there."):

```markdown
   Some `orderIndex` values in the step list will never appear as `currentStepOrderIndex` at all -
   Condition and Variable steps execute automatically server-side and are skipped transparently;
   you only ever need to act on the step `currentStepOrderIndex` actually points to.
```

- [ ] **Step 2: Read the file back** to confirm the addition reads coherently in context (no code to run - documentation only).

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/pipelines/SKILL.md
git commit -m "docs: note that Condition/Variable steps auto-execute in the pipelines skill"
```

---

### Task 5: Frontend types

**Files:**
- Modify: `ui/src/api/types.ts`

**Interfaces:**
- Produces: `PipelineConditionOperator` type; `PipelineStepContentType` gains `'CONDITION' | 'VARIABLE'`; `PipelineStepView`/`PipelineUpsertStep` gain `conditionOperator`/`conditionValue`. Tasks 6-7 import these exact names.

- [ ] **Step 1: Extend `PipelineStepContentType` and add the new operator type**

```typescript
export type PipelineStepContentType = 'PROMPT' | 'MD_FILE' | 'CONDITION' | 'VARIABLE'
export type PipelineConditionOperator = 'EQUALS' | 'GREATER_THAN' | 'LESS_THAN' | 'GREATER_OR_EQUAL' | 'LESS_OR_EQUAL'
```

- [ ] **Step 2: Extend `PipelineStepView`**

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
  conditionOperator: PipelineConditionOperator | null
  conditionValue: string | null
}
```

- [ ] **Step 3: Extend `PipelineUpsertStep`** (find it further down the file, alongside `PipelineUpsertRequest`)

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
  conditionOperator: PipelineConditionOperator | null
  conditionValue: string | null
}
```

- [ ] **Step 4: Type-check**

Run: `cd ui && npm run type-check`
Expected: FAIL - `PipelineBuilderView.vue` constructs `PipelineUpsertStep` object literals (in `loadForEdit`/`addStep`) missing the two new required properties. This is expected and fixed by Task 7 - commit anyway, per the same pattern as the data-flow-pins plan's Task 6/7 split.

- [ ] **Step 5: Commit**

```bash
git add ui/src/api/types.ts
git commit -m "feat(ui): add Condition/Variable step types"
```

---

### Task 6: `PipelineStepNode.vue` - diamond shape for Condition steps

**Files:**
- Modify: `ui/src/components/PipelineStepNode.vue`
- Modify: `ui/src/styles/main.css`

**Interfaces:**
- Consumes: `PipelineConditionOperator`/`PipelineStepContentType` (Task 5) only insofar as the caller passes `contentType` through `data` - the component itself just checks `data.contentType === 'CONDITION'`.
- Produces: `PipelineStepNode.vue` accepts a new `data.contentType: string` prop field. Tasks 7 and 8 (the three views that render this component) must all start passing `contentType` in their node `data` objects, or Condition steps silently render as plain rectangles there.

- [ ] **Step 1: Add the diamond CSS class**

```css
/* ui/src/styles/main.css - add after .pipeline-node-not-reached */
.pipeline-node-condition {
  clip-path: polygon(50% 0%, 100% 50%, 50% 100%, 0% 50%);
  padding: 20px 28px;
}
```

The extra padding compensates for the diamond clipping into the corners of the box, so the label
text inside doesn't get visually clipped along with the corners.

- [ ] **Step 2: Update `PipelineStepNode.vue`'s prop type** to accept `contentType`

```vue
<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'

// Declared as the full `NodeProps<Data>` (not just `{ data: ... }`) because vue-flow's
// `:node-types` prop requires each component to be assignable to `NodeComponent`, which expects
// the whole NodeProps shape (id, type, selected, connectable, ...) - a component typed with only
// a `data` prop type-checks fine on its own but fails to satisfy `NodeComponent` when registered.
defineProps<
  NodeProps<{
    label: string
    outputs: { name: string }[]
    contentType: string
  }>
>()
</script>
```

Note: `data.contentType` is typed as a bare `string` here rather than importing
`PipelineStepContentType` - this component doesn't otherwise depend on `@/api/types`, and the only
thing it does with the value is a single `===` comparison, so pulling in the full type isn't
necessary. If a future change needs the narrower type, importing it then is fine.

The template itself doesn't need a shape-conditional wrapper element - the diamond comes entirely
from the CSS class applied to the node's top-level `class` field by the callers (Task 7/8), exactly
like the existing box chrome. Confirm this stays true: `PipelineStepNode.vue`'s own `<template>`
block requires **no changes** beyond the prop type above - re-read the file after Step 2 to confirm
no shape-related markup crept in here by mistake.

- [ ] **Step 3: Type-check**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add ui/src/components/PipelineStepNode.vue ui/src/styles/main.css
git commit -m "feat(ui): add diamond shape CSS for Condition step nodes"
```

---

### Task 7: `PipelineBuilderView.vue` - add-step-kind menu, kind-specific inspector, diamond wiring

**Files:**
- Modify: `ui/src/views/PipelineBuilderView.vue`

**Interfaces:**
- Consumes: `PipelineConditionOperator` (Task 5), the `data.contentType` field `PipelineStepNode.vue` now reads (Task 6).
- Produces: `steps.value[i].contentType/conditionOperator/conditionValue` kept in sync with the new inspector fields; `flowNodes` passes `contentType` through and applies `pipeline-node-condition` in its `class` string for Condition steps. Task 8 mirrors the `flowNodes` `class`/`data` shape in the two read-only views.

- [ ] **Step 1: Fix `loadForEdit` and `addStep` so the app compiles** (closes Task 5's type-check gap)

In `loadForEdit`, extend the `steps.value = pipeline.steps.map(...)` mapping - add two fields to the object literal:

```typescript
    outputs: s.outputs.map((o) => ({ name: o.name })),
    dataLinksOut: s.dataLinksOut.map((l) => ({ token: l.token, sourceOutputName: l.sourceOutputName, targetStepIndex: l.targetStepOrderIndex })),
    conditionOperator: s.conditionOperator,
    conditionValue: s.conditionValue,
  }))
```

Replace `addStep()` with a version taking the kind, seeding kind-specific defaults:

```typescript
function addStep(kind: PipelineStepContentType) {
  const offset = steps.value.length * 220
  const base = {
    title: '',
    contentType: kind,
    promptText: '',
    assetId: null,
    referenceAssetId: null,
    positionX: offset,
    positionY: 200,
    routes: [] as PipelineUpsertRoute[],
    outputs: [] as PipelineUpsertOutput[],
    dataLinksOut: [] as PipelineUpsertDataLink[],
    conditionOperator: null as PipelineConditionOperator | null,
    conditionValue: null as string | null,
  }
  if (kind === 'CONDITION') {
    base.routes = [
      { outcomeKey: 'true', targetStepIndex: null },
      { outcomeKey: 'false', targetStepIndex: null },
    ]
    base.conditionOperator = 'EQUALS'
    base.conditionValue = ''
  } else if (kind === 'VARIABLE') {
    base.outputs = [{ name: 'value' }]
  }
  steps.value.push(base)
}
```

Add the new type imports at the top of the `<script setup>` block, alongside the existing
`import type { ... } from '@/api/types'` block:

```typescript
import type {
  PipelineConditionOperator,
  PipelineParameterType,
  PipelineStepContentType,
  PipelineUpsertDataLink,
  PipelineUpsertOutput,
  PipelineUpsertParameter,
  PipelineUpsertRoute,
  PipelineUpsertStep,
} from '@/api/types'
```

- [ ] **Step 2: Type-check to confirm the fix**

Run: `cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 3: Update `flowNodes` to pass `contentType` and apply the diamond class**

```typescript
const flowNodes = computed(() => [
  ...steps.value.map((step, index) => ({
    id: String(index),
    type: 'pipelineStep',
    position: { x: step.positionX, y: step.positionY },
    class: [
      'pipeline-node',
      step.contentType === 'CONDITION' ? 'pipeline-node-condition' : '',
      selectedStepIndex.value === index ? 'pipeline-node-selected' : '',
    ].filter(Boolean).join(' '),
    data: { label: step.title || `Шаг ${index + 1}`, outputs: step.outputs, contentType: step.contentType },
  })),
  {
    id: END_NODE_ID,
    type: 'pipelineStep',
    position: endPosition.value,
    class: 'pipeline-node pipeline-node-end',
    data: { label: 'Конец рана', outputs: [], contentType: 'PROMPT' },
  },
])
```

- [ ] **Step 4: Replace the "+ Шаг" button with three kind buttons** in the template, right where the existing button sits:

```html
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
          <div class="flex gap-2">
            <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('PROMPT')">+ Prompt</button>
            <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('CONDITION')">+ Condition</button>
            <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('VARIABLE')">+ Variable</button>
          </div>
        </div>
```

- [ ] **Step 5: Branch the inspector panel on `selectedStep.contentType`**

Replace the existing content-type toggle + prompt textarea block (the `<div class="mb-2 flex gap-3 ...">` radio pair and the `<textarea v-if="selectedStep.contentType === 'PROMPT'" ...>`/`<div v-else ...>` pair right after it) with a three-way branch. The `PROMPT`/`MD_FILE` radio-and-textarea pair stays exactly as it is today, gated behind a new outer condition:

```html
              <template v-if="selectedStep.contentType === 'PROMPT' || selectedStep.contentType === 'MD_FILE'">
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
              </template>
              <div v-else-if="selectedStep.contentType === 'CONDITION'" class="mb-2">
                <label class="mb-1 block text-[12.5px] font-medium text-muted">Оператор сравнения</label>
                <select v-model="selectedStep.conditionOperator" class="mb-2 w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content">
                  <option value="EQUALS">равно</option>
                  <option value="GREATER_THAN">больше</option>
                  <option value="LESS_THAN">меньше</option>
                  <option value="GREATER_OR_EQUAL">больше или равно</option>
                  <option value="LESS_OR_EQUAL">меньше или равно</option>
                </select>
                <label class="mb-1 block text-[12.5px] font-medium text-muted">Значение для сравнения</label>
                <input
                  v-model="selectedStep.conditionValue"
                  class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
                  placeholder="напр. 10"
                />
                <p class="mt-2 text-[11.5px] text-faint">
                  Сравнивается со значением, подключённым через входящую связь (data-link) от другого шага.
                  Ветка "true"/"false" выбирается автоматически, без участия Claude.
                </p>
              </div>
              <div v-else-if="selectedStep.contentType === 'VARIABLE'" class="mb-2">
                <label class="mb-1 block text-[12.5px] font-medium text-muted">Значение</label>
                <input
                  v-model="selectedStep.promptText"
                  class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
                  placeholder="напр. hello"
                />
                <p class="mt-2 text-[11.5px] text-faint">
                  Это значение публикуется в единственный output шага автоматически при старте рана, без участия Claude.
                </p>
              </div>
```

The reference-file section right after this stays as-is, but only makes sense for `PROMPT`/
`MD_FILE` steps - wrap its existing `<div class="mt-3 text-[12.5px] text-muted">` (the "Ссылочный
файл" block) in `v-if="selectedStep.contentType === 'PROMPT' || selectedStep.contentType === 'MD_FILE'"`.

For `VARIABLE`, the existing "Выходы (пины)" section stays visible but should not let the author add
a second output or remove the sole one - find the `+ Выход` button and the per-output remove button
in that section and gate them:

```html
                <div class="mb-1 flex items-center justify-between">
                  <label class="font-medium">Выходы (пины)</label>
                  <button
                    v-if="!(selectedStep.contentType === 'VARIABLE' && selectedStep.outputs.length >= 1)"
                    type="button" class="text-accent" @click="addOutput(selectedStepIndex)">+ Выход</button>
                </div>
                <div v-for="(output, outputIndex) in selectedStep.outputs" :key="outputIndex" class="mb-1 flex items-center gap-2">
                  <input
                    v-model="output.name"
                    placeholder="имя, напр. summary"
                    class="flex-1 rounded-lg border border-border bg-panel px-2 py-1 text-[12px] text-content"
                  />
                  <button
                    v-if="selectedStep.contentType !== 'VARIABLE'"
                    type="button" class="text-faint hover:text-red-600" @click="removeOutput(selectedStepIndex, outputIndex)">
                    <AppIcon name="trash" class="size-4" />
                  </button>
                </div>
```

- [ ] **Step 6: Lock the outcome-key field for a Condition step's routes**

Find the route inspector block (`<template v-else-if="selectedRoute">`) and its `<input
v-model="selectedRoute.outcomeKey" ...>` - a Condition step's routes must keep `outcomeKey` fixed to
`"true"`/`"false"`. Change that input to a read-only display when the route belongs to a Condition
step:

```html
              <label class="mb-1 block text-[12.5px] font-medium text-muted">Ключ outcome</label>
              <input
                v-if="steps[selectedEdge!.stepIndex].contentType !== 'CONDITION'"
                v-model="selectedRoute.outcomeKey"
                placeholder="пусто = маршрут по умолчанию"
                class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              />
              <p v-else class="w-full rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content">
                {{ selectedRoute.outcomeKey }}
              </p>
```

- [ ] **Step 7: Extend `normalizedSteps()`'s outcome-key normalization guard** - no change needed here: the existing "empty string -> null" normalization only applies when the input is editable, and a Condition step's `outcomeKey` is never blank (seeded as `'true'`/`'false'` in Step 1 and never user-editable per Step 6), so it always survives normalization unchanged. Confirm this by reading `normalizedSteps()` - no edit required, this step is a verification checkpoint, not a code change.

- [ ] **Step 8: Type-check and build**

Run: `cd ui && npm run type-check && npx vite build`
Expected: PASS

- [ ] **Step 9: Do what verification you can without browser tooling** - no browser automation is available in this environment; note in the report that manual click-through (adding a Condition step, wiring its input, saving, reloading) was not performed and should be checked by a human before this is fully trusted end-to-end.

- [ ] **Step 10: Commit**

```bash
git add ui/src/views/PipelineBuilderView.vue
git commit -m "feat(ui): add Condition/Variable step kinds to the pipeline builder"
```

---

### Task 8: Read-only views pass `contentType` through

**Files:**
- Modify: `ui/src/views/PipelineView.vue`
- Modify: `ui/src/views/PipelineRunView.vue`

**Interfaces:**
- Consumes: `PipelineStepNode.vue`'s new `data.contentType` field (Task 6).

- [ ] **Step 1: `PipelineView.vue`** - add `contentType: step.contentType` to both node `data` objects in `flowNodes`, and add the `pipeline-node-condition` class the same way Task 7 did:

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
      class: step.contentType === 'CONDITION' ? 'pipeline-node pipeline-node-condition' : 'pipeline-node',
      data: { label: `${step.orderIndex + 1}. ${step.title}`, outputs: step.outputs, contentType: step.contentType },
    })),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      class: 'pipeline-node pipeline-node-end',
      data: { label: 'Конец рана', outputs: [], contentType: 'PROMPT' },
    },
  ]
})
```

- [ ] **Step 2: `PipelineRunView.vue`** - the same change, keeping its existing per-run status `class` computation:

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
      const conditionClass = step.contentType === 'CONDITION' ? ' pipeline-node-condition' : ''
      return {
        id: String(step.orderIndex),
        type: 'pipelineStep',
        position: positions.get(step.orderIndex)!,
        class: (isCurrent(step.orderIndex) ? `${statusClass} pipeline-node-selected` : statusClass) + conditionClass,
        data: { label: `${step.orderIndex + 1}. ${step.title}${runStep?.note ? ` — ${runStep.note}` : ''}`, outputs: step.outputs, contentType: step.contentType },
      }
    }),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      class: run.value.currentStepOrderIndex === null ? 'pipeline-node pipeline-node-end pipeline-node-done' : 'pipeline-node pipeline-node-end',
      data: { label: 'Конец рана', outputs: [], contentType: 'PROMPT' },
    },
  ]
})
```

- [ ] **Step 3: Type-check and build**

Run: `cd ui && npm run type-check && npx vite build`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add ui/src/views/PipelineView.vue ui/src/views/PipelineRunView.vue
git commit -m "feat(ui): render Condition steps as diamonds in the read-only pipeline views"
```
