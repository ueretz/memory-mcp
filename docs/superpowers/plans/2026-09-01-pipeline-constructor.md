# Pipeline Constructor (ComfyUI-style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Довести доску пайплайнов до полноценного конструктора: типизированные цветные пины, входной блок параметров, блок AGENT_TASK и контекстное меню добавления блоков.

**Architecture:** Одна миграция V17 добавляет типы выходов, параметрные data-связи и AGENT_TASK-поля. Бэкенд расширяет существующие пути (валидация в `PipelineService`, подстановка в `PipelineRunService`) без новых сервисов. Фронтенд расширяет `PipelineStepNode.vue` и `PipelineBoardView.vue` по уже сложившемуся паттерну «карточка + колбэки в data».

**Tech Stack:** Spring Boot (Java 25), Flyway/PostgreSQL, Vue 3 + TypeScript, @vue-flow/core.

**Spec:** docs/superpowers/specs/2026-09-01-pipeline-constructor-design.md

## Global Constraints

- Следующая миграция — строго `V17__add_pipeline_constructor_fields.sql`; одна миграция на всю фичу.
- Инвариант источника data-связи: ровно один из (source_step_id+source_output_id) | source_parameter_id — и в CHECK, и в валидации.
- Параметрные связи в DTO — top-level `parameterLinks` (НЕ внутри шагов).
- Токен подстановки один для обоих видов связей: `{{data:<token>}}`; формат для агента не меняется.
- Цвета пинов: STRING `#eab308`, NUMBER `#3b82f6`, BOOLEAN `#a855f7`.
- Существующие тесты не должны ломаться; `./gradlew test` зелёный после каждой задачи; фронтовые задачи — `npm run type-check` и `npx vite build` зелёные.
- Все пользовательские строки UI — на русском (как весь существующий UI).

---

### Task 1: Миграция V17, entity-поля, DTO-поля (механическая проводка)

**Files:**
- Create: `src/main/resources/db/migration/V17__add_pipeline_constructor_fields.sql`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStepOutput.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineDataLink.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/entity/PipelineStep.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineUpsertRequest.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/dto/PipelineDetail.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (только проводка полей, без новой валидации)

**Interfaces:**
- Produces: `PipelineStepOutput.Type { STRING, NUMBER, BOOLEAN }` + `getType()/setType(Type)`; `PipelineDataLink.getSourceParameterId()/setSourceParameterId(Long)`; `PipelineStep.ContentType.AGENT_TASK`; `PipelineStep.getAgentContext()/setAgentContext(String)`, `getAgentExpectedResult()/setAgentExpectedResult(String)`; `OutputRequest(String name, PipelineStepOutput.Type type)`; `ParameterLinkRequest(String token, String parameterName, Integer targetStepIndex)` (в `PipelineUpsertRequest`, top-level поле `List<ParameterLinkRequest> parameterLinks`); `StepRequest` получает `String agentContext, String agentExpectedResult` (после `conditionValue`); `PipelineDetail` получает `List<PipelineParameterLinkView> parameterLinks` где `PipelineParameterLinkView(Long id, String token, String parameterName, Integer targetStepOrderIndex, String targetStepTitle)`; `OutputView(Long id, String name, PipelineStepOutput.Type type)`; `PipelineStepView` получает `agentContext, agentExpectedResult`.

- [ ] **Step 1: Миграция** — создать `V17__add_pipeline_constructor_fields.sql` с ТОЧНО этим содержимым:

```sql
ALTER TABLE pipeline_step_outputs
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'STRING'
        CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN'));

ALTER TABLE pipeline_data_links
    ALTER COLUMN source_step_id DROP NOT NULL,
    ALTER COLUMN source_output_id DROP NOT NULL,
    ADD COLUMN source_parameter_id BIGINT REFERENCES pipeline_parameters (id) ON DELETE CASCADE,
    ADD CONSTRAINT pipeline_data_links_source_check CHECK (
        (source_step_id IS NOT NULL AND source_output_id IS NOT NULL AND source_parameter_id IS NULL)
        OR (source_step_id IS NULL AND source_output_id IS NULL AND source_parameter_id IS NOT NULL)
    );

ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD COLUMN agent_context TEXT,
    ADD COLUMN agent_expected_result TEXT,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK'));

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT IF EXISTS pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK'));
```

