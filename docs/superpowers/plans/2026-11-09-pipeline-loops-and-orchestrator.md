# Pipeline loops and orchestrator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пайплайнам цикл-регион (тело набрасывается рёбрами, возврат делает сервер), самодостаточный цикл с промптом и регион оркестрации, в котором дорогая модель раздаёт работу дешёвым и принимает её.

**Architecture:** Четыре новых типа шага в `PipelineStep.ContentType`. `LOOP_END` и `ORCHESTRATOR_END` указывают на своё начало новой колонкой `region_start_step_id` — одна колонка на обе пары. `LOOP_END` исполняет сервер: он возвращает поток в свой `LOOP` неявным ребром, которое обязано участвовать во всех обходах графа (валидация, рантайм, раскладка) — для этого вводится один общий построитель списка смежности. `PROMPT_LOOP` исполняет Claude, а счётчик итераций ведёт сервер в существующем `arrived_count` через существующий `pipeline_run_step_check`. `ORCHESTRATOR`/`ORCHESTRATOR_END` — сквозные серверные шаги, вся их семантика в скилле; сервер только сообщает агенту, какие шаги лежат в регионе.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL + Flyway (`V23`), JPA; Vue 3 + TypeScript + vue-flow в `ui/`; JUnit 5 + AssertJ + `@SpringBootTest`.

**Spec:** `docs/superpowers/specs/2026-11-09-pipeline-loops-and-orchestrator-design.md`

## Global Constraints

- Следующая миграция — `V23__add_loop_region_and_orchestrator.sql`. Колонки только добавляются; существующие не переименовываются и не удаляются.
- Обе CHECK-констрейнты (`pipeline_steps.content_type`, `pipeline_run_steps.content_type`) перечисляют полный набор: `PROMPT, MD_FILE, CONDITION, VARIABLE, AGENT_TASK, PARALLEL, JOIN, LOOP, WATCHER, LOOP_END, PROMPT_LOOP, ORCHESTRATOR, ORCHESTRATOR_END`. `AGENT_TASK` сохраняется в CHECK, как в V19/V20/V22, хотя в enum его нет.
- Новые конструкторы `PipelineUpsertRequest.StepRequest`, `PipelineDetail.PipelineStepView`, `PipelineRunDetail.PipelineRunStepView` и `PipelineRunStepCheckResult` добавляются **с сохранением всех существующих** (по образцу `/** Pre-V22 shape: no watcher fields. */`). Существующие тесты не правятся.
- Новых MCP-инструментов не добавляется. Меняются только описания существующих.
- Предел итераций у `LOOP` и `PROMPT_LOOP` — 1..100. Попыток у `ORCHESTRATOR` — 1..5.
- Подписи блоков в UI: `LOOP_END` → «Конец цикла», `PROMPT_LOOP` → «Цикл с промптом», `ORCHESTRATOR` → «Оркестратор», `ORCHESTRATOR_END` → «Приёмка».
- Подсказка «кому» (`delegation_hint`): `CHEAPEST` / `BALANCED` / `SELF` / NULL.
- Java-тесты: `./gradlew test --tests "<class>"`. UI: `cd ui && npm run type-check`.

## Review Focus

Пять входов, которые спека подразумевает, но ни один «счастливый путь» не трогает. Тест на каждый добавлен в задачу, владеющую кодом.

1. **`LOOP_END`, указывающий на `LOOP` в другом пайплайне или на удалённый шаг** — сохранение обязано упасть с внятным текстом, а не создать висящий возврат (Task 2).
2. **Два `LOOP_END` на один `LOOP`** — сохранение обязано упасть: иначе рантайм вернётся в цикл дважды за итерацию и счётчик соврёт (Task 2).
3. **Определение шага заменено посреди прогона** (пайплайн сохранили, пока прогон шёл) — `PROMPT_LOOP` без достижимого `maxIterations` обязан закрыться, а не крутиться вечно (Task 4), ровно как watcher получает `DEFAULT_WATCH_TIMEOUT_SECONDS`.
4. **`pipeline_run_step_check` на шаге, который не `WATCHER` и не `PROMPT_LOOP`** — прежняя ошибка `PipelineRunNotAWatcherException` обязана сохраниться, а не превратиться в молчаливый инкремент (Task 4).
5. **`ORCHESTRATOR` без своего `ORCHESTRATOR_END`, и `ORCHESTRATOR_END` внутри фан-аута `PARALLEL`** — оба обязаны падать на сохранении: регион без конца не имеет границы, а внутри развилки директора нет (Task 5).

---

## File Structure

**Сервер**
- Create: `src/main/resources/db/migration/V23__add_loop_region_and_orchestrator.sql`
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineGraph.java` — единственное место, где строится список смежности с неявными возвратами, и единственное место, где считается членство в регионе. Им пользуются и `PipelineService` (валидация), и `PipelineRunService` (рантайм).
- Modify: `entity/PipelineStep.java` (enum + 2 колонки), `entity/PipelineRunStep.java` (ничего, кроме переиспользования `arrived_count`)
- Modify: `dto/PipelineUpsertRequest.java`, `dto/PipelineDetail.java`, `dto/PipelineRunDetail.java`, `dto/PipelineRunStepCheckResult.java`
- Modify: `service/PipelineService.java` (валидация новых типов), `service/PipelineRunService.java` (рантайм), `mcp/PipelineMcpTools.java` (описания)
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`, `.../PipelineRunServiceTest.java`, `.../repository/PipelineRunStepRepositoryTest.java`

