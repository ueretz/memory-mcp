# memory-mcp

MCP-сервер для Claude Code, дающий агенту **долговременную память поверх PostgreSQL** вместо
плоских markdown-файлов. Память организована как на GitHub/GitLab: проект → общий ("common")
контекст продукта + отдельные папки задач, плюс отдельный тип записей — автоматически
построенный граф расположения файлов/классов в проекте, чтобы агент не грепал файловую систему.
Всё это доступно как самому агенту — через 27 MCP-инструментов, так и человеку — через локальный
веб-дашборд с навигацией в стиле GitLab и страницами записей в стиле Confluence.

Поверх самой памяти выросли ещё три подсистемы: **папки** для группировки записей внутри scope,
**доска подзадач** (`agent_task_*`) с атомарным захватом и параллельным исполнением по субагентам,
и **пайплайны** — собранные руками в дашборде графы шагов с ветвлениями, циклами и передачей
данных между шагами, которые агент прогоняет как чеклист. Плюс страницы `/stats` (журнал
обращений к памяти) и `/settings` (флаги экспериментальных фич).

## Зачем это нужно

Встроенная память Claude Code — это markdown-файлы с YAML frontmatter на диске плюс индекс
`MEMORY.md`. Это нормально работает для одного пользователя, но:
- нет структурированного поиска/фильтрации — агент вынужден читать файлы, чтобы понять, что внутри;
- нет визуализации связей между записями и нет понятия "проект → задача";
- агент не знает, где физически лежат классы/файлы, и вынужден грепать файловую систему заново
  в каждой сессии;
- нет единой точки для нескольких агентов/проектов, если понадобится расшириться.

`memory-mcp` заменяет файловое хранилище на Postgres, сохраняя категории записей
(`USER/FEEDBACK/PROJECT/REFERENCE/LOCATION/REPORT`) и идею связей между ними, но даёт агенту
типизированный API, иерархию проект/задача, автоматический индекс кода и дашборд для просмотра.
Отдельно — тип `REPORT`: агент может собрать полноценный HTML-отчёт и сохранить его в память
как готовую страницу вместо файла в проекте, а человек открывает его в дашборде или скачивает
как PDF.

## Стек технологий

| Слой | Технология | Версия | Зачем |
|---|---|---|---|
| Язык/платформа | Java | 25 | LTS, современный синтаксис (records, pattern matching) |
| Фреймворк | Spring Boot | 4.1.0 | DI, автоконфигурация, embedded Tomcat для дашборда |
| MCP-протокол | Spring AI MCP Server Starter (`spring-ai-starter-mcp-server-webmvc`) | 2.0.0 (spring-ai-bom) | Аннотационный API (`@McpTool`/`@McpToolParam`) поверх `io.modelcontextprotocol.sdk`, транспорт streamable HTTP (`/mcp`) на том же embedded Tomcat, что и дашборд |
| БД | PostgreSQL | 17 (alpine, в Docker) | Реальный сервер с полнотекстовым поиском (`tsvector`/GIN) и запасом на рост нагрузки |
| ORM | Spring Data JPA / Hibernate | (через Spring Boot BOM) | Entities, репозитории, нативные запросы для FTS |
| Миграции схемы | Flyway (+ `flyway-database-postgresql`, + `spring-boot-flyway`) | 12.4.0 | Версионируемая схема БД (V1-V3), накатывается автоматически при старте |
| Пул соединений | HikariCP | (входит в Spring Boot) | Стандартный пул для JDBC |
| Сборка | Gradle (Groovy DSL) | wrapper в репозитории | `bootJar` собирает fat-jar `memory-mcp.jar` |
| Логирование | Logback (`logback-spring.xml`) | — | Обычный цветной вывод в stdout — больше нет ограничений stdio-транспорта |
| Веб-слой (дашборд + MCP) | Spring MVC (`spring-boot-starter-web`) + embedded Tomcat | — | Один и тот же порт 8080 отдаёт и REST API дашборда, и `/mcp` |
| PDF-экспорт | Playwright (Java) + commonmark-java | 1.62.0 / 0.30.0 | Headless Chromium печатает HTML (REPORT — как есть, остальное — после рендера markdown→HTML через commonmark) в PDF |
| Фронтенд дашборда | Vue 3 (SFC, `<script setup>` + TypeScript) + Vue Router + Vite + Tailwind CSS | Vue 3.5 / Router 5 / Vite 8 / Tailwind 4 | Отдельный проект `ui/`, собирается Gradle-задачей `buildUi` и упаковывается в jar как `static/`; граф — `d3-force`; markdown — `marked` + `DOMPurify` |
| Контейнеризация | Docker (многостадийный `Dockerfile`) + Docker Compose | — | `docker compose up -d --build` поднимает и Postgres, и сам сервис (дашборд + MCP) как долгоживущий контейнер |

## Как это работает

### Модель данных

Ядро памяти — три таблицы (всего их 17; миграции `src/main/resources/db/migration/V1..V21`):

- **`memory_nodes`** — сама запись памяти: `name` (уникальный slug, до 500 символов — для
  `LOCATION`-записей это может быть полное имя класса), `type`
  (`USER`/`FEEDBACK`/`PROJECT`/`REFERENCE`/`LOCATION`/`REPORT`), `description` (короткое summary
  для дешёвых списков), `content` (полный markdown, а для `REPORT` — целиком самодостаточный
  HTML-документ), `project_scope`, `task_id` (nullable FK на `tasks`), `file_path` (nullable, для
  `LOCATION`), `created_by` (nullable, "Имя <email>", резолвится агентом из `git config`),
  `search_vector` — генерируемая колонка `tsvector` (name/description/content с весами A/B/C) с
  GIN-индексом для полнотекстового поиска.
- **`memory_edges`** — связи, извлечённые из `[[other-name]]` внутри `content` (regex поддерживает
  и точки — для `[[fully.qualified.ClassName]]`). `target_id` nullable: если запись ссылается на
  ещё не существующую сущность, связь остаётся "висячей" и **самоисцеляется** при создании нужной
  записи — `MemoryService.save()` перепарсивает ссылки при каждом сохранении.