- [ ] **Step 2: Entity-поля.** В `PipelineStepOutput` добавить `public enum Type { STRING, NUMBER, BOOLEAN }` и поле `@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Type type = Type.STRING;` с геттером/сеттером (зеркально `PipelineParameter.type`, `PipelineParameter.java:31-33`). В `PipelineDataLink` сделать `sourceStepId`/`sourceOutputId` nullable (`@Column(name = "source_step_id")` — убрать `nullable=false`) и добавить `@Column(name = "source_parameter_id") private Long sourceParameterId;` с геттером/сеттером. В `PipelineStep` добавить в `ContentType` значение `AGENT_TASK` и два поля `@Column(name = "agent_context", columnDefinition = "text") private String agentContext;` и `@Column(name = "agent_expected_result", columnDefinition = "text") private String agentExpectedResult;` с геттерами/сеттерами.

- [ ] **Step 3: DTO.** В `PipelineUpsertRequest`: `OutputRequest(String name, PipelineStepOutput.Type type)`; в `StepRequest` добавить в конец компонентов `String agentContext, String agentExpectedResult`; в сам record добавить компонент `List<ParameterLinkRequest> parameterLinks` (после `steps`) и `public record ParameterLinkRequest(String token, String parameterName, Integer targetStepIndex) {}`. В `PipelineDetail`: `OutputView(Long id, String name, PipelineStepOutput.Type type)`; в `PipelineStepView` добавить `String agentContext, String agentExpectedResult` (в конец); в `PipelineDetail` добавить компонент `List<PipelineParameterLinkView> parameterLinks` (после `steps`) и `public record PipelineParameterLinkView(Long id, String token, String parameterName, Integer targetStepOrderIndex, String targetStepTitle) {}`. Не забыть import `PipelineStepOutput`.

- [ ] **Step 4: Проводка в PipelineService.** В `replaceParametersAndSteps` (`PipelineService.java:428-501`): сохранять `output.setType(outputRequest.type())` (около строки 484); сохранять `step.setAgentContext(stepRequest.agentContext()); step.setAgentExpectedResult(stepRequest.agentExpectedResult());` (около строки 461); после существующего цикла data-связей добавить персист параметрных связей — параметры сохраняются в начале метода, собрать `Map<String, Long> parameterIdByName` из сохранённых `PipelineParameter` и:

```java
for (PipelineUpsertRequest.ParameterLinkRequest linkRequest : request.parameterLinks()) {
    PipelineDataLink link = new PipelineDataLink();
    link.setToken(linkRequest.token());
    link.setSourceParameterId(parameterIdByName.get(linkRequest.parameterName()));
    link.setTargetStepId(savedSteps.get(linkRequest.targetStepIndex()).getId());
    pipelineDataLinkRepository.save(link);
}
```

  Удаление старых параметрных связей отдельно не нужно: `pipelineParameterRepository.deleteByPipelineId` в начале метода каскадно удаляет их через FK `ON DELETE CASCADE` (V17) — оставить комментарий об этом у вызова delete. В `toDetail` добавить маппинг `parameterLinks` (все связи с `sourceParameterId != null`, имя параметра — по id из загруженных параметров; `targetStepOrderIndex/targetStepTitle` — по существующим картам `orderIndexById/titleById`, `PipelineService.java:516-519`) и `type` в `OutputView`, `agentContext/agentExpectedResult` в `PipelineStepView`. ВАЖНО: в существующем маппинге `dataLinksOut` внутри `toDetail` отфильтровать связи с `sourceParameterId != null` (они теперь не принадлежат шагу). `request.parameterLinks()` может прийти null от старых клиентов — в начале валидации/персиста трактовать null как пустой список.

- [ ] **Step 5: Починить компиляцию по всему бэкенду.** Все места создания `OutputRequest`/`StepRequest`/`PipelineUpsertRequest`/`OutputView`/`PipelineStepView` (тесты в `src/test/java`, `PipelineExecutionDetail`-маппинг и т.д.) — добавить новые аргументы (`PipelineStepOutput.Type.STRING`, `null, null`, `List.of()` соответственно). `./gradlew compileJava compileTestJava` до зелёного.

