# Pipeline Constructor (ComfyUI-style) — Design

Дата: 2026-09-01. Статус: одобрено пользователем (выбраны все четыре возможности, порядок определён исполнителем).

## Цель

Довести доску пайплайнов до полноценного конструктора в духе ComfyUI: пользователь собирает агентские задачи из блоков на канвасе вместо написания отдельных скиллов. Четыре возможности:

1. **Типизированные цветные пины** — у выходов шагов появляется тип (STRING/NUMBER/BOOLEAN), пины окрашены по типу, несовместимые связи не соединяются (жёсткая проверка — выбор пользователя).
2. **Входной блок параметров** — параметры пайплайна отображаются на доске как стартовый узел с типизированными выходными пинами; от пина параметра тянется data-связь в любой шаг.
3. **Блок «Агентская задача» (AGENT_TASK)** — узел со структурой «цель / контекст / ожидаемый результат», из которой сервер собирает инструкцию для Claude.
4. **Меню добавления блоков** — контекстное меню на канвасе (правый клик) со списком типов блоков и поиском, добавляющее узел в точку клика.

## Контекст (текущее состояние)

- `pipeline_parameters` уже типизированы (`PipelineParameter.Type { STRING, NUMBER, BOOLEAN }`, V11).
- `pipeline_step_outputs` — только `id, step_id, name` (V15), без типа.
- `pipeline_data_links` — `token, source_step_id, source_output_id, target_step_id` (V15); связи только шаг→шаг. Подстановка `{{data:token}}` в `PipelineRunService.resolveInstructionText` берёт значения из `pipeline_run_step_outputs`.
- Значения параметров живут только в `pipeline_runs.parameters_json`; `{{paramName}}` подставляет сам агент (SKILL.md), сервер — нет.
- `ContentType { PROMPT, MD_FILE, CONDITION, VARIABLE }`, CHECK-констрейнты переписаны в V16. Следующая миграция — **V17**.
- DTO data-связей вложены в шаг: `StepRequest.dataLinksOut[]` — у связи от параметра нет шага-источника, поэтому ей нужно отдельное место в DTO.

## Схема БД — миграция V17 (одна на всё)

```sql
-- 1. Типы выходов
ALTER TABLE pipeline_step_outputs
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'STRING'
        CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN'));

-- 2. Связи от параметров: источник — либо (step+output), либо параметр, ровно один
ALTER TABLE pipeline_data_links
    ALTER COLUMN source_step_id DROP NOT NULL,
    ALTER COLUMN source_output_id DROP NOT NULL,
    ADD COLUMN source_parameter_id BIGINT REFERENCES pipeline_parameters (id) ON DELETE CASCADE,
    ADD CONSTRAINT pipeline_data_links_source_check CHECK (
        (source_step_id IS NOT NULL AND source_output_id IS NOT NULL AND source_parameter_id IS NULL)
        OR (source_step_id IS NULL AND source_output_id IS NULL AND source_parameter_id IS NOT NULL)
    );

-- 3. AGENT_TASK: новый тип контента + структурные поля
ALTER TABLE pipeline_steps
    DROP CONSTRAINT pipeline_steps_content_type_check,
    ADD COLUMN agent_context TEXT,
    ADD COLUMN agent_expected_result TEXT,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK'));

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK'));
```

## Бэкенд

### Типизированные выходы

- `PipelineStepOutput` получает `enum Type { STRING, NUMBER, BOOLEAN }` (свой вложенный enum, зеркально `PipelineParameter.Type`) и поле `type`.
- `OutputRequest(name, type)`, `PipelineOutputView(id, name, type)`; тип обязателен в запросе (null → 400).
- **Жёсткая проверка при апсерте** (валидация там, где типы реально имеют значение):
  - CONDITION с числовым оператором (`GREATER_THAN/LESS_THAN/GREATER_OR_EQUAL/LESS_OR_EQUAL`) требует, чтобы его единственная входящая связь имела источник типа NUMBER (выход шага или параметр). `EQUALS` принимает любой тип.
  - VARIABLE: его единственный выход получает тип, выбранный автором (по умолчанию STRING).
- Существующие пайплайны: DEFAULT 'STRING' в миграции покрывает старые строки.

### Связи от параметров

- `PipelineDataLink` получает nullable `sourceParameterId`; `sourceStepId/sourceOutputId` становятся nullable (инвариант «ровно один источник» — CHECK выше + валидация).
- **DTO — новый top-level список**, не внутри шагов: `PipelineUpsertRequest.parameterLinks: List<ParameterLinkRequest(token, parameterName, targetStepIndex)>`; в `PipelineDetail` — `parameterLinks: List<PipelineParameterLinkView(id, token, parameterName, targetStepOrderIndex, targetStepTitle)>`.
- Валидация: `parameterName` существует среди параметров; `targetStepIndex` в диапазоне; токены уникальны среди всех связей (обоих видов). Проверка «источник — предок цели» для параметрных связей не нужна (параметры доступны всегда).
- `validateConditionStep`: «ровно одна входящая data-связь» теперь считает оба вида связей.
- **Подстановка в ране**: `resolveInstructionText` дополнительно резолвит параметрные связи — значение берётся из `pipeline_runs.parameters_json` (Jackson-парсинг в Map; отсутствует → `defaultValue` параметра → пустая строка). Токен тот же `{{data:token}}` — для агента ничего не меняется.
- CONDITION со входом-параметром: `resolveConditionInputValue` учится брать значение параметра из parameters_json.