- **`tasks`** — единица работы внутри проекта: `project_scope`, `task_key` (номер тикета/задачи),
  `title`, `source` (`MANUAL`/`JIRA`), `status` (`ACTIVE`/`DONE`). Запись в `memory_nodes` с
  `task_id = NULL` — это "общий" (common) контекст проекта; с `task_id` — контекст конкретной
  задачи. Удаление задачи каскадно удаляет её записи (FK `ON DELETE CASCADE`).

Остальные таблицы — надстройки над этим ядром:

- **`folders`** (V6) — папка внутри scope (project или task) для группировки записей, с
  опциональной вложенностью (`parent_id`); scope папки после создания неизменен.
- **`agent_tasks`** (V7-V8) — доска подзадач внутри задачи: тип, статус и опциональная
  зависимость (`depends_on_id`, self-FK), на которой держится атомарный захват
  `agent_task_claim`.
- **`usage_events`** (V5, V13) — журнал обращений к памяти (действие, запись, scope, автор,
  время), источник данных для страницы `/stats`.
- **`settings`** (V9) — key-value настройки дашборда; сейчас единственный ключ
  `feature.pipelines.enabled` — флаг экспериментальной фичи пайплайнов.
- **`pipelines`**, **`pipeline_steps`**, **`pipeline_step_routes`**, **`pipeline_step_outputs`**,
  **`pipeline_parameters`**, **`pipeline_data_links`**, **`pipeline_assets`** — определение
  пайплайна (граф шагов, ветки по outcome, выходы шагов, параметры запуска, проводка данных,
  загруженные `.md`-файлы), а **`pipeline_runs`**, **`pipeline_run_steps`**,
  **`pipeline_run_step_outputs`** — снапшот конкретного прогона (V10-V12, V14-V20). Подробнее —
  в разделе "Пайплайны".

С V21 полнотекстовый поиск индексирует `search_vector` сразу двумя словарями — `english` **и**
`russian`. До этого кириллица попадала в индекс нестеммированными лексемами, и поиск по «стенд» не
находил «стендам»; теперь каждый язык стеммируется своим конфигом, а запросная сторона
(`MemoryNodeRepository.search`) объединяет оба `plainto_tsquery` через OR.

Java-слой (`ru.iuribabalin.memorymcp`):
- `entity/` — `MemoryNode`, `MemoryEdge`, `Task`, `Folder`, `AgentTask`, `UsageEvent`, `Setting`
  плюс 10 `Pipeline*`-сущностей (JPA-сущности);
- `repository/` — `MemoryNodeRepository` (FTS с `ts_rank`, фильтрация по scope/task через
  `taskFilterMode` NONE/COMMON/TASK), `MemoryEdgeRepository`, `TaskRepository`,
  `FolderRepository`, `AgentTaskRepository`, `SettingRepository`, `UsageEventRepository` и
  10 репозиториев пайплайнов;
- `service/LinkParser` — regex-парсер `[[name]]` (буквы/цифры/`_`/`-`/`.`) из markdown;
- `service/MemoryService` — upsert с диффом рёбер и резолвом задачи по `taskKey`, get, list,
  search, graph, related, delete;
- `service/TaskService` — start (upsert)/list/close/delete задач;
- `service/AgentTaskService` — доска подзадач, включая атомарный захват (conditional UPDATE);
- `service/FolderService` — папки внутри scope, с расфайливанием записей при удалении;
- `service/ProjectService` — агрегация списка проектов со счётчиками и удаление проекта целиком;
- `service/RepositoryScanner` — обход дерева проекта и построение `LOCATION`-записей (см. ниже);
- `service/MemoryExportService` + `service/PdfRenderer` — экспорт записи в PDF/markdown (см.
  "PDF-экспорт" ниже);
- `service/PipelineService`, `PipelineRunService`, `PipelineAssetService`,
  `PipelineExportService` — определение пайплайнов и валидация графа, прогоны и продвижение по
  шагам, загруженные `.md`-файлы, экспорт/импорт в JSON;
- `service/SettingsService` — настройки и флаги фич (`isEnabled`), `service/UsageEventRecorder` +
  `service/StatsService` — запись обращений и сводка для `/stats`;
- `dto/` — `MemoryEntrySummary`/`MemoryEntryDetail` (включая
  `projectScope`/`taskKey`/`filePath`/`createdBy`), `GraphResponse`, `TaskSummary`,
  `ProjectSummary`, `FolderSummary`, `AgentTaskSummary`, `SettingSummary`, `StatsOverview`,
  `SetupInfo` и `Pipeline*`-DTO.

### Иерархия Project → Task → Записи

`projectScope` агент всегда определяет сам (по имени git-репозитория), никогда не спрашивая
пользователя. А вот про принадлежность к задаче — **всегда спрашивает явно** (см. `SKILL.md`):
если да — резолвит номер задачи через Jira-подобный MCP-инструмент (если он есть в сессии) или
спрашивает пользователя напрямую, вызывает `task_start`, и дальше пишет рабочие заметки с
`taskKey`. Общий контекст продукта (без `taskKey`) поддерживается отдельно и живёт дольше одной
задачи.

### Автоматический граф расположения файлов/классов (`RepositoryScanner`)

Отдельная подсистема не потребовалась — `LOCATION` это обычная запись памяти с `filePath`,
доступная через те же `memory_list`/`memory_search`/`memory_graph`/`memory_get`. Инструмент
`location_scan(projectScope, rootPath)` обходит дерево проекта (исключая `.git`, `build`,
`node_modules`, `.gradle`, `.idea` и т.п.), и:
- для `*.java` файлов через regex достаёт `package`, объявление типа (`class/interface/enum/record`)
  и `import`; имя записи — полное имя класса (FQCN), а импорты, указывающие на другие
  просканированные в этом же проходе классы, становятся `[[fqcn]]`-ссылками — то есть реальными
  рёбрами графа зависимостей между классами, без какой-либо ручной работы;