- [ ] **Step 6: Прогнать тесты и закоммитить.** Run: `./gradlew test`. Expected: PASS. `git add -A && git commit -m "feat: add V17 schema + DTO plumbing for typed pins, param links, agent task"`.

---

### Task 2: Валидация апсерта (типы, параметрные связи, AGENT_TASK)

**Files:**
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java`
- Test: `src/test/java/.../PipelineServiceTest.java` (найти существующий тест-класс валидаций пайплайнов и дополнить его; если валидации тестируются в другом классе — дополнять тот)

**Interfaces:**
- Consumes: DTO/entity-поля из Task 1.
- Produces: приватные методы `validateParameterLinks(PipelineUpsertRequest request)`, `validateAgentTaskStep(PipelineUpsertRequest.StepRequest step)`, расширенный `validateConditionStep`; все вызываются из существующей цепочки валидации апсерта (рядом с `validateDataLinks`/`validateStepKinds`).

- [ ] **Step 1: Написать падающие тесты** (по паттерну существующих негативных тестов валидации в том же классе):
  - апсерт с `parameterLinks` на несуществующее `parameterName` → исключение с текстом `references unknown parameter`;
  - апсерт с дублем токена между параметрной связью и шаговой data-связью → `duplicate data link token`;
  - `parameterLinks` с `targetStepIndex` вне диапазона → исключение;
  - CONDITION c оператором `GREATER_THAN`, вход — выход шага типа STRING → `requires a NUMBER input`;
  - CONDITION c `GREATER_THAN`, вход — параметр типа NUMBER → апсерт проходит;
  - CONDITION c `EQUALS`, вход типа STRING → проходит;
  - AGENT_TASK без `promptText` → `is type AGENT_TASK and must have a goal`;
  - AGENT_TASK с целью, контекстом, результатом → проходит.

- [ ] **Step 2: Run tests, verify FAIL.** `./gradlew test --tests '*Pipeline*'` — новые падают, старые зелёные.

- [ ] **Step 3: Реализация.**

```java
private void validateParameterLinks(PipelineUpsertRequest request) {
    Set<String> parameterNames = request.parameters().stream()
            .map(PipelineUpsertRequest.ParameterRequest::name)
            .collect(Collectors.toSet());
    Set<String> seenTokens = new HashSet<>();
    request.steps().forEach(s -> s.dataLinksOut().forEach(l -> seenTokens.add(l.token())));
    for (PipelineUpsertRequest.ParameterLinkRequest link : parameterLinksOf(request)) {
        if (!parameterNames.contains(link.parameterName())) {
            throw new PipelineInvalidGraphException(
                    "Parameter link references unknown parameter '" + link.parameterName() + "'");
        }
        if (link.targetStepIndex() == null || link.targetStepIndex() < 0
                || link.targetStepIndex() >= request.steps().size()) {
            throw new PipelineInvalidGraphException(
                    "Parameter link for '" + link.parameterName() + "' targets a step index that does not exist");
        }
        if (!seenTokens.add(link.token())) {
            throw new PipelineInvalidGraphException("Duplicate data link token '" + link.token() + "'");
        }
    }
}

private List<PipelineUpsertRequest.ParameterLinkRequest> parameterLinksOf(PipelineUpsertRequest request) {
    return request.parameterLinks() == null ? List.of() : request.parameterLinks();
}

private void validateAgentTaskStep(PipelineUpsertRequest.StepRequest step) {
    if (step.promptText() == null || step.promptText().isBlank()) {
        throw new PipelineInvalidParametersException(
                "Step '" + step.title() + "' is type AGENT_TASK and must have a goal");
    }
}
```

  Расширить `validateConditionStep` (`PipelineService.java:348-385`): для числовых операторов (все кроме `EQUALS`) найти единственную входящую связь этого шага (шаговую — в `dataLinksOut` других шагов по `targetStepIndex`; параметрную — в `parameterLinksOf(request)` по `targetStepIndex`) и её тип источника (`OutputRequest.type()` или `ParameterRequest.type()`); если тип не NUMBER — `throw new PipelineInvalidGraphException("Step '" + step.title() + "' compares numbers and requires a NUMBER input, got " + actualType)`. Правило «ровно одна входящая data-связь» для CONDITION теперь считает связи обоих видов (обновить существующий подсчёт в `validateConditionStep`/`validateStepKinds`). В `validateStepKinds` добавить ветку `case AGENT_TASK -> validateAgentTaskStep(step);`. Вызвать `validateParameterLinks(request)` из цепочки валидации апсерта рядом с `validateDataLinks`.

- [ ] **Step 4: Run tests, verify PASS.** `./gradlew test`. Expected: все зелёные.
- [ ] **Step 5: Commit.** `git commit -am "feat: validate typed pins, parameter links and agent-task steps on upsert"`.

---

### Task 3: Ран-движок — параметрная подстановка и композиция AGENT_TASK

**Files:**
- Create: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineStepText.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineRunService.java`
- Modify: `src/main/java/ru/iuribabalin/memorymcp/service/PipelineService.java` (метод `resolveInstructionText` для pipeline_get)
- Test: существующий тест-класс `PipelineRunService` (дополнить)

