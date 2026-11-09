# Pipeline Watcher Step Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить в конструктор пайплайнов блок «Наблюдатель», который периодически проверяет внешнее состояние (пайплайн/ревью MR в GitLab или произвольную команду) и уводит поток в ветку по исходу.

**Architecture:** `WATCHER` — новое значение `PipelineStep.ContentType`, по исполнению ведущее себя как `PROMPT` (работает агент, не сервер). Конфиг (источник, аспект, интервал, таймаут) живёт в новых колонках `pipeline_steps`; цель наблюдения переиспользует `prompt_text` и потому бесплатно получает подстановку `{{data:...}}`. Прогонное состояние (счётчик проверок, время последней, последнее состояние, дедлайн) живёт в новых колонках `pipeline_run_steps`; дедлайном владеет сервер и ставит его на первой проверке. Единственная добавка в MCP-протокол — `pipeline_run_step_check`.

**Tech Stack:** Java 25 / Spring Boot (Spring AI MCP annotations), PostgreSQL + Flyway, JUnit 5 + AssertJ + `@SpringBootTest`, Vue 3 + TypeScript + Vue Flow, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-09-pipeline-watcher-step-design.md`

## Global Constraints

- Flyway только вперёд, следующий номер — `V22`; миграция обязана снять и пересоздать CHECK-ограничение на `pipeline_steps.content_type` по образцу `V16`/`V19`.
- Исходы фиксированы набором: `GITLAB_MR`+`PIPELINE` → `success`/`failed`/`timeout`; `GITLAB_MR`+`REVIEW` → `approved`/`comments`/`timeout`; `COMMAND` → `success`/`timeout`.
- `check_interval_seconds`: 10..3600. `watch_timeout_seconds`: 60..86400, и не меньше интервала.
- Маршруты обязаны существовать под все ключи набора, но могут быть непривязанными (`targetStepIndex = null`) — как у `CONDITION`.
- У наблюдателя с источником `COMMAND` входящих data-связей быть не должно.
- `WATCHER`, достижимый из `PARALLEL`-развилки, отклоняется на сохранении.
- Дедлайн ставится на **первой** проверке, не при активации шага.
- Весь пользовательский текст (UI, сообщения валидации, skill) — по-русски, как в остальных блоках.
- UI-тестового раннера в проекте нет и вводить его не нужно: верификация UI-задач — `npm run type-check` в `ui/`.
- Новые записи в `UsageEvent.Action` добавляются в конец enum.

## Review Focus

Входы, которые спецификация подразумевает, но ничьи тесты сами по себе не трогают (тест на каждый добавлен в задачу-владельца):

1. `pipeline_run_step_check` на шаге, который не `WATCHER` (или на несуществующем `orderIndex`) — должен падать внятной ошибкой, а не молча бампать счётчик. → Task 2.
2. Вторая итерация наблюдателя внутри `LOOP` — дедлайн и счётчик первой итерации не должны протухать на второй. → Task 2.
3. Сохранение наблюдателя с таймаутом меньше интервала проверки — ждать «каждые 10 минут, но не дольше минуты» бессмысленно. → Task 1.
4. Чтение прогона, у которого наблюдатель просрочен, но проверок больше не приходит (сессия умерла) — `watchExpired` должен быть `true` на чтении. → Task 2.
5. Переключение источника `GITLAB_MR` → `COMMAND` на канвасе — набор портов меняется с трёх на два, а уже нарисованные провода не должны остаться висеть на исчезнувшем порте. → Task 4.

---

## File Structure

| Файл | Ответственность |
|---|---|
| `src/main/resources/db/migration/V22__add_watcher_steps.sql` | колонки конфига и прогонного состояния, CHECK на `content_type` |
| `entity/PipelineStep.java` | `WATCHER` в `ContentType`, enum'ы `WatchSource`/`WatchAspect`, 4 поля конфига |
| `entity/PipelineRunStep.java` | 4 поля прогонного состояния наблюдателя |
| `dto/PipelineUpsertRequest.java` | 4 поля в `StepRequest` + конструктор совместимости |
| `dto/PipelineDetail.java` | 4 поля в `PipelineStepView` (конструктор пайплайна читает их обратно) |
| `dto/PipelineRunDetail.java` | 5 полей в `PipelineRunStepView` (включая производный `watchExpired`) |
| `dto/PipelineExecutionDetail.java` | 4 поля конфига в `StepView` — то, что видит агент |
| `service/PipelineService.java` | `validateWatcherStep`, правило `PARALLEL`-недостижимости, маппинг полей |
| `service/PipelineRunService.java` | `WATCHER`-ветка активации, `checkStep`, дедлайн, сброс в цикле, `watchExpired` в `toDetail` |
| `mcp/PipelineMcpTools.java` | инструмент `pipeline_run_step_check` |
| `ui/src/api/types.ts` | `'WATCHER'`, типы `PipelineWatchSource`/`PipelineWatchAspect`, поля в шаге |
| `ui/src/lib/pipelineBoard.ts` | палитра, модель, фиксированные порты, маппинг в upsert, issues |
| `ui/src/components/PipelineStepNode.vue` | редактор блока, подписи портов, read-only состояние наблюдателя |
| `.claude/skills/pipelines/SKILL.md` | раздел «Шаг-наблюдатель» для агента-исполнителя |

---

### Task 1: Схема, сущность и валидация сохранения

**Files:**
- Create: `src/main/resources/db/migration/V22__add_watcher_steps.sql`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineServiceTest.java`

