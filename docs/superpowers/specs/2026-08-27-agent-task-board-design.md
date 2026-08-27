# Agent Task Board — доска подзадач агента внутри MCP-задачи

Дата: 2026-08-27
Статус: одобрено в брейншторме, готово к написанию implementation plan

## Проблема

Сейчас `Task` (`task_start`/`task_list`/`task_close`) — это просто "папка" для записей
памяти, привязанных к тикету. Нет способа увидеть, на какие шаги агент разбил работу над
задачей и в каком они состоянии. Пользователь хочет получить Jira-подобную доску подзадач
*внутри* MCP-задачи: агент сам декомпозирует переданное описание работы на подзадачи,
проводит их по статусам, и в конце собирает единый HTML-отчёт о выполнении.

## Область действия

Новая подсистема `agent_tasks`, полностью read-only на дашборде (см. "Решения" ниже) —
все мутации только через MCP-инструменты. Она не заменяет существующие `Task`/`MemoryNode` —
подзадача всегда живёт внутри уже существующей `Task` (тикета) и ссылается на записи памяти
через обычный markdown-текст, а не через отдельную графовую модель.

## Принятые решения (из брейншторма)

1. **Гибридная модель**: 5 фиксированных типов подзадач (категорий), но переменное число
   подзадач внутри каждого типа — агент сам решает, сколько нужно.
2. **4 статуса**: `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED`.
3. **Дашборд только для чтения** — человек наблюдает за доской, всё пишет агент через MCP.
4. **Скилл триггерится автоматически** в момент `task_start`, когда пользователь дал ключ
   задачи и содержательное (не однострочное) описание работы — агент сам заводит доску
   без отдельной команды. Для тривиальных правок/вопросов доска не создаётся.
5. **Диаграммы отчёта — inline SVG/HTML**, не PlantUML/Mermaid — тот же аргумент, что уже
   зафиксирован в скилле `task-planner`: HTML отчёта — аргумент tool-call в `memory_save`,
   JS-библиотека диаграмм там не поместится. inline SVG уже проверенный в этом репо паттерн.

## 1. Модель данных

Новая миграция `src/main/resources/db/migration/V7__add_agent_tasks.sql`:

```sql
CREATE TABLE agent_tasks (
    id             BIGSERIAL PRIMARY KEY,
    task_id        BIGINT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    title          VARCHAR(500) NOT NULL,
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('ANALYSIS','IMPLEMENTATION','TESTING','REVIEW','REPORTING')),
    status         VARCHAR(20)  NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO','IN_PROGRESS','DONE','BLOCKED')),
    description    TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_tasks_task_id ON agent_tasks (task_id);

ALTER TABLE usage_events DROP CONSTRAINT IF EXISTS usage_events_action_check;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_action_check
    CHECK (action IN ('SAVE','GET','LIST','SEARCH','GRAPH','RELATED','DELETE','TASK_START',
                       'TASK_CLOSE','FOLDER_CREATE','AGENT_TASK_CREATE','AGENT_TASK_UPDATE',
                       'AGENT_TASK_DELETE'));
```

(Проверить точный вид текущего constraint на `usage_events` перед миграцией — если Flyway
уже создал его без явного имени, найти актуальное имя через `\d usage_events` в psql, а не
предполагать `usage_events_action_check`.)

Сущность `entity/AgentTask.java` — по образцу `entity/Task.java`: enum `Type`
(`ANALYSIS, IMPLEMENTATION, TESTING, REVIEW, REPORTING`), enum `Status`
(`TODO, IN_PROGRESS, DONE, BLOCKED`), поля `id/taskId/title/type/status/description/
createdAt/updatedAt`, геттеры/сеттеры. `taskId` — обычный `Long` (FK по значению, как
`MemoryNode.taskId`), не JPA `@ManyToOne` — сохраняем стиль остального проекта (плоские
FK-поля, без ленивых связей).

`repository/AgentTaskRepository.java` (Spring Data):
- `findByTaskIdOrderByTypeAscCreatedAtAsc(Long taskId)`
- `findByTaskIdAndTypeOrderByCreatedAtAsc(Long taskId, AgentTask.Type type)`
- `findByTaskIdAndStatusOrderByCreatedAtAsc(Long taskId, AgentTask.Status status)`
- `findByIdAndTaskId(Long id, Long taskId)` — для update/delete со проверкой принадлежности
  задаче (защита от кросс-тасковых ID).

## 2. Сервисный слой

`service/AgentTaskService.java`, конструктор принимает `AgentTaskRepository` и `TaskService`
(для резолва `Task` по `projectScope`+`taskKey` — переиспользовать существующий
package-private `TaskService.resolve(projectScope, taskKey)`, расширив его видимость до
package-private, если ещё не такая — он уже `Task resolve(...)` без модификатора, значит
уже package-private и доступен из `AgentTaskService` в том же пакете `service`).