**Interfaces:**
- Consumes: `PipelineDataLink.sourceParameterId`, `PipelineStep.agentContext/agentExpectedResult`, `PipelineRun.parametersJson`, `PipelineParameter.defaultValue`.
- Produces: `public final class PipelineStepText { public static String baseText(PipelineStep step) }` — для PROMPT/VARIABLE возвращает `promptText`, для AGENT_TASK — композицию секций; используется обоими сервисами.

- [ ] **Step 1: Написать падающие тесты:**
  - `PipelineStepText.baseText`: AGENT_TASK со всеми тремя полями → строка `## Цель\n<goal>\n\n## Контекст\n<ctx>\n\n## Ожидаемый результат\n<exp>`; AGENT_TASK только с целью → только секция цели без пустых секций; PROMPT → просто `promptText`.
  - Ран-тест: пайплайн `параметр city (STRING, default "Moscow")` + параметрная связь в шаг PROMPT с `{{data:tok1}}` в тексте; старт рана с `parametersJson = {"city":"Paris"}` → `resolvedInstructionText` содержит `Paris`; старт без значения → содержит `Moscow`; параметр без default и без значения → пустая строка.
  - Ран-тест: CONDITION (`GREATER_THAN`, comparand `10`) со входом-параметром NUMBER, старт с `{"n":"25"}` → ран автоматически уходит в ветку true.

- [ ] **Step 2: Run tests, verify FAIL.**

- [ ] **Step 3: Реализация.**

```java
package ru.iuribabalin.memorymcp.service;

import ru.iuribabalin.memorymcp.entity.PipelineStep;

public final class PipelineStepText {
    private PipelineStepText() {
    }

    public static String baseText(PipelineStep step) {
        if (step.getContentType() != PipelineStep.ContentType.AGENT_TASK) {
            return step.getPromptText();
        }
        StringBuilder text = new StringBuilder("## Цель\n").append(step.getPromptText());
        if (step.getAgentContext() != null && !step.getAgentContext().isBlank()) {
            text.append("\n\n## Контекст\n").append(step.getAgentContext());
        }
        if (step.getAgentExpectedResult() != null && !step.getAgentExpectedResult().isBlank()) {
            text.append("\n\n## Ожидаемый результат\n").append(step.getAgentExpectedResult());
        }
        return text.toString();
    }
}
```

  В `PipelineRunService.resolveInstructionText` (`PipelineRunService.java:397-420`): заменить `step.getPromptText()` на `PipelineStepText.baseText(step)` (для AGENT_TASK перестаёт возвращать null-гард по promptText — гард оставить: AGENT_TASK всегда имеет promptText по валидации Task 2); в цикле по links добавить ветку параметрного источника:

```java
String value;
if (link.getSourceParameterId() != null) {
    value = parameterValues.getOrDefault(link.getSourceParameterId(), "");
} else {
    Long sourceRunStepId = runStepIdByPipelineStepId.get(link.getSourceStepId());
    value = sourceRunStepId != null
            ? reportedValues.getOrDefault(sourceRunStepId + ":" + link.getSourceOutputId(), "")
            : "";
}
```

  где `Map<Long, String> parameterValues` строится один раз на вызов `toDetail`-пути рана методом:

```java
private Map<Long, String> resolveParameterValues(PipelineRun run) {
    Map<String, String> provided = new HashMap<>();
    if (run.getParametersJson() != null && !run.getParametersJson().isBlank()) {
        try {
            JsonNode node = objectMapper.readTree(run.getParametersJson());
            node.fields().forEachRemaining(e -> provided.put(e.getKey(), e.getValue().asText()));
        } catch (JsonProcessingException ex) {
            // ignore malformed json - падать из-за него нельзя, поведение как «значений нет»
        }
    }
    Map<Long, String> values = new HashMap<>();
    for (PipelineParameter parameter : pipelineParameterRepository.findByPipelineIdOrderByOrderIndexAsc(run.getPipelineId())) {
        String value = provided.get(parameter.getName());
        if (value == null) {
            value = parameter.getDefaultValue() != null ? parameter.getDefaultValue() : "";
        }
        values.put(parameter.getId(), value);
    }
    return values;
}
```

  (заинжектить `ObjectMapper` и `PipelineParameterRepository`, если их ещё нет в конструкторе; `com.fasterxml.jackson.databind.ObjectMapper` — стандартный спринговый бин). Прокинуть `parameterValues` параметром в `resolveInstructionText`. В `resolveConditionInputValue` (`PipelineRunService.java:303-313`) добавить перед step-веткой: `if (link.getSourceParameterId() != null) { return resolveParameterValues(run).getOrDefault(link.getSourceParameterId(), ""); }`. В `PipelineService.resolveInstructionText` (`PipelineService.java:404-413`, путь pipeline_get) — для не-MD_FILE возвращать `PipelineStepText.baseText(step)` вместо `step.getPromptText()`.

- [ ] **Step 4: Run tests, verify PASS.** `./gradlew test`.
- [ ] **Step 5: Commit.** `git commit -am "feat: resolve parameter data links and compose agent-task instructions at run time"`.

---

### Task 4: Фронтенд — типы, цветные пины, карточка AGENT_TASK

**Files:**
- Modify: `ui/src/api/types.ts`
- Modify: `ui/src/components/PipelineStepNode.vue`
- Modify: `ui/src/styles/main.css`

**Interfaces:**
- Consumes: бэкенд-DTO из Task 1.
- Produces: `export type PipelineOutputType = 'STRING' | 'NUMBER' | 'BOOLEAN'`; `PipelineUpsertOutput { name; type: PipelineOutputType }`; `PipelineOutputView` + `type`; `PipelineStepContentType` + `'AGENT_TASK'`; `PipelineUpsertStep` + `agentContext: string | null; agentExpectedResult: string | null`; `PipelineStepView` + те же; `PipelineUpsertParameterLink { token: string; parameterName: string; targetStepIndex: number | null }`; `PipelineParameterLinkView { id: number; token: string; parameterName: string; targetStepOrderIndex: number | null; targetStepTitle: string | null }`; `PipelineUpsertRequest` + `parameterLinks: PipelineUpsertParameterLink[]`; `PipelineDetail` + `parameterLinks: PipelineParameterLinkView[]`; `export const PIN_COLORS: Record<PipelineOutputType, string> = { STRING: '#eab308', NUMBER: '#3b82f6', BOOLEAN: '#a855f7' }` (в `ui/src/lib/` новый файл `pinColors.ts` или внутри types.ts — выбрать `ui/src/lib/pinColors.ts`).