**Interfaces:**
- Consumes: существующие `PipelineUpsertRequest.StepRequest`, `PipelineService.create/update/get`.
- Produces:
  - `PipelineStep.ContentType.WATCHER`
  - `PipelineStep.WatchSource { GITLAB_MR, COMMAND }`, `PipelineStep.WatchAspect { PIPELINE, REVIEW }`
  - `PipelineStep` геттеры/сеттеры: `getWatchSource/setWatchSource`, `getWatchAspect/setWatchAspect`, `getCheckIntervalSeconds/setCheckIntervalSeconds` (`Integer`), `getWatchTimeoutSeconds/setWatchTimeoutSeconds` (`Integer`)
  - `StepRequest` получает в конец 4 компонента: `PipelineStep.WatchSource watchSource, PipelineStep.WatchAspect watchAspect, Integer checkIntervalSeconds, Integer watchTimeoutSeconds`; все существующие конструкторы-совместимости передают в них `null`
  - `PipelineDetail.PipelineStepView` получает те же 4 поля в конец
  - `PipelineService.watcherOutcomeKeys(PipelineStep.WatchSource source, PipelineStep.WatchAspect aspect) -> java.util.List<String>` (package-private static; Task 4 дублирует этот набор на TS-стороне)

- [ ] **Step 1: Write the failing tests**

В `PipelineServiceTest` (шесть тестов; `watcher(...)` — локальный хелпер, собирающий `StepRequest` для наблюдателя):

```java
@Test
void savesAWatcherWithItsSourceAspectIntervalAndTimeout()
// GITLAB_MR/PIPELINE, цель "https://gitlab.example.com/g/p/-/merge_requests/42", 60 / 3600,
// маршруты success -> 1, failed -> null, timeout -> null
// assert: detail.steps().get(0).watchSource() == GITLAB_MR, watchAspect() == PIPELINE,
//         checkIntervalSeconds() == 60, watchTimeoutSeconds() == 3600,
//         routes() extracting outcomeKey containsExactlyInAnyOrder("success", "failed", "timeout")

@Test
void rejectsAWatcherWhoseRouteKeysDoNotMatchItsSource()
// COMMAND с маршрутами success/failed/timeout (у COMMAND нет failed)
// assertThatThrownBy ... PipelineInvalidGraphException, hasMessageContaining("success, timeout")

@Test
void rejectsAWatcherWithATimeoutShorterThanItsCheckInterval()   // Review Focus 3
// интервал 600, таймаут 60 -> PipelineInvalidParametersException, hasMessageContaining("не меньше интервала")

@Test
void rejectsAGitlabWatcherWhoseTargetIsNotAMergeRequestUrl()
// цель "https://gitlab.example.com/g/p" -> PipelineInvalidGraphException, hasMessageContaining("merge_requests")

@Test
void rejectsACommandWatcherWithAnIncomingDataLink()
// PROMPT с выходом "cmd" -> dataLinkOut в наблюдателя COMMAND
// -> PipelineInvalidGraphException, hasMessageContaining("COMMAND")

@Test
void rejectsAWatcherReachableFromAParallelFanOut()
// PARALLEL -> WATCHER -> PipelineInvalidGraphException, hasMessageContaining("PARALLEL")
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*PipelineServiceTest*' --console=plain`
Expected: FAIL — `WATCHER` не существует как значение enum (ошибка компиляции), затем падения валидации.