Методы:
- `create(projectScope, taskKey, title, AgentTask.Type type, String description) -> AgentTaskSummary`
  — резолвит `Task` (кидает `TaskNotFoundException`, если задачи нет — агент обязан сначала
  вызвать `task_start`), создаёт запись со статусом `TODO`.
- `list(projectScope, taskKey, Type typeFilter, Status statusFilter) -> List<AgentTaskSummary>`
- `update(projectScope, taskKey, Long agentTaskId, Status status, String title, String description) -> AgentTaskSummary`
  — все параметры кроме `agentTaskId` опциональны (null = не менять), по аналогии с частичным
  обновлением; кидает новое `AgentTaskNotFoundException`, если `id` не принадлежит этой задаче.
- `delete(projectScope, taskKey, Long agentTaskId) -> boolean`

`service/AgentTaskNotFoundException.java` — по образцу `TaskNotFoundException`/
`FolderNotFoundException`, регистрируется в `ApiExceptionHandler` как 404.

## 3. MCP-инструменты

Новый `mcp/AgentTaskMcpTools.java`, компонент с `@McpTool`, по образцу `TaskMcpTools`.
Префикс `agent_task_*` (не `task_*` — тот уже занят MCP-задачей/тикетом).

- **`agent_task_create`**`(projectScope, taskKey, title, type, description?)` — создаёт
  подзадачу со статусом `TODO`. Не идемпотентно (в отличие от `task_start`) — агент сам
  вызывает `agent_task_list` перед созданием, если хочет проверить дубли, как рекомендовано
  для `folder_create`/`task_start` в SKILL.md.
- **`agent_task_list`**`(projectScope, taskKey, type?, status?)` — список подзадач,
  опциональные фильтры.
- **`agent_task_update`**`(projectScope, taskKey, agentTaskId, status?, title?, description?)`
  — основной рабочий инструмент: двигает статус, дополняет аналитику.
- **`agent_task_delete`**`(projectScope, taskKey, agentTaskId)` — убрать ошибочную/дублирующую
  подзадачу.

Каждый вызов пишет `UsageEventRecorder` с новыми `Action.AGENT_TASK_CREATE/UPDATE/DELETE`
(добавить в enum `UsageEvent.Action` — см. миграцию выше для constraint).

`dto/AgentTaskSummary.java` — record: `id, title, type, status, description, updatedAt`.

## 4. REST (read-only) + дашборд

Новый эндпоинт в `ui/ProjectViewController.java` (или отдельный `AgentTaskViewController`,
если контроллер станет перегружен — на усмотрение реализации):

```
GET /api/projects/{projectScope}/tasks/{taskKey}/agent-tasks
```

возвращает `List<AgentTaskSummary>`. 404 если `projectScope`/`taskKey` не существует —
уже покрывается существующим `TaskNotFoundException` → `ApiExceptionHandler`.

**UI:**
- `ui/src/api/types.ts` — добавить `AgentTaskType = 'ANALYSIS'|'IMPLEMENTATION'|'TESTING'|'REVIEW'|'REPORTING'`,
  `AgentTaskStatus = 'TODO'|'IN_PROGRESS'|'DONE'|'BLOCKED'`, `AgentTaskSummary` (зеркало DTO).
- `ui/src/api/client.ts` — `fetchAgentTasks(projectScope, taskKey)`.
- Новый компонент `ui/src/components/AgentTaskBoard.vue` — 4 колонки (To Do / In Progress /
  Blocked / Done), карточки `AgentTaskCard.vue` с цветом по `type` (расширить существующую
  палитру статус/тип-цветов в `main.css`, тот же принцип, что `TypeBadge`/`StatusBadge`).
  Клик по карточке раскрывает `description` через существующий `MarkdownBody.vue` (инлайново
  под карточкой или в боковой панели — решить в implementation plan, не задавать здесь).
- `ui/src/views/TaskView.vue` — новая секция "Agent Tasks" между "Folders" и списком записей,
  показывается только если подзадачи есть (`EmptyState`, если задача есть, а подзадач ещё
  нет — сама доска ещё не создавалась).
- Пустая доска — не ошибка: `agent-task-board` мог ещё не быть вызван для этой задачи.

## 5. Скилл `agent-task-board`

Новый файл `.claude/skills/agent-task-board/SKILL.md` (+ зеркало в
`src/main/resources/skill/`, если по конвенции проекта скиллы раздаются через `/api/setup/skill` —
**уточнить в implementation plan**, отдаётся ли этот новый скилл тем же эндпоинтом или он
локальный только для этого репо/пользователя; в текущем `/setup` раздаётся один `SKILL.md`
для `memory-mcp`, значит либо расширяем существующий SKILL.md новым разделом, либо описываем
в plan, как раздать второй скилл через дашборд).

**Триггер** (frontmatter `description`): "Use automatically right when `task_start` is called
with a substantive multi-step description of work — decomposes it into an agent task board and
drives it through completion. Skip for one-line fixes or pure questions."