- остальные файлы индексируются по относительному пути (`name = "{projectScope}:{path}"`) без
  автоматических связей — этого достаточно для "где лежит X" в любом языке/проекте.

Такой подход даёт полноценный граф зависимостей классов только для Java, для прочих языков —
просто плоский, но всё равно полезный индекс "имя → путь".

### Один процесс, один транспорт: streamable HTTP

Раньше jar умел работать в двух режимах (stdio-профиль, который Claude Code запускал как
поддочерний процесс, и отдельный долгоживущий дашборд). Сейчас режим один: обычный Spring Boot
процесс с embedded Tomcat на порту 8080, который одновременно отдаёт REST API дашборда и MCP
поверх streamable HTTP (`POST /mcp`, `spring.ai.mcp.server.protocol=streamable` в
`application.yml`). Никакого поддочернего процесса — Claude Code просто ходит по URL, пока
контейнер поднят (`docker compose up -d`).

### MCP-инструменты

| Инструмент | Параметры | Возвращает |
|---|---|---|
| `memory_save` | `name`, `type`, `description`, `content`, `projectScope?`, `taskKey?`, `folder?`, `filePath?`, `createdBy?` | Upsert по имени, парсит `[[links]]`. Для `type=REPORT` `content` — самодостаточный HTML вместо markdown. `folder` — имя существующей папки (см. `folder_create`), без него запись сохраняется в корне своего scope |
| `memory_get` | `name` | Полная запись + `linkedTo`/`linkedFrom` |
| `memory_list` | `type?`, `projectScope?`, `taskKey?`, `folder?`, `limit?`, `offset?` | Дешёвый список (без содержимого); без `taskKey` — только common-записи проекта. Без `folder` — только корень scope (содержимое папок скрыто, как в файловом менеджере) |
| `memory_search` | `query`, `type?`, `projectScope?`, `taskKey?`, `folder?`, `limit?` | Полнотекстовый поиск, тот же дешёвый формат. В отличие от `memory_list`, без `folder` ищет по всему scope, включая записи внутри папок |
| `memory_graph` | `type?`, `projectScope?`, `taskKey?` | `{nodes, edges}` для визуализации; всегда весь scope, папки не фильтрует |
| `memory_related` | `name`, `depth?` | Записи, связанные напрямую (сейчас только depth=1) |
| `memory_delete` | `name` | Удаление записи и её связей (каскад по FK) |
| `folder_create` | `projectScope`, `taskKey?`, `name`, `description`, `parentFolder?`, `createdBy?` | Создать/обновить папку (upsert по имени); scope (project/task) после создания неизменен, `parentFolder` должен быть в том же scope |
| `folder_list` | `projectScope`, `taskKey?`, `parentFolder?` | Список папок верхнего уровня (или дочерних для `parentFolder`) в данном scope |
| `task_start` | `projectScope`, `taskKey`, `title?`, `source?` | Создать/возобновить задачу (upsert) |
| `task_list` | `projectScope` | Список задач проекта |
| `task_close` | `projectScope`, `taskKey` | Пометить задачу выполненной |
| `location_scan` | `projectScope`, `rootPath` | Индексирует файлы/классы проекта как `LOCATION`-записи с автоматическими рёбрами для Java |
| `agent_task_create` | `projectScope`, `taskKey`, `title`, `type`, `description?`, `dependsOnId?` | Создать подзадачу на доске задачи (`type`: `ANALYSIS`/`IMPLEMENTATION`/`TESTING`/`REVIEW`/`REPORTING`), статус `TODO` |
| `agent_task_list` | `projectScope`, `taskKey`, `type?`, `status?`, `claimable?` | Список подзадач задачи с опциональными фильтрами |
| `agent_task_update` | `projectScope`, `taskKey`, `agentTaskId`, `status?`, `title?`, `description?` | Сдвинуть статус подзадачи и/или дополнить аналитику |
| `agent_task_delete` | `projectScope`, `taskKey`, `agentTaskId` | Удалить ошибочную/дублирующую подзадачу |
| `agent_task_claim` | `projectScope`, `taskKey`, `agentTaskId` | Атомарно захватить `TODO`-подзадачу (`TODO → IN_PROGRESS`) для безопасной работы нескольких независимых сессий на одной доске; падает, если уже захвачена или зависимость (`dependsOnId`) ещё не `DONE` |
| `folder_delete` | `name` | Удалить папку (подпапки — каскадно, записи внутри — не удаляются, а расфайливаются в корень своего scope) |
| `task_delete` | `projectScope`, `taskKey` | Безвозвратно удалить задачу целиком — записи/папки/доску подзадач (в отличие от `task_close`, который просто помечает done) |
| `project_delete` | `projectScope` | Безвозвратно удалить весь проект — все задачи, common-записи, common-папки |
| `pipeline_list` | `projectScope?` | Список пайплайнов, собранных руками в дашборде. Пайплайны общие для всех проектов, `projectScope` на фильтрацию не влияет. Нужен, чтобы превратить названное пользователем имя в точный slug |
| `pipeline_get` | `slug` | Полное определение пайплайна: упорядоченные шаги (с инлайненным содержимым загруженных `.md`), параметры, `routes` каждого шага (какой `outcome` ведёт в какой шаг) и `outputs` (какие значения шаг должен вернуть) |
| `pipeline_run_start` | `slug`, `parametersJson?` | Проверяет обязательные параметры, снапшотит текущие шаги и возвращает `runId` + список шагов и индексы активных |
| `pipeline_run_step_update` | `runId`, `orderIndex`, `status`, `note?`, `outcome?`, `outputsJson?` | Статус шага (`RUNNING`/`DONE`/`FAILED`/`SKIPPED`), ветка по `outcome` и значения выходов шага; в ответе — что делать дальше (`currentStepOrderIndex`, `null` — все пути закончились) |
| `pipeline_run_complete` | `runId`, `status` | Закрыть прогон: `DONE`/`FAILED`/`ABORTED` |
| `pipeline_run_get` | `runId` | Текущее состояние прогона — чтобы подхватить прогон, брошенный предыдущей сессией |

