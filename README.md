# memory-mcp

MCP-сервер для Claude Code, дающий агенту **долговременную память поверх PostgreSQL** вместо
плоских markdown-файлов. Память организована как на GitHub/GitLab: проект → общий ("common")
контекст продукта + отдельные папки задач, плюс отдельный тип записей — автоматически
построенный граф расположения файлов/классов в проекте, чтобы агент не грепал файловую систему.
Всё это доступно как самому агенту через MCP-инструменты, так и человеку — через локальный
веб-дашборд с навигацией в стиле GitLab и страницами записей в стиле Confluence.

## Зачем это нужно

Встроенная память Claude Code — это markdown-файлы с YAML frontmatter на диске плюс индекс
`MEMORY.md`. Это нормально работает для одного пользователя, но:
- нет структурированного поиска/фильтрации — агент вынужден читать файлы, чтобы понять, что внутри;
- нет визуализации связей между записями и нет понятия "проект → задача";
- агент не знает, где физически лежат классы/файлы, и вынужден грепать файловую систему заново
  в каждой сессии;
- нет единой точки для нескольких агентов/проектов, если понадобится расшириться.

`memory-mcp` заменяет файловое хранилище на Postgres, сохраняя категории записей
(`USER/FEEDBACK/PROJECT/REFERENCE/LOCATION`) и идею связей между ними, но даёт агенту
типизированный API, иерархию проект/задача, автоматический индекс кода и дашборд для просмотра.

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
| Фронтенд дашборда | Vue 3 (SFC, `<script setup>` + TypeScript) + Vue Router + Vite + Tailwind CSS | Vue 3.5 / Router 5 / Vite 8 / Tailwind 4 | Отдельный проект `ui/`, собирается Gradle-задачей `buildUi` и упаковывается в jar как `static/`; граф — `d3-force`; markdown — `marked` + `DOMPurify` |
| Контейнеризация | Docker (многостадийный `Dockerfile`) + Docker Compose | — | `docker compose up -d --build` поднимает и Postgres, и сам сервис (дашборд + MCP) как долгоживущий контейнер |

## Как это работает

### Модель данных

Три таблицы, миграции `src/main/resources/db/migration/V1..V3`:

- **`memory_nodes`** — сама запись памяти: `name` (уникальный slug, до 500 символов — для
  `LOCATION`-записей это может быть полное имя класса), `type`
  (`USER`/`FEEDBACK`/`PROJECT`/`REFERENCE`/`LOCATION`), `description` (короткое summary для
  дешёвых списков), `content` (полный markdown), `project_scope`, `task_id` (nullable FK на
  `tasks`), `file_path` (nullable, для `LOCATION`), `search_vector` — генерируемая колонка
  `tsvector` (name/description/content с весами A/B/C) с GIN-индексом для полнотекстового поиска.
- **`memory_edges`** — связи, извлечённые из `[[other-name]]` внутри `content` (regex поддерживает
  и точки — для `[[fully.qualified.ClassName]]`). `target_id` nullable: если запись ссылается на
  ещё не существующую сущность, связь остаётся "висячей" и **самоисцеляется** при создании нужной
  записи — `MemoryService.save()` перепарсивает ссылки при каждом сохранении.
- **`tasks`** — единица работы внутри проекта: `project_scope`, `task_key` (номер тикета/задачи),
  `title`, `source` (`MANUAL`/`JIRA`), `status` (`ACTIVE`/`DONE`). Запись в `memory_nodes` с
  `task_id = NULL` — это "общий" (common) контекст проекта; с `task_id` — контекст конкретной
  задачи. Удаление задачи каскадно удаляет её записи (FK `ON DELETE CASCADE`).

Java-слой (`ru.iuribabalin.memorymcp`):
- `entity/` — `MemoryNode`, `MemoryEdge`, `Task` (JPA-сущности);
- `repository/` — `MemoryNodeRepository` (FTS с `ts_rank`, фильтрация по scope/task через
  `taskFilterMode` NONE/COMMON/TASK), `MemoryEdgeRepository`, `TaskRepository`;