- [ ] **Step 3: Write the migration**

`V22__add_watcher_steps.sql`: `alter table pipeline_steps add column watch_source varchar(20), add column watch_aspect varchar(20), add column check_interval_seconds integer, add column watch_timeout_seconds integer;` плюс drop/add CHECK-ограничения на `content_type` со включённым `'WATCHER'` (точное имя ограничения взять из `V19`/`V20`). Колонки `pipeline_run_steps` — в Task 2, отдельной миграцией не нужны: добавить их в этот же файл, чтобы схема наблюдателя приезжала одним шагом (`watch_check_count integer not null default 0`, `watch_last_checked_at timestamptz`, `watch_last_state varchar(100)`, `watch_deadline_at timestamptz`).

- [ ] **Step 4: Add the entity fields and DTO components**

`PipelineStep`: `WATCHER` в `ContentType` (и дописать его в javadoc-комментарий над enum — наблюдателя ведёт Claude, не сервер), два новых enum'а, 4 поля с `@Enumerated(EnumType.STRING)` там, где enum. `StepRequest` и `PipelineDetail.PipelineStepView` — 4 компонента в конец; существующие конструкторы-совместимости передают `null`.

- [ ] **Step 5: Implement the validation in `PipelineService`**

`validateWatcherStep(StepRequest step, int index, ...)` рядом с `validateConditionStep`, вызываемый из той же ветвящейся цепочки по `contentType`; `watcherOutcomeKeys(source, aspect)` как источник истины по набору ключей; правило `PARALLEL`-недостижимости — в `validateGraph`, рядом с существующими проверками достижимости (наблюдатель запрещён, если достижим от любого шага типа `PARALLEL`). Маппинг 4 полей в обе стороны (`toEntity`/`PipelineStepView`).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests '*PipelineServiceTest*' --console=plain`
Expected: PASS. Новые компоненты в `StepRequest`/`PipelineStepView` ломают компиляцию у всех, кто строит эти записи позиционно (`PipelineControllerTest`, `PipelineRunServiceTest`, `PipelineMcpToolsTest`, UI-контроллеры): поправить вызовы, не логику — для этого и нужны конструкторы-совместимости.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V22__add_watcher_steps.sql src/main/java src/test/java
git commit -m "feat(pipelines): WATCHER step kind with save-time validation"
```

---

### Task 2: Движок прогона — активация, проверки, дедлайн

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineRunStep.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineRunDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/service/PipelineRunServiceTest.java`

**Interfaces:**
- Consumes: `PipelineStep.ContentType.WATCHER` и поля конфига из Task 1.
- Produces:
  - `PipelineRunStep`: `watchCheckCount` (`int`), `watchLastCheckedAt` (`Instant`), `watchLastState` (`String`), `watchDeadlineAt` (`Instant`) с геттерами/сеттерами
  - `PipelineRunService.checkStep(Long runId, int orderIndex, String observedState) -> PipelineRunStepCheckResult`
  - `public record PipelineRunStepCheckResult(int checkCount, Instant deadlineAt, boolean expired)` в пакете `dto`
  - `PipelineRunNotAWatcherException extends RuntimeException` в пакете `service` (сообщение называет `orderIndex` и фактический тип шага)
  - `PipelineRunDetail.PipelineRunStepView` получает в конец: `Integer watchCheckCount, Instant watchLastCheckedAt, String watchLastState, Instant watchDeadlineAt, Boolean watchExpired` (для не-наблюдателей — `null`)

- [ ] **Step 1: Write the failing tests**

В `PipelineRunServiceTest`:

```java
@Test
void aWatcherStepIsHandedToTheAgentLikeAPrompt()
// start -> activeStepOrderIndexes содержит индекс наблюдателя, его status == PENDING

@Test
void theFirstCheckStartsTheDeadlineAndTheStepIsRunning()
// checkStep(runId, i, "running") -> checkCount == 1, deadlineAt != null, expired == false
// шаг в RUNNING, watchLastState == "running"