Все `pipeline_*` отключены, пока в `/settings` не включена экспериментальная фича **Pipelines** —
иначе инструмент отвечает ошибкой `PipelineFeatureDisabledException`.

### REST API дашборда

Без авторизации (рассчитано на localhost-only персональное использование).

Память, проекты, задачи — **только чтение**, кроме `DELETE`: это осознанное исключение из
"дашборд не пишет в память" (записи создаёт агент, удаляет человек):
`GET /api/projects`, `GET /api/projects/{scope}/tasks`,
`GET /api/projects/{scope}/tasks/{taskKey}/agent-tasks`, `GET /api/memory`,
`GET /api/memory/{name}`, `GET /api/memory/search?q=`, `GET /api/memory/graph`,
`GET /api/memory/{name}/pdf`, `GET /api/memory/{name}/html`, `GET /api/memory/{name}/markdown`,
`GET /api/folders`, `GET /api/folders/{name}`, `GET /api/stats/overview`, `GET /api/setup`,
`GET /api/setup/skills/{id}`, `DELETE /api/memory/{name}`, `DELETE /api/folders/{name}`,
`DELETE /api/projects/{scope}/tasks/{taskKey}`, `DELETE /api/projects/{scope}`.

Пайплайны и настройки — **читаются и пишутся** через дашборд: пайплайн целиком авторится руками
в UI-конструкторе, поэтому здесь полный CRUD. `GET/POST /api/pipelines`,
`GET/PUT/DELETE /api/pipelines/{slug}`, `GET /api/pipelines/{slug}/runs`,
`GET /api/pipelines/{slug}/export` + `POST /api/pipelines/import` (перенос пайплайна между
инстансами одним JSON), `GET /api/pipeline-runs/{id}`,
`POST /api/pipeline-assets` + `GET /api/pipeline-assets/{id}` (загруженные `.md`-файлы шагов),
`GET /api/settings`, `PUT /api/settings/{key}`.

`ApiExceptionHandler` превращает отсутствие записи/задачи/папки/пайплайна/прогона/шага в
HTTP 404, занятый slug пайплайна — в 409, экспорт markdown у `REPORT`-записи, невалидные
параметры и граф пайплайна, незнакомую версию формата импорта и неизвестный outcome/output шага —
в 400, а сбой рендера PDF — в 500 (вместо голого 500 без объяснения).

### Веб-дашборд

SPA на Vue 3 (`ui/`, history-роутинг через `vue-router`; `SpaForwardController` отдаёт
`index.html` на прямых заходах по `/setup` и `/p/**`):
- `/` — сетка карточек проектов + сводка (проекты / common-записи / задачи);
- `/p/{projectScope}` — страница проекта: секции "Common" (с фильтром по типу записи) и "Tasks"
  (активные списком, выполненные — под сворачиваемым блоком);
- `/p/{projectScope}/t/{taskKey}` — страница задачи со списком её записей;
- `/p/{projectScope}/e/{entryName}` и `/p/{projectScope}/t/{taskKey}/e/{entryName}` — страница
  записи в стиле Confluence: markdown рендерится через `marked` (GFM: таблицы, чеклисты, код) и
  санитизируется `DOMPurify`; `[[wiki-links]]` — собственное inline-расширение `marked`, которое
  резолвит имя в реальную связь записи (нерезолвленные видны как приглушённый чип), плюс блоки
  "Links to"/"Linked from". На любой странице записи есть кнопка **Download PDF**
  (`GET /api/memory/{name}/pdf`); для `REPORT` — ещё и **Download HTML**
  (`GET /api/memory/{name}/html`, тот же самодостаточный документ без рендера через Chromium), а
  для не-REPORT записей — **Download .md** (`GET /api/memory/{name}/markdown`);
- `/p/{projectScope}/e/{entryName}/report` и `/p/{projectScope}/t/{taskKey}/e/{entryName}/report`
  — записи `type=REPORT` открываются не инлайново, а на отдельной full-bleed странице (роут с
  `meta.bare`, `App.vue` пропускает сайдбар/хедер/колонку контента для неё): весь экран занимает
  `<iframe sandbox="allow-scripts" srcdoc="...">` с сохранённым HTML, сверху только тонкий бар
  (имя, тип, переключатель тёмной/светлой темы для превью самого отчёта — независимый от темы
  дашборда, "Download HTML", "Download PDF", ссылка назад). На обычной странице записи для REPORT
  вместо контента — карточка-ссылка "Open report" на эту страницу;
- `/p/{projectScope}/graph` и `/p/{projectScope}/t/{taskKey}/graph` — кнопка **Graph** на странице
  проекта/задачи открывает силовой граф записей на `d3-force` (SVG): перетаскивание узлов, зум и
  панорамирование (`d3-zoom`), радиус узла зависит от числа связей, подсветка соседних рёбер при
  наведении, фильтр по типу (включая `LOCATION` — тот же граф классов, что строит `location_scan`,
  но визуально), клик по узлу ведёт на страницу записи;
- `/p/{projectScope}/f/{folderName}` — страница папки: записи внутри неё и вложенные папки;
- `/pipelines` — список пайплайнов (видна, когда фича включена в `/settings`), `/pipelines/new` и
  `/pipelines/{slug}/edit` — визуальный конструктор графа шагов, `/pipelines/{slug}` — карточка
  пайплайна с историей прогонов, `/pipelines/{slug}/board` — full-bleed доска прогона,
  `/pipelines/{slug}/runs/{runId}` — конкретный прогон пошагово (старые адреса
  `/p/{project}/pipelines/**` сохранены как редиректы);
- `/stats` — сводка по памяти: всего записей и обращений, активность по дням, разбивка по типам
  записей, топ самых востребованных записей (данные из `usage_events`);