- `service/LinkParser` — regex-парсер `[[name]]` (буквы/цифры/`_`/`-`/`.`) из markdown;
- `service/MemoryService` — upsert с диффом рёбер и резолвом задачи по `taskKey`, get, list,
  search, graph, related, delete;
- `service/TaskService` — start (upsert)/list/close задач;
- `service/ProjectService` — агрегация списка проектов со счётчиками;
- `service/RepositoryScanner` — обход дерева проекта и построение `LOCATION`-записей (см. ниже);
- `dto/` — `MemoryEntrySummary`/`MemoryEntryDetail` (включая `projectScope`/`taskKey`/`filePath`),
  `GraphResponse`, `TaskSummary`, `ProjectSummary`, `SetupInfo`.

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
| `memory_save` | `name`, `type`, `description`, `content`, `projectScope?`, `taskKey?`, `filePath?` | Upsert по имени, парсит `[[links]]` |
| `memory_get` | `name` | Полная запись + `linkedTo`/`linkedFrom` |
| `memory_list` | `type?`, `projectScope?`, `taskKey?`, `limit?`, `offset?` | Дешёвый список (без содержимого); без `taskKey` — только common-записи проекта |
| `memory_search` | `query`, `type?`, `projectScope?`, `taskKey?`, `limit?` | Полнотекстовый поиск, тот же дешёвый формат |
| `memory_graph` | `type?`, `projectScope?`, `taskKey?` | `{nodes, edges}` для визуализации |
| `memory_related` | `name`, `depth?` | Записи, связанные напрямую (сейчас только depth=1) |
| `memory_delete` | `name` | Удаление записи и её связей (каскад по FK) |
| `task_start` | `projectScope`, `taskKey`, `title?`, `source?` | Создать/возобновить задачу (upsert) |
| `task_list` | `projectScope` | Список задач проекта |
| `task_close` | `projectScope`, `taskKey` | Пометить задачу выполненной |
| `location_scan` | `projectScope`, `rootPath` | Индексирует файлы/классы проекта как `LOCATION`-записи с автоматическими рёбрами для Java |

### REST API дашборда

Без авторизации (рассчитано на localhost-only персональное использование):
`GET /api/projects`, `GET /api/projects/{scope}/tasks`, `GET /api/memory`,
`GET /api/memory/{name}`, `GET /api/memory/search?q=`, `GET /api/memory/graph`,
`GET /api/setup`, `GET /api/setup/skill`. `ApiExceptionHandler` превращает отсутствие
записи/задачи в HTTP 404 вместо 500.

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
  "Links to"/"Linked from";
- `/p/{projectScope}/graph` и `/p/{projectScope}/t/{taskKey}/graph` — кнопка **Graph** на странице
  проекта/задачи открывает силовой граф записей на `d3-force` (SVG): перетаскивание узлов, зум и
  панорамирование (`d3-zoom`), радиус узла зависит от числа связей, подсветка соседних рёбер при
  наведении, фильтр по типу (включая `LOCATION` — тот же граф классов, что строит `location_scan`,
  но визуально), клик по узлу ведёт на страницу записи;
- `/setup` — страница подключения: команда `claude mcp add --transport http` с автоматически
  подставленным URL текущего инстанса (`http://<host>:<port>/mcp`), плюс кнопка скачивания
  `SKILL.md` с инструкцией положить его в `~/.claude/skills/memory-mcp/` (user scope — чтобы
  работало во всех проектах).

Общая оболочка: сайдбар со списком проектов, хлебные крошки из параметров маршрута, командная
палитра поиска по **Ctrl/⌘ + K** (живой поиск по всем записям с фильтром по типу и навигацией
стрелками), переключатель светлой/тёмной темы с запоминанием выбора. Тема и вся палитра —
CSS-переменные, перенесённые в Tailwind через `@theme inline` (`ui/src/styles/main.css`), поэтому
цвета типов/статусов задаются в одном месте.

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
команда с URL твоего инстанса и кнопка скачивания скилла. Вручную это выглядит так:

```bash
claude mcp add --scope user --transport http memory-mcp http://localhost:8080/mcp

claude mcp list   # проверить, что memory-mcp подключён
```

Никаких env-переменных и путей к java/jar в команде регистрации больше нет — сервер (Postgres,
Docker-контейнер) настраивается один раз через `docker-compose.yml`, а Claude Code просто
обращается по URL, пока контейнер поднят.

Скилл `SKILL.md` (скачивается со страницы `/setup` или лежит в `src/main/resources/skill/`)
нужно положить в `~/.claude/skills/memory-mcp/SKILL.md` — он учит агента: определять
`projectScope` по git-репозиторию самостоятельно, всегда явно спрашивать про принадлежность к
задаче, вести общий контекст продукта отдельно от задач, и запускать `location_scan` вместо
grep по файловой системе.

## Структура проекта

```
src/main/java/ru/iuribabalin/memorymcp/
├── MemoryMcpApplication.java      — точка входа (@SpringBootApplication)
├── entity/                        — MemoryNode, MemoryEdge, Task
├── repository/                    — Spring Data репозитории + нативные FTS/edge-запросы
├── service/                       — LinkParser, MemoryService, TaskService, ProjectService,
│                                     RepositoryScanner, *NotFoundException
├── dto/                           — Summary/Detail/Graph/Task/Project/Setup DTO
├── mcp/                           — MemoryMcpTools, TaskMcpTools, CodeMapMcpTools (11 @McpTool)
└── ui/                            — MemoryViewController, ProjectViewController,
                                      SetupController, ApiExceptionHandler (read-only REST)

src/main/resources/
├── application.yml                — единый конфиг, MCP-транспорт streamable HTTP (protocol: streamable)
├── logback-spring.xml             — обычный цветной вывод в stdout
├── db/migration/                  — V1 (nodes/edges), V2 (tasks), V3 (LOCATION + file_path)
└── skill/SKILL.md                 — скилл для агента (также раздаётся через /api/setup/skill)

ui/                                — фронтенд-проект (Vue 3 + Vite + Tailwind), собирается в static/
├── index.html                     — точка входа Vite (тема применяется до первой отрисовки)
├── vite.config.ts                 — outDir = ../build/ui-dist, прокси /api и /mcp для dev-режима
├── src/api/                       — types.ts (зеркало DTO бэкенда) + client.ts (типизированный fetch)
├── src/components/                — оболочка (сайдбар, шапка, палитра поиска, крошки) и элементы
│                                     списков/карточек, MemoryGraph.vue (d3-force), MarkdownBody.vue
├── src/views/                     — Projects/Project/Task/Entry/Graph/Setup/NotFound
├── src/composables/               — useAsyncData (загрузка + гонки запросов), useTheme
├── src/lib/                       — links.ts (маршруты записей), markdown.ts, format.ts
└── src/styles/main.css            — дизайн-токены (светлая/тёмная) + стили markdown

.claude/skills/memory-mcp/SKILL.md — зеркало skill/SKILL.md для догфудинга в этом репо
Dockerfile                         — многостадийная сборка: ui (node:24) → jar (25-jdk) → рантайм (25-jre)
.dockerignore                      — исключает build/.gradle/.git/ui/node_modules из контекста сборки
docker-compose.yml                 — Postgres (с healthcheck) + сервис app (дашборд + MCP)
```

## Статус и дальнейшие шаги

Реализовано и проверено вручную (миграции, все 11 MCP-инструментов через прямой JSON-RPC поверх
streamable HTTP, REST API, полный дашборд, регистрация в Claude Code через URL, сканирование
этого же репозитория как реальный тест `location_scan`):

- [x] MCP-сервер поверх streamable HTTP (`/mcp`) с 11 инструментами, на одном порту с дашбордом
- [x] Postgres + Flyway-схема (nodes/edges/tasks, тип LOCATION), самоисцеляющиеся dangling-связи
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