**Тело скилла** — жизненный цикл:

1. После `task_start` — если описание работы тянет на несколько шагов (не тривиальная правка),
   создать ANALYSIS-подзадачу (`agent_task_create`, `type: ANALYSIS`), перевести в
   `IN_PROGRESS`, провести анализ проекта и составить план (переиспользовать пайплайн
   `task-planner`, если задача достаточно велика, либо более лёгкий анализ для задач попроще —
   решение эскалации оставить на implementation plan), записать план в `description` через
   `agent_task_update`, статус `DONE`.
2. Создать IMPLEMENTATION-подзадачи — по одной на шаг плана (`agent_task_create` с
   `type: IMPLEMENTATION` для каждого шага). Перед началом каждой — `IN_PROGRESS`, после —
   `DONE` с кратким summary в `description` (что сделано, какие файлы). Если шаг стопорится —
   `BLOCKED` с причиной, не тихое зависание.
3. TESTING-подзадача(и) — написание/прогон тестов, результаты в `description`.
4. REVIEW-подзадача — код-ревью (может переиспользовать существующий скилл `code-review`),
   находки и их резолюция — в `description`.
5. REPORTING-подзадача — собрать финальный отчёт (раздел 6), сохранить `memory_save(type: REPORT)`,
   статус `DONE`, затем `task_close`.

Скилл явно указывает: перед `agent_task_update` на `DONE` — всегда заполнить `description`
аналитикой результата, не оставлять пустым; таск считается протухшим, если долго висит
`IN_PROGRESS` без обновлений — но авто-детекции протухания в v1 нет (ручной YAGNI-срез).

## 6. Финальный отчёт — шаблон с боковым меню

Новый ассет `.claude/skills/agent-task-board/assets/agent_task_report_template.html` —
по образцу `task-planner/assets/report_template.html` (тот же набор CSS-классов для
`.diagram`/`.flow`/`.flow-node`/`.callout`/`.badge`/`.sequence`/`.diagram-notes` — переиспользовать
дословно, не изобретать заново), но layout — **боковое меню** (`position: sticky` sidebar
слева, разделы show/hide через якоря или маленький inline `<script>` без внешних
библиотек — та же "self-contained HTML" гарантия, что и для любого `REPORT`), разделы:

1. **Обзор** — сводка по подзадачам (сколько DONE/BLOCKED, кто/когда, ссылки на разделы)
2. **Архитектура** — итоговая структура (HTML flow-диаграммы)
3. **Диаграммы взаимодействия** — sequence-диаграммы (рукописный inline SVG, короткие подписи
   ≤24 символов + `<ol class="diagram-notes">` с деталями — те же два жёстких правила, что уже
   описаны в `task-planner/SKILL.md`, раздел "Diagram patterns")
4. **Реализация** — по каждой IMPLEMENTATION-подзадаче: что сделано
5. **Тесты** — покрытие, результаты прогона
6. **Код-ревью** — находки и резолюция
7. **Риски/заметки** — что осталось неучтённым

Заполняется тем же способом, что `report_template.html` — плейсхолдеры `{{...}}`, замена
через `Edit` (`replace_all: false`), контент собирается из `description` подзадач + прямых
наблюдений агента.

Сохраняется через `memory_save(name, type: "REPORT", content: <полный HTML>, projectScope,
taskKey, createdBy)` — вся остальная инфраструктура (страница `/e/{name}/report`, PDF/HTML-
экспорт) уже существует, ничего дополнительно строить не нужно.

## Что осознанно вне рамок v1

- Нет ручного редактирования подзадач с дашборда (по решению из брейншторма).
- Нет вложенности подзадач (подзадача подзадачи) — плоский список под `Task`.
- Нет отдельной графовой модели рёбер для `description` подзадач (просто markdown +
  `[[wiki-links]]`, рендерится, но не пишется в `memory_edges`).
- Нет авто-детекции "протухших" `IN_PROGRESS`-подзадач.
- Нет explicit `position`/drag-n-drop поля — порядок фиксированный (`type`, затем `created_at`).

## Тестирование (ориентир для implementation plan)

- Backend: unit-тесты `AgentTaskService` (create/list/update/delete, фильтры, 404 на чужой
  `agentTaskId`), интеграционный тест MCP-инструментов по образцу существующих тестов
  `TaskMcpTools`/`MemoryMcpTools` (уточнить их точное расположение в implementation plan).
- Frontend: там, где у существующих компонент (`TaskCard`, etc.) есть тесты — по аналогии для
  `AgentTaskBoard`/`AgentTaskCard`; если тестов на UI-компоненты в проекте пока нет вообще —
  не заводить их только ради этой фичи (проверить в implementation plan, есть ли вообще
  frontend test harness в `ui/`).
- Ручная проверка: поднять докер-стек, прогнать through сценарий `task_start` →
  `agent_task_create` ×N → `agent_task_update` → дашборд показывает доску → отчёт сохраняется
  и открывается на `/report`.