- `/settings` — флаги экспериментальных фич (сейчас — Pipelines);
- `/setup` — страница подключения: команда `claude mcp add --transport http` с автоматически
  подставленным URL текущего инстанса (`http://<host>:<port>/mcp`), плюс скачивание трёх
  независимых скиллов (`memory-mcp`, `agent-task-board`, `agent-task-report` — каждый отвечает
  за свою часть: работа с памятью, оркестрация доски подзадач, сборка отчётов) с инструкцией,
  куда положить каждый (`~/.claude/skills/<id>/`, user scope — чтобы работало во всех проектах).

Общая оболочка: сайдбар со списком проектов, хлебные крошки из параметров маршрута, командная
палитра поиска по **Ctrl/⌘ + K** (живой поиск по всем записям с фильтром по типу и навигацией
стрелками), переключатель светлой/тёмной темы с запоминанием выбора. Тема и вся палитра —
CSS-переменные, перенесённые в Tailwind через `@theme inline` (`ui/src/styles/main.css`), поэтому
цвета типов/статусов задаются в одном месте.

### Пайплайны

Экспериментальная фича, выключенная по умолчанию — включается тумблером на `/settings`
(`feature.pipelines.enabled`), пока он выключен все `pipeline_*`-инструменты отвечают ошибкой.

Пайплайн — это **именованный граф шагов, собираемый только руками** в конструкторе дашборда
(`/pipelines/new`); агент его не авторит, а исполняет. Пайплайны общие для всех проектов: один и
тот же список приходит из любого репозитория. Шаг (`pipeline_steps`) — один из 7 типов:

| Тип | Кто исполняет | Что делает |
|---|---|---|
| `PROMPT` | агент | Инструкция агенту текстом, прямо в шаге |
| `MD_FILE` | агент | То же, но содержимое загружено файлом (`pipeline_assets`) и инлайнится в `pipeline_get` — отдельная скачка не нужна |
| `CONDITION` | агент (`PROMPT`) / сервер (`VALUE`) | Ветвление: либо вопрос агенту (`conditionMode=PROMPT`), либо сравнение значения сервером (`conditionMode=VALUE` + оператор `EQUALS`/`GREATER_THAN`/`LESS_THAN`/`GREATER_OR_EQUAL`/`LESS_OR_EQUAL`) |
| `VARIABLE` | сервер | Значение, заданное в конструкторе: шаг обязан объявить ровно один output, сервер сразу помечает его `DONE` и подставляет значение дальше |
| `PARALLEL` | сервер | Фан-аут: активирует сразу все свои ветки |
| `JOIN` | сервер | Ждёт, пока придёт каждая входящая ветка (`arrived_count`) |
| `LOOP` | сервер | Возвращает исполнение на более ранний шаг, пока не исчерпан `max_iterations` (`execution_count` считает итерации) |

То есть агенту достаются только `PROMPT`, `MD_FILE` и `CONDITION` в режиме `PROMPT` — остальные
типы `PipelineRunService` разрешает сам и сразу продвигает прогон дальше, агент их даже не видит
как активные.

Поэтому прогон (`pipeline_runs`) отслеживает **множество** активных шагов (`active_steps_json`), а
не один указатель; `current_step_order_index` остаётся как "первый активный" для совместимости.
Переходы между шагами задаются `pipeline_step_routes` (ключ `outcome` → целевой шаг), а передача
данных — `pipeline_step_outputs` (что шаг обязан вернуть) и `pipeline_data_links` (токен в
промпте целевого шага ← выход предыдущего шага или параметр запуска). Параметры запуска
(`pipeline_parameters`, типы `STRING`/`NUMBER`/`BOOLEAN`, обязательность, значение по умолчанию)
валидируются в `pipeline_run_start` — агент обязан спросить их у пользователя заранее.

Протокол исполнения для агента: `pipeline_list` → `pipeline_get` (узнать шаги, параметры и
маршруты) → `pipeline_run_start` → для каждого активного шага `pipeline_run_step_update` →
`pipeline_run_complete`. Брошенный прогон подхватывается через `pipeline_run_get`. На стороне
агента это оформлено скиллом `pipelines` (`.claude/skills/pipelines/`) — в отличие от трёх
остальных он не раздаётся через `/setup`, а живёт только в этом репозитории.

Пайплайн переносится между инстансами одним JSON: `GET /api/pipelines/{slug}/export` →
`POST /api/pipelines/import` (`PipelineExportService` версионирует формат и отказывается
импортировать незнакомую версию).

### PDF-экспорт

`GET /api/memory/{name}/pdf` печатает запись в PDF через headless Chromium (Playwright):
для `REPORT` — как есть (это уже цельный HTML-документ), для остальных типов —
`MemoryExportService` сначала рендерит markdown в HTML через `commonmark-java` и оборачивает в
минимальную печатную стилизацию. Браузер поднимается лениво при первом запросе и живёт в одном
выделенном потоке (`PdfRenderer`) — Playwright требует, чтобы все вызовы шли с того потока, что
его создал. `newPage` эмулирует светлую цветовую схему, а для `REPORT` дополнительно форсится
`data-theme="light"` (см. `forceLightTheme`) — экспорт не зависит от темы, в которой отчёт
сохранился или просматривался.

Многостраничные `REPORT`-отчёты (например, HTML-планы скилла task-planner с вкладками и
flow-диаграммами) печатаются не как есть — `MemoryExportService.applyPrintLayoutFixes` перед
рендером инжектит `<style>` прямо в HTML записи, компенсируя то, что у печати нет ни клика по
вкладке, ни скролла:
- `.tab-panel`/`[role="tabpanel"]` — принудительно `display: block`, а сама панель вкладок
  скрывается (`display: none`), иначе в PDF попадала бы только та вкладка, что была активна на
  момент сохранения отчёта;