@Test
void aLaterCheckKeepsTheDeadlineTheFirstOneSet()
// две проверки -> deadlineAt второй equals deadlineAt первой, checkCount == 2

@Test
void aCheckPastTheDeadlineComesBackExpired()
// наблюдатель с watchTimeoutSeconds == 60; после первой проверки сдвинуть
// watch_deadline_at в прошлое через репозиторий -> expired == true

@Test
void aWatcherOutcomeRoutesLikeAnyOtherOutcome()
// updateStep(..., DONE, outcome "failed") -> активным становится шаг, подключённый к ветке failed

@Test
void checkingAStepThatIsNotAWatcherIsRejected()                     // Review Focus 1
// PROMPT-шаг -> assertThatThrownBy ... PipelineRunNotAWatcherException

@Test
void reactivatingAWatcherInALoopResetsItsChecksAndDeadline()        // Review Focus 2
// LOOP -> WATCHER -> ... -> LOOP; после второй активации
// watchCheckCount == 0, watchDeadlineAt == null, watchLastState == null

@Test
void anOverdueWatcherReadsAsExpiredWithoutAnyFurtherCheck()         // Review Focus 4
// после сдвига дедлайна в прошлое: get(runId).steps().get(i).watchExpired() == true
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*PipelineRunServiceTest*' --console=plain`
Expected: FAIL — `checkStep` не существует.

- [ ] **Step 3: Add the run-step fields and the `WATCHER` activation arm**

В `activate()` `WATCHER` обрабатывается так же, как `PROMPT`/`MD_FILE`: условие «не серверный тип» в начале цикла и `default`-ветка `switch` должны пропускать его в активный набор без изменения `watch_deadline_at`.

- [ ] **Step 4: Implement `checkStep` and the derived `watchExpired`**

`checkStep` под `@Transactional`: найти шаг прогона (иначе `PipelineRunStepNotFoundException`), проверить `contentType == WATCHER` (иначе `PipelineRunNotAWatcherException`), бампнуть счётчик, записать время и состояние, на первой проверке выставить дедлайн из `watch_timeout_seconds` шага пайплайна, перевести `PENDING -> RUNNING`, вернуть `PipelineRunStepCheckResult`. В `prepareStepForActivation` при реактивации обнулить четыре поля наблюдателя. В `toDetail` заполнить 5 новых полей `PipelineRunStepView`, где `watchExpired` считается от `watch_deadline_at` и `Instant.now()`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*PipelineRunServiceTest*' --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(pipelines): watcher run state, server-owned deadline and checkStep"
```

---

### Task 3: MCP-поверхность

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineExecutionDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (`getForExecution`)
- Modify: `src/main/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpTools.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/UsageEvent.java`
- Test: `src/test/java/ru/iuribabalin/memorymcp/mcp/PipelineMcpToolsTest.java`

**Interfaces:**
- Consumes: `PipelineRunService.checkStep`, `PipelineRunStepCheckResult` (Task 2); поля конфига (Task 1).
- Produces:
  - `PipelineExecutionDetail.StepView` получает в конец: `PipelineStep.WatchSource watchSource, PipelineStep.WatchAspect watchAspect, Integer checkIntervalSeconds, Integer watchTimeoutSeconds`
  - `PipelineMcpTools.pipelineRunStepCheck(Long runId, Integer orderIndex, String observedState) -> PipelineRunStepCheckResult`, `@McpTool(name = "pipeline_run_step_check")`
  - `UsageEvent.Action.PIPELINE_RUN_STEP_CHECK`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void pipelineGetExposesAWatcherSourceIntervalAndTimeout()
// getForExecution: steps().get(i).watchSource() == GITLAB_MR, checkIntervalSeconds() == 60

@Test
void pipelineRunStepCheckReportsTheCheckCountAndDeadline()
// pipelineRunStepCheck(runId, i, "running") -> checkCount == 1, expired == false

@Test
void pipelineRunStepCheckIsRefusedWhenPipelinesAreDisabled()
// как существующие тесты на флаг -> PipelineFeatureDisabledException
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*PipelineMcpToolsTest*' --console=plain`
Expected: FAIL — метода нет.

- [ ] **Step 3: Add the watcher config to `StepView` and the new tool**