### AGENT_TASK

- Семантика исполнения — как PROMPT (шаг выполняет Claude), но инструкция собирается сервером из трёх полей:
  - `promptText` — **цель** (обязательна для AGENT_TASK),
  - `agentContext` — контекст (nullable),
  - `agentExpectedResult` — ожидаемый результат (nullable).
- Композиция (и в `resolvedInstructionText` рана, и в `instructionText` у `pipeline_get`):

  ```
  ## Цель
  {promptText}

  ## Контекст
  {agentContext}          — секция опускается, если поле пустое

  ## Ожидаемый результат
  {agentExpectedResult}   — секция опускается, если поле пустое
  ```

- `{{data:token}}` подставляется ПОСЛЕ композиции — токены можно писать в любом из трёх полей.
- DTO: `StepRequest`/`PipelineStepView` получают `agentContext`, `agentExpectedResult`.
- Роуты, выходы, data-связи — как у PROMPT. `.claude/skills/pipelines/SKILL.md` — одно предложение о том, что AGENT_TASK приходит уже собранным в `resolvedInstructionText`.

## Фронтенд

### Типизированные пины

- `types.ts`: `PipelineOutputType = 'STRING' | 'NUMBER' | 'BOOLEAN'`; тип добавляется в `PipelineUpsertOutput`, `PipelineOutputView`.
- Цвета пинов (константа `PIN_COLORS`): STRING `#eab308` (жёлтый), NUMBER `#3b82f6` (синий), BOOLEAN `#a855f7` (фиолетовый). Пины data-выходов и пины параметров красятся инлайн-стилем по типу; route-пины (управление) остаются как есть (true зелёный, false красный, обычный тёмный).
- В строке выхода в карточке — селект типа рядом с именем (S/N/B, компактный).
- `onConnect` на доске: связь от data-пина в CONDITION с числовым оператором отклоняется, если тип источника не NUMBER (тихо не создаётся + короткий toast/подсказка).

### Входной блок параметров

- Специальный узел `id: 'params'`, не являющийся шагом: заголовок «Входные параметры», по строке на параметр — имя, бейдж типа, цветной source-пин `param-<name>` справа.
- Позиция вычисляется: левее самого левого шага (`minX - 320, minY`); перетаскивание в рамках сессии допустимо, позиция не персистится (вне скоупа).
- Пусто-состояние: если параметров нет — узел показывает подсказку «Добавьте параметры в настройках» со ссылкой на экран метаданных.
- Драг от пина параметра к `data-in` шага создаёт `parameterLinks`-запись `{token: uuid, parameterName, targetStepIndex}` и дописывает `\n{{data:token}}` в `promptText` цели (тот же UX, что у связей шаг→шаг); для цели-CONDITION текст не дописывается — связь становится его входом.
- Ребро параметрной связи рисуется пунктиром цвета типа параметра.
- `wiredInputs` карточки показывают и параметрные входы («← Параметры.name»).

### Карточка AGENT_TASK

- В `PipelineStepNode.vue` новая ветка `contentType === 'AGENT_TASK'`: три textarea — «Цель», «Контекст», «Ожидаемый результат» (двое последних компактнее), своя тонировка шапки.
- Выходы/референс-файл — как у PROMPT.

### Меню добавления блоков

- Правый клик по пустому канвасу (`@pane-context-menu` у VueFlow) открывает меню в точке курсора: строка поиска + список из 5 типов (Промпт, MD-файл, Условие, Переменная, Агентская задача) с короткими описаниями.
- Клик по пункту создаёт шаг в канвас-координатах клика (`screenToFlowCoordinate`). Esc/клик мимо — закрыть.
- Существующие кнопки добавления остаются.

### Read-only представления

- `PipelineView.vue` и `PipelineRunView.vue`: рендерят узел параметров (read-only), параметрные рёбра и карточки AGENT_TASK; цвета пинов по типам. Данные для узла параметров берутся из `pipeline.parameters` + `pipeline.parameterLinks`.

## Обработка ошибок

- Апсерт с неизвестным `parameterName`, дублем токена, нечисловым входом числового CONDITION, AGENT_TASK без цели → 400 с человекочитаемым сообщением (паттерн существующих валидаций `PipelineService`).
- Ран: параметр без значения и без default → пустая строка (как у нерепортнутых выходов шагов); NaN при числовом сравнении → FAILED с note (существующее поведение `evaluateCondition` сохраняется).

## Тестирование

- Юнит/интеграционные тесты бэкенда (паттерн существующих): V17-совместимость апсерта, валидации (негативные кейсы выше), параметрная подстановка в `resolveInstructionText` (значение / default / отсутствие), композиция AGENT_TASK (все секции / без опциональных), CONDITION со входом-параметром end-to-end.
- Фронтенд: `npm run type-check` + `vite build`; ручная проверка доски в докере.

## Вне скоупа

- Персистентная позиция узла параметров.
- Редактирование параметров прямо на доске (только просмотр + ссылка на настройки).
- Типы у входов шагов (цели `data-in` остаются нетипизированными, кроме правила для CONDITION).
- Loops/subgraph-узлы.