- [ ] **Step 1: types.ts** — внести все типы из Interfaces выше (зеркально бэкенду, файл сам требует синхронизации в шапке). Создать `ui/src/lib/pinColors.ts` с `PIN_COLORS`.
- [ ] **Step 2: PipelineStepNode.vue** — в строке выхода (`.pipeline-card-output-row`, текущие строки ~128-140) добавить компактный селект типа `<select v-model="output.type" :disabled="data.readonly" class="pipeline-card-type-select nodrag"><option value="STRING">S</option><option value="NUMBER">N</option><option value="BOOLEAN">B</option></select>` и красить пин `:style="{ background: PIN_COLORS[output.type] }"`. Добавить ветку `v-else-if="data.step.contentType === 'AGENT_TASK'"` в тело карточки: textarea «Цель» (v-model `data.step.promptText`), textarea «Контекст» (v-model `data.step.agentContext`), textarea «Ожидаемый результат» (v-model `data.step.agentExpectedResult`), плейсхолдеры соответственно `Что должен сделать агент`, `Что агенту нужно знать`, `Как выглядит готовый результат`. AGENT_TASK показывает секции референс-файла и выходов, как PROMPT (обновить оба существующих `v-if="... === 'PROMPT' || ... === 'MD_FILE'"` до включения AGENT_TASK, а секцию выходов AGENT_TASK уже покрывает — она для всех кроме CONDITION).
- [ ] **Step 3: main.css** — шапка `.pipeline-card-agent_task` тонируется (по паттерну существующих `color-mix`-тонировок condition/variable, цвет — акцентный синий `#3b82f6`); стиль `.pipeline-card-type-select` (компактный, ~34px, как input'ы карточки).
- [ ] **Step 4: Все места создания шагов/выходов на доске и в билдере** — `PipelineBoardView.vue` (функции добавления шага/выхода) и `PipelineBuilderView.vue` (загрузка) дополнить `type: 'STRING'` у новых выходов, `agentContext: null, agentExpectedResult: null` у новых шагов и `parameterLinks` при загрузке/сохранении (`updatePipeline` теперь требует поле — прокинуть загруженные `parameterLinks` round-trip в metadata-экране, аналогично «steps грузятся, но не редактируются»). Кнопку/тип AGENT_TASK в палитру типов доски добавить с подписью «Агентская задача».
- [ ] **Step 5: Verify.** Run: `cd ui && npm run type-check && npx vite build`. Expected: обе команды зелёные.
- [ ] **Step 6: Commit.** `git commit -am "feat(ui): typed colored pins and agent-task card"`.

---

### Task 5: Доска — входной блок параметров и жёсткая проверка связей

**Files:**
- Create: `ui/src/components/PipelineParamsNode.vue`
- Modify: `ui/src/views/PipelineBoardView.vue`
- Modify: `ui/src/styles/main.css`

**Interfaces:**
- Consumes: `PIN_COLORS`, `parameterLinks`-типы из Task 4; паттерн `data`-контракта узлов (`PipelineStepNode.vue`).
- Produces: узел `id: 'params'`, `type: 'pipelineParams'`, source-пины `param-<name>`; ребро параметрной связи `id: 'plink-<token>'`.

- [ ] **Step 1: PipelineParamsNode.vue** — компонент по паттерну `PipelineStepNode.vue` (`defineProps<NodeProps<{...}>>()`, поля под `data`!): `data.parameters: PipelineParameterView[]`, `data.readonly?: boolean`. Карточка «Входные параметры»; на строку параметра: имя, бейдж типа, `<Handle :id="`param-${p.name}`" type="source" :position="Position.Right" class="pipeline-handle pipeline-handle-inline" :style="{ background: PIN_COLORS[p.type] }" />` (строка `position: relative`, как output-строки в PipelineStepNode). Если параметров нет — текст «Добавьте параметры в настройках пайплайна».
- [ ] **Step 2: Board — узел и рёбра.** В `flowNodes` добавить узел `{ id: 'params', type: 'pipelineParams', position: paramsNodePosition.value, class: 'pipeline-node', data: { parameters: pipeline.parameters, readonly: false } }`; `paramsNodePosition` — ref, инициализируемый `{ x: minStepX - 320, y: minStepY }` (при нуле шагов `{x: 0, y: 0}`), обновляется в существующем обработчике перетаскивания узлов (позиция в рамках сессии, не персистится). Зарегистрировать `pipelineParams: PipelineParamsNode` в `:node-types`. В `flowEdges` добавить рёбра параметрных связей: `{ id: 'plink-<token>', source: 'params', sourceHandle: 'param-<parameterName>', target: String(targetStepIndex), targetHandle: 'data-in', class: 'pipeline-data-edge', style: { strokeDasharray: '4 4', stroke: PIN_COLORS[тип параметра] } }`. Хранить связи в `paramLinks = ref<PipelineUpsertParameterLink[]>` (грузить из `pipeline.parameterLinks`, отправлять в `save()`; при `removeStep` фильтровать/ремапить `targetStepIndex` так же, как routes/dataLinksOut).
- [ ] **Step 3: onConnect.** Ветка `connection.source === 'params'`: извлечь `parameterName` из `sourceHandle` (`param-`-префикс), создать `{ token: crypto.randomUUID(), parameterName, targetStepIndex }`, добавить в `paramLinks`; если цель — не CONDITION, дописать `\n{{data:<token>}}` в её `promptText` (тот же паттерн, что у шаговых связей, строки ~285-294). **Жёсткая проверка** для ОБОИХ видов data-связей: если цель — CONDITION с числовым оператором и тип источника (тип выхода или тип параметра) не `NUMBER` — связь не создаётся, показать ненавязчивую плашку-предупреждение (ref `connectError`, absolute-плашка внизу канваса, исчезает через 4с) с текстом «Условие сравнивает числа — нужен вход типа NUMBER». Удаление параметрного ребра — по клику на ребро `plink-*` показать существующий floating-оверлей с кнопкой удаления (удалить из `paramLinks`; из `promptText` цели вычистить строку `{{data:<token>}}`).
- [ ] **Step 4: wiredInputs.** `wiredInputsFor` дополнить параметрными входами: `{ token, sourceStepTitle: 'Параметры', sourceOutputName: parameterName }`.
- [ ] **Step 5: Verify.** `npm run type-check && npx vite build` зелёные.
- [ ] **Step 6: Commit.** `git commit -am "feat(ui): input parameters node with typed pins and strict condition wiring"`.

---

### Task 6: Доска — контекстное меню добавления блоков

**Files:**
- Modify: `ui/src/views/PipelineBoardView.vue`
- Modify: `ui/src/styles/main.css`

**Interfaces:**
- Consumes: существующая функция добавления шага на доске (найти в `PipelineBoardView.vue` и переиспользовать, параметризовав позицией и contentType).

- [ ] **Step 1: Меню.** `@pane-context-menu` у `<VueFlow>` (event.preventDefault) открывает меню: ref `contextMenu = ref<{ x: number; y: number; flowX: number; flowY: number } | null>`, экранные координаты для позиционирования div, `screenToFlowCoordinate` (из `useVueFlow()`) — для позиции нового узла. Меню: input поиска (autofocus) + список 5 типов: Промпт («Инструкция для Claude»), MD-файл («Инструкция из файла»), Условие («Ветвление true/false»), Переменная («Литеральное значение»), Агентская задача («Цель, контекст и ожидаемый результат»). Фильтрация по подстроке (регистронезависимо, по названию и описанию). Клик — создать шаг данного типа в `{flowX, flowY}` и закрыть; Esc и клик мимо (обработчик `@pane-click` + `keydown.esc` на input) — закрыть.
- [ ] **Step 2: Стили** — `.pipeline-context-menu` (absolute, панель как существующий floating-оверлей: elevated фон, border, rounded-xl, shadow).
- [ ] **Step 3: Verify.** `npm run type-check && npx vite build`.
- [ ] **Step 4: Commit.** `git commit -am "feat(ui): canvas context menu for adding pipeline blocks"`.

---

### Task 7: Read-only представления, SKILL.md, финальная сверка

**Files:**
- Modify: `ui/src/views/PipelineView.vue`
- Modify: `ui/src/views/PipelineRunView.vue`
- Modify: `.claude/skills/pipelines/SKILL.md`

**Interfaces:**
- Consumes: `PipelineParamsNode` (Task 5), `PIN_COLORS`, `parameterLinks` из `PipelineDetail`.

- [ ] **Step 1: Оба read-only вью** — добавить узел `params` (`data.readonly: true`, позиция `minX - 320, minY`), зарегистрировать `pipelineParams` в `:node-types`, добавить рёбра `plink-*` (пунктир цвета типа параметра, как на доске), прокинуть `agentContext/agentExpectedResult` и `outputs` (с типами) в `data.step` узлов (структура `data.step` уже есть — дополнить поля). В `PipelineRunView` то же + существующая статусная логика не меняется.
- [ ] **Step 2: SKILL.md** — в раздел про `resolvedInstructionText` добавить одно предложение: сервер также подставляет значения параметров в `{{data:...}}`-токены параметрных связей, а шаги AGENT_TASK приходят уже собранными из цели/контекста/ожидаемого результата — исполнять их как обычную инструкцию.
- [ ] **Step 3: Полная сверка.** Run: `cd ui && npm run type-check && npx vite build && cd .. && ./gradlew test`. Expected: всё зелёное.
- [ ] **Step 4: Commit.** `git commit -am "feat(ui): params node in read-only views; document constructor semantics in pipelines skill"`.