Описание инструмента (`description`) должно сказать агенту то, ради чего инструмент существует: вызывать на каждой проверке наблюдателя, передавая наблюдённое состояние как есть; при `expired: true` — прекратить ожидание и закрыть шаг через `pipeline_run_step_update` с исходом `timeout`. Как у остальных инструментов: `requireEnabled()` первым делом и `usageEventRecorder.record(PIPELINE_RUN_STEP_CHECK, String.valueOf(runId), null, null, null)` после успешного вызова.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*PipelineMcpToolsTest*' --console=plain`
Expected: PASS

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew test --console=plain`
Expected: PASS (включая `PipelineControllerTest`/`PipelineRunControllerTest`, которые ломаются от изменившихся record-конструкторов, — поправить их вызовы, не логику)

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(pipelines): expose watcher config and pipeline_run_step_check over MCP"
```

---

### Task 4: Блок в конструкторе

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/lib/pipelineBoard.ts`
- Modify: `ui/src/components/PipelineStepNode.vue`
- Modify: `ui/src/components/AppIcon.vue` (иконка `eye`, если её нет)

**Interfaces:**
- Consumes: REST-поля шага из Task 1 (`watchSource`, `watchAspect`, `checkIntervalSeconds`, `watchTimeoutSeconds`).
- Produces:
  - `PipelineStepContentType` включает `'WATCHER'`; `export type PipelineWatchSource = 'GITLAB_MR' | 'COMMAND'`; `export type PipelineWatchAspect = 'PIPELINE' | 'REVIEW'`
  - `BoardStep` получает `watchSource: PipelineWatchSource | null`, `watchAspect: PipelineWatchAspect | null`, `checkIntervalSeconds: number | null`, `watchTimeoutSeconds: number | null`
  - `export function watcherOutcomeKeys(source: PipelineWatchSource | null, aspect: PipelineWatchAspect | null): string[]` — TS-двойник `PipelineService.watcherOutcomeKeys`
  - `export const WATCHER_PORT_LABELS: Record<string, string>` — `success` → «событие наступило», `failed` → «сборка упала», `timeout` → «не дождались», `approved` → «апрув», `comments` → «есть замечания»

- [ ] **Step 1: Extend the types and the board model**

`'WATCHER'` в `PipelineStepContentType` и в типы шагов (`PipelineUpsertStep`, `PipelineDetail`-шаг); 4 поля в `BoardStep`; в `newBoardStep` для `WATCHER` дефолты: `watchSource: 'GITLAB_MR'`, `watchAspect: 'PIPELINE'`, `checkIntervalSeconds: 60`, `watchTimeoutSeconds: 3600`, `promptText: ''`; для остальных типов — `null`. Элемент палитры в `BLOCK_KINDS`: `{ kind: 'WATCHER', label: 'Наблюдатель', description: 'Ждёт внешнее событие и ветвится по результату', icon: 'eye', color: '#0891b2' }`. `acceptsDataInput` / `hasOutputs` / `hasEditableOutputs` — включить `WATCHER`; `supportsNamedBranches` / `supportsAddingPorts` — не включать.

- [ ] **Step 2: Fixed ports, upsert mapping and issues**

`ensureFixedPorts`: для `WATCHER` переписать `step.routes` строго под `watcherOutcomeKeys(step.watchSource, step.watchAspect)`, сохраняя привязки совпавших по ключу портов — это же и закрывает Review Focus 5: при смене источника/аспекта исчезнувшие ключи (и их провода) отбрасываются, новые появляются непривязанными. Вызвать `ensureFixedPorts` из обработчика смены источника/аспекта в `PipelineBoardView`. В `toUpsertSteps` добавить `WATCHER` в исключение фильтра непривязанных маршрутов (рядом с `CONDITION`/`LOOP`) и прокинуть 4 поля. В `collectIssues` для `WATCHER`: пустая цель — `error` («укажите ссылку на MR» / «укажите команду»); `GITLAB_MR` без `/merge_requests/<число>` в цели — `error`; интервал вне 10..3600 или таймаут вне 60..86400 либо меньше интервала — `error`; `COMMAND` с входящими data-связями — `error`; наблюдатель, достижимый от `PARALLEL` (по тому же `analyzeGraph`, которым считается достижимость от старта) — `error` с текстом про недопустимость наблюдателя в параллельной ветке; непривязанные порты — `warning` в формулировке «путь завершится», как у `CONDITION`.

- [ ] **Step 3: The card editor and port labels**

В `PipelineStepNode.vue` — ветка `v-else-if="data.step.contentType === 'WATCHER'"` в цепочке редакторов: селект источника (GitLab MR / Команда), селект аспекта только при `GITLAB_MR` (Сборка / Ревью), поле цели с плейсхолдером по источнику, два числовых поля — интервал проверки и предельное ожидание (в минутах в UI, в секундах в модели), и `pl-hint`, который честно говорит: проверки идут в фоне и не тратят токены, но ожидание держится на открытой сессии. Подписи портов — `WATCHER_PORT_LABELS[route.outcomeKey]` в той же цепочке `template`, где сейчас `isCondition`/`isLoop`.

- [ ] **Step 4: Verify types and build**

Run: `cd ui && npm run type-check && npm run build-only`
Expected: PASS, без ошибок типов

- [ ] **Step 5: Commit**

```bash
git add ui/src
git commit -m "feat(pipelines): watcher block in the pipeline constructor"
```

---

### Task 5: Прогон в дашборде и инструкция агенту

**Files:**
- Modify: `ui/src/views/PipelineRunView.vue`
- Modify: `ui/src/api/types.ts` (поля прогонного шага)
- Modify: `.claude/skills/pipelines/SKILL.md`

**Interfaces:**
- Consumes: `watchCheckCount`, `watchLastCheckedAt`, `watchLastState`, `watchDeadlineAt`, `watchExpired` из Task 2; `pipeline_run_step_check` из Task 3.
- Produces: раздел «Шаг-наблюдатель» в skill'е — единственный документ, по которому агент умеет исполнять этот блок.

- [ ] **Step 1: Surface the watcher state in the run view**

У шага-наблюдателя в статусе `RUNNING` показать строку «проверка N · последнее состояние X · дедлайн в HH:MM», а при `watchExpired` — вместо дедлайна заметный «ожидание просрочено». Поля добавить в тип прогонного шага в `api/types.ts`.

- [ ] **Step 2: Verify types and build**

Run: `cd ui && npm run type-check && npm run build-only`
Expected: PASS

- [ ] **Step 3: Write the skill section**

Новый раздел «Шаг-наблюдатель» в `.claude/skills/pipelines/SKILL.md`, после раздела про параллельные ветки. Он должен однозначно отвечать на пять вопросов:
1. Как опознать шаг: `contentType == "WATCHER"` в списке шагов прогона; конфиг (`watchSource`, `watchAspect`, `checkIntervalSeconds`, `watchTimeoutSeconds`) — из `pipeline_get`, цель — `resolvedInstructionText` этого шага.
2. Как ждать: одна фоновая команда-поллер (`run_in_background`), которая сама спит `checkIntervalSeconds` между попытками и завершается, когда событие наступило; не цикл проверок в диалоге. Честная рамка: токенов это не стоит, но держится на открытой сессии; прерванный прогон продолжается через `pipeline_run_get` и накопленное ожидание не теряет.
3. Как проверять GitLab: по порядку деградации — MCP-сервер `gitlab`, иначе `glab`, иначе `curl` с токеном; проект и `iid` берутся из ссылки на MR. Для `PIPELINE` — статус последнего пайплайна MR (`success` → `success`, `failed`/`canceled` → `failed`, остальное → ждём). Для `REVIEW` — правило из п. 3 решений спеки по приоритету: одобрен → `approved`; иначе есть нерешённая дискуссия, в которой последняя реплика не от автора MR → `comments`; иначе ждём.
4. Что делать на каждой проверке: `pipeline_run_step_check(runId, orderIndex, observedState)`; при `expired: true` — прекратить ожидание и закрыть шаг через `pipeline_run_step_update` с исходом `timeout`.
5. Как закрыть шаг: тот же `pipeline_run_step_update` с `outcome` из фиксированного набора этого наблюдателя и `outputsJson` для объявленных выходов — ничем не отличается от обычного шага.
Там же — явно: наблюдателя не бывает внутри `PARALLEL`-развилки, так что раздел про суб-агентов его не касается.

- [ ] **Step 4: Full verification**

Run: `./gradlew test --console=plain && cd ui && npm run type-check`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ui/src .claude/skills/pipelines/SKILL.md
git commit -m "feat(pipelines): watcher run state in the dashboard and the agent's skill section"
```