- `overflow-x: auto`-контейнеры (диаграммы, широкие блоки кода) и `<svg>` с фиксированной шириной
  больше печатной страницы — раньше просто обрезались по краю контейнера (нет скролла на бумаге);
  теперь `overflow: visible` + `svg { max-width: 100%; height: auto }` вписывают их в страницу
  вместо обрезки;
- `.flow-node`/`.flow-arrow`/`.diagram`/`.stat-tile`/`tr` и т.п. получают
  `break-inside: avoid` — без этого движок печати мог разрезать блок ровно по границе страницы
  где попало, и текст внутри узла диаграммы пропадал, оставляя только стрелку без подписи; теперь
  такой блок целиком переносится на следующую страницу.

Это эвристика по разметке (свой `.tab-panel`/`.flow-node` конвент этого проекта + общий ARIA
`role="tabpanel"`) — отчёт с другой вёрсткой ею не покрывается. Поэтому у `REPORT`-записей есть
и **`GET /api/memory/{name}/html`** — скачивание того же документа без прогона через Chromium и
без print-компромиссов вообще: открывается в браузере с рабочими вкладками, темой и
интерактивностью, как оригинал.

Разово нужно скачать сам Chromium (~300 МБ), которым управляет Playwright:

```bash
./gradlew installPlaywrightBrowsers
```

Без этого шага `/pdf` вернёт 500 с понятным сообщением, что нужно выполнить эту команду. Внутри
Docker-образа (`Dockerfile`) шаг уже встроен в сборку — для варианта A ничего делать не нужно.

### Разработка UI

Нужен **Node.js `^20.19.0 || >=22.12.0`** (`ui/.nvmrc` пинит 22). Vite 8 поставляет бандлер
нативными бинарниками как optional-зависимости с тем же ограничением по `engines`: на более старой
ноде npm их молча пропускает, и сборка падает с `Cannot find native binding`. Поэтому в
`ui/.npmrc` включён `engine-strict`, а Gradle проверяет версию ноды до запуска npm и пишет, что
делать. После апгрейда ноды: `rm -rf ui/node_modules && ./gradlew buildUi`.

```bash
cd ui
npm install
npm run dev          # http://localhost:5173, /api и /mcp проксируются на localhost:8080
npm run build        # сборка в build/ui-dist (то же самое делает ./gradlew buildUi)
npm run type-check   # vue-tsc
```

Для дев-режима нужен запущенный бэкенд (`docker compose up -d` или `./gradlew bootRun`); другой
адрес бэкенда — через `MEMORY_MCP_BACKEND=http://host:port npm run dev`.

Сборка jar (`./gradlew bootJar`) сама вызывает `npm ci` и `npm run build`. Если npm стоит через
nvm/fnm/volta и IDE его не видит, Gradle ищет его в типовых местах установки; при необходимости
можно указать явно: `./gradlew build -PnpmExecutable=/path/to/npm`. Собрать сервер без дашборда
(например, когда бандл уже готов) — `-PskipUi`.

## Запуск локально

### Вариант A: всё в Docker (рекомендуется — не нужно держать IDE/терминал открытыми)

`Dockerfile` — многостадийная сборка (`eclipse-temurin:25-jdk` компилирует jar, рантайм на
`eclipse-temurin:25-jre`). `docker-compose.yml` поднимает и Postgres, и сам сервис `app`
(дашборд + MCP-сервер — один и тот же процесс, один и тот же порт 8080), который ждёт готовности
Postgres (`healthcheck`) и переживает перезапуски (`restart: unless-stopped`) — то есть один раз
собрал и забыл, никакого `java -jar` в терминале/IDE и никакого отдельного процесса, который
Claude Code запускал бы сам.

```bash
docker compose up -d --build    # соберёт образ приложения (если менялся код) и поднимет оба сервиса
docker compose ps               # memory-mcp-postgres + memory-mcp-app, оба "Up"
docker compose logs -f app      # логи дашборда + MCP
# -> http://localhost:8080/     (проекты → задачи/common → записи, плюс /setup)
# -> http://localhost:8080/mcp  (MCP поверх streamable HTTP)

docker compose down             # остановить (данные в volume memory-mcp-pgdata сохранятся)
```

После правок в коде: `docker compose up -d --build app` пересоберёт только образ приложения.

### Вариант B: всё локально (без Docker для самого приложения)

```bash
docker compose up -d postgres        # только Postgres, на localhost:5433
./gradlew bootJar                    # соберёт build/libs/memory-mcp.jar; Flyway применит миграции при старте

java -jar build/libs/memory-mcp.jar
# -> http://localhost:8080/  и  http://localhost:8080/mcp
```

### Регистрация в Claude Code

Проще всего — открыть дашборд и зайти на страницу **Setup** (`/setup`): там уже готовая
команда с URL твоего инстанса и кнопки скачивания всех трёх скиллов. Вручную это выглядит так:

```bash
claude mcp add --scope user --transport http memory-mcp http://localhost:8080/mcp

claude mcp list   # проверить, что memory-mcp подключён
```

Никаких env-переменных и путей к java/jar в команде регистрации больше нет — сервер (Postgres,
Docker-контейнер) настраивается один раз через `docker-compose.yml`, а Claude Code просто
обращается по URL, пока контейнер поднят.

Три скилла (скачиваются со страницы `/setup` или лежат в `src/main/resources/skill/`), каждый —
за свою часть:

- **`memory-mcp`** (`~/.claude/skills/memory-mcp/SKILL.md`) — определять `projectScope` по
  git-репозиторию самостоятельно, всегда явно спрашивать про принадлежность к задаче, вести общий
  контекст продукта отдельно от задач, запускать `location_scan` вместо grep по файловой системе;
  описание задачи сохранять как MD-спеку (`memory_save` в скоупе задачи) и цеплять её
  `task_spec_set`, а прогресс дописывать в ту же запись.