**UI**
- Modify: `ui/src/api/types.ts`, `ui/src/lib/pipelineBoard.ts`, `ui/src/lib/pipelineStageLayout.ts`
- Modify: `ui/src/components/PipelineStepNode.vue`, `ui/src/views/PipelineBoardView.vue`, `ui/src/views/PipelineRunView.vue`

**Скилл и примеры**
- Modify: `.claude/skills/pipelines/SKILL.md`
- Create: `docs/examples/pipelines/README.md`, `review-loop.pipeline.json`, `loop-region.pipeline.json`, `orchestrator.pipeline.json`

---

### Task 1: Схема, enum и поля DTO

**Files:**
- Create: `src/main/resources/db/migration/V23__add_loop_region_and_orchestrator.sql`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`

**Interfaces:**
- Produces: `PipelineStep.ContentType.{LOOP_END, PROMPT_LOOP, ORCHESTRATOR, ORCHESTRATOR_END}`; `PipelineStep.DelegationHint.{CHEAPEST, BALANCED, SELF}`; `PipelineStep.getRegionStartStepId()/setRegionStartStepId(Long)`, `getDelegationHint()/setDelegationHint(DelegationHint)`; `StepRequest` и `PipelineStepView` получают два последних поля `Integer regionStartStepIndex, PipelineStep.DelegationHint delegationHint` (в `PipelineStepView` — `regionStartStepOrderIndex`).

- [ ] **Step 1: Write the failing test** в `PipelineServiceTest`

```java
@Test
void savesAndReadsBackLoopRegionAndOrchestratorFields() {
    // LOOP(2 итерации) -> тело(PROMPT) -> LOOP_END(regionStartStepIndex=0); LOOP done -> end
    // плюс PROMPT_LOOP(5) и ORCHESTRATOR(2 попытки) с шагом, у которого delegationHint=CHEAPEST
    PipelineDetail saved = pipelineService.create(request, "Tester");

    assertThat(saved.steps().get(2).contentType()).isEqualTo(PipelineStep.ContentType.LOOP_END);
    assertThat(saved.steps().get(2).regionStartStepOrderIndex()).isEqualTo(0);
    assertThat(saved.steps().get(3).contentType()).isEqualTo(PipelineStep.ContentType.PROMPT_LOOP);
    assertThat(saved.steps().get(3).maxIterations()).isEqualTo(5);
    assertThat(saved.steps().get(5).delegationHint()).isEqualTo(PipelineStep.DelegationHint.CHEAPEST);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: FAIL — компиляция, `ContentType.LOOP_END` не существует.

- [ ] **Step 3: Написать миграцию `V23__add_loop_region_and_orchestrator.sql`**

Две новые колонки на `pipeline_steps`: `region_start_step_id BIGINT` и `delegation_hint VARCHAR(20)` с `CHECK (delegation_hint IS NULL OR delegation_hint IN ('CHEAPEST','BALANCED','SELF'))`. Затем обе CHECK-констрейнты `content_type` пересоздаются с полным набором типов из Global Constraints — на `pipeline_steps` и на `pipeline_run_steps`, по образцу `V22__add_watcher_steps.sql`. Комментарий в файле: `region_start_step_id` у `LOOP_END` указывает на свой `LOOP`, у `ORCHESTRATOR_END` — на свой `ORCHESTRATOR`; FK не ставим, потому что сохранение пайплайна заменяет все строки шагов и id меняются внутри одной транзакции.

- [ ] **Step 4: Расширить enum и сущность**

В `PipelineStep`: четыре значения в `ContentType`, новый `enum DelegationHint { CHEAPEST, BALANCED, SELF }`, поля `@Column(name = "region_start_step_id") Long regionStartStepId` и `@Enumerated(EnumType.STRING) @Column(name = "delegation_hint", length = 20) DelegationHint delegationHint` с геттерами/сеттерами. Обновить javadoc над `ContentType`: `PROMPT / MD_FILE`, PROMPT-режим `CONDITION`, `WATCHER` и `PROMPT_LOOP` выполняет Claude; остальные — сервер.

- [ ] **Step 5: Расширить DTO, сохранив старые конструкторы**

`StepRequest` получает два поля в конец: `Integer regionStartStepIndex, PipelineStep.DelegationHint delegationHint`; существующий 18-аргументный конструктор становится делегирующим с `null, null` и комментарием `/** Pre-V23 shape: no region or delegation fields. */`. То же для `PipelineDetail.PipelineStepView` (`Integer regionStartStepOrderIndex, PipelineStep.DelegationHint delegationHint`). Протащить поля через `PipelineService.create`/`toDetail` и `PipelineExportService` (экспорт собирает `StepRequest` из `PipelineStepView` — поля обязаны переноситься, иначе экспорт теряет регион). Индекс ↔ id: как у `routes.targetStepIndex` — на сохранении `regionStartStepIndex` переводится в id уже сохранённого шага, на чтении обратно в `orderIndex`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V23__add_loop_region_and_orchestrator.sql src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java src/main/java/ru/iuribabalin/memorymcp/dto src/main/java/ru/iuribabalin/memorymcp/service src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java
git commit -m "feat(pipelines): schema and DTO fields for loop regions and the orchestrator"
```

---

### Task 2: `PipelineGraph` и валидация цикла-региона

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineGraph.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java:234-330` (`validateGraph`, `buildExecutionAdjacency`), `:439-480` (`validateStepKinds`)
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (`ContentType.LOOP_END`, `regionStartStepIndex`).
- Produces:
  - `static List<List<Integer>> PipelineGraph.adjacency(List<List<Integer>> routeTargets, List<Integer> implicitReturnTargets)` — по позиции i добавляет к `routeTargets.get(i)` ребро `implicitReturnTargets.get(i)`, если оно не `null`. Чистая функция, без знания о сущностях.
  - `static Set<Integer> PipelineGraph.reachableUntil(int start, List<List<Integer>> adjacency, Set<Integer> stopAt)` — обход от `start`, не входящий в индексы из `stopAt`. Заменяет `reachableBeforeAnyJoin` (вызов: `stopAt` = индексы всех `JOIN`).

- [ ] **Step 1: Write the failing tests** в `PipelineServiceTest`

```java
@Test
void loopEndMustPointAtALoopStep() {
    // LOOP_END с regionStartStepIndex на PROMPT-шаг
    assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
            .isInstanceOf(PipelineInvalidStepException.class)
            .hasMessageContaining("is type LOOP_END and must point at a LOOP step");
}

@Test
void loopEndMustPointAtAnExistingStep() {
    // regionStartStepIndex = 99
    assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
            .isInstanceOf(PipelineInvalidStepException.class)
            .hasMessageContaining("is type LOOP_END and must point at a LOOP step");
}

@Test
void oneLoopTakesAtMostOneLoopEnd() {
    // два LOOP_END, оба regionStartStepIndex = 0
    assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
            .isInstanceOf(PipelineInvalidStepException.class)
            .hasMessageContaining("already has a LOOP_END");
}

@Test
void loopEndMustBeReachableFromItsLoop() {
    // LOOP_END стоит в стороне: из LOOP до него переходами не дойти
    assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
            .isInstanceOf(PipelineInvalidStepException.class)
            .hasMessageContaining("is not reachable from the LOOP");
}

@Test
void loopMustBeClosedEitherByAnEdgeOrByALoopEnd() {
    // LOOP, тело подключено, обратного ребра нет, LOOP_END нет
    assertThatThrownBy(() -> pipelineService.create(request, "Tester"))
            .isInstanceOf(PipelineInvalidStepException.class)
            .hasMessageContaining("is type LOOP and its body never returns");
}

@Test
void loopClosedByALoopEndSavesWithoutABackEdge() {
    // LOOP(2) -> PROMPT -> LOOP_END(->LOOP); LOOP done -> end. Обратного ребра нет.
    PipelineDetail saved = pipelineService.create(request, "Tester");
    assertThat(saved.steps()).hasSize(3);
}

@Test
void loopClosedByADrawnBackEdgeStillSaves() {
    // прежняя форма из PipelineRunServiceTest.createReviewLoopPipeline
    PipelineDetail saved = pipelineService.create(request, "Tester");
    assertThat(saved.steps()).hasSize(3);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: FAIL — все семь: валидации нет, `loopClosedByALoopEnd...` падает на отсутствии `LOOP_END` в `validateStepKinds`.

- [ ] **Step 3: Создать `PipelineGraph` с двумя функциями из Interfaces**

- [ ] **Step 4: Переписать `buildExecutionAdjacency` через `PipelineGraph.adjacency`**

Второй аргумент — список, где у шага типа `LOOP_END` стоит его `regionStartStepIndex`, иначе `null`. Этим неявный возврат попадает и в проверку достижимости data-links, и в проверку циклов. В `validateGraph` цикл, проходящий по неявному ребру, законен так же, как цикл, возвращающийся в `LOOP` нарисованным ребром: условие «ребро ведёт в `LOOP`» уже это покрывает, потому что неявное ребро ведёт именно в `LOOP`. Заменить тело `reachableBeforeAnyJoin` вызовом `PipelineGraph.reachableUntil`.

- [ ] **Step 5: Добавить `validateLoopEndStep` и правило замкнутости в `validateLoopStep`**

`validateLoopEndStep(step, index, steps, incomingDataLinkCount)`: `regionStartStepIndex` не null, в границах списка и указывает на `LOOP` — иначе `"Step '<title>' is type LOOP_END and must point at a LOOP step"`; на этот `LOOP` ещё не указывает другой `LOOP_END` — иначе `"Step '<title>': loop '<loop title>' already has a LOOP_END"`; `LOOP_END` достижим из своего `LOOP` по нарисованным переходам — иначе `"Step '<title>' is not reachable from the LOOP it closes"`; своих маршрутов нет, данных не несёт (ни выходов, ни входящих data-links) — текст по образцу `JOIN`.

В `validateLoopStep` добавить: цикл замкнут, если какой-нибудь шаг имеет маршрут в этот `LOOP` **или** существует `LOOP_END` с `regionStartStepIndex` = индекс этого `LOOP`; иначе `"Step '<title>' is type LOOP and its body never returns: wire its last step back into the loop or close the body with a LOOP_END step"`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/service src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java
git commit -m "feat(pipelines): validate loop regions closed by a LOOP_END step"
```

---

### Task 3: Рантайм цикла-региона

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java:428-520` (`activate`, `loopCycleSteps`), `:509-540` (`executeLoopStep`)
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: Task 2 (`PipelineGraph.adjacency`).
- Produces: `private Integer executeLoopEndStep(PipelineRun run, PipelineStep step, List<PipelineStep> orderedSteps)` — закрывает шаг и возвращает `orderIndex` своего `LOOP`.

- [ ] **Step 1: Write the failing tests** в `PipelineRunServiceTest`

```java
/** LOOP(max) -> «Прогнать тесты» -> «Починить» -> LOOP_END(->LOOP); LOOP done -> «Отчёт». */
private void createLoopRegionPipeline(String slug, int maxIterations) { /* ... */ }

@Test
void loopRegionRunsItsBodyOncePerIterationWithoutADrawnBackEdge() {
    createLoopRegionPipeline("loop-region-1", 2);
    PipelineRunDetail run = pipelineRunService.start("loop-region-1", "{}", "Tester");
    // первая итерация: тело активно от начала
    assertThat(run.currentStepOrderIndex()).isEqualTo(1);
    run = pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, null, null, null);
    run = pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, null, null, null);
    // LOOP_END вернул поток в цикл: вторая итерация снова начинается с шага 1
    assertThat(run.currentStepOrderIndex()).isEqualTo(1);
    assertThat(run.steps().get(1).status()).isEqualTo(PipelineRunStep.Status.PENDING);
    run = pipelineRunService.updateStep(run.id(), 1, PipelineRunStep.Status.DONE, null, null, null);
    run = pipelineRunService.updateStep(run.id(), 2, PipelineRunStep.Status.DONE, null, null, null);
    // лимит исчерпан: вышли на «Отчёт» (orderIndex 4)
    assertThat(run.currentStepOrderIndex()).isEqualTo(4);
}

@Test
void loopRegionBodyStepsReportTheirExecutionCountPerIteration() {
    createLoopRegionPipeline("loop-region-2", 2);
    // после двух итераций executionCount шага тела равен 2
}

@Test
void loopRegionWhoseLoopStartsThePipelineHasTheLoopAsItsRoot() {
    createLoopRegionPipeline("loop-region-3", 1);
    PipelineRunDetail run = pipelineRunService.start("loop-region-3", "{}", "Tester");
    assertThat(run.steps().get(0).contentType()).isEqualTo(PipelineStep.ContentType.LOOP);
    assertThat(run.currentStepOrderIndex()).isEqualTo(1);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest"`
Expected: FAIL — `LOOP_END` попадает в `default` ветку `activate()` и ждёт агента, второй итерации не наступает.

- [ ] **Step 3: Реализовать `executeLoopEndStep`**

Ставит `DONE`/`startedAt`/`finishedAt`, пишет `note` «Возврат в цикл «<заголовок LOOP>»», возвращает `orderIndex` шага, на который указывает `regionStartStepId`. Если `regionStartStepId` не указывает на существующий `LOOP` (определение заменено посреди прогона) — логировать `log.warn` и вернуть `null`, завершив путь, а не бросать: образец — `watchTimeoutSecondsOf`.

- [ ] **Step 4: Включить `LOOP_END` в `activate()` и в `loopCycleSteps()`**

В `switch` добавить `case LOOP_END -> enqueue(queue, executeLoopEndStep(run, step, orderedSteps));`, а сам тип — в список «не ждут агента» рядом с `PARALLEL`/`JOIN`/`LOOP`. В `loopCycleSteps()` список смежности строить через `PipelineGraph.adjacency`, добавляя ребро `LOOP_END → LOOP`: без этого взаимная достижимость не находится и шаги тела не сбрасываются в `PENDING`. Та же замена — в `resolveRootOrderIndex`, но **только** для внутреннего `reaches`: набор `targeted` по-прежнему считается по нарисованным маршрутам, иначе пайплайн, начинающийся с `LOOP`, останется без стартового шага.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest"`
Expected: PASS (включая прежние `reviewLoop*` — нарисованный возврат не изменился)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java
git commit -m "feat(pipelines): LOOP_END returns the flow to its loop server-side"
```

---

### Task 4: `PROMPT_LOOP` — цикл с промптом

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (`validateStepKinds`)
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java` (`activate`, `checkStep`, `toDetail`)
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunStepCheckResult.java`, `dto/PipelineRunDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java:102-121`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`, `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (`ContentType.PROMPT_LOOP`).
- Produces: `PipelineRunStepCheckResult(int checkCount, Instant deadlineAt, boolean expired, Integer iteration, Integer maxIterations, Boolean done)` плюс прежний 3-аргументный конструктор; `PipelineRunDetail.PipelineRunStepView` получает в конец `Integer loopIteration, Integer loopMaxIterations` (и поля оркестратора из Task 5 — добавить обе группы одним расширением, чтобы не плодить конструкторы).

- [ ] **Step 1: Write the failing tests**

В `PipelineServiceTest`:

```java
@Test
void promptLoopNeedsAPromptAndAnIterationLimit() {
    // PROMPT_LOOP с пустым промптом
    assertThatThrownBy(...).hasMessageContaining("is type PROMPT_LOOP and needs an instruction");
    // PROMPT_LOOP с maxIterations = 0
    assertThatThrownBy(...).hasMessageContaining("is type PROMPT_LOOP and needs a max iteration count between 1 and 100");
}
```

В `PipelineRunServiceTest`:

```java
@Test
void promptLoopIsWorkedByTheAgentAndCountsItsIterationsServerSide() {
    createPromptLoopPipeline("prompt-loop-1", 3); // PROMPT_LOOP(3) -> «Отчёт»
    PipelineRunDetail run = pipelineRunService.start("prompt-loop-1", "{}", "Tester");
    assertThat(run.currentStepOrderIndex()).isEqualTo(0);

    PipelineRunStepCheckResult first = pipelineRunService.checkStep(run.id(), 0, "нашёл 2 замечания");
    assertThat(first.iteration()).isEqualTo(1);
    assertThat(first.maxIterations()).isEqualTo(3);
    assertThat(first.done()).isFalse();

    pipelineRunService.checkStep(run.id(), 0, "поправил");
    PipelineRunStepCheckResult third = pipelineRunService.checkStep(run.id(), 0, "чисто");
    assertThat(third.iteration()).isEqualTo(3);
    assertThat(third.done()).isTrue();
}

@Test
void promptLoopIterationIsVisibleOnTheRunStep() {
    // после двух проверок loopIteration = 2, loopMaxIterations = 3, note = "Итерация 2 из 3"
}

@Test
void promptLoopWhoseDefinitionDisappearedMidRunReportsDone() {
    // step definition без maxIterations -> done = true с первой же проверки, прогон не зависает
}

@Test
void checkStepStillRejectsAStepThatIsNeitherWatcherNorPromptLoop() {
    assertThatThrownBy(() -> pipelineRunService.checkStep(runId, 0, "x"))
            .isInstanceOf(PipelineRunNotAWatcherException.class);
}

@Test
void promptLoopInsideALoopRegionRestartsItsIterationCount() {
    // LOOP(2) -> PROMPT_LOOP(2) -> LOOP_END: на второй итерации внешнего цикла loopIteration снова 1
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest" --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest"`
Expected: FAIL — `checkStep` бросает `PipelineRunNotAWatcherException` для `PROMPT_LOOP`.

- [ ] **Step 3: Валидация `PROMPT_LOOP`**

`validatePromptLoopStep(step)`: непустой `promptText`, `maxIterations` в 1..100, ровно один безымянный маршрут **или** именованные ветки (как у `PROMPT`). Данные носит как `PROMPT`: `acceptsDataInput` и `hasOutputs` для него истинны, отдельных ограничений нет.

- [ ] **Step 4: Активация и учёт итераций**

В `activate()` добавить `PROMPT_LOOP` в набор типов, которые становятся активными и ждут агента (рядом с `PROMPT`, `MD_FILE`, `WATCHER`). В `checkStep` развести по типу: `WATCHER` — как сейчас; `PROMPT_LOOP` — инкремент `arrivedCount`, `watchLastState` = усечённый `observedState`, `note` = `"Итерация " + iteration + " из " + max`, перевод `PENDING → RUNNING` со `startedAt`; результат — `new PipelineRunStepCheckResult(iteration, null, false, iteration, max, iteration >= max)`. Любой другой тип — прежнее исключение. Предел берётся из определения шага; если его не достать (определение заменено посреди прогона) — `log.warn` и `done = true`, по образцу `watchTimeoutSecondsOf`.

- [ ] **Step 5: Показать итерацию в `toDetail`**

`loopIteration` = `arrivedCount` для `LOOP` и `PROMPT_LOOP`, иначе `null`; `loopMaxIterations` — из определения шага для тех же двух типов.

- [ ] **Step 6: Описание MCP-инструмента**

В `pipeline_run_step_check` дописать вторую половину: инструмент обслуживает и `PROMPT_LOOP` — выполни промпт шага один раз, вызови проверку с коротким итогом итерации, и пока ответ говорит `done=false`, выполняй промпт снова; как только `true` — закрой шаг `pipeline_run_step_update`. Явно сказать, что считает сервер и что считать самому не надо.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.*" --tests "ru.iuribabalin.memorymcp.mcp.PipelineMcpToolsTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(pipelines): PROMPT_LOOP step kind with server-side iteration accounting"
```

---

### Task 5: Регион оркестрации

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (`validateStepKinds`, запрет внутри `PARALLEL`)
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java` (`activate`, `toDetail`)
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineGraph.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`, `.../PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: Task 2 (`PipelineGraph.reachableUntil`), Task 4 (`PipelineRunStepView` уже несёт `Boolean orchestrated, PipelineStep.DelegationHint delegationHint, Integer orchestratorMaxAttempts` — эта задача их заполняет, а не добавляет).
- Produces: `static Set<Integer> PipelineGraph.orchestratedRegion(int orchestratorIndex, int endIndex, List<List<Integer>> adjacency)` — шаги, достижимые от `ORCHESTRATOR`, не входя в его `ORCHESTRATOR_END`, без самого начала региона.

- [ ] **Step 1: Write the failing tests**

В `PipelineServiceTest`:

```java
@Test
void orchestratorNeedsItsEndStep() {
    assertThatThrownBy(...).hasMessageContaining("is type ORCHESTRATOR and needs an ORCHESTRATOR_END step closing its region");
}

@Test
void orchestratorEndMustPointAtAnOrchestrator() {
    assertThatThrownBy(...).hasMessageContaining("is type ORCHESTRATOR_END and must point at an ORCHESTRATOR step");
}

@Test
void orchestratorNeedsAnAttemptLimitBetweenOneAndFive() {
    assertThatThrownBy(...).hasMessageContaining("is type ORCHESTRATOR and needs an attempt limit between 1 and 5");
}

@Test
void orchestratorCannotSitInsideAParallelFanOut() {
    assertThatThrownBy(...).hasMessageContaining("cannot sit inside the fan-out of the PARALLEL step");
}

@Test
void delegationHintIsOnlyAllowedInsideAnOrchestratedRegion() {
    // PROMPT с delegationHint=CHEAPEST вне региона
    assertThatThrownBy(...).hasMessageContaining("has a delegation hint but does not sit inside an orchestrated region");
}

@Test
void anOrchestratorCannotSitInsideAnotherOrchestratorsRegion() {
    // спека: директор один на прогон — вложенный регион должен падать на сохранении, а не
    // сохраняться с неопределённым поведением
    assertThatThrownBy(...).hasMessageContaining("cannot sit inside the region of the ORCHESTRATOR step");
}
```

В `PipelineRunServiceTest`:

```java
@Test
void orchestratorMarksItsRegionOnEveryRunStep() {
    createOrchestratorPipeline("orch-1", 2); // ORCHESTRATOR(2) -> A -> B -> ORCHESTRATOR_END -> «Выводы»
    PipelineRunDetail run = pipelineRunService.start("orch-1", "{}", "Tester");
    // сам маркер — сквозной: агент сразу стоит на первом шаге региона
    assertThat(run.currentStepOrderIndex()).isEqualTo(1);
    assertThat(run.steps().get(1).orchestrated()).isTrue();
    assertThat(run.steps().get(1).orchestratorMaxAttempts()).isEqualTo(2);
    assertThat(run.steps().get(2).orchestrated()).isTrue();
    // шаг после приёмки директор делает сам
    assertThat(run.steps().get(4).orchestrated()).isFalse();
}

@Test
void orchestratorEndHandsTheFlowToTheStepAfterTheRegion() {
    // закрыли A и B -> currentStepOrderIndex = 4, ORCHESTRATOR_END уже DONE
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ru.iuribabalin.memorymcp.service.PipelineServiceTest" --tests "ru.iuribabalin.memorymcp.service.PipelineRunServiceTest"`
Expected: FAIL

- [ ] **Step 3: Добавить `PipelineGraph.orchestratedRegion`**

- [ ] **Step 4: Валидация пары и подсказок**

`validateOrchestratorStep`: `maxIterations` в 1..5 (это число попыток), ровно один безымянный маршрут, существует `ORCHESTRATOR_END` с `regionStartStepIndex` на него, данных не носит. `validateOrchestratorEndStep`: указывает на существующий `ORCHESTRATOR`, на один `ORCHESTRATOR` — один конец, достижим из своего начала, один безымянный маршрут, данных не носит. Запрет внутри фан-аута `PARALLEL` — тем же способом, что `validateNoWatcherInsideAParallelFanOut`; обобщить этот метод до набора запрещённых типов `{WATCHER, ORCHESTRATOR, ORCHESTRATOR_END}` вместо одного. Подсказка `delegationHint` допустима только на шаге, попавшем в `orchestratedRegion` какого-нибудь `ORCHESTRATOR`. Вложенность запрещена: `ORCHESTRATOR`, попавший в `orchestratedRegion` другого `ORCHESTRATOR`, — ошибка сохранения («директор один на прогон» из спеки).

- [ ] **Step 5: Сквозной рантайм и разметка региона**

В `activate()`: `ORCHESTRATOR` и `ORCHESTRATOR_END` — серверные сквозные шаги, по образцу `executeVariableStep` без выходов: `DONE` с `note` («Директор берёт регион на себя: попыток на шаг — N» / «Приёмка региона завершена») и переход по безымянному маршруту. В `toDetail` заполнить `orchestrated`, `delegationHint`, `orchestratorMaxAttempts` по объединению регионов всех `ORCHESTRATOR` пайплайна.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(pipelines): orchestrator region marks its steps for the leading agent"
```

---

### Task 6: Борд — модель, палитра и валидация

**Files:**
- Modify: `ui/src/api/types.ts:164` (`PipelineStepContentType`, `PipelineDelegationHint`, новые поля во `PipelineStepView`/`PipelineUpsertStep`/`PipelineRunStepView`)
- Modify: `ui/src/lib/pipelineBoard.ts`
- Test: `cd ui && npm run type-check`

**Interfaces:**
- Consumes: Task 1–5 (формы API).
- Produces: `BoardStep` получает `regionStartStepIndex: number | null` и `delegationHint: PipelineDelegationHint | null`; `executionAdjacency(steps: BoardStep[]): number[][]`; `regionStepsOf(steps: BoardStep[], orchestratorIndex: number): Set<number>`; `loopEndIndexFor(steps: BoardStep[], loopIndex: number): number | null`.

- [ ] **Step 1: Расширить типы API и `BoardStep`**

Четыре новых значения в `PipelineStepContentType`, `export type PipelineDelegationHint = 'CHEAPEST' | 'BALANCED' | 'SELF'`, новые поля в трёх интерфейсах представлений — имена ровно как в Java-DTO.

- [ ] **Step 2: Палитра и значения по умолчанию**

В `BLOCK_KINDS` четыре записи (подписи из Global Constraints), по образцу существующих, с описаниями: «Конец цикла» — «Замыкает тело цикла: поток сам возвращается в начало»; «Цикл с промптом» — «Повторяет один промпт заданное число раз»; «Оркестратор» — «Дорогая модель раздаёт шаги региона дешёвым и принимает работу»; «Приёмка» — «Конец региона оркестрации». Цвета: `LOOP_END` — тот же `#c026d3`, что у `LOOP`; `PROMPT_LOOP` — `#a21caf`; `ORCHESTRATOR`/`ORCHESTRATOR_END` — `#ea580c`. В `newBoardStep`: `maxIterations` = 5 для `PROMPT_LOOP`, 2 для `ORCHESTRATOR`; `regionStartStepIndex` = `null`. Обновить описание `LOOP` на «Повторяет тело заданное число раз; тело замыкает блок «Конец цикла»».

- [ ] **Step 3: Порты, носители данных, конверсия**

`ensureFixedPorts`: `LOOP_END` — маршрутов нет вовсе (`step.routes = []`); `PROMPT_LOOP` — как `PROMPT` (именованные + «далее»); `ORCHESTRATOR`/`ORCHESTRATOR_END` — ровно один «далее», как `JOIN`. `acceptsDataInput`/`hasOutputs`: ложь для `LOOP_END`, `ORCHESTRATOR`, `ORCHESTRATOR_END`; истина для `PROMPT_LOOP`. `supportsNamedBranches`/`hasEditableOutputs`: добавить `PROMPT_LOOP`. `fromDetail`/`toUpsertSteps` переносят оба новых поля. `materializeLegacyChain` пропускает `LOOP_END` так же, как `CONDITION`.

- [ ] **Step 4: Графовые функции и подсказки на борде**

`executionAdjacency` = нарисованные маршруты плюс ребро `LOOP_END → regionStartStepIndex`. Перевести на неё `analyzeGraph`'s `reachable`, `isAncestor` и `reachableBeforeAnyJoin`; `wiredAdjacency` остаётся нарисованной и продолжает обслуживать `wouldCreateCycle`, иначе внутри тела цикла нельзя будет провести ни одного провода. В `collectIssues` добавить правила — ошибки (зеркало сервера, текст по образцу существующих): `LOOP_END` без выбранного цикла; два конца на один цикл; цикл, не замкнутый ни ребром, ни концом; `PROMPT_LOOP` с пустым промптом или лимитом вне 1..100; `ORCHESTRATOR` без приёмки или с числом попыток вне 1..5; `ORCHESTRATOR_END` без выбранного начала; оркестратор внутри развилки; подсказка «кому» вне региона. Предупреждение: `ORCHESTRATOR`, в регионе которого нет ни одного шага, выполняемого моделью.

- [ ] **Step 5: Verify**

Run: `cd ui && npm run type-check`
Expected: без ошибок

- [ ] **Step 6: Commit**

```bash
git add ui/src/api/types.ts ui/src/lib/pipelineBoard.ts
git commit -m "feat(ui): board model, palette and checks for loop regions and the orchestrator"
```

---

### Task 7: Борд — карточки блоков и знак «?»

**Files:**
- Modify: `ui/src/components/PipelineStepNode.vue`
- Modify: `ui/src/views/PipelineBoardView.vue`
- Test: `cd ui && npm run type-check && npm run build`

**Interfaces:**
- Consumes: Task 6.

- [ ] **Step 1: Карточка `LOOP_END`**

Селект «Цикл» со списком всех блоков `LOOP` борда (подпись — заголовок блока), пишущий `regionStartStepIndex`. При постановке блока на полотно — автоподстановка, если `LOOP` на борде ровно один. Подсказка: «Поток сам вернётся в начало цикла — обратный провод рисовать не нужно». Портов исхода на карточке нет.

- [ ] **Step 2: Карточка `PROMPT_LOOP`**

Поля: «Максимум итераций» (как у `LOOP`) и текстовое поле промпта (как у `PROMPT`, через существующий `v-model="data.step.promptText"`), выходы и именованные ветки — как у `PROMPT`. Подсказка: «Блок повторяет свой промпт заданное число раз сам в себе; тело рёбрами набрасывать не нужно».

- [ ] **Step 3: Карточка `ORCHESTRATOR` со знаком «?» и карточка «Приёмка»**

У `ORCHESTRATOR`: поле «Попыток на шаг» (1..5) и кнопка-иконка «?» в заголовке карточки, раскрывающая поповер текстом (ровно это, формулировка зафиксирована спекой): что внутри региона работу делают дешёвые модели под присмотром дорогой; что для этого нужен скилл `pipelines` и хотя бы один локальный CLI; что без них шаги просто выполнит сам агент. У `ORCHESTRATOR_END` — селект «Оркестратор» (как в Step 1) и подсказка «Конец региона: дальше работает сам ведущий агент».

- [ ] **Step 4: Поле «кому» и рамка региона**

На карточках `PROMPT` / `MD_FILE` / `PROMPT_LOOP`, попавших в регион оркестрации, показывать селект «Кому»: «решает директор» (null), «подешевле» (`CHEAPEST`), «средняя» (`BALANCED`), «только директор» (`SELF`). Вне региона селект не показывается, а значение сбрасывается в `null`. Признак «в регионе» приходит в `data` узла новым полем `orchestrated: boolean`, которое `PipelineBoardView` считает через `regionStepsOf`. Шаг в регионе получает видимый бейдж оркестратора в заголовке.

- [ ] **Step 5: Постановка пары из палитры**

В `PipelineBoardView`: как `PARALLEL` ставится сам, так `ORCHESTRATOR` при постановке создаёт сразу два блока — себя и `ORCHESTRATOR_END` правее, связанные `regionStartStepIndex`. `LOOP` при постановке так же создаёт парный `LOOP_END` (и его «тело» остаётся неподключённым, как сейчас). Удаление блока-начала должно снимать `regionStartStepIndex` у его конца — проверить `removeStepAt` и доработать: индексы в этом поле сдвигаются по тому же правилу, что `routes.target` и `dataLinksOut.targetStepIndex`.

- [ ] **Step 6: Verify**

Run: `cd ui && npm run type-check && npm run build`
Expected: сборка проходит

- [ ] **Step 7: Commit**

```bash
git add ui/src
git commit -m "feat(ui): block cards for LOOP_END, PROMPT_LOOP and the orchestrator region"
```

---

### Task 8: Только-чтение — раскладка и вид прогона

**Files:**
- Modify: `ui/src/lib/pipelineStageLayout.ts:83-115`
- Modify: `ui/src/views/PipelineRunView.vue`
- Modify: `ui/src/components/PipelineStageGraph.vue`
- Test: `cd ui && npm run type-check && npm run build`

- [ ] **Step 1: Неявный возврат в раскладке**

В `rawEdges` добавить ребро от `LOOP_END` к его `regionStartStepOrderIndex` с меткой `null`. В расчёте глубины это ребро обязано попасть в ту же ветку, что нарисованный возврат (`loopReturn`), иначе цикл уедет за собственное тело. Нарисовать его пунктиром — тем же стилем, которым уже рисуется нарисованный возврат.

- [ ] **Step 2: Итерации и регион в виде прогона**

У шага `PROMPT_LOOP` и `LOOP` показывать «итерация N из M» из `loopIteration`/`loopMaxIterations` (у `PROMPT_LOOP` — пока шаг `RUNNING`, по образцу показа состояния наблюдателя). У шагов с `orchestrated = true` — бейдж региона и, если в `note` есть, кто сделал шаг, оставить `note` как есть: скилл уже пишет туда «сделано <модель>, попытка N».

- [ ] **Step 3: Verify**

Run: `cd ui && npm run type-check && npm run build`
Expected: сборка проходит

- [ ] **Step 4: Commit**

```bash
git add ui/src
git commit -m "feat(ui): read-only views show loop returns, iterations and the orchestrated region"
```

---

### Task 9: Скилл `pipelines`

**Files:**
- Modify: `.claude/skills/pipelines/SKILL.md`

- [ ] **Step 1: Раздел «Цикл с промптом»**

После раздела о наблюдателе. Содержание: тип `PROMPT_LOOP` — шаг агента; выполнить `resolvedInstructionText` один раз, вызвать `pipeline_run_step_check(runId, orderIndex, короткий итог)`, и пока ответ говорит `done: false`, выполнять снова; как только `true` — закрыть шаг `pipeline_run_step_update(..., "DONE", note)`, где в `note` число пройденных итераций. Счётчик ведёт сервер, считать самому не надо; прерванный прогон продолжается с той итерации, на которой встал (`loopIteration` в `pipeline_run_get`).

- [ ] **Step 2: Раздел «Цикл-регион»**

Короткий: `LOOP` и `LOOP_END` выполняет сервер, действовать на них не надо; важно только то, что шаги тела будут приходить в `activeStepOrderIndexes` повторно, и это не ошибка — чеклист в чате для повторного прохода отмечать заново с пометкой номера итерации.

- [ ] **Step 3: Раздел «Оркестратор»**

Пять подпунктов ровно по спеке (раздел «Оркестратор: что в сервере и что в скилле»):
разведка моделей один раз за прогон на входе в регион (какие CLI пробовать: `claude`, `codex`, `ollama`, `llm` — проверять наличие через `command -v`, список моделей — родной командой CLI; результат печатать в чат одной строкой);
делегирование шага с учётом `delegationHint` (`SELF` — делает сам; пусто — выбирает сам);
приёмка с повторами до `orchestratorMaxAttempts`, затем моделью уровнем выше, затем сам;
отчётность в `note` в формате `сделано <модель>, попытка <n>` либо `сделано директором: <причина>`;
деградация: ничего не нашлось — директор делает шаги сам, причину пишет в `note`, прогон не падает.
Явно: суб-агенты и делегированные модели сами пайплайновые инструменты не вызывают — состояние пишет ведущий агент.

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/pipelines/SKILL.md
git commit -m "docs(skill): run prompt loops, loop regions and the orchestrator region"
```

---

### Task 10: Пайплайны-примеры

**Files:**
- Create: `docs/examples/pipelines/README.md`
- Create: `docs/examples/pipelines/review-loop.pipeline.json`
- Create: `docs/examples/pipelines/loop-region.pipeline.json`
- Create: `docs/examples/pipelines/orchestrator.pipeline.json`

**Interfaces:**
- Consumes: формат `PipelineExportEnvelope` — `{"formatVersion": 1, "pipeline": {<PipelineUpsertRequest>}, "warnings": []}`; ставится через `POST /api/pipelines/import`.

- [ ] **Step 1: Собрать три примера на живом сервере, а не вручную**

Поднять приложение, включить флаг пайплайнов, собрать каждый пример `POST /api/pipelines` (той же формой, что шлёт борд), затем выгрузить `GET /api/pipelines/{slug}/export` в файл. Так формат гарантированно валиден и проходит собственную валидацию сохранения. Состав — ровно по разделу «Examples» спеки: `review-loop` (параметр «папка», `PROMPT_LOOP` на 5 итераций с промптом «проведи ревью, после поправь ошибки», затем шаг-отчёт), `loop-region` (`LOOP` на 3 → прогнать тесты → починить упавшее → `LOOP_END`, выход `done` на отчёт), `orchestrator` (`ORCHESTRATOR` на 2 попытки → три шага с подсказками `CHEAPEST`/`CHEAPEST`/`BALANCED` → `ORCHESTRATOR_END` → шаг выводов).

- [ ] **Step 2: Проверить, что каждый файл импортируется в чистую базу**

Run: `curl -fsS -X POST localhost:8080/api/pipelines/import -H 'Content-Type: application/json' --data @docs/examples/pipelines/<each>.pipeline.json`
Expected: HTTP 201 на всех трёх, затем блоки видны на борде с правильными связями пар.

- [ ] **Step 3: Написать `README.md`**

Что это за три файла, что каждый показывает, команда импорта из Step 2 и строка о том, что после импорта пайплайн запускается обычным «запусти пайплайн <имя>».

- [ ] **Step 4: Commit**

```bash
git add docs/examples/pipelines
git commit -m "docs: importable example pipelines for the new loop and orchestrator blocks"
```

---

### Task 11: Сквозная проверка и запись в дорожную карту

**Files:**
- Modify: `README.md` (раздел дорожной карты — по образцу коммита `cc0593b`)

- [ ] **Step 1: Прогнать всё**

Run: `./gradlew test && cd ui && npm run type-check && npm run build`
Expected: PASS целиком

- [ ] **Step 2: Прогнать пример вручную**

Импортировать `loop-region.pipeline.json`, запустить прогон через MCP-инструменты, пройти две итерации и убедиться, что шаги тела приходят повторно, а `done` выводит из цикла.

- [ ] **Step 3: Записать три фичи в дорожную карту `README.md` и закоммитить**

```bash
git add README.md
git commit -m "docs: record loop regions, prompt loops and the orchestrator in the roadmap"
```