- **`agent-task-board`** (`~/.claude/skills/agent-task-board/SKILL.md`) — обязательная
  авто-декомпозиция нетривиальной задачи на доску подзадач (`agent_task_*`) ещё до правок кода, с
  чекпоинтом-подтверждением плана перед стартом реализации; описание и у задачи, и у каждой
  подзадачи живёт в MD-записи, прицепленной через `task_spec_set` / `agent_task_spec_set` (в теле
  карточки остаётся строка-подпись), туда же дописывается прогресс и фактический результат;
  прогресс задачи не вводится руками, а считается как доля `DONE` на её доске; независимые
  подзадачи (без `dependsOnId` и с непересекающимися файлами) исполняются волнами — по субагенту
  на подзадачу, параллельно.
- **`agent-task-report`** (`~/.claude/skills/agent-task-report/` — распаковать zip) — сборка и
  сохранение HTML-отчёта в стиле дашборда; вызывается из `agent-task-board` или отдельно.

### Дашборд как приложение в Dock (macOS)

```bash
scripts/macos/install-dock-app.sh                                # http://localhost:8080
scripts/macos/install-dock-app.sh --url http://localhost:8091    # другой адрес инстанса
```

Скрипт собирает `~/Applications/Memory MCP.app` с иконкой дашборда (🧠) и закрепляет его в Dock.
По клику дашборд открывается отдельным окном Chrome (`--app`, без вкладок и адресной строки), а
если Chrome не установлен — в браузере по умолчанию. Иконка рисуется при сборке через Swift
(`xcode-select --install`, если тулчейна нет — останется стандартная иконка апплета). Повторный
запуск пересобирает приложение и не дублирует плитку; `--no-dock` — только собрать приложение,
`--dest` — другая папка вместо `~/Applications`. Убрать — вытащить иконку из Dock и удалить
приложение.

## Структура проекта

```
src/main/java/ru/iuribabalin/memorymcp/
├── MemoryMcpApplication.java      — точка входа (@SpringBootApplication)
├── entity/                        — MemoryNode, MemoryEdge, Task, Folder, AgentTask, Setting,
│                                     UsageEvent, Pipeline* (10 сущностей)
├── repository/                    — Spring Data репозитории + нативные FTS/edge-запросы
├── service/                       — LinkParser, MemoryService, TaskService, AgentTaskService,
│                                     FolderService, ProjectService, RepositoryScanner,
│                                     MemoryExportService, PdfRenderer, SettingsService,
│                                     StatsService, UsageEventRecorder, Pipeline*Service,
│                                     *NotFoundException, PdfRenderException, UnsupportedExportException
├── dto/                           — Summary/Detail/Graph/Task/Project/Folder/AgentTask/Stats/
│                                     Setting/Setup/Pipeline* DTO
├── mcp/                           — MemoryMcpTools, TaskMcpTools, CodeMapMcpTools, FolderMcpTools,
│                                     AgentTaskMcpTools, ProjectMcpTools, PipelineMcpTools
│                                     (27 @McpTool)
└── ui/                            — MemoryViewController, MemoryExportController,
                                      ProjectViewController, FolderViewController,
                                      StatsViewController, SettingsController, SetupController,
                                      Pipeline{,Run,Asset,Export}Controller, SpaForwardController,
                                      ApiExceptionHandler (read-only REST по памяти + CRUD по
                                      пайплайнам/настройкам + PDF/markdown/html export)

src/main/resources/
├── application.yml                — единый конфиг, MCP-транспорт streamable HTTP (protocol: streamable)
├── logback-spring.xml             — обычный цветной вывод в stdout
├── db/migration/                  — V1..V21: V1 (nodes/edges), V2 (tasks), V3 (LOCATION +
│                                     file_path), V4 (REPORT + created_by), V5/V13 (usage_events),
│                                     V6 (folders), V7-V8 (agent_tasks + dependencies),
│                                     V9 (settings), V10-V12/V14-V20 (пайплайны: assets,
│                                     определение, прогоны, routes, data links, condition mode,
│                                     parallel, loop, prompt conditions), V21 (russian FTS)
└── skill/                         — три скилла, раздаются через /api/setup/skills/{id}
    ├── SKILL.md                   — memory-mcp (одиночный файл)
    ├── agent-task-board/SKILL.md  — оркестрация доски подзадач (одиночный файл)
    └── agent-task-report/         — SKILL.md + assets/agent_task_report_template.html (zip)

ui/                                — фронтенд-проект (Vue 3 + Vite + Tailwind), собирается в static/
├── index.html                     — точка входа Vite (тема применяется до первой отрисовки)
├── vite.config.ts                 — outDir = ../build/ui-dist, прокси /api и /mcp для dev-режима
├── src/api/                       — types.ts (зеркало DTO бэкенда) + client.ts (типизированный fetch)
├── src/components/                — оболочка (сайдбар, шапка, палитра поиска, крошки) и элементы
│                                     списков/карточек, MemoryGraph.vue (d3-force), MarkdownBody.vue
├── src/views/                     — Projects/Project/Task/Folder/Entry/Report/Graph/Stats/
│                                     Settings/Setup/NotFound + Pipelines/PipelineBuilder/
│                                     Pipeline/PipelineBoard/PipelineRun
├── src/composables/               — useAsyncData (загрузка + гонки запросов), useTheme
├── src/lib/                       — links.ts (маршруты записей), markdown.ts, format.ts
└── src/styles/main.css            — дизайн-токены (светлая/тёмная) + стили markdown

.claude/skills/                    — зеркала src/main/resources/skill/ (memory-mcp, agent-task-board,
                                      agent-task-report) для догфудинга в этом репо, плюс
                                      pipelines — скилл исполнения пайплайнов, который через
                                      /setup не раздаётся (SkillAssetsInSyncTest сторожит, что
                                      зеркала не разъехались с раздаваемыми копиями)
Dockerfile                         — многостадийная сборка: ui (node:24) → jar (25-jdk) → рантайм (25-jre)
.dockerignore                      — исключает build/.gradle/.git/ui/node_modules из контекста сборки
docker-compose.yml                 — Postgres (с healthcheck) + сервис app (дашборд + MCP)
scripts/macos/                     — install-dock-app.sh + dock-icon.swift: дашборд как приложение в Dock
```

## Статус и дальнейшие шаги

Реализовано и проверено (юнит/интеграционные тесты в `src/test`, плюс ручная проверка: все 27
MCP-инструментов через прямой JSON-RPC поверх streamable HTTP, REST API, полный дашборд,
регистрация в Claude Code через URL, сканирование этого же репозитория как реальный тест
`location_scan`):

- [x] MCP-сервер поверх streamable HTTP (`/mcp`) с 27 инструментами, на одном порту с дашбордом
- [x] Postgres + Flyway-схема V1..V21 (17 таблиц), самоисцеляющиеся dangling-связи
- [x] Иерархия Project → Task → Common/записи
- [x] Дашборд на Vue 3 + Tailwind (проект `ui/`): навигация в стиле GitLab, страницы записей в стиле
      Confluence с рендерингом markdown, тёмная тема, поиск по Ctrl/⌘ + K
- [x] Страница `/setup` с готовой командой подключения (`--transport http`) и скачиванием SKILL.md
- [x] Автоматический граф расположения файлов/классов (`location_scan`), реальные Java-зависимости
- [x] `SKILL.md`, обучающий агента этому всему, включая always-ask про задачу
- [x] Docker-сборка всего сервиса (`Dockerfile` + `docker-compose.yml`), не требует IDE/терминала —
      `docker compose up -d --build`, и Claude Code подключается по URL, без запуска процессов
- [x] Графовая диаграмма на дашборде (`/p/{project}/graph`, `/p/{project}/t/{task}/graph`) — узлы/рёбра
      как настоящий силовой граф (`d3-force` + SVG), с
      перетаскиванием узлов, зумом/паном и фильтром по типу (включая `LOCATION`), а не только
      дерево/списки
- [x] Тип записи `REPORT` — агент сохраняет готовый самодостаточный HTML-отчёт в память вместо
      файла в проекте, читается на отдельной full-bleed странице (`.../e/{name}/report`) вместо
      обычной страницы записи
- [x] Экспорт любой записи в PDF (`GET /api/memory/{name}/pdf`, headless Chromium через
      Playwright, со всеми вкладками/диаграммами многостраничных REPORT-отчётов, а не только
      активной вкладкой на момент сохранения) и экспорт не-REPORT записей в исходный `.md`
      (`GET /api/memory/{name}/markdown`)
- [x] Экспорт REPORT-записи в исходный `.html` (`GET /api/memory/{name}/html`) — тот же документ
      без прогона через Chromium, надёжный фолбэк, когда печать в PDF капризничает
- [x] Поле `createdBy` — агент резолвит автора из `git config user.name`/`user.email` и передаёт
      его в `memory_save`, без вопросов пользователю
- [x] Доска подзадач агента (`agent_tasks`) внутри MCP-задачи — Jira-подобный борд с 5 типами
      подзадач (`ANALYSIS`/`IMPLEMENTATION`/`TESTING`/`REVIEW`/`REPORTING`) и 4 статусами
      (`TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`), read-only Kanban на `/p/{project}/t/{task}`,
      MCP-инструменты `agent_task_*`, и скилл `agent-task-board`, который автоматически ведёт
      задачу через весь цикл и в конце собирает HTML-отчёт (`type: REPORT`) с боковым меню и
      inline-SVG диаграммами
- [x] MD-спека задачи и подзадачи (`spec_entry_id` → FK на `memory_nodes`, `ON DELETE SET NULL`) —
      описание и фактический результат живут в записи памяти, а в задаче остаётся строка-подпись;
      MCP-инструменты `task_spec_set`, `agent_task_spec_set` и параметр `specEntryName` у
      `task_start`/`agent_task_create`, привязка проверяется на принадлежность scope. В дашборде
      спека — кликабельная карточка на странице задачи и в миниатюре на карточке подзадачи
- [x] Прогресс задачи и статус `CANCELLED` — прогресс считается на сервере как доля `DONE` на
      доске подзадач (руками не вводится, `GET /api/projects/{p}/tasks/{key}` отдаёт
      `progress{done,total,percent}` и спеку одним запросом), снаружи задача показывается кольцом
      (галочка / дуга с процентом / красный крестик), внутри — прогресс-баром с разбивкой по
      статусам доски; `task_cancel` помечает задачу отменённой, а повторный `task_start` снова
      делает её активной
- [x] Мультисессионное параллельное исполнение доски подзадач — атомарный захват `TODO` через
      `agent_task_claim` (conditional UPDATE, без гонок между независимыми сессиями), опциональная
      зависимость между подзадачами (`dependsOnId`, self-FK) и фильтр `agent_task_list(claimable:
      true)`, раздел про мультисессионный режим в скилле `agent-task-board`, 2 новых раздела отчёта
      (влияние на прод/продукт, на что обращать внимание при проверке) плюс авто-агрегация
      критичных находок в `agent-task-report`
- [x] Папки (`folders`) — группировка записей внутри scope с вложенностью, MCP-инструменты
      `folder_*`, страница папки в дашборде; удаление папки не удаляет записи, а расфайливает их
      в корень scope
- [x] Страница `/stats` — журнал обращений к памяти (`usage_events`): всего записей/обращений,
      активность по дням, разбивка по типам, топ востребованных записей
- [x] Страница `/settings` — флаги экспериментальных фич (key-value `settings`)
- [x] Пайплайны (за флагом фичи) — визуальный конструктор графа шагов в дашборде, 7 типов шагов
      (включая `PARALLEL`/`JOIN`/`LOOP` и `CONDITION` в режимах VALUE/PROMPT), ветвление по
      `outcome`, проводка данных между шагами, параметры запуска, прогоны с историей,
      экспорт/импорт пайплайна одним JSON, 6 MCP-инструментов `pipeline_*` и скилл `pipelines`
- [x] Полнотекстовый поиск по русскому тексту со стеммингом (V21) — двойная индексация
      `english` + `russian` вместо нестеммированной кириллицы
